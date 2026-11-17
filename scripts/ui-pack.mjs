#!/usr/bin/env node
// Builds + signs the UI pack (docs/flows/setup-build.md 4.4): ui/index.html + ui/assets/*.js|css -> a zip with manifest.json
// and manifest.sig (ECDSA P-256 / SHA-256 over the manifest bytes). Phones and the parent app serve it instead of their
// bundled page (android/shared/UiPack.kt verifies it).
//
//   node scripts/ui-pack.mjs --init            make the signing keys that are missing (+ write the public keys into UiPack.kt)
//   node scripts/ui-pack.mjs                   website/ui/{manifest.json,manifest.sig,pack-<ver>.zip}   (build.sh, deploy.sh)
//   node scripts/ui-pack.mjs --out DIR         same, into DIR (ui-push.sh uses a temp dir; the zip is DIR/pack.zip too)
//   node scripts/ui-pack.mjs --check           key + public key match, config sane (no output files)
//
// Keys (never committed, *.pem is git-ignored): <main checkout>/android/app/ui-pack-key.pem (signs packs) and
// ui-pack-backup-key.pem (move it OFFLINE; its public key is already in the apps, so a lost/leaked primary can be replaced
// only by an app release that lists new keys). KG_UI_KEY=/path overrides the primary. Config: scripts/ui-pack.json {minKid, minParent}.
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import zlib from "node:zlib";
import { execSync } from "node:child_process";

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const args = process.argv.slice(2);
const flag = n => args.includes(n);
const opt = n => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };

// worktrees share the main checkout's key (like signing.jks)
function mainRoot() {
  try { return path.resolve(root, execSync("git rev-parse --git-common-dir", { cwd: root, encoding: "utf8" }).trim(), ".."); } catch { return root; }
}
const keyDir = path.join(mainRoot(), "android", "app");
const KEY = process.env.KG_UI_KEY || path.join(keyDir, "ui-pack-key.pem");
const BACKUP = path.join(keyDir, "ui-pack-backup-key.pem");
const KT = path.join(root, "android", "shared", "UiPack.kt");

const pubOf = pem => crypto.createPublicKey(crypto.createPrivateKey(pem)).export({ type: "spki", format: "der" }).toString("base64");
const makeKey = file => {
  const { privateKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
  fs.writeFileSync(file, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
  console.log("created " + file);
};
const ktKeys = () => { const m = /\/\*KEYS\*\/(.*)\/\*KEYS\*\//.exec(fs.readFileSync(KT, "utf8")); return m ? [...m[1].matchAll(/"([^"]+)"/g)].map(x => x[1]) : []; };

if (flag("--init")) {
  let made = false;
  if (!fs.existsSync(KEY)) { fs.mkdirSync(path.dirname(KEY), { recursive: true }); makeKey(KEY); made = true; }
  if (!fs.existsSync(BACKUP)) { makeKey(BACKUP); made = true; }
  const keys = [pubOf(fs.readFileSync(KEY)), pubOf(fs.readFileSync(BACKUP))];
  if (made || ktKeys().join() !== keys.join()) {
    const s = fs.readFileSync(KT, "utf8").replace(/\/\*KEYS\*\/.*\/\*KEYS\*\//, "/*KEYS*/" + keys.map(k => `"${k}"`).join(", ") + "/*KEYS*/");
    fs.writeFileSync(KT, s); console.log("public keys written to android/shared/UiPack.kt: commit that file");
  }
  if (!made) process.exit(0);
  console.log(`\nBACK UP these two files (never commit, never share):\n  ${KEY}\n  ${BACKUP}  <- keep this one offline (USB / password manager)\nLose both = phones keep their bundled page until an app release carries new public keys.`);
  process.exit(0);
}

if (!fs.existsSync(KEY)) { console.error(`no signing key at ${KEY}\nrun: node scripts/ui-pack.mjs --init`); process.exit(2); }
const priv = crypto.createPrivateKey(fs.readFileSync(KEY));
if (!ktKeys().includes(pubOf(fs.readFileSync(KEY)))) { console.error("the signing key's public half is not in android/shared/UiPack.kt KEYS: no phone would accept this pack (run --init on a fresh checkout / fork)"); process.exit(2); }
const cfg = JSON.parse(fs.readFileSync(path.join(root, "scripts", "ui-pack.json"), "utf8"));
const kgCode = +/^kg\.code=(\d+)/m.exec(fs.readFileSync(path.join(root, "android", "gradle.properties"), "utf8"))[1];
if (cfg.minKid > kgCode || cfg.minParent > kgCode) { console.error(`scripts/ui-pack.json minKid/minParent (${cfg.minKid}/${cfg.minParent}) is above kg.code ${kgCode}: the release itself would not get the pack`); process.exit(2); }
if (flag("--check")) { console.log("ok: key matches UiPack.kt, minKid " + cfg.minKid + ", minParent " + cfg.minParent); process.exit(0); }

// ---- collect files: same set + names the phones serve (Safety.uiAsset: /ui/<name>.js|css, plus index.html) ----
const ui = path.join(root, "ui");
const names = ["index.html", ...fs.readdirSync(path.join(ui, "assets")).filter(f => /^[a-z0-9][a-z0-9-]{0,40}\.(js|css)$/.test(f)).map(f => "assets/" + f)];
const files = {};
for (const n of names.sort()) files[n] = fs.readFileSync(path.join(ui, n));
const hashes = Object.fromEntries(Object.entries(files).map(([n, b]) => [n, crypto.createHash("sha256").update(b).digest("hex")]));
const ver = crypto.createHash("sha256").update(Object.keys(hashes).sort().map(n => `${n}:${hashes[n]}\n`).join("")).digest("hex").slice(0, 12);

const out = path.resolve(opt("--out") || path.join(root, "website", "ui"));
fs.mkdirSync(out, { recursive: true });
// same content + same minimums as what is published: keep it (the seq must not move on every build)
let seq = Math.floor(Date.now() / 1000);
try {
  const old = JSON.parse(fs.readFileSync(path.join(out, "manifest.json"), "utf8"));
  if (old.ver === ver && old.minKid === cfg.minKid && old.minParent === cfg.minParent && fs.existsSync(path.join(out, `pack-${ver}.zip`))) {
    console.log(`ui pack ${ver} seq ${old.seq} unchanged`); if (opt("--out")) fs.copyFileSync(path.join(out, `pack-${ver}.zip`), path.join(out, "pack.zip")); process.exit(0);
  }
  if (old.seq >= seq) seq = old.seq + 1;
} catch {}
const manifest = Buffer.from(JSON.stringify({ v: 1, ver, seq, minKid: cfg.minKid, minParent: cfg.minParent, files: hashes }));
const sig = crypto.sign("sha256", manifest, priv).toString("base64");   // DER, what java.security SHA256withECDSA verifies

// ---- minimal zip writer (deflate, fixed timestamp: the same files give the same bytes) ----
const T = new Uint32Array(256).map((_, n) => { for (let k = 0; k < 8; k++) n = n & 1 ? 0xEDB88320 ^ (n >>> 1) : n >>> 1; return n >>> 0; });
const crc = b => { let c = ~0; for (const x of b) c = T[(c ^ x) & 255] ^ (c >>> 8); return ~c >>> 0; };
function zip(entries) {
  const parts = [], cen = []; let off = 0;
  for (const [name, data] of entries) {
    const nm = Buffer.from(name), def = zlib.deflateRawSync(data, { level: 9 }), c = crc(data);
    const lh = Buffer.alloc(30); lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0x0800, 6); lh.writeUInt16LE(8, 8);
    lh.writeUInt16LE(0, 10); lh.writeUInt16LE(0x21, 12); lh.writeUInt32LE(c, 14); lh.writeUInt32LE(def.length, 18); lh.writeUInt32LE(data.length, 22); lh.writeUInt16LE(nm.length, 26);
    parts.push(lh, nm, def);
    const ch = Buffer.alloc(46); ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x0800, 8); ch.writeUInt16LE(8, 10);
    ch.writeUInt16LE(0, 12); ch.writeUInt16LE(0x21, 14); ch.writeUInt32LE(c, 16); ch.writeUInt32LE(def.length, 20); ch.writeUInt32LE(data.length, 24); ch.writeUInt16LE(nm.length, 28); ch.writeUInt32LE(off, 42);
    cen.push(ch, nm); off += 30 + nm.length + def.length;
  }
  const cd = Buffer.concat(cen), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(off, 16);
  return Buffer.concat([...parts, cd, end]);
}
const z = zip([["manifest.json", manifest], ["manifest.sig", Buffer.from(sig)], ...Object.entries(files)]);

// only the newest pack stays in the folder (the manifest names it)
for (const f of fs.readdirSync(out)) if (/^pack-[0-9a-f]{12}\.zip$/.test(f)) fs.unlinkSync(path.join(out, f));
fs.writeFileSync(path.join(out, `pack-${ver}.zip`), z);
fs.writeFileSync(path.join(out, "manifest.json"), manifest);
fs.writeFileSync(path.join(out, "manifest.sig"), sig);
if (opt("--out")) fs.writeFileSync(path.join(out, "pack.zip"), z);
console.log(`ui pack ${ver} seq ${seq} (${names.length} files, ${(z.length / 1024).toFixed(0)} KB zip) -> ${path.relative(root, out) || out}`);

#!/usr/bin/env node
// Every permission the child app declares has a row in docs/permissions.md that says how each setup gets it,
// and every file the row cites really mentions it (owner 2026-11-17: "thiếu quyền thì chỉ test tới mới biết").
// CI + build.sh. Usage: node scripts/check-perms.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const rd = p => fs.readFileSync(path.join(ROOT, p), "utf8");
const doc = rd("docs/permissions.md");
const manifest = rd("android/app/src/main/AndroidManifest.xml");
const declared = [...manifest.matchAll(/<uses-permission[^>]*android:name="android\.permission\.([A-Z_]+)"/g)].map(m => m[1]);

// every source file once, by base name (scripts/, ui/, android/app/src/main/kotlin)
const files = new Map();
const walk = d => { for (const e of fs.readdirSync(path.join(ROOT, d), { withFileTypes: true })) {
  const p = path.join(d, e.name);
  if (e.isDirectory()) walk(p); else if (/\.(kt|sh|ps1|html|js|mjs)$/.test(e.name) && !files.has(e.name)) files.set(e.name, p);
} };
["android/app/src/main/kotlin", "scripts", "ui"].forEach(walk);

const bad = [];
const normal = new Set(((doc.match(/^Install-time \(no grant needed\): (.*)$/m) || [])[1] || "").split(/,\s*/).filter(Boolean));
const rows = doc.split("\n").filter(l => /^\| [^-|]/.test(l) && !l.startsWith("| Permission")).map(l => l.split("|").slice(1, -1).map(c => c.trim()));
const inTable = new Set(rows.map(r => r[0]));
for (const p of declared) if (!normal.has(p) && !inTable.has(p)) bad.push(`${p}: declared in the manifest, no row in docs/permissions.md`);
for (const p of normal) if (!declared.includes(p)) bad.push(`${p}: in the install-time list but not declared`);
for (const r of rows) {
  if (r.length !== 6) { bad.push(`row "${r[0]}": needs 6 cells`); continue; }
  const [perm, token, , ...cells] = r;
  if (!perm.startsWith("(") && !declared.includes(perm)) bad.push(`${perm}: row in docs/permissions.md but not declared`);
  cells.forEach((c, i) => {
    if (!c) bad.push(`${perm}: empty cell ${["Device Owner", "Basic + own adb", "Basic, no adb"][i]}`);
    for (const m of c.matchAll(/`([\w.-]+\.(?:kt|sh|ps1|html|js|mjs))(?:#([^`]+))?`/g)) {
      const f = files.get(m[1]);
      if (!f) { bad.push(`${perm}: cites ${m[1]}, no such file`); continue; }
      const want = m[2] || token;
      if (!rd(f).includes(want)) bad.push(`${perm}: ${m[1]} does not contain ${want}`);
    }
  });
}
if (bad.length) { console.error("Permission check failed (docs/permissions.md):\n- " + bad.join("\n- ")); process.exit(1); }
console.log(`Permission check ok: ${declared.length} declared, ${rows.length} rows`);

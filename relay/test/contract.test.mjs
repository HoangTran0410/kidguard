// Plain node (no wrangler): phone commands (Guard.kt run()) <-> worker whitelist (CMDS / ONCE) <-> docs/protocol.md.
// The worker lists short codes only ("Command codes"): one table in the page (core.js WIRE), the phone (Wire.kt) and the docs.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const rd = p => readFileSync(join(root, p), "utf8");
let pass = 0, fail = 0;
const ck = (n, c) => { console.log(c ? "PASS" : "FAIL", n); c ? pass++ : fail++; };

const guard = rd("android/app/src/main/kotlin/dev/kidguard/guard/Guard.kt");
const start = guard.indexOf("private fun run(t: String");
const body = guard.slice(start, guard.indexOf('else -> "unknown"', start));
// Guard.run routes the access family (PIN, pairing, relay link, AI token, Authenticator) to GuardAccess.run
const access = rd("android/app/src/main/kotlin/dev/kidguard/guard/GuardAccess.kt");
const accBody = access.slice(access.indexOf("fun run(t: String"), access.indexOf("else -> null"));
const phone = [...(body + "\n" + accBody).matchAll(/^\s{8}"(\w+)"\s*->/gm)].map(m => m[1]);
const js = rd("relay/src/index.js");
const arr = n => [...(js.match(new RegExp(`const ${n} = \\[([^\\]]*)\\]`)) || [, ""])[1].matchAll(/"(\w+)"/g)].map(m => m[1]);
const docs = rd("docs/protocol.md");
// the code table, three copies that must match: core.js `WIRE = {name: "code"}`, Wire.kt `"name" to "code"`, protocol.md `| name | code |`
const core = rd("ui/assets/core.js"), wireJs = Object.fromEntries([...(core.match(/const WIRE = \{([^}]*)\}/) || [, ""])[1].matchAll(/(\w+): "(\w+)"/g)].map(m => [m[1], m[2]]));
const wireKt = Object.fromEntries([...rd("android/app/src/main/kotlin/dev/kidguard/remote/Wire.kt").matchAll(/"(\w+)" to "(\w+)"/g)].map(m => [m[1], m[2]]));
const secDoc = docs.slice(docs.indexOf("### Command codes"), docs.indexOf("\n### ", docs.indexOf("### Command codes") + 5));
const wireDoc = Object.fromEntries([...secDoc.matchAll(/^\| `(\w+)` \| `(\w+)` \|/gm)].map(m => [m[1], m[2]]));
const NAME = Object.fromEntries(Object.entries(wireJs).map(([n, c]) => [c, n]));
ck("WIRE parsed (>=50)", Object.keys(wireJs).length >= 50);
ck("WIRE codes unique", Object.keys(NAME).length === Object.keys(wireJs).length);
ck("Wire.kt == core.js WIRE", JSON.stringify(wireKt) === JSON.stringify(wireJs));
ck("protocol.md Command codes == core.js WIRE", JSON.stringify(Object.entries(wireDoc).sort()) === JSON.stringify(Object.entries(wireJs).sort()));
const codesOf = n => arr(n);
for (const n of ["CMDS", "ONCE", "SHORT", "AI_CMDS"]) for (const c of codesOf(n)) ck(`worker ${n} '${c}' is a WIRE code`, c in NAME);
ck("worker REPLACE keys are WIRE codes", [...(js.match(/const REPLACE = \{([^}]*)\}/) || [, ""])[1].matchAll(/(\w+):/g)].every(m => m[1] in NAME));
for (const n of Object.keys(wireJs)) ck(`worker bundle has no command name '${n}' as a string`, !new RegExp(`["']${n}["']`).test(js.replace(/\/\/.*$/gm, "")) || ["lock", "ring", "answer", "wake", "tgCode", "pinSet"].includes(n));   // other meanings: input key, call states, FCM kind, storage key, rule key
const CMDS = codesOf("CMDS").map(c => NAME[c] || c), ONCE = codesOf("ONCE").map(c => NAME[c] || c);
for (const n of Object.keys(wireJs)) ck(`WIRE '${n}' is a worker command`, CMDS.includes(n));

// Commands deliberately LAN only (never relayed): the worker must not list them.
const LAN_ONLY = ["relay", "tailscale"];

ck("parsed phone commands (>=10)", phone.length >= 10);
ck("parsed worker CMDS (>=8) and ONCE", CMDS.length >= 8 && ONCE.length >= 3);
for (const c of CMDS) ck(`worker cmd '${c}' exists in Guard.kt`, phone.includes(c));
for (const c of ONCE) ck(`ONCE '${c}' is in CMDS`, CMDS.includes(c));
for (const c of phone) ck(`phone cmd '${c}' is in worker CMDS or the LAN-only allow-list`, CMDS.includes(c) || LAN_ONLY.includes(c));
for (const c of LAN_ONLY) ck(`LAN-only '${c}' is not in worker CMDS`, !CMDS.includes(c));
for (const c of new Set([...phone, ...CMDS])) ck(`'${c}' mentioned in docs/protocol.md`, new RegExp("(^|[\\s`])" + c + "([\\s`{:,.]|$)", "m").test(docs));
for (const c of ["reboot", "release", "unpairAll"]) ck(`destructive '${c}' is deliver-once`, ONCE.includes(c));
// every icon the page draws (ic("x"), sec(id, "x", ...), QS table) has a <symbol id="i-x"> in ui/index.html
{
  const { readdirSync } = await import("node:fs");
  const html = rd("ui/index.html"), js = readdirSync(join(root, "ui/assets")).filter(f => f.endsWith(".js") && !f.startsWith("i18n") && f !== "qrcode.js").map(f => rd("ui/assets/" + f)).join("\n");
  const names = new Set([...js.matchAll(/\bic\("([a-z0-9-]+)"/g), ...js.matchAll(/\bsec\("[^"]+",\s*"([a-z0-9-]+)"/g), ...js.matchAll(/\["[a-z]+", "[^"]*", "[^"]*", "([a-z0-9-]+)", \[/g)].map(m => m[1]));
  for (const n of names) ck(`icon '${n}' is in ui/index.html`, html.includes(`id="i-${n}"`));
}
// quiet-phone alert (relay/src/quiet.js): once per quiet spell, later at bedtime, never for a week-old phone
{
  const { quietStep, QUIET_ALERT, QUIET_BED } = await import("../src/quiet.js");
  const h = 1_000_000_000_000, m = 60e3;
  ck("quiet: 10 min = wait", !quietStep(h + 10 * m, h, 0, false).alert && quietStep(h + 10 * m, h, 0, false).next === h + QUIET_ALERT + 5000);
  ck("quiet: 31 min = alert", quietStep(h + 31 * m, h, 0, false).alert);
  ck("quiet: already alerted for this spell", !quietStep(h + 90 * m, h, h, false).alert);
  ck("quiet: new spell after it was heard", quietStep(h + 200 * m, h + 100 * m, h, false).alert);
  ck("quiet: bedtime waits 12 h", !quietStep(h + 31 * m, h, 0, true).alert && quietStep(h + QUIET_BED, h, 0, true).alert);
  ck("quiet: a week gone = no alert", !quietStep(h + 8 * 86400e3, h, 0, false).alert);
  ck("quiet: never heard = nothing", !quietStep(h, 0, 0, false).alert);
}

console.log(pass, "pass", fail, "fail");
process.exit(fail ? 1 : 0);

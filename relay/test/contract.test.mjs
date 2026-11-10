// Plain node (no wrangler): phone commands (Guard.kt run()) <-> worker whitelist (CMDS / ONCE) <-> docs/protocol.md.
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
const phone = [...body.matchAll(/^\s{8}"(\w+)"\s*->/gm)].map(m => m[1]);
const js = rd("relay/src/index.js");
const arr = n => [...(js.match(new RegExp(`const ${n} = \\[([^\\]]*)\\]`)) || [, ""])[1].matchAll(/"(\w+)"/g)].map(m => m[1]);
const CMDS = arr("CMDS"), ONCE = arr("ONCE");
const docs = rd("docs/protocol.md");

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
console.log(pass, "pass", fail, "fail");
process.exit(fail ? 1 : 0);

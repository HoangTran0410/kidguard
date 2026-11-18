#!/usr/bin/env node
// Every relay command and rule key newer than the frozen baseline (scripts/need-baseline.json, = what kid app 0.9.28 has)
// must be listed in NEED (ui/assets/kid.js) with the first kid app version that has it, so a parent whose kid runs an
// older app is told "update the kid's phone" with a button, instead of a switch that silently turns itself off
// (owner 2026-11-17). CI + build.sh. Usage: node scripts/check-need.mjs
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const rd = p => fs.readFileSync(path.join(ROOT, p), "utf8");
const base = JSON.parse(rd("scripts/need-baseline.json"));
const bad = [];

const kid = rd("ui/assets/kid.js");
const m = /^const NEED = (\[[\s\S]*?\n\]);/m.exec(kid);
if (!m) { console.error("check-need: no `const NEED = [...]` in ui/assets/kid.js"); process.exit(1); }
const NEED = vm.runInNewContext(m[1]);
const ver = (rd("android/gradle.properties").match(/^kg\.version=(.+)$/m) || [])[1] || "";
const num = v => String(v).split(".").map(x => parseInt(x, 10) || 0);
const le = (a, b) => { const x = num(a), y = num(b); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i]; return true; };

const has = { cmd: new Set(), rule: new Set() };
for (const n of NEED) {
  if (!/^\d+\.\d+\.\d+$/.test(n.v || "")) bad.push(`NEED entry with a bad version "${n.v}"`);
  else if (!le(n.v, ver)) bad.push(`NEED ${n.v} is newer than kg.version ${ver}`);
  for (const k of ["cmd", "rule"]) for (const x of n[k] || []) has[k].add(x);
}

const cmds = (/^const CMDS = \[([^\]]*)\]/m.exec(rd("relay/src/index.js")) || [])[1] || "";
// the worker lists codes (docs/protocol.md "Command codes"): back to names with the page's WIRE table
const wire = (/^const WIRE = \{([^}]*)\}/m.exec(rd("ui/assets/core.js")) || [])[1] || "";
const NAME = Object.fromEntries([...wire.matchAll(/(\w+): "(\w+)"/g)].map(x => [x[2], x[1]]));
for (const c of [...cmds.matchAll(/"([A-Za-z0-9]+)"/g)].map(x => NAME[x[1]] || x[1]))
  if (!base.cmd.includes(c) && !has.cmd.has(c)) bad.push(`command "${c}" is newer than ${base.v}: add it to NEED (ui/assets/kid.js) with the kid version that has it`);
const rules = rd("android/app/src/main/kotlin/dev/kidguard/guard/Rules.kt");
for (const k of new Set([...rules.matchAll(/put\("([A-Za-z]+)"/g)].map(x => x[1])))
  if (!base.rule.includes(k) && !has.rule.has(k)) bad.push(`rule key "${k}" is newer than ${base.v}: add it to NEED (ui/assets/kid.js) with the kid version that has it`);

if (bad.length) { console.error("check-need:\n  " + bad.join("\n  ")); process.exit(1); }
console.log(`Need check ok: ${NEED.length} NEED entries, ${has.cmd.size} commands + ${has.rule.size} rule keys newer than ${base.v}`);

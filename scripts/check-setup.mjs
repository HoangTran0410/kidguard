#!/usr/bin/env node
// The 4 setup paths (AGENTS.md: setup.sh, setup.ps1, ui/install.html, K/setup/SelfSetup.kt) must grant the same things.
// They stay separate programs (different runtimes, decided), so this gate keeps their grant lists equal instead.
// Compares every `pm grant <pkg> <perm>`, `appops set <pkg> <op> allow` and `cmd notification allow_*` (maintainability
// review 2026-11-17). Run by CI + build.sh.
import { readFileSync } from "node:fs";

const FILES = {
  "setup.sh": "scripts/setup.sh",
  "setup.ps1": "scripts/setup.ps1",
  "install.html": "ui/install.html",
  "SelfSetup.kt": "android/app/src/main/kotlin/dev/kidguard/setup/SelfSetup.kt",
};

function grants(text) {
  // one spelling of the package in all 4: $PKG, $Pkg, " + PKG + "
  const t = text.replace(/"\s*\+\s*PKG\s*\+\s*"/g, "P").replace(/\$\{?PKG\}?|\$Pkg/g, "P");
  const out = new Set();
  for (const m of t.matchAll(/pm grant P (android\.permission\.[A-Z_]+)/g)) out.add("grant " + m[1]);
  for (const m of t.matchAll(/appops set P ([A-Z_]+) allow/g)) out.add("appops " + m[1]);
  for (const m of t.matchAll(/cmd notification (allow_[a-z]+)/g)) out.add("notification " + m[1]);
  return out;
}

const sets = Object.fromEntries(Object.entries(FILES).map(([k, p]) => [k, grants(readFileSync(p, "utf8"))]));
const all = new Set(Object.values(sets).flatMap((s) => [...s]));
let bad = 0;
for (const g of [...all].sort()) {
  const missing = Object.keys(sets).filter((k) => !sets[k].has(g));
  if (missing.length) { bad++; console.error(`setup grant "${g}" missing in: ${missing.join(", ")}`); }
}
if (bad) { console.error(`check-setup: ${bad} grant(s) differ between the 4 setup paths (AGENTS.md "Setup steps are done 4 ways")`); process.exit(1); }
console.log(`check-setup: ${all.size} grants, same in all 4 setup paths`);

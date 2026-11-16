#!/usr/bin/env node
// Feature search gate (owner 2026-11-11: "thêm chức năng mà thiếu keyword tìm feature, quên hoài").
// Fails when a section row on the parent page (`sec("id", ...)`, `secP`, `secMix`, `secL` in ui/assets) has no entry in the search
// index `QS` (ui/assets/search.js), when a QS entry points at a section that no longer exists, or when a QS / tab
// text key is missing in either language. Run: node scripts/check-search.mjs (CI + scripts/build.sh).
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "ui", "assets");
// Sections that are not features of their own (lists / repeats of another tab). Add here only with a reason.
const NOT_FEATURES = {
  "ov-rep": "overview copy of the Báo cáo tab",
};

// Parts of a merged row (secMix): they are not rows, so search must point at the row that holds them (id -> row id)
const MERGED = { "r-daily": "r-day", "r-chore": "r-day", "r-bed": "r-night", "r-wind": "r-night", "r-dns": "r-net", "r-web": "r-net", "r-more": "r-block",
  "r-anti": "r-block", "r-home": "r-kid", "r-kind": "r-kid", "s-pin": "s-sec", "r-totp": "s-sec", "s-ts": "s-remote", "s-relay": "s-remote", "s-dev": "s-adv", "s-sh": "s-adv" };

const files = readdirSync(dir).filter(f => f.endsWith(".js"));
const src = Object.fromEntries(files.map(f => [f, readFileSync(join(dir, f), "utf8")]));
const shell = src["shell.js"] + "\n" + src["search.js"];   // NAV is in shell.js, QS in search.js
const literal = name => {
  const m = shell.match(new RegExp(`^const ${name} = (\\[[\\s\\S]*?\\n?\\]);`, "m"));
  if (!m) throw new Error(`${name} not found in shell.js / search.js`);
  return Function(`"use strict"; return ${m[1]}`)();
};
const NAV = literal("NAV"), QS = literal("QS");
const keysOf = f => new Set([...src[f].matchAll(/"([A-Za-z0-9_.#-]+)":/g)].map(m => m[1]));
const langs = { vi: keysOf("i18n-vi.js"), en: keysOf("i18n-en.js") };

const errs = [];
const secs = new Map();
for (const [f, s] of Object.entries(src)) for (const m of s.matchAll(/\bsec(?:P|Mix|L)?\("([^"]+)"/g)) secs.set(m[1], f);
const indexed = new Set(QS.map(e => e[1]));

for (const [id, f] of secs) if (!indexed.has(id) && !NOT_FEATURES[id] && !MERGED[id])
  errs.push(`section "${id}" (${f}) is not in the feature search: add ["<tab>", "${id}", "<seg>", "<icon>", ["<title key>", "<desc key>", ...]] to QS in search.js (or to NOT_FEATURES here with a reason)`);
for (const r of new Set(Object.values(MERGED))) if (!secs.has(r)) errs.push(`merged row "${r}" has no secMix("${r}") any more`);
for (const [tab, id, , , keys] of QS) {
  if (MERGED[id]) errs.push(`QS entry "${id}" is a part of the merged row "${MERGED[id]}": point it there`);
  if (id && !id.startsWith("#") && !secs.has(id)) errs.push(`QS entry "${id}" points at no sec("${id}") any more`);
  if (id && id.startsWith("#") && !Object.values(src).some(s => s.includes(`id="${id.slice(1)}"`))) errs.push(`QS entry "${id}": no element with that id`);
  if (!NAV.some(n => n[0] === tab)) errs.push(`QS entry "${id}": unknown tab "${tab}"`);
  for (const k of keys) for (const [l, set] of Object.entries(langs)) if (!set.has(k)) errs.push(`QS "${id}": key "${k}" missing in i18n-${l}.js`);
}
for (const [id] of NAV) for (const [l, set] of Object.entries(langs)) if (!set.has("tab." + id)) errs.push(`tab "${id}": "tab.${id}" missing in i18n-${l}.js`);

if (errs.length) { console.error("Feature search check FAILED:\n- " + errs.join("\n- ")); process.exit(1); }
console.log(`Feature search check ok: ${secs.size} sections, ${QS.length} search entries, ${NAV.length} tabs`);

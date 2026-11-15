#!/usr/bin/env node
// Feature search gate (owner 2026-11-11: "thêm chức năng mà thiếu keyword tìm feature, quên hoài").
// Fails when a collapsible section on the parent page (`sec("id", ...)` in ui/assets) has no entry in the search
// index `QS` (ui/assets/shell.js), when a QS entry points at a section that no longer exists, or when a QS / tab
// text key is missing in either language. Run: node scripts/check-search.mjs (CI + scripts/build.sh).
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "ui", "assets");
// Sections that are not features of their own (lists / repeats of another tab). Add here only with a reason.
const NOT_FEATURES = {
  "cl-sug": "app list inside Ứng dụng › Dọn máy (the tab itself is searchable)",
  "cl-rest": "app list inside Ứng dụng › Dọn máy",
  "ov-rep": "overview copy of the Báo cáo tab",
};

const files = readdirSync(dir).filter(f => f.endsWith(".js"));
const src = Object.fromEntries(files.map(f => [f, readFileSync(join(dir, f), "utf8")]));
const shell = src["shell.js"];
const literal = name => {
  const m = shell.match(new RegExp(`^const ${name} = (\\[[\\s\\S]*?\\n?\\]);`, "m"));
  if (!m) throw new Error(`${name} not found in shell.js`);
  return Function(`"use strict"; return ${m[1]}`)();
};
const NAV = literal("NAV"), QS = literal("QS");
const keysOf = f => new Set([...src[f].matchAll(/"([A-Za-z0-9_.#-]+)":/g)].map(m => m[1]));
const langs = { vi: keysOf("i18n-vi.js"), en: keysOf("i18n-en.js") };

const errs = [];
const secs = new Map();
for (const [f, s] of Object.entries(src)) for (const m of s.matchAll(/\bsec\("([^"]+)"/g)) secs.set(m[1], f);
const indexed = new Set(QS.map(e => e[1]));

for (const [id, f] of secs) if (!indexed.has(id) && !NOT_FEATURES[id])
  errs.push(`section "${id}" (${f}) is not in the feature search: add ["<tab>", "${id}", "<seg>", "<icon>", ["<title key>", "<desc key>", ...]] to QS in shell.js (or to NOT_FEATURES here with a reason)`);
for (const [tab, id, , , keys] of QS) {
  if (id && !id.startsWith("#") && !secs.has(id)) errs.push(`QS entry "${id}" points at no sec("${id}") any more`);
  if (id && id.startsWith("#") && !Object.values(src).some(s => s.includes(`id="${id.slice(1)}"`))) errs.push(`QS entry "${id}": no element with that id`);
  if (!NAV.some(n => n[0] === tab)) errs.push(`QS entry "${id}": unknown tab "${tab}"`);
  for (const k of keys) for (const [l, set] of Object.entries(langs)) if (!set.has(k)) errs.push(`QS "${id}": key "${k}" missing in i18n-${l}.js`);
}
for (const [id] of NAV) for (const [l, set] of Object.entries(langs)) if (!set.has("tab." + id)) errs.push(`tab "${id}": "tab.${id}" missing in i18n-${l}.js`);

if (errs.length) { console.error("Feature search check FAILED:\n- " + errs.join("\n- ")); process.exit(1); }
console.log(`Feature search check ok: ${secs.size} sections, ${QS.length} search entries, ${NAV.length} tabs`);

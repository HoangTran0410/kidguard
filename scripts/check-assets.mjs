// Gate for the parent page's files (CI + build.sh). A new ui/assets file must be wired in 2 places or it 404s somewhere:
//   1. ui/index.html: <script defer src="/ui/x.js"> (or the stylesheet <link>)
//   2. relay/src/index.js: `import ui_x_js from "../../ui/assets/x.js"` AND `"x.js": ui_x_js` in the UI map
// (the phone and the parent app serve any ui/assets/<name>.(js|css) by pattern: Safety.uiAsset, nothing to list there).
// Also: every i18n key exists in both languages (a missing vi key shows English to a Vietnamese parent).
import { readFileSync, readdirSync } from "node:fs";
import vm from "node:vm";

const root = new URL("..", import.meta.url).pathname;
const read = p => readFileSync(root + p, "utf8");
const html = read("ui/index.html"), relay = read("relay/src/index.js");
const errs = [];

const files = readdirSync(root + "ui/assets").filter(f => /^[a-z0-9-]+\.(js|css)$/.test(f));
for (const f of files) {
  const v = "ui_" + f.replace(/[.-]/g, "_");
  // i18n-<code>.js are not tags: lang.js document.write()s the active one and loads the other on a language switch
  if (!/^i18n-/.test(f) && !html.includes(`/ui/${f}"`)) errs.push(`${f}: no <script>/<link> in ui/index.html`);
  if (!relay.includes(`from "../../ui/assets/${f}"`)) errs.push(`${f}: no import in relay/src/index.js`);
  if (!new RegExp(`"${f.replace(/\./g, "\\.")}":\\s*${v}\\b`).test(relay)) errs.push(`${f}: no "${f}": ${v} entry in the relay UI map`);
}
for (const m of html.matchAll(/(?:src|href)="\/ui\/([^"]+)"/g)) if (!files.includes(m[1])) errs.push(`ui/index.html loads /ui/${m[1]}: no such file`);

// i18n parity: run both files in a sandbox, compare key sets (arrays such as wd count as one key)
const ctx = vm.createContext({});
vm.runInContext("const L = {};\n" + read("ui/assets/i18n-vi.js") + "\n" + read("ui/assets/i18n-en.js") + "\nthis.__L = L;", ctx);
const listed = [...read("ui/assets/lang.js").matchAll(/const LANGS = (\[\[.*\]\]);/g)].flatMap(m => JSON.parse(m[1]).map(x => x[0]));
for (const f of files) { const m = /^i18n-([a-z]+)\.js$/.exec(f); if (m && !listed.includes(m[1])) errs.push(`lang.js: LANGS does not list ${m[1]} (ui/assets/${f})`); }
for (const c of listed) if (!files.includes(`i18n-${c}.js`)) errs.push(`lang.js: LANGS lists ${c}: no ui/assets/i18n-${c}.js`);
const { vi, en } = ctx.__L;
const miss = (a, b, name) => Object.keys(a).filter(k => !(k in b)).forEach(k => errs.push(`i18n: "${k}" missing in ${name}`));
miss(en, vi, "i18n-vi.js"); miss(vi, en, "i18n-en.js");

if (errs.length) { console.error("check-assets:\n  " + errs.join("\n  ")); process.exit(1); }
console.log(`Asset check ok: ${files.length} files wired, ${Object.keys(en).length} i18n keys in both languages`);

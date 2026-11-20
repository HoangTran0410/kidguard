// Gate for the parent page's files (CI + build.sh). A new ui/assets file must be wired in ui/index.html:
//   <script defer src="/ui/x.js"> (or the stylesheet <link>), or it 404s.
// (the phone and the parent app serve any ui/assets/<name>.(js|css) by pattern: Safety.uiAsset; the website copies the
// folder: scripts/site-app.mjs. The relay serves no page since plan B 2026-11-18: it must import nothing from ui/.)
// Also: every i18n key exists in both languages (a missing vi key shows English to a Vietnamese parent).
import { readFileSync, readdirSync } from "node:fs";
import vm from "node:vm";

const root = new URL("..", import.meta.url).pathname;
const read = p => readFileSync(root + p, "utf8");
const html = read("ui/index.html"), relay = read("relay/src/index.js");
if (/from "\.\.\/\.\.\/ui\//.test(relay)) errs.push("relay/src/index.js imports from ui/: the relay is JSON only (plan B), the website serves the page");
const errs = [];

const files = readdirSync(root + "ui/assets").filter(f => /^[a-z0-9-]+\.(js|css)$/.test(f));
for (const f of files) {
  // i18n-<code>.js are not tags: lang.js document.write()s the active one and loads the other on a language switch
  if (!/^i18n-/.test(f) && !html.includes(`/ui/${f}"`)) errs.push(`${f}: no <script>/<link> in ui/index.html`);
}
for (const m of html.matchAll(/(?:src|href)="\/ui\/([^"]+)"/g)) if (!files.includes(m[1])) errs.push(`ui/index.html loads /ui/${m[1]}: no such file`);

const MAX_GLOBALS = 863, MAX_LETS = 70;   // 863: live camera (CAM + seenCamWire, 2026-11-20)
let lets = 0, globals = 0;
// one top-level name per page: the scripts share globals, and a 2nd `function x` silently replaces the 1st
// (INCIDENT 2026-11-17: chat's pendHtml(job) replaced shell.js's pendHtml(id, html): "NaN MB" under the bank list)
{
  const seen = new Map();
  for (const m of html.matchAll(/<script[^>]*src="\/ui\/([^"]+)"/g)) {
    if (/^i18n-/.test(m[1]) || !files.includes(m[1])) continue;
    for (const d of read("ui/assets/" + m[1]).matchAll(/^(?:async\s+)?(?:function\*?\s+([A-Za-z_$][\w$]*)|(?:const|let|var|class)\s+([A-Za-z_$][\w$]*))/gm)) {
      const n = d[1] || d[2];
      if (seen.has(n) && seen.get(n) !== m[1]) errs.push(`top-level name "${n}" in both ${seen.get(n)} and ${m[1]}`); else seen.set(n, m[1]);
      if (/^(?:let|var)\s/.test(d[0])) lets++;
    }
  }
  // ceiling on shared globals (maintainability review 2026-11-17: 621 -> 841 in a day). Raising it is a decision:
  // prefer a local inside the function, or one object per file, over a new top-level name.
  if (seen.size > MAX_GLOBALS) errs.push(`${seen.size} top-level names (ceiling ${MAX_GLOBALS}): keep new helpers local, or raise MAX_GLOBALS on purpose`);
  if (lets > MAX_LETS) errs.push(`${lets} top-level let/var (ceiling ${MAX_LETS}): mutable page state belongs in an existing state object`);
  globals = seen.size;
}

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
console.log(`Asset check ok: ${files.length} files wired, ${Object.keys(en).length} i18n keys in both languages, ${globals}/${MAX_GLOBALS} globals, ${lets}/${MAX_LETS} let/var`);

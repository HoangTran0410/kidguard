// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- language: which dictionary to load ------------------------------------------------------------------------
// Only the active language's dictionary (i18n-<code>.js, 110-120 KB) is fetched: this plain (not defer) script picks the
// language and document.write()s that file's <script>, so L[lang] exists before core.js and the other scripts run.
// Switching language (setLang in core.js) loads the other file on demand (loadLang), then redraws.
// To add a language: add [code, name] to LANGS and ui/assets/i18n-<code>.js (L.<code> = {...}, same keys as the others,
// `node scripts/check-assets.mjs` checks parity), plus its import + UI map entry in relay/src/index.js.
const LANGS = [["vi", "Tiếng Việt"], ["en", "English"]];
const L = {};   // L[code] = dictionary, filled by i18n-<code>.js
// pick: saved choice, else the first browser language we have (vi* -> vi), else en
let lang = (() => {
  const ok = c => LANGS.some(l => l[0] === c);
  // parent app: its own saved choice first (the WebView origin, and so localStorage, changes with the proxy port)
  try { const s = window.KGApp && KGApp.getLang && String(KGApp.getLang() || ""); if (s && ok(s)) return s; } catch {}
  try { const s = localStorage.getItem("kg.lang"); if (s && ok(s)) return s; } catch {}
  for (const n of navigator.languages || [navigator.language || ""]) { const b = String(n).toLowerCase().split("-")[0]; if (ok(b)) return b; }
  return "en";
})();
// load a dictionary on demand; resolves when L[code] exists (also on a network error: t() then shows the keys)
const loadLang = code => L[code] ? Promise.resolve() : new Promise(res => {
  const s = document.createElement("script");
  s.src = "/ui/i18n-" + code + ".js"; s.onload = s.onerror = () => res();
  document.head.appendChild(s);
});
document.write('<script src="/ui/i18n-' + lang + '.js"><\/script>');

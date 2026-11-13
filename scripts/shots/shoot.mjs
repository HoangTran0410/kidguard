// Screenshots of the parent page from sample data: node shoot.mjs [--out DIR] [--lang vi,en] [--only today,apps] [--theme dark|light]
// Starts server.mjs (3 fake kids), drives headless Chrome over CDP, writes <out>/<lang>/<name>.png (400x860 CSS px, 2x).
// Needs Node >= 20.10 (global WebSocket behind a flag: this file re-runs itself with it) and Chrome (CHROME env to override).
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

if (typeof WebSocket === "undefined") {
  const r = (await import("node:child_process")).spawnSync(process.execPath, ["--experimental-websocket", ...process.argv.slice(1)], { stdio: "inherit" });
  process.exit(r.status ?? 1);
}
const HERE = path.dirname(fileURLToPath(import.meta.url));
const arg = (n, d) => { const i = process.argv.indexOf("--" + n); return i > 0 ? process.argv[i + 1] : d; };
const OUT = path.resolve(arg("out", path.join(HERE, "out")));
const LANGS = arg("lang", "vi,en").split(",");
const ONLY = arg("only", "") ? arg("only").split(",") : null;
const THEME = arg("theme", "dark");
const W = +arg("width", 400);
// --audit: every section open, full-page shot, and a layout report (what sticks out of the screen, what is clipped)
const AUDIT = process.argv.includes("--audit");
// --faults ok? no: after each shot, the server goes down / answers 500 / answers after 15 s; the page refreshes and saves once,
// then a shot <name>-<fault>.png and a report of what the parent sees (toast, error lines) and what breaks
const FAULTS = process.argv.includes("--faults") ? ["down", "e500", "slow"] : [];
const CHROME = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORTS = { bin: 8771, mia: 8772, tom: 8773 };
// --cdp <port>: attach to a browser that is already running (Android WebView via adb forward, see README) instead of
// starting desktop Chrome; the page keeps the device's own size
const EXT = +arg("cdp", 0);
const CDP = EXT || 9333;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const NOW = (() => { const d = new Date(); d.setHours(16, 20, 0, 0); return d.getTime(); })();

// What to shoot. js runs after the page shows the kid (st loaded); ready = expression that must become truthy; scroll = px.
// A trailing wait is added for map tiles. Names are the output file names.
const SHOTS = [
  { name: "kids", js: `overview(kids, () => {})`, ready: `document.querySelector("#ovKids .ovk, #ovKids .card, #ovKids > *") && !document.querySelector("#ovKids .spin")`, settle: 1200 },
  { name: "today", js: `goTab("home")`, ready: `document.querySelector(".hero")` },
  { name: "apps", js: `appsSub = "list"; goTab("apps")`, ready: `document.querySelector(".app .ic")`, settle: 800 },
  { name: "rules", poke: 1, js: `goTab("rules")`, ready: `document.querySelector("[data-sec]")`, open: ["r-bed", "r-sch"], shut: ["r-daily"], scrollTo: "[data-sec=r-daily]" },
  { name: "where", js: `goTab("where")`, ready: `document.querySelector("#map .layer")`, tiles: true, settle: 800 },
  { name: "screen", js: `goTab("screen")`, ready: `document.querySelector("#s-img") && document.querySelector("#s-img").complete && document.querySelector("#s-img").naturalWidth > 0`, settle: 800 },
  { name: "files", js: `goTab("files")`, ready: `document.querySelector("#fxOut .app, #fxOut .li, #fxOut > *")`, settle: 600 },
  { name: "settings-away", js: `goTab("set")`, ready: `document.querySelector("#awaySec")`, open: ["s-tg"], shut: ["s-ts", "s-relay"], scrollTo: "#awaySec", settle: 600 },
];

// Runs in the page. Reports (1) visible elements whose box ends past the screen edge and are not inside a sideways
// scroller, (2) text cut by an overflow:hidden box (not a deliberate ellipsis / line clamp), (3) page wider than the screen.
const layoutAudit = String(() => {
  const vw = document.documentElement.clientWidth, out = [], seen = new Set();
  const name = e => { const t = (e.innerText || e.value || e.getAttribute("aria-label") || "").trim().replace(/\s+/g, " ").slice(0, 50);
    const sec = e.closest("[data-sec]"); return (sec ? "[" + sec.dataset.sec + "] " : "") + e.tagName.toLowerCase() + (e.id ? "#" + e.id : e.className && typeof e.className === "string" ? "." + e.className.split(" ")[0] : "") + (t ? ' "' + t + '"' : ""); };
  const scroller = e => { for (let p = e.parentElement; p && p !== document.body; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o === "auto" || o === "scroll") return true; } return false; };
  if (document.documentElement.scrollWidth > vw + 1) out.push("page is " + document.documentElement.scrollWidth + "px wide on a " + vw + "px screen");
  for (const e of document.querySelectorAll("body *")) {
    if (e.closest("#map") || e.closest("svg")) continue;   // map tiles overhang on purpose; icon geometry is not layout
    const cs = getComputedStyle(e); if (cs.display === "none" || cs.visibility === "hidden" || cs.position === "fixed") continue;
    const r = e.getBoundingClientRect(); if (!r.width || !r.height) continue;
    if (r.right > vw + 1 && !scroller(e)) { const k = "o" + name(e); if (!seen.has(k)) { seen.add(k); out.push("sticks out " + Math.round(r.right - vw) + "px: " + name(e)); } }
    // cut by the nearest ancestor that clips sideways (card corners, collapsible bodies): the bug a screen-edge check misses
    for (let p = e.parentElement; p && p !== document.body; p = p.parentElement) {
      const o = getComputedStyle(p).overflowX; if (o === "visible") continue;
      if (o === "auto" || o === "scroll") break;
      const pr = p.getBoundingClientRect(), cut = Math.round(r.right - pr.right);
      if (cut > 1) { const k = "c" + name(e); if (!seen.has(k)) { seen.add(k); out.push("cut off " + cut + "px by " + name(p).split(' "')[0] + ": " + name(e)); } }
      break;
    }
  }
  // only the outermost offender of each chain is useful
  return out.filter((x, i) => !out.slice(0, i).some(y => y.startsWith("sticks out") && x.startsWith("sticks out") && x.includes(y.split(": ").slice(1).join(": ").slice(0, 12)))).slice(0, 40);
});

// Runs in the page: labels that are i18n keys, controls with the same / nearly the same label (two switches for one thing),
// tap targets under 40 px, controls without a name.
const uxLint = String(() => {
  const out = [], vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== "hidden"; };
  const keyLike = /(^|\s)([a-z][a-zA-Z]*\.[a-zA-Z][a-zA-Z0-9.]*)(?=\s|$)/;
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n; (n = w.nextNode());) { const m = keyLike.exec(n.nodeValue.trim()); if (m && !/\.(com|net|org|vn|dev|io|app|json|apk|js)\b/.test(m[2]) && !/^\d/.test(m[2]) && n.parentElement && vis(n.parentElement)) out.push("i18n key shown as text: " + m[2]); }
  const ctl = [...document.querySelectorAll("input[type=checkbox], select, input[type=radio]")].filter(vis);
  const lab = e => ((e.closest("label") || {}).innerText || (e.id && (document.querySelector('label[for="' + e.id + '"]') || {}).innerText) || e.getAttribute("aria-label") || "").trim().replace(/\s+/g, " ");
  const words = s => new Set(s.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/g, "d").split(/[^a-z0-9]+/).filter(x => x.length > 1));
  const L = ctl.map(e => [e, lab(e), words(lab(e))]).filter(x => x[2].size >= 2);
  for (let i = 0; i < L.length; i++) for (let j = i + 1; j < L.length; j++) {
    const [a, la, wa] = L[i], [b, lb, wb] = L[j]; if (a.closest(".bkt") && b.closest(".bkt")) continue;   // per-app table rows repeat on purpose
    const inter = [...wa].filter(x => wb.has(x)).length, sim = inter / Math.min(wa.size, wb.size);
    if (sim >= 0.75 && la !== "" && (a.closest("[data-sec]") || {}).dataset?.sec !== undefined) out.push("similar controls: \"" + la.slice(0, 50) + "\" [" + (a.closest("[data-sec]") || { dataset: {} }).dataset.sec + "] ~ \"" + lb.slice(0, 50) + "\" [" + (b.closest("[data-sec]") || { dataset: {} }).dataset.sec + "]");
  }
  for (const e of [...document.querySelectorAll("button, select, input:not([type=checkbox]):not([type=radio]):not([type=hidden]), [role=button]")].filter(vis)) {
    const r = e.getBoundingClientRect(), nm = (e.textContent || e.getAttribute("aria-label") || e.title || e.value || "").trim();
    if (!nm && !(e.labels && e.labels.length)) out.push("control without a name: " + e.tagName.toLowerCase() + (e.id ? "#" + e.id : "." + (e.className || "").toString().split(" ")[0]));
    // WCAG 2.2 AA target size is 24 px; a ::after with a negative inset (e.g. .tipb) grows the hit area beyond the box
    const ext = Math.max(0, -(parseFloat(getComputedStyle(e, "::after").top) || 0));
    if (Math.min(r.height, r.width) + 2 * ext < 24) out.push("small tap target " + Math.round(r.width) + "x" + Math.round(r.height) + ": " + (nm.slice(0, 30) || e.tagName.toLowerCase() + (e.id ? "#" + e.id : "")));
  }
  // one card saying the same first sentence twice (two "Needs Device Owner." notes for one switch)
  for (const sec of document.querySelectorAll("[data-sec]")) {
    const firsts = [...sec.querySelectorAll("p, .mute, .note")].filter(e => vis(e) && !e.querySelector("p")).map(e => (/^([^.!?]{9,60})[.!?]\s/.exec(e.innerText.trim()) || [])[1]).filter(Boolean);   // whole sentences only (list rows repeat on purpose)
    const dup = firsts.find((x, i) => firsts.indexOf(x) !== i); if (dup) out.push("repeated note in [" + sec.dataset.sec + "]: \"" + dup + "\"");
  }
  return [...new Set(out)].slice(0, 40);
});
// Flip every checkbox / pick every select option (then put it back); exceptions are collected by the caller.
const poke = String(() => {
  const out = [], vis = e => e.getBoundingClientRect().height > 0;
  const fire = (e, t) => e.dispatchEvent(new Event(t, { bubbles: true }));
  for (const e of [...document.querySelectorAll("#rsx input[type=checkbox], #rsx select")]) {
    if (!vis(e) || e.disabled || !document.contains(e)) continue;
    try {
      if (e.type === "checkbox") { e.checked = !e.checked; fire(e, "change"); if (document.contains(e)) { e.checked = !e.checked; fire(e, "change"); } }
      else { const v = e.value; for (const o of [...e.options].filter(o => !o.disabled)) { e.value = o.value; fire(e, "change"); if (!document.contains(e)) break; } if (document.contains(e)) { e.value = v; fire(e, "change"); } }
    } catch (x) { out.push("poke " + (e.id || e.name) + ": " + x.message); }
  }
  return out;
});
// The phone replaces its rules with what the page sends: every PUT must carry the whole object.
const putCheck = String(() => {
  const out = [], ps = (window.__puts || []).map(b => { try { return JSON.parse(b); } catch { return null; } });
  if (!ps.length) return ["no rules PUT after changing controls"];
  const need = Object.keys(edit || {}).filter(k => !["pin", "pinSet", "pinHash"].includes(k));   // the page never sends these back
  ps.forEach((p, i) => { if (!p) return out.push("rules PUT #" + i + " is not JSON"); const miss = need.filter(k => !(k in p)); if (miss.length) out.push("rules PUT #" + i + " misses " + miss.slice(0, 8).join(",")); });
  return [...new Set(out)];
});

// ---- tiny CDP client ------------------------------------------------------------------
async function connect(url) {
  const ws = new WebSocket(url); let id = 0; const wait = new Map(); const ev = [];
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = m => { const j = JSON.parse(m.data); if (j.id && wait.has(j.id)) { const [ok, bad] = wait.get(j.id); wait.delete(j.id); j.error ? bad(new Error(j.error.message)) : ok(j.result); } else if (j.method) ev.push(j); };
  return { send: (method, params = {}) => new Promise((ok, bad) => { const i = ++id; wait.set(i, [ok, bad]); ws.send(JSON.stringify({ id: i, method, params })); }), events: ev, close: () => ws.close() };
}
const evalIn = async (c, expr) => { const r = await c.send("Runtime.evaluate", { expression: expr, awaitPromise: true, returnByValue: true }); if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text); return r.result.value; };
async function until(c, expr, ms = 15000) { const t0 = Date.now(); while (Date.now() - t0 < ms) { try { if (await evalIn(c, `!!(${expr})`)) return true; } catch {} await sleep(150); } return false; }

const kidsSeed = () => JSON.stringify(Object.entries(PORTS).map(([n, p], i) => ({ id: ["5b1e0c9a7d3f4e21", "9c4d2e7b1a6f3058", "2f8a6b1c9e4d7035"][i], token: String(i + 1).repeat(48).slice(0, 48).replace(/\d/g, c => "abcdef"[+c]), name: { bin: "Bin", mia: "Mia", tom: "Tom" }[n], via: "lan", target: `http://127.0.0.1:${p}`, lv: 1 })));

async function shootLang(lang) {
  const outDir = path.join(OUT, lang); fs.mkdirSync(outDir, { recursive: true });
  const srv = spawn(process.execPath, [path.join(HERE, "server.mjs"), "--lang", lang, "--now", String(NOW), "--kids", Object.entries(PORTS).map(([k, p]) => k + ":" + p).join(","), ...(arg("patch") ? ["--patch", path.resolve(arg("patch"))] : [])], { stdio: "inherit" });
  await sleep(600);
  try {
    const tgt = EXT ? (await (await fetch(`http://127.0.0.1:${CDP}/json/list`)).json()).find(x => x.type === "page")
      : await (await fetch(`http://127.0.0.1:${CDP}/json/new?about:blank`, { method: "PUT" })).json();
    const c = await connect(tgt.webSocketDebuggerUrl);
    await c.send("Page.enable"); await c.send("Runtime.enable");
    // parent app WebView: its own page (served by its proxy, which forwards to the fake kids); its real kid list is put back after
    const PAGE = EXT ? new URL(tgt.url).origin + "/" : `http://127.0.0.1:${PORTS.bin}/`;
    const keep = EXT ? await evalIn(c, `window.KGApp ? KGApp.getKids() : null`) : null;
    if (!EXT) { await c.send("Emulation.setDeviceMetricsOverride", { width: W, height: 860, deviceScaleFactor: 2, mobile: true });
      await c.send("Emulation.setTouchEmulationEnabled", { enabled: true }); }
    await c.send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: THEME }] });
    // before any page script: saved kids + language + theme, and a clock fixed at 16:20 (the server's story time) that keeps ticking
    const { identifier: seedId } = await c.send("Page.addScriptToEvaluateOnNewDocument", { source: `(() => {
      try { const o = ${NOW} - Date.now(), D = Date; globalThis.Date = class extends D { constructor(...a) { if (a.length) super(...a); else super(D.now() + o); } static now() { return D.now() + o; } }; } catch {}
      if (${AUDIT}) { window.confirm = () => false; window.alert = () => {}; window.prompt = () => null;
        window.__puts = []; const F = window.fetch; window.fetch = (u, o) => { try { if (o && o.method === "PUT" && String(u).includes("/rules")) window.__puts.push(o.body); } catch {} return F(u, o); }; }
      try { localStorage.setItem("kg.kids", ${JSON.stringify(kidsSeed())}); localStorage.setItem("kg.lang", "${lang}"); localStorage.setItem("kg.theme", "${THEME}"); } catch {}
    })()` });
    const errors = [], faultLog = [];
    // parent app: the bridge exists only once the page runs, and its proxy forwards only to kids in its own list
    if (EXT) { await c.send("Page.navigate", { url: PAGE }); await until(c, `!!window.KGApp && document.readyState === "complete"`, 15000); await evalIn(c, `KGApp.setKids(${JSON.stringify(kidsSeed())}); 0`); }
    for (const shot of SHOTS) {
      if (ONLY && !ONLY.includes(shot.name)) continue;
      // fresh page per shot: no state leaks between them
      await c.send("Page.navigate", { url: PAGE });
      await sleep(400);
      if (EXT && await until(c, `typeof kids !== "undefined" && kids.some(k => k.name === "Bin") && document.readyState === "complete" && typeof screenStop === "function"`, 15000)) await evalIn(c, `open(kids.find(k => k.name === "Bin")); 0`);   // app: the page starts on the kid list
      if (!(await until(c, `typeof st !== "undefined" && st && st.apps && document.querySelector(".hero")`, 20000))) errors.push(shot.name + ": page did not open the kid (" + await evalIn(c, `JSON.stringify({ u: location.href, kids: typeof kids !== "undefined" ? kids.map(k => k.name) : null, cur: typeof cur !== "undefined" && cur ? cur.name : null, err: typeof lastErr !== "undefined" ? lastErr : null })`) + ")");
      const secs = Object.fromEntries([...(shot.open || []).map(i => [i, 1]), ...(shot.shut || []).map(i => [i, 0])]);
      await evalIn(c, `Object.assign(secOpen, ${JSON.stringify(secs)}); ${shot.js}; 0`);
      if (!(await until(c, shot.ready, 20000))) errors.push(shot.name + ": not ready (" + shot.ready.slice(0, 50) + ")");
      if (shot.tiles) { await sleep(500); await until(c, `[...document.querySelectorAll("#map img")].length > 0 && [...document.querySelectorAll("#map img")].every(i => i.complete && i.naturalWidth > 0)`, 20000) || errors.push(shot.name + ": map tiles not all loaded"); }
      await sleep(shot.settle || 400);
      if (Object.keys(secs).length) await evalIn(c, `Object.entries(${JSON.stringify(secs)}).forEach(([id, on]) => { const s = document.querySelector('[data-sec="' + id + '"]'); if (s) { s.classList.toggle("open", !!on); const h = s.querySelector(".clh"); if (h) h.setAttribute("aria-expanded", String(!!on)); } }); 0`);
      if (AUDIT) {
        await evalIn(c, `document.querySelectorAll("[data-sec]").forEach(s => { secOpen[s.dataset.sec] = 1; s.classList.add("open"); const h = s.querySelector(".clh"); if (h) h.setAttribute("aria-expanded", "true"); }); 0`);
        await sleep(700);
        for (const f of await evalIn(c, `(${layoutAudit})()`)) errors.push(shot.name + ": " + f);
        for (const f of await evalIn(c, `(${uxLint})()`)) errors.push(shot.name + ": " + f);
        if (shot.poke) { for (const f of await evalIn(c, `(${poke})()`)) errors.push(shot.name + ": " + f); await sleep(1500); for (const f of await evalIn(c, `(${putCheck})()`)) errors.push(shot.name + ": " + f); }
      }
      for (const f of FAULTS) {
        await fetch(`http://127.0.0.1:${PORTS.bin}/__fault?m=${f}`);
        const ex0 = c.events.length;
        await evalIn(c, `window.__toasts = []; { const el = document.querySelector("#toast"); if (el && !el.__obs) { el.__obs = new MutationObserver(() => { const s = el.innerText.trim(); if (s && window.__toasts.at(-1) !== s) window.__toasts.push(s); }); el.__obs.observe(el, { childList: true, subtree: true, characterData: true }); } }
          ${shot.name === "kids" ? "overview(kids, () => {});" : `refresh1().catch(e => window.__toasts.push("refresh threw: " + e.message));`}
          { const x = document.querySelector("#rsx input[type=checkbox]:not(:disabled)"); if (x) { x.checked = !x.checked; x.dispatchEvent(new Event("change", { bubbles: true })); } } 0`);
        await sleep(f === "slow" ? 4000 : 9000);
        const seen = await evalIn(c, `({ toasts: window.__toasts, busy: [...document.querySelectorAll(".spin, [aria-busy=true]")].filter(e => e.getBoundingClientRect().height > 0).length,
          err: [...document.querySelectorAll("#live > *, #hst, [role=alert], #ovKids .bad, #ovKids .mute")].filter(e => e.getBoundingClientRect().height > 0).map(e => e.innerText.trim().replace(/\\s+/g, " ").slice(0, 70)).filter(Boolean).slice(0, 4),
          txt: (document.body.innerText.match(/.{0,40}(\\b(undefined|NaN|\\[object|HTTP \\d+|Failed to fetch|TypeError))\\b.{0,30}/i) || [""])[0] })`);
        const rs = await c.send("Page.captureScreenshot", { format: "png" });
        fs.writeFileSync(path.join(outDir, shot.name + "-" + f + ".png"), Buffer.from(rs.data, "base64"));
        const tag = shot.name + " [" + f + "]";
        faultLog.push(tag + ": toasts=" + JSON.stringify(seen.toasts) + " busy=" + seen.busy + " errs=" + JSON.stringify(seen.err));
        if (seen.txt) errors.push(tag + ": raw text shown: " + seen.txt.replace(/\s+/g, " "));
        if (f !== "slow" && !seen.toasts.length && !seen.err.length) errors.push(tag + ": nothing tells the parent it failed");
        for (const e of c.events.slice(ex0).filter(e => e.method === "Runtime.exceptionThrown")) errors.push(tag + ": js exception: " + (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text).split("\n")[0]);
        await fetch(`http://127.0.0.1:${PORTS.bin}/__fault?m=ok`);
        if (f === "slow") await sleep(12000);   // let the slow reply land: a late answer must not undo or break anything
      }
      if (!AUDIT && shot.scrollTo) await evalIn(c, `(() => { const e = document.querySelector(${JSON.stringify(shot.scrollTo)}); if (e) window.scrollTo(0, e.getBoundingClientRect().top + scrollY - 12); })()`);
      await sleep(500);
      const txt = await evalIn(c, `document.body.innerText`);
      { const m = txt.match(/.{0,40}(\b(error|undefined|NaN|\[object)|(?<!gỡ )lỗi).{0,40}/i); if (m) errors.push(shot.name + ": suspicious text: " + m[0].replace(/\s+/g, " ")); }
      // Android WebView: captureBeyondViewport repeats the viewport (header/nav printed many times): one shot per section
      if (EXT && AUDIT) {
        const ids = await evalIn(c, `[...document.querySelectorAll("[data-sec]")].map(s => s.dataset.sec)`);
        for (const id of ids) {
          await evalIn(c, `(() => { const s = document.querySelector('[data-sec="${id}"]'); window.scrollTo(0, s.getBoundingClientRect().top + scrollY - 120); })()`); await sleep(250);
          const rr = await c.send("Page.captureScreenshot", { format: "png" });
          fs.writeFileSync(path.join(outDir, shot.name + "-" + id + ".png"), Buffer.from(rr.data, "base64"));
        }
      }
      const r = await c.send("Page.captureScreenshot", AUDIT && !EXT ? { format: "png", captureBeyondViewport: true, clip: await evalIn(c, `({ x: 0, y: 0, width: innerWidth, height: Math.min(document.documentElement.scrollHeight, 16000), scale: 1 })`) } : { format: "png" });
      fs.writeFileSync(path.join(outDir, shot.name + ".png"), Buffer.from(r.data, "base64"));
      console.log(lang, shot.name);
    }
    for (const e of c.events.filter(e => e.method === "Runtime.exceptionThrown")) errors.push("js exception: " + (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text).split("\n")[0]);
    if (EXT && keep != null) { await c.send("Page.removeScriptToEvaluateOnNewDocument", { identifier: seedId }).catch(() => {}); await evalIn(c, `KGApp.setKids(${JSON.stringify(keep)}); localStorage.setItem("kg.kids", ${JSON.stringify(keep)}); 0`); await c.send("Page.navigate", { url: PAGE }); }
    c.close(); if (!EXT) await fetch(`http://127.0.0.1:${CDP}/json/close/${tgt.id}`).catch(() => {});
    if (faultLog.length) console.log(lang + " FAULTS:\n  " + faultLog.join("\n  "));
    if (errors.length) console.log(lang + " WARN:\n  " + [...new Set(errors)].join("\n  "));
  } finally { srv.kill(); await new Promise(r => srv.once("exit", r)); }
}
const prof = fs.mkdtempSync(path.join(os.tmpdir(), "kgshots-"));
const chrome = EXT ? { kill() {} } : spawn(CHROME, ["--headless=new", `--remote-debugging-port=${CDP}`, `--user-data-dir=${prof}`, "--hide-scrollbars", "--force-color-profile=srgb", "--no-first-run", "--disable-gpu", "--window-size=400,860", "about:blank"], { stdio: "ignore" });
for (let i = 0; i < 40; i++) { try { await fetch(`http://127.0.0.1:${CDP}/json/version`); break; } catch { await sleep(300); } }
try { for (const l of LANGS) await shootLang(l); }
finally { chrome.kill(); await sleep(500); fs.rmSync(prof, { recursive: true, force: true }); }
console.log("done ->", OUT);
process.exit(0);

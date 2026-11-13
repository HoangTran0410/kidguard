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
const CHROME = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORTS = { bin: 8771, mia: 8772, tom: 8773 };
const CDP = 9333;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const NOW = (() => { const d = new Date(); d.setHours(16, 20, 0, 0); return d.getTime(); })();

// What to shoot. js runs after the page shows the kid (st loaded); ready = expression that must become truthy; scroll = px.
// A trailing wait is added for map tiles. Names are the output file names.
const SHOTS = [
  { name: "kids", js: `overview(kids, () => {})`, ready: `document.querySelector("#ovKids .ovk, #ovKids .card, #ovKids > *") && !document.querySelector("#ovKids .spin")`, settle: 1200 },
  { name: "today", js: `goTab("home")`, ready: `document.querySelector(".hero")` },
  { name: "apps", js: `goTab("apps")`, ready: `document.querySelector(".app .ic")`, settle: 800 },
  { name: "rules", js: `goTab("rules")`, ready: `document.querySelector("[data-sec]")`, open: ["r-bed", "r-sch"], shut: ["r-daily"], scrollTo: "[data-sec=r-daily]" },
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
    if (e.closest("#map")) continue;   // map tiles overhang on purpose
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
    const tgt = await (await fetch(`http://127.0.0.1:${CDP}/json/new?about:blank`, { method: "PUT" })).json();
    const c = await connect(tgt.webSocketDebuggerUrl);
    await c.send("Page.enable"); await c.send("Runtime.enable");
    await c.send("Emulation.setDeviceMetricsOverride", { width: W, height: 860, deviceScaleFactor: 2, mobile: true });
    await c.send("Emulation.setTouchEmulationEnabled", { enabled: true });
    await c.send("Emulation.setEmulatedMedia", { features: [{ name: "prefers-color-scheme", value: THEME }] });
    // before any page script: saved kids + language + theme, and a clock fixed at 16:20 (the server's story time) that keeps ticking
    await c.send("Page.addScriptToEvaluateOnNewDocument", { source: `(() => {
      try { const o = ${NOW} - Date.now(), D = Date; globalThis.Date = class extends D { constructor(...a) { if (a.length) super(...a); else super(D.now() + o); } static now() { return D.now() + o; } }; } catch {}
      try { localStorage.setItem("kg.kids", ${JSON.stringify(kidsSeed())}); localStorage.setItem("kg.lang", "${lang}"); localStorage.setItem("kg.theme", "${THEME}"); } catch {}
    })()` });
    const errors = [];
    for (const shot of SHOTS) {
      if (ONLY && !ONLY.includes(shot.name)) continue;
      // fresh page per shot: no state leaks between them
      await c.send("Page.navigate", { url: `http://127.0.0.1:${PORTS.bin}/` });
      await sleep(400);
      if (!(await until(c, `typeof st !== "undefined" && st && st.apps && document.querySelector(".hero")`, 20000))) errors.push(shot.name + ": page did not open the kid");
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
      }
      if (!AUDIT && shot.scrollTo) await evalIn(c, `(() => { const e = document.querySelector(${JSON.stringify(shot.scrollTo)}); if (e) window.scrollTo(0, e.getBoundingClientRect().top + scrollY - 12); })()`);
      await sleep(500);
      const txt = await evalIn(c, `document.body.innerText`);
      { const m = txt.match(/.{0,40}(\b(error|undefined|NaN|\[object)|(?<!gỡ )lỗi).{0,40}/i); if (m) errors.push(shot.name + ": suspicious text: " + m[0].replace(/\s+/g, " ")); }
      const r = await c.send("Page.captureScreenshot", AUDIT ? { format: "png", captureBeyondViewport: true, clip: await evalIn(c, `({ x: 0, y: 0, width: innerWidth, height: Math.min(document.documentElement.scrollHeight, 16000), scale: 1 })`) } : { format: "png" });
      fs.writeFileSync(path.join(outDir, shot.name + ".png"), Buffer.from(r.data, "base64"));
      console.log(lang, shot.name);
    }
    for (const e of c.events.filter(e => e.method === "Runtime.exceptionThrown")) errors.push("js exception: " + (e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text).split("\n")[0]);
    c.close(); await fetch(`http://127.0.0.1:${CDP}/json/close/${tgt.id}`).catch(() => {});
    if (errors.length) console.log(lang + " WARN:\n  " + [...new Set(errors)].join("\n  "));
  } finally { srv.kill(); await new Promise(r => srv.once("exit", r)); }
}
const prof = fs.mkdtempSync(path.join(os.tmpdir(), "kgshots-"));
const chrome = spawn(CHROME, ["--headless=new", `--remote-debugging-port=${CDP}`, `--user-data-dir=${prof}`, "--hide-scrollbars", "--force-color-profile=srgb", "--no-first-run", "--disable-gpu", "--window-size=400,860", "about:blank"], { stdio: "ignore" });
for (let i = 0; i < 40; i++) { try { await fetch(`http://127.0.0.1:${CDP}/json/version`); break; } catch { await sleep(300); } }
try { for (const l of LANGS) await shootLang(l); }
finally { chrome.kill(); await sleep(500); fs.rmSync(prof, { recursive: true, force: true }); }
console.log("done ->", OUT);
process.exit(0);

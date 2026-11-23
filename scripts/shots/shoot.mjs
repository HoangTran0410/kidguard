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
// --app: the parent Android app's page on desktop Chrome: a fake KGApp bridge + its /k/<target>/ proxy (forwarded to the
// fake kids), two dead kids (Nam on the killed relay = Cloudflare 1101, Lan released = 410), and the app-only screens
// (kid list, app settings, a released kid's page). Use with --only applist,appset,gone (or alone: all shots in app mode).
const FAKE_APP = process.argv.includes("--app");
const CDP = EXT || +arg("port", 9333);   // --port N: another free port when 9333 is taken (e.g. an adb forward)
const sleep = ms => new Promise(r => setTimeout(r, ms));
const NOW = (() => { const d = new Date(); d.setHours(16, 20, 0, 0); return d.getTime(); })();

// What to shoot. js runs after the page shows the kid (st loaded); ready = expression that must become truthy; scroll = px.
// A trailing wait is added for map tiles. Names are the output file names.
const SHOTS = [
  ...(FAKE_APP ? [
    { name: "applist", bare: 1, js: `appBoot()`, ready: `document.querySelectorAll("[data-kb]").length && [...document.querySelectorAll("[data-kb]")].every(e => !/^(relay|LAN)/i.test(e.textContent.trim()))`, settle: 1500 },
    // ... menu on a row (2026-11-20): rename + remove; the rename prompt after it
    { name: "applist-menu", bare: 1, js: `appBoot(); document.querySelector(".kx").click()`, ready: `document.querySelector("#askPop [data-km=del]")`, settle: 500 },
    { name: "applist-ren", bare: 1, js: `appBoot(); document.querySelector(".kx").click(); setTimeout(() => document.querySelector("#askPop [data-km=ren]").click(), 200)`, ready: `document.querySelector("#askKn")`, settle: 500 },
    { name: "addkid", bare: 1, js: `addScreen()`, ready: `document.querySelector("#ac") && document.querySelector("#appSetBack")`, settle: 500 },
    // Android Back on "Add a kid" = the list again (2026-11-20: it closed the app)
    { name: "addkid-pick", bare: 1, js: `kids = kids.filter(k => k.via !== "lan"); addScreen.pick("123456", localStorage.getItem("kg.binT"))`, ready: `document.querySelector("#pkLan")`, settle: 500 },
    { name: "addkid-lan", bare: 1, js: `kids = kids.filter(k => k.via !== "lan"); addScreen.pick("123456", localStorage.getItem("kg.binT")).then(() => $("#pkLan").click())`, ready: `document.querySelector("#appSetBack") && document.querySelector("#app .card h2")`, settle: 500 },
    { name: "addkid-miss", bare: 1, js: `addScreen.pick("123456", "http://127.0.0.1:9")`, ready: `document.querySelector("#ap1") && document.querySelector("#ac").value === "123456"`, settle: 500 },
    { name: "addkid-back", bare: 1, js: `appBoot(); addScreen(); setTimeout(() => history.back(), 300)`, ready: `!document.querySelector("#ac") && document.querySelector("#addKid")`, settle: 800 },
    { name: "appset", bare: 1, js: `goAppSettings()`, ready: `document.querySelector("#nhBox") && document.querySelector("#uiPk")`, settle: 800 },
    // move to a new phone (2026-11-23): the screen after scanning the one-QR code of the old phone
    { name: "move-recv", bare: 1, js: `xfer.screen(xfer.of(new URLSearchParams({ mv: JSON.stringify([["https://r.example", "0123456789abcdef", "a".repeat(32), "Bin"], ["https://r.example", "fedcba9876543210", "b".repeat(32), "Na"]]) })))`, ready: `document.querySelector("#mvGo")`, settle: 500 },
    { name: "appset-move", bare: 1, js: `goAppSettings()`, ready: `document.querySelector("#bkSave")`, scrollTo: "#bkSave", settle: 600 },
    { name: "backup-ask", bare: 1, js: `goAppSettings(); setTimeout(() => $("#bkSave").click(), 300)`, ready: `document.querySelector("#bkP2")`, settle: 500 },
    { name: "gone", bare: 1, js: `open(kids.find(k => k.name === "Lan"))`, ready: `document.querySelector("#forgetGone")`, settle: 800 },
    { name: "oldrelay", bare: 1, js: `open(kids.find(k => k.name === "Nam"))`, ready: `document.querySelector("#goneDel")`, settle: 800 },
  ] : []),
  { name: "kids", js: `overview(kids, () => {})`, ready: `document.querySelector("#ovKids .ovk, #ovKids .card, #ovKids > *") && !document.querySelector("#ovKids .spin")`, settle: 1200 },
  { name: "kids-sel", js: `overview(kids, () => {}); setTimeout(() => { const c = document.querySelector("#ovKids [data-sel]"); if (c) { c.click(); } }, 600)`, ready: `document.querySelector("#ovKids [data-sel]") && !document.querySelector("#ovKids .spin") && document.querySelector("#ovSelN")`, settle: 1800 },
  ...[1, 2, 3, 4].map(n => ({ name: "wiz" + n, js: `wizOpen(cur, ${n}, false)`, ready: `document.querySelector("#wizPop .wz")`, settle: 300 })),   // the guided setup, one shot per step
  { name: "today", js: `goTab("home")`, ready: `document.querySelector(".hero")` },
  // battery history sheet (2026-11-22): tap the battery cell of "Kid's phone"
  { name: "bat", js: `goTab("home"); setTimeout(() => document.querySelector("#pcBat").click(), 400)`, ready: `document.querySelector("#batSvg")`, settle: 500 },
  // runtime language switch (the other dictionary loads on demand, then the page redraws): must look like the other language's "today"
  { name: "langsw", js: `goTab("home"); window.__sw = lang === "vi" ? "en" : "vi"; setLang(window.__sw)`, ready: `window.__sw && lang === window.__sw && document.documentElement.lang === window.__sw && document.querySelector(".hero")`, settle: 600 },
  { name: "apps", js: `goTab("apps")`, ready: `document.querySelector("[data-sec] .clh")`, settle: 800 },
  // family chat sheet: sample thread (kid, parent, own pending send), the sheet draws st.chat
  { name: "chat", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "a", t: n - 3600e3, from: "p", who: "Mẹ", text: "Con về nhà chưa? Ăn cơm nhé." }, { id: "b", t: n - 3500e3, from: "k", who: "", text: "Dạ con sắp về, con đang ở nhà bạn." }, { id: "c", t: n - 600e3, from: "p", who: "Bố", text: "Nhớ mang áo mưa, chiều nay có mưa lớn lắm đó con." }, { id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "e", t: n - 400e3, from: "k", who: "", text: "", att: { id: "f2", name: "voice.webm", mime: "audio/webm", size: 52000, kind: "audio", dur: 12, st: "ok" } }, { id: "g", t: n - 300e3, from: "p", who: "Bố", text: "", att: { id: "f3", name: "bai-tap-toan-tuan-12.pdf", mime: "application/pdf", size: 880000, kind: "file", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }, { id: "i", t: n - 100e3, from: "k", who: "", text: "", att: { id: "f5", name: "old.jpg", mime: "image/jpeg", size: 1, kind: "image", st: "gone" } }]; st.freeMb = 600; msgSheet(); (chatPend[cur.id] = []).push({ from: "p", who: "", text: "OK", t: n, local: 1 }); chatDraw()`, ready: `document.querySelector("#chatBox .cb")`, settle: 400 },
  { name: "chat-menu", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "a", t: n - 3600e3, from: "p", who: "Mẹ", text: "Con về nhà chưa? Ăn cơm nhé." }, { id: "b", t: n - 3500e3, from: "k", who: "", text: "Dạ con sắp về, con đang ở nhà bạn." }, { id: "c", t: n - 600e3, from: "p", who: "Bố", text: "Nhớ mang áo mưa, chiều nay có mưa lớn lắm đó con." }, { id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "e", t: n - 400e3, from: "k", who: "", text: "", att: { id: "f2", name: "voice.webm", mime: "audio/webm", size: 52000, kind: "audio", dur: 12, st: "ok" } }, { id: "g", t: n - 300e3, from: "p", who: "Bố", text: "", att: { id: "f3", name: "bai-tap-toan-tuan-12.pdf", mime: "application/pdf", size: 880000, kind: "file", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }, { id: "i", t: n - 100e3, from: "k", who: "", text: "", att: { id: "f5", name: "old.jpg", mime: "image/jpeg", size: 1, kind: "image", st: "gone" } }]; st.freeMb = 600; msgSheet(); (chatPend[cur.id] = []).push({ from: "p", who: "", text: "OK", t: n, local: 1 }); chatDraw(); document.querySelector("#chatMore").click()`, ready: `document.querySelector("#chatBox .cb")`, settle: 400 },
  { name: "chat-att", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "a", t: n - 3600e3, from: "p", who: "Mẹ", text: "Con về nhà chưa? Ăn cơm nhé." }, { id: "b", t: n - 3500e3, from: "k", who: "", text: "Dạ con sắp về, con đang ở nhà bạn." }, { id: "c", t: n - 600e3, from: "p", who: "Bố", text: "Nhớ mang áo mưa, chiều nay có mưa lớn lắm đó con." }, { id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "e", t: n - 400e3, from: "k", who: "", text: "", att: { id: "f2", name: "voice.webm", mime: "audio/webm", size: 52000, kind: "audio", dur: 12, st: "ok" } }, { id: "g", t: n - 300e3, from: "p", who: "Bố", text: "", att: { id: "f3", name: "bai-tap-toan-tuan-12.pdf", mime: "application/pdf", size: 880000, kind: "file", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }, { id: "i", t: n - 100e3, from: "k", who: "", text: "", att: { id: "f5", name: "old.jpg", mime: "image/jpeg", size: 1, kind: "image", st: "gone" } }]; st.freeMb = 600; msgSheet(); (chatPend[cur.id] = []).push({ from: "p", who: "", text: "OK", t: n, local: 1 }); chatDraw(); document.querySelector("#attBtn").click()`, ready: `document.querySelector("#chatBox .cb")`, settle: 400 },
  { name: "chat-gal", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "e", t: n - 400e3, from: "k", who: "", text: "", att: { id: "f2", name: "voice.webm", mime: "audio/webm", size: 52000, kind: "audio", dur: 12, st: "ok" } }, { id: "g", t: n - 300e3, from: "p", who: "Bố", text: "", att: { id: "f3", name: "bai-tap-toan-tuan-12.pdf", mime: "application/pdf", size: 880000, kind: "file", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }]; galOpen()`, ready: `document.querySelector("#galB .gt")`, settle: 400 },
  // select mode (select.js): chat thread with 2 messages ticked + the bar; the gallery with a day group
  { name: "chat-sel", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "a", t: n - 86400e3 * 2, from: "p", who: "Mẹ", text: "Con về nhà chưa?" }, { id: "b", t: n - 3500e3, from: "k", who: "", text: "Dạ con sắp về." }, { id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }]; msgSheet(); chatSel.on = true; chatSel.ids.add("d"); chatSel.ids.add("h"); chatDraw()`, ready: `document.querySelector("#chatBox .selon") && document.querySelector(".selbar")`, settle: 400 },
  { name: "chat-gal-sel", js: `goTab("home"); const n = Date.now(); st.chat = [{ id: "d", t: n - 500e3, from: "k", who: "", text: "", att: { id: "f1", name: "IMG_1.jpg", mime: "image/jpeg", size: 2400000, kind: "image", st: "ok" } }, { id: "g", t: n - 86400e3 * 3, from: "p", who: "Bố", text: "", att: { id: "f3", name: "bai-tap-toan-tuan-12.pdf", mime: "application/pdf", size: 880000, kind: "file", st: "ok" } }, { id: "h", t: n - 200e3, from: "k", who: "", text: "", att: { id: "f4", name: "clip.mp4", mime: "video/mp4", size: 18000000, kind: "video", dur: 9, st: "ok" } }]; galOpen(); galSel.on = true; galSel.ids.add("f1"); galDraw()`, ready: `document.querySelector("#galB .selon") && document.querySelector(".selbar")`, settle: 400 },
  // Seen tab: photo history (one failed try, select mode on) + listening history (recorded, not recorded, from another device)
  { name: "seen-hist", js: `edit.watch = { notif: { on: false, kw: false, apps: "all", list: [] }, words: [], cam: true, sms: false }; st.snaps = { n: 4, mb: 3 }; st.freeMb = 700; const n = Date.now(); st.listen = Object.assign(st.listen || {}, { hist: [{ at: n - 86400e3 * 2, dur: 125, via: "relay" }] }); goTab("seen"); setTimeout(() => { snapH.k = cur; snapH.done = true; snapH.more = true; snapH.rows = [{ id: "00000000000000a1", at: n - 600e3, cam: "front", flash: "off", ok: true, size: 61000 }, { id: "00000000000000a2", at: n - 900e3, cam: "back", flash: "on", ok: false, err: "screen off" }, { id: "00000000000000a3", at: n - 3600e3, cam: "front", flash: "off", ok: true, size: 58000 }, { id: "00000000000000a4", at: n - 86400e3, cam: "back", flash: "auto", ok: true, size: 120000 }]; snapPick.on = true; snapPick.ids.add("00000000000000a1"); snapDraw(); lh.k = cur; lh.rows = [{ id: n - 1800e3, kid: cur.id, at: n - 1800e3, dur: 184000, via: "lan", rec: true, mime: "audio/webm", size: 720000 }, { id: n - 7200e3, kid: cur.id, at: n - 7200e3, dur: 42000, via: "relay", rec: false, size: 0 }]; lhDraw(); }, 200)`, ready: `document.querySelector("#snapHist .selon") && document.querySelector("#seenListen .fxrow")`, settle: 500, scrollTo: "#snapHist" },
  { name: "more", js: `goTab("home"); moreSheet()`, ready: `document.querySelector("#scrGo")`, settle: 400 },
  { name: "rules", poke: 1, js: `goTab("rules")`, ready: `document.querySelector("[data-sec]")`, scrollTo: "[data-sec=r-day]" },
  { name: "where", js: `goTab("where")`, ready: `document.querySelector("#map .layer")`, tiles: true, settle: 800 },
  { name: "screen-start", js: `goTab("screen")`, ready: `document.querySelector("#s-go") && !document.querySelector("#s-start").hidden`, settle: 400 },
  { name: "screen", js: `goTab("screen"); scr.on = cur; screenStart()`, ready: `document.querySelector("#s-img") && document.querySelector("#s-img").complete && document.querySelector("#s-img").naturalWidth > 0`, settle: 800 },
  { name: "files", js: `goTab("files")`, ready: `document.querySelector("#fxOut .app, #fxOut .li, #fxOut > *")`, settle: 600 },
  { name: "seen", js: `edit.watch = { notif: { on: true, kw: false, apps: "except", list: [] }, words: ["chết", "ma tuý"], cam: true, sms: true }; seenFeedL = { k: cur, items: [{ id: 1, t: Date.now() - 600000, pkg: "com.zing.zalo", app: "Zalo", title: "Bin", text: "tối nay đi đâu không", hit: "" }, { id: 2, t: Date.now() - 60000, pkg: "com.facebook.orca", app: "Messenger", title: "Nam", text: "mình chán quá muốn chết", hit: "chết" }] }; goTab("seen")`, ready: `document.querySelector("#seenWords")`, settle: 600 },
  { name: "start", js: `$("#app").innerHTML = head("KidGuard").replace("</h1>", '</h1><a class="rb btn" href="/">' + ic("house") + "</a>") + anyCard() + startCard(true); anyWire(); const o = document.querySelector(".ownr"); if (o) o.open = true`, ready: `document.querySelector(".start")`, settle: 300 },
  { name: "log", js: `goTab("log")`, ready: `document.querySelector(".lgr, #tab .empty")`, settle: 600 },
  { name: "log-use", js: `goTab("apps"); setTimeout(() => document.querySelector("#apUse").click(), 300)`, ready: `document.querySelector(".lgr .ic")`, settle: 600 },
  { name: "log-use-app", js: `goTab("apps"); setTimeout(() => document.querySelector("#apUse").click(), 300); setTimeout(() => document.querySelectorAll(".lgr.use")[2].click(), 1200)`, ready: `document.querySelector(".lgr.use.dim")`, settle: 400 },
  // regression (owner 2026-11-16): a chip tap redraws the page and threw the chip row back to the start; "not ready" = broken again
  { name: "log-chipx", js: `goTab("apps"); setTimeout(() => document.querySelector("#apUse").click(), 300); setTimeout(() => { const r = [...document.querySelectorAll(".chips.scroll")].find(r => r.querySelector("[data-fe='']")); r.scrollLeft = 120; [...r.children].find(x => x.offsetLeft - r.offsetLeft > 140 && x.offsetLeft - r.offsetLeft + x.offsetWidth < 400).click(); }, 1200); setTimeout(() => { document.title = "X" + [...document.querySelectorAll(".chips.scroll")].find(r => r.querySelector("[data-fe='']")).scrollLeft; }, 1600)`, ready: `+document.title.slice(1) > 0`, settle: 200 },
  // regression (owner 2026-11-16): the "waiting for the phone" chip squeezed the name column until the status line wrapped to 4 lines
  { name: "head-pending", js: `goTab("home"); setInterval(() => { pendingRules = { rev: ((st.rules && st.rules.rev) || 0) + 1e9 }; pendSince = pendSince || Date.now() - 120000; paintSave(); }, 200)`, ready: `document.querySelector("#saveChip .tag")`, settle: 300 },
  // replay controls (2026-11-23): "next" from the start of the day steps to the first point, speed chip cycles
  { name: "where-step", js: `goTab("where"); setTimeout(() => { scrSet(0); $("#scNext").click(); $("#scNext").click(); $("#scSpd").click(); }, 800)`, ready: `document.querySelector("#scL b") && $("#scSpd").textContent === "2×"`, settle: 600 },
  { name: "where-cal", js: `goTab("where"); setTimeout(() => document.querySelector("[data-dp=trk]").click(), 600)`, ready: `document.querySelector("#dpPop .dpg button")`, settle: 400 },
  { name: "log-cal", js: `goTab("log"); setTimeout(() => document.querySelector("[data-dp=log]").click(), 400)`, ready: `document.querySelector("#dpPop .dpg button")`, settle: 400 },
  { name: "settings-rules", js: `goTab("set"); setTimeout(() => { const r = document.querySelector('[data-sec="s-kid"] summary, [data-sec="s-kid"] [role=button], [data-sec="s-kid"]'); if (r) r.click(); }, 400)`, ready: `document.querySelector("#rxSave")`, scrollTo: "#rxSave", settle: 600 },
  // copy rules from another kid end to end: Mia's dailyMin lands in Bin's edit, Bin's PIN state stays (2026-11-23)
  { name: "rules-copy", js: `goTab("set"); setTimeout(() => { document.querySelector('[data-sec="s-kid"]').click(); setTimeout(() => { window.__d0 = edit.dailyMin; $("#rxCopy").click(); setTimeout(() => $("#askOk").click(), 700); }, 400); }, 400)`, ready: `/quy tắc|Rules applied/i.test($("#toast").textContent)`, settle: 300 },
  { name: "settings-away", js: `goTab("set")`, ready: `document.querySelector("#awaySec")`, scrollTo: "#awaySec", settle: 600 },
  // the watch card with two connected watches (parents list of kind "watch", docs/protocol.md "Watch connect")
  { name: "settings-watch", js: `cur.relay = cur.relay || { url: "https://r.example", id: "0123456789abcdef", token: "e".repeat(48) }; st.relay = st.relay || { on: true }; rpS = { k: cur, at: Date.now(), list: [{ id: "a1", name: "Mẹ", kind: "web", at: Date.now() - 864e6, seen: Date.now() - 6e4, me: true }, { id: "b2", name: "Đồng hồ của bố", kind: "watch", dev: "Zepp - Amazfit Bip Max", at: Date.now() - 1728e5, seen: Date.now() - 3e5 }, { id: "c3", name: "Wear OS ảo", kind: "watch", dev: "Wear OS - sdk_gwear_arm64", at: Date.now() - 36e5, seen: Date.now() - 9e5 }], pinKnown: true }; goTab("set"); setTimeout(() => { subOpen("s-watch"); setTimeout(() => { wPaint(); const g = document.querySelector("#s-watch details, details"); document.querySelectorAll("details").forEach(d => d.open = true); }, 200); }, 300)`, ready: `document.querySelector("#wList [data-wx]")`, settle: 700 },
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
      const o = getComputedStyle(p).overflowX; if (o === "visible" || getComputedStyle(p).display === "contents") continue;
      if (o === "auto" || o === "scroll") break;
      const pr = p.getBoundingClientRect(), cut = Math.round(r.right - pr.right);
      if (cut > 1) { const k = "c" + name(e); if (!seen.has(k)) { seen.add(k); out.push("cut off " + cut + "px by " + name(p).split(' "')[0] + ": " + name(e)); } }
      break;
    }
  }
  // a button label on two lines (owner 2026-11-17: "button bị rớt hàng"; a big Android font size = a narrower --width)
  for (const b of document.querySelectorAll("button, .seg label, a.btn")) {
    const r = b.getBoundingClientRect(); if (!r.width || getComputedStyle(b).visibility === "hidden" || b.closest("[hidden]")) continue;
    const rg = document.createRange(), tops = new Set();
    for (const n of b.childNodes) if (n.nodeType === 3 && n.textContent.trim()) { rg.selectNodeContents(n); for (const q of rg.getClientRects()) if (q.width > 2) tops.add(Math.round(q.top)); }
    if (tops.size > 1) { const k = "w" + name(b); if (!seen.has(k)) { seen.add(k); out.push("label wraps (" + Math.round(r.width) + "px): " + name(b)); } }
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

const kidsSeed = () => JSON.stringify(Object.entries(PORTS).map(([n, p], i) => ({ id: ["5b1e0c9a7d3f4e21", "9c4d2e7b1a6f3058", "2f8a6b1c9e4d7035"][i], token: String(i + 1).repeat(48).slice(0, 48).replace(/\d/g, c => "abcdef"[+c]), name: { bin: "Bin", mia: "Mia", tom: "Tom" }[n], via: "lan", target: `http://127.0.0.1:${p}`, lv: 1 })).concat(FAKE_APP ? [
  { id: "0a1b2c3d4e5f6071", token: "c".repeat(48), name: "Nam", via: "relay", target: "https://kidguard.99-hoangtran.workers.dev" },
  { id: "1a2b3c4d5e6f7081", token: "d".repeat(48), name: "Lan", via: "relay", target: "https://kg-relay.99-hoangtran.workers.dev" }] : []));

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
      if (${FAKE_APP}) {
        // the app's bridge (MainActivity JS interface): settings stubs + the kid list in localStorage
        const P = { notifOn: "true", getNotify: "true", appVersion: "0.9.44", takeOpen: "null", takeSettings: "false", getLock: "", getDigest: "", getKidNotif: "true", getHistoryDays: "30", canNotify: "true",
          getKids: () => localStorage.getItem("kg.kids"), setKids: s => localStorage.setItem("kg.kids", s), getLang: () => localStorage.getItem("kg.lang") || "",
          notifHealth: () => JSON.stringify({ lastCheck: Date.now() - 240e3, push: "part", pushBad: "Nam", pushAt: Date.now() - 600e3, battery: true, enabled: true, perm: true }) };
        window.KGApp = new Proxy({}, { get: (o, m) => typeof P[m] === "function" ? P[m] : () => m in P ? P[m] : "" });
        // its proxy: /k/<b64url target>/rest -> target + rest; the killed relay answers Cloudflare's 1101 page, the released kid 410
        const F0 = window.fetch;
        window.fetch = (u, o) => { const m = /^[/]k[/]([A-Za-z0-9_-]+)([/].*)$/.exec(String(u));
          if (String(u) === "/ui/pack.json") return Promise.resolve(new Response(JSON.stringify(window.__uiPack), { headers: { "Content-Type": "application/json" } }));
          if (!m) return F0(u, o);
          const tg = atob(m[1].replace(/-/g, "+").replace(/_/g, "/"));
          // the app's proxy keeps the last state: Nam (killed relay) comes back as a 25 h old snapshot, like the owner's phone
          if (tg.includes("kidguard.99-")) return m[2].endsWith("/state") ? F0(localStorage.getItem("kg.binT") + "/api/state", { headers: { "X-KG-Token": "a".repeat(48) } }).then(r => r.json())
            .then(s => new Response(JSON.stringify({ state: Object.assign(s, { t: Date.now() - 25 * 3600e3 }), seen: Date.now() - 25 * 3600e3 }), { headers: { "Content-Type": "application/json", "X-KG-Cached": String(Date.now() - 25 * 3600e3), "X-KG-Why": "cf:1101" } }))
            : Promise.resolve(new Response("error code: 1101", { status: 500 }));
          if (tg.includes("kg-relay.99-")) return Promise.resolve(new Response(JSON.stringify({ error: "released", at: Date.now() - 26 * 3600e3 }), { status: 410, headers: { "Content-Type": "application/json" } }));
          return F0(tg + m[2], o); };
        window.__uiPack = { src: "pack", ver: "fca291b6f0a0", seq: 1795007023 };
      }
      try { localStorage.setItem("kg.binT", "http://127.0.0.1:${PORTS.bin}"); localStorage.setItem("kg.kids", ${JSON.stringify(kidsSeed())}); localStorage.setItem("kg.lang", "${lang}"); localStorage.setItem("kg.theme", "${THEME}"); } catch {}
    })()` });
    const errors = [], faultLog = [];
    // parent app: the bridge exists only once the page runs, and its proxy forwards only to kids in its own list
    if (EXT) { await c.send("Page.navigate", { url: PAGE }); await until(c, `!!window.KGApp && document.readyState === "complete"`, 15000); await evalIn(c, `KGApp.setKids(${JSON.stringify(kidsSeed())}); 0`); }
    for (const shot of SHOTS) {
      if (ONLY && !ONLY.includes(shot.name)) continue;
      // fresh page per shot: no state leaks between them
      await c.send("Page.navigate", { url: PAGE });
      await sleep(400);
      if (shot.bare) await until(c, `typeof appBoot === "function" && document.readyState === "complete" && typeof kids !== "undefined"`, 15000);
      else if (FAKE_APP && await until(c, `typeof kids !== "undefined" && kids.some(k => k.name === "Bin") && document.readyState === "complete" && typeof screenStop === "function"`, 15000)) await evalIn(c, `open(kids.find(k => k.name === "Bin")); 0`);
      else if (EXT && await until(c, `typeof kids !== "undefined" && kids.some(k => k.name === "Bin") && document.readyState === "complete" && typeof screenStop === "function"`, 15000)) await evalIn(c, `open(kids.find(k => k.name === "Bin")); 0`);   // app: the page starts on the kid list
      if (!shot.bare && !(await until(c, `typeof st !== "undefined" && st && st.apps && document.querySelector(".hero")`, 20000))) errors.push(shot.name + ": page did not open the kid (" + await evalIn(c, `JSON.stringify({ u: location.href, kids: typeof kids !== "undefined" ? kids.map(k => k.name) : null, cur: typeof cur !== "undefined" && cur ? cur.name : null, err: typeof lastErr !== "undefined" ? lastErr : null })`) + ")");
      await evalIn(c, `${shot.js}; 0`);
      if (!(await until(c, shot.ready, 20000))) errors.push(shot.name + ": not ready (" + shot.ready.slice(0, 50) + ")");
      if (shot.tiles) { await sleep(500); await until(c, `[...document.querySelectorAll("#map img")].length > 0 && [...document.querySelectorAll("#map img")].every(i => i.complete && i.naturalWidth > 0)`, 20000) || errors.push(shot.name + ": map tiles not all loaded"); }
      await sleep(shot.settle || 400);
      if (AUDIT) {
        // list -> sub-screen (phone) / two panes (wide): audit the list, then every section on its own (one PNG each)
        await sleep(500);
        for (const f of await evalIn(c, `(${layoutAudit})()`)) errors.push(shot.name + ": " + f);
        for (const f of await evalIn(c, `(${uxLint})()`)) errors.push(shot.name + ": " + f);
        const ids = await evalIn(c, `[...document.querySelectorAll("[data-sec]")].filter(s => !s.parentElement.closest("[data-sec]")).map(s => s.dataset.sec)`);
        const nested = await evalIn(c, `document.querySelectorAll("[data-sec] [data-sec]").length`);
        if (nested) errors.push(shot.name + ": " + nested + " nested sec() (the list/sub-screen only handles top-level ones)");
        for (const id of ids) {
          await evalIn(c, `subOpen(${JSON.stringify(id)}); 0`); await sleep(350);
          for (const f of await evalIn(c, `(${layoutAudit})()`)) errors.push(shot.name + " [" + id + "]: " + f);
          for (const f of await evalIn(c, `(${uxLint})()`)) errors.push(shot.name + " [" + id + "]: " + f);
          if (shot.poke) for (const f of await evalIn(c, `(${poke})()`)) errors.push(shot.name + " [" + id + "]: " + f);
          if (!EXT) { const rr = await c.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true, clip: await evalIn(c, `({ x: 0, y: 0, width: innerWidth, height: Math.min(document.documentElement.scrollHeight, 6000), scale: 1 })`) }); fs.writeFileSync(path.join(outDir, shot.name + "-" + id + ".png"), Buffer.from(rr.data, "base64")); }
        }
        if (shot.poke) { await sleep(1500); for (const f of await evalIn(c, `(${putCheck})()`)) errors.push(shot.name + ": " + f); }
        await evalIn(c, `if (typeof subId !== "undefined" && subId) subClose(true); 0`); await sleep(500);
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
          await evalIn(c, `subOpen(${JSON.stringify(id)}); 0`); await sleep(300);
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
finally {   // SIGTERM alone sometimes leaves headless Chrome holding the CDP port (the next run then hangs)
  if (!EXT) { const gone = new Promise(r => chrome.once("exit", r)); chrome.kill(); if (await Promise.race([gone.then(() => 1), sleep(3000)]) !== 1) chrome.kill("SIGKILL"); }
  await sleep(300); fs.rmSync(prof, { recursive: true, force: true }); }
console.log("done ->", OUT);
process.exit(0);

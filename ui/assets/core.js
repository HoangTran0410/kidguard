// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// One page for both: served by the child phone on the LAN (/api/...) and by the relay worker (/c/<id>/...).
const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
// `lang`, `L`, `LANGS`, `loadLang` come from lang.js (loaded first: only the active language's dictionary is fetched)
const hasT = k => k in L[lang];
// t("key", a, b): chosen language -> the key itself (vi and en have the same keys: scripts/check-assets.mjs). {0} {1} = args; a {one, other} value picks a plural form by the first arg.
function t(k, ...a) {
  let v = L[lang][k] ?? k;
  if (v && typeof v === "object" && !Array.isArray(v)) { let p = "other"; try { p = new Intl.PluralRules(lang).select(+a[0]); } catch {} v = v[p] ?? v.other; }
  return typeof v === "string" ? v.replace(/\{(\d)\}/g, (m, i) => a[i] ?? "") : v;
}
const tc = (pre, code, dflt) => hasT(pre + code) ? t(pre + code) : dflt;   // a code from the phone -> its text, unknown codes as given (or dflt)
const langSel = (id = "lang") => `<select id="${id}" class="lang" aria-label="${esc(t("lang"))}">${LANGS.map(([v, n]) => `<option value="${v}" ${v === lang ? "selected" : ""}>${n}</option>`).join("")}</select>`;
function applyLang() {
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-t]").forEach(e => e.textContent = t(e.dataset.t));
  document.querySelectorAll("[data-ta]").forEach(e => e.setAttribute("aria-label", t(e.dataset.ta)));
}
let reScreen = null;   // redraws the current non-kid screen after a language switch
// tell the parent app, so its native notifications use the same language
const appLang = () => { try { window.KGApp && KGApp.setLang && KGApp.setLang(lang); } catch {} };
async function setLang(l) {
  if (!LANGS.some(x => x[0] === l) || l === lang) return;
  await loadLang(l);   // first switch fetches the other dictionary
  if (!L[l]) { const s = $("select.lang"); if (s) s.value = lang; return; }   // did not load (offline): stay as is
  lang = l; try { localStorage.setItem("kg.lang", l); } catch {}
  appLang(); applyLang();
  if (cur) { lastSig = ""; render(); } else if (ov) { ovShell(); ovDraw(); } else if (reScreen) reScreen();
}
document.addEventListener("change", e => { if (e.target && e.target.classList && e.target.classList.contains("lang")) setLang(e.target.value); });
const KEY = "kg.kids";
// App mode: running inside the KidGuard parent Android app (WebView + local proxy, bridge window.KGApp).
const APP = !!window.KGApp;
// on-device parent page (child app AdminActivity): the session rides an HttpOnly cookie, JS never sees a token
const LOCAL = !APP && !!window.KGLocal;
// one slim line (it used to fill half a phone screen); "Exit" ends the session, the full rule is in its title
const localBar = () => LOCAL ? `<div class="lbar" title="${esc(t("local.why"))}">${ic("smartphone")}<span>${t("local.bar")}</span><button id="localEnd">${ic("x")} ${t("local.end")}</button></div>` : "";
function localWire() { const b = $("#localEnd"); if (b) b.onclick = () => { try { KGLocal.end(); } catch {} }; }
function localOver() { gen++; clearInterval(timer); cur = null; $("#app").className = "solo"; $("#app").innerHTML = `<div class="card mute">${ic("lock")} ${t("local.over")}</div>`; try { KGLocal.end(); } catch {} }
async function localBoot() {
  let hello = null;
  try { const r = await fetch("/api/hello"); if (r.ok) hello = await r.json(); } catch {}
  if (!hello || !hello.id) return localOver();
  let lv = 0; try { lv = window.KGLocal.lvOffered() ? 1 : 0; } catch {}
  open({ id: hello.id, token: "", name: t("kid"), via: "lan", lv });   // not in `kids`: never saved (the phone keeps `lv`)
}
const b64u = s => btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const kgCall = (m, ...a) => { try { return window.KGApp[m](...a); } catch { return null; } };
const load = () => {
  try {
    // the app's WebView origin changes with the proxy port, so prefer the app's own copy when the bridge offers one
    const fromApp = APP && typeof window.KGApp.getKids === "function" ? kgCall("getKids") : null;
    return JSON.parse(fromApp || localStorage.getItem(KEY) || "[]");
  } catch { return []; }
};
const save = k => {
  // healAt / fb / relayTry: per page load only (core.js relocate / learnAddrs)
  try { localStorage.setItem(KEY, JSON.stringify(k, (key, v) => key === "healAt" || key === "fb" || key === "relayTry" || key === "gateOff" || key === "migTry" || key === "icm" || key === "ikP" ? undefined : v)); } catch {}
  if (APP) kgCall("setKids", JSON.stringify(k.filter(x => x.target).map(x => ({ name: x.name, target: x.target, via: x.via, id: x.id, token: x.token, lv: x.lv, relay: x.relay }))));
};
let kids = load(), cur = null, st = null, pendingRules = null, timer = null, edit = null, tab = "home", lastErr = "", viewDay = null, histDays = null, cachedAt = 0, srvWhy = "", seenAt = 0, answered = new Set(), histSel = -1, lastNet = false;
const onLan = () => !location.pathname.startsWith("/c/") && !isRelayOrigin;
let isRelayOrigin = false;
// v0.7 basic mode: no Device Owner (old phones without mode = owner as before)
const isBasic = () => !!(st && st.mode && st.mode !== "owner");
const needOwner = (what = t("no.this")) => `<p class="mute keep">${ic("lock")} ${t("needOwner", what)}</p>`;
// Feature gating (state.features / state.caps from phones >= 0.9.8; an older phone has neither = everything counts as available).
const feat = id => { const f = st && Array.isArray(st.features) && st.features.find(x => x.id === id); return f ? { ok: !!f.ok, missing: f.missing || [] } : { ok: true, missing: [] }; };
// usable on this phone? Phones with state.features say so (owner OR a capability alternative, e.g. WRITE_SECURE_SETTINGS); older ones: not in basic mode
const canFeat = id => st && Array.isArray(st.features) ? feat(id).ok : !isBasic();
const CAPS = ["owner", "admin", "acc", "usage", "location", "notify", "battery", "home", "adb", "paired", "wss"];
// KidGuard's own adb needs wireless debugging (Android 11+): on an older phone say that, not "turn on USB debugging"
const capName = c => c === "adb" && st && +st.sdk > 0 && +st.sdk < 30 ? t("cap_adbOld") : hasT("cap_" + c) ? t("cap_" + c) : c;
// section id -> feature id (sec() dims the body of a section whose feature is not ok), tab id -> feature id (render())
const FEAT_SEC = { "r-daily": "limits", "r-grp": "limits", "r-bed": "limits", "r-wind": "limits", "r-sch": "limits", "r-dns": "dns", "r-anti": "noUninstall",
  "r-kind": "launcherLock", "r-zone": "location", "r-trk": "location", "a-clean": "debloat" };
const FEAT_TAB = { apps: "block", screen: "screen", where: "location", rep: "usage" };
// "Cần: Device Owner, Trợ năng" + how to get it; empty when the feature is available
function gateNote(id) {
  const f = feat(id); if (f.ok) return "";
  return `<div class="needline" role="note"><span class="tag warn">${ic("lock")} ${t("cap_need", esc(f.missing.map(capName).join(", ") || "?"))}</span>
    <span class="mute">${f.missing.map(c => hasT("cap_how_" + c) ? t("cap_how_" + c) : "").filter(Boolean).map(esc).join(" ")}</span></div>`;
}

const ic = n => `<svg class="si" aria-hidden="true"><use href="#i-${n}"/></svg>`;
const dot = c => `<i class="dot ${c}"></i>`;
const noEmoji = s => String(s ?? "").replace(/[\p{Extended_Pictographic}\uFE0F\u200D]/gu, "").replace(/^\s+/, "");
const TI = { ok: ["circle-check", "var(--ok)"], err: ["circle-x", "var(--bad)"], warn: ["triangle-alert", "var(--warn)"], send: ["send", ""] };
function toast(m) { const el = $("#toast"); const k = /^(ok|err|warn|send)\|/.exec(m); el.innerHTML = k ? `<span style="color:${TI[k[1]][1]}">${ic(TI[k[1]][0])}</span> ${esc(m.slice(k[0].length))}` : esc(m); el.style.display = "block"; clearTimeout(toast.t); toast.t = setTimeout(() => el.style.display = "none", 2500); }
function fmt(ms) { if (ms < 0) return "—"; const m = Math.round(ms / 60000); return m < 60 ? t("fmt.m", m) : m % 60 ? t("fmt.hm", Math.floor(m / 60), m % 60) : t("fmt.h", Math.floor(m / 60)); }
function ago(at) {
  const s = Math.max(0, Math.round((Date.now() - at) / 1000)), [v, u] = s < 60 ? [s, "second"] : s < 3600 ? [Math.round(s / 60), "minute"] : [Math.round(s / 3600), "hour"];
  try { return new Intl.RelativeTimeFormat(lang, { numeric: "always" }).format(-v, u); } catch { return v + " " + u; }
}
// block reasons (decide() codes, docs/protocol.md)
const reason = c => tc("rs.", c, t("rs.locked"));
const hhmm = at => new Date(at).toLocaleTimeString(lang, { hour: "numeric", minute: "2-digit" });
// event time: "16:19" today, "Hôm qua 16:19", "7 thg 11 16:19" (clock time everywhere, never "n giờ trước")
const evAt = at => { const k = dkey(at); return (k === dkey(Date.now()) ? "" : (k === dkey(Date.now() - 864e5) ? t("yesterday") : dm(k)) + " ") + hhmm(at); };
const dm = k => new Date(+k.slice(0, 4), +k.slice(5, 7) - 1, +k.slice(8, 10)).toLocaleDateString(lang, { day: "numeric", month: "short" });   // "YYYY-MM-DD" -> "7 thg 11" / "Nov 7"
// ---- UI kit: markup helpers shared by every tab. Never hand-write these shapes; list in docs/flows/parent-page.md "Components" ----
const dtFull = at => new Date(at).toLocaleString();   // date + time in the page language
// empty state: icon tile + text (html). The text may carry a button / <br>.
const emptyBox = (icn, html) => `<div class="empty"><span class="e">${ic(icn)}</span>${html}</div>`;
// banner: kind "warn" | "bad" | "ok" | "info" | "" ; icn (Lucide name or ""); html = message (already escaped); style = extra inline style
const alertBox = (kind, icn, html, style) => `<div class="alert${kind ? " " + kind : ""}"${style ? ` style="${style}"` : ""}>${icn ? ic(icn) + " " : ""}${html}</div>`;
// small status pill: kind "ok" | "warn" | "bad" | "t-info" | ""; icn optional; html already escaped
const tagEl = (kind, icn, html, style) => `<span class="tag${kind ? " " + kind : ""}"${style ? ` style="${style}"` : ""}>${icn ? ic(icn) + " " : ""}${html}</span>`;
// toggle chip: attrs = its data-* attributes (string), label html, on = pressed. Wrap several in chipRow().
const chipBtn = (attrs, label, on) => `<button type="button" ${attrs} class="${on ? "on" : ""}" aria-pressed="${!!on}">${label}</button>`;
// chip row: btns = array of chipBtn; o = { scroll: one line that scrolls sideways, cls: extra class, id, style, aria: group label (plain text) }
const chipRow = (btns, o = {}) => `<div class="chips${o.scroll ? " scroll" : ""}${o.cls ? " " + o.cls : ""}"${o.id ? ` id="${o.id}"` : ""}${o.style ? ` style="${o.style}"` : ""}${o.aria ? ` role="group" aria-label="${esc(o.aria)}"` : ""}>${btns.join("")}</div>`;
// tappable list row (.li): leading icon tile, title (html), optional sub line (html), chevron. attrs = id / data-* of the button. Put rows inside <div class="list">.
const navRow = (attrs, icn, title, sub) => `<button type="button" class="li" ${attrs}><span class="ib">${ic(icn)}</span><span class="x"><b>${title}</b>${sub ? `<span class="sub">${sub}</span>` : ""}</span>${ic("chevron-right")}</button>`;
const DNS = () => [["", t("off")], ["family-filter-dns.cleanbrowsing.org", "CleanBrowsing Family", 1], ["family.adguard-dns.com", "AdGuard Family", 1], ["family.cloudflare-dns.com", "Cloudflare Family", 0]];
// [host, name, forces SafeSearch + YouTube restricted via DNS]. Cloudflare Family only blocks adult/malware domains.
const WD_STEPS = [0, 15, 30, 45, 60];
// rules from older phones may lack v0.2 fields
function norm(e) {
  e.hidden = Array.isArray(e.hidden) ? e.hidden : [];   // v0.8 preinstalled apps hidden by the parent
  e.dailyByDay = Array.isArray(e.dailyByDay) && e.dailyByDay.length === 7 ? e.dailyByDay : [];
  e.schedules = e.schedules || []; e.approved = e.approved || []; e.safeDns = e.safeDns || ""; e.safeDnsPauseUntil = +e.safeDnsPauseUntil || 0; e.noVpn = !!e.noVpn; e.noIncognito = !!e.noIncognito; e.webBlock = Array.isArray(e.webBlock) ? e.webBlock : []; e.webAllow = Array.isArray(e.webAllow) ? e.webAllow : []; e.frpIds = Array.isArray(e.frpIds) ? e.frpIds.map(String) : [];
  e.trackMin = e.trackMin === undefined ? 5 : +e.trackMin; e.lockLocation = e.lockLocation !== false; e.noAirplane = e.noAirplane !== false; e.showWatching = e.showWatching !== false; e.autoUpdate = e.autoUpdate !== false; e.tsKeep = !!e.tsKeep; e.blockNew = !!e.blockNew; e.monitor = !!e.monitor;
  e.kidClean = !!e.kidClean;
  { const w = e.watch || {}, n = w.notif || {};   // 0.9.29 what the parent may read (docs/protocol.md "Monitoring")
    e.watch = { notif: { on: !!n.on, kw: !!n.kw, apps: ["only", "except"].includes(n.apps) ? n.apps : "all", list: Array.isArray(n.list) ? n.list.map(String).slice(0, 200) : [] },
      words: (Array.isArray(w.words) ? w.words : []).map(x => String(x).trim().slice(0, 40)).filter(Boolean).slice(0, 30), cam: !!w.cam, sms: !!w.sms }; }
  e.calls = { block: ["unknown", "hidden"].includes(e.calls && e.calls.block) ? e.calls.block : "off", allow: Array.isArray(e.calls && e.calls.allow) ? e.calls.allow.map(String) : [] };   // call screening (Calls.kt)
  e.passUntil = +e.passUntil || 0;   // 0.9.16 timed pass (docs/protocol.md "More time")
  e.bedByDay = Array.isArray(e.bedByDay) && e.bedByDay.length === 7 ? e.bedByDay.map(x => String(x || "")) : []; e.oneMore = e.oneMore !== false;
  e.lostOn = !!e.lostOn; e.lostMsg = String(e.lostMsg || "").slice(0, 200); e.lostPhone = String(e.lostPhone || "").slice(0, 20); e.lostLock = e.lostLock !== false; e.lostRing = e.lostRing !== false;
  e.msgs = (Array.isArray(e.msgs) ? e.msgs : []).map(x => String(x || "").trim().slice(0, 120)).filter(Boolean).slice(0, 12);
  // 0.9.22 timed mode {name, until (ms), rules}: kept as it is (full replace), dropped when malformed or over
  const md = e.mode;
  if (md && typeof md === "object" && +md.until > Date.now() && md.name) e.mode = { name: String(md.name).slice(0, 24), until: +md.until, rules: md.rules && typeof md.rules === "object" ? md.rules : {} }; else delete e.mode;
  e.blocked = e.blocked || []; e.allow = e.allow || []; e.appMin = e.appMin || {};
  e.chores = Array.isArray(e.chores) ? e.chores : []; e.zones = Array.isArray(e.zones) ? e.zones : [];
  e.groups = Array.isArray(e.groups) ? e.groups : []; e.unlockMin = [15, 30, 60, 120].includes(+e.unlockMin) ? +e.unlockMin : 30;
  const wd = e.windDown || {}, li = e.lockInfo || {};   // v0.9.7
  e.windDown = { gray: !!wd.gray, min: WD_STEPS.includes(+wd.min) ? +wd.min : 30, nudge: !!wd.nudge };
  e.lockInfo = { msg: String(li.msg || ""), contact: String(li.contact || "") }; e.blockNote = String(e.blockNote || "");
  const la = e.launcher || {};   // v0.9.9 kid launcher
  e.launcher = { on: !!la.on, settings: !!la.settings, notes: la.notes !== false, lockCustom: !!la.lockCustom, shade: !!la.shade, style: ["", "be", "teen", "gon"].includes(la.style) ? la.style : "" };
  return e;
}
// index 0 = Monday, from "YYYY-MM-DD"
function dayIdx(day) { const [y, m, d] = String(day).split("-").map(Number); return (new Date(y, m - 1, d).getDay() + 6) % 7; }
function baseLimit(r, day) { return r.dailyByDay && r.dailyByDay.length === 7 && day ? +r.dailyByDay[dayIdx(day)] || 0 : r.dailyMin || 0; }
function todayLimit(r, day) { const b = baseLimit(r, day); return b ? b + (r.bonus && r.bonus.day === day ? r.bonus.min || 0 : 0) : 0; }

// Browser on the LAN: another phone's kid is reached directly at its own origin (CORS on its /api, docs/protocol.md);
// the kid of the phone that served this page keeps relative URLs.
const direct = k => !APP && k.via === "lan" && k.target && k.target !== location.origin ? k.target : "";
const lanApi = target => (APP ? "/k/" + b64u(target) : target === location.origin ? "" : target) + "/api";
// an unreachable phone on the LAN would hang a fetch for the whole TCP timeout
const quick = ms => { if (typeof AbortSignal.timeout === "function") return AbortSignal.timeout(ms); if (typeof AbortController !== "function") return undefined; const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal; };   // Chrome < 103: no AbortSignal.timeout
function base(k) { return (APP ? "/k/" + b64u(k.target) : k.via === "lan" ? direct(k) : relayOf(k)) + (k.via === "lan" ? "/api" : "/c/" + encodeURIComponent(k.id)); }
// the kids the home list shows: app = every kid with an address; LAN browser = every LAN kid saved on this origin
const homeKids = () => APP ? kids.filter(k => k.target) : kids.filter(k => k.via === "lan");
// Error with a readable message (keys "e.<code>"); .raw keeps the original code for logic ("not live", "not paired"), .net = our own network failed
function errOf(raw, msg, retryAt) {
  let m = msg || tc("e.", raw, "");
  if (!m) { const h = /^HTTP (\d+)$/.exec(raw); m = h ? t("e.http", h[1]) : raw; }
  // relay 429 with a reset time (daily caps): say when it works again
  if (retryAt > Date.now()) { const d = new Date(retryAt); m += " " + t("e.retryAt", retryAt - Date.now() > 12 * 3600e3 ? d.toLocaleString([], { dateStyle: "short", timeStyle: "short" }) : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })); }
  const e = new Error(m); e.raw = raw; e.net = raw === "net"; return e;
}
// X-KG-Name: how this browser shows up in the kid's "paired parent devices" list (state.devices[].name). ASCII only:
// fetch() refuses header values outside ISO-8859-1.
const KG_NAME = (() => { const u = navigator.userAgent || "";
  const br = APP ? "KidGuard app" : /Edg\//.test(u) ? "Edge" : /OPR\/|Opera/.test(u) ? "Opera" : /SamsungBrowser/.test(u) ? "Samsung Internet" : /Firefox\//.test(u) ? "Firefox" : /Chrome\//.test(u) ? "Chrome" : /Safari\//.test(u) ? "Safari" : "Browser";
  const os = /Android/.test(u) ? "Android" : /iPhone|iPad|iPod/.test(u) ? "iOS" : /Mac OS X|Macintosh/.test(u) ? "Mac" : /Windows/.test(u) ? "Windows" : /CrOS/.test(u) ? "ChromeOS" : /Linux/.test(u) ? "Linux" : "";
  return (br + (os ? " / " + os : "")).replace(/[^\x20-\x7e]/g, "").slice(0, 40); })();
async function call1(k, path, method, body, again) {
  // app mode: a cached snapshot means the phone/relay is unreachable, so commands cannot be delivered
  if (APP && method !== "GET" && viewDay && k === cur) throw new Error(t("e.viewOnly"));
  if (APP && method !== "GET" && cachedAt && k === cur) throw new Error(t("e.cachedOff"));
  if (k.fb && k.via === "lan" && Date.now() - k.fb < 60000 && relayable(path, method)) { const j = await viaRelay(k, path, method, body); if (path === "/state" && k === cur) cachedAt = 0; return j; }
  let r; const t0 = Date.now();
  const rel = k.via !== "lan", out = rel ? wireOut(path, body) : body;
  try { r = await fetch(base(k) + path, { method, headers: { "X-KG-Token": k.token, "X-KG-Name": KG_NAME, "Content-Type": "application/json" }, body: out ? JSON.stringify(out) : undefined, signal: direct(k) ? quick(15000) : undefined }); }
  catch (e) { const x = errOf("net", APP ? t("e.lost") : null); x.late = Date.now() - t0 > 3000; throw x; }   // late = it may have arrived
  if (!again && k.via !== "lan" && (r.status === 403 || r.status === 423) && await relayFix(r, await r.clone().json().catch(() => ({})), k, base(k))) return call1(k, path, method, body, 1);
  const cached = APP && method === "GET" && path === "/state" ? +r.headers.get("X-KG-Cached") || 0 : 0;
  // app: a cached snapshot = the phone did not answer; a LAN kid that can heal treats it as a network failure
  if (cached && k.via === "lan" && canHeal(k) && !body) { const e = errOf("net", t("e.lost")); e.cached = r; throw e; }
  if (APP && method === "GET" && path === "/state" && k === cur) { cachedAt = cached; srvWhy = cached ? r.headers.get("X-KG-Why") || "" : ""; }
  const raw = await r.text().catch(() => ""); let j = {}; try { j = JSON.parse(raw); } catch {}
  // Cloudflare's own error page ("error code: 1101"): the relay worker itself is down, not the phone (INCIDENT 2026-11-17)
  const cf = !r.ok && /error code: (\d{3,4})/i.exec(raw.slice(0, 200));
  if (path === "/state" && k === cur && !cached) srvWhy = j.error === "released" ? "gone:" + (+j.at || 0) : cf ? "cf:" + cf[1] : j.why || (r.status >= 500 && k.via !== "lan" ? "http:" + r.status : "");
  if (cf && k.via === "relay" && k.target === OLD_RELAY) throw errOf("oldRelay");   // dead for good, not "down for now"
  if (cf) throw errOf(t("e.relayDown", "Cloudflare " + cf[1]));
  if (j.error === "released" && j.at) throw errOf("released", t("e.releasedAt", dtFull(+j.at)));
  if (!r.ok) throw errOf(APP && j.error === "offline" ? "offline" : j.error || ("HTTP " + r.status), null, j.retryAt);
  return rel ? wireIn(j) : j;
}

// LAN kid whose address changed (DHCP, other Wi-Fi): try the addresses it reported before (state.lan, Tailscale ip),
// then the ones in its relay state; still nothing -> state / commands / rules over its relay for a minute at a time.
// Each step is automatic (docs/protocol.md "LAN address heal").
const LAN_PORT = 8770;
// Relay command codes (docs/protocol.md "Command codes"): the relay only sees these, never the names. Same table as K/remote/Wire.kt
// (relay/test/contract.test.mjs checks both + the worker CMDS). Encoded on the way to a relay, decoded in its replies; LAN keeps the names.
const WIRE = {
  aiToken: "ai", refusedDrop: "rd", uiUpdate: "uu", smsPull: "sp", chatPull: "cp", chatClear: "cc", chatFileDel: "cf", chatDel: "cd",
  snapList: "sl", snapUp: "su", snapDel: "sd", snap: "sn", lock: "lk", locate: "lc", message: "ms", ring: "rg",
  openPairing: "op", unpairAll: "ua", unpair: "up", release: "rl", answer: "an", reboot: "rb", tailscaleOff: "to", sosAck: "sa",
  clearData: "cl", update: "ud", totpSet: "ts", wake: "wk", ringStop: "rs", listenStop: "ls", devOff: "d0", devOn: "d1",
  setting: "st", fileUp: "fu", fileDel: "fd", shotUp: "sh", notifPull: "np", tgSet: "gs", tgCode: "gc", tgUnlink: "gu",
  tgOff: "go", pinSet: "ps", pinReset: "pr", pinResetCancel: "pc", support: "sx", supportSay: "sy", adbShell: "ab", shClear: "hc",
  adbTcp: "ap", bankEnd: "be", unlockEnd: "ue", clearCache: "ca", rescan: "re", bgLimit: "bl", bgRestore: "br", fixPerms: "fp",
  selfOpen: "so", tsInstall: "ti",
};
const WIRE_NAME = Object.fromEntries(Object.entries(WIRE).map(([n, c]) => [c, n]));
const wireOut = (path, body) => path === "/cmd" && body && Object.hasOwn(WIRE, body.type) ? { ...body, type: WIRE[body.type] } : body;
const wireIn = j => { for (const f of ["expired", "queue"]) if (j && Array.isArray(j[f])) j[f] = j[f].map(x => x && Object.hasOwn(WIRE_NAME, x.type) ? { ...x, type: WIRE_NAME[x.type] } : x); return j; };
/** The author's shared relay, on by default on paired phones (Relay.DEFAULT_URL in the app). */
const DEFAULT_RELAY = "https://kg-relay.99-hoangtran.workers.dev";
// Plan B (docs/decisions/remote.md 2026-11-18): the relay is JSON only; a browser gets this page from the website (SITE /app/, /go)
// and calls the relay cross-origin. RELAY = the relay of the link that opened it (#r=<https origin>), else the last one, else the author's.
const SITE = "https://kidguard.asia";
const OLD_SITE = "https://kid-guard.pages.dev";   // links shared before 2026-11-19 (same site)
const httpsOrigin = v => { try { const u = new URL(v); return u.protocol === "https:" ? u.origin : ""; } catch { return ""; } };
let RELAY = DEFAULT_RELAY;
const OLD_RELAY = "https://kidguard.99-hoangtran.workers.dev";   // killed 2026-11-17 (docs/decisions/remote.md): read as DEFAULT_RELAY
try { RELAY = httpsOrigin(localStorage.getItem("kg.relay") || "") || RELAY; if (RELAY === OLD_RELAY) RELAY = DEFAULT_RELAY; } catch {}
const setRelay = r => { r = httpsOrigin(r || ""); if (!r) return; RELAY = r; try { localStorage.setItem("kg.relay", r); } catch {} };
/** A browser relay kid's relay: its own (target), else this page's. */
const relayOf = k => { const t = k && k.target; return t === OLD_RELAY ? DEFAULT_RELAY : t || RELAY; };
/** The relay a pasted / scanned link names: #r=, else (a link to the website) the page's relay, else the link's own origin (an old relay link). */
const relayOfLink = (u, h) => httpsOrigin(h.get("r") || "") || (u.origin === SITE || u.origin === OLD_SITE || u.origin === location.origin ? RELAY : u.origin);
/** The link that always opens: the website's /go page (core.js goBoot); r= only for a relay other than the author's. */
const goLink = (url, id, token, name, relay) => `${url}/go#c=${encodeURIComponent(id)}&k=${encodeURIComponent(token)}&n=${encodeURIComponent(name || "")}${relay && relay !== DEFAULT_RELAY ? "&r=" + encodeURIComponent(relay) : ""}`;
const lanLink = (base, id, token, name) => `${base}/#lk=${token}&rc=${encodeURIComponent(id)}&ru=${encodeURIComponent(RELAY)}&n=${encodeURIComponent(name || "")}`;
const GO_FRESH = 180000;   // the phone syncs at least every 60 s (screen off); 3 min without = offline
/**
 * Relay /go#c&k&n: ask the relay where the phone is. Same public network + seen lately -> open its LAN page (faster,
 * listen works) with the relay token; Back within 90 s (the LAN page did not load) -> stay on the relay with a toast.
 * Offline -> an error screen with what the relay last saw. Otherwise -> the normal page over the relay.
 */
async function goBoot(h) {
  isRelayOrigin = true;
  const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay", target: RELAY };
  kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids);
  let w = null, err = "";
  try {
    const where = () => fetch(`${relayOf(k)}/c/${encodeURIComponent(k.id)}/where`, { headers: { "X-KG-Token": k.token }, signal: quick(10000) });
    let r = await where();
    w = await r.json().catch(() => null);
    if (!r.ok && w && await relayFix(r, w, k, relayOf(k) + "/c/" + encodeURIComponent(k.id))) { r = await where(); w = await r.json().catch(() => null); }
    if (!r.ok) { err = (w && w.error) || "HTTP " + r.status; w = null; }
  } catch { err = "net"; }
  const key = "kg.go." + k.id, now = Date.now();
  let back = false; try { back = now - (+sessionStorage.getItem(key) || 0) < 90000; } catch {}
  const fresh = w && now - (w.seen || 0) < GO_FRESH;
  if (fresh && w.same && w.lan && !back) {
    try { sessionStorage.setItem(key, String(now)); } catch {}
    location.assign(lanLink(w.lan, k.id, k.token, k.name));   // assign, not replace: Back returns here = "LAN did not load"
    return;
  }
  history.replaceState(null, "", location.pathname);
  if (err === "not paired" || err === "waiting for the phone") return goError(k, null, t("go.unpaired"));
  if (!w) return goError(k, null, t("go.relayErr", err === "net" ? t("e.net") : err));
  if (!fresh) return goError(k, w, w.seen ? t("go.offline", ago(w.seen)) : t("go.never"));
  open(k);
  if (back && w.lan) setTimeout(() => toast("warn|" + t("go.lanFail", w.lan.replace(/^http:\/\//, ""))), 800);
}
function goError(k, w, why) {
  const host = u => u.replace(/^http:\/\//, "");
  const ts = w && w.ts ? `http://${w.ts}:${LAN_PORT}` : "";
  $("#app").innerHTML = head("KidGuard") + `<div class="card">
    <h2>${ic("wifi-off")} ${esc(t("go.h", k.name))}</h2>
    <p><b>${esc(why)}</b></p>
    ${w && w.seen ? `<p class="mute">${esc(t("go.seen", dtFull(w.seen)))}</p>` : ""}
    ${w && w.lan ? `<p class="mute">${esc(t("go.lastLan", host(w.lan)))}</p>` : ""}
    ${w && w.ts ? `<p class="mute">${esc(t("go.ts", w.ts))}</p>` : ""}
    <p class="mute">${t("go.why")}</p>
    <div class="row" style="flex-wrap:wrap;gap:8px">
      <button id="goRetry" class="pri">${ic("refresh-cw")} ${t("go.retry")}</button>
      ${w ? `<button id="goOpen">${ic("eye")} ${t("go.openAnyway")}</button>` : ""}
      ${w && w.lan ? `<a class="lnkbtn" href="${esc(lanLink(w.lan, k.id, k.token, k.name))}">${ic("wifi")} ${t("go.tryLan")}</a>` : ""}
      ${ts ? `<a class="lnkbtn" href="${esc(lanLink(ts, k.id, k.token, k.name))}">${ic("globe")} ${t("go.tryTs")}</a>` : ""}
    </div></div>`;
  $("#goRetry").onclick = () => { try { sessionStorage.removeItem("kg.go." + k.id); } catch {} location.replace(goLink(location.origin, k.id, k.token, k.name, relayOf(k))); };
  if ($("#goOpen")) $("#goOpen").onclick = () => open(k);
}
/** This phone's page opened from /go: save it as a LAN kid with the relay token and its relay creds, then open it. */
async function lanFromGo(h) {
  const token = h.get("lk"), rid = h.get("rc") || "", ru = h.get("ru") || "", name = h.get("n") || t("kid");
  history.replaceState(null, "", location.pathname + location.search);
  let hello = null;
  try { const r = await fetch("/api/hello"); if (r.ok) hello = await r.json(); } catch {}
  if (!hello || !hello.id) return boot();
  const k = { id: hello.id, token, name, via: "lan", target: location.origin };
  if (/^https:\/\//.test(ru) && /^[0-9a-f]{16}$/.test(rid)) k.relay = { url: new URL(ru).origin, id: rid, token };
  const old = kids.find(x => x.via === "lan" && x.id === hello.id);
  if (old) { old.target = location.origin; if (k.relay) old.relay = k.relay; save(kids); return open(old); }   // keeps its own token
  kids = kids.concat(k); save(kids);
  open(k);
}
const canHeal = k => !LOCAL && !!((k.alt && k.alt.length) || k.relay || (APP && window.KGApp.lanFind));
const relayable = (path, method) => (path === "/state" && method === "GET") || (path === "/cmd" && method === "POST") || (path === "/rules" && method === "PUT");
const addrsOf = s => { if (!s) return [];
  const out = []; if (typeof s.lan === "string" && /^http:\/\/[\d.]+:\d+$/.test(s.lan)) out.push(s.lan);
  const ip = s.ts && s.ts.ip; if (typeof ip === "string" && /^100\.[\d.]+$/.test(ip)) out.push(`http://${ip}:${LAN_PORT}`);
  return out; };
async function viaRelay(k, path, method, body, again) {
  const rl = k.relay, b = (APP ? "/k/" + b64u(rl.url) : rl.url) + "/c/" + encodeURIComponent(rl.id);
  let r;
  try { r = await fetch(b + path, { method, headers: { "X-KG-Token": rl.token, "X-KG-Name": KG_NAME, "Content-Type": "application/json" }, body: body ? JSON.stringify(wireOut(path, body)) : undefined, signal: quick(15000) }); }
  catch (e) { throw errOf("net", APP ? t("e.lost") : null); }
  const j = await r.json().catch(() => ({}));
  if (!again && (r.status === 403 || r.status === 423) && await relayFix(r, j, rl, b)) return viaRelay(k, path, method, body, 1);
  if (!r.ok) throw errOf(j.error || ("HTTP " + r.status), null, j.retryAt);
  return wireIn(j);
}
// --- per-parent relay tokens (docs/protocol.md "Parents"): 403 join = the saved token is the shared link one; 423 locked = a browser token
// needs the family PIN (12 h). One shared prompt per credential, so many failing calls never stack screens.
const gates = new Map();
function relayFix(r, j, cred, b) {   // -> Promise<boolean>: fixed, the caller retries once
  const w = r.status === 403 && j.error === "join" ? "join" : r.status === 423 && j.error === "locked" ? "unlock" : "";
  if (!w || LOCAL) return Promise.resolve(false);
  return relayGate(cred, b, w, {});
}
function gateErr(j) {
  const e = (j && j.error) || "";
  return e === "bad pin" ? t("gt.bad", j.left) : e === "pin locked" ? t("gt.locked", j.retryAt ? hhmm(j.retryAt) : "?") : e === "invite needed" ? t("gt.inv") : e === "too many parents" ? t("gt.many") : tc("e.", e, t("e.net"));
}
function relayGate(cred, b, what, o) {
  if (gates.has(cred)) return gates.get(cred);
  if (Date.now() - (cred.gateOff || 0) < 60000) return Promise.resolve(false);
  const join = what === "join", optPin = join && o.pinOptional;
  const p = new Promise(done => {
    const old = $("#kgGate"); if (old) old.remove();
    let nm = ""; try { nm = localStorage.getItem("kg.pname") || ""; } catch {}
    const el = document.createElement("div"); el.id = "kgGate"; el.className = "sheet"; el.style.display = "flex"; el.style.zIndex = 70; el.style.background = "var(--bg)";   // nothing of the kid shows behind it
    el.innerHTML = `<div class="dp" role="dialog" aria-modal="true"><h2>${ic("key-round")} ${t(join ? "jn.h" : "ul.h")}</h2><p class="mute">${t(join ? "jn.p" : "ul.p")}</p>
      ${join ? `<input id="gN" autocomplete="off" maxlength="30" placeholder="${esc(t("jn.name"))}" aria-label="${esc(t("jn.name"))}" value="${esc(nm || KG_NAME)}" style="width:100%;margin-bottom:10px">` : ""}
      ${optPin ? "" : `<input id="gP" type="password" inputmode="numeric" maxlength="8" autocomplete="off" placeholder="${esc(t("jn.pin"))}" aria-label="${esc(t("jn.pin"))}" style="width:100%;margin-bottom:6px">`}
      <p class="err" id="gE" role="alert"></p>
      <div class="row"><button class="pri sp" id="gGo">${ic("check")} ${t(join ? "jn.go" : "ul.go")}</button><button id="gNo">${t("cancel")}</button></div></div>`;
    document.body.appendChild(el);
    const end = v => { el.remove(); gates.delete(cred); done(v); }, say = m => { $("#gE").textContent = m; };
    $("#gNo").onclick = () => { cred.gateOff = Date.now(); end(false); };
    const go = async () => {
      const btn = $("#gGo"), pin = $("#gP") ? $("#gP").value : "", name = join ? $("#gN").value.trim() : "";
      if (btn.disabled) return;
      if (join && !name) return say(t("jn.needName"));
      if (!optPin && !/^\d{4,8}$/.test(pin)) return say(t("pe.bad pin"));
      say(""); btn.disabled = true;
      try {
        const r = await fetch(b + (join ? "/join" : "/unlock"), { method: "POST", headers: { "Content-Type": "application/json", "X-KG-Token": cred.token, "X-KG-Name": KG_NAME }, body: JSON.stringify(join ? { name, kind: APP ? "app" : "web", pin } : { pin }) });
        const j = await r.json().catch(() => ({}));
        if (r.ok) { if (join && j.token) { cred.token = j.token; cred.own = 1; try { localStorage.setItem("kg.pname", name); } catch {} save(kids); toast("ok|" + t("jn.ok", name)); } return end(true); }
        say(gateErr(j));
      } catch { say(t("e.net")); }
      btn.disabled = false;
    };
    $("#gGo").onclick = go;
    el.onkeydown = e => { if (e.key === "Enter") go(); };
    const f = $("#gN") || $("#gP"); if (f) f.focus();
  });
  gates.set(cred, p);
  return p;
}
// Once per page load per relay credential: while no parent has an own token the shared one still works and never answers 403 join,
// so ask the relay who we are; no row with me:true = we hold the shared token -> offer the join screen (dismissed: asked again next load).
async function relayMigrate(k) {
  const cred = k.via === "relay" ? k : k.relay;
  if (!cred || cred.migTry || LOCAL || cred.own) return;
  cred.migTry = 1;
  const b = k.via === "relay" ? base(k) : (APP ? "/k/" + b64u(cred.url) : cred.url) + "/c/" + encodeURIComponent(cred.id);
  try {
    const r = await fetch(b + "/parents", { headers: { "X-KG-Token": cred.token, "X-KG-Name": KG_NAME }, signal: quick(10000) });
    if (!r.ok) return;   // 401 / 404 / older relay: nothing to do (403 join and 423 are handled by the normal calls)
    const j = await r.json().catch(() => null);
    if (!j || !Array.isArray(j.parents) || j.parents.some(p => p.me)) return;
    if (await relayGate(cred, b, "join", { pinOptional: j.pinKnown === false })) { lastSig = ""; if (k === cur) refresh(); }
  } catch {}
}
const healing = new Map();
// /api/hello?n= proof (LanName.proof): HMAC-SHA256(key = sha256-hex(token), "kg-lan:" + n). Only where crypto.subtle
// exists (the parent app's localhost page); a plain-http browser page already sends its token in the clear.
const hexOf = b => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, "0")).join("");
async function lanProof(token, n) {
  const enc = new TextEncoder();
  const h = hexOf(await crypto.subtle.digest("SHA-256", enc.encode(token)));
  const key = await crypto.subtle.importKey("raw", enc.encode(h), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hexOf(await crypto.subtle.sign("HMAC", key, enc.encode("kg-lan:" + n)));
}
function relocate(k) {
  if (healing.has(k)) return healing.get(k);
  if (Date.now() - (k.healAt || 0) < 20000) return Promise.resolve(false);
  k.healAt = Date.now();
  const tried = new Set([k.target]);
  const probe = async u => {
    if (!u || tried.has(u)) return false;
    tried.add(u);
    try {
      const sub = window.crypto && crypto.subtle && k.token;
      const n = sub ? hexOf(crypto.getRandomValues(new Uint8Array(16))) : "";
      const r = await fetch(lanApi(u) + "/hello" + (n ? "?n=" + n : ""), { signal: quick(4000) });
      const h = r.ok ? await r.json() : null;
      // the old IP may now be someone else's device answering with the (public) id: it must prove it holds our token
      const proven = !n || (Array.isArray(h && h.proof) && h.proof.includes(await lanProof(k.token, n)));
      if (h && h.id === k.id && proven) { try { window.KGApp && KGApp.lanMoved && KGApp.lanMoved(k.id, u); } catch {} const from = k.target; k.target = u; k.alt = [from, ...(k.alt || [])].filter(x => x !== u).slice(0, 4); k.fb = 0; save(kids); toast("ok|" + t("lan.moved", u.replace(/^http:\/\//, ""))); return true; }
    } catch {}
    return false;
  };
  const p = (async () => {
    for (const u of k.alt || []) if (await probe(u)) return true;
    // parent app: the kid advertises itself on the Wi-Fi (NSD, LanName.kt); the app looks in the background and proves
    // the address with the token; "" = still looking, "none" = nothing / no Wi-Fi (no wait then)
    if (APP && window.KGApp.lanFind) for (let i = 0; i < 8; i++) {
      let u = "none"; try { u = String(KGApp.lanFind(k.id) || ""); } catch {}
      if (u === "none") break;
      if (u && /^http:\/\/[0-9.]+:\d+$/.test(u)) { if (await probe(u)) return true; break; }
      await new Promise(r => setTimeout(r, 1500));
    }
    if (k.relay) { try { const j = await viaRelay(k, "/state", "GET"); for (const u of addrsOf(j && j.state)) if (await probe(u)) return true; } catch {} }
    return false;
  })().finally(() => healing.delete(k));
  healing.set(k, p);
  return p;
}
const REPLAY_OK = new Set(["lock", "locate", "wake", "ringStop", "listenStop", "sosAck", "setting", "devOn", "devOff", "openPairing"]);
async function call(k, path, method = "GET", body) {
  // a command the kid's app is too old for: never sent (it would be ignored), the parent is offered the update (NEED, kid.js)
  if (path === "/cmd" && method === "POST" && body && k === cur && typeof needOf === "function") {
    const v = needOf("cmd", body.type); if (v) { needAsk(v); throw new Error(t("need.short", v)); }
  }
  try { return await call1(k, path, method, body); }
  catch (e) {
    if (!e.net || k.via !== "lan" || !canHeal(k) || viewDay) throw e;
    // a LAN command that died mid-way (late) may already have run (reboot / release / update cut the link, a slow release
    // outlives the 15 s abort): then only harmless-twice ones are retried on another address or the relay.
    // A fast failure (no route, refused) never reached the phone: retried as before.
    if (e.late && path === "/cmd" && method === "POST" && !REPLAY_OK.has(body && body.type)) { const m = errOf("net", t("e.maybeSent")); m.maybe = true; throw m; }
    if (await relocate(k)) return call1(k, path, method, body);
    if (k.relay && relayable(path, method)) {
      try { const j = await viaRelay(k, path, method, body); if (path === "/state" && k === cur) cachedAt = 0; if (!k.fb) toast("warn|" + t("lan.viaRelay")); k.fb = Date.now(); return j; } catch {}
    }
    if (e.cached) { if (k === cur) cachedAt = +e.cached.headers.get("X-KG-Cached") || 0; return e.cached.json().catch(() => ({})); }
    throw e;
  }
}
/** After a LAN state: remember the phone's other addresses and (once per page load) its relay credentials. */
function learnAddrs(k, s) {
  if (!k || k.via !== "lan" || LOCAL || !s || k.fb) return;
  const alt = [...new Set([...addrsOf(s), ...(k.alt || [])])].filter(u => u !== k.target).slice(0, 4);
  let dirty = JSON.stringify(alt) !== JSON.stringify(k.alt || []);
  if (dirty) k.alt = alt;
  const url = typeof s.relay === "string" && /^https:\/\//.test(s.relay) ? s.relay : "";
  if (!url && k.relay) { delete k.relay; dirty = true; }
  if (dirty) save(kids);
  // once per page load, also when the url is the same: the phone may have a new relay id (re-pointed / reset), and stale
  // credentials sent push + the relay fallback to a dead id (owner 2026-11-16: alerts ~20 min late)
  if (url && !k.relayTry) {
    k.relayTry = 1;   // the same url again: the phone keeps its relay id/token and just returns them (not logged)
    call1(k, "/cmd", "POST", { type: "relay", url }).then(r => {
      if (r && /^[0-9a-f]+$/.test(r.id || "") && /^[0-9a-f]{48}$/.test(r.token || "")) {
        const nr = { url: new URL(url).origin, id: r.id, token: r.token }, o = k.relay || {};
        if (o.url !== nr.url || o.id !== nr.id || (o.token !== nr.token && !o.own)) { k.relay = nr; save(kids); }   // an own parent token is kept: the phone only knows the shared one
      }
    }).catch(() => {});
  }
}

// --- boot ---------------------------------------------------------------
async function boot() {
  if (navLvl()) try { history.replaceState(null, ""); } catch {}   // a reload keeps the old entry state
  if (APP) return appBoot();
  if (LOCAL) return localBoot();
  // a link from the child: #c=<id>&k=<token>&n=<name>[&r=<relay>]
  const h = new URLSearchParams(location.hash.slice(1));
  if (h.get("r")) setRelay(h.get("r"));
  // QR from the child's screen: #pair=<6 digits> prefills the code; drop the hash so a reload does not reuse it
  const pc = /^\d{6}$/.test(h.get("pair") || "") ? h.get("pair") : "";
  if (h.has("pair")) history.replaceState(null, "", location.pathname + location.search);
  // from a relay /go link: this phone's own page with the relay token (also a LAN token on the phone)
  if (h.get("inv") && h.get("rc")) { const o = inviteOf(h); if (o) { history.replaceState(null, "", location.pathname + location.search); isRelayOrigin = true; return inviteScreen(o); } }
  if (/^[0-9a-f]{48}$/.test(h.get("lk") || "")) return lanFromGo(h);
  if (location.pathname === "/go" && h.get("c") && h.get("k")) return goBoot(h);
  if (h.get("c") && h.get("k")) {
    isRelayOrigin = true;
    const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay", target: RELAY };
    kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids);
    history.replaceState(null, "", location.pathname);
    return open(k);
  }
  let hello = null;
  try { const r = await fetch("/api/hello"); if (r.ok) hello = await r.json(); } catch {}
  if (hello) {  // served by the child itself
    const k = kids.find(x => x.via === "lan" && x.id === hello.id);
    if (k) return open(k);
    return pairScreen(hello, undefined, pc);
  }
  isRelayOrigin = true;
  const relayKids = kids.filter(k => k.via === "relay");
  if (relayKids.length === 1) return open(relayKids[0]);
  listScreen(relayKids);
}

// kid list row: name, then (once the state arrives) online dot + last seen + battery (A5.10)
const kidRow = (k, i) => `<div class="card row"><span class="sp" style="min-width:0"><b>${ic("user")} ${esc(k.name)}</b><span class="mute kb" data-kb="${i}" style="display:block">${APP ? (k.via === "lan" ? t("via.lan") : "relay") : "&nbsp;"}</span></span><button class="ghost kx" data-x="${i}" hidden aria-label="${esc(t("forget.rowAria", k.name))}">${ic("trash-2")}</button><button class="pri" data-i="${i}">${ic("folder-open")} ${t("open")}</button></div>`;
// a saved kid that can't come back by itself: paired with the killed relay, released (relay says "released"), or the relay no longer knows us
const deadKid = (k, raw) => (k.via === "relay" && k.target === OLD_RELAY) || raw === "released" || raw === "not paired";
// remove a kid from this device (settings "Xoá máy này khỏi app" and the list's bin button for a dead / 1-day-offline kid)
async function dropKid(k, dead) {
  if (!(await ask(t(k.via === "relay" && !dead ? "forget.askRelay" : APP ? "forget.askApp" : "forget.askWeb", k.name), APP ? t("delete") : t("forget"), true, k.via === "relay" && !dead ? t("forget.word") : ""))) return false;
  if (k === cur) { screenStop(); cur = null; clearInterval(timer); }
  kids = kids.filter(x => x !== k); save(kids); return true;
}
async function kidBrief(list) {
  const root = $("#app");
  await Promise.all(list.map(async (k, i) => {
    const el = () => { const e = root.querySelector(`[data-kb="${i}"]`); return e && e.isConnected ? e : null; };
    // bin button (owner 2026-11-18): only for a kid that is gone or silent for a day; the settings path stays for the rest
    const bin = dead => { const b = root.querySelector(`[data-x="${i}"]`); if (!b || !b.isConnected) return; b.hidden = false; b.onclick = async () => { if (await dropKid(k, dead)) reScreen ? reScreen() : boot(); }; };
    if (deadKid(k)) bin(true);
    try {
      const j = await call(k, "/state"), s = j.state !== undefined ? j.state : j;
      if (!s) { if (deadKid(k)) return el() && (el().innerHTML = `${dot("bad")} ${t("kb.oldRelay")}`); return el() && (el().innerHTML = `${dot("")} ${t("kb.never")}`); }
      const seen = j.state !== undefined ? +j.seen || 0 : s.t, on = k.via === "lan" ? Date.now() - s.t < 30000 : seen && Date.now() - seen < 120000;
      if (el()) el().innerHTML = deadKid(k) ? `${dot("bad")} ${t("kb.oldRelay")}` : `${dot(on ? "on" : "bad")} ${on ? t("ov.online") : t("ov.offline", ago(seen || s.t))} · ${ic("battery-full")} ${+s.battery}%${s.charging ? ic("zap") : ""}`;
      if (!on && Date.now() - (seen || s.t || 0) > 864e5) bin(deadKid(k));
    } catch (e) {
      const gone = deadKid(k, e && e.raw);
      if (el()) el().innerHTML = `${dot("bad")} ${gone ? t(k.target === OLD_RELAY ? "kb.oldRelay" : e.raw === "released" ? "kb.released" : "kb.notPaired") : t("kb.noAnswer")}`;
      if (gone) bin(true);
    }
  }));
}
// --- app mode: kid list, add-kid ---------------------------------------------
function appBoot() {
  save(kids);   // tell the app the current list once on load
  const list = homeKids(); reScreen = appBoot;
  $("#app").innerHTML = head("KidGuard") + (list.length ? list.map((k, i) =>
    kidRow(k, i)).join("")
    : `<div class="card empty"><span class="e">${ic("inbox")}</span><p>${t("app.none")}</p></div>`) +
    `<div class="row">${list.length > 1 ? `<button class="pri sp" id="ovGo">${ic("layout-grid")} ${t("ovN", list.length)}</button>` : ""}<button class="${list.length > 1 ? "" : "pri "}sp" id="addKid">${ic("plus")} ${t("addKid")}</button></div>`;
  $("#app").querySelectorAll("[data-i]").forEach(b => b.onclick = () => open(list[+b.dataset.i]));
  kidBrief(list);
  $("#addKid").onclick = addScreen;
  if ($("#ovGo")) $("#ovGo").onclick = () => overview(list, appBoot);
  appOpenKid();
}
// parent app: a notification tap names one kid (KGApp.takeOpen() -> {target, id}, given once); the app also calls this on a running page
function appOpenKid() {
  let o = null;
  try { o = JSON.parse(window.KGApp && KGApp.takeOpen ? KGApp.takeOpen() || "null" : "null"); } catch {}
  const k = o && kids.find(x => x.target === o.target && x.id === o.id);
  if (k && k !== cur) open(k);
  if (!k && KGApp.takeSettings && KGApp.takeSettings() === "true") goAppSettings();
}
window.kgAppOpen = appOpenKid;
// Pair from anywhere (docs/protocol.md "Pair over relay"): the code on the kid's screen also works through the relay, no shared Wi-Fi.
// The page talks to the relay it was served from (a browser) or the default relay (the app, via its proxy). A phone with a parent never offers it.
const anyBase = () => APP ? "/k/" + b64u(DEFAULT_RELAY) : RELAY;
const anyOk = () => APP || (location.protocol === "https:" && isRelayOrigin && !!(window.crypto && crypto.subtle));
const anyHex = n => [...crypto.getRandomValues(new Uint8Array(n))].map(b => b.toString(16).padStart(2, "0")).join("");
const anySha = async x => [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(x)))].map(b => b.toString(16).padStart(2, "0")).join("");
const anyCard = () => `<div class="card"><h2>${ic("globe")} ${t("any.h")}</h2><p class="mute">${t("any.p")}</p>
  <div class="pairf"><input id="ac" inputmode="numeric" maxlength="6" placeholder="123456" aria-label="${esc(t("pair.code"))}">
  <input id="an" placeholder="${esc(t("pair.name"))}" value="${esc(t("kid"))}" aria-label="${esc(t("pair.name"))}"></div>
  <p class="mute" style="margin:10px 0 6px">${ic("key-round")} <b>${t("pair.newPin")}</b><br>${t("pair.newPinP")}</p>
  <div class="pairf"><input id="ap1" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin1"))}" aria-label="${esc(t("pair.pin1"))}">
  <input id="ap2" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin2"))}" aria-label="${esc(t("pair.pin2"))}">
  <button class="pri" id="aGo">${ic("globe")} ${t("pair.go")}</button></div><p class="err" id="eA"></p></div>`;
function anyWire() {
  const go = $("#aGo"); if (!go) return;
  const idle = go.innerHTML, say = m => { $("#eA").textContent = m; };
  const busy = (on, label) => { go.disabled = on; go.setAttribute("aria-busy", on ? "true" : "false"); go.innerHTML = on ? `<i class="spin"></i> ${label}` : idle; document.querySelectorAll("#aGo, #ac, #an, #ap1, #ap2").forEach(i => i.disabled = on); };
  const wait = ms => new Promise(z => setTimeout(z, ms));
  go.onclick = async () => {
    if (go.disabled) return;
    say("");
    const code = $("#ac").value.trim(), a = $("#ap1").value, name = $("#an").value.trim() || t("kid");
    if (!/^\d{6}$/.test(code)) return say(t("pe.wrong code"));
    if (!/^\d{4,8}$/.test(a)) return say(t("pe.bad pin"));
    if (a !== $("#ap2").value) return say(t("pe.mismatch"));
    const fail = j => say(hasT("pe." + (j && j.error)) ? t("pe." + j.error, Math.ceil(((j && j.wait) || 60000) / 60000)) : t("pe.fail", String((j && j.error) || "?").slice(0, 40)));
    busy(true, t("any.busy"));
    try {
      const ticket = anyHex(16), b = anyBase();
      let r = await fetch(b + "/p/" + code + "/join", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ th: await anySha(ticket), newPin: a, name }) });
      if (!r.ok) return fail(await r.json().catch(() => ({})));
      // the kid's phone answers within a few seconds while its screen is on; it keeps nothing until this page has the link
      for (let end = Date.now() + 45000; Date.now() < end; await wait(1500)) {
        r = await fetch(b + "/p/" + code + "/result" + (APP ? "?kind=app" : ""), { headers: { "X-KG-Ticket": ticket } });
        const j = await r.json().catch(() => ({}));
        if (r.ok && j.token) return anyDone(j, name);
        if (!r.ok) return fail(j);
      }
      say(t("any.timeout"));
    } catch { say(t("any.fail")); } finally { if ($("#aGo")) busy(false); }
  };
}
// same result as a relay link (addRelayLink): a kid via the relay, opened at once
function anyDone(j, name) {
  const k = { id: j.id, token: j.token, name, via: "relay", target: j.url, own: 1 };
  kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids); open(k);
}
function addScreen() {
  const keep = [($("#addr") || {}).value || "", ($("#rlink") || {}).value || ""]; reScreen = addScreen;
  $("#app").innerHTML = head(t("addKid")) + `
  ${APP ? `<div class="card"><h2>${ic("scan-line")} ${t("scan.h")}</h2><p class="mute">${t("scan.p")}</p><div class="row" style="gap:8px;flex-wrap:wrap"><button class="pri" id="scanGo">${ic("scan-line")} ${t("scan.go")}</button><button id="scanImg">${ic("image")} ${t("scan.img")}</button></div></div>` : ""}
  <div class="card"><h2>${ic("wifi")} ${t("add.lanH")}</h2>
    <p class="mute">${t("add.lanP")}</p>
    <div class="pairf"><input id="addr" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="192.168.1.5" aria-label="${esc(t("add.addr"))}" value="${esc(keep[0])}"><button class="pri" id="lanGo">${ic("link")} ${t("add.connect")}</button></div><p class="err" id="e1"></p>
    ${APP ? "" : `<p class="mute">${ic("lightbulb")} ${t("add.sameOrigin")}</p>`}</div>
  ${anyOk() ? anyCard() : ""}
  ${APP ? `<div class="card"><h2>${ic("globe")} ${t("add.relayH")}</h2>
    <p class="mute">${t("add.relayP")}</p>
    <div class="pairf"><input id="rlink" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(t("add.relayPh"))}" aria-label="${esc(t("add.relayLink"))}" value="${esc(keep[1])}"><button class="pri" id="rGo">${ic("globe")} ${t("add")}</button></div><p class="err" id="e2"></p></div>` : ""}
  <button id="abk">${ic("list")} ${t("back")}</button>`;
  $("#abk").onclick = appBoot;
  $("#lanGo").onclick = () => {
    let a = $("#addr").value.trim().replace(/^https?:\/\//i, "").replace(/\/.*$/, "");
    if (!/^[A-Za-z0-9.-]+(:\d{1,5})?$/.test(a)) return $("#e1").textContent = t("add.badAddr");
    lanHello("http://" + (a.includes(":") ? a : a + ":8770"));
  };
  anyWire();
  if ($("#rGo")) $("#rGo").onclick = () => { if (!addRelayLink($("#rlink").value)) $("#e2").textContent = t("add.badLink"); };
  if ($("#scanGo")) $("#scanGo").onclick = () => { try { KGApp.scanQr(); } catch { toast("err|" + t("scan.none")); } };
  if ($("#scanImg")) $("#scanImg").onclick = () => { try { KGApp.scanImg(); } catch { toast("err|" + t("scan.none")); } };
}
// invite link (tab-set.js "Invite another parent"): #ri=<relay origin>&rc=<id>&inv=<code>&n=<kid name>; asks only a name, no PIN
function inviteOf(h) {
  const ri = httpsOrigin(h.get("ri") || (APP ? DEFAULT_RELAY : RELAY));
  const rc = h.get("rc") || "", inv = h.get("inv") || "";
  return ri && /^[0-9a-f]{16}$/.test(rc) && /^[0-9a-f]{32}$/.test(inv) ? { ri, rc, inv, n: (h.get("n") || "").slice(0, 40) } : null;
}
function inviteScreen(o) {
  reScreen = () => inviteScreen(o);
  let nm = ""; try { nm = localStorage.getItem("kg.pname") || ""; } catch {}
  $("#app").innerHTML = head(t("iv.h")) + `<div class="card"><h2>${ic("users")} ${esc(t("iv.h2", o.n || t("kid")))}</h2><p class="mute">${t("iv.p")}</p>
    <div class="pairf"><input id="ivN" maxlength="30" autocomplete="off" placeholder="${esc(t("jn.name"))}" aria-label="${esc(t("jn.name"))}" value="${esc(nm || KG_NAME)}"><button class="pri" id="ivGo">${ic("check")} ${t("jn.go")}</button></div><p class="err" id="ivE" role="alert"></p></div>`;
  const go = $("#ivGo"), say = m => { $("#ivE").textContent = m; };
  $("#ivN").onkeydown = e => { if (e.key === "Enter") go.click(); };
  go.onclick = async () => {
    const name = $("#ivN").value.trim(); if (!name) return say(t("jn.needName"));
    say(""); go.disabled = true;
    try {
      const r = await fetch((APP ? "/k/" + b64u(o.ri) : o.ri) + "/c/" + o.rc + "/join", { method: "POST", headers: { "Content-Type": "application/json", "X-KG-Name": KG_NAME }, body: JSON.stringify({ name, kind: APP ? "app" : "web", inv: o.inv }) });
      const j = await r.json().catch(() => ({}));
      if (!r.ok || !j.token) { say(j.error === "invite used or expired" ? t("iv.dead") : gateErr(j)); go.disabled = false; return; }
      try { localStorage.setItem("kg.pname", name); } catch {}
      const k = { id: o.rc, token: j.token, name: o.n || t("kid"), via: "relay", target: o.ri, own: 1 };
      kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids); reScreen = null; open(k);
    } catch { say(t("e.net")); go.disabled = false; }
  };
}
// the one parser for a relay link (typed, pasted or scanned): https + #c=<id>&k=<token>[&n=<name>]; anything else is refused
function addRelayLink(text) {
  try {
    const u = new URL(String(text).trim()), h = new URLSearchParams(u.hash.slice(1));
    if (u.protocol === "https:" && h.get("inv")) { const o = inviteOf(h); if (!o) return false; inviteScreen(o); return true; }
    if (u.protocol !== "https:" || !h.get("c") || !h.get("k")) return false;
    const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay", target: relayOfLink(u, h) };
    kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids); open(k);
    return true;
  } catch { return false; }
}
// parent app: KGApp.scanQr() answers kgScan({text} | {st: "cancel" | "fail"}). Only our two link formats are acted on:
// a relay link (addRelayLink) or the kid phone's own QR http://<host>[:port]/#pair=<6 digits>. Never navigates anywhere.
window.kgScan = o => {
  if (o && o.st === "none") return toast("err|" + t("scan.noQr"));
  if (!o || !o.text) return;
  if (addRelayLink(o.text)) return;
  try {
    const u = new URL(o.text.trim()), c = new URLSearchParams(u.hash.slice(1)).get("pair") || "";
    if (u.protocol === "http:" && /^[A-Za-z0-9.-]+$/.test(u.hostname) && /^\d{6}$/.test(c)) return lanHello("http://" + u.hostname + ":" + (u.port || 8770), c);
  } catch {}
  toast("err|" + t("scan.bad"));
};
// plain screens (list / add / pair): one centred column (#app.solo); render() and the overview clear it
// back: a sub-screen (app settings) shows a back arrow instead of the logo, and no settings gear
const head = (title, back) => { $("#app").className = "solo"; return `<div class="row" style="margin-bottom:14px">${back ? `<button type="button" class="rb" id="appSetBack" aria-label="${esc(t("back"))}" title="${esc(t("back"))}">${ic("arrow-left")}</button>` : `<span class="badge">${ic("shield")}</span>`}<h1 class="sp" style="margin:0">${title}</h1>${back ? "" : APP ? `<button type="button" class="rb" id="appSetBtn" aria-label="${esc(t("au.h"))}" title="${esc(t("au.h"))}">${ic("settings")}</button>` : `<button type="button" class="rb" data-view-set aria-label="${esc(t("vs.h"))}" title="${esc(t("vs.h"))}">${ic("settings")}</button>`}</div>`; };
document.addEventListener("click", e => { if (APP && e.target.closest && e.target.closest("#appSetBtn")) appSettings(); });
async function lanHello(target, code = "") {
  const e = $("#e1"); if (e) e.textContent = t("connecting");
  try {
    const r = await fetch(lanApi(target) + "/hello", { signal: APP ? undefined : quick(8000) });
    const hello = r.ok ? await r.json() : null;
    if (!hello || !hello.id) throw 0;
    const k = kids.find(x => x.via === "lan" && x.id === hello.id);
    if (k) { k.target = target; save(kids); return open(k); }
    pairScreen(hello, target, code);
  } catch { if ($("#e1")) $("#e1").textContent = t("add.fail", target.replace("http://", "")); else toast("err|" + t("add.failShort")); }
}

function pairScreen(hello, target, prefill = "") {
  const pre = target ? lanApi(target) : "/api";
  reScreen = () => pairScreen(hello, target, ($("#code") || {}).value || prefill);
  // no PIN yet (phone >= 0.9.6 says pairPin): this pairing sets it, atomically on the phone; older phones get the PIN gate after
  const newPin = hello.pairPin && !hello.pin;
  $("#app").innerHTML = head("KidGuard") + `<div class="card">
    <h2>${ic("link")} ${t("pair.h")}</h2>
    ${hello.pairing ? `<p>${ic("hash")} ${t("pair.enter")}</p>
    <div class="pairf"><input id="code" inputmode="numeric" maxlength="6" placeholder="123456" value="${esc(prefill)}" aria-label="${esc(t("pair.code"))}">
    <input id="nm" placeholder="${esc(t("pair.name"))}" value="${esc(t("kid"))}" aria-label="${esc(t("pair.name"))}">
    ${hello.pin ? `<input id="pin" type="password" inputmode="numeric" maxlength="8" autocomplete="off" placeholder="${esc(t("pair.pin"))}" aria-label="${esc(t("pair.pin"))}">` : ""}
    ${newPin ? `</div><p class="mute" style="margin:10px 0 6px">${ic("key-round")} <b>${t("pair.newPin")}</b><br>${t("pair.newPinP")}</p><div class="pairf">
    <input id="np1" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin1"))}" aria-label="${esc(t("pair.pin1"))}">
    <input id="np2" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin2"))}" aria-label="${esc(t("pair.pin2"))}">` : ""}
    <button class="pri" id="go">${ic("link")} ${t("pair.go")}</button></div><p class="err" id="e"></p>`
    : `<p>${ic("hourglass")} ${t("pair.closedP")}</p><button id="retry" onclick="location.reload()">${ic("refresh-cw")} ${t("retry")}</button>`}
    ${hello.owner || hello.mode === "basic" ? "" : `<p class="err">${ic("triangle-alert")} ${t("pair.notSetup")}</p>`}
  </div>`;
  // app, or a LAN browser adding another phone: back to the add screen; this phone's own pairing: back to the list if it has kids
  if (APP || target || homeKids().length) {
    $("#app").insertAdjacentHTML("beforeend", `<button id="abk">${ic("list")} ${t("back")}</button>`);
    $("#abk").onclick = () => APP || target ? addScreen() : appBoot();
    if ($("#retry") && target) $("#retry").onclick = () => lanHello(target);
  }
  const go = $("#go"); if (!go) return;
  if (prefill) (hello.pin ? $("#pin") : newPin ? $("#np1") : go).focus();
  const idle = go.innerHTML;
  // busy: the phone can take seconds (PIN hashing on old phones); show it, block double taps, give up after 20 s
  const busy = (on, label) => {
    go.disabled = on; go.setAttribute("aria-busy", on ? "true" : "false");
    go.innerHTML = on ? `<i class="spin"></i> ${label}` : idle;
    document.querySelectorAll(".pairf input").forEach(i => i.disabled = on);
  };
  go.onclick = async () => {
    if (go.disabled) return;
    $("#e").textContent = "";
    const ac = new AbortController(), to = setTimeout(() => ac.abort(), 20000);
    try {
      const body = { code: $("#code").value, pin: $("#pin") ? $("#pin").value : "" };
      if (newPin) {
        const a = $("#np1").value, b = $("#np2").value;
        if (!/^\d{4,8}$/.test(a)) throw new Error(t("pe.bad pin"));
        if (a !== b) throw new Error(t("pe.mismatch"));
        body.newPin = a;
      }
      // the phone keeps nothing until this page uses the token once (open -> /state): closing now leaves it unpaired
      busy(true, t("pair.busy"));
      const r = await fetch(pre + "/pair", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body), signal: ac.signal });
      const j = await r.json();
      if (!r.ok) throw new Error(hasT("pe." + j.error) ? t("pe." + j.error, Math.ceil((j.wait || 60000) / 60000)) : t("pe.fail", String(j.error || r.status).slice(0, 40)));
      clearTimeout(to);
      const k = { id: j.id, token: j.token, name: $("#nm").value || t("kid"), via: "lan", ...(target ? { target } : {}) };
      kids = kids.filter(x => !(x.id === k.id && x.via === "lan")).concat(k); save(kids);
      busy(true, t("pair.opening")); open(k);
    } catch (e) {
      clearTimeout(to); busy(false);
      $("#e").textContent = e.name === "AbortError" ? t("pe.timeout") : e.message;
    }
  };
}

// The relay's front door (owner 2026-11-11: "/" was a dead end with no phone yet): every way in from one page, as rows
// (install on the kid's phone, Device Owner QR, how to pair, the guide). Full card when no phone yet, a short one after.
function startCard(full) {
  const row = (href, icn, k, ext) => `<a class="go" href="${href}"${ext ? ' target="_blank" rel="noopener"' : ""}><span class="gi">${ic(icn)}</span>`
    + `<span class="gt"><b>${t("st." + k)}</b>${full ? `<small>${t("st." + k + "D")}</small>` : ""}</span>${ic(ext ? "external-link" : "chevron-right")}</a>`;
  return `<div class="card start"><h2>${ic("sparkles")} ${t(full ? "st.title" : "st.more")}</h2>${full ? `<p class="sub">${t("st.none")}</p>` : ""}`
    + row("/install", "download", "install") + row("/qr", "shield", "qr")
    + (full ? `<div class="go nolink"><span class="gi">${ic("link")}</span><span class="gt"><b>${t("st.pair")}</b><small>${t("st.pairD")}</small></span></div>` : "")
    + row(SITE + "/guide", "book-open", "guide", true) + `</div>`;
}

function listScreen(list) {
  reScreen = () => listScreen(list);
  $("#app").innerHTML = head("KidGuard") + (list.length ? list.map((k, i) =>
    kidRow(k, i)).join("")
    : "")
    + (list.length > 1 ? `<button class="pri" id="ovGo">${ic("layout-grid")} ${t("ovN", list.length)}</button>` : "")
    + (anyOk() ? anyCard() : "") + startCard(!list.length);
  anyWire();
  $("#app").querySelectorAll("[data-i]").forEach(b => b.onclick = () => open(list[+b.dataset.i]));
  kidBrief(list);
  if ($("#ovGo")) $("#ovGo").onclick = () => overview(list, () => listScreen(list));
}

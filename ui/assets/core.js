// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// One page for both: served by the child phone on the LAN (/api/...) and by the relay worker (/c/<id>/...).
const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
// pick: saved choice, else the first browser language we have (vi* -> vi), else en
let lang = (() => {
  // parent app: its own saved choice first (the WebView origin, and so localStorage, changes with the proxy port)
  try { const s = window.KGApp && KGApp.getLang && String(KGApp.getLang() || ""); if (s && L[s]) return s; } catch {}
  try { const s = localStorage.getItem("kg.lang"); if (s && L[s]) return s; } catch {}
  for (const n of navigator.languages || [navigator.language || ""]) { const b = String(n).toLowerCase().split("-")[0]; if (L[b]) return b; }
  return "en";
})();
const hasT = k => k in L[lang] || k in L.en;
// t("key", a, b): chosen language -> en -> the key itself. {0} {1} = args; a {one, other} value picks a plural form by the first arg.
function t(k, ...a) {
  let v = L[lang][k] ?? L.en[k] ?? k;
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
function setLang(l) {
  if (!L[l] || l === lang) return;
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
  try { localStorage.setItem(KEY, JSON.stringify(k, (key, v) => key === "healAt" || key === "fb" || key === "relayTry" ? undefined : v)); } catch {}
  if (APP) kgCall("setKids", JSON.stringify(k.filter(x => x.target).map(x => ({ name: x.name, target: x.target, via: x.via, id: x.id, token: x.token, lv: x.lv, relay: x.relay }))));
};
let kids = load(), cur = null, st = null, pendingRules = null, timer = null, edit = null, tab = "home", lastErr = "", viewDay = null, histDays = null, cachedAt = 0, seenAt = 0, answered = new Set(), histSel = -1, lastNet = false;
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
const capName = c => hasT("cap_" + c) ? t("cap_" + c) : c;
// section id -> feature id (sec() dims the body of a section whose feature is not ok), tab id -> feature id (render())
const FEAT_SEC = { "r-daily": "limits", "r-grp": "limits", "r-bed": "limits", "r-wind": "limits", "r-sch": "limits", "r-dns": "dns", "r-anti": "noUninstall",
  "r-kind": "launcherLock", "r-zone": "location", "r-trk": "location", "cl-sug": "debloat", "cl-rest": "debloat" };
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
const dm = k => new Date(+k.slice(0, 4), +k.slice(5, 7) - 1, +k.slice(8, 10)).toLocaleDateString(lang, { day: "numeric", month: "short" });   // "YYYY-MM-DD" -> "7 thg 11" / "Nov 7"
const DNS = () => [["", t("off")], ["family-filter-dns.cleanbrowsing.org", "CleanBrowsing Family", 1], ["family.adguard-dns.com", "AdGuard Family", 1], ["family.cloudflare-dns.com", "Cloudflare Family", 0]];
// [host, name, forces SafeSearch + YouTube restricted via DNS]. Cloudflare Family only blocks adult/malware domains.
const WD_STEPS = [0, 15, 30, 45, 60];
// rules from older phones may lack v0.2 fields
function norm(e) {
  e.hidden = Array.isArray(e.hidden) ? e.hidden : [];   // v0.8 preinstalled apps hidden by the parent
  e.dailyByDay = Array.isArray(e.dailyByDay) && e.dailyByDay.length === 7 ? e.dailyByDay : [];
  e.schedules = e.schedules || []; e.approved = e.approved || []; e.safeDns = e.safeDns || ""; e.safeDnsPauseUntil = +e.safeDnsPauseUntil || 0; e.noVpn = !!e.noVpn;
  e.trackMin = e.trackMin === undefined ? 5 : +e.trackMin; e.lockLocation = e.lockLocation !== false; e.noAirplane = e.noAirplane !== false; e.showWatching = e.showWatching !== false; e.autoUpdate = e.autoUpdate !== false; e.tsKeep = !!e.tsKeep; e.blockNew = !!e.blockNew; e.monitor = !!e.monitor;
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
const quick = ms => typeof AbortSignal.timeout === "function" ? AbortSignal.timeout(ms) : undefined;
function base(k) { return (APP ? "/k/" + b64u(k.target) : direct(k)) + (k.via === "lan" ? "/api" : "/c/" + encodeURIComponent(k.id)); }
// the kids the home list shows: app = every kid with an address; LAN browser = every LAN kid saved on this origin
const homeKids = () => APP ? kids.filter(k => k.target) : kids.filter(k => k.via === "lan");
// Error with a readable message (keys "e.<code>"); .raw keeps the original code for logic ("not live", "not paired"), .net = our own network failed
function errOf(raw, msg) {
  let m = msg || tc("e.", raw, "");
  if (!m) { const h = /^HTTP (\d+)$/.exec(raw); m = h ? t("e.http", h[1]) : raw; }
  const e = new Error(m); e.raw = raw; e.net = raw === "net"; return e;
}
// X-KG-Name: how this browser shows up in the kid's "paired parent devices" list (state.devices[].name). ASCII only:
// fetch() refuses header values outside ISO-8859-1.
const KG_NAME = (() => { const u = navigator.userAgent || "";
  const br = APP ? "KidGuard app" : /Edg\//.test(u) ? "Edge" : /OPR\/|Opera/.test(u) ? "Opera" : /SamsungBrowser/.test(u) ? "Samsung Internet" : /Firefox\//.test(u) ? "Firefox" : /Chrome\//.test(u) ? "Chrome" : /Safari\//.test(u) ? "Safari" : "Browser";
  const os = /Android/.test(u) ? "Android" : /iPhone|iPad|iPod/.test(u) ? "iOS" : /Mac OS X|Macintosh/.test(u) ? "Mac" : /Windows/.test(u) ? "Windows" : /CrOS/.test(u) ? "ChromeOS" : /Linux/.test(u) ? "Linux" : "";
  return (br + (os ? " / " + os : "")).replace(/[^\x20-\x7e]/g, "").slice(0, 40); })();
async function call1(k, path, method, body) {
  // app mode: a cached snapshot means the phone/relay is unreachable, so commands cannot be delivered
  if (APP && method !== "GET" && viewDay && k === cur) throw new Error(t("e.viewOnly"));
  if (APP && method !== "GET" && cachedAt && k === cur) throw new Error(t("e.cachedOff"));
  if (k.fb && k.via === "lan" && Date.now() - k.fb < 60000 && relayable(path, method)) { const j = await viaRelay(k, path, method, body); if (path === "/state" && k === cur) cachedAt = 0; return j; }
  let r; const t0 = Date.now();
  try { r = await fetch(base(k) + path, { method, headers: { "X-KG-Token": k.token, "X-KG-Name": KG_NAME, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined, signal: direct(k) ? quick(15000) : undefined }); }
  catch (e) { const x = errOf("net", APP ? t("e.lost") : null); x.late = Date.now() - t0 > 3000; throw x; }   // late = it may have arrived
  const cached = APP && method === "GET" && path === "/state" ? +r.headers.get("X-KG-Cached") || 0 : 0;
  // app: a cached snapshot = the phone did not answer; a LAN kid that can heal treats it as a network failure
  if (cached && k.via === "lan" && canHeal(k) && !body) { const e = errOf("net", t("e.lost")); e.cached = r; throw e; }
  if (APP && method === "GET" && path === "/state" && k === cur) cachedAt = cached;
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw errOf(APP && j.error === "offline" ? "offline" : j.error || ("HTTP " + r.status));
  return j;
}

// LAN kid whose address changed (DHCP, other Wi-Fi): try the addresses it reported before (state.lan, Tailscale ip),
// then the ones in its relay state; still nothing -> state / commands / rules over its relay for a minute at a time.
// Each step is automatic (docs/protocol.md "LAN address heal").
const LAN_PORT = 8770;
/** The author's shared relay, on by default on paired phones (Relay.DEFAULT_URL in the app). */
const DEFAULT_RELAY = "https://kidguard.99-hoangtran.workers.dev";
/** The link that always opens: the relay's /go page (core.js goBoot). */
const goLink = (url, id, token, name) => `${url}/go#c=${encodeURIComponent(id)}&k=${encodeURIComponent(token)}&n=${encodeURIComponent(name || "")}`;
const lanLink = (base, id, token, name) => `${base}/#lk=${token}&rc=${encodeURIComponent(id)}&ru=${encodeURIComponent(location.origin)}&n=${encodeURIComponent(name || "")}`;
const GO_FRESH = 180000;   // the phone syncs at least every 60 s (screen off); 3 min without = offline
/**
 * Relay /go#c&k&n: ask the relay where the phone is. Same public network + seen lately -> open its LAN page (faster,
 * listen works) with the relay token; Back within 90 s (the LAN page did not load) -> stay on the relay with a toast.
 * Offline -> an error screen with what the relay last saw. Otherwise -> the normal page over the relay.
 */
async function goBoot(h) {
  isRelayOrigin = true;
  const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay" };
  kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids);
  let w = null, err = "";
  try {
    const r = await fetch(`/c/${encodeURIComponent(k.id)}/where`, { headers: { "X-KG-Token": k.token }, signal: quick(10000) });
    w = await r.json().catch(() => null);
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
    ${w && w.seen ? `<p class="mute">${esc(t("go.seen", new Date(w.seen).toLocaleString()))}</p>` : ""}
    ${w && w.lan ? `<p class="mute">${esc(t("go.lastLan", host(w.lan)))}</p>` : ""}
    ${w && w.ts ? `<p class="mute">${esc(t("go.ts", w.ts))}</p>` : ""}
    <p class="mute">${t("go.why")}</p>
    <div class="row" style="flex-wrap:wrap;gap:8px">
      <button id="goRetry" class="pri">${ic("refresh-cw")} ${t("go.retry")}</button>
      ${w ? `<button id="goOpen">${ic("eye")} ${t("go.openAnyway")}</button>` : ""}
      ${w && w.lan ? `<a class="lnkbtn" href="${esc(lanLink(w.lan, k.id, k.token, k.name))}">${ic("wifi")} ${t("go.tryLan")}</a>` : ""}
      ${ts ? `<a class="lnkbtn" href="${esc(lanLink(ts, k.id, k.token, k.name))}">${ic("globe")} ${t("go.tryTs")}</a>` : ""}
    </div></div>`;
  $("#goRetry").onclick = () => { try { sessionStorage.removeItem("kg.go." + k.id); } catch {} location.replace(goLink(location.origin, k.id, k.token, k.name)); };
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
const canHeal = k => !LOCAL && !!((k.alt && k.alt.length) || k.relay);
const relayable = (path, method) => (path === "/state" && method === "GET") || (path === "/cmd" && method === "POST") || (path === "/rules" && method === "PUT");
const addrsOf = s => { if (!s) return [];
  const out = []; if (typeof s.lan === "string" && /^http:\/\/[\d.]+:\d+$/.test(s.lan)) out.push(s.lan);
  const ip = s.ts && s.ts.ip; if (typeof ip === "string" && /^100\.[\d.]+$/.test(ip)) out.push(`http://${ip}:${LAN_PORT}`);
  return out; };
async function viaRelay(k, path, method, body) {
  const rl = k.relay, b = (APP ? "/k/" + b64u(rl.url) : rl.url) + "/c/" + encodeURIComponent(rl.id);
  let r;
  try { r = await fetch(b + path, { method, headers: { "X-KG-Token": rl.token, "X-KG-Name": KG_NAME, "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined, signal: quick(15000) }); }
  catch (e) { throw errOf("net", APP ? t("e.lost") : null); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw errOf(j.error || ("HTTP " + r.status));
  return j;
}
const healing = new Map();
function relocate(k) {
  if (healing.has(k)) return healing.get(k);
  if (Date.now() - (k.healAt || 0) < 20000) return Promise.resolve(false);
  k.healAt = Date.now();
  const tried = new Set([k.target]);
  const probe = async u => {
    if (!u || tried.has(u)) return false;
    tried.add(u);
    try {
      const r = await fetch(lanApi(u) + "/hello", { signal: quick(4000) });
      const h = r.ok ? await r.json() : null;
      if (h && h.id === k.id) { const from = k.target; k.target = u; k.alt = [from, ...(k.alt || [])].filter(x => x !== u).slice(0, 4); k.fb = 0; save(kids); toast("ok|" + t("lan.moved", u.replace(/^http:\/\//, ""))); return true; }
    } catch {}
    return false;
  };
  const p = (async () => {
    for (const u of k.alt || []) if (await probe(u)) return true;
    if (k.relay) { try { const j = await viaRelay(k, "/state", "GET"); for (const u of addrsOf(j && j.state)) if (await probe(u)) return true; } catch {} }
    return false;
  })().finally(() => healing.delete(k));
  healing.set(k, p);
  return p;
}
const REPLAY_OK = new Set(["lock", "locate", "wake", "ringStop", "listenStop", "sosAck", "setting", "devOn", "devOff", "openPairing"]);
async function call(k, path, method = "GET", body) {
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
  if (url && (!k.relay || k.relay.url !== new URL(url).origin) && !k.relayTry) {
    k.relayTry = 1;   // the same url again: the phone keeps its relay id/token and just returns them (not logged)
    call1(k, "/cmd", "POST", { type: "relay", url }).then(r => {
      if (r && /^[0-9a-f]+$/.test(r.id || "") && /^[0-9a-f]{48}$/.test(r.token || "")) { k.relay = { url: new URL(url).origin, id: r.id, token: r.token }; save(kids); }
    }).catch(() => {});
  }
}

// --- boot ---------------------------------------------------------------
async function boot() {
  if (navLvl()) try { history.replaceState(null, ""); } catch {}   // a reload keeps the old entry state
  if (APP) return appBoot();
  if (LOCAL) return localBoot();
  // a link from the child: #c=<id>&k=<token>&n=<name>
  const h = new URLSearchParams(location.hash.slice(1));
  // QR from the child's screen: #pair=<6 digits> prefills the code; drop the hash so a reload does not reuse it
  const pc = /^\d{6}$/.test(h.get("pair") || "") ? h.get("pair") : "";
  if (h.has("pair")) history.replaceState(null, "", location.pathname + location.search);
  // from a relay /go link: this phone's own page with the relay token (also a LAN token on the phone)
  if (/^[0-9a-f]{48}$/.test(h.get("lk") || "")) return lanFromGo(h);
  if (location.pathname === "/go" && h.get("c") && h.get("k")) return goBoot(h);
  if (h.get("c") && h.get("k")) {
    isRelayOrigin = true;
    const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay" };
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

// --- app mode: kid list, add-kid ---------------------------------------------
function appBoot() {
  save(kids);   // tell the app the current list once on load
  const list = homeKids(); reScreen = appBoot;
  $("#app").innerHTML = head("KidGuard") + (list.length ? list.map((k, i) =>
    `<div class="card row"><b class="sp">${ic("user")} ${esc(k.name)}${APP ? ` <span class="mute">· ${k.via === "lan" ? t("via.lan") : "relay"}</span>` : ""}</b><button class="pri" data-i="${i}">${ic("folder-open")} ${t("open")}</button></div>`).join("")
    : `<div class="card empty"><span class="e">${ic("inbox")}</span><p>${t("app.none")}</p></div>`) +
    `<div class="row">${list.length > 1 ? `<button class="pri sp" id="ovGo">${ic("layout-grid")} ${t("ovN", list.length)}</button>` : ""}<button class="${list.length > 1 ? "" : "pri "}sp" id="addKid">${ic("plus")} ${t("addKid")}</button></div>`;
  $("#app").querySelectorAll("[data-i]").forEach(b => b.onclick = () => open(list[+b.dataset.i]));
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
function addScreen() {
  const keep = [($("#addr") || {}).value || "", ($("#rlink") || {}).value || ""]; reScreen = addScreen;
  $("#app").innerHTML = head(t("addKid")) + `
  <div class="card"><h2>${ic("wifi")} ${t("add.lanH")}</h2>
    <p class="mute">${t("add.lanP")}</p>
    <div class="pairf"><input id="addr" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="192.168.1.5" aria-label="${esc(t("add.addr"))}" value="${esc(keep[0])}"><button class="pri" id="lanGo">${ic("link")} ${t("add.connect")}</button></div><p class="err" id="e1"></p>
    ${APP ? "" : `<p class="mute">${ic("lightbulb")} ${t("add.sameOrigin")}</p>`}</div>
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
  if ($("#rGo")) $("#rGo").onclick = () => {
    try {
      const u = new URL($("#rlink").value.trim()), h = new URLSearchParams(u.hash.slice(1));
      if (u.protocol !== "https:" || !h.get("c") || !h.get("k")) throw 0;
      const k = { id: h.get("c"), token: h.get("k"), name: h.get("n") || t("kid"), via: "relay", target: u.origin };
      kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); save(kids); open(k);
    } catch { $("#e2").textContent = t("add.badLink"); }
  };
}
// plain screens (list / add / pair): one centred column (#app.solo); render() and the overview clear it
// back: a sub-screen (app settings) shows a back arrow instead of the logo, and no settings gear
const head = (title, back) => { $("#app").className = "solo"; return `<div class="row" style="margin-bottom:14px">${back ? `<button type="button" class="rb" id="appSetBack" aria-label="${esc(t("back"))}" title="${esc(t("back"))}">${ic("arrow-left")}</button>` : `<span class="badge">${ic("shield")}</span>`}<h1 class="sp" style="margin:0">${title}</h1>${back ? "" : APP ? `<button type="button" class="rb" id="appSetBtn" aria-label="${esc(t("au.h"))}" title="${esc(t("au.h"))}">${ic("settings")}</button>` : `${langSel()}${themeBtn()}`}</div>`; };
document.addEventListener("click", e => { if (APP && e.target.closest && e.target.closest("#appSetBtn")) appSettings(); });
async function lanHello(target) {
  const e = $("#e1"); if (e) e.textContent = t("connecting");
  try {
    const r = await fetch(lanApi(target) + "/hello", { signal: APP ? undefined : quick(8000) });
    const hello = r.ok ? await r.json() : null;
    if (!hello || !hello.id) throw 0;
    const k = kids.find(x => x.via === "lan" && x.id === hello.id);
    if (k) { k.target = target; save(kids); return open(k); }
    pairScreen(hello, target);
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
      if (!r.ok) throw new Error(hasT("pe." + j.error) ? t("pe." + j.error, Math.ceil((j.wait || 60000) / 60000)) : t("pe.fail"));
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
    + row("https://kid-guard.pages.dev/guide", "book-open", "guide", true) + `</div>`;
}

function listScreen(list) {
  reScreen = () => listScreen(list);
  $("#app").innerHTML = head("KidGuard") + (list.length ? list.map((k, i) =>
    `<div class="card row"><b class="sp">${ic("user")} ${esc(k.name)}</b><button class="pri" data-i="${i}">${ic("folder-open")} ${t("open")}</button></div>`).join("")
    : "")
    + (list.length > 1 ? `<button class="pri" id="ovGo">${ic("layout-grid")} ${t("ovN", list.length)}</button>` : "")
    + startCard(!list.length);
  $("#app").querySelectorAll("[data-i]").forEach(b => b.onclick = () => open(list[+b.dataset.i]));
  if ($("#ovGo")) $("#ovGo").onclick = () => overview(list, () => listScreen(list));
}

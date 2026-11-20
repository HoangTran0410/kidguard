// KidGuard relay: lets the parent's page reach the child phone when they are not on the same Wi-Fi.
// One Durable Object per child (by relay id). It stores the last state the child posted, the newest rules the
// parent set, and a short queue of commands. The child polls POST /c/<id>/sync every 20-60 s.
// Auth: the first child sync binds sha256 of the phone's token to the id. Parents get their own token (docs/protocol.md
// "Parents"); the phone's token opens the parent routes only while no parent has one (old links / old pages).
// JSON only (plan B, docs/decisions/remote.md 2026-11-18): no page or files; the page lives on the website and calls in with
// CORS. Command types are short codes (docs/protocol.md "Command codes"): no feature names in this bundle.
import { createHash } from "node:crypto";
import { quietStep, QUIET_ALERT } from "./quiet.js";

const MAX_BODY = 256 * 1024, MAX_DEVICE = 32 * 1024, MAX_LOGDAY = 100 * 1024, KEEP_LOGDAYS = 4;
const MAX_FRAME = 1024 * 1024;
const MAX_RULES = 64 * 1024, MAX_CMD = 4096, MAX_CMDS = 20;   // 64 KB + 20 x 4 KB stays far below the phone's 256 KB reply cap
const KEYS = ["back", "home", "recents", "notifications", "quick", "lock", "power"];
const PKG = /^[A-Za-z0-9._]{1,200}$/, MAX_ICONS = 400;
// icon URLs end up in request logs: the page sends ?k=sha256("icon:" + token) (only opens icons), stored at the first authenticated request
const ICON_KEY = /^[0-9a-f]{64}$/;
// video call (docs/protocol.md "Call"): the relay passes the two SDPs; sound and picture go phone <-> parent directly
// (TURN only when the networks need it). Ring 45 s, a call ends by itself after 1 h, an ended one is kept 60 s.
const CALL_RING = 45e3, CALL_MAX = 60 * 60e3, CALL_KEEP = 60e3, CALL_SDP = 16000, CALL_ID = /^[0-9a-f]{16}$/;
const CALL_WHY = ["hangup", "decline", "busy", "timeout", "failed", "noperm", "nocam", "missed", "no mic permission", "notice blocked", "mic blocked", "mic failed", "ringing"];
// "listen around" over the relay = the same call slot with kind "listen": the phone sends its mic only, nobody taps Answer,
// the child gets the beep + notification (Listen.kt). Capped at 10 min like the LAN stream.
const LISTEN_MAX = 10 * 60e3 + 15e3;
// "live camera" = the same slot with kind "cam" (+ face front|back, snd): one camera (+ mic) one way, no tap, 10 min like listen.
// A kid app older than CAM_KID would take the offer for a video call and ring: refused here (409 old).
const CAM_KID = "0.9.71", CAM_WHY = ["off", "no camera permission", "no such camera", "screen off", "cam blocked", "cam failed"];
const vOlder = (a, b) => { const x = String(a).split(".").map(n => parseInt(n, 10) || 0), y = String(b).split(".").map(n => parseInt(n, 10) || 0); for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) < (y[i] || 0); return false; };
// <img> can't send headers: only the icon GET may carry the token in ?k=
const wsToken = req => { const m = /(?:^|,)\s*kg\.([0-9a-f]{48})\s*(?:,|$)/.exec(req.headers.get("sec-websocket-protocol") || ""); return m ? m[1] : ""; };
const tokenOf = (req, url) => req.headers.get("x-kg-token") || (req.method === "GET" && url.pathname.endsWith("/icon") ? url.searchParams.get("k") || "" : "")
  || (req.method === "GET" && url.pathname.endsWith("/scr") ? wsToken(req) : "");
// shared files (docs/protocol.md "Shared files"): <= 50 MB a file in 1 MiB chunks (a chunk is the only thing the DO holds: 128 MB memory),
// <= 100 MB stored per kid, kept 1 h, deleted once fetched. Anti-spam: the default relay is one free account for everybody, so each kid also has a
// 24 h quota (bytes + files, both directions, counted when a file starts; one small record "fq" {start, bytes, n}) -> 429 {error:"quota", retryAt}
const FILE_CHUNK = 1048576, FILE_MAX = 50 * 1048576, FILE_TTL = 3600e3, FILE_N = 8, FILE_TOTAL = 100 * 1048576, FILE_DAY_BYTES = 300 * 1048576, FILE_DAY_N = 100, FILE_THUMB = 204800, FID = /^[0-9a-f]{16}$/;
const limitFor = (action) => action === "frame" ? MAX_FRAME : action === "file" ? FILE_CHUNK : action === "watch" ? 512 : MAX_BODY;
// Zepp watch connect (docs/protocol.md "Watch connect"): a code on the watch, typed into the parent page
const WATCH_TTL = 10 * 60e3, WATCH_PER_DAY = 30;
// Pair from anywhere (docs/protocol.md "Pair over relay"): a code the unpaired phone shows (/p/new) or an invite the parent made (/p/inv)
const PAIR_TTL = 15 * 60e3, INVITE_TTL = 3 * 3600e3, PAIR_PER_DAY = 100, INVITE_PER_DAY = 30, PAIR_JOINS = 5;
// Wrong codes per client network: a fixed PAIR_MISS_WIN window (starts at the first miss; a burst at a window edge can double it once), not a per-day cap (one bad actor must not lock a whole network
// out of pairing for a day). Math: a wrong guess does not name a code, so the risk is hitting ANY live 6-digit code:
// P(hit) ~ live codes x guesses / 10^6. 20 misses / 10 min, a code living 15 min => <= ~30 guesses per code lifetime per network;
// even 1000 live codes at once = ~3 % per network per code lifetime, and each hit still needs the kid phone's PIN / the PAIR_JOINS cap.
const PAIR_MISS_MAX = 20, PAIR_MISS_WIN = 10 * 60e3;
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
});
const ONCE = ["rb", "rl", "ua", "up", "cl", "ud", "ts", "fd", "gs", "go", "ps", "pr", "pc", "ab", "ap", "sx", "so", "cc", "cf", "cd", "sd"];   // destructive: handed out until acked, but only within ONCE_WINDOW of the first hand-out
const STATE_FLUSH = 10 * 60e3, SEEN_EVERY = 10 * 60e3, PAR_SEEN_EVERY = 3600e3;   // storage rows: see stateCore / sync
const ONCE_WINDOW = 3600e3;   // 1 h (owner 2026-11-15: a phone that is off for 10 min must not lose a release / reboot)
// push wake (kid app's FCM token, sync body `fcm`): quiet this long => high-priority data push {kind:"wake"}. The phone's
// watchdog syncs every ~10 min even asleep, so 20 min quiet = asleep beyond that, killed, or offline. Then every 20 min
// (9 times), then hourly, for 2 days. A parent reading the state of a phone quiet > 3 min wakes it too (once per 3 min).
const WAKE_QUIET = 20 * 60e3, WAKE_PARENT = 3 * 60e3, WAKE_DAYS = 2 * 86400e3;
const GC_MS = 30 * 86400e3;   // a phone not seen for 30 days: everything of this id is deleted (dead pairings, junk binds); a phone that comes back re-binds on its next sync
// "now" commands, stale after SHORT_TTL (10 min). Only what would be harmful or confusing if it fired hours later:
//  lock locate message ring wake ringStop listenStop = about the moment; reboot clearData = a destructive one asked while the phone was off
//  must not fire next day; pinSet pinReset pinResetCancel = a PIN change or recovery nobody is waiting for any more; openPairing = a 10 min
//  window for a parent standing next to the phone; adbShell = a shell command running a day later is surprising; unlockEnd = ends a
//  temporary pause "now"; fixPerms = opens Settings screens on the kid's phone, confusing if the kid is using it hours later.
// Everything else (setting devOn devOff bgLimit bgRestore rescan clearCache support ...) is harmless late: normal 24 h.
const SHORT = ["fu", "sh", "uu", "np", "sp", "cp", "cc", "cf", "cd", "sl", "su", "sd", "sn", "lk", "lc", "ms", "rg", "wk", "rs", "ls", "rb", "cl", "ps", "pr", "pc", "op", "ab", "ue", "fp", "so", "ti"];
let SHORT_TTL = 10 * 60e3;   // env.SHORT_TTL_MS overrides (tests only)
// secrets a parent typed into a command (PIN, new PIN, Authenticator secret, bot token): handed out once, then not kept in the queue copy
const SECRET_KEYS = ["pin", "new", "secret", "token"];
const hasSecret = (c) => SECRET_KEYS.some(k => k in c);
const noSecret = (c) => { const o = { ...c }; for (const k of SECRET_KEYS) delete o[k]; return o; };
// first binds per client network per day (see Gate): a phone binds once in its life, so a family stays far below it
const BIND_PER_DAY = 30, UNUSED_MS = 7 * 86400e3;
// still wanted? (24 h overall; "now" commands 10 min; a destructive one only shortly after it was first handed to the phone)
const live = (c, now) => now - (c.at || 0) < (SHORT.includes(c.type) ? SHORT_TTL : 86400e3) && !(c.sent && now - c.sent > ONCE_WINDOW);
/** Receipts for commands dropped unrun (TTL / ONCE window): newest last, at most EXPIRED_N, shown to the parent as state `expired`. */
const EXPIRED_N = 10;
const expiredOf = (list, dropped, now) => [...(list || []), ...dropped.map(c => ({ id: c.id, type: c.type, res: "expired", at: now }))].slice(-EXPIRED_N);
const CMDS = ["ai", "rd", "uu", "sp", "cp", "cc", "cf", "cd", "sl", "su", "sd", "sn", "lk", "lc", "ms", "rg", "op", "ua", "up", "rl", "an", "rb", "to", "sa", "cl", "ud", "ts", "wk", "rs", "ls", "d0", "d1", "st", "fu", "fd", "sh", "np", "gs", "gc", "gu", "go", "ps", "pr", "pc", "sx", "sy", "ab", "hc", "ap", "be", "ue", "ca", "re", "bl", "br", "fp", "so", "ti"];
// idempotent commands a newer copy may replace when the queue is full: type -> target field that must match too (null = the type alone)
const REPLACE = { lk: null, lc: null, rg: null, wk: null, rs: null, ls: null, d1: null, d0: null, st: "key", ca: null, re: null, fp: null, ti: null, op: null, ue: null, be: null, np: null, sp: null, cp: null, sl: null, cc: null, fu: "file" };
// AI token (docs/protocol.md "AI token"): the phone sends sha256 of it in every sync (`ai`); a request with it gets role "ai".
// Same lists as K/guard/AiScope.kt: commands it may queue, rule keys it can never change (the stored value stays).
const AI_CMDS = ["lk", "lc", "ms", "rg", "rs", "sa", "an"];
const AI_LOCKED = ["pin", "pinHash", "pinSalt", "pinAt", "pinSet", "noInstall", "noDebug", "pinShot", "lockLocation", "noAirplane", "frpIds", "safeDns", "safeDnsPauseUntil", "noVpn", "tsKeep", "vpnPause", "accPause", "devPause", "tcpPause", "autoUpdate", "showWatching", "unlockMin", "hidden", "approved", "mode", "watch"];
// what an AI token never reads (audit 2026-11-20): the child's messages, calls, notifications, route, files, photos, addresses;
// events of these kinds keep their kind + time, not their text
const AI_HIDE = ["notifs", "chat", "calls", "sms", "track", "log", "files", "listen", "snaps", "notes", "tg", "lan", "ts", "relay", "devices", "webOther"];
const EV_PRIVATE = ["word", "chat", "sms", "call", "nf"];
const aiState = (st) => {
  if (!st) return st;
  const s = { ...st };
  for (const k of AI_HIDE) delete s[k];
  if (Array.isArray(s.events)) s.events = s.events.map(e => e && EV_PRIVATE.includes(e.kind) ? { ...e, text: "" } : e);
  return s;
};
// a watch token (kind "watch"): what the watch apps call (watch/*/ Api), nothing else (audit 2026-11-20)
const watchOk = (action, m) => (action === "state" || action === "tile" || action === "icon" || action === "icons") ? m === "GET" : action === "rules" ? m === "PUT" : action === "cmd" && m === "POST";
const aiRoute = (action, m) => (action === "state" || action === "icon") ? m === "GET" : action === "rules" ? m === "PUT" : action === "cmd" && m === "POST";
const SETTING_KEYS = ["vol.ring", "vol.media", "vol.alarm", "bright", "autoBright", "timeoutMs", "dnd"];
const isObj = (o) => o && typeof o === "object" && !Array.isArray(o);
const stripPin = (r) => {
  if (!isObj(r)) return r;
  const { pinHash, pinSalt, pin, ...o } = r;
  o.pinSet = !!pinHash || r.pinSet === true;
  return o;
};


// The relay is JSON only (plan B, docs/decisions/remote.md 2026-11-18): the parent page, /install and /qr live on the
// website (Pages) and call here cross-origin. Tokens ride in X-KG-* headers, never cookies, so any origin may call.
const CORS = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
  "access-control-allow-headers": "*", "access-control-expose-headers": "*", "access-control-max-age": "86400" };

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const res = await route(req, env);
    if (res.status === 101 || res.webSocket) return res;
    const h = new Headers(res.headers);
    for (const [k, v] of Object.entries(CORS)) h.set(k, v);
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
  },
};

async function route(req, env) {
  const url = new URL(req.url);
  // someone opens the relay address in a browser (2026-11-20: a user read {"error":"not found"} as "broken"): say it works + what next
  if (req.method === "GET" && url.pathname === "/") return new Response(
    `KidGuard relay: OK.\nThis address is not a page. Paste it into the parent page: search "relay", Own relay, Turn on:\n  ${url.origin}\nGuide: https://kidguard.asia/en/guide#relay\n\nRelay KidGuard: chạy tốt.\nĐây không phải trang web. Dán địa chỉ này vào trang bố mẹ: tìm "relay", Relay riêng, Bật:\n  ${url.origin}\nHướng dẫn: https://kidguard.asia/guide#relay\n`,
    { headers: { "content-type": "text/plain; charset=utf-8", "x-content-type-options": "nosniff", "cache-control": "no-store" } });
  const th = req.method === "POST" && env.TG_BOT ? url.pathname.match(/^\/tg\/([0-9a-f]{32})$/) : null;
  if (th) return tgHook(req, env, th[1]);
  if (url.pathname === "/w/new" || /^\/w\/\d{6}$/.test(url.pathname)) return watchRoute(req, env, url);
  if (/^\/p\/(new|inv|\d{6}|[0-9a-f]{16})(\/(join|reply|result))?$/.test(url.pathname)) return pairRoute(req, env, url);
  const m = url.pathname.match(/^\/c\/([0-9a-f]{16})\/(sync|state|rules|cmd|live|frame|input|icons|icon|push|device|logday|file|tg|where|ws|forget|watch|tile|join|unlock|invite|parents|call|scr|mint|rekey)$/);
  if (!m) return json({ error: "not found" }, 404);
  if (env.RL) {
    const { success } = await env.RL.limit({ key: req.headers.get("cf-connecting-ip") || "?" });
    if (!success) return json({ error: "slow down" }, 429);
  }
  // reject junk before waking a Durable Object (an icon GET may carry the icon-only key instead of the token)
  if (!/^[0-9a-f]{48}$/.test(tokenOf(req, url)) && !(m[2] === "join" && !req.headers.get("x-kg-token")) && !((m[2] === "icon" || m[2] === "tile" || (m[2] === "icons" && req.method === "GET")) && ICON_KEY.test(url.searchParams.get("k") || ""))) return json({ error: "not paired" }, 401);
  const len = +(req.headers.get("content-length") || 0);
  if (len > limitFor(m[2])) return json({ error: "too big" }, 413);
  const stub = env.KIDS.get(env.KIDS.idFromName(m[1]));
  if (req.method === "GET" || req.method === "HEAD") return stub.fetch(req);
  // buffer the body (<= 1 MB, limitFor) before handing it on: a streamed body that the Durable Object answers early
  // without reading (401, 400, ...) throws "Can't read from request stream after response has been sent" here, which
  // restarts the isolate = 503 "worker restarted mid-request" for the next requests (the CI relay flake, 2026-11-18)
  const body = await req.arrayBuffer();
  if (body.byteLength > limitFor(m[2])) return json({ error: "too big" }, 413);
  return stub.fetch(new Request(req, { body }));
}

/** GET state?nf=<cursor>: `{n: rows of state.notifs with id > cursor (oldest first), more, t}`. more = rows past the cursor
 * may be missing (all the state's rows are new and it left older ones out); t = the phone's clock when it built that state. */
export function nfRead(st, cursor) {
  const nf = st && st.notifs, all = nf && Array.isArray(nf.n) ? nf.n.filter(r => r && typeof r === "object") : [];
  const n = all.filter(r => +r.id > cursor);
  return { n, more: !!(nf && nf.more) && n.length > 0 && n.length === all.length, t: +(st && st.t) || 0 };
}
/** GET state?watch=1 (docs/protocol.md "Watch connect"): what a watch shows, small enough for Garmin (~8-16 KB).
 * Scalars of the state as they are, the few lists a watch reads cut short, `apps` = the 15 most used; rules whole
 * (minus the phone's copy inside state) because watches edit them with a full-replace PUT. */
function watchState(st, rules) {
  if (!st) return { state: null, rules };
  const s = {};
  for (const [k, v] of Object.entries(st)) if (v === null || typeof v !== "object") s[k] = v;
  for (const k of ["now", "location", "lost"]) if (st[k] && typeof st[k] === "object") s[k] = st[k];
  const last = (k, n) => Array.isArray(st[k]) ? st[k].slice(-n) : [];
  s.requests = last("requests", 5); s.done = last("done", 20); s.sos = last("sos", 3);
  s.events = last("events", 10).map(e => ({ t: e.t, kind: e.kind, text: EV_PRIVATE.includes(e.kind) ? "" : String(e.text || "").slice(0, 80) }));
  // family chat on the watch (Zepp shows the last 12)
  s.chat = last("chat", 12).map(c => c && typeof c === "object" ? { ...c, text: String(c.text || "").slice(0, 160) } : c);
  const all = (Array.isArray(st.apps) ? st.apps : []).filter(a => a && a.label), cur = all.find(a => a.pkg === st.current);
  s.apps = all.sort((a, b) => (b.ms || 0) - (a.ms || 0)).slice(0, 15);
  if (cur && !s.apps.includes(cur)) s.apps.push(cur);   // the watch names the app in use from this list
  s.apps = s.apps.map(a => ({ pkg: a.pkg, label: String(a.label).slice(0, 24), ms: a.ms || 0 }));
  if (cur) s.currentLabel = String(cur.label || "").slice(0, 24);
  return { state: s, rules: rules || (st.rules || null) };
}

/** /w/new (watch asks for a code) and /w/<code> (watch polls with its secret). No auth: the watch has no kid yet; limits instead. */
async function watchRoute(req, env, url) {
  if (env.RL && !(await env.RL.limit({ key: req.headers.get("cf-connecting-ip") || "?" })).success) return json({ error: "slow down" }, 429);
  if (!env.WATCH) return json({ error: "not available" }, 501);
  const isNew = url.pathname === "/w/new";
  if (req.method !== (isNew ? "POST" : "GET")) return json({ error: "method" }, 405);
  if (+(req.headers.get("content-length") || 0) > 512) return json({ error: "too big" }, 413);
  if (!isNew) {
    const sec = req.headers.get("x-kg-secret") || "";
    if (!/^[0-9a-f]{32,128}$/.test(sec)) return json({ error: "forbidden" }, 403);
    const r = await env.WATCH.get(env.WATCH.idFromName("w:" + url.pathname.slice(3))).fetch("https://w/take?sh=" + await sha256(sec));
    return new Response(r.body, { status: r.status, headers: r.headers });
  }
  let b; try { b = await req.json(); } catch { return json({ error: "bad json" }, 400); }
  if (!b || !/^[0-9a-f]{64}$/.test(b.sh || "")) return json({ error: "bad json" }, 400);
  if (env.GATE) {   // codes per client network per day: a script must not create Durable Objects without limit
    const g = env.GATE.get(env.GATE.idFromName("wip:" + (netKey(req.headers.get("cf-connecting-ip")) || "?")));
    const r = await g.fetch("https://gate/take?max=" + (+env.WATCH_PER_DAY || WATCH_PER_DAY));
    if (r.status !== 200) return json({ error: "too many codes from this network, try tomorrow", retryAt: +(await r.text()) || Date.now() + 3600e3 }, 429);
  }
  for (let i = 0; i < 6; i++) {
    const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1e6).padStart(6, "0");
    const r = await env.WATCH.get(env.WATCH.idFromName("w:" + code)).fetch("https://w/new", { method: "POST", body: JSON.stringify({ sh: b.sh }) });
    if (r.status === 200) return json({ code, exp: (await r.json()).exp });
  }
  return json({ error: "try again" }, 503);
}

/** Pair a kid phone with no parent over the relay (docs/protocol.md "Pair over relay"). No auth (neither side has the other yet): limits + secrets.
 *  Phone: POST /p/new {sh,code} (code = its LAN code), GET /p/<key> (X-KG-Secret) polls, POST /p/<key>/reply gives {id,token} or {error}.
 *  Parent: POST /p/<code>/join {th,pin,newPin,name}, GET /p/<key>/result (X-KG-Ticket); or POST /p/inv {th,newPin,name} -> invite id for the QR. */
async function pairRoute(req, env, url) {
  if (env.RL && !(await env.RL.limit({ key: req.headers.get("cf-connecting-ip") || "?" })).success) return json({ error: "slow down" }, 429);
  if (!env.WATCH) return json({ error: "not available" }, 501);
  const [, key, op] = url.pathname.match(/^\/p\/([^/]+)(?:\/(\w+))?$/);
  const POSTS = key === "new" || key === "inv" || op === "join" || op === "reply";
  if (req.method !== (POSTS ? "POST" : "GET")) return json({ error: "method" }, 405);
  if (+(req.headers.get("content-length") || 0) > 512) return json({ error: "too big" }, 413);
  const net = netKey(req.headers.get("cf-connecting-ip")) || "?";
  // -> 0 = allowed, else the epoch ms when this network may try again (win = fixed window in ms, none = until the UTC day ends)
  const gate = async (name, max, peek, win) => {
    if (!env.GATE) return 0;
    const r = await env.GATE.get(env.GATE.idFromName(name + ":" + net)).fetch("https://gate/take?max=" + max + (peek ? "&peek=1" : "") + (win ? "&win=" + win : ""));
    return r.status === 200 ? 0 : +(await r.text()) || Date.now() + 3600e3;
  };
  const full = (what, at) => json({ error: `too many ${what} from this network, try tomorrow`, retryAt: at }, 429);
  const fwd = (k, o, body, query = "") => env.WATCH.get(env.WATCH.idFromName("p:" + k)).fetch("https://w/" + o + query, body ? { method: "POST", body: JSON.stringify(body) } : undefined);
  let b = {};
  if (POSTS) { try { b = await req.json(); } catch { return json({ error: "bad json" }, 400); } if (!b || typeof b !== "object") return json({ error: "bad json" }, 400); }
  const hex = (v, n) => typeof v === "string" && new RegExp("^[0-9a-f]{" + n + "}$").test(v);
  const clean = v => String(v || "").replace(/[\u0000-\u001f]/g, "").slice(0, 20);
  if (key === "new") {
    if (!hex(b.sh, 64) || !/^\d{6}$/.test(String(b.code || ""))) return json({ error: "bad json" }, 400);
    const at = await gate("pip", +env.PAIR_PER_DAY || PAIR_PER_DAY); if (at) return full("codes", at);
    return fwd(b.code, "pnew", { sh: b.sh });
  }
  if (key === "inv") {
    if (!hex(b.th, 64) || !/^\d{4,8}$/.test(String(b.newPin || ""))) return json({ error: "bad json" }, 400);
    const at = await gate("piv", +env.INVITE_PER_DAY || INVITE_PER_DAY); if (at) return full("invites", at);
    const inv = [...crypto.getRandomValues(new Uint8Array(8))].map(x => x.toString(16).padStart(2, "0")).join("");
    const r = await fwd(inv, "pinv", { th: b.th, req: { pin: "", newPin: String(b.newPin), name: clean(b.name) } });
    return r.status === 200 ? json({ inv, exp: (await r.json()).exp }) : r;
  }
  if (op === "join") {
    if (!/^\d{6}$/.test(key) || !hex(b.th, 64)) return json({ error: "bad json" }, 400);
    const max = +env.PAIR_MISS_MAX || PAIR_MISS_MAX, win = +env.PAIR_MISS_WIN_MS || PAIR_MISS_WIN;
    // counted BEFORE the lookup (a peek-then-count let a parallel burst pass the cap); a right code costs one try too,
    // and "busy" counts, so a live code can't be told apart for free
    const lock = await gate("pmiss", max, false, win);
    if (lock) return json({ error: "too many wrong codes", wait: Math.max(1000, lock - Date.now()), retryAt: lock }, 429);   // wait = ms until the window ends
    const r = await fwd(key, "pjoin", { th: b.th, req: { pin: String(b.pin || "").replace(/\D/g, "").slice(0, 8), newPin: String(b.newPin || "").slice(0, 8), name: clean(b.name) } });
    if (r.status !== 404) return r;
    return json({ error: "wrong code" }, 403);
  }
  if (op === "result") {
    const tk = req.headers.get("x-kg-ticket") || "";
    if (!hex(tk, 32)) return json({ error: "forbidden" }, 403);
    const r = await fwd(key, "presult", null, "?th=" + await sha256(tk));
    if (r.status !== 200) return r;
    const j = await r.json();
    if (j.token) j.url = url.origin;   // the phone's relay is the one that answered
    if (j.token && /^[0-9a-f]{16}$/.test(j.id || "")) {   // the page gets a parent token of its own, not the phone's
      const mr = await env.KIDS.get(env.KIDS.idFromName(j.id)).fetch(`https://do/c/${j.id}/_mint`, { method: "POST", headers: { "x-kg-internal": "1" },
        body: JSON.stringify({ token: j.token, name: j.name, kind: url.searchParams.get("kind") === "app" ? "app" : "web" }) });
      if (mr.status !== 200) return json(await mr.json().catch(() => ({ error: "relay" })), mr.status);
      j.token = (await mr.json()).token;
    }
    if (j.error) return json(j, j.error === "wait" || j.error === "pin locked" ? 429 : j.error === "bad pin" || j.error === "pin required" ? 400 : 403);
    return json(j);
  }
  const sec = req.headers.get("x-kg-secret") || "";
  if (!/^[0-9a-f]{32,128}$/.test(sec)) return json({ error: "forbidden" }, 403);
  const sh = await sha256(sec);
  if (op === "reply") {
    if (!(b.error ? typeof b.error === "string" : hex(b.id, 16) && hex(b.token, 48))) return json({ error: "bad json" }, 400);
    return fwd(key, "preply", b.error ? { error: b.error.slice(0, 40), wait: +b.wait || 0 } : { id: b.id, token: b.token }, "?sh=" + sh);
  }
  return fwd(key, "ppoll", null, "?sh=" + sh);
}

/** One code waiting for a parent page ("w:<code>"): {sh, exp, creds?}; deleted when taken, expired (lazy + alarm). */
export class Watch {
  constructor(ctx, env) {
    this.ctx = ctx; this.s = ctx.storage; const t = +(env && env.WATCH_TTL_MS) > 0 ? +env.WATCH_TTL_MS : 0;   // env.WATCH_TTL_MS: tests only
    this.ttl = t || WATCH_TTL; this.pttl = t || PAIR_TTL; this.ittl = t || INVITE_TTL;
  }
  /** Pair entries (w.k = "code" | "inv"): {sh (phone's secret hash), th (parent's ticket hash), req {pin,newPin,name}, res {id,token}|{error,wait}, taken, joins, exp}. */
  async pair(op, w, url, req) {
    const sh = url.searchParams.get("sh"), th = url.searchParams.get("th");
    const save = () => this.s.put("w", w);
    if (op === "pjoin") {   // the parent types the phone's code (an invite has its request already)
      if (w.k !== "code" || w.req || w.res || w.taken) return json({ error: "busy" }, 409);
      if (++w.joins > PAIR_JOINS) { await this.wipe(); return json({ error: "no such code" }, 404); }   // like the LAN flow: a few tries, then the code is dead
      const b = await req.json(); w.th = b.th; w.req = b.req; await save();
      return json({ ok: true, exp: w.exp });
    }
    if (op === "ppoll") {   // the phone (an invite is claimed by the first phone that asks)
      if (w.k === "inv" && !w.sh) { w.sh = sh; await save(); }
      if (w.sh !== sh) return json({ error: "forbidden" }, 403);
      if (w.taken) { await this.wipe(); return json({ taken: true }); }   // the parent has the link: the phone commits now
      return json(w.req && !w.res ? { req: w.req, exp: w.exp } : { waiting: true, exp: w.exp });
    }
    if (op === "preply") {
      if (w.sh !== sh) return json({ error: "forbidden" }, 403);
      if (!w.req || w.res || w.taken) return json({ error: "no request" }, 409);
      w.res = await req.json(); await save();
      return json({ ok: true });
    }
    if (op === "presult") {
      if (w.th !== th) return json({ error: "forbidden" }, 403);
      if (w.taken) return json({ error: "no such code" }, 404);
      if (!w.res) return json({ waiting: true, exp: w.exp });
      if (w.res.error) {
        const e = w.res; w.res = null;
        if (w.k === "code") { w.req = null; await save(); } else await this.wipe();   // a code can be tried again; an invite is one shot
        return json(e);
      }
      const c = w.res; w.res = null; w.taken = true; await save();   // one shot: the link leaves the relay with this reply; the phone sees `taken`
      return json({ ...c, name: (w.req && w.req.name) || "" });
    }
    return json({ error: "not found" }, 404);
  }   // env.WATCH_TTL_MS: tests only
  async fetch(req) {
    const url = new URL(req.url), op = url.pathname.slice(1), now = Date.now();
    let w = await this.s.get("w");
    if (w && w.exp <= now) { await this.wipe(); w = null; }
    if (op === "new") {
      if (w) return json({ error: "taken" }, 409);
      let b = {}; try { b = await req.json(); } catch {}
      const exp = now + this.ttl;
      await this.s.put("w", { sh: String(b.sh), exp }); await this.s.setAlarm(exp + 1000);
      return json({ exp });
    }
    if (op === "pnew" || op === "pinv") {   // pair from anywhere: the phone offers its code / the parent made an invite
      let b = {}; try { b = await req.json(); } catch {}
      const exp = now + (op === "pnew" ? this.pttl : this.ittl);
      if (w && op === "pnew" && w.k === "code" && w.sh === String(b.sh) && !w.req && !w.res && !w.taken) {   // the same phone renews its code
        w.exp = exp; await this.s.put("w", w); await this.s.setAlarm(exp + 1000); return json({ exp });
      }
      if (w) return json({ error: "taken" }, 409);
      await this.s.put("w", op === "pnew" ? { k: "code", sh: String(b.sh), exp, joins: 0 } : { k: "inv", th: String(b.th), req: b.req, exp, joins: 0 });
      await this.s.setAlarm(exp + 1000);
      return json({ exp });
    }
    if (!w) return json({ error: "no such code" }, 404);
    if (w.k) return this.pair(op, w, url, req);
    if (op === "attach") {
      if (w.creds) return json({ error: "already used" }, 409);
      w.creds = await req.json(); await this.s.put("w", w);
      return json({ ok: true, exp: w.exp });
    }
    if (op === "take") {
      if (url.searchParams.get("sh") !== w.sh) return json({ error: "forbidden" }, 403);
      if (!w.creds) return json({ waiting: true, exp: w.exp });
      await this.wipe();   // one shot: the credentials leave the relay with this reply
      return json(w.creds);
    }
    return json({ error: "not found" }, 404);
  }
  async wipe() { await this.s.deleteAlarm().catch(() => {}); await this.s.deleteAll(); }
  async alarm() { await this.s.deleteAll(); }
}

/** "Same network" key of a client address: the IPv4 address, or the /64 prefix of an IPv6 one (phones keep the prefix,
 * rotate the rest). "" when unknown. */
export function netKey(ip) {
  ip = String(ip || "").trim();
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return ip;
  if (!/^[0-9a-f:]+$/i.test(ip) || !ip.includes(":")) return "";
  const [a, b = ""] = ip.toLowerCase().split("::");
  const head = a ? a.split(":") : [], tail = b ? b.split(":") : [];
  const full = ip.includes("::") ? [...head, ...Array(8 - head.length - tail.length).fill("0"), ...tail] : head;
  return full.length === 8 ? full.slice(0, 4).map(x => x.replace(/^0+(?=.)/, "")).join(":") + "::/64" : "";
}

// Parents (docs/protocol.md "Parents"): one token each, named; the family PIN (checked here against the phone's hash) or a one-use invite gets one
const MAX_PARENTS = 12, INV_TTL = 10 * 60e3, MAX_INVS = 5, UNLOCK_MS = 12 * 3600e3, PIN_TRIES = 5, PIN_LOCK = 15 * 60e3, PIN_LOCK_MAX = 24 * 3600e3, PIN_KEYS = 16, PAR_KINDS = ["app", "web", "watch"];
// the shared (phone) token once a parent has a token of its own: the phone's routes only
const phoneRoute = (action, m) => action === "sync" || action === "ws" || action === "file" || action === "forget" || action === "join" || action === "call"
  || ((action === "mint" || action === "rekey") && m === "POST")
  || (action === "icons" && m === "POST") || (action === "frame" && m === "POST") || ((action === "device" || action === "logday") && m === "PUT");
/** A parent row that must open with the family PIN first: a browser past its 12 h, or a token the phone minted (lk). */
const parLocked = (p, now) => !!p.lk || (p.k === "web" && !(p.ul > now));
const KM_PENDING = 3;
// secAlert texts (English: older pages show `text`; newer ones translate `rk`, i18n "rev.<rk>")
const REV_TEXT = { pvRefused: "Relay: a PIN change without the old PIN was refused", pvNew: "Relay: the phone set a family PIN" };   // phone-minted tokens still waiting for their PIN: the oldest goes (a looping phone never fills MAX_PARENTS)
const cleanName = v => String(v || "").replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, 24);
const hexRand = n => [...crypto.getRandomValues(new Uint8Array(n))].map(x => x.toString(16).padStart(2, "0")).join("");
const HEXC = new TextEncoder().encode("0123456789abcdef");
/** Same as Rules.hashPin (K/guard/Rules.kt): "" salt = sha256("kg:" + pin), else 10 000 rounds. node:crypto (sync, ~9 ms): WebCrypto awaits took ~120 ms. */
function hashPin(pin, salt) {
  if (!salt) return createHash("sha256").update("kg:" + pin).digest("hex");
  const sb = new TextEncoder().encode(salt), buf = new Uint8Array(64 + sb.length); buf.set(sb, 64);
  let h = createHash("sha256").update(salt + ":" + pin).digest();
  for (let r = 0; r < 9999; r++) {
    for (let i = 0; i < 32; i++) { buf[2 * i] = HEXC[h[i] >> 4]; buf[2 * i + 1] = HEXC[h[i] & 15]; }
    h = createHash("sha256").update(buf).digest();
  }
  return h.toString("hex");
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

/** One per client network ("ip:<netKey>"): counts first binds of new relay ids a day. /take?max=N[&win=ms][&peek=1] -> 200 (counted) | 429 (body = retryAt ms epoch).
 *  No `win`: UTC-day counter (resets at the next UTC midnight). With `win`: fixed window that starts at the first count and resets win ms later. */
export class Gate {
  constructor(ctx) { this.ctx = ctx; this.s = ctx.storage; }
  async fetch(req) {
    const q = new URL(req.url).searchParams, now = Date.now(), win = +q.get("win") || 0;
    const max = Math.max(1, +q.get("max") || BIND_PER_DAY), day = Math.floor(now / 86400e3);
    const g = (await this.s.get("g")) || { day, n: 0, start: now };
    if (win ? now - (g.start || 0) >= win : g.day !== day) { g.day = day; g.n = 0; g.start = now; }
    if (g.n >= max) return new Response(String(win ? g.start + win : (day + 1) * 86400e3), { status: 429 });
    if (q.get("peek")) return new Response("ok");   // ask without counting
    g.n++; await this.s.put("g", g);
    await this.s.setAlarm(now + 2 * 86400e3);
    return new Response("ok");
  }
  async alarm() { await this.s.deleteAll(); }
}

/** Kid DO routes after auth + JSON body: "action METHOD" (or "action" = any method; the method checks itself) -> Kid method. */
const ROUTES = {
  "device": "rDevice",
  "logday": "rLogday",
  "live": "rLive",
  "input": "rInput",
  "sync POST": "rSync",
  "where GET": "rWhere",
  "state GET": "rState",
  "rules PUT": "rRules",
  "push POST": "rPush",
  "tg": "rTg",
  "cmd POST": "rCmd",
};

export class Kid {
  constructor(ctx, env) {
    this.ctx = ctx; this.s = ctx.storage; this.env = env || {};
    if (+this.env.SHORT_TTL_MS > 0) SHORT_TTL = +this.env.SHORT_TTL_MS;
    this.liveUntil = 0; this.frame = null; this.seq = Date.now(); this.seen = 0; this.seenWritten = 0; this.inputs = []; this.waiters = new Set();
    // rows written (owner 2026-11-20: ~4k/day of the 100k free): the state kept here, stored only when more than the time
    // counters changed or every STATE_FLUSH (memory only: an evicted object loses at most that much of usedMs / app ms)
    this.stateMem = null; this.stateCore = null; this.stateWritten = 0; this.stateDirty = false;
    this.lgdWant = new Map();   // day -> ms the parent asked for that log day (memory only)
    this.deviceWant = 0;   // parent asked for a device-health snapshot at this ms (memory only, like liveUntil)
  }

  /** Phone connected over the WebSocket (hibernation keeps it across evictions). */
  phoneWs() { return this.ctx.getWebSockets("phone"); }
  parWs(not) {
    const now = Date.now();
    return this.ctx.getWebSockets("parent").filter(w => {
      if (w === not || w.readyState > 1) return false;
      let p = 0; try { p = +(this.ctx.getWebSocketAutoResponseTimestamp(w) || 0); } catch {}
      return now - Math.max(p, (w.deserializeAttachment() || {}).at || 0) < 30e3;
    });
  }
  isLive(now = Date.now()) { return this.liveUntil > now || this.parWs().length > 0; }
  phoneSay(o) { const s = JSON.stringify(o); for (const w of this.phoneWs()) try { w.send(s); } catch {} }
  /** Last time the phone was heard: HTTP sync, or its socket's last message / keep-alive "ping" (a half-dead socket
   * stops pinging, so an open socket alone never means online). */
  seenNow(stored) {
    let t = Math.max(stored || 0, this.seen);
    for (const ws of this.phoneWs()) {
      const a = ws.deserializeAttachment() || {};
      let p = 0; try { p = +(this.ctx.getWebSocketAutoResponseTimestamp(ws) || 0); } catch {}
      t = Math.max(t, a.at || 0, p);
    }
    return t;
  }
  /** Ask the phone (WebSocket) to sync now: outgoing messages are free, the phone's sync costs 1/20 request. */
  kickPhone(now = Date.now()) {
    if (now - (this.kickedAt || 0) < 2000) return;
    this.kickedAt = now;
    for (const ws of this.phoneWs()) try { ws.send('{"t":"kick"}'); } catch {}
  }

  async fetch(req) {
    const url = new URL(req.url), action = url.pathname.split("/").pop(), m = req.method;
    const res = await this.handle(req);
    // parent changes the phone must see now: cmd / rules / live / device ask / a file chunk; a stale state on view
    if (res.status < 300 && action !== "sync" && action !== "ws" && ((m === "POST" && ["cmd", "live", "device", "logday", "file"].includes(action)) || (m === "PUT" && action === "rules")
        || (m === "GET" && action === "state" && Date.now() - (this.stateAt || 0) > 30e3))) this.kickPhone();
    return res;
  }

  async handle(req) {
    const url = new URL(req.url), action = url.pathname.split("/").pop(), id = url.pathname.split("/")[2];
    this.kidId = id;
    if (action === "_tg" && req.method === "POST" && req.headers.get("x-kg-internal") === "1") return this.tgIn(await req.json().catch(() => ({})), id);   // from tgHook only: the public route regex never matches `_tg`
    if (action === "_mint" && req.method === "POST" && req.headers.get("x-kg-internal") === "1") return this.pairMint(await req.json().catch(() => ({})));   // from pairRoute only
    const token = tokenOf(req, url);
    if (action === "join" && req.method === "POST" && !req.headers.get("x-kg-token")) return this.join(await req.json().catch(() => null), null, Date.now());   // with an invite
    if (action === "icon" && req.method === "GET" && ICON_KEY.test(url.searchParams.get("k") || "")) {
      if (!(await this.viewKeys("iconKey")).includes(url.searchParams.get("k"))) return json({ error: "not paired" }, 401);
      return this.icon(url);
    }
    if (action === "icons" && req.method === "GET" && ICON_KEY.test(url.searchParams.get("k") || "")) {
      if (!(await this.viewKeys("iconKey")).includes(url.searchParams.get("k"))) return json({ error: "not paired" }, 401);
      return this.iconsAll();
    }
    // map tile by a tile-only key (sha256("tile:" + token), from state?watch=1): Garmin's image request cannot send headers.
    // The key fetches OSM tiles and nothing else, so a leaked URL reveals no kid data.
    if (action === "tile" && req.method === "GET" && !req.headers.get("x-kg-token") && ICON_KEY.test(url.searchParams.get("k") || "")) {
      if (!(await this.viewKeys("tileKey")).includes(url.searchParams.get("k"))) return json({ error: "not paired" }, 401);
      return this.tile(req, url);
    }
    if (!/^[0-9a-f]{48}$/.test(token)) return json({ error: "not paired" }, 401);
    const h = await sha256(token);
    let bound = await this.s.get("tokenHash");
    if (!bound) {
      if (action !== "sync") {
        const g = await this.s.get("gone");
        if (g && g.p.includes(h)) return json({ error: "released", at: g.at }, 410);
        return json({ error: "waiting for the phone" }, 409);   // only the phone may bind
      }
      // ...and only with a real first sync (it always carries a state): a random POST must not claim / create storage
      let first = null; try { first = await req.clone().json(); } catch {}
      if (!first || typeof first.state !== "object" || !first.state) return json({ error: "bad first sync" }, 400);
      // a phone binds once; a script looping random ids is stopped by a per-network daily cap (Gate), not by a secret the phone lacks
      if (this.env.GATE) {
        const g = this.env.GATE.get(this.env.GATE.idFromName("ip:" + (netKey(req.headers.get("cf-connecting-ip")) || "?")));
        const r = await g.fetch("https://gate/take?max=" + (+this.env.BIND_PER_DAY || BIND_PER_DAY));
        if (r.status !== 200) return json({ error: "too many new pairings from this network, try tomorrow", retryAt: +(await r.text()) || Date.now() + 3600e3 }, 429);
      }
      await this.s.put({ tokenHash: h, born: Date.now() }); await this.s.delete("gone"); bound = h;
    }
    let role = "parent", who = "", me = null;
    const par = await this.parents(), now0 = Date.now();
    if (h !== bound) {
      if (par[h]) {   // a parent's own token
        me = par[h]; who = me.n;
        // a watch says what it is in X-KG-Name ("Zepp - Amazfit Bip Max", "Wear OS - Pixel Watch 2"; ASCII, it is a header): shown in the page's
        // watch list so two watches of one parent can be told apart
        const dv = me.k === "watch" ? (req.headers.get("x-kg-name") || "").replace(/[\u0000-\u001f]/g, "").slice(0, 40) : "";
        if (now0 - (me.seen || 0) > PAR_SEEN_EVERY || (dv && me.d !== dv)) { me.seen = now0; if (dv) me.d = dv; await this.s.put("par", par); }
        // a browser opens with the family PIN (12 h); a token the phone minted for a LAN page (`lk`) opens once with it
        // locked = unlock + GET parents (the page shows who it is) only: no invite, rename or revoke (audit 2026-11-20)
        if (parLocked(me, now0) && action !== "unlock" && !(action === "parents" && req.method === "GET"))
          return json({ error: "locked", name: me.n }, 423);
        if (me.k === "watch" && !watchOk(action, req.method)) return json({ error: "watch token: not allowed" }, 403);
      } else {
        const ai = await this.s.get("aiHash");
        if (!ai || h !== ai) return json({ error: "not paired" }, 401);
        if (!aiRoute(action, req.method)) return json({ error: "ai token: not allowed" }, 403);
        role = "ai"; who = "AI";
      }
    } else if (!phoneRoute(action, req.method) && (Object.keys(par).length || ((await this.s.get("pv")) || {}).h))
      return json({ error: "join" }, 403);   // the link alone opens nothing once a parent has a token or the phone sent its PIN (pv, 0.9.27+): join = name + PIN
    if (action === "join") return req.method === "POST" ? this.join(await req.json().catch(() => null), h === bound ? h : null, now0) : json({ error: "method" }, 405);
    if (action === "unlock" || action === "invite" || action === "parents") {
      if (!me && !(h === bound && action !== "unlock")) return json({ error: "not for this token" }, 403);
      return this.parentOp(action, req, h, me, now0);
    }
    if (action === "forget" && req.method === "POST") {   // the phone is released: delete everything of this id (the phone's token only)
      if (h !== bound) return json({ error: "phone only" }, 403);
      for (const w of this.phoneWs()) { try { w.close(1000, "forgotten"); } catch {} }
      const gp = [bound, ...Object.keys(par)];
      await this.s.deleteAlarm().catch(() => {}); await this.s.deleteAll();
      // tombstone: the parents' next call says "released" (410) instead of "waiting for the phone"; a new bind clears it
      await this.s.put("gone", { at: Date.now(), p: gp });
      this.seen = 0; this.stateAt = 0; this.stateMem = null; this.stateCore = null; this.stateDirty = false; this.ikSeen = null; this.kfcm = undefined; this.call = undefined; this.wk = null; this.par = null; this.vk = null;
      return json({ result: "forgotten" });
    }
    // the phone's own parent-token work (kid token only, docs/protocol.md "Parents"): mint = a LOCKED token for a LAN page
    // (opens with the family PIN, like a join), rekey = a new kid token on the same id (a LAN device was removed)
    if (action === "mint" || action === "rekey") {
      if (h !== bound) return json({ error: "phone only" }, 403);
      if (req.method !== "POST") return json({ error: "method" }, 405);
      const b = await req.json().catch(() => null);
      if (!isObj(b)) return json({ error: "bad json" }, 400);
      return action === "mint" ? this.kidMint(b, now0) : this.rekey(b, now0);
    }
    if (action === "watch") {   // a Zepp watch shows a 6-digit code; the parent page sends it here, authenticated like any parent call
      if (req.method !== "POST") return json({ error: "method" }, 405);
      if (!this.env.WATCH) return json({ error: "not available" }, 501);
      let b; try { b = await req.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!b || !/^\d{6}$/.test(String(b.code || ""))) return json({ error: "bad code" }, 400);
      const name = String(b.name || "").replace(/[\u0000-\u001f]/g, "").slice(0, 20);
      // the watch gets a parent token of its own (kind "watch": no browser PIN), named `wname` (the page's name for the watch,
      // shown in the parents / watch list); `name` is the kid name the watch shows. Pages before 2026-11-19 send only `name`.
      const wname = String(b.wname || "").replace(/[\u0000-\u001f]/g, "").slice(0, 30);
      const wt = await this.mint(wname || name || "Watch", "watch", Date.now());
      if (!wt) return json({ error: "too many parents" }, 409);
      const r = await this.env.WATCH.get(this.env.WATCH.idFromName("w:" + b.code)).fetch("https://w/attach", { method: "POST", body: JSON.stringify({ url: url.origin, id, token: wt, name }) });
      if (r.status !== 200) { delete (await this.parents())[await sha256(wt)]; await this.s.put("par", this.par); }
      return json(r.status === 200 ? { ok: true } : { error: r.status === 409 ? "code already used" : "no such code" }, r.status === 200 ? 200 : r.status === 409 ? 409 : 404);
    }
    if (role === "ai" && action === "tile") return json({ error: "ai token: not allowed" }, 403);
    if (action === "tile") return req.method === "GET" ? this.tile(req, url) : json({ error: "method" }, 405);
    // icon / tile keys: one per parent token (each page derives its own from its token)
    if (role === "parent" && !(this.ikSeen || (this.ikSeen = new Set())).has(h)) {
      this.ikSeen.add(h);
      const [ik, tk] = await Promise.all([sha256("icon:" + token), sha256("tile:" + token)]);
      const [oi, ot] = await Promise.all([this.viewKeys("iconKey"), this.viewKeys("tileKey")]);
      if (!oi.includes(ik) || !ot.includes(tk)) {
        this.vk.iconKey = [...oi.filter(k => k !== ik), ik].slice(-MAX_PARENTS - 2); this.vk.tileKey = [...ot.filter(k => k !== tk), tk].slice(-MAX_PARENTS - 2);
        await this.s.put({ iconKeys: this.vk.iconKey, tileKeys: this.vk.tileKey });
      }
    }
    const now = Date.now();
    if (action === "ws") {   // the phone's push channel (Relay.kt RelayWs): {"t":"sync",...} = POST sync, server sends {"t":"kick"}
      if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") return json({ error: "websocket only" }, 426);
      for (const old of this.phoneWs()) try { old.close(1000, "replaced"); } catch {}
      const [client, server] = Object.values(new WebSocketPair());
      this.ctx.acceptWebSocket(server, ["phone"]);
      server.serializeAttachment({ id, nk: netKey(req.headers.get("cf-connecting-ip")), at: now });
      // "ping" answered by the runtime without waking the object (not billed): the phone's keep-alive
      this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
      return new Response(null, { status: 101, webSocket: client });
    }
    if (action === "icons" && req.method === "POST") {   // phone: {pkg: base64 webp}, each app once
      let o; try { o = await req.json(); } catch { return json({ error: "bad json" }, 400); }
      if (!o || typeof o !== "object" || Array.isArray(o)) return json({ error: "bad json" }, 400);
      const list = new Set((await this.s.get("iconList")) || []), puts = {};
      // full (years of installs and removals): icons of apps no longer on the phone make room, else a new app never gets one
      if (list.size + Object.keys(o).length > MAX_ICONS) {
        const st = await this.getK("state"), here = new Set((st && Array.isArray(st.apps) ? st.apps : []).map(a => a && a.pkg));
        const gone = here.size ? [...list].filter(k => !here.has(k)) : [];
        for (let i = 0; i < gone.length; i += 128) await this.s.delete(gone.slice(i, i + 128).map(k => "icon:" + k));
        gone.forEach(k => list.delete(k));
      }
      for (const [pkg, b64] of Object.entries(o)) {
        if (!PKG.test(pkg) || typeof b64 !== "string" || b64.length > 16000 || !/^[A-Za-z0-9+/=]+$/.test(b64)) continue;
        if (!list.has(pkg) && list.size >= MAX_ICONS) continue;
        list.add(pkg); puts["icon:" + pkg] = b64;
      }
      puts.iconList = [...list]; puts.iconV = Date.now();   // the page's /icons?v= URL changes, so its year-long cache stays right
      await this.s.put(puts);
      return json({ have: list.size });
    }
    if (action === "icon" && req.method === "GET") return this.icon(url);   // older pages: the token in ?k=
    if (action === "call") return this.callRoute(req, url, h === bound, who, now);
    if (action === "frame") return this.rFrame({ req, now });
    if (action === "scr") {   // the parent's screen-view socket (0.9.61): frames pushed as they come (outgoing = free), inputs in
      if (role !== "parent") return json({ error: "parents only" }, 403);   // h === bound: the shared link token (no parent joined yet)
      if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") return json({ error: "websocket only" }, 426);
      const [client, server] = Object.values(new WebSocketPair());
      this.ctx.acceptWebSocket(server, ["parent"]);
      server.serializeAttachment({ par: 1, at: now });
      this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
      this.phoneSay({ t: "live", live: true });   // a phone already streaming keeps going (isLive counts this socket)
      this.kickPhone(now);                        // else its sync reply says live -> it starts
      const f = this.frame;
      if (f && f.raw && now - f.at < 5000) try { server.send(f.raw); } catch {}
      return new Response(null, { status: 101, webSocket: client, headers: { "sec-websocket-protocol": "kg" } });
    }
    if (action === "file") return this.file(req, url, now);
    let body = null;
    if (req.method !== "GET") {
      const buf = await req.arrayBuffer();
      if (buf.byteLength > limitFor(action)) return json({ error: "too big" }, 413);
      body = (() => { try { return JSON.parse(new TextDecoder().decode(buf)); } catch { return null; } })();
    }
    if (req.method !== "GET" && !isObj(body)) return json({ error: "bad json" }, 400);
    const c = { req, url, body, now, role, who, token, id, watch: !!me && me.k === "watch" };

    const r = ROUTES[action + " " + req.method] || ROUTES[action];   // routes after the JSON body (Kid.rX methods below)
    if (r) return this[r](c);

    return json({ error: "method not allowed" }, 405);
  }

  async rFrame({ req, url, body, now, role, who, token, id }) {
    if (req.method === "POST") {
      const buf = await req.arrayBuffer();
      if (buf.byteLength > MAX_FRAME) return json({ error: "too big" }, 413);
      const err = (req.headers.get("x-kg-err") || "").slice(0, 60);
      const jpeg = buf.byteLength ? buf : null;
      this.frame = { seq: ++this.seq, jpeg, w: +req.headers.get("x-kg-w") || 0, h: +req.headers.get("x-kg-h") || 0, at: now, err: jpeg ? "" : err || "no frame" };
      for (const w of this.waiters) w();
      const pars = this.parWs();   // a page on the screen socket and a phone on HTTP (older app / no socket): same message as phoneFrame
      if (pars.length) {
        const f = this.frame, raw = wsFrame(f.w, f.h, f.err, jpeg);
        if (jpeg) f.raw = raw;
        for (const w of pars) try { w.send(raw); } catch {}
      }
      const inputs = this.inputs.filter(i => now - i.at <= 5000).map(({ at, ...i }) => i); this.inputs = [];
      return json({ inputs, live: this.isLive(now) });
    }
    if (req.method === "GET") {
      const after = +new URL(req.url).searchParams.get("after") || 0;
      this.liveUntil = Math.max(this.liveUntil, now + 20e3);
      if (!(this.frame && this.frame.jpeg && this.frame.seq > after)) {
        await new Promise(res => {
          const done = () => { clearTimeout(t); this.waiters.delete(done); res(); };
          const t = setTimeout(done, 5000);
          this.waiters.add(done);
        });
      }
      const f = this.frame;
      if (f && f.jpeg && f.seq > after) {
        return new Response(f.jpeg, { headers: {
          "content-type": "image/jpeg", "cache-control": "no-store", "x-content-type-options": "nosniff",
          "x-kg-seq": String(f.seq), "x-kg-w": String(f.w), "x-kg-h": String(f.h), "x-kg-age": String(Date.now() - f.at),
        } });
      }
      const hd = { "cache-control": "no-store" };
      if (f && f.err) hd["x-kg-err"] = f.err;
      return new Response(null, { status: 204, headers: hd });
    }
    return json({ error: "method not allowed" }, 405);
  }

  // device health ("Máy" view): parent POST {} asks (the next sync reply says "device": true) and gets the last one
  // stored; the phone PUTs the snapshot. GET only reads. Stored (<= 32 KB) so the page shows the last one at once.
  async rDevice({ req, url, body, now, role, who, token, id }) {
    if (req.method === "PUT") {
      if (!isObj(body) || JSON.stringify(body).length > MAX_DEVICE) return json({ error: "too big" }, 413);
      await this.s.put("device", { ...body, at: now });
      this.deviceWant = 0;
      return json({ ok: true });
    }
    if (req.method === "POST") this.deviceWant = now;
    else if (req.method !== "GET") return json({ error: "method not allowed" }, 405);
    const d = (await this.s.get("device")) || null;
    return json({ device: d, asked: this.deviceWant, seen: this.seenNow() });
  }

  // "Nhật ký" history, one day: parent POST {day} asks (the next sync reply says "logday": day) and gets the last stored
  // copy of that day; the phone PUTs {day, days, ev, lg, cut} (<= 100 KB). The last KEEP_LOGDAYS days stay stored.
  async rLogday({ req, url, body, now, role, who, token, id }) {
    const dayOk = d => typeof d === "string" && /^\d{4}-\d\d-\d\d$/.test(d);
    if (req.method === "PUT") {
      if (!isObj(body) || !dayOk(body.day)) return json({ error: "bad day" }, 400);
      if (JSON.stringify(body).length > MAX_LOGDAY) return json({ error: "too big" }, 413);
      const idx = ((await this.s.get("lgdIdx")) || []).filter(d => d !== body.day); idx.push(body.day);
      const drop = idx.splice(0, Math.max(0, idx.length - KEEP_LOGDAYS));
      await this.s.put({ ["lgd:" + body.day]: { ...body, at: now }, lgdIdx: idx });
      if (drop.length) await this.s.delete(drop.map(d => "lgd:" + d));
      this.lgdWant.delete(body.day);
      return json({ ok: true });
    }
    const day = req.method === "POST" ? body.day : url.searchParams.get("day");
    if (!dayOk(day)) return json({ error: "bad day" }, 400);
    if (req.method === "POST") { this.lgdWant.set(day, now); if (this.lgdWant.size > 8) this.lgdWant.delete(this.lgdWant.keys().next().value); }
    else if (req.method !== "GET") return json({ error: "method not allowed" }, 405);
    return json({ logday: (await this.s.get("lgd:" + day)) || null, asked: this.lgdWant.get(day) || 0, seen: this.seenNow() });
  }

  async rLive({ req, url, body, now, role, who, token, id }) {
    if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
    this.liveUntil = body && body.stop === true ? 0 : now + 20e3;   // stop: the phone's next frame upload gets live:false and it ends at once
    this.phoneSay({ t: "live", live: this.isLive(now) });   // a phone sending frames on its socket keeps going / stops now
    return json({ ok: true, phone: this.frame ? now - this.frame.at : -1 });
  }

  async rInput({ req, url, body, now, role, who, token, id }) {
    if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
    const o = inputOf(body, now);
    if (!o) return json({ error: "bad input" }, 400);
    if (!this.isLive(now)) return json({ error: "not live" }, 409);
    if (this.inputs.length >= 50) return json({ error: "queue full" }, 429);
    this.queueInput(o);
    return json({ result: "queued" });
  }
  // a phone on its socket gets the input at once; else with its next frame upload's reply
  queueInput(o) {
    if (this.phoneWs().length && this.wsFrames) { const { at, ...i } = o; this.phoneSay({ t: "in", inputs: [i] }); }
    else this.inputs.push(o);
  }

  async rSync({ req, url, body, now, role, who, token, id }) {
    const out = await this.sync(body, id, netKey(req.headers.get("cf-connecting-ip")), now);
    return out.error ? json({ error: out.error }, out.status) : json(out);
  }

  // /go: where the phone is reachable now
  async rWhere({ req, url, body, now, role, who, token, id }) {
    const [state, seen, ipk] = await Promise.all(["state", "seen", "ipk"].map(k => this.getK(k)));
    const nk = netKey(req.headers.get("cf-connecting-ip"));
    const same = !!nk && (ipk || []).includes(await sha256("net:" + nk));
    const ts = state && state.ts && typeof state.ts.ip === "string" && /^100\.[\d.]+$/.test(state.ts.ip) ? state.ts.ip : "";
    const lan = state && typeof state.lan === "string" && /^http:\/\/[\d.]+:\d+$/.test(state.lan) ? state.lan : "";
    return json({ seen: this.seenNow(seen), lan, ts, same, mode: state && state.mode || "" });
  }

  async rState({ req, url, body, now, role, who, token, id, watch }) {
    const [state, rules, seen, cmds, iconV] = await Promise.all(["state", "rules", "seen", "cmds", "iconV"].map(k => this.getK(k)));
    if (role === "parent") {   // a parent is looking at a quiet phone: wake it (rate-limited in wakeKid)
      const heard = this.seenNow(seen), q = Date.now() - heard;
      if (heard && q >= +(this.env.WAKE_PARENT_MS || WAKE_PARENT) && q < WAKE_DAYS) this.ctx.waitUntil(this.wakeKid(Date.now(), heard, "parent").catch(() => {}));
    }
    const st = state ? { ...state, rules: stripPin(state.rules) } : null;
    // relayOk = last time the relay heard the phone (the phone no longer re-sends it each sync: that alone made every tick a sync)
    if (st && st.relayOk !== undefined) st.relayOk = Math.max(+st.relayOk || 0, this.seenNow(seen));
    if (st) st.iconV = iconV || 1;   // GET /icons?v= (one request for every icon)
    const rev = await this.s.get("secEv");   // the worker's own security notices (secAlert), shown with the phone's events
    if (st && rev && rev.length) st.events = [...(Array.isArray(st.events) ? st.events : []), ...rev].sort((a, b) => (+a.t || 0) - (+b.t || 0));
    // GET state?nf=<cursor> (parent app, after an FCM "nf"): only the kid-notification rows newer than its cursor (2-5 KB, not the whole state)
    if (role === "parent" && !watch && url.searchParams.has("nf")) return json(nfRead(st, +url.searchParams.get("nf") || 0));
    // a watch token always gets the watch view (2026-11-20): no notifications, call log, route, files...
    if (role === "parent" && (watch || url.searchParams.get("watch") === "1")) return json({ ...watchState(st, rules ? stripPin(rules) : null), seen: this.seenNow(seen), pending: (cmds || []).length,
      queue: (cmds || []).filter(c => live(c, Date.now())).slice(-10).map(c => ({ id: c.id, type: c.type, at: c.at, ...(c.by ? { by: c.by } : {}) })),
      expired: ((await this.s.get("expired")) || []).slice(-10), tileKey: await sha256("tile:" + token) });
    return json({ state: role === "ai" ? aiState(st) : st, rules: rules ? stripPin(rules) : null, seen: this.seenNow(seen), pending: (cmds || []).length,
      queue: (cmds || []).filter(c => live(c, Date.now())).slice(-20).map(c => ({ id: c.id, type: c.type, at: c.at, ...(c.by ? { by: c.by } : {}) })), expired: (await this.s.get("expired")) || [] });
  }

  async rRules({ req, url, body, now, role, who, token, id }) {
    const [state, old] = await Promise.all([this.getK("state"), this.s.get("rules")]);
    const rev = Math.max(Date.now(), (old && old.rev || 0) + 1, (state && state.rules && state.rules.rev || 0) + 1);
    if (role === "ai") {   // never the PIN or a protection switch: those keep the stored value (the newer of the queued rules and the phone's)
      const cur = (old && state && state.rules ? ((old.rev || 0) >= (state.rules.rev || 0) ? old : state.rules) : old || (state && state.rules)) || {};
      for (const k of AI_LOCKED) { delete body[k]; if (!/^pin/.test(k) && k in cur) body[k] = cur[k]; }
    }
    // the sync reply carries rules + queued commands and the phone refuses replies over 256 KB: keep both small
    if (JSON.stringify(body).length > MAX_RULES) return json({ error: "too big" }, 413);
    const rules = { ...body, rev };
    if (who) rules.by = who; else delete rules.by;   // who changed them (the phone logs it)
    // a new PIN arrives in plain: keep only the hash the phone uses (sha256 of "kg:" + PIN). No PIN given: keep the old hash.
    const pin = String(rules.pin || "").replace(/\D/g, "");
    // pinAt: when the hash last changed, so the phone takes it once and a newer PIN set on LAN is not reverted
    // salted like the phone (Rules.hashPin, 10 000 rounds): an unsalted sha256 of a 4-6 digit PIN is read back in a blink
    if (pin.length >= 4) { const ps = hexRand(8); rules.pinHash = hashPin(pin, ps); rules.pinSalt = ps; rules.pinAt = rev;
      // checks joins at once; "pvWeb" when the phone never sent pv (old phone), so a PIN set from the shared link does not lock that link
      const pv0 = await this.s.get("pv");
      if (pv0 && pv0.h) await this.s.put({ pv: { h: rules.pinHash, s: ps }, pvPrev: pv0.h });   // the phone proves its next pv with the old one
      else await this.s.put("pvWeb", { h: rules.pinHash, s: ps }); }
    else if (typeof rules.pinHash === "string" && /^[0-9a-f]{64}$/.test(rules.pinHash)) { rules.pinAt = rev; if (!/^[0-9a-f]{16}$/.test(String(rules.pinSalt || ""))) delete rules.pinSalt; }
    else if (old && old.pinHash) { rules.pinHash = old.pinHash; rules.pinAt = old.pinAt || 1; if (old.pinSalt) rules.pinSalt = old.pinSalt; else delete rules.pinSalt; }
    else if (old && old.pinSet) { rules.pinAt = old.pinAt || 1; }   // hash already taken by the phone and dropped here
    else { delete rules.pinHash; delete rules.pinAt; }
    if (!rules.pinHash) delete rules.pinSalt;
    const keepSet = !rules.pinHash && !!(old && old.pinSet);
    delete rules.pin; delete rules.pinSet;
    if (keepSet) rules.pinSet = true;
    await this.s.put("rules", rules);
    // "Resume all" only PUTs rules (pausedUntil 0): a `lock` still queued for an offline phone must not fire after it
    const nowMs = Date.now();
    if (!(+rules.pausedUntil > nowMs) && ((old && +old.pausedUntil > nowMs) || (state && state.rules && +state.rules.pausedUntil > nowMs))) {
      const q = (await this.s.get("cmds")) || [], keep = q.filter(c => c.type !== "lk");
      if (keep.length !== q.length) await this.s.put("cmds", keep);
    }
    return json(stripPin(rules));
  }

  // parent app: {fcm: token} registers, {fcm, off: true} removes; nf: true / false = also wants / no longer wants the
  // kid-notification nudge (FCM kind "nf", own list `pushNf`); nf absent (older app) leaves that list alone
  async rPush({ req, url, body, now, role, who, token, id }) {
    const t = typeof body.fcm === "string" ? body.fcm : "";
    if (!FCM_TOKEN.test(t)) return json({ error: "bad token" }, 400);
    const list = ((await this.s.get("push")) || []).filter(x => x !== t);
    if (!body.off) { list.push(t); while (list.length > MAX_PUSH) list.shift(); }
    await this.s.put("push", list);
    if (body.off || typeof body.nf === "boolean") {
      const nf = ((await this.s.get("pushNf")) || []).filter(x => x !== t && list.includes(x));
      if (!body.off && body.nf) nf.push(t);
      await this.s.put("pushNf", nf);
    }
    return json({ ok: true, enabled: !!this.env.FCM_SA, devices: list.length });
  }

  // Telegram alerts: GET status; POST {name, lang} -> one-time link; POST {off: true} unlinks
  async rTg({ req, url, body, now, role, who, token, id }) {
    if (!this.env.TG_BOT) return json({ enabled: false });
    const tg = (await this.s.get("tg")) || null;
    if (req.method === "GET") return json({ enabled: true, linked: !!(tg && tg.chat) });
    if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (body.off) { await this.s.delete(["tg", "tgCode"]); return json({ enabled: true, linked: false }); }
    const bot = await tgBotName(this.env);
    if (!bot) return json({ error: "telegram unreachable" }, 502);
    const code = id + [...crypto.getRandomValues(new Uint8Array(8))].map(b => b.toString(16).padStart(2, "0")).join("");
    const name = String(body.name || "").replace(/[\u0000-\u001f<>]/g, "").slice(0, 30), lang = body.lang === "en" ? "en" : "vi";
    await this.s.put("tgCode", { code, exp: now + 15 * 60e3, name, lang });
    return json({ enabled: true, linked: !!(tg && tg.chat), link: `https://t.me/${bot}?start=${code}` });
  }

  async rCmd({ req, url, body, now, role, who, token, id }) {
    const type = String(body.type || ""); let cut = null;
    if (role === "ai" && !AI_CMDS.includes(type)) return json({ error: "ai token: not allowed" }, 403);
    if (!CMDS.includes(type)) return json({ error: "bad command" }, 400);
    if (type === "ai") {   // the page makes the token; here only the shape, and a revoke takes effect at once (the phone follows)
      if (!(body.op === "revoke" || (body.op === "set" && /^[0-9a-f]{64}$/.test(String(body.hash || ""))))) return json({ error: "bad command" }, 400);
      // revoke AND a new code: the old code dies here at once; the new one works after the phone took it (its next sync
      // brings the hash; a wrong PIN = the phone keeps the old one and its sync puts it back)
      // the phone's syncs until it ran this command still carry the old hash: ignore that one until it acks (aiCut)
      cut = (await this.s.get("aiHash")) || ((await this.s.get("aiCut")) || {}).h || "";   // a cut still waiting keeps its hash
      await this.s.delete("aiHash"); this.aiSeen = undefined;
    }
    if (type === "cl" && !/^[A-Za-z0-9._]{1,200}$/.test(String(body.pkg || ""))) return json({ error: "bad pkg" }, 400);
    if (type === "sn" && !(/^[0-9a-f]{16}$/.test(String(body.fid || "")) && ["front", "back"].includes(body.cam) && ["off", "on", "auto"].includes(body.flash) && ["small", "medium"].includes(body.size) && Number.isInteger(body.delay) && body.delay >= 0 && body.delay <= 5)) return json({ error: "bad command" }, 400);
    if (type === "sp" && !(/^[0-9a-f]{16}$/.test(String(body.fid || "")) && Number.isFinite(body.before || 0) && Number.isFinite(body.n || 0))) return json({ error: "bad command" }, 400);
    // bulk deletes: {ids:[<=200 x 16 hex]} (chatFileDel / chatDel / snapDel; chatFileDel also the old single {id}); fileDel {files:[...]} or the old {file}
    const idsOk = (a) => Array.isArray(a) && a.length >= 1 && a.length <= 200 && a.every(x => /^[0-9a-f]{16}$/.test(String(x)));
    if (type === "cf" && !(body.ids !== undefined ? idsOk(body.ids) : /^[0-9a-f]{16}$/.test(String(body.id || "")))) return json({ error: "bad command" }, 400);
    if ((type === "cd" || type === "sd") && !idsOk(body.ids)) return json({ error: "bad command" }, 400);
    if (type === "fd" && body.files !== undefined && !idsOk(body.files)) return json({ error: "bad command" }, 400);
    if (type === "su" && !(/^[0-9a-f]{16}$/.test(String(body.id || "")) && (body.thumb === undefined || body.thumb === 1))) return json({ error: "bad command" }, 400);
    if (type === "sl" && !(/^[0-9a-f]{16}$/.test(String(body.fid || "")) && Number.isFinite(body.before || 0) && Number.isFinite(body.n || 0))) return json({ error: "bad command" }, 400);
    if (type === "cp" && !(/^[0-9a-f]{16}$/.test(String(body.fid || "")) && Number.isFinite(body.before || 0) && Number.isFinite(body.n || 0))) return json({ error: "bad command" }, 400);
    if (type === "np" && !(/^[0-9a-f]{16}$/.test(String(body.fid || "")) && Number.isFinite(body.before || 0) && Number.isFinite(body.n || 0))) return json({ error: "bad command" }, 400);
    // PIN-gated commands (protocol "PIN-gated commands"): the phone checks the PIN; here only the shape
    if (body.pin !== undefined && !/^\d{0,8}$/.test(String(body.pin))) return json({ error: "bad pin" }, 400);
    if ((type === "ps" || type === "pr") && !/^\d{4,8}$/.test(String(body.new || ""))) return json({ error: "bad pin" }, 400);
    if (type === "st" && !(SETTING_KEYS.includes(body.key) && (typeof body.value === "boolean" || Number.isFinite(body.value)))) return json({ error: "bad setting" }, 400);
    // expired commands are only dropped on a phone sync: don't let them fill the queue
    const stored = (await this.s.get("cmds")) || [], cmds = stored.filter(c => live(c, now));
    if (cmds.length !== stored.length) await this.s.put("expired", expiredOf(await this.s.get("expired"), stored.filter(c => !live(c, now)), now));
    // full queue: a new command first replaces a still-queued older one of the same kind (same type + same target field), never
    // one already handed out and never a destructive (ONCE) one; only when still full: refuse (the oldest may be a pending release)
    // fileUp: an older still-queued copy for the same file is dropped at once: a phone that was asleep would otherwise
    // upload the same file once per copy (up to 50 MB of mobile data each, review 2026-11-18)
    if (type === "fu") { const i = cmds.findIndex(o => o.type === "fu" && !o.sent && o.file === body.file && !!o.thumb === !!body.thumb); if (i >= 0) cmds.splice(i, 1); }
    if (cmds.length >= MAX_CMDS && type in REPLACE && !ONCE.includes(type)) {
      const f = REPLACE[type], i = cmds.findIndex(o => o.type === type && !o.sent && (!f || o[f] === body[f]));
      if (i >= 0) cmds.splice(i, 1);
    }
    if (cmds.length >= MAX_CMDS) return json({ error: "queue full" }, 429);
    const c = { ...body, type, id: typeof body.id === "string" && /^[\w.-]{1,64}$/.test(body.id) ? body.id : crypto.randomUUID(), at: now };
    delete c.sent;
    // `answer`: the request id may not be a legal command id; keep it apart so the phone never answers the wrong request
    if (type === "an") c.req = String(body.req || body.id || "").slice(0, 200);
    if (who) c.by = who; else delete c.by;   // the phone logs who sent it (docs/protocol.md "Parents")
    if (JSON.stringify(c).length > MAX_CMD) return json({ error: "too big" }, 413);
    cmds.push(c);
    await this.s.put("cmds", cmds);
    if (cut !== null) await this.s.put("aiCut", { h: cut, id: c.id });
    return json({ result: "queued" });
  }

  /** Parent tokens: {sha256(token): {n name, k app|web|watch, at, seen, ul (web: unlocked until)}} (docs/protocol.md "Parents"). */
  async parents() { if (!this.par) this.par = (await this.s.get("par")) || {}; return this.par; }
  /** Icon / tile keys of every parent token ("iconKey" | "tileKey"; the single key of older versions still counts). */
  async viewKeys(k) {
    if (!this.vk) {
      const [ik, tk, iks, tks] = await Promise.all(["iconKey", "tileKey", "iconKeys", "tileKeys"].map(x => this.s.get(x)));
      this.vk = { iconKey: [...(ik ? [ik] : []), ...(iks || [])], tileKey: [...(tk ? [tk] : []), ...(tks || [])] };
    }
    return this.vk[k];
  }
  /** A new parent token, or null when MAX_PARENTS have one. */
  async mint(name, kind, now, extra = null) {
    const par = await this.parents();
    if (Object.keys(par).length >= MAX_PARENTS) return null;
    const token = hexRand(24);
    par[await sha256(token)] = { n: cleanName(name) || "?", k: kind, at: now, seen: now, ...(kind === "web" && !extra ? { ul: now + UNLOCK_MS } : {}), ...(extra || {}) };
    await this.s.put("par", par);
    return token;
  }
  /** The family PIN against the phone's hash (sync `pv`) or one the page set here: {r: ok|bad|locked|none, left?, retryAt?}. */
  /** {h, s} to check the family PIN with: the phone's (sync `pv`) or one the page set in a rules PUT; null = none known. */
  async pinVerifier() {
    const [pv, pw, rules] = await Promise.all(["pv", "pvWeb", "rules"].map(k => this.s.get(k)));
    return pv && pv.h ? pv : pw && pw.h ? pw : rules && /^[0-9a-f]{64}$/.test(String(rules.pinHash || "")) ? { h: rules.pinHash, s: String(rules.pinSalt || "") } : null;
  }
  /** [key] = who is trying (audit 2026-11-20): a browser token's own hash, "km" for every phone-minted one, "join" for the
   *  phone's link; so a child guessing with the link can't lock a parent's browser out. 5 misses = 15 min, doubling, 24 h max. */
  async pinCheck(pin, now, key) {
    const [v, tr0] = await Promise.all([this.pinVerifier(), this.s.get("pinTry")]);
    if (!v) return { r: "none" };
    const tr = isObj(tr0) && typeof tr0.n !== "number" ? tr0 : {};   // older shape {n, until} (one per kid) is dropped
    const e = tr[key] || { n: 0, lv: 0, until: 0 };
    if (e.until > now) return { r: "locked", retryAt: e.until };
    const p = String(pin || "").replace(/\D/g, "").slice(0, 12);
    if (p.length >= 4 && hashPin(p, v.s) === v.h) { if (tr[key] || (tr0 && tr0 !== tr)) { delete tr[key]; await this.s.put("pinTry", tr); } return { r: "ok" }; }
    e.n++; e.at = now;
    if (e.n >= PIN_TRIES) { e.until = now + Math.min(PIN_LOCK * 2 ** e.lv, PIN_LOCK_MAX); e.lv++; e.n = 0; }
    tr[key] = e;
    const ks = Object.keys(tr);
    if (ks.length > PIN_KEYS) ks.sort((a, b) => (tr[a].at || 0) - (tr[b].at || 0)).slice(0, ks.length - PIN_KEYS).forEach(k => { if (k !== key) delete tr[k]; });
    await this.s.put("pinTry", tr);
    return e.until > now ? { r: "locked", retryAt: e.until } : { r: "bad", left: PIN_TRIES - e.n };
  }
  /** POST join {name, kind, pin} with the phone's token (the link) or {name, kind, inv} with no token -> {token, name, kind, pin: ok|none|inv}. */
  async join(b, shared, now) {
    if (!isObj(b)) return json({ error: "bad json" }, 400);
    const name = cleanName(b.name), kind = PAR_KINDS.includes(b.kind) && b.kind !== "watch" ? b.kind : "web";
    if (!name) return json({ error: "name needed" }, 400);
    const par = await this.parents();
    let how;
    if (!shared) {
      if (!(await this.s.get("tokenHash"))) return json({ error: "not paired" }, 401);
      const invs = ((await this.s.get("invs")) || []).filter(x => x.exp > now), ih = await sha256("inv:" + String(b.inv || ""));
      const i = invs.findIndex(x => x.h === ih);
      if (i < 0) return json({ error: "invite used or expired" }, 403);
      invs.splice(i, 1); await this.s.put("invs", invs);   // one use: the first one to come wins (the object runs one request at a time)
      how = "inv";
    } else {
      const c = await this.pinCheck(b.pin, now, "join");
      if (c.r === "locked") return json({ error: "pin locked", retryAt: c.retryAt }, 429);
      if (c.r === "bad") return json({ error: "bad pin", left: c.left }, 403);
      // no PIN known here (an old phone that does not send its hash, no PIN yet): only the first parent, by the link alone
      if (c.r === "none" && Object.keys(par).length) return json({ error: "invite needed" }, 403);
      how = c.r;
    }
    const token = await this.mint(name, kind, now);
    if (!token) return json({ error: "too many parents" }, 409);
    return json({ token, name, kind, pin: how });
  }
  /** unlock {pin} (a browser token) | invite (-> {inv, exp}) | GET parents | POST parents {revoke: id} | {name}. */
  async parentOp(action, req, h, me, now) {
    const b = req.method === "POST" ? await req.json().catch(() => null) : null;
    if (req.method === "POST" && !isObj(b)) return json({ error: "bad json" }, 400);
    if (action === "unlock") {
      if (req.method !== "POST") return json({ error: "method" }, 405);
      const c = await this.pinCheck(b.pin, now, me.km ? "km" : h.slice(0, 12));
      if (c.r === "locked") return json({ error: "pin locked", retryAt: c.retryAt }, 429);
      if (c.r === "bad") return json({ error: "bad pin", left: c.left }, 403);
      me.ul = now + UNLOCK_MS; me.seen = now; delete me.lk; await this.s.put("par", this.par);
      return json({ until: me.ul, pin: c.r });
    }
    if (action === "invite") {
      if (req.method !== "POST") return json({ error: "method" }, 405);
      const inv = hexRand(16), invs = ((await this.s.get("invs")) || []).filter(x => x.exp > now).slice(-(MAX_INVS - 1));
      invs.push({ h: await sha256("inv:" + inv), exp: now + INV_TTL, by: me ? me.n : "" });
      await this.s.put("invs", invs);
      return json({ inv, exp: now + INV_TTL });
    }
    const par = await this.parents();
    if (req.method === "POST") {
      if (typeof b.revoke === "string") {
        const k = Object.keys(par).find(x => x.slice(0, 12) === b.revoke);
        if (!k) return json({ error: "no such parent" }, 404);
        delete par[k];
      } else if (typeof b.rename === "string") {   // a watch can't name itself: any parent renames it (people rename themselves)
        const k = Object.keys(par).find(x => x.slice(0, 12) === b.rename);
        if (!k) return json({ error: "no such parent" }, 404);
        if (par[k].k !== "watch") return json({ error: "only a watch" }, 403);
        if (!cleanName(b.name)) return json({ error: "bad json" }, 400);
        par[k].n = cleanName(b.name);
      } else if (me && cleanName(b.name)) me.n = cleanName(b.name);
      else return json({ error: "bad json" }, 400);
      await this.s.put("par", par);
    } else if (req.method !== "GET") return json({ error: "method" }, 405);
    return json({ parents: Object.entries(par).map(([k, p]) => ({ id: k.slice(0, 12), name: p.n, kind: p.k, at: p.at, seen: p.seen, me: k === h, ...(parLocked(p, now) ? { locked: true } : {}), ...(p.lk ? { lk: true } : {}), ...(p.km ? { lan: true } : {}), ...(p.d ? { dev: p.d } : {}) })),
      pinKnown: !!(await this.pinVerifier()) });
  }
  /** Drops one parent row (revoke, rekey `drop`, a phone-minted token replaced). */
  async dropParent(k) {
    const par = await this.parents();
    delete par[k];
  }
  /** POST mint {name, kind, drop?} (kid token): a parent token for a LAN page that asked the phone (`relay {mint:true}`).
   * It is LOCKED (`lk`, 423) until a family PIN unlock: the kid token alone never becomes a parent. `drop` = the id this
   * phone minted for the same LAN device before (only phone-minted rows can be dropped this way). */
  async kidMint(b, now) {
    const par = await this.parents();
    if (typeof b.drop === "string" && /^[0-9a-f]{12}$/.test(b.drop)) {
      const k = Object.keys(par).find(x => x.slice(0, 12) === b.drop && par[x].km);
      if (k) await this.dropParent(k);
    }
    const pend = Object.keys(par).filter(k => par[k].km && par[k].lk).sort((a, c) => par[a].at - par[c].at);
    while (pend.length >= KM_PENDING) await this.dropParent(pend.shift());
    const token = await this.mint(b.name || "LAN", b.kind === "app" ? "app" : "web", now, { km: 1, lk: 1 });
    if (!token) { await this.s.put("par", par); return json({ error: "too many parents" }, 409); }
    return json({ token, id: (await sha256(token)).slice(0, 12) });
  }
  /** POST rekey {token, drop?: [id]} (kid token): the phone's new token on the same id (a LAN device was removed; it may
   * hold the old one). Parents keep their own tokens; `drop` = phone-minted rows of the removed devices. */
  async rekey(b, now) {
    const t = String(b.token || "");
    if (!/^[0-9a-f]{48}$/.test(t)) return json({ error: "bad token" }, 400);
    const nh = await sha256(t), par = await this.parents();
    if (nh === (await this.s.get("tokenHash"))) return json({ ok: true });   // asked again with the new token (the first reply was lost)
    if (par[nh]) return json({ error: "bad token" }, 400);
    for (const id of Array.isArray(b.drop) ? b.drop.slice(0, MAX_PARENTS) : []) {
      const k = Object.keys(par).find(x => x.slice(0, 12) === id && par[x].km);
      if (k) await this.dropParent(k);
    }
    await this.s.put({ tokenHash: nh, par });
    for (const w of this.phoneWs()) { try { w.close(1000, "rekeyed"); } catch {} }   // the old token's socket; the phone reconnects with the new one
    return json({ ok: true });
  }
  /** Remote pairing done (pairRoute /result): the phone's token proves it; the page gets a parent token named as typed. */
  async pairMint(b) {
    if (!isObj(b) || !/^[0-9a-f]{48}$/.test(String(b.token || ""))) return json({ error: "bad json" }, 400);
    const h = await sha256(b.token), bound = await this.s.get("tokenHash");
    if (bound && bound !== h) return json({ error: "not paired" }, 401);
    if (!bound) await this.s.put({ tokenHash: h, born: Date.now() });   // the phone's first sync may come after the parent's result
    const token = await this.mint(b.name || "?", b.kind === "app" ? "app" : "web", Date.now());
    return token ? json({ token }) : json({ error: "too many parents" }, 409);
  }

  /** Shared files list ("files": [{fid, to, name, size, n, at, got: [chunk #]}]); expired ones (1 h) dropped with their chunks. */
  async fileGc(now) {
    const list = (await this.s.get("files")) || [];
    const keep = list.filter(f => now - f.at < FILE_TTL);
    if (keep.length !== list.length) {
      await this.s.delete(list.filter(f => !keep.includes(f)).flatMap(f => [...Array(f.n).keys()].map(i => "fc:" + f.fid + ":" + i)));
      await this.s.put("files", keep);
    }
    return keep;
  }

  /** /file: POST ?fid&i&n&to&size&name (one chunk), GET ?to= (list), GET ?fid&i (chunk), DELETE ?fid. Parent and phone share the token. */
  /** One phone sync (HTTP POST /sync or a WebSocket {"t":"sync"} message): {cmds, rules?, live?, files?, device?} or {error, status}. */
  /** OSM map tile for the watches: some networks block tile.openstreetmap.org (DNS -> 127.0.0.1); paired callers
   * (X-KG-Token) or the tile-only key `k`; edge-cached 7 days. */
  async tile(req, url) {
    const [z, x, y] = ["z", "x", "y"].map(k => +url.searchParams.get(k));
    if (![z, x, y].every(Number.isInteger) || z < 13 || z > 18 || x < 0 || y < 0 || x >= 2 ** z || y >= 2 ** z) return json({ error: "bad tile" }, 400);
    const src = `https://tile.openstreetmap.org/${z}/${x}/${y}.png`, key = new Request(src);
    let r = await caches.default.match(key).catch(() => null);
    if (!r) {
      const up = await fetch(src, { headers: { "User-Agent": "KidGuardRelay/1 (map for the KidGuard watch app; +https://kidguard.asia)" } }).catch(() => null);
      if (!up || !up.ok) return json({ error: "tile " + (up ? up.status : "unreachable") }, 502);
      r = new Response(await up.arrayBuffer(), { headers: { "content-type": "image/png", "cache-control": "public, max-age=604800" } });
      await caches.default.put(key, r.clone()).catch(() => {});
    }
    return new Response(r.body, { headers: { "content-type": "image/png", "cache-control": "private, max-age=604800", "x-content-type-options": "nosniff" } });
  }

  /** Every icon in one reply (the page asked one URL per app: ~100 requests per Apps view, 2026-11-17). The page puts
   *  ?v=<state.iconV> in the URL, so the reply is cached for good and a new upload changes the URL. */
  async iconsAll() {
    const list = (await this.s.get("iconList")) || [], out = {};
    for (let i = 0; i < list.length; i += 128) {
      const got = await this.s.get(list.slice(i, i + 128).map(p => "icon:" + p));
      for (const [k, v] of got) out[k.slice(5)] = v;
    }
    return new Response(JSON.stringify(out), { headers: { "content-type": "application/json", "cache-control": "private, max-age=31536000, immutable", "x-content-type-options": "nosniff" } });
  }
  async icon(url) {
    const pkg = url.searchParams.get("pkg") || "";
    const b64 = PKG.test(pkg) ? await this.s.get("icon:" + pkg) : null;
    if (!b64) return json({ error: "no icon" }, 404);
    return new Response(Uint8Array.from(atob(b64), c => c.charCodeAt(0)), { headers: {
      "content-type": "image/webp", "cache-control": "private, max-age=86400", "x-content-type-options": "nosniff" } });
  }

  /** Push wake to the kid phone (its own FCM token, `kfcm`), rate-limited by `wk` {at, n}. [why] "parent" | "alarm". */
  async wakeKid(now, seen, why) {
    if (this.kfcm === undefined) this.kfcm = (await this.s.get("kfcm")) || "";
    if (!this.kfcm || !this.env.FCM_SA) return false;
    const wk = this.wk || (this.wk = (await this.s.get("wk")) || { at: 0, n: 0 });
    if (seen > wk.at) wk.n = 0;   // heard since the last push: a fresh quiet spell
    const gap = why === "parent" ? +(this.env.WAKE_PARENT_MS || WAKE_PARENT) : wk.n < 9 ? WAKE_QUIET : 60 * 60e3;
    if (now - wk.at < gap) return false;
    wk.at = now; wk.n++; await this.s.put("wk", wk);
    let st = 0;
    try { st = await fcmSend(this.env, this.kfcm, { kind: "wake", kid: this.kidId || "" }, "600s"); } catch (e) { console.log("wake", String(e)); return false; }
    if (st >= 300) console.log("wake fcm", st, why);
    if (st === 404) { this.kfcm = ""; await this.s.delete("kfcm"); }   // app removed / reinstalled: the next sync brings the new token
    return st > 0 && st < 300;   // 0 = fcmSend threw
  }

  /**
   * The phone went quiet ([quietStep]): one Telegram line + a push that makes the parent app check now (it then shows its
   * own "offline since" notice). Once per quiet spell; [quietBack] says when it is heard again. Returns the next look (ms).
   */
  async quietCheck(now, heard) {
    const [qa, state, tg, tokens, kid] = await Promise.all(["qa", "state", "tg", "push", "kid"].map(k => this.s.get(k)));
    if (!state || !((tg && tg.chat) || (tokens && tokens.length))) return 0;   // nobody to tell
    const bed = !!(state.now && state.now.why === "bedtime");
    const { alert, next } = quietStep(now, heard, qa || 0, bed);
    if (!alert) return next;
    this.qa = heard; await this.s.put("qa", heard);   // before sending: a retry must not double-notify
    if (tg && tg.chat && this.env.TG_BOT) {
      const m = TGM[tg.lang] || TGM.vi;
      const st = await tgSend(this.env, tg.chat, m.quiet(tg.name, Math.round((now - heard) / 60e3))).catch(() => 0);
      if (st === 403) await this.s.delete(["tg", "tgCode"]);
    }
    if (kid && this.env.FCM_SA) for (const t of tokens || [])
      await fcmSend(this.env, t, { kind: "ev", kid, eid: "q:" + heard, t: String(heard), ek: "quiet" }).catch(() => 0);
    return 0;
  }

  /** Heard again after a quiet alert: Telegram says so (the parent app clears its own notice on its next check). */
  async quietBack() {
    if (this.qa === undefined) this.qa = (await this.s.get("qa")) || 0;
    if (!this.qa) return;
    this.qa = 0; await this.s.delete("qa");
    const a = await this.s.getAlarm(), t = Date.now() + QUIET_ALERT + 5000;   // the alert left the alarm far out: watch the next spell
    if (!a || a > t) await this.s.setAlarm(t);
    const tg = await this.s.get("tg");
    if (tg && tg.chat && this.env.TG_BOT) await tgSend(this.env, tg.chat, (TGM[tg.lang] || TGM.vi).back(tg.name)).catch(() => 0);
  }

  /** Storage GC: armed once per object wake by sync(); see GC_MS. Also the push wake of a quiet phone (wakeKid). */
  async alarm() {
    const now = Date.now(), seen = this.phoneWs().length ? now : Math.max((await this.s.get("seen")) || 0, this.seen || 0);
    if (now - seen > GC_MS) { this.stateMem = null; await this.s.deleteAll(); return; }
    const sf = this.flushState({}); if (Object.keys(sf).length) await this.s.put(sf);
    const files = await this.fileGc(now);   // expired shared files go now; the ones left bring the next alarm forward
    // bound by a junk first sync and never heard again: no rules, no queue, no second sync within UNUSED_MS => gone
    const [born, rules, cmds, seenAt] = await Promise.all(["born", "rules", "cmds", "seen"].map(k => this.s.get(k)));
    if (born && !this.phoneWs().length && !rules && !(cmds && cmds.length) && (seenAt || 0) - born < 120e3 && now - born > UNUSED_MS) { this.stateMem = null; await this.s.deleteAll(); return; }
    let next = born && now - born < UNUSED_MS ? born + UNUSED_MS + 1000 : seen + GC_MS;
    if (this.kfcm === undefined) this.kfcm = (await this.s.get("kfcm")) || "";
    if (this.kfcm && this.env.FCM_SA) {
      const heard = this.seenNow(seenAt), quiet = now - heard;
      if (quiet >= WAKE_QUIET && quiet < WAKE_DAYS) { await this.wakeKid(now, heard, "alarm"); next = Math.min(next, now + WAKE_QUIET); }
      else if (quiet < WAKE_QUIET) next = Math.min(next, heard + WAKE_QUIET + 5000);
    }
    const q = await this.quietCheck(now, this.seenNow(seenAt)).catch(e => { console.log("quiet", String(e)); return 0; });
    if (q) next = Math.min(next, q);
    await this.s.setAlarm(Math.max(now + 1000, files.length ? Math.min(next, Math.min(...files.map(f => f.at)) + FILE_TTL + 60e3) : next));
  }

  async sync(body, id, nk, now, ws = null) {
    // armed once per object wake. Pulled in to the quiet-alert horizon: an alarm set 30 days out (no kid push token) would
    // otherwise never look at a quiet spell (alarm() recomputes the real next time, junk-bind GC included)
    if (!this.gcArmed) { this.gcArmed = true; const a = await this.s.getAlarm(), t = now + QUIET_ALERT + 5000; if (!a || a > t) await this.s.setAlarm(t); }
    if (this.kidSaved !== id) { this.kidSaved = id; if ((await this.s.get("kid")) !== id) await this.s.put("kid", id); }   // the alarm has no request: quiet push needs the id
    this.ctx.waitUntil(this.quietBack().catch(() => {}));
    let state = body.state && typeof body.state === "object" ? body.state : null;
    // a partial state (only what changed since the last sync, see mergeState) is applied on the stored one;
    // nothing stored (first sync after a GC, a wiped object) => ask the phone for a whole one
    let needWhole = false;
    if (state && state._part) {
      const old = await this.getK("state");
      if (old) state = mergeState(old, state); else { state = null; needWhole = true; }
    }
    if (state && new TextEncoder().encode(JSON.stringify(state)).length > 120e3) return { error: "state too big", status: 413 };   // DO storage value limit is 128 KiB
    const acks = new Set(Array.isArray(body.acks) ? body.acks.map(String) : []);
    // the family PIN hash (Rules.pinHash/pinSalt), sent when it changes: joins and browser unlocks are checked here. "" = no PIN
    const pvRes = body.pv !== undefined ? await this.pvSet(body.pv, body.pvo, now) : "";
    if (typeof body.fcm === "string" && FCM_TOKEN.test(body.fcm)) {   // the kid app's own token: push wake (wakeKid / alarm)
      if (this.kfcm === undefined) this.kfcm = (await this.s.get("kfcm")) || "";
      if (body.fcm !== this.kfcm) {
        this.kfcm = body.fcm; await this.s.put("kfcm", body.fcm);
        const al = await this.s.getAlarm(); if (!al || al > now + WAKE_QUIET) await this.s.setAlarm(now + WAKE_QUIET + 5000);
      }
    }
    const aiCut = typeof body.ai === "string" && body.ai !== this.aiSeen ? await this.s.get("aiCut") : null;
    if (aiCut && (body.ai !== aiCut.h || acks.has(aiCut.id))) await this.s.delete("aiCut");   // the phone ran it (or has another hash)
    if (typeof body.ai === "string" && body.ai !== this.aiSeen && (body.ai === "" || /^[0-9a-f]{64}$/.test(body.ai))
      && !(aiCut && aiCut.h && body.ai === aiCut.h && !acks.has(aiCut.id))) {   // the phone's AI token hash ("" = none)
      this.aiSeen = body.ai;
      if (body.ai) await this.s.put("aiHash", body.ai); else await this.s.delete("aiHash");
    }
    // awaits before the cmds read-modify-write: a parent POST /cmd landing in an await must not be overwritten by a stale list
    const netHash = nk && nk !== this.lastNet ? await sha256("net:" + nk) : null;
    const stored = (await this.s.get("cmds")) || [];
    const cmds = stored.filter(c => !acks.has(c.id) && live(c, now));
    const rules = await this.s.get("rules");
    const puts = {};
    // destructive commands stay queued until acked (a lost reply must not drop a release); the phone de-dups by id.
    // `sent` marks the first hand-out: after ONCE_WINDOW they are dropped even if never acked (e.g. the phone released).
    let changed = cmds.length !== stored.length;
    const dropped = stored.filter(c => !acks.has(c.id) && !live(c, now));
    for (const c of cmds) if ((ONCE.includes(c.type) || hasSecret(c)) && !c.sent) { c.sent = now; changed = true; }
    // the phone gets the secrets in this reply; what stays queued (until acked, <= ONCE_WINDOW) has none. A lost reply = the
    // re-sent command fails "pin needed" on the phone and the parent asks again: safe over convenient.
    if (changed) puts.cmds = cmds.map(c => c.sent === now && hasSecret(c) ? noSecret(c) : c);
    if (dropped.length) puts.expired = expiredOf(await this.s.get("expired"), dropped, now);
    this.seen = now;
    // over the WebSocket "seen" = connected (seenNow); written once on close instead of every minute
    if (!ws && now - this.seenWritten >= SEEN_EVERY) { puts.seen = now; this.seenWritten = now; }
    // the phone's public network (hashed, last 2: Wi-Fi v4 + v6), for /where "same network"
    if (netHash) {
      this.lastNet = nk;
      const h = netHash, ipk = (await this.s.get("ipk")) || [];
      if (ipk[0] !== h) puts.ipk = [h, ...ipk.filter(x => x !== h)].slice(0, 2);
    }
    if (state && Object.keys(state).length) {
      this.stateAt = now; this.stateMem = state;
      const core = stateCore(state);
      if (core !== this.stateCore || now - this.stateWritten >= STATE_FLUSH) { puts.state = state; this.stateCore = core; this.stateWritten = now; this.stateDirty = false; }
      else this.stateDirty = true;
    }
    // the phone has the rules (its state carries their rev) and keeps its own salted PIN hash: the worker's unsalted copy is not needed any more
    if (rules && rules.pinHash && state && state.rules && state.rules.pinSet === true && (+state.rules.rev || 0) >= (+rules.rev || 0)) {
      const { pinHash, pinSalt, ...rest } = rules; puts.rules = { ...rest, pinSet: true };
    }
    if (Object.keys(puts).length) await this.s.put(puts);
    if (state) this.ctx.waitUntil(this.pushNew(state, id).catch(e => console.log("push", String(e))));
    const out = { cmds, merge: 1 };   // merge: this worker takes partial states (the phone sends whole ones until it sees it)
    if (pvRes) out.pv = pvRes;
    if (needWhole) out.full = true;
    if (this.isLive(now)) { out.live = true; out.lws = 1; }   // lws: frames may come as binary socket messages (0.9.61)
    const files = (await this.fileGc(now)).filter(f => f.to === "kid" && f.got.length === f.n).length;
    if (files) out.files = files;   // the phone then fetches them from /file
    if (this.deviceWant && now - this.deviceWant < 120e3) out.device = true;
    const call = await this.callNow(now);
    if (call) out.call = { id: call.id, st: call.st, by: call.by, ...(call.kind ? { kind: call.kind } : {}), ...(call.kind === "cam" ? { face: call.face, snd: !!call.snd } : {}), ...(call.st === "ring" ? { sdp: call.offer, ice: await this.iceServers(now) } : {}) };
    for (const [d, at] of this.lgdWant) { if (now - at < 120e3) { out.logday = d; break; } this.lgdWant.delete(d); }   // one day per sync
    const known = state || (await this.getK("state"));   // the phone omits state when unchanged
    const have = known && known.rules ? +known.rules.rev || 0 : 0;
    if (rules && rules.rev > have) out.rules = rules;
    return out;
  }

  /**
   * sync `pv` (audit 2026-11-20 HIGH: the kid token set its own PIN here, then joined with it = a parent token).
   * Taken when none is stored, when it is the stored one, or with `pvo` = the hash this worker had (the phone keeps the
   * one the worker last said "ok" to; the kid token alone never learns it). Else refused + every parent told.
   * -> "ok" | "refused" | "" (bad shape).
   */
  async pvSet(pv, pvo, now) {
    const nv = pv === "" ? null : isObj(pv) && /^[0-9a-f]{64}$/.test(String(pv.h)) && /^[0-9a-f]{0,32}$/.test(String(pv.s || "")) ? { h: pv.h, s: String(pv.s || "") } : undefined;
    if (nv === undefined) return "";
    const [old, prev] = await Promise.all([this.s.get("pv"), this.s.get("pvPrev")]);
    if (old && old.h && nv && nv.h === old.h) return "ok";
    const proof = typeof pvo === "string" && /^[0-9a-f]{64}$/.test(pvo) && (pvo === (old && old.h) || pvo === prev);
    if (old && old.h && !proof) { await this.secAlert("pvRefused", now); return "refused"; }
    if (nv) await this.s.put("pv", nv); else await this.s.delete("pv");
    await this.s.delete(["pvWeb", "pvPrev"]);
    // a first PIN while parents already exist (no PIN before: the link alone was enough) is worth a look too
    if (!(old && old.h) && nv && Object.keys(await this.parents()).length) await this.secAlert("pvNew", now);
    return "ok";
  }
  /** A security notice from the worker itself: kept in `secEv` (merged into state.events on GET state) + FCM / Telegram "tamper". */
  async secAlert(rk, now) {
    const e = { id: "r" + now.toString(36), t: now, kind: "tamper", rk, text: REV_TEXT[rk] || rk };
    await this.s.put("secEv", [...((await this.s.get("secEv")) || []), e].slice(-5));
    const id = this.kidId || (await this.s.get("kid")) || "", ev = [{ kind: "ev", eid: "e:" + e.id, t: now, ek: "tamper" }];
    this.ctx.waitUntil(Promise.all([this.fcmNew(ev, id), this.tgNew(ev)]).catch(e2 => console.log("secAlert", String(e2))));
  }

  /** The call in progress (memory, else storage), or null once it is over: a ring nobody took, a call past CALL_MAX, an end kept CALL_KEEP. */
  async callNow(now) {
    if (this.call === undefined) this.call = (await this.s.get("call")) || null;
    const c = this.call;
    if (c && ((c.st === "ring" && now - c.at > CALL_RING) || (c.st === "on" && now - c.on > (c.kind ? LISTEN_MAX : CALL_MAX)) || (c.st === "end" && now - c.endAt > CALL_KEEP))) {
      if (c.st === "end") { this.call = null; await this.s.delete("call"); return null; }
      return this.callSet({ ...c, st: "end", why: c.st === "ring" ? "missed" : "timeout", endAt: now });
    }
    return c;
  }
  async callSet(c) {
    this.call = c; await this.s.put("call", c);
    for (const w of this.callWaiters || []) w();
    return c;
  }
  /** GET: the call as this side sees it (?ice=1 adds STUN/TURN servers; ?id=&st= waits up to 12 s for a change: under the page's 15 s fetch limit).
   * POST {op: offer|answer|end, id, sdp?, why?}: the parent offers; the phone answers; either side ends. */
  async callRoute(req, url, phone, who, now) {
    let c = await this.callNow(now);
    // ring: the parent's offer (the phone takes it); on: the phone's answer (the parent takes it). An old shared token is both sides.
    const view = (c) => c && { id: c.id, st: c.st, by: c.by, at: c.at, ...(c.kind ? { kind: c.kind } : {}), ...(c.kind === "cam" ? { face: c.face, snd: !!c.snd } : {}), ...(c.st === "ring" ? { sdp: c.offer } : c.st === "on" ? { sdp: c.answer } : {}), ...(c.why ? { why: c.why } : {}) };
    if (req.method === "GET") {
      const q = url.searchParams, id = q.get("id");
      if (id && c && c.id === id && c.st === (q.get("st") || "")) {
        this.callWaiters = this.callWaiters || new Set();
        await new Promise(res => { const done = () => { clearTimeout(t); this.callWaiters.delete(done); res(); }; const t = setTimeout(done, 12e3); this.callWaiters.add(done); });
        c = await this.callNow(Date.now());
      }
      return json({ call: view(c), ...(q.get("ice") === "1" ? { ice: await this.iceServers(now) } : {}) });
    }
    if (req.method !== "POST") return json({ error: "method" }, 405);
    let b; try { b = await req.json(); } catch { return json({ error: "bad json" }, 400); }
    const op = b && b.op, id = String(b && b.id || ""), sdp = b && b.sdp;
    if (!CALL_ID.test(id)) return json({ error: "bad id" }, 400);
    if ((op === "offer" || op === "answer") && (typeof sdp !== "string" || !sdp.startsWith("v=0") || sdp.length > CALL_SDP)) return json({ error: "bad sdp" }, 400);
    if (op === "offer") {
      // the phone's own token (an old shared link) starts nothing once parents have tokens or the PIN is known
      if (phone && (Object.keys(await this.parents()).length || ((await this.s.get("pv")) || {}).h)) return json({ error: "join" }, 403);
      if (c && c.st !== "end" && c.id !== id) return json({ error: "busy", by: c.by }, 409);
      if (b.kind === "cam") {
        const ks = await this.getK("state");
        if (ks && ks.version && /^\d/.test(ks.version) && vOlder(ks.version, CAM_KID)) return json({ error: "old", need: CAM_KID }, 409);
      }
      const kind = b.kind === "listen" || b.kind === "cam" ? b.kind : "";
      c = await this.callSet({ id, st: "ring", by: who || "", at: now, offer: sdp, ...(kind ? { kind } : {}),
        ...(kind === "cam" ? { face: b.face === "back" ? "back" : "front", snd: b.snd === true } : {}) });
      this.kickPhone(now);
      if (!this.phoneWs().length) this.ctx.waitUntil(this.wakeKid(now, this.seenNow(await this.s.get("seen")), "parent").catch(() => {}));
      return json({ call: view(c) });
    }
    if (!c || c.id !== id) return json({ error: "no call" }, 404);
    if (op === "answer") {
      if (c.st !== "ring") return json({ error: "not ringing", call: view(c) }, 409);
      return json({ call: view(await this.callSet({ ...c, st: "on", on: now, answer: sdp })) });
    }
    if (op === "end") {
      if (c.st === "end") return json({ call: view(c) });
      const why = CALL_WHY.includes(b.why) || CAM_WHY.includes(b.why) ? b.why : "hangup";
      c = await this.callSet({ ...c, st: "end", why, endAt: now, endBy: phone ? "kid" : "parent" });
      if (!phone) this.kickPhone(now);
      return json({ call: view(c) });
    }
    return json({ error: "bad op" }, 400);
  }
  /** STUN always; Cloudflare TURN (free 1 TB/month) when the worker has TURN_KEY_ID + TURN_KEY_TOKEN. Minted creds are kept 1 h (they live 4 h). */
  async iceServers(now) {
    if (this.ice && now < this.ice.until) return this.ice.list;
    let list = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
    if (this.env.TURN_KEY_ID && this.env.TURN_KEY_TOKEN) {
      try {
        const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${this.env.TURN_KEY_ID}/credentials/generate-ice-servers`, {
          method: "POST", headers: { authorization: "Bearer " + this.env.TURN_KEY_TOKEN, "content-type": "application/json" }, body: JSON.stringify({ ttl: 4 * 3600 }) });
        if (r.ok) {
          // port 53 TURN urls time out in browsers (Cloudflare's own advice: drop them)
          const got = [].concat((await r.json()).iceServers || []).map(x => ({ ...x, urls: [].concat(x.urls || []).filter(u => !/:53(\?|$)/.test(u)) })).filter(x => x.urls.length);
          if (got.length) list = got; else console.log("turn empty");
        } else console.log("turn", r.status);
      } catch (e) { console.log("turn", String(e)); }
    }
    this.ice = { list, until: now + 3600e3 };
    return list;
  }

  async webSocketMessage(ws, msg) {
    const tags = this.ctx.getTags(ws);
    if (tags.includes("parent")) return this.parMsg(ws, msg);
    if (typeof msg !== "string") return this.phoneFrame(msg);
    if (msg.length > MAX_BODY) return;
    let b; try { b = JSON.parse(msg); } catch { return; }
    if (!isObj(b) || b.t !== "sync") return;
    const a = ws.deserializeAttachment() || {}, now = Date.now();
    a.at = now; ws.serializeAttachment(a);
    const out = await this.sync(b, a.id, a.nk, now, ws);
    try { ws.send(JSON.stringify({ t: "sync", n: b.n, ...out })); } catch {}
  }
  // the phone's frame over its socket: [2 bytes header length][header JSON {w,h,err}][JPEG]. Sent to every parent socket
  // as is (outgoing messages are free); the phone only sends when the picture changed (LiveLogic).
  phoneFrame(buf) {
    if (!(buf instanceof ArrayBuffer) || buf.byteLength < 2 || buf.byteLength > MAX_FRAME) return;
    const now = Date.now(), dv = new DataView(buf), n = dv.getUint16(0);
    if (2 + n > buf.byteLength) return;
    let hd = {}; try { hd = JSON.parse(new TextDecoder().decode(new Uint8Array(buf, 2, n))); } catch {}
    const jpeg = buf.byteLength > 2 + n ? buf.slice(2 + n) : null;
    this.wsFrames = true;
    this.frame = { seq: ++this.seq, jpeg, raw: jpeg ? buf : null, w: +hd.w || 0, h: +hd.h || 0, at: now, err: jpeg ? "" : String(hd.err || "no frame").slice(0, 60) };
    for (const w of this.waiters) w();   // HTTP viewers (older pages)
    const pars = this.parWs();
    for (const w of pars) try { w.send(buf); } catch {}
    const inputs = this.inputs.filter(i => now - i.at <= 5000).map(({ at, ...i }) => i); this.inputs = [];
    if (inputs.length) this.phoneSay({ t: "in", inputs });
    if (!this.isLive(now)) this.phoneSay({ t: "live", live: false });
  }
  parMsg(ws, msg) {
    if (typeof msg !== "string" || msg.length > 2048) return;
    let b; try { b = JSON.parse(msg); } catch { return; }
    const now = Date.now();
    ws.serializeAttachment({ par: 1, at: now });
    if (b.t === "stop") { try { ws.close(1000, "stop"); } catch {} return this.parGone(ws); }
    if (b.t === "in") { const o = inputOf(b.i, now); if (o && this.inputs.length < 50) this.queueInput(o); return; }
    if (b.t === "live") this.phoneSay({ t: "live", live: true });   // every 15 s: the phone stops on its own after 45 s without one
  }
  parGone(ws) {
    if (this.parWs(ws).length || this.liveUntil > Date.now()) return;
    // the last viewer left: the phone stops capturing and drops its notice now
    this.phoneSay({ t: "live", live: false });
  }
  getK(k) { return k === "state" && this.stateMem ? this.stateMem : this.s.get(k); }
  flushState(puts) { if (this.stateDirty && this.stateMem) { puts.state = this.stateMem; this.stateDirty = false; this.stateWritten = Date.now(); } return puts; }
  async webSocketClose(ws) { await this.closed(ws); }
  async webSocketError(ws) { await this.closed(ws); }
  async closed(ws) {
    const a = ws.deserializeAttachment() || {};
    if (a.par) { try { ws.close(1000, "bye"); } catch {} return this.parGone(ws); }
    let p = 0; try { p = +(this.ctx.getWebSocketAutoResponseTimestamp(ws) || 0); } catch {}
    try { ws.close(1011, "closed"); } catch {}
    this.wsFrames = false;
    const last = Math.max(a.at || 0, p, this.seen);   // the phone's last sign of life, not the moment we noticed
    if (last) { this.seen = last; this.seenWritten = Date.now(); await this.s.put(this.flushState({ seen: last })); }
  }

  async file(req, url, now) {
    const q = url.searchParams, fid = q.get("fid") || "";
    const buf = req.method === "POST" ? await req.arrayBuffer() : null;   // before any storage read: no other await between get and put below
    if (buf && buf.byteLength > FILE_CHUNK) return json({ error: "too big" }, 413);
    const list = await this.fileGc(now);
    if (req.method === "GET" && !fid) {
      const to = q.get("to");
      return json({ files: list.filter(f => f.to === to).map(f => ({ fid: f.fid, name: f.name, size: f.size, n: f.n, at: f.at, done: f.got.length === f.n, ...(f.chat ? { chat: 1 } : {}) })), max: FILE_MAX, chunk: FILE_CHUNK });
    }
    if (!FID.test(fid)) return json({ error: "bad id" }, 400);
    let f = list.find(x => x.fid === fid), fresh = false;
    const key = (i) => "fc:" + fid + ":" + i;
    if (req.method === "POST") {
      if (!f) {
        const to = q.get("to"), size = Number(q.get("size")), n = Number(q.get("n")), name = String(q.get("name") || "").slice(0, 200);
        if (!["kid", "parent"].includes(to) || !name || !Number.isInteger(size) || size < 0) return json({ error: "bad file" }, 400);
        if (size > FILE_MAX) return json({ error: "too big" }, 413);
        const thumb = q.get("thumb") === "1", chat = q.get("chat") === "1";   // thumb: a small preview, not counted in the daily quota; chat: the file is a chat attachment (the phone adds the chat entry)
        if (thumb && size > FILE_THUMB) return json({ error: "too big" }, 413);
        if (n !== Math.max(1, Math.ceil(size / FILE_CHUNK))) return json({ error: "bad file" }, 400);
        if (list.length >= FILE_N || list.reduce((s, x) => s + x.size, 0) + size > FILE_TOTAL) return json({ error: "relay full" }, 507);
        if (!thumb) {
          let fq = (await this.s.get("fq")) || { start: now, bytes: 0, n: 0 };
          if (now - fq.start >= 86400e3) fq = { start: now, bytes: 0, n: 0 };
          if (fq.n + 1 > FILE_DAY_N || fq.bytes + size > FILE_DAY_BYTES) return json({ error: "quota", retryAt: fq.start + 86400e3 }, 429);
          fq.n++; fq.bytes += size; await this.s.put("fq", fq);
        }
        f = { fid, to, name, size, n, at: now, got: [], ...(chat ? { chat: 1 } : {}), ...(thumb ? { thumb: 1 } : {}) }; list.push(f); fresh = true;
      }
      const i = Number(q.get("i"));
      if (!Number.isInteger(i) || i < 0 || i >= f.n) return json({ error: "bad chunk" }, 400);
      if (buf.byteLength !== (i < f.n - 1 ? FILE_CHUNK : f.size - FILE_CHUNK * (f.n - 1))) return json({ error: "bad chunk" }, 400);
      if (!f.got.includes(i)) f.got.push(i);
      await this.s.put({ [key(i)]: buf, files: list });
      // nobody fetched it and no sync / file call comes any more (phone off, page closed): the alarm removes the chunks at
      // the 1 h TTL, they used to stay (up to 32 MB) until the next request for this kid, or the 30-day GC
      if (fresh) { const due = now + FILE_TTL + 60e3, al = await this.s.getAlarm(); if (!al || al > due) await this.s.setAlarm(due); }
      return json({ ok: true, done: f.got.length === f.n });
    }
    if (!f) return json({ error: "no such file" }, 404);
    if (req.method === "GET") {
      const i = Number(q.get("i"));
      const b = Number.isInteger(i) && i >= 0 && i < f.n ? await this.s.get(key(i)) : null;
      if (!b) return json({ error: "no such chunk" }, 404);
      return new Response(b, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
    }
    if (req.method === "DELETE") {
      // a file dropped before it was whole (upload failed / cancelled) gives back the bytes it never received: a retry must
      // not cost the whole size twice; what did arrive (and the file count) stays counted, so start+drop is no way around it
      if (!f.thumb && f.got.length < f.n) {
        const fq = await this.s.get("fq"), unsent = f.size - f.got.reduce((s, i) => s + (i < f.n - 1 ? FILE_CHUNK : f.size - FILE_CHUNK * (f.n - 1)), 0);
        if (fq && f.at >= fq.start && unsent > 0) await this.s.put("fq", { ...fq, bytes: Math.max(0, fq.bytes - unsent) });
      }
      await this.s.delete([...Array(f.n).keys()].map(key));
      await this.s.put("files", list.filter(x => x !== f));
      return json({ ok: true });
    }
    return json({ error: "method not allowed" }, 405);
  }

  /** Telegram webhook update -> the kid DO named in the link code (/start <code>) or in /stop_<id>. */
  async tgIn(o, id) {
    const tg = (await this.s.get("tg")) || null, chat = o.chat;
    if (o.stop) {
      if (!tg || tg.chat !== chat) return json({ ok: false });
      await this.s.delete(["tg", "tgCode"]);
      await tgSend(this.env, chat, TGM[tg.lang].off(tg.name));
      return json({ ok: true });
    }
    const c = await this.s.get("tgCode");
    if (!c || c.exp < Date.now() || typeof o.code !== "string" || o.code !== c.code) return json({ ok: false });
    await this.s.put("tg", { chat, name: c.name, lang: c.lang, at: Date.now() });
    await this.s.delete("tgCode");
    await tgSend(this.env, chat, TGM[c.lang].on(c.name, id));
    return json({ ok: true });
  }

  /** Same moments as FCM (SOS, more-time request, new app / tamper event) -> one Telegram text to the linked chat. */
  async tgNew(ev) {
    if (!this.env.TG_BOT) return;
    const tg = await this.s.get("tg");
    if (!tg || !tg.chat) return;
    const seen = (await this.s.get("tgSeen")) || [];
    const fresh = ev.filter(e => !seen.includes(e.eid) && (e.kind !== "ev" || e.ek === "newApp" || e.ek === "tamper")).slice(0, 5);
    if (!fresh.length) return;
    await this.s.put("tgSeen", [...seen, ...fresh.map(e => e.eid)].slice(-50));   // before sending: a retry must not double-notify
    const m = TGM[tg.lang] || TGM.vi;
    for (const e of fresh) {
      const st = await tgSend(this.env, tg.chat, e.kind === "sos" ? m.sos(tg.name) : e.kind === "req" ? m.req(tg.name, e.min) : e.ek === "tamper" ? m.tamper(tg.name) : m.newApp(tg.name));
      if (st === 403) { await this.s.delete(["tg", "tgCode"]); return; }   // the parent blocked the bot: unlink
    }
  }

  /**
   * Kid notifications (rule watch.notif, docs/protocol.md "Push"): the newest row of state.notifs (rows with a keyword `hit`
   * left out: their `word` event pushes already) newer than the last one pushed (`nfPushed`, its own key: the shared `pushed`
   * list is for events) -> one FCM `{kind: "nf", kid}` with no text to the parent apps that asked for it (`pushNf`). Collapsed
   * per kid, 10 min ttl, at most one per NF_GAP (no longer: a skipped nudge waits for the next sync). The app then reads
   * `state?nf=<its cursor>`.
   */
  async nfNew(state, id) {
    const nf = state && state.notifs, rows = nf && Array.isArray(nf.n) ? nf.n : [];
    let top = 0;
    for (const r of rows) if (r && !r.hit && Number.isSafeInteger(+r.id) && +r.id > top) top = +r.id;
    if (!top || !this.env.FCM_SA) return;
    const [was, at, toks] = await Promise.all(["nfPushed", "nfAt", "pushNf"].map(k => this.s.get(k)));
    const tokens = toks || [];
    if (!tokens.length) return;
    // first sight: nothing is new yet. The kid's clock went back (pushed id ahead of the state's own time): start again from here
    if (was === undefined || +was / 1000 > (+state.t || Date.now()) + 60e3) { await this.s.put("nfPushed", top); return; }
    const now = Date.now();
    if (top <= +was || now - (+at || 0) < +(this.env.NF_GAP_MS || NF_GAP)) return;
    await this.s.put({ nfPushed: top, nfAt: now });   // before sending: a retry must not double-notify
    const dead = new Set(); let retry = 0;
    for (const t of tokens) {
      const st = await fcmSend(this.env, t, { kind: "nf", kid: id }, "600s", "nf-" + id);
      if (st >= 300) console.log("fcm nf", st);
      if (st === 404) dead.add(t);
      else if (st === 0 || st === 429 || st >= 500) retry++;
    }
    if (dead.size) {
      await this.s.put("pushNf", tokens.filter(t => !dead.has(t)));
      await this.s.put("push", ((await this.s.get("push")) || []).filter(t => !dead.has(t)));
    }
    if (retry && retry + dead.size === tokens.length) await this.s.put("nfPushed", was);   // nobody got it: the next sync tries again
  }

  /** New SOS / more-time requests in the phone's state -> FCM data message to every registered parent app. */
  async pushNew(state, id) {
    const nf = this.nfNew(state, id).catch(e => console.log("nf", String(e)));   // beside the events: never holds an SOS push
    const now = Date.now(), ev = [];
    for (const s of Array.isArray(state.sos) ? state.sos : []) if (s && now - +s.t < 6 * 3600e3) ev.push({ kind: "sos", eid: "s:" + s.id, t: +s.t });
    for (const r of Array.isArray(state.requests) ? state.requests : []) if (r && now - +r.t < 3600e3) ev.push({ kind: "req", eid: "r:" + r.id, t: +r.t, min: +r.min || 0, app: r.kind === "app" });
    for (const e of Array.isArray(state.events) ? state.events : []) if (e && now - +e.t < 3600e3 && /^[\w-]{1,24}$/.test(String(e.id)) && e.kind !== "tsDown" && e.kind !== "tsUp")   // Tailscale drop / return: log only, no push
      ev.push({ kind: "ev", eid: "e:" + e.id, t: +e.t, ek: String(e.kind || "").slice(0, 12) });   // no text / zone name to Google
    if (!ev.length) return nf;
    // Telegram beside FCM, not before it: a slow Telegram API must not hold the SOS push (review 2026-11-18)
    const tg = this.tgNew(ev).catch(e => console.log("tg", String(e)));
    await this.fcmNew(ev, id).catch(e => console.log("fcm", String(e)));
    await tg; await nf;
  }

  async fcmNew(ev, id) {
    if (!this.env.FCM_SA) return;
    // at most 5 events a sync (x 5 devices = 25 FCM calls, under the 50 subrequests a request may make)
    const sent = (await this.s.get("pushed")) || [], fresh = ev.filter(e => !sent.includes(e.eid)).slice(0, 5);
    if (!fresh.length) return;
    const tokens = (await this.s.get("push")) || [];
    await this.s.put("pushed", [...sent, ...fresh.map(e => e.eid)].slice(-50));   // before sending: a retry must not double-notify
    if (!tokens.length) return;
    const dead = new Set(), retry = new Set();
    for (const e of fresh) for (const t of tokens) {
      const st = await fcmSend(this.env, t, { kind: e.kind === "req" && e.app ? "ev" : e.kind, kid: id, eid: e.eid, t: String(e.t), ...(e.kind === "ev" ? { ek: e.ek || "req" } : e.kind === "req" && e.app ? { ek: "req" } : { min: String(e.min || "") }) });
      if (st >= 300) console.log("fcm", st, e.kind);   // Google refused (wrong project, auth, ...): was silent (owner 2026-11-16)
      if (st === 404) dead.add(t);   // UNREGISTERED: the app was removed or re-installed (a 400 may be our own config: keep the token)
      if (st === 0 || st === 429 || st >= 500) retry.add(e.eid);   // Google down / network: not pushed, the next sync tries again
    }
    if (dead.size) await this.s.put("push", tokens.filter(t => !dead.has(t)));
    if (retry.size) await this.s.put("pushed", ((await this.s.get("pushed")) || []).filter(x => !retry.has(x)));
  }
}

/**
 * Applies a partial state from the phone (bandwidth, owner 2026-11-11: whole states were ~20 MB/day) on [old]:
 * top-level keys replace, `_drop` removes keys, `_apps` = {set: [app objects], del: [pkg]} merged by pkg (then sorted by
 * use like the phone does), `_trk` = {from, add}: points appended (only newer than the last stored one: a resend after a
 * lost reply must not double them), then points older than `from` (the phone's 2-day window) dropped.
 */
// what must be stored at once: the state without its time counters (t, usedMs/leftMs, per-app ms, battery %)
export function stateCore(s) {
  const { t, relayOk, usedMs, leftMs, battery, apps, ...rest } = s || {};
  return JSON.stringify([rest, Array.isArray(apps) ? apps.map(a => a && typeof a === "object" ? { ...a, ms: 0 } : a) : apps]);
}
// [2 bytes header length][header JSON {w,h[,err]}][JPEG]: the screen socket's frame (same as LiveLogic.wsFrame on the phone)
export function wsFrame(w, h, err, jpeg) {
  const hd = new TextEncoder().encode(JSON.stringify(err ? { w, h, err } : { w, h })), n = jpeg ? jpeg.byteLength : 0;
  const out = new Uint8Array(2 + hd.length + n);
  out[0] = hd.length >> 8; out[1] = hd.length & 255; out.set(hd, 2);
  if (jpeg) out.set(new Uint8Array(jpeg), 2 + hd.length);
  return out.buffer;
}
export function inputOf(b, now) {
  if (!b || typeof b !== "object") return null;
  const t = b.type, f01 = (v) => typeof v === "number" && v >= 0 && v <= 1;
  const ok = (t === "tap" || t === "long") ? f01(b.x) && f01(b.y)
    : t === "swipe" ? f01(b.x1) && f01(b.y1) && f01(b.x2) && f01(b.y2)
    : t === "key" ? KEYS.includes(b.key)
    : t === "text" ? typeof b.text === "string" && b.text.length <= 500 : false;
  if (!ok) return null;
  const o = { type: t, at: now };
  for (const k of ["x", "y", "x1", "y1", "x2", "y2", "key", "text"]) if (k in b) o[k] = b[k];
  if (t === "swipe") o.ms = Math.min(Math.max(+b.ms || 300, 50), 3000);   // same clamp as Acc.kt
  return o;
}
export function mergeState(old, part) {
  const s = { ...old };
  for (const [k, v] of Object.entries(part)) if (k[0] !== "_") s[k] = v;
  for (const k of Array.isArray(part._drop) ? part._drop : []) if (typeof k === "string") delete s[k];
  const a = part._apps;
  if (a && typeof a === "object") {
    const by = new Map((Array.isArray(old.apps) ? old.apps : []).map(x => [x && x.pkg, x]));
    for (const p of Array.isArray(a.del) ? a.del : []) by.delete(p);
    for (const x of Array.isArray(a.set) ? a.set : []) if (x && typeof x.pkg === "string") by.set(x.pkg, x);
    s.apps = [...by.values()].sort((x, y) => (+y.ms || 0) - (+x.ms || 0));
  }
  const tk = part._trk;
  if (tk && typeof tk === "object") {
    const cur = Array.isArray(old.track) ? old.track : [];
    const last = cur.length ? +cur[cur.length - 1][0] || 0 : 0;
    const add = (Array.isArray(tk.add) ? tk.add : []).filter(p => Array.isArray(p) && +p[0] > last);
    s.track = cur.concat(add).filter(p => +p[0] >= (+tk.from || 0));
  }
  return s;
}

// ---- FCM HTTP v1 (optional; secret FCM_SA = the Firebase service-account JSON) ----
const FCM_TOKEN = /^[\w:.-]{20,400}$/, MAX_PUSH = 5, NF_GAP = 15e3;   // NF_GAP: least time between two kid-notification nudges
let fcmAuth = null;   // {key, at, exp} per isolate
const b64u = (b) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64uStr = (s) => b64u(new TextEncoder().encode(s));

async function fcmToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (fcmAuth && fcmAuth.key === sa.client_email && fcmAuth.exp - 60 > now) return fcmAuth.at;
  const head = b64uStr(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64uStr(JSON.stringify({ iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: sa.token_uri, iat: now, exp: now + 3600 }));
  const der = Uint8Array.from(atob(sa.private_key.replace(/-----[^-]+-----|\s/g, "")), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(head + "." + claim));
  const r = await fetch(sa.token_uri, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + head + "." + claim + "." + b64u(sig) });
  const j = await r.json();
  if (!j.access_token) throw new Error("fcm auth " + r.status);
  fcmAuth = { key: sa.client_email, at: j.access_token, exp: now + (+j.expires_in || 3600) };
  return j.access_token;
}

/** Returns the HTTP status of the send. Data-only + high priority: the parent app builds the notification itself. */
// [collapse]: a newer message with the same key replaces one still waiting (kid-notification nudges)
async function fcmSend(env, token, data, ttl = "21600s", collapse = "") {
  try { return await fcmSend1(env, token, data, ttl, collapse); } catch (e) { console.log("fcm send", String(e)); return 0; }   // 0 = retry later
}
async function fcmSend1(env, token, data, ttl, collapse) {
  const sa = JSON.parse(env.FCM_SA);
  const at = await fcmToken(sa);
  const url = (env.FCM_URL || "https://fcm.googleapis.com") + `/v1/projects/${sa.project_id}/messages:send`;
  const r = await fetch(url, { method: "POST", headers: { authorization: "Bearer " + at, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl, ...(collapse ? { collapse_key: collapse } : {}) } } }) });
  if (r.status === 401) fcmAuth = null;
  return r.status;
}

// ---- Telegram alerts (optional; secret TG_BOT = bot token from @BotFather; TG_URL (var, tests only) overrides https://api.telegram.org) ----
// Text only, no location / note / app names: the same facts FCM carries, plus the kid's name the parent page sent when linking.
const TGM = {
  vi: {
    sos: n => `SOS: ${n} vừa bấm nút SOS! Mở KidGuard để xem vị trí.`,
    req: (n, min) => `${n} xin thêm ${min ? min + " phút" : "thời gian"}. Mở KidGuard để duyệt.`,
    newApp: n => `${n}: có app mới được cài trên máy.`,
    tamper: n => `${n}: có người thử can thiệp vào cài đặt máy.`,
    quiet: (n, min) => `${n}: máy con im lặng ${min} phút. Có thể hết pin, tắt máy, mất mạng, hoặc KidGuard bị buộc dừng (mở lại KidGuard trên máy con).`,
    back: n => `${n}: máy con đã kết nối lại.`,
    on: (n, id) => `Đã bật báo qua Telegram cho ${n}. Để huỷ: gửi /stop_${id} hoặc bấm "Huỷ liên kết" trên trang KidGuard.`,
    off: n => `Đã huỷ báo qua Telegram cho ${n}.`,
    help: "Mở trang KidGuard (Cài đặt > Báo qua Telegram) rồi bấm nút liên kết.",
  },
  en: {
    sos: n => `SOS: ${n} pressed the SOS button! Open KidGuard to see the location.`,
    req: (n, min) => `${n} asks for ${min ? min + " more minutes" : "more time"}. Open KidGuard to answer.`,
    newApp: n => `${n}: a new app was installed on the phone.`,
    tamper: n => `${n}: someone tried to tamper with the phone's settings.`,
    quiet: (n, min) => `${n}: the kid's phone has been quiet for ${min} minutes. Flat battery, switched off, no internet, or KidGuard was force-stopped (open KidGuard on the kid's phone).`,
    back: n => `${n}: the kid's phone is back online.`,
    on: (n, id) => `Telegram alerts are on for ${n}. To stop: send /stop_${id} or press "Unlink" on the KidGuard page.`,
    off: n => `Telegram alerts for ${n} are off.`,
    help: "Open the KidGuard page (Settings > Telegram alerts) and press the link button.",
  },
};
const tgApi = (env, method) => `${env.TG_URL || "https://api.telegram.org"}/bot${env.TG_BOT}/${method}`;
let tgBot = null;   // {key, name} per isolate
async function tgBotName(env) {
  if (tgBot && tgBot.key === env.TG_BOT) return tgBot.name;
  try {
    const j = await (await fetch(tgApi(env, "getMe"))).json();
    const name = j && j.ok && j.result && /^\w{3,64}$/.test(j.result.username || "") ? j.result.username : "";
    if (name) tgBot = { key: env.TG_BOT, name };
    return name;
  } catch { return ""; }
}
/** Returns the HTTP status (0 = network error). */
async function tgSend(env, chat, text) {
  try {
    const r = await fetch(tgApi(env, "sendMessage"), { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ chat_id: chat, text, disable_web_page_preview: true }) });
    await r.text();
    return r.status;
  } catch { return 0; }
}
/** POST /tg/<sha256("tg:" + TG_BOT)[:32]>: Telegram's webhook. Always answers 200 (Telegram retries otherwise). */
async function tgHook(req, env, key) {
  const ok = () => json({ ok: true });
  if (key !== (await sha256("tg:" + env.TG_BOT)).slice(0, 32)) return json({ error: "not found" }, 404);
  if (+(req.headers.get("content-length") || 0) > 16384) return ok();
  let u; try { u = await req.json(); } catch { return ok(); }
  const msg = u && u.message, text = msg && typeof msg.text === "string" ? msg.text.trim() : "";
  if (!msg || !msg.chat || msg.chat.type !== "private" || !Number.isSafeInteger(msg.chat.id)) return ok();
  const chat = msg.chat.id;
  const m = text.match(/^\/start(?:@\w+)?\s+([0-9a-f]{32})$/), stop = text.match(/^\/stop_([0-9a-f]{16})(?:@\w+)?$/);
  if (!m && !stop) { await tgSend(env, chat, TGM.vi.help + "\n" + TGM.en.help); return ok(); }
  const id = m ? m[1].slice(0, 16) : stop[1];
  const r = await env.KIDS.get(env.KIDS.idFromName(id)).fetch(`https://do/c/${id}/_tg`, { method: "POST", headers: { "x-kg-internal": "1" }, body: JSON.stringify(m ? { chat, code: m[1] } : { chat, stop: true }) });
  const j = await r.json().catch(() => ({}));
  if (!j.ok && m) await tgSend(env, chat, TGM.vi.help + "\n" + TGM.en.help);
  return ok();
}

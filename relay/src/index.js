// KidGuard relay: lets the parent's page reach the child phone when they are not on the same Wi-Fi.
// One Durable Object per child (by relay id). It stores the last state the child posted, the newest rules the
// parent set, and a short queue of commands. The child polls POST /c/<id>/sync every 20-60 s.
// Auth: the token in the parent's link; the first child sync binds its sha256 to the id.
import html from "../../ui/index.html";
import install from "../../ui/install.html";
import qr from "../../ui/qr.html";
// the parent page's code (ui/assets/, text via the wrangler rule), served at /ui/<name>: a new file there goes here too
import ui_style_css from "../../ui/assets/style.css";
import ui_qrcode_js from "../../ui/assets/qrcode.js";
import ui_i18n_vi_js from "../../ui/assets/i18n-vi.js";
import ui_i18n_en_js from "../../ui/assets/i18n-en.js";
import ui_core_js from "../../ui/assets/core.js";
import ui_overview_js from "../../ui/assets/overview.js";
import ui_kid_js from "../../ui/assets/kid.js";
import ui_tab_files_js from "../../ui/assets/tab-files.js";
import ui_shell_js from "../../ui/assets/shell.js";
import ui_tab_home_js from "../../ui/assets/tab-home.js";
import ui_tab_apps_js from "../../ui/assets/tab-apps.js";
import ui_tab_rules_js from "../../ui/assets/tab-rules.js";
import ui_tab_screen_js from "../../ui/assets/tab-screen.js";
import ui_tab_where_js from "../../ui/assets/tab-where.js";
import ui_tab_set_js from "../../ui/assets/tab-set.js";
import ui_start_js from "../../ui/assets/start.js";
const UI = {
  "style.css": ui_style_css,
  "qrcode.js": ui_qrcode_js,
  "i18n-vi.js": ui_i18n_vi_js,
  "i18n-en.js": ui_i18n_en_js,
  "core.js": ui_core_js,
  "overview.js": ui_overview_js,
  "kid.js": ui_kid_js,
  "tab-files.js": ui_tab_files_js,
  "shell.js": ui_shell_js,
  "tab-home.js": ui_tab_home_js,
  "tab-apps.js": ui_tab_apps_js,
  "tab-rules.js": ui_tab_rules_js,
  "tab-screen.js": ui_tab_screen_js,
  "tab-where.js": ui_tab_where_js,
  "tab-set.js": ui_tab_set_js,
  "start.js": ui_start_js,
};

const MAX_BODY = 256 * 1024, MAX_DEVICE = 32 * 1024;
const MAX_FRAME = 1024 * 1024;
const MAX_RULES = 64 * 1024, MAX_CMD = 4096, MAX_CMDS = 20;   // 64 KB + 20 x 4 KB stays far below the phone's 256 KB reply cap
const KEYS = ["back", "home", "recents", "notifications", "quick", "lock", "power"];
const PKG = /^[A-Za-z0-9._]{1,200}$/, MAX_ICONS = 400;
// icon URLs end up in request logs: the page sends ?k=sha256("icon:" + token) (only opens icons), stored at the first authenticated request
const ICON_KEY = /^[0-9a-f]{64}$/;
// <img> can't send headers: only the icon GET may carry the token in ?k=
const tokenOf = (req, url) => req.headers.get("x-kg-token") || (req.method === "GET" && url.pathname.endsWith("/icon") ? url.searchParams.get("k") || "" : "");
// shared files (docs/protocol.md "Shared files"): <= 10 MB a file in 1 MiB chunks, kept 1 h, deleted once fetched
const FILE_CHUNK = 1048576, FILE_MAX = 10 * 1048576, FILE_TTL = 3600e3, FILE_N = 8, FILE_TOTAL = 32 * 1048576, FID = /^[0-9a-f]{16}$/;
const limitFor = (action) => action === "frame" ? MAX_FRAME : action === "file" ? FILE_CHUNK : MAX_BODY;
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
});
const ONCE = ["reboot", "release", "unpairAll", "unpair", "clearData", "update", "totpSet", "fileDel", "tgSet", "tgOff", "pinSet", "pinReset", "pinResetCancel", "adbShell", "adbTcp"];   // destructive: handed out until acked, but only within ONCE_WINDOW of the first hand-out
const ONCE_WINDOW = 10 * 60e3;
const GC_MS = 30 * 86400e3;   // a phone not seen for 30 days: everything of this id is deleted (dead pairings, junk binds); a phone that comes back re-binds on its next sync
const SHORT = ["lock", "locate", "message", "ring", "wake", "ringStop", "listenStop", "devOn", "devOff", "setting", "reboot", "clearData", "pinSet", "pinReset", "pinResetCancel", "adbShell", "adbTcp"];   // "now" commands: stale after SHORT_TTL (a reboot / clear asked while the phone was off must not fire the next day)
const SHORT_TTL = 10 * 60e3;
// still wanted? (24 h overall; "now" commands 10 min; a destructive one only shortly after it was first handed to the phone)
const live = (c, now) => now - (c.at || 0) < (SHORT.includes(c.type) ? SHORT_TTL : 86400e3) && !(c.sent && now - c.sent > ONCE_WINDOW);
const CMDS = ["lock", "locate", "message", "ring", "openPairing", "unpairAll", "unpair", "release", "answer", "reboot", "tailscaleOff", "sosAck", "clearData", "update", "totpSet", "wake", "ringStop", "listenStop", "devOff", "devOn", "setting", "fileUp", "fileDel", "shotUp", "tgSet", "tgCode", "tgUnlink", "tgOff", "pinSet", "pinReset", "pinResetCancel", "support", "supportSay", "adbShell", "shClear", "adbTcp"];
const SETTING_KEYS = ["vol.ring", "vol.media", "vol.alarm", "bright", "autoBright", "timeoutMs", "dnd"];
const isObj = (o) => o && typeof o === "object" && !Array.isArray(o);
// 'self': the parent page's /ui/ files; 'unsafe-inline': install/qr inline scripts + a few inline handlers in the parent page; the installer imports ESM from jsDelivr and downloads the APK
const CSP = [
  "default-src 'none'", "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net", "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://tile.openstreetmap.de https://tile.openstreetmap.org", "font-src 'self' data:",
  "connect-src 'self' https://cdn.jsdelivr.net https://github.com https://objects.githubusercontent.com",
  "frame-src https://www.openstreetmap.org", "worker-src 'self' blob:", "object-src 'none'", "base-uri 'none'",
  "form-action 'self'", "frame-ancestors 'none'",
].join("; ");
const stripPin = (r) => {
  if (!isObj(r)) return r;
  const { pinHash, pin, ...o } = r;
  o.pinSet = !!pinHash || r.pinSet === true;
  return o;
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    // /go#c=<id>&k=<token>: the same page; core.js sends the parent to the phone's LAN address when both are on one network
    const page = req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html" || url.pathname === "/go") ? html
      : req.method === "GET" && url.pathname === "/install" ? install
      : req.method === "GET" && url.pathname === "/qr" ? qr : null;
    if (req.method === "GET" && url.pathname.startsWith("/ui/")) {
      const n = url.pathname.slice(4), body = Object.hasOwn(UI, n) ? UI[n] : null;
      if (body === null) return json({ error: "not found" }, 404);
      return new Response(body, { headers: {   // no-cache: always the same version as the page that loads it
        "content-type": (n.endsWith(".css") ? "text/css" : "text/javascript") + "; charset=utf-8", "cache-control": "no-cache",
        "x-content-type-options": "nosniff" } });
    }
    if (page) {
      return new Response(page, { headers: {
        "content-type": "text/html; charset=utf-8", "cache-control": "no-cache",
        "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
        "x-frame-options": "DENY", "content-security-policy": CSP,
      } });
    }
    const th = req.method === "POST" && env.TG_BOT ? url.pathname.match(/^\/tg\/([0-9a-f]{32})$/) : null;
    if (th) return tgHook(req, env, th[1]);
    const m = url.pathname.match(/^\/c\/([0-9a-f]{16})\/(sync|state|rules|cmd|live|frame|input|icons|icon|push|device|file|tg|where|ws)$/);
    if (!m) return json({ error: "not found" }, 404);
    if (env.RL) {
      const { success } = await env.RL.limit({ key: req.headers.get("cf-connecting-ip") || "?" });
      if (!success) return json({ error: "slow down" }, 429);
    }
    // reject junk before waking a Durable Object (an icon GET may carry the icon-only key instead of the token)
    if (!/^[0-9a-f]{48}$/.test(tokenOf(req, url)) && !(m[2] === "icon" && ICON_KEY.test(url.searchParams.get("k") || ""))) return json({ error: "not paired" }, 401);
    const len = +(req.headers.get("content-length") || 0);
    if (len > limitFor(m[2])) return json({ error: "too big" }, 413);
    const stub = env.KIDS.get(env.KIDS.idFromName(m[1]));
    return stub.fetch(req);
  },
};

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

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export class Kid {
  constructor(ctx, env) {
    this.ctx = ctx; this.s = ctx.storage; this.env = env || {};
    this.liveUntil = 0; this.frame = null; this.seq = Date.now(); this.seen = 0; this.seenWritten = 0; this.inputs = []; this.waiters = new Set();
    this.deviceWant = 0;   // parent asked for a device-health snapshot at this ms (memory only, like liveUntil)
  }

  /** Phone connected over the WebSocket (hibernation keeps it across evictions). */
  phoneWs() { return this.ctx.getWebSockets("phone"); }
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
    if (res.status < 300 && action !== "sync" && action !== "ws" && ((m === "POST" && ["cmd", "live", "device", "file"].includes(action)) || (m === "PUT" && action === "rules")
        || (m === "GET" && action === "state" && Date.now() - (this.stateAt || 0) > 30e3))) this.kickPhone();
    return res;
  }

  async handle(req) {
    const url = new URL(req.url), action = url.pathname.split("/").pop(), id = url.pathname.split("/")[2];
    if (action === "_tg" && req.method === "POST" && req.headers.get("x-kg-internal") === "1") return this.tgIn(await req.json().catch(() => ({})), id);   // from tgHook only: the public route regex never matches `_tg`
    const token = tokenOf(req, url);
    if (action === "icon" && req.method === "GET" && ICON_KEY.test(url.searchParams.get("k") || "")) {
      const ik = await this.s.get("iconKey");
      if (!ik || ik !== url.searchParams.get("k")) return json({ error: "not paired" }, 401);
      return this.icon(url);
    }
    if (!/^[0-9a-f]{48}$/.test(token)) return json({ error: "not paired" }, 401);
    const h = await sha256(token);
    let bound = await this.s.get("tokenHash");
    if (!bound) {
      if (action !== "sync") return json({ error: "waiting for the phone" }, 409);   // only the phone may bind
      // ...and only with a real first sync (it always carries a state): a random POST must not claim / create storage
      let first = null; try { first = await req.clone().json(); } catch {}
      if (!first || typeof first.state !== "object" || !first.state) return json({ error: "bad first sync" }, 400);
      await this.s.put("tokenHash", h); bound = h;
    }
    if (h !== bound) return json({ error: "not paired" }, 401);
    if (!this.ikSet) { this.ikSet = true; const ik = await sha256("icon:" + token); if ((await this.s.get("iconKey")) !== ik) await this.s.put("iconKey", ik); }
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
      for (const [pkg, b64] of Object.entries(o)) {
        if (!PKG.test(pkg) || typeof b64 !== "string" || b64.length > 16000 || !/^[A-Za-z0-9+/=]+$/.test(b64)) continue;
        if (!list.has(pkg) && list.size >= MAX_ICONS) continue;
        list.add(pkg); puts["icon:" + pkg] = b64;
      }
      puts.iconList = [...list];
      await this.s.put(puts);
      return json({ have: list.size });
    }
    if (action === "icon" && req.method === "GET") return this.icon(url);   // older pages: the token in ?k=
    if (action === "frame") {
      if (req.method === "POST") {
        const buf = await req.arrayBuffer();
        if (buf.byteLength > MAX_FRAME) return json({ error: "too big" }, 413);
        const err = (req.headers.get("x-kg-err") || "").slice(0, 60);
        const jpeg = buf.byteLength ? buf : null;
        this.frame = { seq: ++this.seq, jpeg, w: +req.headers.get("x-kg-w") || 0, h: +req.headers.get("x-kg-h") || 0, at: now, err: jpeg ? "" : err || "no frame" };
        for (const w of this.waiters) w();
        const inputs = this.inputs.filter(i => now - i.at <= 5000).map(({ at, ...i }) => i); this.inputs = [];
        return json({ inputs, live: this.liveUntil > now });
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
    if (action === "file") return this.file(req, url, now);
    let body = null;
    if (req.method !== "GET") {
      const buf = await req.arrayBuffer();
      if (buf.byteLength > limitFor(action)) return json({ error: "too big" }, 413);
      body = (() => { try { return JSON.parse(new TextDecoder().decode(buf)); } catch { return null; } })();
    }
    if (req.method !== "GET" && !isObj(body)) return json({ error: "bad json" }, 400);

    // device health ("Máy" view): parent POST {} asks (the next sync reply says "device": true) and gets the last one
    // stored; the phone PUTs the snapshot. GET only reads. Stored (<= 32 KB) so the page shows the last one at once.
    if (action === "device") {
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
    if (action === "live") {
      if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
      this.liveUntil = now + 20e3;
      return json({ ok: true, phone: this.frame ? now - this.frame.at : -1 });
    }
    if (action === "input") {
      if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
      const t = body.type, f01 = (v) => typeof v === "number" && v >= 0 && v <= 1;
      const ok = (t === "tap" || t === "long") ? f01(body.x) && f01(body.y)
        : t === "swipe" ? f01(body.x1) && f01(body.y1) && f01(body.x2) && f01(body.y2)
        : t === "key" ? KEYS.includes(body.key)
        : t === "text" ? typeof body.text === "string" && body.text.length <= 500 : false;
      if (!ok) return json({ error: "bad input" }, 400);
      if (this.liveUntil < now) return json({ error: "not live" }, 409);
      if (this.inputs.length >= 50) return json({ error: "queue full" }, 429);
      const o = { type: t, at: now };
      for (const k of ["x", "y", "x1", "y1", "x2", "y2", "key", "text"]) if (k in body) o[k] = body[k];
      if (t === "swipe") o.ms = Math.min(Math.max(+body.ms || 300, 50), 3000);   // same clamp as Acc.kt
      this.inputs.push(o);
      return json({ result: "queued" });
    }

    if (action === "sync" && req.method === "POST") {
      const out = await this.sync(body, id, netKey(req.headers.get("cf-connecting-ip")), now);
      return out.error ? json({ error: out.error }, out.status) : json(out);
    }
    if (action === "where" && req.method === "GET") {   // /go: where the phone is reachable now
      const [state, seen, ipk] = await Promise.all(["state", "seen", "ipk"].map(k => this.s.get(k)));
      const nk = netKey(req.headers.get("cf-connecting-ip"));
      const same = !!nk && (ipk || []).includes(await sha256("net:" + nk));
      const ts = state && state.ts && typeof state.ts.ip === "string" && /^100\.[\d.]+$/.test(state.ts.ip) ? state.ts.ip : "";
      const lan = state && typeof state.lan === "string" && /^http:\/\/[\d.]+:\d+$/.test(state.lan) ? state.lan : "";
      return json({ seen: this.seenNow(seen), lan, ts, same, mode: state && state.mode || "" });
    }
    if (action === "state" && req.method === "GET") {
      const [state, rules, seen, cmds] = await Promise.all(["state", "rules", "seen", "cmds"].map(k => this.s.get(k)));
      const st = state ? { ...state, rules: stripPin(state.rules) } : null;
      return json({ state: st, rules: rules ? stripPin(rules) : null, seen: this.seenNow(seen), pending: (cmds || []).length });
    }
    if (action === "rules" && req.method === "PUT") {
      const [state, old] = await Promise.all([this.s.get("state"), this.s.get("rules")]);
      const rev = Math.max(Date.now(), (old && old.rev || 0) + 1, (state && state.rules && state.rules.rev || 0) + 1);
      // the sync reply carries rules + queued commands and the phone refuses replies over 256 KB: keep both small
      if (JSON.stringify(body).length > MAX_RULES) return json({ error: "too big" }, 413);
      const rules = { ...body, rev };
      // a new PIN arrives in plain: keep only the hash the phone uses (sha256 of "kg:" + PIN). No PIN given: keep the old hash.
      const pin = String(rules.pin || "").replace(/\D/g, "");
      // pinAt: when the hash last changed, so the phone takes it once and a newer PIN set on LAN is not reverted
      if (pin.length >= 4) { rules.pinHash = await sha256("kg:" + pin); rules.pinAt = rev; }
      else if (typeof rules.pinHash === "string" && /^[0-9a-f]{64}$/.test(rules.pinHash)) rules.pinAt = rev;
      else if (old && old.pinHash) { rules.pinHash = old.pinHash; rules.pinAt = old.pinAt || 1; }
      else { delete rules.pinHash; delete rules.pinAt; }
      delete rules.pin; delete rules.pinSet;
      await this.s.put("rules", rules);
      return json(stripPin(rules));
    }
    if (action === "push" && req.method === "POST") {   // parent app: {fcm: token} registers, {fcm, off: true} removes
      const t = typeof body.fcm === "string" ? body.fcm : "";
      if (!FCM_TOKEN.test(t)) return json({ error: "bad token" }, 400);
      const list = ((await this.s.get("push")) || []).filter(x => x !== t);
      if (!body.off) { list.push(t); while (list.length > MAX_PUSH) list.shift(); }
      await this.s.put("push", list);
      return json({ ok: true, enabled: !!this.env.FCM_SA, devices: list.length });
    }
    if (action === "tg") {   // Telegram alerts: GET status; POST {name, lang} -> one-time link; POST {off: true} unlinks
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
    if (action === "cmd" && req.method === "POST") {
      const type = String(body.type || "");
      if (!CMDS.includes(type)) return json({ error: "bad command" }, 400);
      if (type === "clearData" && !/^[A-Za-z0-9._]{1,200}$/.test(String(body.pkg || ""))) return json({ error: "bad pkg" }, 400);
      // PIN-gated commands (protocol "PIN-gated commands"): the phone checks the PIN; here only the shape
      if (body.pin !== undefined && !/^\d{0,8}$/.test(String(body.pin))) return json({ error: "bad pin" }, 400);
      if ((type === "pinSet" || type === "pinReset") && !/^\d{4,8}$/.test(String(body.new || ""))) return json({ error: "bad pin" }, 400);
      if (type === "setting" && !(SETTING_KEYS.includes(body.key) && (typeof body.value === "boolean" || Number.isFinite(body.value)))) return json({ error: "bad setting" }, 400);
      // expired commands are only dropped on a phone sync: don't let them fill the queue
      const cmds = ((await this.s.get("cmds")) || []).filter(c => live(c, now));
      // full queue: refuse instead of silently dropping the oldest (it may be a pending release)
      if (cmds.length >= MAX_CMDS) return json({ error: "queue full" }, 429);
      const c = { ...body, type, id: typeof body.id === "string" && /^[\w.-]{1,64}$/.test(body.id) ? body.id : crypto.randomUUID(), at: now };
      delete c.sent;
      // `answer`: the request id may not be a legal command id; keep it apart so the phone never answers the wrong request
      if (type === "answer") c.req = String(body.req || body.id || "").slice(0, 200);
      if (JSON.stringify(c).length > MAX_CMD) return json({ error: "too big" }, 413);
      cmds.push(c);
      await this.s.put("cmds", cmds);
      return json({ result: "queued" });
    }
    return json({ error: "method not allowed" }, 405);
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
  async icon(url) {
    const pkg = url.searchParams.get("pkg") || "";
    const b64 = PKG.test(pkg) ? await this.s.get("icon:" + pkg) : null;
    if (!b64) return json({ error: "no icon" }, 404);
    return new Response(Uint8Array.from(atob(b64), c => c.charCodeAt(0)), { headers: {
      "content-type": "image/webp", "cache-control": "private, max-age=86400", "x-content-type-options": "nosniff" } });
  }

  /** Storage GC: armed once per object wake by sync(); see GC_MS. */
  async alarm() {
    const now = Date.now(), seen = this.phoneWs().length ? now : Math.max((await this.s.get("seen")) || 0, this.seen || 0);
    if (now - seen > GC_MS) { await this.s.deleteAll(); return; }
    await this.s.setAlarm(seen + GC_MS);
  }

  async sync(body, id, nk, now, ws = null) {
    if (!this.gcArmed) { this.gcArmed = true; if (!(await this.s.getAlarm())) await this.s.setAlarm(now + GC_MS); }
    let state = body.state && typeof body.state === "object" ? body.state : null;
    // a partial state (only what changed since the last sync, see mergeState) is applied on the stored one;
    // nothing stored (first sync after a GC, a wiped object) => ask the phone for a whole one
    let needWhole = false;
    if (state && state._part) {
      const old = await this.s.get("state");
      if (old) state = mergeState(old, state); else { state = null; needWhole = true; }
    }
    if (state && new TextEncoder().encode(JSON.stringify(state)).length > 120e3) return { error: "state too big", status: 413 };   // DO storage value limit is 128 KiB
    const acks = new Set(Array.isArray(body.acks) ? body.acks.map(String) : []);
    // awaits before the cmds read-modify-write: a parent POST /cmd landing in an await must not be overwritten by a stale list
    const netHash = nk && nk !== this.lastNet ? await sha256("net:" + nk) : null;
    const stored = (await this.s.get("cmds")) || [];
    const cmds = stored.filter(c => !acks.has(c.id) && live(c, now));
    const rules = await this.s.get("rules");
    const puts = {};
    // destructive commands stay queued until acked (a lost reply must not drop a release); the phone de-dups by id.
    // `sent` marks the first hand-out: after ONCE_WINDOW they are dropped even if never acked (e.g. the phone released).
    let changed = cmds.length !== stored.length;
    for (const c of cmds) if (ONCE.includes(c.type) && !c.sent) { c.sent = now; changed = true; }
    if (changed) puts.cmds = cmds;
    this.seen = now;
    // over the WebSocket "seen" = connected (seenNow); written once on close instead of every minute
    if (!ws && now - this.seenWritten >= 60e3) { puts.seen = now; this.seenWritten = now; }
    // the phone's public network (hashed, last 2: Wi-Fi v4 + v6), for /where "same network"
    if (netHash) {
      this.lastNet = nk;
      const h = netHash, ipk = (await this.s.get("ipk")) || [];
      if (ipk[0] !== h) puts.ipk = [h, ...ipk.filter(x => x !== h)].slice(0, 2);
    }
    if (state && Object.keys(state).length) { puts.state = state; this.stateAt = now; }
    if (Object.keys(puts).length) await this.s.put(puts);
    if (state) this.ctx.waitUntil(this.pushNew(state, id).catch(e => console.log("push", String(e))));
    const out = { cmds, merge: 1 };   // merge: this worker takes partial states (the phone sends whole ones until it sees it)
    if (needWhole) out.full = true;
    if (this.liveUntil > now) out.live = true;
    const files = (await this.fileGc(now)).filter(f => f.to === "kid" && f.got.length === f.n).length;
    if (files) out.files = files;   // the phone then fetches them from /file
    if (this.deviceWant && now - this.deviceWant < 120e3) out.device = true;
    const known = state || (await this.s.get("state"));   // the phone omits state when unchanged
    const have = known && known.rules ? +known.rules.rev || 0 : 0;
    if (rules && rules.rev > have) out.rules = rules;
    return out;
  }

  async webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > MAX_BODY) return;
    let b; try { b = JSON.parse(msg); } catch { return; }
    if (!isObj(b) || b.t !== "sync") return;
    const a = ws.deserializeAttachment() || {}, now = Date.now();
    a.at = now; ws.serializeAttachment(a);
    const out = await this.sync(b, a.id, a.nk, now, ws);
    try { ws.send(JSON.stringify({ t: "sync", n: b.n, ...out })); } catch {}
  }
  async webSocketClose(ws) { await this.closed(ws); }
  async webSocketError(ws) { await this.closed(ws); }
  async closed(ws) {
    const a = ws.deserializeAttachment() || {};
    let p = 0; try { p = +(this.ctx.getWebSocketAutoResponseTimestamp(ws) || 0); } catch {}
    try { ws.close(1011, "closed"); } catch {}
    const last = Math.max(a.at || 0, p, this.seen);   // the phone's last sign of life, not the moment we noticed
    if (last) { this.seen = last; this.seenWritten = Date.now(); await this.s.put("seen", last); }
  }

  async file(req, url, now) {
    const q = url.searchParams, fid = q.get("fid") || "";
    const buf = req.method === "POST" ? await req.arrayBuffer() : null;   // before any storage read: no other await between get and put below
    if (buf && buf.byteLength > FILE_CHUNK) return json({ error: "too big" }, 413);
    const list = await this.fileGc(now);
    if (req.method === "GET" && !fid) {
      const to = q.get("to");
      return json({ files: list.filter(f => f.to === to).map(f => ({ fid: f.fid, name: f.name, size: f.size, n: f.n, at: f.at, done: f.got.length === f.n })), max: FILE_MAX, chunk: FILE_CHUNK });
    }
    if (!FID.test(fid)) return json({ error: "bad id" }, 400);
    let f = list.find(x => x.fid === fid);
    const key = (i) => "fc:" + fid + ":" + i;
    if (req.method === "POST") {
      if (!f) {
        const to = q.get("to"), size = Number(q.get("size")), n = Number(q.get("n")), name = String(q.get("name") || "").slice(0, 200);
        if (!["kid", "parent"].includes(to) || !name || !Number.isInteger(size) || size < 0) return json({ error: "bad file" }, 400);
        if (size > FILE_MAX) return json({ error: "too big" }, 413);
        if (n !== Math.max(1, Math.ceil(size / FILE_CHUNK))) return json({ error: "bad file" }, 400);
        if (list.length >= FILE_N || list.reduce((s, x) => s + x.size, 0) + size > FILE_TOTAL) return json({ error: "relay full" }, 507);
        f = { fid, to, name, size, n, at: now, got: [] }; list.push(f);
      }
      const i = Number(q.get("i"));
      if (!Number.isInteger(i) || i < 0 || i >= f.n) return json({ error: "bad chunk" }, 400);
      if (buf.byteLength !== (i < f.n - 1 ? FILE_CHUNK : f.size - FILE_CHUNK * (f.n - 1))) return json({ error: "bad chunk" }, 400);
      if (!f.got.includes(i)) f.got.push(i);
      await this.s.put({ [key(i)]: buf, files: list });
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

  /** New SOS / more-time requests in the phone's state -> FCM data message to every registered parent app. */
  async pushNew(state, id) {
    const now = Date.now(), ev = [];
    for (const s of Array.isArray(state.sos) ? state.sos : []) if (s && now - +s.t < 6 * 3600e3) ev.push({ kind: "sos", eid: "s:" + s.id, t: +s.t });
    for (const r of Array.isArray(state.requests) ? state.requests : []) if (r && now - +r.t < 3600e3) ev.push({ kind: "req", eid: "r:" + r.id, t: +r.t, min: +r.min || 0 });
    for (const e of Array.isArray(state.events) ? state.events : []) if (e && now - +e.t < 3600e3 && /^[\w-]{1,24}$/.test(String(e.id)))
      ev.push({ kind: "ev", eid: "e:" + e.id, t: +e.t, ek: String(e.kind || "").slice(0, 12) });   // no text / zone name to Google
    if (!ev.length) return;
    await this.tgNew(ev).catch(e => console.log("tg", String(e)));
    if (!this.env.FCM_SA) return;
    // at most 5 events a sync (x 5 devices = 25 FCM calls, under the 50 subrequests a request may make)
    const sent = (await this.s.get("pushed")) || [], fresh = ev.filter(e => !sent.includes(e.eid)).slice(0, 5);
    if (!fresh.length) return;
    const tokens = (await this.s.get("push")) || [];
    await this.s.put("pushed", [...sent, ...fresh.map(e => e.eid)].slice(-50));   // before sending: a retry must not double-notify
    if (!tokens.length) return;
    const dead = new Set();
    for (const e of fresh) for (const t of tokens) {
      const st = await fcmSend(this.env, t, { kind: e.kind, kid: id, eid: e.eid, t: String(e.t), ...(e.kind === "ev" ? { ek: e.ek } : { min: String(e.min || "") }) });
      if (st === 404) dead.add(t);   // UNREGISTERED: the app was removed or re-installed (a 400 may be our own config: keep the token)
    }
    if (dead.size) await this.s.put("push", tokens.filter(t => !dead.has(t)));
  }
}

/**
 * Applies a partial state from the phone (bandwidth, owner 2026-11-11: whole states were ~20 MB/day) on [old]:
 * top-level keys replace, `_drop` removes keys, `_apps` = {set: [app objects], del: [pkg]} merged by pkg (then sorted by
 * use like the phone does), `_trk` = {from, add}: points appended (only newer than the last stored one: a resend after a
 * lost reply must not double them), then points older than `from` (the phone's 2-day window) dropped.
 */
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
const FCM_TOKEN = /^[\w:.-]{20,400}$/, MAX_PUSH = 5;
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
async function fcmSend(env, token, data) {
  const sa = JSON.parse(env.FCM_SA);
  const at = await fcmToken(sa);
  const url = (env.FCM_URL || "https://fcm.googleapis.com") + `/v1/projects/${sa.project_id}/messages:send`;
  const r = await fetch(url, { method: "POST", headers: { authorization: "Bearer " + at, "content-type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl: "21600s" } } }) });
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
    on: (n, id) => `Đã bật báo qua Telegram cho ${n}. Để huỷ: gửi /stop_${id} hoặc bấm "Huỷ liên kết" trên trang KidGuard.`,
    off: n => `Đã huỷ báo qua Telegram cho ${n}.`,
    help: "Mở trang KidGuard (Cài đặt > Báo qua Telegram) rồi bấm nút liên kết.",
  },
  en: {
    sos: n => `SOS: ${n} pressed the SOS button! Open KidGuard to see the location.`,
    req: (n, min) => `${n} asks for ${min ? min + " more minutes" : "more time"}. Open KidGuard to answer.`,
    newApp: n => `${n}: a new app was installed on the phone.`,
    tamper: n => `${n}: someone tried to tamper with the phone's settings.`,
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

// KidGuard relay: lets the parent's page reach the child phone when they are not on the same Wi-Fi.
// One Durable Object per child (by relay id). It stores the last state the child posted, the newest rules the
// parent set, and a short queue of commands. The child polls POST /c/<id>/sync every 20-60 s.
// Auth: the token in the parent's link; the first child sync binds its sha256 to the id.
import html from "../../web/index.html";
import install from "../../web/install.html";
import qr from "../../web/qr.html";

const MAX_BODY = 256 * 1024;
const MAX_FRAME = 1024 * 1024;
const MAX_RULES = 64 * 1024, MAX_CMD = 4096, MAX_CMDS = 20;   // 64 KB + 20 x 4 KB stays far below the phone's 256 KB reply cap
const KEYS = ["back", "home", "recents", "notifications", "quick", "lock", "power"];
const PKG = /^[A-Za-z0-9._]{1,200}$/, MAX_ICONS = 400;
// <img> can't send headers: only the icon GET may carry the token in ?k=
const tokenOf = (req, url) => req.headers.get("x-kg-token") || (req.method === "GET" && url.pathname.endsWith("/icon") ? url.searchParams.get("k") || "" : "");
const limitFor = (action) => action === "frame" ? MAX_FRAME : MAX_BODY;
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
});
const ONCE = ["reboot", "release", "unpairAll", "clearData", "update"];   // deliver-once: dropped from the queue when handed to the phone
const CMDS = ["lock", "locate", "message", "ring", "openPairing", "unpairAll", "release", "answer", "reboot", "tailscaleOff", "sosAck", "clearData", "update"];
const isObj = (o) => o && typeof o === "object" && !Array.isArray(o);
// inline scripts/styles are needed (single-file pages); the installer imports ESM from jsDelivr and downloads the APK
const CSP = [
  "default-src 'none'", "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net", "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https://tile.openstreetmap.org", "font-src 'self' data:",
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
    const page = req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html") ? html
      : req.method === "GET" && url.pathname === "/install" ? install
      : req.method === "GET" && url.pathname === "/qr" ? qr : null;
    if (page) {
      return new Response(page, { headers: {
        "content-type": "text/html; charset=utf-8", "cache-control": "no-cache",
        "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
        "x-frame-options": "DENY", "content-security-policy": CSP,
      } });
    }
    const m = url.pathname.match(/^\/c\/([0-9a-f]{16})\/(sync|state|rules|cmd|live|frame|input|icons|icon|push)$/);
    if (!m) return json({ error: "not found" }, 404);
    if (env.RL) {
      const { success } = await env.RL.limit({ key: req.headers.get("cf-connecting-ip") || "?" });
      if (!success) return json({ error: "slow down" }, 429);
    }
    // reject junk before waking a Durable Object
    if (!/^[0-9a-f]{48}$/.test(tokenOf(req, url))) return json({ error: "not paired" }, 401);
    const len = +(req.headers.get("content-length") || 0);
    if (len > limitFor(m[2])) return json({ error: "too big" }, 413);
    const stub = env.KIDS.get(env.KIDS.idFromName(m[1]));
    return stub.fetch(req);
  },
};

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export class Kid {
  constructor(ctx, env) {
    this.ctx = ctx; this.s = ctx.storage; this.env = env || {};
    this.liveUntil = 0; this.frame = null; this.seq = Date.now(); this.seen = 0; this.seenWritten = 0; this.inputs = []; this.waiters = new Set();
  }

  async fetch(req) {
    const url = new URL(req.url), action = url.pathname.split("/").pop(), id = url.pathname.split("/")[2];
    const token = tokenOf(req, url);
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
    const now = Date.now();
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
    if (action === "icon" && req.method === "GET") {   // parent page <img>: token in ?k=
      const pkg = url.searchParams.get("pkg") || "";
      const b64 = PKG.test(pkg) ? await this.s.get("icon:" + pkg) : null;
      if (!b64) return json({ error: "no icon" }, 404);
      return new Response(Uint8Array.from(atob(b64), c => c.charCodeAt(0)), { headers: {
        "content-type": "image/webp", "cache-control": "private, max-age=86400", "x-content-type-options": "nosniff" } });
    }
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
    let body = null;
    if (req.method !== "GET") {
      const buf = await req.arrayBuffer();
      if (buf.byteLength > limitFor(action)) return json({ error: "too big" }, 413);
      body = (() => { try { return JSON.parse(new TextDecoder().decode(buf)); } catch { return null; } })();
    }
    if (req.method !== "GET" && !isObj(body)) return json({ error: "bad json" }, 400);

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
      if (t === "swipe") o.ms = Math.min(Math.max(+body.ms || 300, 50), 2000);
      this.inputs.push(o);
      return json({ result: "queued" });
    }

    if (action === "sync" && req.method === "POST") {
      const state = body.state && typeof body.state === "object" ? body.state : null;
      if (state && JSON.stringify(state).length > 120e3) return json({ error: "state too big" }, 413);   // DO storage value limit is 128 KiB
      const acks = new Set(Array.isArray(body.acks) ? body.acks.map(String) : []);
      const stored = (await this.s.get("cmds")) || [];
      const cmds = stored.filter(c => !acks.has(c.id) && now - c.at < 86400e3);
      const rules = await this.s.get("rules");
      const puts = {};
      const keep = cmds.filter(c => !ONCE.includes(c.type));   // deliver-once commands leave the queue now
      if (keep.length !== stored.length) puts.cmds = keep;
      this.seen = now;
      if (now - this.seenWritten >= 60e3) { puts.seen = now; this.seenWritten = now; }
      if (state && Object.keys(state).length) puts.state = state;
      if (Object.keys(puts).length) await this.s.put(puts);
      if (state) this.ctx.waitUntil(this.pushNew(state, id).catch(e => console.log("push", String(e))));
      const out = { cmds };
      if (this.liveUntil > now) out.live = true;
      const known = state || (await this.s.get("state"));   // the phone omits state when unchanged
      const have = known && known.rules ? +known.rules.rev || 0 : 0;
      if (rules && rules.rev > have) out.rules = rules;
      return json(out);
    }
    if (action === "state" && req.method === "GET") {
      const [state, rules, seen, cmds] = await Promise.all(["state", "rules", "seen", "cmds"].map(k => this.s.get(k)));
      const st = state ? { ...state, rules: stripPin(state.rules) } : null;
      return json({ state: st, rules: rules ? stripPin(rules) : null, seen: Math.max(seen || 0, this.seen), pending: (cmds || []).length });
    }
    if (action === "rules" && req.method === "PUT") {
      const [state, old] = await Promise.all([this.s.get("state"), this.s.get("rules")]);
      const rev = Math.max(Date.now(), (old && old.rev || 0) + 1, (state && state.rules && state.rules.rev || 0) + 1);
      // the sync reply carries rules + queued commands and the phone refuses replies over 256 KB: keep both small
      if (JSON.stringify(body).length > MAX_RULES) return json({ error: "too big" }, 413);
      const rules = { ...body, rev };
      // a new PIN arrives in plain: keep only the hash the phone uses (sha256 of "kg:" + PIN). No PIN given: keep the old hash.
      const pin = String(rules.pin || "").replace(/\D/g, "");
      if (pin.length >= 4) rules.pinHash = await sha256("kg:" + pin);
      else if (typeof rules.pinHash === "string" && /^[0-9a-f]{64}$/.test(rules.pinHash)) { /* explicit hash */ }
      else if (old && old.pinHash) rules.pinHash = old.pinHash;
      else delete rules.pinHash;
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
    if (action === "cmd" && req.method === "POST") {
      const type = String(body.type || "");
      if (!CMDS.includes(type)) return json({ error: "bad command" }, 400);
      if (type === "clearData" && !/^[A-Za-z0-9._]{1,200}$/.test(String(body.pkg || ""))) return json({ error: "bad pkg" }, 400);
      const cmds = (await this.s.get("cmds")) || [];
      // full queue: refuse instead of silently dropping the oldest (it may be a pending release)
      if (cmds.length >= MAX_CMDS) return json({ error: "queue full" }, 429);
      const c = { ...body, type, id: typeof body.id === "string" && /^[\w.-]{1,64}$/.test(body.id) ? body.id : crypto.randomUUID(), at: now };
      if (JSON.stringify(c).length > MAX_CMD) return json({ error: "too big" }, 413);
      cmds.push(c);
      await this.s.put("cmds", cmds);
      return json({ result: "queued" });
    }
    return json({ error: "method not allowed" }, 405);
  }

  /** New SOS / more-time requests in the phone's state -> FCM data message to every registered parent app. */
  async pushNew(state, id) {
    const now = Date.now(), ev = [];
    for (const s of Array.isArray(state.sos) ? state.sos : []) if (s && now - +s.t < 6 * 3600e3) ev.push({ kind: "sos", eid: "s:" + s.id, t: +s.t });
    for (const r of Array.isArray(state.requests) ? state.requests : []) if (r && now - +r.t < 3600e3) ev.push({ kind: "req", eid: "r:" + r.id, t: +r.t, min: +r.min || 0 });
    for (const e of Array.isArray(state.events) ? state.events : []) if (e && now - +e.t < 3600e3 && /^[\w-]{1,24}$/.test(String(e.id)))
      ev.push({ kind: "ev", eid: "e:" + e.id, t: +e.t, ek: String(e.kind || "").slice(0, 12) });   // no text / zone name to Google
    if (!ev.length || !this.env.FCM_SA) return;
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

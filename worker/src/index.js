// KidGuard relay: lets the parent's page reach the child phone when they are not on the same Wi-Fi.
// One Durable Object per child (by relay id). It stores the last state the child posted, the newest rules the
// parent set, and a short queue of commands. The child polls POST /c/<id>/sync every 20-60 s.
// Auth: the token in the parent's link; the first child sync binds its sha256 to the id.
import html from "../../web/index.html";
import install from "../../web/install.html";

const MAX_BODY = 256 * 1024;
const MAX_FRAME = 1024 * 1024;
const KEYS = ["back", "home", "recents", "notifications", "quick", "lock", "power"];
const limitFor = (action) => action === "frame" ? MAX_FRAME : MAX_BODY;
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
});

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const page = req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html") ? html
      : req.method === "GET" && url.pathname === "/install" ? install : null;
    if (page) {
      return new Response(page, { headers: {
        "content-type": "text/html; charset=utf-8", "cache-control": "no-cache",
        "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
      } });
    }
    const m = url.pathname.match(/^\/c\/([0-9a-f]{16})\/(sync|state|rules|cmd|live|frame|input)$/);
    if (!m) return json({ error: "not found" }, 404);
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
  constructor(ctx) {
    this.ctx = ctx; this.s = ctx.storage;
    this.liveUntil = 0; this.frame = null; this.seq = 0; this.inputs = []; this.waiters = new Set();
  }

  async fetch(req) {
    const action = new URL(req.url).pathname.split("/").pop();
    const token = req.headers.get("x-kg-token") || "";
    if (!/^[0-9a-f]{48}$/.test(token)) return json({ error: "not paired" }, 401);
    const h = await sha256(token);
    let bound = await this.s.get("tokenHash");
    if (!bound) {
      if (action !== "sync") return json({ error: "waiting for the phone" }, 409);   // only the phone may bind
      await this.s.put("tokenHash", h); bound = h;
    }
    if (h !== bound) return json({ error: "not paired" }, 401);
    const now = Date.now();
    if (action === "frame") {
      if (req.method === "POST") {
        const buf = await req.arrayBuffer();
        if (buf.byteLength > MAX_FRAME) return json({ error: "too big" }, 413);
        const err = (req.headers.get("x-kg-err") || "").slice(0, 60);
        const jpeg = buf.byteLength ? buf : null;
        this.frame = { seq: ++this.seq, jpeg, w: +req.headers.get("x-kg-w") || 0, h: +req.headers.get("x-kg-h") || 0, at: now, err: jpeg ? "" : err || "no frame" };
        for (const w of this.waiters) w();
        const inputs = this.inputs; this.inputs = [];
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
    if (req.method !== "GET" && !body) return json({ error: "bad json" }, 400);

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
      if (this.inputs.length >= 50) return json({ error: "queue full" }, 429);
      const o = { type: t };
      for (const k of ["x", "y", "x1", "y1", "x2", "y2", "key", "text"]) if (k in body) o[k] = body[k];
      if (t === "swipe") o.ms = Math.min(Math.max(+body.ms || 300, 50), 2000);
      this.inputs.push(o);
      return json({ result: "queued" });
    }

    if (action === "sync" && req.method === "POST") {
      const state = body.state && typeof body.state === "object" ? body.state : null;
      const acks = new Set(Array.isArray(body.acks) ? body.acks.map(String) : []);
      let cmds = ((await this.s.get("cmds")) || []).filter(c => !acks.has(c.id) && Date.now() - c.at < 86400e3);
      const rules = await this.s.get("rules");
      const puts = { cmds, seen: Date.now() };
      if (state && Object.keys(state).length) puts.state = state;
      await this.s.put(puts);
      const out = { cmds };
      if (this.liveUntil > now) out.live = true;
      const have = state && state.rules ? +state.rules.rev || 0 : 0;
      if (rules && rules.rev > have) out.rules = rules;
      return json(out);
    }
    if (action === "state" && req.method === "GET") {
      const [state, rules, seen, cmds] = await Promise.all(["state", "rules", "seen", "cmds"].map(k => this.s.get(k)));
      return json({ state: state || null, rules: rules || null, seen: seen || 0, pending: (cmds || []).length });
    }
    if (action === "rules" && req.method === "PUT") {
      const [state, old] = await Promise.all([this.s.get("state"), this.s.get("rules")]);
      const rev = Math.max(Date.now(), (old && old.rev || 0) + 1, (state && state.rules && state.rules.rev || 0) + 1);
      const rules = { ...body, rev };
      // a new PIN arrives in plain: keep only the hash the phone uses (sha256 of "kg:" + PIN)
      const pin = String(rules.pin || "").replace(/\D/g, "");
      if (pin.length >= 4) rules.pinHash = await sha256("kg:" + pin);
      delete rules.pin;
      await this.s.put("rules", rules);
      return json(rules);
    }
    if (action === "cmd" && req.method === "POST") {
      const type = String(body.type || "");
      if (!["lock", "locate", "message", "ring", "openPairing", "unpairAll", "release", "answer", "reboot"].includes(type)) return json({ error: "bad command" }, 400);
      const cmds = ((await this.s.get("cmds")) || []).slice(-19);
      cmds.push({ ...body, type, id: crypto.randomUUID(), at: Date.now() });
      await this.s.put("cmds", cmds);
      return json({ result: "queued" });
    }
    return json({ error: "method not allowed" }, 405);
  }
}

// KidGuard relay: lets the parent's page reach the child phone when they are not on the same Wi-Fi.
// One Durable Object per child (by relay id). It stores the last state the child posted, the newest rules the
// parent set, and a short queue of commands. The child polls POST /c/<id>/sync every 20-60 s.
// Auth: the token in the parent's link; the first child sync binds its sha256 to the id.
import html from "../../web/index.html";

const MAX_BODY = 256 * 1024;
const json = (o, status = 200) => new Response(JSON.stringify(o), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
});

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html")) {
      return new Response(html, { headers: {
        "content-type": "text/html; charset=utf-8", "cache-control": "no-cache",
        "referrer-policy": "no-referrer", "x-content-type-options": "nosniff",
      } });
    }
    const m = url.pathname.match(/^\/c\/([0-9a-f]{16})\/(sync|state|rules|cmd)$/);
    if (!m) return json({ error: "not found" }, 404);
    const len = +(req.headers.get("content-length") || 0);
    if (len > MAX_BODY) return json({ error: "too big" }, 413);
    const stub = env.KIDS.get(env.KIDS.idFromName(m[1]));
    return stub.fetch(req);
  },
};

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

export class Kid {
  constructor(ctx) { this.ctx = ctx; this.s = ctx.storage; }

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
    const body = req.method === "GET" ? null : await req.json().catch(() => null);
    if (req.method !== "GET" && !body) return json({ error: "bad json" }, 400);

    if (action === "sync" && req.method === "POST") {
      const state = body.state && typeof body.state === "object" ? body.state : null;
      const acks = new Set(Array.isArray(body.acks) ? body.acks.map(String) : []);
      let cmds = ((await this.s.get("cmds")) || []).filter(c => !acks.has(c.id) && Date.now() - c.at < 86400e3);
      const rules = await this.s.get("rules");
      const puts = { cmds, seen: Date.now() };
      if (state && Object.keys(state).length) puts.state = state;
      await this.s.put(puts);
      const out = { cmds };
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
      if (!["lock", "locate", "message", "ring", "openPairing", "unpairAll", "release"].includes(type)) return json({ error: "bad command" }, 400);
      const cmds = ((await this.s.get("cmds")) || []).slice(-19);
      cmds.push({ ...body, type, id: crypto.randomUUID(), at: Date.now() });
      await this.s.put("cmds", cmds);
      return json({ result: "queued" });
    }
    return json({ error: "not found" }, 404);
  }
}

#!/usr/bin/env node
// KidGuard MCP server (stdio, no dependencies, Node >= 18). See mcp/README.md.
// JSON-RPC 2.0, newline-delimited on stdin/stdout. Logs go to stderr only.
// Config (env): relay = KG_RELAY (worker base URL) + KG_ID (16 hex) + KG_TOKEN; LAN = KG_URL (http://ip:8770) + KG_TOKEN.

const PROTOCOL = "2025-06-18";
const NAME = "AI assistant (MCP)";
const log = (...a) => process.stderr.write("[kidguard-mcp] " + a.join(" ") + "\n");

// Commands the AI may send. Everything else (release, wipe, adbShell, reboot, PIN-gated, pairing, listen, screen...) is refused here.
const SAFE_CMDS = {
  lock: "Lock the screen now.",
  locate: "Ask the phone for a fresh location.",
  message: "Show a message on the child's screen. Needs text (max 500 chars).",
  ring: "Make the phone ring loudly (find my phone).",
  ringStop: "Stop the ringing.",
  sosAck: "Acknowledge the child's SOS.",
};

function cfg() {
  const e = process.env;
  const token = e.KG_TOKEN || "";
  if (e.KG_RELAY && e.KG_ID) return { via: "relay", base: e.KG_RELAY.replace(/\/+$/, "") + "/c/" + encodeURIComponent(e.KG_ID), token };
  if (e.KG_URL) return { via: "lan", base: e.KG_URL.replace(/\/+$/, "") + "/api", token };
  throw new Error("not configured: set KG_RELAY + KG_ID + KG_TOKEN, or KG_URL + KG_TOKEN");
}

async function call(path, method = "GET", body) {
  const c = cfg();
  if (!c.token) throw new Error("KG_TOKEN is not set");
  const r = await fetch(c.base + path, {
    method,
    headers: { "X-KG-Token": c.token, "X-KG-Name": NAME, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  const txt = await r.text();
  let j = {};
  try { j = txt ? JSON.parse(txt) : {}; } catch { j = { raw: txt.slice(0, 200) }; }
  if (r.status >= 400) throw new Error((j && (j.error || j.result)) || "HTTP " + r.status);
  return j;
}

// -> {state, rules, seen, pending, via}. Relay: {state, rules, seen}; LAN: the state itself (rules inside).
async function load() {
  const j = await call("/state");
  const c = cfg();
  if (c.via === "relay") {
    const s = j.state || {};
    const a = j.rules, b = s.rules;
    const rules = a && b ? ((+a.rev || 0) >= (+b.rev || 0) ? a : b) : a || b || {};
    return { state: s, rules, seen: j.seen || 0, pending: j.pending || 0, via: "relay" };
  }
  return { state: j, rules: j.rules || {}, seen: j.t || Date.now(), pending: 0, via: "lan" };
}

const min = (ms) => Math.round((ms || 0) / 60000);
const iso = (t) => (t ? new Date(t).toISOString() : null);

function cleanRules(r) {
  const o = structuredClone(r || {});
  delete o.pinHash; delete o.pinSet; delete o.pin;   // never sent back: the phone keeps its PIN
  return o;
}

function mergePatch(target, patch) {   // RFC 7396
  if (patch === null || typeof patch !== "object" || Array.isArray(patch)) return patch;
  const out = target && typeof target === "object" && !Array.isArray(target) ? { ...target } : {};
  for (const [k, v] of Object.entries(patch)) {
    if (v === null) delete out[k];
    else out[k] = mergePatch(out[k], v);
  }
  return out;
}

async function putRules(rules) {
  return call("/rules", "PUT", rules);   // WHOLE object: rules are a full replace on the phone
}

const tools = {
  kid_status: {
    description: "Summary of the child's phone: online, battery %, time used/left today (minutes), current app, last location and zone, open requests.",
    schema: { type: "object", properties: {}, additionalProperties: false },
    async run() {
      const { state: s, rules: r, seen, pending, via } = await load();
      const ageMin = via === "relay" ? Math.round((Date.now() - seen) / 60000) : 0;
      const cur = (s.apps || []).find((a) => a.pkg === s.current);
      const loc = s.location && s.location.lat != null ? s.location : null;
      const zoneEv = (s.events || []).filter((e) => e.kind === "zoneIn" || e.kind === "zoneOut").slice(-1)[0];
      return {
        via, online: ageMin < 6, last_seen: iso(seen), last_seen_min_ago: ageMin, queued_commands: pending,
        day: s.day, battery_pct: s.battery, charging: !!s.charging, screen_on: s.screenOn !== false,
        locked_now: !!(s.now && s.now.why), lock_reason: s.now ? s.now.why || "" : "", lock_until: s.now && s.now.until ? iso(s.now.until) : null,
        limit_min: s.limitMin || 0, used_min: min(s.usedMs), left_min: s.leftMs == null || s.leftMs < 0 ? null : min(s.leftMs),
        paused_until: r.pausedUntil ? iso(r.pausedUntil) : null,
        current_app: s.screenOn === false ? null : (cur && cur.label) || s.current || null,
        location: loc ? { lat: loc.lat, lng: loc.lng, accuracy_m: Math.round(loc.acc || 0), at: iso(loc.t) } : null,
        last_zone_event: zoneEv ? { text: zoneEv.text, at: iso(zoneEv.t) } : null,
        open_requests: (s.requests || []).map((x) => ({ id: x.id, kind: x.kind === "app" ? "app" : "time", minutes: x.min || 0, app: x.app || x.pkg || "", note: x.note || "", at: iso(x.at || x.t) })),
        version: s.version,
      };
    },
  },
  usage_today: {
    description: "Per-app screen time today in minutes (top 15) and total.",
    schema: { type: "object", properties: {}, additionalProperties: false },
    async run() {
      const { state: s } = await load();
      const apps = (s.apps || []).filter((a) => a.label && a.ms > 0).sort((x, y) => y.ms - x.ms).slice(0, 15)
        .map((a) => ({ app: a.label, package: a.pkg, minutes: min(a.ms), blocked: a.blocked || null }));
      return { day: s.day, total_min: min(s.usedMs), apps };
    },
  },
  get_rules: {
    description: "The current rules JSON (limits in minutes, blocked apps, schedules, zones...). PIN hash is hidden.",
    schema: { type: "object", properties: {}, additionalProperties: false },
    async run() { return cleanRules((await load()).rules); },
  },
  update_rules: {
    description: "Change rules with a JSON merge-patch (RFC 7396: objects merge, null deletes a key, arrays replace). The server reads the current rules, applies the patch and sends the WHOLE object back. Example: {\"dailyMin\":90}. Call get_rules first to see field names. Cannot set or change the PIN.",
    schema: { type: "object", properties: { patch: { type: "object", description: "Merge-patch over the rules" } }, required: ["patch"], additionalProperties: false },
    async run({ patch }) {
      if (!patch || typeof patch !== "object" || Array.isArray(patch)) throw new Error("patch must be an object");
      for (const k of ["pin", "pinHash", "pinSet", "rev"]) if (k in patch) throw new Error("field not allowed: " + k);
      const cur = cleanRules((await load()).rules);   // read right before the PUT: narrow window for a conflicting edit
      const next = mergePatch(cur, patch);
      const r = await putRules(next);
      return { sent: cfg().via === "relay" ? "queued for the phone (relay)" : "saved on the phone", rev: r && r.rev, changed_keys: Object.keys(patch) };
    },
  },
  grant_time: {
    description: "Give extra screen time for today, in minutes (adds to today's bonus). Same as the page's '+ time'.",
    schema: { type: "object", properties: { minutes: { type: "integer", minimum: 1, maximum: 240 } }, required: ["minutes"], additionalProperties: false },
    async run({ minutes }) {
      const m = Math.trunc(+minutes);
      if (!(m >= 1 && m <= 240)) throw new Error("minutes must be 1..240");
      const { state: s, rules } = await load();
      const day = s.day;
      if (!day) throw new Error("phone state has no day yet");
      const cur = cleanRules(rules);
      const had = cur.bonus && cur.bonus.day === day ? +cur.bonus.min || 0 : 0;
      cur.bonus = { day, min: Math.min(600, had + m) };
      await putRules(cur);
      return { sent: "ok", bonus_today_min: cur.bonus.min };
    },
  },
  answer_request: {
    description: "Answer the child's open request (id from kid_status). ok=true grants it; min = minutes for a time request.",
    schema: { type: "object", properties: { req: { type: "string" }, ok: { type: "boolean" }, min: { type: "integer", minimum: 0, maximum: 240 } }, required: ["req", "ok"], additionalProperties: false },
    async run({ req, ok, min: m }) {
      if (typeof req !== "string" || !req) throw new Error("req is required");
      const { state: s, rules } = await load();
      const rq = (s.requests || []).find((x) => x.id === req);
      if (!rq) throw new Error("no such open request");
      const body = { type: "answer", req, ok: !!ok };
      if (ok && rq.kind !== "app") {
        const g = Math.max(1, Math.min(240, Math.trunc(+(m ?? rq.min ?? 15)) || 15));
        body.min = g;
        // like the page: the command only removes the request; the parent side edits the rules (full replace)
        const cur = cleanRules(rules), day = s.day;
        if (day && (!s.now || !s.now.why || s.now.why === "daily")) {
          const had = cur.bonus && cur.bonus.day === day ? +cur.bonus.min || 0 : 0;
          cur.bonus = { day, min: had + g };
          await putRules(cur);
        }
      } else if (ok && rq.kind === "app" && rq.pkg) {
        const cur = cleanRules(rules);
        cur.approved = [...new Set([...(cur.approved || []), rq.pkg])];
        cur.blocked = (cur.blocked || []).filter((p) => p !== rq.pkg);
        await putRules(cur);
      }
      const r = await call("/cmd", "POST", body);
      return { sent: "queued, the phone has not confirmed yet", reply: r };
    },
  },
  send_command: {
    description: "Send a safe command to the phone. Allowed: " + Object.keys(SAFE_CMDS).join(", ") + ". Queued, not confirmed done.",
    schema: { type: "object", properties: { type: { type: "string", enum: Object.keys(SAFE_CMDS) }, text: { type: "string", description: "For message: the text" } }, required: ["type"], additionalProperties: false },
    async run({ type, text }) {
      if (!Object.prototype.hasOwnProperty.call(SAFE_CMDS, type)) throw new Error("command not allowed: " + String(type).slice(0, 30));
      const body = { type };
      if (type === "message") {
        if (!text || typeof text !== "string") throw new Error("text is required for message");
        body.text = text.slice(0, 500);
      }
      const r = await call("/cmd", "POST", body);
      return { sent: "queued, the phone has not confirmed yet", reply: r };
    },
  },
};

// ---------- JSON-RPC ----------
const send = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const reply = (id, result) => send({ jsonrpc: "2.0", id, result });
const fail = (id, code, message) => send({ jsonrpc: "2.0", id, error: { code, message } });

async function handle(m) {
  const { id, method, params } = m;
  const note = id === undefined || id === null;
  switch (method) {
    case "initialize":
      return reply(id, { protocolVersion: PROTOCOL, capabilities: { tools: {} }, serverInfo: { name: "kidguard", version: "0.1.0" } });
    case "ping": return reply(id, {});
    case "tools/list":
      return reply(id, { tools: Object.entries(tools).map(([name, t]) => ({ name, description: t.description, inputSchema: t.schema })) });
    case "tools/call": {
      const t = tools[params && params.name];
      if (!t) return fail(id, -32602, "unknown tool");
      try {
        const out = await t.run((params && params.arguments) || {});
        return reply(id, { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] });
      } catch (e) {
        return reply(id, { isError: true, content: [{ type: "text", text: "Error: " + (e && e.message || e) }] });
      }
    }
    default:
      if (note) return;   // notifications (notifications/initialized ...): no reply
      return fail(id, -32601, "method not found: " + method);
  }
}

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => {
  buf += d;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let m;
    try { m = JSON.parse(line); } catch { fail(null, -32700, "parse error"); continue; }
    handle(m).catch((e) => { log("handler", e && e.message); if (m && m.id != null) fail(m.id, -32603, "internal error"); });
  }
});
process.stdin.on("end", () => process.exit(0));
log("ready");

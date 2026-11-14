import { BaseSideService } from "@zeppos/zml/base-side";

// Runs inside the Zepp app on the PARENT's phone (it has internet; the watch does not).
// Talks to the KidGuard relay exactly like the parent page (kidguard docs/protocol.md):
//   GET  <relay>/c/<id>/state  -> {state, rules, seen, pending}
//   POST <relay>/c/<id>/cmd    {type, ...}
//   PUT  <relay>/c/<id>/rules  the WHOLE rules object (full replace on the phone)
// Kids come from (1) the code flow (0.4.0): the watch shows a 6-digit code, the parent types it on the KidGuard page
//   (Settings > Watch); we POST <relay>/w/new {sh=sha256(secret)} and poll GET <relay>/w/<code> (X-KG-Secret) until the
//   relay hands over {url,id,token,name} once, or (2) the settings page: paste the relay link
//   "https://…/go#c=<id>&k=<token>&n=<name>". Both end as JSON in settingsStorage "kids" [{url,id,token,name}].

const MIN = 60000;
const newId = () => "w" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

const store = () => settings.settingsStorage;
// the author's shared relay (same as DEFAULT_RELAY in ui/assets/core.js); settingsStorage "relay" overrides (settings page)
const DEFAULT_RELAY = "https://kidguard.99-hoangtran.workers.dev";
const relay = () => {
  const r = String(store().getItem("relay") || "").trim().replace(/\/+$/, "");
  return /^https:\/\/[^/#?\s]+$/.test(r) ? r : DEFAULT_RELAY;
};

// SHA-256 hex (the side service has no crypto.subtle guaranteed)
function sha256(msg) {
  const K = [], H = [];
  const rr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let p = 2, c = 0; c < 64; p++) {
    let pr = true;
    for (let d = 2; d * d <= p; d++) if (p % d === 0) { pr = false; break; }
    if (!pr) continue;
    if (c < 8) H[c] = (Math.pow(p, 0.5) * 4294967296) | 0;
    K[c++] = (Math.pow(p, 1 / 3) * 4294967296) | 0;
  }
  const b = [];
  for (let i = 0; i < msg.length; i++) b.push(msg.charCodeAt(i) & 255);   // ASCII only (hex secret)
  const bits = b.length * 8;
  b.push(0x80);
  while (b.length % 64 !== 56) b.push(0);
  for (let i = 7; i >= 0; i--) b.push(i > 3 ? 0 : (bits >>> (i * 8)) & 255);
  for (let o = 0; o < b.length; o += 64) {
    const w = [];
    for (let i = 0; i < 16; i++) w[i] = (b[o + 4 * i] << 24) | (b[o + 4 * i + 1] << 16) | (b[o + 4 * i + 2] << 8) | b[o + 4 * i + 3];
    for (let i = 16; i < 64; i++) {
      const s0 = rr(w[i - 15], 7) ^ rr(w[i - 15], 18) ^ (w[i - 15] >>> 3), s1 = rr(w[i - 2], 17) ^ rr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    let [a, bb, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (rr(e, 6) ^ rr(e, 11) ^ rr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      const t2 = ((rr(a, 2) ^ rr(a, 13) ^ rr(a, 22)) + ((a & bb) ^ (a & c) ^ (bb & c))) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = bb; bb = a; a = (t1 + t2) | 0;
    }
    [a, bb, c, d, e, f, g, h].forEach((v, i) => (H[i] = (H[i] + v) | 0));
  }
  return H.map((v) => (v >>> 0).toString(16).padStart(8, "0")).join("");
}

function randHex(n) {
  let o = "";
  for (let i = 0; i < n; i++) o += ((Math.random() * 16) | 0).toString(16);
  return o;
}

function kids() {
  try {
    const k = JSON.parse(store().getItem("kids") || "[]");
    return Array.isArray(k) ? k : [];
  } catch (e) {
    return [];
  }
}

function demoKid() {
  return { name: "Demo", demo: true };
}

// kid index -1 = the demo phone: only when the user chose "Xem thử" on the watch, never by default
function kidAt(i) {
  if (i === -1) return demoKid();
  const k = kids();
  if (!k.length) throw new Error("no kid");
  return k[Math.max(0, Math.min(k.length - 1, i | 0))];
}

// ---------- connect with a code (docs/protocol.md "Watch connect") ----------

async function relayJson(path, method, headers, body) {
  const r = await fetch({ url: relay() + path, method, headers: { "Content-Type": "application/json", ...(headers || {}) }, body: body ? JSON.stringify(body) : undefined });
  const j = typeof r.body === "string" ? (r.body ? JSON.parse(r.body) : {}) : r.body || {};
  return { status: r.status, j };
}

async function pairNew() {
  const secret = randHex(32);
  const { status, j } = await relayJson("/w/new", "POST", null, { sh: sha256(secret) });
  if (status >= 400 || !j.code) throw new Error(j.error || "HTTP " + status);
  store().setItem("wsecret", secret);
  store().setItem("wcode", j.code);
  if (!store().getItem("relay")) store().setItem("relay", relay());
  return { code: j.code, exp: j.exp };
}

async function pairPoll(code) {
  const secret = store().getItem("wsecret");
  if (!secret || store().getItem("wcode") !== code) return { expired: true };
  const { status, j } = await relayJson("/w/" + code, "GET", { "X-KG-Secret": secret });
  if (status === 404) return { expired: true };
  if (status >= 400) throw new Error(j.error || "HTTP " + status);
  if (j.waiting) return { waiting: true };
  if (!/^https:\/\/[^/#?\s]+$/.test(j.url || "") || !/^[0-9a-f]{16}$/.test(j.id || "") || !/^[0-9a-f]{48}$/.test(j.token || "")) throw new Error("bad reply");
  const k = { url: j.url, id: j.id, token: j.token, name: String(j.name || "").slice(0, 20) || "Con" };
  const list = kids().filter((x) => x.id !== k.id).concat(k);
  store().setItem("kids", JSON.stringify(list));
  store().removeItem("wsecret");
  store().removeItem("wcode");
  return { done: true, name: k.name, index: list.length - 1 };
}

async function http(k, path, method = "GET", body) {
  const r = await fetch({
    url: `${k.url}/c/${k.id}${path}`,
    method,
    headers: { "X-KG-Token": k.token, "X-KG-Name": "Zepp watch", "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = typeof r.body === "string" ? (r.body ? JSON.parse(r.body) : {}) : r.body || {};
  if (r.status >= 400) throw new Error(j.error || "HTTP " + r.status);
  return j;
}

// ---------- state summary (small: it goes over Bluetooth) ----------

function baseLimit(r, day) {
  if (r.dailyByDay && r.dailyByDay.length === 7 && day) {
    const d = new Date(day + "T12:00:00");
    return +r.dailyByDay[(d.getDay() + 6) % 7] || 0;
  }
  return r.dailyMin || 0;
}

function newestRules(j) {
  const a = j.rules, b = j.state && j.state.rules;
  if (a && b) return (+a.rev || 0) >= (+b.rev || 0) ? a : b;
  return a || b || {};
}

function summary(k, j) {
  const s = j.state || {};
  const r = newestRules(j);
  const day = s.day || "";
  const blocked = new Set(r.blocked || []);
  const apps = (s.apps || [])
    .filter((a) => a.label)
    .sort((x, y) => (y.ms || 0) - (x.ms || 0))
    .slice(0, 15)
    .map((a) => ({ p: a.pkg, l: a.label.slice(0, 22), ms: a.ms || 0, b: blocked.has(a.pkg) ? 1 : 0 }));
  const cur = (s.apps || []).find((a) => a.pkg === s.current);
  const loc = s.location && s.location.lat != null ? s.location : null;
  const zoneEv = (s.events || []).filter((e) => e.kind === "zoneIn" || e.kind === "zoneOut").slice(-1)[0];
  return {
    name: k.name || "",
    seen: j.seen || 0,
    pending: j.pending || 0,
    day,
    leftMs: s.leftMs == null ? -1 : s.leftMs,
    usedMs: s.usedMs || 0,
    limitMin: s.limitMin || 0,
    base: baseLimit(r, day),
    bonus: r.bonus && r.bonus.day === day ? r.bonus.min || 0 : 0,
    paused: r.pausedUntil || 0,
    bed: r.bedtime ? { on: !!r.bedtime.on, s: r.bedtime.start, e: r.bedtime.end } : null,
    byDay: !!(r.dailyByDay && r.dailyByDay.length === 7),
    battery: s.battery == null ? -1 : s.battery,
    charging: !!s.charging,
    screenOn: s.screenOn !== false,
    current: s.screenOn === false ? "" : (cur && cur.label) || "",
    loc: loc ? { lat: loc.lat, lng: loc.lng, acc: Math.round(loc.acc || 0), t: loc.t || 0 } : null,
    zone: zoneEv ? { t: zoneEv.t, text: zoneEv.text } : null,
    now: s.now ? { why: String(s.now.why || ""), until: s.now.until || 0, name: String(s.now.name || "").slice(0, 24) } : { why: "", until: 0, name: "" },
    pass: +r.passUntil || 0,
    lost: !!((s.lost && s.lost.on) || r.lostOn),
    lostMsg: String(r.lostMsg || "").slice(0, 200),
    lostPhone: String(r.lostPhone || "").slice(0, 20),
    sos: (s.sos || []).map((x) => ({ id: x.id, t: x.t })),
    req: (s.requests || []).slice(-1).map((x) => ({
      id: x.id, t: x.at || x.t || 0, again: x.remindAt || 0, kind: x.kind === "app" ? "app" : "time", min: x.min || 0,
      pkg: x.pkg || "", app: String(x.app || x.pkg || "").slice(0, 24), note: (x.note || "").slice(0, 60),
    })),
    events: (s.events || []).slice(-10).reverse().map((e) => ({ t: e.t, text: (e.text || e.kind || "").slice(0, 60) })),
    apps,
  };
}

function demoState() {
  const now = Date.now();
  return {
    name: "Demo", demo: true, seen: now - 40000, pending: 0, day: "",
    leftMs: 45 * MIN, usedMs: 75 * MIN, limitMin: 120, base: 120, bonus: 0, paused: 0,
    bed: { on: true, s: "21:30", e: "06:30" }, byDay: false, battery: 62, charging: false, screenOn: true,
    current: "YouTube Kids",
    loc: { lat: 21.0285, lng: 105.8542, acc: 18, t: now - 4 * MIN },
    zone: { t: now - 50 * MIN, text: "Đã đến Nhà" },
    now: { why: "", until: 0, name: "" }, pass: 0, lost: false, lostMsg: "", lostPhone: "",
    sos: [],
    req: [{ id: "demo1", t: now - 2 * MIN, again: 0, kind: "time", min: 30, pkg: "", app: "", note: "con làm xong bài tập rồi" }],
    events: [
      { t: now - 50 * MIN, text: "Đã đến Nhà" },
      { t: now - 3 * 60 * MIN, text: "Đã rời Trường" },
    ],
    apps: [
      { p: "a", l: "YouTube Kids", ms: 40 * MIN, b: 0 },
      { p: "b", l: "Roblox", ms: 20 * MIN, b: 1 },
      { p: "c", l: "Zalo", ms: 10 * MIN, b: 0 },
      { p: "d", l: "Duolingo", ms: 5 * MIN, b: 0 },
    ],
  };
}

// ---------- rules edits (read newest, change one thing, PUT the whole object) ----------

// Same rule as the page's grantApply (kid.js) and the phone's Grant.apply:
// now.why "" / "daily" -> today's bonus; any other lock -> timed pass passUntil.
function grantApply(r, st, min, day) {
  const why = st && st.now ? st.now.why || "" : "";
  if (why === "" || why === "daily") {
    const c0 = r.bonus && r.bonus.day === day ? +r.bonus.min || 0 : 0;
    r.bonus = { day, min: Math.min(600, c0 + min) };
    return "daily";
  }
  r.passUntil = Math.max(Date.now(), +r.passUntil || 0) + min * MIN;
  return "pass";
}

function editRules(r, op, day, st) {
  const now = Date.now();
  switch (op.k) {
    case "grant":
      grantApply(r, st, op.n, day);
      break;
    case "passEnd":
      r.passUntil = 0;
      break;
    case "lost":
      r.lostOn = !!op.on;
      if (op.on && !r.lostMsg) r.lostMsg = String(op.msg || "").slice(0, 200);
      break;
    case "appOk": {
      r.approved = [...new Set([...(r.approved || []), op.p])];
      r.blocked = (r.blocked || []).filter((v) => v !== op.p);
      break;
    }
    case "bonusClear":
      r.bonus = { day, min: 0 };
      break;
    case "pause":
      r.pausedUntil = now + op.n * MIN;
      break;
    case "resume":
      r.pausedUntil = 0;
      break;
    case "bed":
      r.bedtime = { ...(r.bedtime || { start: "21:30", end: "06:30" }), on: !!op.on };
      break;
    case "limit":
      if (r.dailyByDay && r.dailyByDay.length === 7) r.dailyByDay = [0, 0, 0, 0, 0, 0, 0].map(() => op.n);
      r.dailyMin = op.n;
      break;
    case "block": {
      const b = new Set(r.blocked || []);
      if (op.on) b.add(op.p);
      else b.delete(op.p);
      r.blocked = [...b];
      break;
    }
    default:
      throw new Error("bad op");
  }
  return r;
}

async function changeRules(k, op) {
  const j = await http(k, "/state");
  const day = (j.state && j.state.day) || "";
  const r = editRules(JSON.parse(JSON.stringify(newestRules(j))), op, day, j.state);
  delete r.pinHash;
  delete r.pinSet;
  delete r.pin;
  await http(k, "/rules", "PUT", r);
  return "ok";
}

// ---------- requests from the watch ----------

async function handle(req) {
  const p = req.params || {};
  if (req.method === "KIDS") return { kids: kids().map((k) => k.name || k.id) };
  if (req.method === "PAIR_NEW") return await pairNew();
  if (req.method === "PAIR_POLL") return await pairPoll(String(p.code || ""));
  const k = kidAt(p.kid);
  if (k.demo) {
    if (req.method === "STATE") return { state: demoState() };
    return { result: "demo" };
  }
  switch (req.method) {
    case "STATE":
      return { state: summary(k, await http(k, "/state")) };
    case "CMD": {
      // our own command id: the phone's receipt (state.done) or the worker's (expired) is matched on it
      const c = { ...(p.c || {}), id: newId() };
      const j = await http(k, "/cmd", "POST", c);
      const res = j.result || "queued";
      if (res !== "queued" && res !== "ok" && res !== "no such sos") throw new Error(res);
      return { result: res, id: c.id };
    }
    case "RECEIPT": {
      const j = await http(k, "/state");
      const d = ((j.state && j.state.done) || []).find((x) => x.id === p.id);
      if (d) return { r: d.res === "ok" ? "ok" : "fail", res: String(d.res || "").slice(0, 40) };
      if ((j.expired || []).some((x) => x.id === p.id)) return { r: "expired" };
      return { r: "wait" };
    }
    case "RULES":
      return { result: await changeRules(k, p.op) };
    case "ANSWER": {
      // same order as the page: edit the rules first, then tell the child (answer removes the request)
      const app = p.kind === "app";
      if (p.ok) await changeRules(k, app ? { k: "appOk", p: p.pkg } : { k: "grant", n: p.min });
      const id = newId();
      const j = await http(k, "/cmd", "POST", {
        type: "answer", req: p.req, id, ok: !!p.ok,
        ...(p.ok && app ? { always: true } : {}), ...(p.ok && !app ? { min: p.min } : {}),
      });
      return { result: j.result || "ok", id };
    }
    default:
      throw new Error("bad method");
  }
}

AppSideService(
  BaseSideService({
    onInit() {},
    onRequest(req, res) {
      handle(req)
        .then((out) => res(null, out))
        .catch((e) => res(null, { error: String((e && e.message) || e) }));
    },
    onSettingsChange() {},
    onRun() {},
    onDestroy() {},
  })
);

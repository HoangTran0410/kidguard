import { BaseSideService } from "@zeppos/zml/base-side";

// Runs inside the Zepp app on the PARENT's phone (it has internet; the watch does not).
// Talks to the KidGuard relay exactly like the parent page (kidguard docs/protocol.md):
//   GET  <relay>/c/<id>/state  -> {state, rules, seen, pending}
//   POST <relay>/c/<id>/cmd    {type, ...}
//   PUT  <relay>/c/<id>/rules  the WHOLE rules object (full replace on the phone)
// Kids come from the settings page: the relay link "https://…/go#c=<id>&k=<token>&n=<name>"
// shown in the parent page (Settings > connection). Stored as JSON in settingsStorage "kids".

const MIN = 60000;
const newId = () => "w" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

const store = () => settings.settingsStorage;

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

function kidAt(i) {
  const k = kids();
  return k.length ? k[Math.max(0, Math.min(k.length - 1, i | 0))] : demoKid();
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

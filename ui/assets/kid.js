// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- one child ------------------------------------------------------------
let noState = false;   // relay answered {state: null}: the child never synced
let gen = 0;   // bumped by every screen change; async work started for an older screen drops its result
// relay: the phone sends the state only when it changed, so its sync time (seen) says "online", not state.t
function lastSeen() { return cur && cur.via !== "lan" && seenAt ? Math.max(seenAt, st ? st.t : 0) : (st ? st.t : 0); }
async function open(k) { const g = ++gen; if (askDone) askDone(false, true); leaveSave(); pairLocal = null; wantReset(); screenStop(); earStop(); cur = k; cachedAt = 0; viewDay = null; histDays = null; st = null; edit = null; trk = []; trkLoaded = 0; mv = null; sel.on = false; sel.set.clear(); hideDraft = null; noState = false; saveFail(false); render(); if ($("#back")) navTo(1); clearInterval(timer); await refresh(); if (g !== gen) return; clearInterval(timer); timer = setInterval(refresh, k.via === "lan" ? 4000 : 8000); }

async function refresh() {
  if (!cur || viewDay || document.hidden) return;
  const k = cur;
  try {
    const j = await call(k, "/state");
    if (k !== cur || viewDay) return;
    // LAN: the state itself; relay: {state, rules (sent, not yet taken), seen}
    st = j.state !== undefined ? j.state : j;
    noState = j.state === null;
    pendingRules = j.state !== undefined ? j.rules : null;
    seenAt = j.state !== undefined ? +j.seen || 0 : 0;
    lastErr = ""; lastNet = false;
    settle();
  } catch (e) {
    if (k !== cur) return;
    lastErr = e.message;
    lastNet = !!e.net;
    if (LOCAL && e.raw === "not paired") return localOver();   // the on-device session expired
    if (e.raw === "not paired" || /not paired/.test(e.message)) { gen++; clearInterval(timer); cur = null; kids = kids.filter(x => x !== k); save(kids); navDown(0); toast(t("e.not paired")); return setTimeout(boot, 1000); }
  }
  const busy = document.activeElement && /^(SELECT|INPUT|TEXTAREA)$/.test(document.activeElement.tagName);
  if (st && st.rules) {
    const nw = pendingRules && pendingRules.rev > st.rules.rev ? pendingRules : st.rules;
    if (!edit) { edit = norm(structuredClone(nw)); levelsOffer(k); }
    // another parent (or the child) changed the rules: reload unless we are mid-edit or saving, else a stale page would overwrite them
    else if ((nw.rev || 0) > (edit.rev || 0) && !saveT && !saveBusy && !busy && !saveFailed) { edit = norm(structuredClone(nw)); lastSig = ""; }
  }
  // redraw the tab only when what it shows changed and nobody is using a control (icons would reload, a select would close)
  const sig = st ? JSON.stringify([tab, st.rules && st.rules.rev, st.apps.map(a => [a.suspended, a.blocked, Math.floor(a.ms / 60000)]),
    st.location && st.location.t, (st.log || []).length, (st.track || []).length, Math.floor(st.usedMs / 60000), lastErr, cachedAt, viewDay,
    (st.requests || []).map(q => q.id), (st.history || []).length, st.limitMin, st.totpOn, st.unlockUntil, (st.apps || []).filter(a => a.new).length, (st.events || []).slice(-10).map(e => e.id).join(","), (st.preinstalled || []).map(x => (x.hidden ? 1 : 0) + (x.refused ? 2 : 0)).join(""), (st.devices || []).map(d => d.id + d.name).join(","), (st.rules && st.rules.hidden || []).join(",")]) : lastErr + (noState ? "|nostate" : "");
  if (tab === "screen") updateLive();   // never redraw the screen tab: the <img> updates by itself
  else if (sig !== lastSig && !busy) { lastSig = sig; render(); } else updateLive();
  if (tab === "files" && k === cur) fxSync();   // shared folder list + relay upload delivery (its own redraw)
  if (tab === "set") sysDraw();   // the phone-settings card follows st.sys by itself
}
let lastSig = "";

let saveT = null, saveBusy = 0, saveFailed = false;
let zoneWarn = 0;   // zone count we already warned about (a zone without valid coordinates is not saved)
// leaving a kid with a failed save: one last try in the background, say so if it still fails; hides the save-failed bar
function leaveSave() {
  if (saveFailed && cur && edit && !saveBusy) {
    const k = cur, e = edit;
    putRules(k, e).then(() => toast("ok|" + k.name + ": " + t("saved")), er => toast("err|" + k.name + ": " + t("saveFail") + " (" + er.message + ")"));
  }
  saveFail(false);
}
function saveFail(on) { saveFailed = on; const b = $("#saveBar"); if (b) b.style.display = on ? "flex" : "none"; document.body.classList.toggle("sb", !!on); }
$("#saveRetry").onclick = () => changed();
// PUT the rules; throws on failure. Used by changed() (debounced) and by approve (needs the result before it answers).
async function putRules(k, e) {
  // Preview (phone never paired, no parent PIN yet): the first save sets the parent PIN. It is the same PIN the parent
  // later enters to pair their own phone, and from then on it guards "Bố mẹ quản lý trên máy này".
  if (!e.pinSet && !e.pin && st && st.caps && st.caps.paired === false) {
    let pin = "";
    const inp = (id, ph) => `<input id="${id}" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(ph)}" aria-label="${esc(ph)}" style="width:100%;margin-bottom:10px">`;
    const collect = () => { const x = $("#askP1").value, y = $("#askP2").value; if (/^\d{4,8}$/.test(x) && x === y) { pin = x; return true; } return false; };
    if (!(await ask(t("pinFirst.p"), t("pinGate.go"), false, "", inp("askP1", t("pair.pin1")) + inp("askP2", t("pair.pin2")), collect))) throw new Error(t("pinFirst.need"));
    e.pin = pin;
  }
  saveBusy++;
  try {
    const body = { ...e }; delete body.pinHash; delete body.pinSet; if (!body.pin) delete body.pin;
    if (Array.isArray(body.zones)) { const n0 = body.zones.length; body.zones = body.zones.filter(zoneOk); if (body.zones.length < n0 && zoneWarn !== n0) toast("warn|" + t("zone.skipped")); zoneWarn = body.zones.length < n0 ? n0 : 0; }
    const sig0 = JSON.stringify(e), r = await call(k, "/rules", "PUT", body); const hadPin = !!body.pin;
    // LAN: the reply is the phone's normalized rules; take it only if nothing was toggled meanwhile (else keep the edit, just the rev)
    if (k.via === "lan" && r && typeof r === "object" && JSON.stringify(e) === sig0) { for (const x of Object.keys(e)) delete e[x]; Object.assign(e, norm(r)); }
    else if (r && r.rev) e.rev = r.rev;   // relay: else an older poll (rev of the previous save) looks newer and undoes this edit
    delete e.pin; if (hadPin) e.pinSet = true; delete e.pinHash;
    if (k === cur) saveFail(false);
  } finally { saveBusy--; }
}
function changed() {
  clearTimeout(saveT);
  if (viewDay) return;   // history snapshot: read-only
  const k = cur, e = edit;
  if (!k || !e) return;
  saveT = setTimeout(async () => {
    saveT = null;
    try { await putRules(k, e); toast(k.via === "lan" ? "ok|" + t("saved") : "send|" + t("sentRelay")); }
    catch (er) { toast("err|" + t("errX", er.message)); if (k === cur) saveFail(true); }
  }, 500);
}

// Every action that changes something on the child's phone asks first (custom modal: native confirm() looks off in the app).
// typed: the user must type this word (release). Resolves true/false; Esc / backdrop = false.
let askDone = null, askAuto = false;   // askAuto: the last dialog was closed by the page (new dialog, screen change), not by the parent
function ask(text, ok = t("agree"), danger = false, typed = "", extra = "", collect = null) {
  if (askDone) askDone(false, true);
  const pop = $("#askPop");
  pop.innerHTML = `<div class="dp"><h2 id="askT" style="margin:0 0 8px">${ic(danger ? "triangle-alert" : "circle-help")} ${t("confirm")}</h2>
    <div style="white-space:pre-line;margin-bottom:12px">${esc(text)}</div>
    ${extra}
    ${typed ? `<input id="askIn" autocomplete="off" placeholder="${esc(t("ask.typePh", typed))}" aria-label="${esc(t("ask.typeAria", typed))}" style="width:100%;margin-bottom:12px">` : ""}
    <div class="row"><button id="askNo" class="sp">${ic("x")} ${t("cancel")}</button><button id="askOk" class="sp ${danger ? "bad" : "pri"}" ${typed ? "disabled" : ""}>${ic("check")} ${esc(ok)}</button></div></div>`;
  pop.style.display = "flex";
  return new Promise(res => {
    const done = (v, auto) => { if (askDone !== done) return; askDone = null; askAuto = !!auto; pop.style.display = "none"; pop.innerHTML = ""; res(v); };
    askDone = done;
    $("#askNo").onclick = () => done(false); $("#askOk").onclick = () => { if (collect && !collect()) return; done(true); };
    if (collect) { const upd = () => { $("#askOk").disabled = !collect(); }; pop.querySelectorAll("input").forEach(c => { c.oninput = c.onchange = upd; }); upd(); }
    if (typed) { const i = $("#askIn"); const flat = v => String(v).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();   // "GỠ" = "GO" = "go": any keyboard works
      i.oninput = () => { $("#askOk").disabled = flat(i.value) !== flat(typed); }; i.focus(); } else $(danger ? "#askNo" : "#askOk").focus();   // Enter must not confirm a reboot / unpair
  });
}
$("#askPop").addEventListener("click", e => { if (e.target.id === "askPop" && askDone) askDone(false); });
document.addEventListener("keydown", e => { if (e.key === "Escape" && askDone) askDone(false); });
// confirm text "ask.<type>" + button "askOk.<type>" for commands that need no extra wording; these are the dangerous ones
const ASK_BAD = { reboot: 1, unpairAll: 1, tailscaleOff: 1 };
const askOf = c => hasT("ask." + c) ? [t("ask." + c), t("askOk." + c), !!ASK_BAD[c]] : null;
async function cmd(c, okMsg, q) {
  const k = cur, a0 = q || (q === false ? null : askOf(c.type)) || (c.type === "message" ? [t("askMsg", c.text), t("send")] : null);
  // dangerous: name the kid in the text, so a dialog for A can never be mistaken for B
  const a = a0 && a0[2] && k && k.name ? [k.name + "\n" + a0[0], ...a0.slice(1)] : a0;
  if (a && !(await ask(...a))) return;
  if (k !== cur) return;   // the screen changed while the dialog was open (notification tap): never send A's command to B
  try {
    const r = await call(k, "/cmd", "POST", c);
    if (r && r.result && !["ok", "queued", "no such sos"].includes(r.result) && !(c.type === "tailscale" || c.type === "openPairing")) { toast("err|" + tc("cr." + c.type + ".", r.result, t("cmd.answer", r.result))); return; }
    if (r && r.result === "queued") {   // relay: only stored, the phone runs it on its next poll
      const on = Date.now() - lastSeen() < (k.via === "lan" ? 30000 : 120000);
      toast(on ? "send|" + t("cmd.queued") : "warn|" + t("cmd.queuedOff"));
    } else toast("ok|" + (okMsg || t("sent")));
    return r || {};
  } catch (e) { toast("err|" + t("errX", e.message)); }
}

// Authenticator (RFC 6238) secret: made here, sent once with `totpSet`, the phone never sends it back
const gOpen = new Set();
const grpSum = n => (n ? t("grp.nApps", n) : t("grp.noApps")) + " · " + t("grp.pick");
let totpShown = null;   // {k, secret, label, uri}: the new key stays on screen across redraws until "done"
function b32(bytes) {
  const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = 0, v = 0, out = "";
  for (const x of bytes) { v = (v << 8) | x; bits += 8; while (bits >= 5) { out += A[(v >>> (bits - 5)) & 31]; bits -= 5; } }
  return bits ? out + A[(v << (5 - bits)) & 31] : out;
}
async function totpSetup() {
  const again = st && st.totpOn, kq = cur;
  const secret = b32(crypto.getRandomValues(new Uint8Array(20)));
  const r = await cmd({ type: "totpSet", secret }, again ? t("totp.newDone") : t("totp.onDone"),
    [again ? t("totp.askNew") : t("totp.askOn"), again ? t("totp.new") : t("turnOn")]);
  if (!r) return;
  const label = "KidGuard: " + ((cur && cur.name) || t("kid")), uri = `otpauth://totp/${encodeURIComponent(label)}?secret=${secret}&issuer=KidGuard`;
  totpShown = { k: kq, secret, label, uri, queued: r.result === "queued" };
  document.activeElement && document.activeElement.blur(); render();
}
// drawn on each render, so a language switch also redraws it
const qrSvg = text => {   // dark modules on a white quiet zone: scanners need dark-on-light even in dark theme
  try {
    qrcode.stringToBytes = qrcode.stringToBytesFuncs["UTF-8"];
    const q = qrcode(0, "M"); q.addData(text, "Byte"); q.make();
    const n = q.getModuleCount(); let d = "";
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (q.isDark(r, c)) d += `M${c + 4} ${r + 4}h1v1h-1z`;
    return `<svg viewBox="0 0 ${n + 8} ${n + 8}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
  } catch { return ""; }
};
const totpHtml = s => `<div class="alert warn" style="margin-top:10px">${s.queued && !(st && st.totpOn && Date.now() - lastSeen() < 120000) ? `<p><b>${ic("clock")} ${t("totp.queued")}</b></p>` : ""}<b>${ic("key-round")} ${t("totp.addNow")}</b> ${t("totp.once")}
    <div class="totpqr"><div class="qrw" role="img" aria-label="${esc(t("totp.qrAlt"))}">${qrSvg(s.uri)}</div><p>${ic("smartphone")} ${t("totp.scan")}</p></div>
    <p><a class="btn pri" href="${esc(s.uri)}">${ic("smartphone")} ${t("totp.openGA")}</a> ${t("totp.onPhone")}</p>
    <p>${t("totp.manual", esc(s.label), `<code style="user-select:all;word-break:break-all">${s.secret.replace(/(.{4})/g, "$1 ").trim()}</code>`)}</p>
    <p class="mute">${t("totp.share")}</p>
    <button id="totpDone">${ic("check")} ${t("totp.done")}</button></div>`;
// ---- v0.6: events, chores, zones, templates ----
const EV_IC = { zoneIn: "map-pin", zoneOut: "route", battery: "battery-full", newApp: "package", clock: "clock", pairing: "link", tamper: "triangle-alert", unlock: "lock-open", checkin: "hand", launcher: "house" };
// phone log line kind (log[].k, protocol) -> icon + tone; lines from older phones have no k
const LOG_IC = { pin_wrong: ["key-round", "bad"], unlock: ["lock-open"], paired: ["link"], tz: ["globe"], loc_fixed: ["map-pin"],
  usage_off: ["triangle-alert", "bad"], new_app: ["package"], suspended: ["pause"], unsuspended: ["play"], recover_open: ["refresh-cw"],
  recover_req: ["refresh-cw"], zone: ["map-pin"], cmd: ["send"], cleared: ["eraser"], totp_off: ["shield", "bad"], totp_on: ["shield"],
  release_fail: ["octagon-x", "bad"], released: ["unlink", "bad"], sos: ["siren", "bad"], acc_settings: ["triangle-alert", "bad"],
  acc_rebind: ["refresh-cw"], acc_reenabled: ["refresh-cw"], acc_paused: ["landmark"], acc_resumed: ["refresh-cw"], view_start: ["eye"], control: ["pointer"], proj_denied: ["eye-off", "bad"],
  listen_start: ["ear"], listen_done: ["ear"], listen_err: ["ear", "bad"], ring_stopped: ["bell"], dns_fallback: ["wifi-off", "bad"],
  admin_open: ["settings"], checkin: ["hand"], rules: ["list"], dev_off: ["settings"], dev_on: ["settings"],
  setting_vol: ["sliders-horizontal"], setting_bright: ["sliders-horizontal"], setting_auto_on: ["sliders-horizontal"], setting_auto_off: ["sliders-horizontal"], setting_timeout: ["sliders-horizontal"], setting_dnd_on: ["sliders-horizontal"], setting_dnd_off: ["sliders-horizontal"] };
const logRow = l => { const [i, tone] = LOG_IC[l.k] || ["circle"];
  return `<div class="lgr${tone ? " " + tone : ""}"><span class="lgi">${ic(i)}</span><span class="sp" style="min-width:0;overflow-wrap:anywhere">${esc(logTr(noEmoji(String(l.m || ""))))}</span><span class="mute lgt">${hhmm(l.t)}</span></div>`; };
// newest first, one heading per day (Hôm nay / Hôm qua / 5 thg 11)
const byDay = (rows, at, draw) => { let last = "", out = "";
  for (const r of rows) { const k = dkey(at(r)); if (k !== last) { last = k; out += `<div class="lgd">${esc(dayLabel(k))}</div>`; } out += draw(r); }
  return out; };
const evHtml = e => `<div class="row" style="padding:3px 0">${ic(EV_IC[e.kind] || "bell")} <span class="sp">${esc(noEmoji(e.text))}</span><span class="mute">${ago(e.t)}</span></div>`;
const zoneOk = z => z && Number.isFinite(+z.lat) && Number.isFinite(+z.lng) && z.lat !== "" && z.lng !== "" && z.lat !== null && z.lng !== null && Math.abs(+z.lat) <= 90 && Math.abs(+z.lng) <= 180;
const newId = p => p + Math.random().toString(36).slice(2, 8);
const clampN = (v, a, b, d) => Math.min(b, Math.max(a, Math.round(+v || d)));
let reqMin = {}, reqNote = {};   // per request id: chosen minutes, typed note (survive redraws)

// built-in templates: names in the dictionary ("tpl.<id>"), read when drawn, so they follow the language
const PRESET_D = [
  { id: "school", rules: { dailyMin: 90, dailyByDay: [], bedtime: { on: true, start: "21:00", end: "06:30" }, schedules: [{ n: "sch.class", on: true, days: [1, 2, 3, 4, 5], start: "07:00", end: "11:30" }] } },
  { id: "weekend", rules: { dailyMin: 180, dailyByDay: [], bedtime: { on: true, start: "22:00", end: "07:30" }, schedules: [] } },
  { id: "summer", rules: { dailyMin: 240, dailyByDay: [], bedtime: { on: true, start: "22:00", end: "07:00" }, schedules: [] } },
  { id: "exams", rules: { dailyMin: 90, dailyByDay: [], bedtime: { on: true, start: "21:00", end: "06:00" }, schedules: [{ n: "sch.study", on: true, days: [1, 2, 3, 4, 5, 6, 7], start: "18:00", end: "20:00" }] } },
];
const presets = () => PRESET_D.map(p => ({ name: t("tpl." + p.id), rules: { ...p.rules, schedules: p.rules.schedules.map(({ n, ...x }) => ({ name: t(n), ...x })) } }));
const TPL_F = ["dailyMin", "dailyByDay", "bedtime", "schedules"];
function userTpl() {
  try { const a = JSON.parse((window.KGApp && KGApp.getTpl ? KGApp.getTpl() : localStorage.getItem("kg.tpl")) || "[]"); return Array.isArray(a) ? a.filter(t => t && typeof t.name === "string" && t.rules && typeof t.rules === "object") : []; } catch { return []; }
}
function saveTpl(a) {   // parent app: in its prefs (the WebView origin changes with the proxy port)
  try { const s = JSON.stringify(a); if (window.KGApp && KGApp.setTpl) KGApp.setTpl(s); else localStorage.setItem("kg.tpl", s); return true; } catch { return false; }
}
const allTpl = () => presets().concat(userTpl());
let tplSel = 0;
const tplFields = (r, F = TPL_F) => { const o = {}; for (const f of F) if (r[f] !== undefined) o[f] = structuredClone(r[f]); return o; };
const limTxt = r => r.dailyByDay && r.dailyByDay.length === 7 ? t("lim.byDay") : r.dailyMin ? t("mins", r.dailyMin) : t("noLimit");
function tplSummary(tp) {
  const r = tp.rules, b = r.bedtime || {};
  return `${r.dailyByDay && r.dailyByDay.length === 7 ? t("tps.byDay") : r.dailyMin ? t("perDay", r.dailyMin) : t("tps.noLimit")} · ${b.on ? t("tps.bed", esc(b.start) + "–" + esc(b.end)) : t("tps.noBed")} · ${t("tps.sch", (r.schedules || []).length)}`;
}
// kids this page can reach right now (LAN page: only this phone; app / relay page: every saved kid)
const reachKids = () => APP || onLan() ? homeKids() : kids.filter(k => k.via === "relay");
async function kidRules(k) {
  const j = await call(k, "/state");
  const r = j.rules ?? (j.state && j.state.rules);   // relay: {state, rules (sent, not yet taken)}; LAN: the state itself
  if (!r) throw new Error(t("noData"));
  return r;
}
// First-pairing starting levels: same template machinery (tplFields), plus 3 extra fields only these carry (docs/protocol.md "Starting levels").
const LV_F = TPL_F.concat(["blockNew", "safeDns", "noInstall"]);
const LV_D = [
  { id: "light", rules: { dailyMin: 180, dailyByDay: [], bedtime: { on: true, start: "22:00", end: "06:30" }, schedules: [], blockNew: false, safeDns: "", noInstall: false } },
  { id: "balanced", rules: { dailyMin: 120, dailyByDay: [120, 120, 120, 120, 120, 180, 180], bedtime: { on: true, start: "21:30", end: "06:30" }, schedules: [], blockNew: true, safeDns: "family.cloudflare-dns.com", noInstall: false } },
  { id: "strict", rules: { dailyMin: 60, dailyByDay: [60, 60, 60, 60, 60, 120, 120], bedtime: { on: true, start: "21:00", end: "06:30" }, schedules: [], blockNew: true, safeDns: "family.cloudflare-dns.com", noInstall: true } },
];
// "untouched" = nothing the parent could have set: no limits, no bedtime/schedules, no app lists/groups/zones, no DNS/install lock
const rulesUntouched = r => !r.dailyMin && !(r.dailyByDay || []).length && !(r.bedtime && r.bedtime.on) && !(r.schedules || []).length
  && !(r.blocked || []).length && !(r.allow || []).length && !Object.keys(r.appMin || {}).length && !(r.groups || []).length
  && !(r.zones || []).length && !(r.hidden || []).length && !r.blockNew && !r.safeDns && !r.noInstall && !r.pausedUntil;
let lvBusy = false;
async function levelsOffer(k) {
  if (lvBusy || k.lv || !edit) return;
  const mark = () => { k.lv = 1; save(kids); if (LOCAL) try { window.KGLocal.lvDone(); } catch {} };
  // Rules already set => never offer, never overwrite. A never-paired preview phone is fine: the first save asks for the PIN in putRules.
  if (!rulesUntouched(edit)) return mark();
  lvBusy = true;
  let pick = "balanced";
  const row = id => { const d = LV_D.find(x => x.id === id);
    return `<label class="app"><input type="radio" name="lv" value="${id}" ${id === pick ? "checked" : ""}><span class="nm"><b>${esc(t("lv." + id))}</b><span class="mute">${tplSummary(d)}${d.rules.blockNew ? " · " + esc(t("lv.new")) : ""}${d.rules.safeDns ? " · " + esc(t("lv.dns")) : ""}${d.rules.noInstall ? " · " + esc(t("lv.noinst")) : ""}</span></span></label>`; };
  const extra = `<div role="radiogroup" aria-label="${esc(t("lv.title"))}" style="margin-bottom:8px">${LV_D.map(d => row(d.id)).join("")}<label class="app"><input type="radio" name="lv" value="later"><span class="nm"><b>${esc(t("lv.later"))}</b></span></label></div>`;
  const collect = () => { const c = document.querySelector('#askPop input[name="lv"]:checked'); if (!c) return false; pick = c.value; return true; };
  const ok = await ask(t("lv.ask", k.name), t("lv.go"), false, "", extra, collect);
  const auto = askAuto; lvBusy = false; if (ok || !auto) mark();   // dismissed by another dialog / screen change: offer again later
  const lv = LV_D.find(x => x.id === pick);
  if (!ok || !lv || k !== cur || !edit || !rulesUntouched(edit)) return;   // re-check: someone may have edited while the dialog was open
  Object.assign(edit, tplFields(lv.rules, LV_F)); changed(); render();
}
async function tplApply(tp) {
  if (!(await ask(t("tpl.askOne", tp.name, cur.name), t("apply")))) return;
  Object.assign(edit, tplFields(tp.rules)); changed(); render();
}
async function tplMulti(tp) {
  const list = reachKids(); toast("send|" + t("tpl.reading"));
  const k0 = cur, e0 = edit;
  const rows = await Promise.all(list.map(async k => { try { return { k, r: k === k0 ? e0 : await kidRules(k) }; } catch (e) { return { k, err: e.message }; } }));
  if (cur !== k0) return;
  let chosen = [];
  const extra = `<div role="group" aria-label="${esc(t("tpl.pickKids"))}" style="margin-bottom:8px">${rows.map((x, i) => `<label class="app"><input type="checkbox" data-pk="${i}" ${x.r ? "checked" : "disabled"}><span class="sw" style="background:${KC[i % KC.length]}"></span><span class="nm"><b>${esc(x.k.name)}</b>${x.err ? `<span class="mute">${esc(x.err)}</span>` : ""}</span>${x.r ? `<span class="tag">${esc(limTxt(x.r))} → ${esc(limTxt({ ...x.r, ...tplFields(tp.rules) }))}</span>` : `<span class="tag warn">${t("tpl.cant")}</span>`}</label>`).join("")}</div>`;
  const collect = () => { chosen = [...document.querySelectorAll("#askPop [data-pk]:checked")].map(c => +c.dataset.pk); return chosen.length > 0; };
  if (!(await ask(t("tpl.askMulti", tp.name), t("apply"), false, "", extra, collect))) return;
  let ok = 0; const bad = [];
  for (const i of chosen) {
    const { k, r } = rows[i];
    try {
      if (k === k0 && e0 === edit) { clearTimeout(saveT); saveT = null; Object.assign(edit, tplFields(tp.rules)); try { await putRules(k, edit); } catch (er) { if (k === cur) saveFail(true); throw er; } }
      else await putRules(k, { ...r, ...tplFields(tp.rules) });
      ok++;
    } catch (er) { bad.push(k.name + ": " + er.message); }
  }
  toast(bad.length ? "err|" + t("tpl.failFor", bad.join("; ")) + (ok ? " " + t("tpl.sentN", ok) : "") : "send|" + t(list[0] && list[0].via === "lan" && !APP ? "tpl.savedFor" : "tpl.sentFor", ok));
  if (cur) render();
}
async function tplSave() {
  let name = "";
  const collect = () => { name = ($("#askName") ? $("#askName").value : "").trim().slice(0, 24); return !!name; };
  const p = ask(t("tpl.askSave"), t("save"), false, "", `<input id="askName" maxlength="24" autocomplete="off" placeholder="${esc(t("tpl.namePh"))}" aria-label="${esc(t("tpl.name"))}" style="width:100%;margin-bottom:12px">`, collect);
  if ($("#askName")) $("#askName").focus();
  if (!(await p)) return;
  const u = userTpl().filter(x => x.name !== name); u.push({ name, rules: tplFields(edit) });
  if (!saveTpl(u.slice(-12))) return toast("err|" + t("tpl.noStore"));
  tplSel = PRESET_D.length + Math.min(u.length, 12) - 1; toast("ok|" + t("tpl.saved", name)); render();
}

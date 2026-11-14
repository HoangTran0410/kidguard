// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- one child ------------------------------------------------------------
let noState = false;   // relay answered {state: null}: the child never synced
let gen = 0;   // bumped by every screen change; async work started for an older screen drops its result
// relay: the phone sends the state only when it changed, so its sync time (seen) says "online", not state.t
function lastSeen() { return cur && cur.via !== "lan" && seenAt ? Math.max(seenAt, st ? st.t : 0) : (st ? st.t : 0); }
async function open(k) { const g = ++gen; if (askDone) askDone(false, true); leaveSave(); pairLocal = null; wantReset(); screenStop(); earStop(); cur = k; cachedAt = 0; viewDay = null; histDays = null; st = null; edit = null; trk = []; trkLoaded = 0; mv = null; sel.on = false; sel.set.clear(); sel.q = ""; sel.n = 40; spaceN = 15; hideDraft = null; noState = false; lastSig = ""; saveFail(false); render(); if ($("#back")) navTo(1); clearInterval(timer); await refresh(); if (g !== gen) return; clearInterval(timer); timer = setInterval(refresh, k.via === "lan" ? 4000 : 8000); }

// One poll at a time (a hung mobile link must not stack them; one stuck > 30 s no longer blocks). Nobody touched the
// page for 2 min -> every 30 s instead of 4 / 8 s (a forgotten tab kept the phone syncing its state every 30 s); a touch
// polls at once and the fast pace is back.
let pollAt = 0, pollBusy = 0, actAt = Date.now();
const IDLE_MS = 120000, IDLE_POLL = 30000;
["pointerdown", "keydown", "wheel", "touchstart"].forEach(ev => addEventListener(ev, () => {
  const was = Date.now() - actAt > IDLE_MS; actAt = Date.now(); if (was && cur) refresh();
}, { passive: true, capture: true }));
async function refresh() {
  if (!cur || viewDay || document.hidden) return;
  const now = Date.now();
  if (pollBusy && now - pollBusy < 30000) return;
  if (now - actAt > IDLE_MS && now - pollAt < IDLE_POLL) return;
  pollAt = now; pollBusy = now;
  try { await refresh1(); } finally { pollBusy = 0; }
}
async function sameKid(k) {
  try { const r = await fetch(lanApi(k.target) + "/hello", { signal: quick(4000) }); const h = r.ok ? await r.json() : null; return !!h && h.id === k.id; } catch { return false; }
}
let retryShow = false;   // a poll is in flight after an error: the banner says "retrying", not the old error
async function refresh1() {
  const k = cur;
  if (lastErr && st) { retryShow = true; updateLive(); }
  try {
    const j = await call(k, "/state");
    retryShow = false;
    if (k !== cur || viewDay) return;
    // LAN: the state itself; relay: {state, rules (sent, not yet taken), seen}
    st = j.state !== undefined ? j.state : j;
    try { lgMerge(); } catch {}   // keep what the phone's capped log / events showed (Nhật ký day view)
    noState = j.state === null;
    pendingRules = j.state !== undefined ? j.rules : null;
    seenAt = j.state !== undefined ? +j.seen || 0 : 0;
    rcSync(k, j.state !== undefined ? j.expired : null);   // command receipts (st.done + the worker's expired list)
    lastErr = ""; lastNet = false;
    if (j.state === undefined) learnAddrs(k, st);   // a LAN answer (relay replies wrap the state)
    settle();
  } catch (e) {
    retryShow = false;
    if (k !== cur) return;
    lastErr = e.message;
    lastNet = !!e.net;
    if (LOCAL && e.raw === "not paired") return localOver();   // the on-device session expired
    // a LAN address can now belong to ANOTHER KidGuard phone (it answers 401 to our token): forget the kid only when /hello still names it
    if ((e.raw === "not paired" || /not paired/.test(e.message)) && (k.via !== "lan" || await sameKid(k)) && k === cur) { gen++; clearInterval(timer); cur = null; kids = kids.filter(x => x !== k); save(kids); navDown(0); toast(t("e.not paired")); return setTimeout(boot, 1000); }
  }
  const busy = document.activeElement && /^(SELECT|INPUT|TEXTAREA)$/.test(document.activeElement.tagName);
  if (st && st.rules) {
    const nw = pendingRules && pendingRules.rev > st.rules.rev ? pendingRules : st.rules;
    if (!edit) { edit = norm(structuredClone(nw)); editBase = structuredClone(edit); wizAuto(k); }
    // another parent (or the child) changed the rules: reload unless we are mid-edit or saving, else a stale page would overwrite them
    else if ((nw.rev || 0) > (edit.rev || 0) && !saveT && !saveBusy && !busy && !saveFailed) { edit = norm(structuredClone(nw)); editBase = structuredClone(edit); lastSig = ""; }
  }
  // redraw the tab only when what it shows changed and nobody is using a control (icons would reload, a select would close)
  const sig = st ? JSON.stringify([tab, st.rules && st.rules.rev, st.apps.map(a => [a.suspended, a.blocked, Math.floor(a.ms / 60000), a.bg, a.bgMine]),
    st.location && st.location.t, (st.log || []).length, ((st.log || []).slice(-1)[0] || {}).t, (st.track || []).length, Math.floor(st.usedMs / 60000), lastErr, cachedAt, viewDay,
    (st.requests || []).map(q => q.id), (st.history || []).length, st.limitMin, st.totpOn, st.unlockUntil, st.now && st.now.why, (st.requests || []).map(q => q.remindAt || 0).join(), rc.map(x => x.s).join(), (st.apps || []).filter(a => a.new).length, (st.events || []).slice(-10).map(e => e.id).join(","), (st.preinstalled || []).map(x => (x.hidden ? 1 : 0) + (x.refused ? 2 : 0)).join(""), (st.devices || []).map(d => d.id + d.name).join(","), (st.rules && st.rules.hidden || []).join(","),
    st.sh && [st.sh.busy, st.sh.tcp, st.sh.tcpUp, (st.sh.log || []).map(e => e.at).join(",")], st.support && [st.support.busy, st.support.err, st.support.code, (st.support.msgs || []).length]]) : lastErr + (noState ? "|nostate" : "");
  // background-limit fields (bg / bgMine) change a few seconds after the command, on the phone's next tick: redraw the open "App chạy nền" sheet then
  const bgSig = st ? (st.apps || []).map(a => (a.bg === false ? 1 : 0) + (a.bgMine ? 2 : 0)).join("") : "";
  if (bgSig !== lastBgSig) { const was = lastBgSig; lastBgSig = bgSig; if (was && typeof hlSheetDraw === "function" && hlSheetDraw && !busy) hlSheetDraw(); }
  if (tab === "screen") updateLive();   // never redraw the screen tab: the <img> updates by itself
  else if (sig !== lastSig && !busy) { lastSig = sig; render(); } else updateLive();
  if (tab === "files" && k === cur) fxSync();   // shared folder list + relay upload delivery (its own redraw)
  if (tab === "set") sysDraw();   // the phone-settings card follows st.sys by itself
}
let lastSig = "", lastBgSig = "";

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
// Rules are a full replace. When the phone's rules moved on while this page was editing (another parent, the Telegram
// bot, the kid's phone), only the fields changed here are laid over the newer rules: the other edit is not undone.
let editBase = null;
const sameJ = (a, b) => JSON.stringify(a) === JSON.stringify(b);
// Array fields (blocked, zones, chores...) and the appMin map merge per item, three-way against editBase: an item only
// the other parent added/removed/edited survives, an item edited on both sides takes this page's version.
const itemKey = o => typeof o === "string" ? "s:" + o : o && typeof o === "object" ? "o:" + (o.pkg || o.id || o.name || JSON.stringify(o)) : "j:" + JSON.stringify(o);
function mergeList(mine, base, srv) {
  const idx = a => { const m = new Map(); for (const o of a) m.set(itemKey(o), o); return m.size === a.length ? m : null; };
  const M = idx(mine), B = idx(base), S = idx(srv);
  if (!M || !B || !S) return mine;   // duplicate keys: no safe per-item merge, this page's list wins
  const out = [];
  for (const [k, o] of S) { if (B.has(k) && !M.has(k)) continue; out.push(B.has(k) && M.has(k) && !sameJ(M.get(k), B.get(k)) ? M.get(k) : o); }
  for (const [k, o] of M) if (!B.has(k) && !S.has(k)) out.push(o);
  return out;
}
function mergeMap(mine, base, srv) {
  const out = { ...srv };
  for (const k of new Set([...Object.keys(mine), ...Object.keys(base)])) if (!sameJ(mine[k], base[k])) { if (k in mine) out[k] = mine[k]; else delete out[k]; }
  return out;
}
function mergeNewer(e) {
  const srv = pendingRules && st && st.rules && (pendingRules.rev || 0) > (st.rules.rev || 0) ? pendingRules : st && st.rules;
  if (!srv || !editBase || (srv.rev || 0) <= (editBase.rev || 0)) return false;
  const n = norm(structuredClone(srv)), mine = {};
  for (const x of new Set([...Object.keys(e), ...Object.keys(editBase)])) if (x !== "rev" && !sameJ(e[x], editBase[x])) mine[x] = e[x];
  const plain = v => v && typeof v === "object" && !Array.isArray(v);
  for (const x of Object.keys(mine)) {
    if (Array.isArray(mine[x]) && Array.isArray(editBase[x]) && Array.isArray(n[x])) mine[x] = mergeList(mine[x], editBase[x], n[x]);
    else if (x === "appMin" && plain(mine[x]) && plain(editBase[x]) && plain(n[x])) mine[x] = mergeMap(mine[x], editBase[x], n[x]);
  }
  for (const x of Object.keys(e)) if (!(x in n) && !(x in mine)) delete e[x];
  Object.assign(e, n, mine);
  editBase = structuredClone(n);
  return true;
}
// PUTs per kid run one after another: two quick edits over a slow relay must not land out of order (the body is built when its turn comes, so it carries the latest edit)
const putQ = new WeakMap();
function putRules(k, e) {
  saveBusy++;   // counted from the moment it is queued: a poll must not reload the rules under a waiting save
  const run = (putQ.get(k) || Promise.resolve()).then(() => putRules1(k, e));
  putQ.set(k, run.catch(() => {}));
  return run.finally(() => { saveBusy--; });
}
async function putRules1(k, e) {
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
    if (k === cur && e === edit && mergeNewer(e)) lastSig = "";   // redraw with the merged values
    const body = { ...e }; delete body.pinHash; delete body.pinSet; if (!body.pin) delete body.pin;
    if (Array.isArray(body.zones)) { const n0 = body.zones.length; body.zones = body.zones.filter(zoneOk); if (body.zones.length < n0 && zoneWarn !== n0) toast("warn|" + t("zone.skipped")); zoneWarn = body.zones.length < n0 ? n0 : 0; }
    const sig0 = JSON.stringify(e), hadPin = !!body.pin, sent = structuredClone(e);   // sent = what this PUT carries = the next merge base (edits made while it is in flight stay "mine")
    if (Array.isArray(body.zones)) sent.zones = structuredClone(body.zones);
    delete sent.pin; delete sent.pinHash; if (hadPin) sent.pinSet = true;
    const r = await call(k, "/rules", "PUT", body); let took = false;
    // LAN: the reply is the phone's normalized rules; take it only if nothing was toggled meanwhile (else keep the edit, just the rev)
    if (k.via === "lan" && r && typeof r === "object" && JSON.stringify(e) === sig0) { for (const x of Object.keys(e)) delete e[x]; Object.assign(e, norm(r)); took = true; }
    else if (r && r.rev) e.rev = r.rev;   // relay: else an older poll (rev of the previous save) looks newer and undoes this edit
    delete e.pin; if (hadPin) e.pinSet = true; delete e.pinHash;
    if (k === cur && e === edit) { if (took) editBase = structuredClone(e); else { if (e.rev) sent.rev = e.rev; editBase = sent; } }
    if (k === cur) { savedAt = Date.now(); paintSave(); setTimeout(paintSave, 3100); }
    if (k === cur) saveFail(false);
  } finally { saveBusy--; }
}
// a failed call as one sentence (same wording as the live banner): the phone answered with an error vs no network
const errSentence = er => er && er.net ? t("errX", er.message) : t("save.phoneErr", er && er.message || "?");
function changed() {
  clearTimeout(saveT);
  if (viewDay) return;   // history snapshot: read-only
  const k = cur, e = edit;
  if (!k || !e) return;
  saveT = setTimeout(async () => {
    saveT = null;
    try { await putRules(k, e); toast(k.via === "lan" ? "ok|" + t("saved") : "send|" + t("sentRelay")); }
    catch (er) { toast("err|" + errSentence(er)); if (k === cur) saveFail(true); }
  }, 500);
}

// Every action that changes something on the child's phone asks first (custom modal: native confirm() looks off in the app).
// typed: the user must type this word (release). Resolves true/false; Esc / backdrop = false.
let askDone = null, askAuto = false;   // askAuto: the last dialog was closed by the page (new dialog, screen change), not by the parent
// info: a title string = a notice, not a question (no Cancel; OK only)
function ask(text, ok = t("agree"), danger = false, typed = "", extra = "", collect = null, info = "") {
  if (askDone) askDone(false, true);
  const pop = $("#askPop");
  pop.innerHTML = `<div class="dp"><h2 id="askT" style="margin:0 0 8px">${ic(info ? "info" : danger ? "triangle-alert" : "circle-help")} ${info ? esc(info) : t("confirm")}</h2>
    <div style="white-space:pre-line;margin-bottom:12px">${esc(text)}</div>
    ${extra}
    ${typed ? `<input id="askIn" autocomplete="off" placeholder="${esc(t("ask.typePh", typed))}" aria-label="${esc(t("ask.typeAria", typed))}" style="width:100%;margin-bottom:12px">` : ""}
    <div class="row"><button id="askNo" class="sp"${info ? ' hidden' : ""}>${ic("x")} ${t("cancel")}</button><button id="askOk" class="sp ${danger ? "bad" : "pri"}" ${typed ? "disabled" : ""}>${ic("check")} ${esc(ok)}</button></div></div>`;
  pop.style.display = "flex";
  return new Promise(res => {
    const done = (v, auto) => { if (askDone !== done) return; askDone = null; askAuto = !!auto; pop.style.display = "none"; pop.innerHTML = ""; res(v); };
    askDone = done;
    const flat = v => String(v).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();   // "GỠ" = "GO" = "go": any keyboard works
    const okNow = () => (!typed || flat($("#askIn").value) === flat(typed)) && (!collect || collect());
    $("#askNo").onclick = () => done(false); $("#askOk").onclick = () => { if (!okNow()) return; done(true); };
    if (typed || collect) { const upd = () => { $("#askOk").disabled = !okNow(); }; pop.querySelectorAll("input").forEach(c => { c.oninput = c.onchange = upd; }); upd(); }
    const first = pop.querySelector("#askIn, #askPin");
    if (first) first.focus(); else $(danger ? "#askNo" : "#askOk").focus();   // Enter must not confirm a reboot / unpair
    if (collect) pop.querySelectorAll("input").forEach(c => { c.onkeydown = e => { if (e.key === "Enter" && okNow()) $("#askOk").click(); }; });
  });
}
$("#askPop").addEventListener("click", e => { if (e.target.id === "askPop" && askDone) askDone(false); });
document.addEventListener("keydown", e => { if (e.key === "Escape" && askDone) askDone(false); });
// confirm text "ask.<type>" + button "askOk.<type>" for commands that need no extra wording; these are the dangerous ones
const ASK_BAD = { reboot: 1, unpairAll: 1, tailscaleOff: 1 };
// One confirm gate for every control with a big effect on the kid's phone or one this page can't undo by itself.
// id -> { on / off: text when a checkbox is turned on / off, click: text for a button; ok: button label key;
// safe: not red }. A text is an i18n key or a function. New risky control = one line here, no handler change.
const CONFIRM = {
  devOff: { click: () => t("dev.askOff") + " " + t(st && st.dev && st.dev.canOn ? "dev.askOffBack" : "dev.askOffNoBack"), ok: "dev.btn" },
  "f-nd": { on: "anti.debugAsk" },
  "f-ni": { on: "anti.installAsk" },
  "f-nv": { on: "dns.novpnAsk" },
  "f-na": { on: "more.airAsk" },
  "f-hm": { on: "hm.askOn", off: "hm.askOff", safe: 1 },
  "f-mon": { off: "mon.askOff", safe: 1 },
  "f-lost": { on: "lost.askOn", off: "lost.askOff", safe: 1 },
};
let confirmPass = null;
function confirmGate(e) {
  const el = e.target, c = el && el.id && CONFIRM[el.id];
  if (!c || el === confirmPass || el.disabled) return;
  const box = el.type === "checkbox";
  if (e.type !== (box ? "change" : "click")) return;
  const key = box ? (el.checked ? c.on : c.off) : c.click;
  if (!key) return;
  e.stopImmediatePropagation(); e.preventDefault();
  const want = el.checked;
  if (box) el.checked = !want;   // stays as it was until the parent agrees
  const k = cur, txt = typeof key === "function" ? key() : t(key);
  ask((k && k.name ? k.name + "\n" : "") + txt, t(c.ok || (box ? (want ? "turnOn" : "turnOff") : "agree")), !c.safe).then(ok => {
    if (!ok || k !== cur || !el.isConnected) return;
    confirmPass = el;   // the real handler runs now, synchronously, without asking again
    try { if (box) { el.checked = want; el.dispatchEvent(new Event("change", { bubbles: true })); } else el.click(); }
    finally { confirmPass = null; }
  });
}
document.addEventListener("change", confirmGate, true);
document.addEventListener("click", confirmGate, true);
const askOf = c => hasT("ask." + c) ? [t("ask." + c), t("askOk." + c), !!ASK_BAD[c]] : null;
// Commands the phone runs only with the parent PIN (or an Authenticator code) inside: guard/CmdGate.kt, protocol
// "PIN-gated commands". Phones before 0.9.13 have no gate (no state.pinResetAt): nothing asked there.
const GATED = new Set(["release", "unpairAll", "unpair", "clearData", "reboot", "relay", "tailscaleOff", "openPairing", "totpSet", "tgSet", "tgCode", "tgOff", "pinSet", "pinResetCancel", "adbShell", "adbTcp"]);
const gateOn = () => !!(st && "pinResetAt" in st && ((edit && edit.pinSet) || st.totpOn));
let pinMem = null;   // {token, pin, at}: the PIN typed for this kid in the last 2 min (a "Dọn máy" run sends clearData per app)
const pinField = () => `<input id="askPin" type="password" inputmode="numeric" autocomplete="off" maxlength="8" placeholder="${esc(t("pin.cmdPh"))}" aria-label="${esc(t("pin.cmdPh"))}" style="width:100%;margin-bottom:6px">
  <p class="mute" style="margin:0 0 12px;font-size:13px">${esc(t("pin.cmdWhy"))}</p>`;
/** Confirm dialog [a] (or a plain "enter the PIN" one) with a PIN field. Resolves the PIN, or null when cancelled. */
async function askPin(k, a) {
  if (pinMem && pinMem.token === k.token && Date.now() - pinMem.at < 120000) { if (!a || await ask(...a)) return pinMem.pin; return null; }
  let pin = "";
  const [text, ok, danger, typed] = a || [t("pin.cmdAsk"), t("agree"), false, ""];
  const yes = await ask(text, ok, danger, typed || "", pinField(), () => { const v = ($("#askPin") || {}).value || ""; pin = v; return /^\d{4,8}$/.test(v); });
  return yes ? pin : null;
}
/** A gated command's answer: true when it was a PIN refusal (toast shown, remembered PIN dropped). */
function pinRefused(r) {
  const x = r && typeof r.result === "string" && r.result.startsWith("failed: pin") ? r.result : "";
  if (!x) return false;
  pinMem = null;
  toast("err|" + t(x === "failed: pin locked" ? "pin.cmdLocked" : x === "failed: pin needed" ? "pin.cmdNeeded" : "pin.cmdWrong"));
  return true;
}
// Commands the worker drops after 10 min when the phone is offline (relay/src/index.js SHORT): a "now" action must not run later by surprise
const SHORT_CMDS = new Set(["lock", "locate", "message", "ring", "wake", "ringStop", "listenStop", "devOn", "devOff", "setting", "reboot", "clearData", "clearCache", "rescan", "bgLimit", "bgRestore", "pinSet", "pinReset", "pinResetCancel", "adbShell", "support", "openPairing", "unlockEnd"]);
async function cmd(c, okMsg, q) {
  const k = cur, a0 = q || (q === false ? null : askOf(c.type)) || (c.type === "message" ? [t("askMsg", c.text), t("send")] : null);
  // dangerous: name the kid in the text, so a dialog for A can never be mistaken for B
  const a = a0 && a0[2] && k && k.name ? [k.name + "\n" + a0[0], ...a0.slice(1)] : a0;
  if (GATED.has(c.type) && gateOn() && !c.pin) {
    const pin = await askPin(k, a || (k && k.name ? [k.name + "\n" + t("pin.cmdAsk"), t("agree")] : null));
    if (pin == null) return;
    c = { ...c, pin };
  } else if (a && !(await ask(...a))) return;
  if (k !== cur) return;   // the screen changed while the dialog was open (notification tap): never send A's command to B
  const plain = { ...c }; delete plain.pin; delete plain.id;
  // relay: our own command id, so the phone's receipt (state.done) or the worker's (expired) can be matched; an answer keeps the request in `req`
  if (k.via !== "lan" && !c.id) c = { ...c, id: newId("p") };
  try {
    const r = await call(k, "/cmd", "POST", c);
    if (c.pin) { if (pinRefused(r)) return; if (!r || r.result !== "queued") pinMem = { token: k.token, pin: c.pin, at: Date.now() }; }   // queued: the phone has not checked the PIN yet
    if (r && r.result && !["ok", "queued", "no such sos"].includes(r.result) && !(c.type === "tailscale" || c.type === "openPairing")) { toast("err|" + tc("cr." + c.type + ".", r.result, t("cmd.answer", r.result))); return; }
    if (r && r.result === "queued") {   // relay: only stored, the phone runs it on its next poll
      const on = Date.now() - lastSeen() < (k.via === "lan" ? 30000 : 120000), gated = !!c.pin, short = SHORT_CMDS.has(c.type);
      toast(gated ? "send|" + t("cmd.queuedPin") : on ? "send|" + t("cmd.queued") : short ? "warn|" + t("cmd.queuedShort") : "warn|" + t("cmd.queuedOff"));
      rcAdd(k, c, plain);
    } else toast("ok|" + (okMsg || t("sent")));
    return r || {};
  } catch (e) { toast("err|" + errSentence(e)); }
}

// ---- command receipts (0.9.16, contract C4): a queued relay command is remembered by id until the phone says it ran
// (state.done) or the worker says it dropped it (expired). s: sent | ok | fail | expired.
const RC_TYPES = new Set(["lock", "wake", "ring", "locate", "message", "reboot", "release", "unpairAll", "unpair", "clearData", "clearCache", "pinSet", "pinReset", "adbShell", "devOn", "devOff", "unlockEnd", "openPairing", "totpSet", "tgSet", "tgOff", "relay", "tailscaleOff", "answer"]);
let rc = [];   // [{k, id, type, c (the command without its PIN), pin (memory only, until a receipt), at, s, res, doneAt}]
function rcAdd(k, c, plain) {
  if (!RC_TYPES.has(c.type) || !c.id) return;
  rc = rc.filter(x => !(x.k === k && x.type === c.type && x.s !== "sent")).concat({ k, id: c.id, type: c.type, c: plain, pin: c.pin || "", at: Date.now(), s: "sent" }).slice(-8);
  updateLive();
}
const rcTtl = type => SHORT_CMDS.has(type) ? 10 * 60e3 : 24 * 3600e3;
function rcSync(k, expired) {
  if (!rc.length || !st) return;
  const done = new Map((st.done || []).map(d => [d.id, d])), exp = new Map((expired || []).map(d => [d.id, d])), now = Date.now();
  for (const x of rc) {
    if (x.k !== k || x.s !== "sent") continue;
    const d = done.get(x.id), e = exp.get(x.id);
    if (d) {
      x.doneAt = +d.at || now; x.res = String(d.res || "");
      x.s = x.res === "ok" ? "ok" : "fail";
      if (x.s === "ok" && x.pin) pinMem = { token: k.token, pin: x.pin, at: now };   // the phone accepted this PIN: only now is it worth remembering
      if (x.res.startsWith("failed: pin")) pinMem = null;
    } else if (e || now - x.at > rcTtl(x.type) + 60000) x.s = "expired";
    if (x.s !== "sent") x.pin = "";
  }
}
// a banner line per receipt of the open kid: [tone, icon, html]; fresh answers fade after a minute, bad ones stay until closed
function rcBans() {
  const now = Date.now(), out = [];
  rc = rc.filter(x => !(x.s === "ok" && now - (x.doneAt || x.at) > 60000) && now - x.at < 36 * 3600e3);
  for (const x of rc) {
    if (x.k !== cur) continue;
    const name = tc("cl.", x.type, x.type), id = esc(x.id);
    const btn = x.s === "expired" ? ` <button type="button" class="pill" data-rcr="${id}">${ic("refresh-cw")} ${t("rc.resend")}</button>` : "";
    const close = x.s === "sent" || x.s === "ok" ? "" : `<button type="button" class="banx" data-rcx="${id}" aria-label="${esc(t("ban.hide"))}" title="${esc(t("ban.hide"))}">${ic("x")}</button>`;
    const body = x.s === "sent" ? t(x.pin ? "rc.sentPin" : SHORT_CMDS.has(x.type) ? "rc.sentShort" : "rc.sentLong", esc(name))
      : x.s === "ok" ? t("rc.ok", esc(name), hhmm(x.doneAt || x.at))
      : x.s === "expired" ? t("rc.expired", esc(name))
      : t("rc.fail", esc(name), esc(x.res.startsWith("failed: pin") ? t(x.res === "failed: pin locked" ? "pin.cmdLocked" : x.res === "failed: pin needed" ? "pin.cmdNeeded" : "pin.cmdWrong") : tc("cr." + x.type + ".", x.res, tc("cr.", x.res, x.res))));
    out.push([x.s === "ok" ? "ok" : x.s === "sent" ? "info" : "warn", x.s === "ok" ? "check" : x.s === "sent" ? "hourglass" : "triangle-alert", body + btn, null, null, close]);
  }
  return out;
}
document.addEventListener("click", e => {
  const b = e.target.closest && e.target.closest("[data-rcr],[data-rcx]"); if (!b) return;
  const id = b.dataset.rcr || b.dataset.rcx, x = rc.find(o => o.id === id); if (!x) return;
  if (b.dataset.rcx) { rc = rc.filter(o => o !== x); return updateLive(); }
  rc = rc.filter(o => o !== x); updateLive();
  if (x.k === cur) cmd({ ...x.c }, null, false);   // sends again; a PIN-gated one asks for the PIN again
});

// ---- more time (0.9.16, contract C1): ONE rule for the page, the phone (Grant.apply) and the parent app (Logic.kt)
// now.why "" / "daily": today's bonus; any other lock (bedtime, school, pause, app limit...): a timed pass `passUntil`
function grantApply(r, s, min) {
  const why = s && s.now ? s.now.why || "" : "", day = (s && s.day) || dkey(Date.now());
  if (why === "" || why === "daily") { const c0 = r.bonus && r.bonus.day === day ? +r.bonus.min || 0 : 0; r.bonus = { day, min: c0 + min }; return "daily"; }
  r.passUntil = Math.max(Date.now(), +r.passUntil || 0) + min * 60000; return "pass";
}
const grantToast = (kind, r, min) => kind === "pass" ? t("pass.done", fmt(min * 60000), whenTxt(r.passUntil)) : t("pk.bonusDone", fmt(min * 60000));
/** Give the open kid more time and save it. Throws when the save fails (the rules are put back). */
async function grant(min) {
  const k = cur, e = edit, prev = { bonus: e.bonus, passUntil: e.passUntil };
  clearTimeout(saveT); saveT = null;
  const kind = grantApply(e, st, min);
  try { await putRules(k, e); } catch (er) { if (e === edit) Object.assign(e, prev); throw er; }
  return kind;
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
const EV_IC = { zoneIn: "map-pin", zoneOut: "route", battery: "battery-full", newApp: "package", clock: "clock", pairing: "link", tamper: "triangle-alert", unlock: "lock-open", checkin: "hand", launcher: "house", adminOpen: "key-round" };
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
const evHtml = e => `<div class="row" style="padding:3px 0">${ic(EV_IC[e.kind] || "bell")} <span class="sp">${esc(noEmoji(e.text))}${e.shot ? ` <button type="button" class="chip" data-shot="${esc(e.shot)}">${ic("camera")} ${t("shot.btn")}</button>` : ""}</span><span class="mute">${ago(e.t)}</span></div>`;
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
  // relay: {state, rules (last PUT, kept after the phone took it)}; LAN: the state itself. The stored PUT wins only while newer
  const s = j.state !== undefined ? j.state || {} : j;
  const r = j.state !== undefined && j.rules && j.rules.rev > ((s.rules || {}).rev || 0) ? j.rules : s.rules;
  if (!r || typeof r !== "object" || !Object.keys(r).length) throw new Error(t("noData"));
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
// the 4 things a starting level sets, one line each (A4.9): time per day, bedtime, new apps, web filter + installs
function lvLines(r) {
  const by = r.dailyByDay && r.dailyByDay.length === 7, mn = by ? Math.min(...r.dailyByDay) : 0, mx = by ? Math.max(...r.dailyByDay) : 0, b = r.bedtime || {};
  return [by && mn !== mx ? t("lv.l.limBy", mn, mx) : t("lv.l.lim", by ? mn : r.dailyMin), t("lv.l.bed", b.start, b.end),
    t(r.blockNew ? "lv.l.newOn" : "lv.l.newOff"), t(r.safeDns ? "lv.l.dnsOn" : "lv.l.dnsOff") + (r.noInstall ? " · " + t("lv.l.noInst") : "")];
}
// the first-pairing offer is the guided setup in wizard.js (wizAuto); lvLines / LV_D above feed its first step
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
      else await putRules(k, { ...(await kidRules(k)), ...tplFields(tp.rules) });   // read again: the dialog may have stayed open
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

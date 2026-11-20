// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- time pickers, sheets, app/time requests, More sheet, quick messages ----
function modeBans() {
  const m = modeBans0();
  return st.rules && st.rules.monitor ? [["warn", "eye", t("mon.ban")]].concat(m) : m;
}
function modeBans0() {   // basic mode / unfinished setup: [tone, icon, text] banners
  if (st.mode === "setup" && st.features) return [["info", "eye", t("mode.preview")]];
  if (st.owner || !st.mode && st.owner !== false) return [];
  const m = st.basic || {}, miss = ["acc", "admin", "usage", "location", "notify", "battery"].filter(k => m[k] === false).map(k => t("perm." + k)).concat(st.listen && st.listen.mic === false ? [t("perm.mic")] : []);   // mic: "listen" after SOS, warn BEFORE it is needed
  if (st.mode !== "basic") return [["bad", "triangle-alert", t("mode.setup", esc(miss.join(", ") || t("mode.items")))]];
  return (m.acc === false ? [["bad", "siren", t("mode.accOff")]] : []).concat([["warn", "info", t("mode.basicS") + (miss.length ? " " + t("mode.miss", esc(miss.join(", "))) : ""), "basic", t("mode.basic")]]);
}
// "Cấm dùng…" / "Cho thêm giờ…" modal on the #askPop layer (Esc / backdrop close it through askDone).
// pause: rules.pausedUntil = end time (ms), "for N min" or "until HH:MM" (passed today = tomorrow), both computed here.
// bonus: rules.bonus = {day, min}, today only, so minutes only.
const partOfDay = h => t(h < 11 ? "pk.p0" : h < 13 ? "pk.p1" : h < 18 ? "pk.p2" : "pk.p3");
function whenTxt(ts) {
  const d = new Date(ts), n = new Date(), tm = new Date(n.getFullYear(), n.getMonth(), n.getDate() + 1);
  const same = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  return same(d, n) ? hhmm(ts) : same(d, tm) ? t("pk.tmr", hhmm(ts), partOfDay(d.getHours())) : hhmm(ts);
}
function untilOf(hm) {   // "HH:MM" -> next such moment (today if still ahead, else tomorrow)
  const [h, m] = String(hm).split(":").map(Number); if (!(h >= 0 && m >= 0)) return 0;
  const d = new Date(); d.setHours(h, m, 0, 0); if (d.getTime() <= Date.now() + 30000) d.setDate(d.getDate() + 1); return d.getTime();
}
// "more time" is a timed pass unless the daily limit is what stops the phone (contract C1)
const passMode = () => { const w = st && st.now && st.now.why || ""; return w !== "" && w !== "daily"; };
function pickTime(kind, active, onOk, onClear) {
  if (askDone) askDone(false, true);
  const pause = kind === "pause", pop = $("#askPop");
  const s = { mode: "dur", n: pause ? 30 : 15, unit: "m", at: "06:00" };
  const chips = pause ? [[10], [30], [60], ["06:00"]] : [[10], [15], [30], [60]];
  const ms = () => s.mode === "at" ? untilOf(s.at) - Date.now() : Math.round(s.n * (s.unit === "h" ? 60 : 1)) * 60000;
  const ok = () => { const v = ms(); return v >= 60000 && v <= 24 * 3600e3; };
  const draw = () => {
    const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v;
    pop.innerHTML = `<div class="dp pk" role="dialog" aria-modal="true" aria-labelledby="askT"><h2 id="askT" style="margin:0 0 4px">${ic(pause ? "pause" : "plus")} ${t(pause ? "pk.pauseH" : "pk.bonusH")}</h2>
      ${active ? `<div class="alert warn" style="font-weight:400;margin:4px 0 10px">${ic(pause ? "pause" : "timer")} ${active}</div>` : ""}
      ${pause ? `<div class="seg" role="tablist" aria-label="${esc(t("pk.pauseH"))}">${[["dur", "timer", t("pk.dur")], ["at", "clock", t("pk.at")]].map(([id, i, n]) => `<button type="button" role="tab" data-pm="${id}" aria-selected="${s.mode === id}">${ic(i)}<span>${n}</span></button>`).join("")}</div>` : ""}
      ${s.mode === "dur" ? `<div class="row stp"><button type="button" id="pkMinus" aria-label="${esc(t("pk.less"))}">${ic("minus")}</button><input id="pkN" type="number" inputmode="decimal" min="1" max="${s.unit === "h" ? 24 : 1440}" step="${s.unit === "h" ? 0.5 : 5}" value="${s.n}" aria-label="${esc(t("pk.amount"))}"><button type="button" id="pkPlus" aria-label="${esc(t("pk.more"))}">${ic("plus")}</button>
        <select id="pkU" aria-label="${esc(t("pk.unit"))}"><option value="m" ${s.unit === "m" ? "selected" : ""}>${t("pk.mins")}</option><option value="h" ${s.unit === "h" ? "selected" : ""}>${t("pk.hours")}</option></select></div>`
      : `<label class="row stp"><span>${t("pk.untilL")}</span><input id="pkAt" type="time" value="${esc(s.at)}" aria-label="${esc(t("pk.at"))}"></label>`}
      ${chipRow(chips.map(([c]) => chipBtn(`data-pc="${c}"`, typeof c === "string" ? t("pk.am6") : fmt(c * 60000), typeof c === "string" ? s.mode === "at" && s.at === c : s.mode === "dur" && ms() === c * 60000)), { style: "margin:10px 0", aria: t("pk.quick") })}
      <div class="pks" role="status">${ok() ? `<b>${pause ? t("pk.sumPause", whenTxt(end)) : t("pk.sumBonus", fmt(v))}</b> <span class="mute">· ${pause ? fmt(v) : passMode() ? t("pk.passTill", whenTxt(end)) : t("pk.today")}</span>` : `<span class="err">${t("pk.bad")}</span>`}</div>
      ${pause ? `<p class="mute" style="margin:6px 0 12px">${t("pause.askNote")}</p>` : `<p class="mute" style="margin:6px 0 12px">${t(passMode() ? "pk.passNote" : "pk.bonusNote")}</p>`}
      <div class="row"><button type="button" id="askNo" class="sp">${ic("x")} ${t("cancel")}</button><button type="button" id="askOk" class="sp pri" ${ok() ? "" : "disabled"}>${ic("check")} ${t(pause ? (active ? "pk.update" : "pause.ok") : "pk.give")}</button></div>
      ${onClear ? `<button type="button" id="pkClear" class="bad" style="width:100%;margin-top:8px">${ic(pause ? "play" : "trash-2")} ${t(pause ? "unpause" : "home.unbonus")}</button>` : ""}</div>`;
    pop.querySelectorAll("[data-pm]").forEach(x => x.onclick = () => { s.mode = x.dataset.pm; redraw("[data-pm][aria-selected=true]"); });
    pop.querySelectorAll("[data-pc]").forEach(x => x.onclick = () => { const c = x.dataset.pc; if (c.includes(":")) { s.mode = "at"; s.at = c; } else { s.mode = "dur"; if (+c % 60 === 0) { s.unit = "h"; s.n = c / 60; } else { s.unit = "m"; s.n = +c; } } redraw(`[data-pc="${c}"]`); });
    const N = $("#pkN");
    if (N) {
      N.oninput = () => { s.n = +String(N.value).replace(",", ".") || 0; sum(); };
      $("#pkU").onchange = e => { const m = ms(); s.unit = e.target.value; s.n = s.unit === "h" ? Math.max(0.5, Math.round(m / 1800000) / 2) : Math.round(m / 60000); redraw("#pkU"); };
      const step = d => { const st0 = s.unit === "h" ? 0.5 : 5; s.n = Math.max(st0, Math.min(s.unit === "h" ? 24 : 1440, Math.round((s.n + d * st0) / st0) * st0)); redraw(d > 0 ? "#pkPlus" : "#pkMinus"); };
      $("#pkMinus").onclick = () => step(-1); $("#pkPlus").onclick = () => step(1);
    }
    if ($("#pkAt")) $("#pkAt").onchange = $("#pkAt").oninput = e => { if (e.target.value) { s.at = e.target.value; sum(); } };
    $("#askNo").onclick = () => askDone && askDone(false);
    $("#askOk").onclick = () => { if (!ok()) return; const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v; askDone && askDone(true); onOk(pause ? end : Math.round(v / 60000)); };
    if ($("#pkClear")) $("#pkClear").onclick = () => { askDone && askDone(true); onClear(); };
  };
  // the summary line follows typing without redrawing (the input keeps focus)
  const sum = () => { const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v, el = pop.querySelector(".pks");
    el.innerHTML = ok() ? `<b>${pause ? t("pk.sumPause", whenTxt(end)) : t("pk.sumBonus", fmt(v))}</b> <span class="mute">· ${pause ? fmt(v) : passMode() ? t("pk.passTill", whenTxt(end)) : t("pk.today")}</span>` : `<span class="err">${t("pk.bad")}</span>`;
    $("#askOk").disabled = !ok();
    pop.querySelectorAll("[data-pc]").forEach(x => { const c = x.dataset.pc, on = c.includes(":") ? s.mode === "at" && s.at === c : s.mode === "dur" && v === c * 60000; x.classList.toggle("on", on); x.setAttribute("aria-pressed", on); }); };
  const redraw = f => { draw(); const x = f && pop.querySelector(f); if (x) x.focus(); };
  draw(); pop.style.display = "flex";
  return new Promise(res => { const done = v => { if (askDone !== done) return; askDone = null; pop.style.display = "none"; pop.innerHTML = ""; res(v); }; askDone = done; const f = $("#pkN") || $("#askOk"); if (f) f.focus(); });
}
// generic sheet on the #askPop layer (bottom sheet on phones, dialog on desktop); Esc / backdrop / #askNo close it
function sheet(html, wire) {
  if (askDone) askDone(false, true);
  const pop = $("#askPop"); pop.innerHTML = html; pop.style.display = "flex";
  return new Promise(res => {
    const done = v => { if (askDone !== done) return; askDone = null; pop.style.display = "none"; pop.innerHTML = ""; res(v); };
    askDone = done;
    const x = pop.querySelector("#askNo"); if (x) x.onclick = () => done(false);
    if (wire) wire(pop, done);
    needMark(pop);   // features the kid's app is too old for (NEED, kid.js)
    const f = pop.querySelector(".close") || pop.querySelector("button"); if (f) f.focus();
  });
}
const sheetHead = (icn, title) => `<div class="shh"><h2 id="askT">${ic(icn)} ${title}</h2><button type="button" class="close" id="askNo" aria-label="${esc(t("close"))}">${ic("x")}</button></div>`;

// time request: compact card on home; note, canned replies and minute chips live in the "Trả lời…" sheet
// N/2, N, 2N (5-minute steps, at least 5); the kid's own N is the middle one
const reqChips = q => { const n = Math.max(5, +q.min || 15); return [...new Set([Math.max(5, Math.round(n / 2 / 5) * 5), n, Math.min(240, n * 2)])]; };
// kind "app" (pkg) or "web" (host in q.app): yes = allow it, no minutes. reqApprove edits the rules object in place.
const reqIsAsk = q => q.kind === "app" || q.kind === "web";
const reqIcon = q => q.kind === "web" ? "globe" : q.kind === "app" ? "package" : "hand";
function reqApprove(r, q) {
  if (q.kind === "web") { r.webAllow = [...new Set([...(r.webAllow || []), q.app])]; r.webBlock = (r.webBlock || []).filter(v => v !== q.app); }
  else { r.approved = [...new Set([...r.approved, q.pkg])]; r.blocked = r.blocked.filter(v => v !== q.pkg); }
}
const reqRemind = q => q.remindAt && q.at && q.remindAt - q.at > 30000 ? ` ${tagEl("warn", "bell", t("req.again", ago(q.remindAt)), "margin:0")}` : "";
function reqCard(q, r) {
  const app = reqIsAsk(q), ch = q.chore ? (r.chores || []).find(c => c.id === q.chore) : null, m = reqMin[q.id] || +q.min, id = esc(q.id), note = (reqNote[q.id] || "").trim();
  const head = app ? t("req.appAsks", esc(cur.name), esc(q.app || q.pkg || "?")) : t("req.asks", esc(cur.name), +q.min);
  return `<div class="card req" role="group" aria-label="${esc(t("req.aria"))}"><div class="row" style="flex-wrap:nowrap;align-items:flex-start"><span class="ib t-warn" style="color:var(--warn);background:rgba(217,119,6,.16)">${ic(reqIcon(q))}</span>
    <div class="sp" style="min-width:0"><b>${head}</b>${q.at || q.t ? ` <span class="mute">· ${ago(q.at || q.t)}</span>` : ""}${reqRemind(q)}
    ${q.chore ? `<div style="margin-top:4px">${tagEl("warn", "star", t("req.chore"), "margin:0")} <span class="tag">${esc(ch ? ch.name : t("req.choreGone"))}</span></div>` : ""}
    ${q.note ? `<div class="mute" style="margin-top:4px">${t("req.note", esc(q.note))}</div>` : ""}
    ${note ? `<div class="mute" style="margin-top:4px">${ic("message-square")} ${esc(note)}</div>` : ""}</div></div>
    ${app ? "" : `${chipRow(reqChips(q).map(v => chipBtn(`data-rm="${id}" data-v="${v}"`, t("fmt.m", v), v === m)), { style: "margin-top:10px", aria: t("minutes") })}`}
    <div class="row" style="margin-top:12px"><button class="pri" data-yes="${id}" data-min="${m}">${ic("circle-check")} ${app ? t("req.appYes") : t("req.yes", m)}</button><button data-no="${id}">${ic("circle-x")} ${t("req.no")}</button><button data-reply="${id}">${ic("message-square")} ${t("req.replyBtn")}</button></div></div>`;
}
function replySheet(q, today) {
  const m = reqMin[q.id] || +q.min, id = esc(q.id);
  return sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead(reqIcon(q), reqIsAsk(q) ? t("req.appAsks", esc(cur.name), esc(q.app || q.pkg || "?")) : t("req.asks", esc(cur.name), +q.min))}
    ${q.note ? `<p class="mute" style="margin-top:0">${t("req.note", esc(q.note))}</p>` : ""}
    <label class="mute" for="f-rn-${id}" style="display:block;margin-top:6px">${t("req.reply")}</label>
    <input id="f-rn-${id}" data-rn="${id}" maxlength="200" autocomplete="off" placeholder="${esc(t("req.replyPh"))}" style="width:100%;margin-top:6px" value="${esc(reqNote[q.id] || "")}">
    ${chipRow(t("req.canned").map(c => chipBtn(`data-cr="${id}" data-v="${esc(c)}"`, esc(c), reqNote[q.id] === c)), { style: "margin-top:8px", aria: t("req.cannedAria") })}
    ${reqIsAsk(q) ? "" : `<div class="mute" style="margin-top:14px">${t("minutes")}</div>
    ${chipRow(reqChips(q).map(v => chipBtn(`data-rm="${id}" data-v="${v}"`, t("fmt.m", v), v === m)), { style: "margin-top:6px", aria: t("minutes") })}`}
    <div class="row" style="margin-top:16px"><button class="sp" data-no="${id}">${ic("circle-x")} ${t("req.no")}</button><button class="sp pri" data-yes="${id}" data-min="${m}">${ic("circle-check")} ${reqIsAsk(q) ? t("req.appYes") : t("req.yes", m)}</button></div></div>`,
    pop => wireReq(pop, today, true));
}
function wireReq(b, today, inSheet) {
  b.querySelectorAll("[data-rn]").forEach(i => i.oninput = () => { reqNote[i.dataset.rn] = i.value; });
  // canned reply: fills the message (tap again = clear); the chip that matches the text shows as pressed
  const crPaint = id => b.querySelectorAll("[data-cr]").forEach(o => { if (o.dataset.cr !== id) return; const on = (reqNote[id] || "") === o.dataset.v; o.classList.toggle("on", on); o.setAttribute("aria-pressed", on); });
  b.querySelectorAll("[data-rn]").forEach(i => i.addEventListener("input", () => crPaint(i.dataset.rn)));
  b.querySelectorAll("[data-cr]").forEach(c => c.onclick = () => {
    const id = c.dataset.cr, v = reqNote[id] === c.dataset.v ? "" : c.dataset.v; reqNote[id] = v;
    const i = [...b.querySelectorAll("[data-rn]")].find(o => o.dataset.rn === id); if (i) i.value = v;
    crPaint(id);
  });
  b.querySelectorAll("[data-rm]").forEach(c => c.onclick = () => {
    const id = c.dataset.rm, v = +c.dataset.v; reqMin[id] = v;
    b.querySelectorAll("[data-rm]").forEach(o => { if (o.dataset.rm !== id) return; const on = +o.dataset.v === v; o.classList.toggle("on", on); o.setAttribute("aria-pressed", on); });
    document.querySelectorAll("[data-yes]").forEach(y => { if (y.dataset.yes === id) { y.dataset.min = v; y.innerHTML = ic("circle-check") + " " + t("req.yes", v); } });
  });
  const noteOf = id => (reqNote[id] || "").trim().slice(0, 200);
  const doneReq = id => { delete reqMin[id]; delete reqNote[id]; };
  const after = () => { if (cur) render(); };
  b.querySelectorAll("[data-yes]").forEach(x => x.onclick = async () => {
    const id = x.dataset.yes, note = noteOf(id), q = (st.requests || []).find(o => o.id === id) || {}, app = reqIsAsk(q);
    if (!(await ask((app ? t("req.askApp", q.app || q.pkg || "?") : t("req.askYes", +x.dataset.min)) + (note ? "\n" + t("req.askNote", note) : "")))) return;
    const n = +x.dataset.min, k = cur, e = edit, prev = { bonus: e.bonus, passUntil: e.passUntil, approved: e.approved, blocked: e.blocked, webAllow: e.webAllow, webBlock: e.webBlock };
    // idempotent per request: if the change was saved but `answer` failed, the next tap only re-sends the answer (never gives the time twice)
    let kind = "daily";
    if (!bonusGiven.has((k.target || "") + k.id + ":" + id)) {
      clearTimeout(saveT); saveT = null;
      if (app) reqApprove(e, q); else kind = grantApply(e, st, n);
      try { await putRules(k, e); } catch (er) { if (e === edit) Object.assign(e, prev); toast("err|" + t("req.bonusFail", er.message)); return; }   // card stays
      bonusGiven.add((k.target || "") + k.id + ":" + id);
    }
    if (k !== cur) return;
    const ans = app ? { type: "answer", req: id, ok: true, always: true, ...(k.via === "lan" ? { id } : {}) } : { type: "answer", req: id, ok: true, min: n, ...(k.via === "lan" ? { id } : {}) };
    if (await cmd({ ...ans, ...(note ? { note } : {}) }, app ? t("req.appDone", q.app || q.pkg || "?") : kind === "pass" ? t("pass.done", fmt(n * 60000), whenTxt(e.passUntil)) : t("req.yesDone", n), false)) { answered.add(id); doneReq(id); }
    after();
  });
  b.querySelectorAll("[data-no]").forEach(x => x.onclick = async () => {
    const id = x.dataset.no, note = noteOf(id);
    if (!(await ask(t("req.askNo") + (note ? "\n" + t("req.askNote", note) : ""), t("req.no"), true))) return;
    if (await cmd({ type: "answer", req: id, ok: false, ...(cur.via === "lan" ? { id } : {}), ...(note ? { note } : {}) }, t("req.noDone"), false)) { answered.add(id); doneReq(id); }
    after();
  });
  if (!inSheet) b.querySelectorAll("[data-reply]").forEach(x => x.onclick = () => { const q = (st.requests || []).find(o => o.id === x.dataset.reply); if (q) replySheet(q, today).then(() => { if (cur && tab === "home") render(); }); });
}
// "Thêm" round button: wake, listen, message, restart
function moreSheet() {
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("zap", t("now"))}
    <div class="grid mo2">${(() => {
      // video call first (the main thing here); with an odd count the first button takes the whole row, restart always its own row (no half-row labels on two lines)
      const b = [viewDay ? "" : `<button type="button" id="vcGo">${ic("video")} ${t("call.go")}</button>`, !viewDay ? `<button type="button" id="ear1" class="act"></button>` : "",
        viewDay ? "" : `<button type="button" id="snapNow">${ic("camera")} ${t("more.snap")}</button>`, viewDay ? "" : `<button type="button" id="camNow">${ic("video")} ${t("cam.more")}</button>`, `<button type="button" id="scrGo"${navOk("screen") ? "" : " disabled"}>${ic("smartphone")} ${t("more.scr")}</button>`, `<button type="button" id="wake" class="act"></button>`].filter(Boolean);
      if (b.length % 2) b[0] = b[0].replace("<button ", '<button style="grid-column:1/-1" ');
      return b.join("") + (isBasic() ? "" : `<button type="button" id="reboot" class="bad" style="grid-column:1/-1">${ic("refresh-cw")} ${t("reboot")}</button>`);
    })()}</div>
    </div>`,
    (pop, done) => {
      pop.querySelector("#scrGo").onclick = () => { done(false); goTab("screen"); };
      const sn = pop.querySelector("#snapNow"); if (sn) sn.onclick = () => { done(false); snapOpen(); };
      const cn = pop.querySelector("#camNow"); if (cn) cn.onclick = () => { done(false); snapOpen(true); };   // Live camera: the same form, titled for it
      const vg = pop.querySelector("#vcGo"); if (vg) vg.onclick = () => { done(false); vcStart(); };
      pop.querySelector("#wake").onclick = () => act("wake", () => cmd({ type: "wake" }, t("wake.done"), false));
      const e1 = pop.querySelector("#ear1"); if (e1) e1.onclick = () => earOn() ? micStop() : ringOn() ? toast("warn|" + t("act.noEar")) : earStart();
      const rb = pop.querySelector("#reboot"); if (rb) rb.onclick = () => cmd({ type: "reboot" }, t("reboot.done")).then(r => { if (r) rebootStart(); });
      actPaint();
    });
}
// "Take photo" from the Now sheet: the same form + flow as Seen > Photo on request (tab-seen.js). Rule watch.cam off: jump to its switch there.
function snapOpen(live) {
  if (!(edit && edit.watch && edit.watch.cam)) { toast("warn|" + t("more.snapOff")); qsGo({ tab: "seen", sec: "#seenCam" }); return; }
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead(live ? "video" : "camera", t(live ? "cam.h" : "seen.ch"))}${seenCamForm()}</div>`, pop => seenCamWire(pop));
}
// Family chat (docs/flows/remote.md 2.15): the thread = st.chat (the phone keeps the last 100, state carries 30) + our own sends
// the phone has not echoed yet (chatPend). The kid's unread count = kid messages newer than the last time this sheet was open.
const chatPend = {};
// Older messages (the phone keeps 100, state carries 30): pulled a page at a time (tab-seen.js pullPage: LAN GET /chat or relay chatPull),
// kept per kid while the page is open. chatMore: undefined = not asked yet (a full state tail may hide older ones), false = nothing older.
const chatOld = {}, chatMore = {};
let chatBusy = "";   // id of the kid whose older page is loading
function chatHasOlder() {
  const k = cur && cur.id, a = (st && st.chat) || [];
  return chatBusy !== k ? (chatMore[k] === undefined ? a.length >= 30 : !!chatMore[k]) : false;
}
async function chatOlder() {
  const k = cur; if (!k || chatBusy === k.id) return;
  const mine = [...(chatOld[k.id] || []), ...((st && st.chat) || [])], before = mine.length ? Math.min(...mine.map(m => m.t)) : 0;
  chatBusy = k.id; chatDraw();
  try {
    const r = await pullPage(k, "/chat", "chatPull", before), box = $("#chatBox"), h0 = box ? box.scrollHeight : 0, t0 = box ? box.scrollTop : 0;
    if (k !== cur) return;
    const have = new Set(mine.map(m => m.id)), add = ((r && r.chat) || []).filter(m => !have.has(m.id));
    chatOld[k.id] = [...(chatOld[k.id] || []), ...add].sort((a, b) => a.t - b.t);
    chatMore[k.id] = !!(r && r.more) && add.length > 0;
    if (chatBusy === k.id) chatBusy = ""; chatDraw();
    if (box) box.scrollTop = t0 + (box.scrollHeight - h0);   // stay where the reader was, the older ones grow above
  } catch (e) { toast("err|" + t("chat.olderFail", errSentence(e))); }
  finally { if (chatBusy === k.id) chatBusy = ""; chatDraw(); }
}
const chatSeenKey = () => "kg.chat." + (cur && cur.id);
function chatSeen() { try { return +localStorage.getItem(chatSeenKey()) || 0; } catch { return 0; } }
function chatUnread() { const s = chatSeen(); return ((st && st.chat) || []).filter(m => m.from === "k" && m.t > s).length; }
const chatGone = new Set();   // attachments deleted from this page (the phone's state says "gone" a sync later)
const chatHide = new Set();   // messages deleted from this page (bulk delete: the phone's state drops them a sync later)
// select mode of the chat thread (select.js): messages (and, if asked, their files) go in one chatDel; our own unsent bubbles are not selectable
const chatSel = selNew({
  get: () => chatList().filter(m => m.id && !m.local && !m.job).map(m => ({ id: m.id, t: m.t, size: m.att && attOk(m.att) ? m.att.size || 0 : 0 })),
  paint: () => { const b = $("#chatBox"); if (b) b.dataset.h = ""; chatDraw(); },
  del: ids => chatSelDel(ids),
});
const selCls = (S, id) => S.on && S.ids.has(id) ? " selon" : "";
async function chatSelDel(ids) {
  const set = new Set(ids), files = chatList().filter(m => set.has(m.id) && m.att && attOk(m.att)), fsz = files.reduce((n, m) => n + (m.att.size || 0), 0);
  let wf = false;
  const extra = files.length ? `<label class="chk" style="display:flex;gap:8px;align-items:center;margin:0 0 12px"><input type="checkbox" id="selFiles"> ${esc(t("sel.chatFiles", files.length, fxSize(fsz)))}</label>` : "";
  const ok = await ask(cur.name + "\n" + t("sel.chatAsk", ids.length), t("sel.del"), true, "", extra, () => { wf = !!($("#selFiles") || {}).checked; return true; });
  if (!ok) { if (!askAuto) msgSheet(true); return false; }   // askAuto: the page closed the dialog (kid switch, another dialog), not the parent
  const done = await selChunks(ids, c => cmd({ type: "chatDel", ids: c, ...(wf ? { files: true } : {}) }, t("sel.done", ids.length), false));
  if (done) { ids.forEach(i => chatHide.add(i)); if (wf) files.forEach(m => { chatGone.add(m.att.id); delete chatFull[attKey(m.att)]; delete chatTh[attKey(m.att)]; }); refresh(); }
  msgSheet(true);
  return !!done;
}
function chatList() {
  const cs = ((st && st.chat) || []).filter(m => !chatHide.has(m.id)), now = Date.now(), k = cur && cur.id;
  if (!cs.length) { chatOld[k] = []; chatMore[k] = undefined; }   // the thread was cleared (by a parent): nothing older either
  const ids = new Set(cs.map(m => m.id)), a = (chatOld[k] || []).filter(m => !ids.has(m.id) && !chatHide.has(m.id)).concat(cs);
  chatPend[k] = (chatPend[k] || []).filter(p => now - p.t < 600e3 && !a.some(m => m.from === "p" && m.text === p.text && m.t >= p.t - 120e3));
  // our own attachments still on their way (or not echoed by the phone's state yet): local bubbles with progress
  const jobs = fx.jobs.filter(j => j.kid === cur && j.kind === "up" && j.chat && !j.gone && now - j.at0 < 600e3
    && !a.some(m => m.att && (m.att.id === j.fid || (m.att.name === j.name && m.att.size === j.size && m.t >= j.at0 - 120e3)))).map(j => ({ from: "p", who: "", text: "", t: j.at0, local: 1, job: j }));
  return a.concat(chatPend[k], jobs).sort((x, y) => x.t - y.t);
}
const durTxt = s => Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0");
const attKey = a => cur.id + "/" + a.id;
const attIc = a => a.kind === "video" ? "video" : a.kind === "image" ? "image" : a.kind === "audio" ? "music" : "inbox";
const attOk = a => a && a.st !== "gone" && !chatGone.has(a.id);
// ---- thumbnails: memory + IndexedDB per kid+id, fetched only when the tile is visible (IntersectionObserver), 2 (relay) / 4 (LAN) at a time
const chatTh = {}, chatThQ = [];   // key -> blob url ("" loading, 0 failed)
let chatThRun = 0;
const thDb = () => thDb.p || (thDb.p = new Promise(res => { try { const q = indexedDB.open("kgchat", 1); q.onupgradeneeded = () => q.result.createObjectStore("th"); q.onsuccess = () => res(q.result); q.onerror = () => res(null); } catch { res(null); } }));
const thGet = async key => { const d = await thDb(); return d ? new Promise(r => { try { const q = d.transaction("th").objectStore("th").get(key); q.onsuccess = () => r(q.result || null); q.onerror = () => r(null); } catch { r(null); } }) : null; };
const thPut = async (key, b) => { try { const d = await thDb(); if (d) d.transaction("th", "readwrite").objectStore("th").put(b, key); } catch {} };
function chatThPump() {
  while (chatThQ.length && chatThRun < (fxRelay() ? 2 : 4)) { chatThRun++; chatThQ.shift()().finally(() => { chatThRun--; chatThPump(); }); }
}
function chatThumb(k, a) {
  const key = k.id + "/" + a.id; if (key in chatTh) return; chatTh[key] = "";
  chatThQ.push(async () => {
    try {
      let b = await thGet(key);
      if (!b) { const g = await fxFetch(k, a, { state: "run", pct: 0 }, true); b = new Blob(g.parts, { type: "image/jpeg" }); if (g.drop) g.drop(); thPut(key, b); }
      chatTh[key] = URL.createObjectURL(b);
    } catch { chatTh[key] = 0; setTimeout(() => { if (chatTh[key] === 0) delete chatTh[key]; }, 60000); }
    document.querySelectorAll("[data-th]").forEach(el => { if (el.dataset.th === key && chatTh[key]) el.querySelector(".cthi").innerHTML = `<img src="${chatTh[key]}" alt="" decoding="async">`; });
  });
  chatThPump();
}
let chatIO = null;
function chatObs() {
  const els = [...document.querySelectorAll("[data-th]")].filter(el => !chatTh[el.dataset.th] && chatTh[el.dataset.th] !== 0); if (!els.length || !cur) return;
  const go = el => { const k = cur; if (k) chatThumb(k, { id: el.dataset.aid }); };
  if (!window.IntersectionObserver) return els.forEach(go);
  if (!chatIO) chatIO = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { chatIO.unobserve(e.target); go(e.target); } }), { rootMargin: "80px" });
  els.forEach(el => chatIO.observe(el));
}
// ---- opening: the whole file as a Blob (LAN GET, or relay fileUp + poll), cached in memory when small
const chatFull = {};
async function chatBlob(k, a, job) {
  const key = k.id + "/" + a.id; if (chatFull[key]) return chatFull[key];
  const g = await fxFetch(k, a, job), b = new Blob(g.parts, { type: a.mime || "application/octet-stream" }); if (g.drop) g.drop();
  if ((a.size || 0) < 25e6) chatFull[key] = b;
  return b;
}
function attHtml(m) {
  const a = m.att; if (!a) return "";
  if (!attOk(a)) return `<em class="cgone">${ic("trash-2")} ${t("chat.gone")}</em>`;
  const key = attKey(a), id = esc(a.id), nm = esc(a.name || ""), meta = fxSize(a.size || 0) + (a.dur ? " · " + durTxt(a.dur) : "") + (a.st === "wait" ? " · " + t("chat.attWait") : "");
  if (a.kind === "image" || a.kind === "video") return `<button type="button" class="cth" data-open="${id}" data-th="${esc(key)}" data-aid="${id}" aria-label="${nm}"><span class="cthi">${chatTh[key] ? `<img src="${chatTh[key]}" alt="" decoding="async">` : ic(attIc(a))}</span>${a.kind === "video" ? `<span class="cpl">${ic("play")}</span>` : ""}</button><i>${esc(meta)}</i>`;
  if (a.kind === "audio") return `<button type="button" class="cfile" data-open="${id}">${ic("play")}<span><b>${t("chat.voice")}</b><i>${esc(meta)}</i></span></button>`;
  return `<button type="button" class="cfile" data-open="${id}">${ic("inbox")}<span><b>${nm}</b><i>${esc(meta)}</i></span></button>`;
}
function chatJobHtml(j) {   // not pendHtml: that name is shell.js's (a 2nd top-level function silently replaced it: INCIDENT 2026-11-17 "NaN MB")
  const bad = /^(err|stop)$/.test(j.state);
  return `<span class="cjn">${ic(/^voice/.test(j.name) ? "music" : "upload")} <b>${esc(j.name)}</b> <span class="mute">${fxSize(j.size)}</span></span>
    <i data-cjt="${j.id}">${esc(fxTxt({ ...j, pct: 0 }))}</i>${fxBar({ ...j, id: "c" + j.id })}
    <span class="cjb">${/^(queue|run|wait)$/.test(j.state) ? `<button type="button" data-jc="${j.id}">${ic("x")} ${t("fx.cancel")}</button>` : ""}${bad && !j.big ? `<button type="button" data-jr="${j.id}">${ic("refresh-cw")} ${t("fx.retry")}</button>` : ""}${/^(err|stop|done)$/.test(j.state) ? `<button type="button" data-jd="${j.id}" aria-label="${esc(t("fx.dismiss"))}">${ic("x")}</button>` : ""}</span>`;
}
function chatLow() {
  const f = st && st.freeMb;
  return typeof f === "number" && f >= 0 && f < 1024 ? alertBox("warn", "hard-drive", `${esc(t("chat.low", Math.round(f)))} <br><button type="button" class="sp" id="lowGo" style="margin-top:6px">${t("chat.lowGo")}</button>`, "margin:0 0 8px") : "";
}
function chatDraw() {
  const box = $("#chatBox"); if (!box) return false;
  const low = $("#chatLow"); if (low) { const h = chatLow(); if (low.dataset.h !== h) { low.dataset.h = h; low.innerHTML = h; } }
  // a player is running: leave the DOM alone (a redraw would cut the sound), only the progress below is refreshed
  const playing = [...box.querySelectorAll("audio")].some(a => !a.paused && !a.ended);
  const l = chatList(), near = box.scrollHeight - box.scrollTop - box.clientHeight < 60, days = new Map(selDays(chatSel.get()).map(d => [d.key, d]));
  box.classList.toggle("selmode", chatSel.on);
  const cmp = $("#cmpRow"), sb = $("#chatSelBar"); if (cmp && !rec.mr) cmp.hidden = chatSel.on;
  if (sb) { const h = selBar(chatSel); const sg = $("#chatSelB"); if (sg) sg.hidden = chatSel.on || !l.some(m => m.id && !m.local); if (sb.dataset.h !== h) { sb.dataset.h = h; sb.innerHTML = h; } }
  const html = (chatHasOlder() || chatBusy === cur.id ? `<button type="button" id="chatOld" class="sp" style="align-self:center;margin:2px 0 6px"${chatBusy === cur.id ? " disabled" : ""}>${ic("history")} ${t(chatBusy === cur.id ? "chat.olderBusy" : "chat.older")}</button>` : "") + (l.length ? l.map((m, i) => (chatSel.on && m.id && !m.local && !m.job && (!i || selDayKey(m.t) !== selDayKey(l[i - 1].t)) ? selDayHead(chatSel, days.get(selDayKey(m.t)) || { key: selDayKey(m.t), ids: [] }, esc(selDayLabel(selDayKey(m.t)))) : "") + `<div class="cb ${m.from === "k" ? "k" : "p"}${m.local ? " pend" : ""}${selCls(chatSel, m.id)}"${m.id && !m.local && !m.job ? ` data-sid="${esc(m.id)}"` : ""}${m.att && attOk(m.att) ? ` data-m="${esc(m.att.id)}"` : ""}>${m.from === "k" ? `<b>${esc(cur.name)}</b>` : m.who ? `<b>${esc(m.who)}</b>` : ""}${m.job ? chatJobHtml(m.job) : `${m.text ? `<span>${esc(m.text)}</span>` : ""}${attHtml(m)}${m.att && attOk(m.att) ? `<button type="button" class="cmo" data-menu="${esc(m.att.id)}" aria-label="${esc(t("chat.more"))}">${ic("ellipsis")}</button>` : ""}<i>${m.local ? t("chat.wait") : esc(evAt(m.t))}</i>`}</div>`).join("")
    : `<p class="mute" style="margin:auto;text-align:center">${t("chat.empty")}</p>`);
  const clr = $("#chatClr"); if (clr) clr.hidden = !((st && st.chat) || []).length;
  if (chatSel.on) { const mm = $("#chatMenu"); if (mm) mm.hidden = true; }
  if (box.dataset.h !== html && !playing) { box.dataset.h = html; box.innerHTML = html; if (near || !box.dataset.s) box.scrollTop = box.scrollHeight; box.dataset.s = "1"; chatObs(); }
  for (const m of l) if (m.job) { const x = box.querySelector(`[data-cjt="${m.job.id}"]`), p = box.querySelector(`[data-fxp="c${m.job.id}"]`); if (x) x.textContent = fxTxt(m.job); if (p) p.style.width = Math.floor(m.job.pct * 100) + "%"; }
  const last = Math.max(0, ...((st && st.chat) || []).map(m => m.t));
  if (last > chatSeen()) { try { localStorage.setItem(chatSeenKey(), String(last)); } catch {} }
  return true;
}
const chatAtt = id => { for (const m of chatList()) if (m.att && m.att.id === id) return m.att; return null; };
// ---- one attachment: viewer sheet (image / video / audio inline, file = download), menu, delete
function attMenu(a, back) {
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead(attIc(a), esc(a.name || t("chat.voice")))}
    <div class="grid mo2"><button type="button" id="amO">${ic("play")} ${t("chat.open")}</button><button type="button" id="amG">${ic("download")} ${t("chat.get")}</button>
    <button type="button" id="amD" class="bad" style="grid-column:1/-1">${ic("trash-2")} ${t("chat.delFile")}</button></div></div>`, (pop, done) => {
    pop.querySelector("#amO").onclick = () => attOpen(a, back);
    pop.querySelector("#amG").onclick = () => { done(false); attGet(a); };
    pop.querySelector("#amD").onclick = () => attDel(a, back);
  });
}
function attGet(a) { const b = chatFull[attKey(a)]; if (b) fxSave(b, a.name || "file"); else fxGet(a); }
async function attDel(a, back) {
  if (!(await ask(t("chat.delAsk", a.name || ""), t("fx.del"), true))) return askAuto ? undefined : back && back();
  const r = await cmd({ type: "chatFileDel", id: a.id }, t("chat.delDone"), false);
  if (r) { chatGone.add(a.id); delete chatFull[attKey(a)]; delete chatTh[attKey(a)]; }
  if (back) back();
}
function attOpen(a, back) {
  const k = cur; let alive = true;
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead(attIc(a), esc(a.name || t("chat.voice")))}
    <div id="avBox" class="avbox mute" role="status">${t("chat.loading", 0)}</div>
    <div class="row"><button type="button" id="avBack" class="sp">${ic("chevron-left")} ${t("chat.back")}</button><button type="button" id="avGet" class="sp">${ic("download")} ${t("chat.get")}</button><button type="button" id="avDel" class="sp bad">${ic("trash-2")} ${t("chat.delFile")}</button></div></div>`, pop => {
    pop.querySelector("#avBack").onclick = () => { alive = false; back && back(); };
    pop.querySelector("#avGet").onclick = () => attGet(a);
    pop.querySelector("#avDel").onclick = () => { alive = false; attDel(a, back); };
    const job = { state: "run", pct: 0 }, iv = setInterval(() => { const b = $("#avBox"); if (!b || !alive) return clearInterval(iv); if (!b.classList.contains("mute")) return; b.textContent = job.state === "wait" ? t("fx.getWait") : t("chat.loading", Math.floor(job.pct * 100)); }, 400);
    (async () => {
      try {
        const b = await chatBlob(k, a, job), box = $("#avBox"); if (!box || !alive || k !== cur) return;
        const u = URL.createObjectURL(b); box.classList.remove("mute");
        box.innerHTML = a.kind === "image" ? `<img src="${u}" alt="${esc(a.name || "")}">` : a.kind === "video" ? `<video controls playsinline autoplay src="${u}"></video>` : a.kind === "audio" ? `<audio controls autoplay src="${u}"></audio>` : `${ic("check")} ${esc(a.name || "")} (${fxSize(a.size || 0)})`;
        if (a.kind === "file") attGet(a);
      } catch (e) { const box = $("#avBox"); if (box && alive) box.textContent = t("chat.loadFail", fxErr(e)); }
      finally { clearInterval(iv); }
    })();
  });
}
// ---- gallery: every attachment of the loaded thread (state 30 + pulled pages), filter chips, thumbs grid + list
const gal = { f: "", size: false };
function galItems() {
  const seen = new Set(), all = chatList().filter(m => m.att && attOk(m.att) && !seen.has(m.att.id) && seen.add(m.att.id)).map(m => ({ ...m.att, t: m.t, from: m.from }));
  return all.filter(a => !gal.f || a.kind === gal.f).sort((x, y) => gal.size ? (y.size || 0) - (x.size || 0) : y.t - x.t);
}
// bulk delete of the gallery files (the messages stay, their file shows "deleted"): one chatFileDel {ids} per 200
const galSel = selNew({
  get: () => galItems().map(a => ({ id: a.id, t: a.t, size: a.size || 0 })),
  paint: () => galDraw(),
  del: async ids => {
    const sz = galSel.get().reduce((n, i) => n + (ids.includes(i.id) ? i.size : 0), 0);
    if (!(await ask(cur.name + "\n" + t("sel.galAsk", ids.length, fxSize(sz)), t("sel.del"), true))) { if (!askAuto) galOpen({ size: gal.size }, true); return false; }
    const done = await selChunks(ids, c => cmd({ type: "chatFileDel", ids: c }, t("sel.done", ids.length), false));
    if (done) { const set = new Set(ids); chatList().forEach(m => { if (m.att && set.has(m.att.id)) { chatGone.add(m.att.id); delete chatFull[attKey(m.att)]; delete chatTh[attKey(m.att)]; } }); refresh(); }
    galOpen({ size: gal.size }, true);
    return !!done;
  },
});
function galOpen(o, keep) {
  if (!cur) return; gal.size = !!(o && o.size);
  if (!keep) { galSel.on = false; galSel.ids.clear(); galSel.range = ""; }
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("image", t("chat.gal"))}
    <div class="chips" id="galF" role="group"></div><div id="galB"></div><div id="galS"></div></div>`, pop => {
    selPress(pop.querySelector("#galB"), galSel);
    pop.querySelector("#galS").onclick = e => selClick(galSel, e);
    pop.onchange = e => { selChange(galSel, e); };
    pop.querySelector("#galF").onclick = e => { const b = e.target.closest("[data-f]"); if (!b) return; if (b.dataset.f === "size") gal.size = !gal.size; else gal.f = gal.f === b.dataset.f ? "" : b.dataset.f; galDraw(); };
    pop.querySelector("#galB").onclick = async e => {
      if (selClick(galSel, e)) return;
      if (e.target.closest("#galOld")) { await chatOlder(); return galDraw(); }
      const b = e.target.closest("[data-open]"); if (b) { const a = chatAtt(b.dataset.open); if (a) attOpen(a, () => galOpen({ size: gal.size })); }
    };
    galDraw();
  });
}
function galDraw() {
  const F = $("#galF"), B = $("#galB"); if (!F || !B || !cur) return;
  const l = galItems(), S = galSel, byDay = S.on && !gal.size, G = $("#galS");
  B.classList.toggle("selmode", S.on);
  if (G) { const h = selBar(S); if (G.dataset.h !== h) { G.dataset.h = h; G.innerHTML = h; } }
  F.innerHTML = [["image", "chat.fImg"], ["video", "chat.fVid"], ["audio", "chat.fAud"], ["file", "chat.fFile"]].map(([v, k]) => `<button type="button" class="${gal.f === v ? "on" : ""}" data-f="${v}" aria-pressed="${gal.f === v}">${t(k)}</button>`).join("")
    + `<button type="button" class="${gal.size ? "on" : ""}" data-f="size" aria-pressed="${gal.size}">${ic("hard-drive")} ${t("chat.sortSize")}</button>`;
  const sid = a => ` data-sid="${esc(a.id)}"`, cls = a => selCls(S, a.id);
  const tile = a => `<button type="button" class="gt${cls(a)}"${sid(a)} data-open="${esc(a.id)}" data-th="${esc(attKey(a))}" data-aid="${esc(a.id)}" aria-label="${esc(a.name || "")}"><span class="cthi">${chatTh[attKey(a)] ? `<img src="${chatTh[attKey(a)]}" alt="" decoding="async">` : ic(attIc(a))}</span>${a.kind === "video" ? `<span class="cpl">${ic("play")}</span>` : ""}<i>${fxSize(a.size || 0)}</i></button>`;
  const row = a => `<button type="button" class="cfile${cls(a)}"${sid(a)} data-open="${esc(a.id)}">${ic(attIc(a))}<span><b>${esc(a.kind === "audio" ? t("chat.voice") : a.name || "")}</b><i>${fxSize(a.size || 0)}${a.dur ? " · " + durTxt(a.dur) : ""} · ${esc(evAt(a.t))}</i></span></button>`;
  const media = l.filter(a => a.kind === "image" || a.kind === "video"), rest = l.filter(a => !(a.kind === "image" || a.kind === "video"));
  const days = new Map(selDays(l).map(d => [d.key, d])), dh = (a, i, arr) => byDay && (!i || selDayKey(a.t) !== selDayKey(arr[i - 1].t)) ? selDayHead(S, days.get(selDayKey(a.t)), esc(selDayLabel(selDayKey(a.t)))) : "";
  const sel0 = l.length && !S.on ? `<div class="row" style="justify-content:flex-end">${selBtn(S)}</div>` : "";
  B.innerHTML = sel0 + (l.length ? (media.length ? `<div class="ggrid">${media.map((a, i) => dh(a, i, media) + tile(a)).join("")}</div>` : "") + (rest.length ? `<div class="glist">${rest.map((a, i) => dh(a, i, rest) + row(a)).join("")}</div>` : "") : `<p class="mute">${t("chat.galEmpty")}</p>`)
    + (chatHasOlder() || chatBusy === cur.id ? `<button type="button" id="galOld" class="sp" style="margin-top:10px"${chatBusy === cur.id ? " disabled" : ""}>${ic("history")} ${t(chatBusy === cur.id ? "chat.olderBusy" : "chat.older")}</button>` : "");
  chatObs();
}
// ---- composer: attach menu + voice note (MediaRecorder; getUserMedia needs a secure context, like the video call: rtc.js can())
const chatSecure = () => !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder);
const rec = { mr: null, chunks: [], t0: 0, iv: 0, held: false, cancel: false, starting: false, x0: 0, down: 0 };
function recUi(on) {
  const b = $("#recBar"), c = $("#cmpRow"); if (!b || !c) return;
  b.hidden = !on; c.hidden = on;
  if (on) recTick();
}
function recTick() { const s = (Date.now() - rec.t0) / 1000, el = $("#recT"); if (el) el.textContent = durTxt(s); if (s >= 120) recStop(false); }
async function recStart() {
  if (rec.mr || rec.starting) return; rec.starting = true; rec.held = true; rec.cancel = false;
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
  catch { rec.starting = false; rec.held = false; return toast("err|" + t("chat.micDenied")); }
  if (!$("#recBar")) { stream.getTracks().forEach(x => x.stop()); rec.starting = false; rec.held = false; return; }   // the chat sheet closed while the permission prompt was open
  const mt = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find(m => MediaRecorder.isTypeSupported(m));
  if (!mt) { stream.getTracks().forEach(x => x.stop()); rec.starting = false; rec.held = false; return toast("err|" + t("chat.micFail")); }
  const mr = new MediaRecorder(stream, { mimeType: mt }), kid = cur; rec.chunks = [];
  mr.ondataavailable = e => { if (e.data && e.data.size) rec.chunks.push(e.data); };
  mr.onstop = () => {
    stream.getTracks().forEach(x => x.stop()); clearInterval(rec.iv); rec.mr = null; recUi(false);
    const s = (Date.now() - rec.t0) / 1000;
    if (rec.cancel || !rec.chunks.length) return;
    if (s < 0.8) return toast("warn|" + t("chat.recShort"));
    const type = mt.split(";")[0], f = new File(rec.chunks, "voice-" + new Date().toISOString().slice(0, 19).replace(/[-:T]/g, "") + "." + (type === "audio/mp4" ? "m4a" : "webm"), { type });
    if (kid === cur) { fxSend([f], { chat: true }); chatDraw(); }
  };
  rec.mr = mr; rec.starting = false; rec.t0 = Date.now(); mr.start(1000);
  rec.iv = setInterval(recTick, 250); recUi(true);   // a quick tap (< 0.4 s) keeps recording: the strip has Send / Cancel
}
function recStop(cancel) { if (!rec.mr) return; rec.cancel = !!cancel; try { rec.mr.stop(); } catch {} }
function chatAttach(files) { if (files && files.length) { fxSend(files, { chat: true }); chatDraw(); } }
// "Nhắn" tile: the family chat with the kid (a send is the `message` command: it pops up on the kid's screen and joins the thread);
// attachments = the Files upload paths with chat=1 (the phone adds the chat entry); saved messages one tap away (same list as the watch)
function msgSheet(keep) {
  if (!keep) { chatSel.on = false; chatSel.ids.clear(); chatSel.range = ""; }
  clearInterval(msgSheet.iv);   // reopened within 4 s (delete confirm, menu "back"): one timer only
  const sid = msgSheet.iv = setInterval(() => { if (!chatDraw()) clearInterval(sid); else refresh(); }, 4000);
  const sec = chatSecure(), noF = FXNO;
  const head = `<div class="shh"><h2 id="askT">${ic("message-square")} ${t("chat.title", esc(cur.name))}</h2><button type="button" class="close" id="chatMore" aria-haspopup="menu" aria-expanded="false" aria-label="${esc(t("chat.more"))}">${ic("ellipsis")}</button><button type="button" class="close" id="askNo" aria-label="${esc(t("close"))}">${ic("x")}</button></div>
    <div id="chatMenu" class="pmenu phd" role="menu" hidden><button type="button" role="menuitem" id="chatGalB">${ic("image")} ${t("chat.gal")}</button><button type="button" role="menuitem" id="chatSelB" data-sel="start" hidden>${ic("check")} ${t("sel.start")}</button><button type="button" role="menuitem" id="chatClr" class="bad" hidden>${ic("trash-2")} ${t("chat.clear")}</button></div>`;
  sheet(`<div class="dp cdp" role="dialog" aria-modal="true" aria-labelledby="askT">${head}
    <div id="chatLow"></div><div id="chatBox" class="chat" aria-live="polite"></div><div id="chatSelBar"></div>
    <div id="qmBox"></div>
    <div class="cmpw">
    <div class="row cmp" id="cmpRow">${noF ? "" : `<button type="button" id="attBtn" class="ib2" aria-label="${esc(t("chat.att"))}" aria-expanded="false">${ic("plus")}</button>`}<input id="msg" class="sp" placeholder="${esc(t("msgPh"))}" maxlength="300">${noF || !sec ? "" : `<button type="button" id="micBtn" class="ib2" aria-label="${esc(t("chat.mic"))}" title="${esc(t("chat.mic"))}">${ic("mic")}</button>`}<button type="button" id="send" class="pri">${ic("send")} <span class="sn">${t("send")}</span></button></div>
    <div class="row" id="recBar" hidden><span class="rdot"></span><b id="recT">0:00</b><span class="mute sp" style="flex:1">${t("chat.recHint")}</span><button type="button" id="recNo" class="ib2" aria-label="${esc(t("cancel"))}">${ic("x")}</button><button type="button" id="recOk" class="pri ib2" aria-label="${esc(t("send"))}">${ic("send")}</button></div>
    ${noF ? "" : `<div id="attMenu" role="menu" class="pmenu pat" hidden><button type="button" data-p="media">${ic("image")} ${t("chat.pickMedia")}</button><button type="button" data-p="file">${ic("inbox")} ${t("chat.pickFile")}</button>${sec ? `<button type="button" data-p="cam">${ic("camera")} ${t("chat.pickCam")}</button><button type="button" data-p="vid">${ic("video")} ${t("chat.pickVid")}</button>` : ""}</div>
    </div>
    ${sec ? "" : `<p class="mute" id="attWhy" hidden>${ic("info")} ${t("chat.secure")}</p>`}
    <input type="file" id="fMedia" accept="image/*,video/*" multiple hidden><input type="file" id="fFile" multiple hidden><input type="file" id="fCam" accept="image/*" capture="environment" hidden><input type="file" id="fVid" accept="video/*" capture="environment" hidden>`}
    </div>`,
    pop => {
      const box = pop.querySelector("#chatBox");
      const sbar = pop.querySelector("#chatSelBar");
      selPress(box, chatSel);
      sbar.onclick = e => selClick(chatSel, e);
      pop.onchange = e => { selChange(chatSel, e); };
      box.onclick = e => {
        if (selClick(chatSel, e)) return;
        if (e.target.closest("#chatOld")) return chatOlder();
        const jb = e.target.closest("[data-jc],[data-jr],[data-jd]");
        if (jb) { const id = jb.dataset.jc || jb.dataset.jr || jb.dataset.jd, job = fx.jobs.find(q => q.id === id); if (!job) return;
          if (jb.dataset.jc) fxCancel(job); else if (jb.dataset.jr) { job.state = "queue"; job.pct = 0; job.fid = null; fxPaint(); fxPump(); } else job.gone = true;
          return chatDraw(); }
        const mo = e.target.closest("[data-menu]"); if (mo) { const a = chatAtt(mo.dataset.menu); if (a) attMenu(a, msgSheet); return; }
        const op = e.target.closest("[data-open]"); if (op) { const a = chatAtt(op.dataset.open); if (a) attOpen(a, msgSheet); }
      };
      // long press on a bubble = select mode (select.js); the file menu stays on the bubble's "..." button
      const mm = pop.querySelector("#chatMenu"), mb = pop.querySelector("#chatMore"), dp = pop.querySelector(".dp");
      const mShut = () => { mm.hidden = true; mb.setAttribute("aria-expanded", "false"); const am = pop.querySelector("#attMenu"); if (am) { am.hidden = true; const ab2 = pop.querySelector("#attBtn"); if (ab2) ab2.setAttribute("aria-expanded", "false"); const w = pop.querySelector("#attWhy"); if (w) w.hidden = true; } };
      mb.onclick = () => { const o = mm.hidden; mShut(); mm.hidden = !o; mb.setAttribute("aria-expanded", String(o)); };
      dp.addEventListener("click", e => { if (!e.target.closest("#chatMenu,#chatMore,#attMenu,#attBtn")) mShut(); });
      mm.onclick = e => { const b = e.target.closest("button"); if (!b) return; if (b.id !== "chatClr") mShut(); if (b.id === "chatGalB") galOpen(); else if (b.id === "chatSelB") { selClick(chatSel, e); chatDraw(); } };
      pop.querySelector("#chatLow").onclick = e => { if (e.target.closest("#lowGo")) galOpen({ size: true }); };
      // destructive: our own confirm (with "also delete photos and files"), then the thread is empty on the phone; the sheet closed for the confirm, so it opens again
      pop.querySelector("#chatClr").onclick = async () => {
        let wf = false;
        const ok = await ask(cur.name + "\n" + t("ask.chatClear"), t("askOk.chatClear"), true, "", `<label class="chk" style="display:flex;gap:8px;align-items:center;margin:0 0 12px"><input type="checkbox" id="clrFiles"> ${t("chat.clrFiles")}</label>`, () => { wf = !!($("#clrFiles") || {}).checked; return true; });
        if (!ok) return askAuto ? undefined : msgSheet();
        const r = await cmd({ type: "chatClear", ...(wf ? { files: true } : {}) }, t("chat.cleared"), false);
        if (r) { const k = cur.id; chatOld[k] = []; chatMore[k] = undefined; chatPend[k] = []; if (st && r.result !== "queued") st.chat = []; refresh(); }   // relay "queued": the phone has not cleared yet, the thread stays until its state says so
        msgSheet();
      };
      pop.querySelector("#send").onclick = () => { const tx = $("#msg").value.trim(); if (!tx) return $("#msg").focus();
        const sb = pop.querySelector("#send"); if (sb.disabled) return; sb.disabled = true;   // no double send while the dialog / request is in flight
        cmd({ type: "message", text: tx }, t("msg.done")).then(r => { if (r) { (chatPend[cur.id] = chatPend[cur.id] || []).push({ from: "p", who: "", text: tx, t: Date.now(), local: 1 }); chatDraw(); if ($("#msg") && $("#msg").value.trim() === tx) $("#msg").value = ""; } }).finally(() => { sb.disabled = false; }); };
      pop.querySelector("#msg").onkeydown = e => { if (e.key === "Enter") pop.querySelector("#send").click(); };
      pop.querySelector("#msg").oninput = qmDraw;
      const ab = pop.querySelector("#attBtn");
      if (ab) {
        const menu = pop.querySelector("#attMenu"), why = pop.querySelector("#attWhy");
        ab.onclick = () => { const o = menu.hidden; mShut(); menu.hidden = !o; if (why) why.hidden = menu.hidden; ab.setAttribute("aria-expanded", String(!menu.hidden)); };
        const ids = { media: "#fMedia", file: "#fFile", cam: "#fCam", vid: "#fVid" };
        menu.onclick = e => { const b = e.target.closest("[data-p]"); if (b) pop.querySelector(ids[b.dataset.p]).click(); };
        for (const i of Object.values(ids)) pop.querySelector(i).onchange = e => { menu.hidden = true; if (why) why.hidden = true; chatAttach(e.target.files); e.target.value = ""; };
      }
      const mic = pop.querySelector("#micBtn");
      if (mic) {
        mic.oncontextmenu = e => e.preventDefault();
        mic.onpointerdown = e => { rec.x0 = e.clientX; rec.down = Date.now(); try { mic.setPointerCapture(e.pointerId); } catch {} recStart(); };
        mic.onpointermove = e => { if (rec.mr && rec.held && e.clientX - rec.x0 < -70) { rec.held = false; recStop(true); } };
        mic.onpointerup = mic.onpointercancel = () => { if (!rec.held) return; rec.held = false; if (Date.now() - rec.down >= 400) recStop(false); };
        pop.querySelector("#recNo").onclick = () => recStop(true);
        pop.querySelector("#recOk").onclick = () => recStop(false);
      }
      chatDraw(); qmDraw();
    }).then(() => { recStop(true); clearInterval(sid); });
}
// Saved quick messages (rules.msgs, also listed on the watch): a tap fills the box (Send stays the one way to send),
// x removes, "Lưu làm mẫu" keeps what is typed. Saved through the normal rules save (changed()).
function qmDraw() {
  const box = $("#qmBox"), inp = $("#msg"); if (!box || !inp) return;
  const list = (edit && edit.msgs) || [], tx = inp.value.trim().slice(0, 120);
  const canSave = edit && !viewDay && tx && !list.includes(tx) && list.length < 12;
  box.innerHTML = list.length || canSave ? `<div class="qms">${list.map((m, i) =>
    `<span class="qm"><button type="button" class="qmu" data-q="${i}">${esc(m)}</button><button type="button" class="qmx" data-x="${i}" aria-label="${esc(t("qm.del", m))}">${ic("x")}</button></span>`).join("")}${canSave ? `<button type="button" id="qmSave" class="qmadd">${ic("plus")} ${t("qm.save")}</button>` : ""}</div>` : list.length >= 12 && tx && !list.includes(tx) ? `<p class="mute">${t("qm.full")}</p>` : "";
  box.querySelectorAll("[data-q]").forEach(b => b.onclick = () => { inp.value = list[+b.dataset.q]; inp.focus(); qmDraw(); });
  box.querySelectorAll("[data-x]").forEach(b => b.onclick = () => { edit.msgs = list.filter((_, i) => i !== +b.dataset.x); changed(); qmDraw(); });
  const sv = $("#qmSave"); if (sv) sv.onclick = () => { edit.msgs = list.concat(tx); changed(); toast("ok|" + t("qm.saved")); qmDraw(); };
}

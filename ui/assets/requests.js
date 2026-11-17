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
    <div class="grid"><button type="button" id="scrGo"${navOk("screen") ? "" : " disabled"}>${ic("smartphone")} ${t("more.scr")}</button><button type="button" id="wake" class="act"></button>${cur.via === "lan" && !viewDay ? `<button type="button" id="ear1" class="act"></button>` : ""}${isBasic() ? "" : `<button type="button" id="reboot" class="bad">${ic("refresh-cw")} ${t("reboot")}</button>`}</div>
    ${cur.via === "lan" ? "" : `<p class="mute">${ic("ear")} ${t("ear.lanOnly")}</p>`}</div>`,
    (pop, done) => {
      pop.querySelector("#scrGo").onclick = () => { done(false); goTab("screen"); };
      pop.querySelector("#wake").onclick = () => act("wake", () => cmd({ type: "wake" }, t("wake.done"), false));
      const e1 = pop.querySelector("#ear1"); if (e1) e1.onclick = () => earOn() ? micStop() : ringOn() ? toast("warn|" + t("act.noEar")) : earStart();
      const rb = pop.querySelector("#reboot"); if (rb) rb.onclick = () => cmd({ type: "reboot" }, t("reboot.done")).then(r => { if (r) rebootStart(); });
      actPaint();
    });
}
// Family chat (docs/flows/remote.md 2.15): the thread = st.chat (the phone keeps the last 100, state carries 30) + our own sends
// the phone has not echoed yet (chatPend). The kid's unread count = kid messages newer than the last time this sheet was open.
const chatPend = {};
const chatSeenKey = () => "kg.chat." + (cur && cur.id);
function chatSeen() { try { return +localStorage.getItem(chatSeenKey()) || 0; } catch { return 0; } }
function chatUnread() { const s = chatSeen(); return ((st && st.chat) || []).filter(m => m.from === "k" && m.t > s).length; }
function chatList() {
  const a = ((st && st.chat) || []).slice(), now = Date.now(), k = cur && cur.id;
  chatPend[k] = (chatPend[k] || []).filter(p => now - p.t < 600e3 && !a.some(m => m.from === "p" && m.text === p.text && m.t >= p.t - 120e3));
  return a.concat(chatPend[k]);
}
function chatDraw() {
  const box = $("#chatBox"); if (!box) return false;
  const l = chatList(), near = box.scrollHeight - box.scrollTop - box.clientHeight < 60;
  const html = l.length ? l.map(m => `<div class="cb ${m.from === "k" ? "k" : "p"}${m.local ? " pend" : ""}">${m.from === "k" ? `<b>${esc(cur.name)}</b>` : m.who ? `<b>${esc(m.who)}</b>` : ""}<span>${esc(m.text)}</span><i>${m.local ? t("chat.wait") : esc(evAt(m.t))}</i></div>`).join("")
    : `<p class="mute" style="margin:auto;text-align:center">${t("chat.empty")}</p>`;
  if (box.dataset.h !== html) { box.dataset.h = html; box.innerHTML = html; if (near || !box.dataset.s) box.scrollTop = box.scrollHeight; box.dataset.s = "1"; }
  const last = Math.max(0, ...((st && st.chat) || []).map(m => m.t));
  if (last > chatSeen()) { try { localStorage.setItem(chatSeenKey(), String(last)); } catch {} }
  return true;
}
// "Nhắn" tile: the family chat with the kid (a send is the `message` command: it pops up on the kid's screen and joins the thread);
// saved messages one tap away (same list as the watch)
function msgSheet() {
  const sid = setInterval(() => { if (!chatDraw()) clearInterval(sid); else refresh(); }, 4000);
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("message-square", t("chat.title", esc(cur.name)))}
    <div id="chatBox" class="chat" aria-live="polite"></div>
    <div class="row"><input id="msg" class="sp" placeholder="${esc(t("msgPh"))}" maxlength="300"><button type="button" id="send" class="pri">${ic("send")} ${t("send")}</button></div>
    <div id="qmBox"></div></div>`,
    pop => {
      pop.querySelector("#send").onclick = () => { const tx = $("#msg").value.trim(); if (!tx) return $("#msg").focus();
        const sb = pop.querySelector("#send"); if (sb.disabled) return; sb.disabled = true;   // no double send while the dialog / request is in flight
        cmd({ type: "message", text: tx }, t("msg.done")).then(r => { if (r) { (chatPend[cur.id] = chatPend[cur.id] || []).push({ from: "p", who: "", text: tx, t: Date.now(), local: 1 }); chatDraw(); if ($("#msg") && $("#msg").value.trim() === tx) $("#msg").value = ""; } }).finally(() => { sb.disabled = false; }); };
      pop.querySelector("#msg").onkeydown = e => { if (e.key === "Enter") pop.querySelector("#send").click(); };
      pop.querySelector("#msg").oninput = qmDraw;
      chatDraw(); qmDraw();
    });
}
// Saved quick messages (rules.msgs, also listed on the watch): a tap fills the box (Send stays the one way to send),
// x removes, "Lưu làm mẫu" keeps what is typed. Saved through the normal rules save (changed()).
function qmDraw() {
  const box = $("#qmBox"), inp = $("#msg"); if (!box || !inp) return;
  const list = (edit && edit.msgs) || [], tx = inp.value.trim().slice(0, 120);
  const canSave = edit && !viewDay && tx && !list.includes(tx) && list.length < 12;
  box.innerHTML = `${list.length || canSave ? `<p class="mute" style="margin:12px 0 6px">${t("qm.h")}</p>` : ""}<div class="qms">${list.map((m, i) =>
    `<span class="qm"><button type="button" class="qmu" data-q="${i}">${esc(m)}</button><button type="button" class="qmx" data-x="${i}" aria-label="${esc(t("qm.del", m))}">${ic("x")}</button></span>`).join("")}</div>
    ${canSave ? `<button type="button" id="qmSave" class="sp" style="margin-top:8px">${ic("plus")} ${t("qm.save")}</button>` : list.length >= 12 && tx && !list.includes(tx) ? `<p class="mute">${t("qm.full")}</p>` : ""}`;
  box.querySelectorAll("[data-q]").forEach(b => b.onclick = () => { inp.value = list[+b.dataset.q]; inp.focus(); qmDraw(); });
  box.querySelectorAll("[data-x]").forEach(b => b.onclick = () => { edit.msgs = list.filter((_, i) => i !== +b.dataset.x); changed(); qmDraw(); });
  const sv = $("#qmSave"); if (sv) sv.onclick = () => { edit.msgs = list.concat(tx); changed(); toast("ok|" + t("qm.saved")); qmDraw(); };
}

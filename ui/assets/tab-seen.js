// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- "What you can see" tab (seen): what the parent reads from the child's phone. Every switch here is a Monitoring entry on the phone
// (docs/protocol.md "Monitoring"): the child is told, so each switch shows the exact wording first (CONFIRM in kid.js). ----
// Notifications (rule watch.notif): new ones arrive with the normal sync (state.notifs, newest 20, merged here by id and kept per kid in
// this browser); older ones are pulled on demand, one page of 30: LAN GET /notifs?before=&n= or relay command notifPull + the file channel.
// Keywords (rule watch.words): the phone raises an alert for a hit (docs/protocol.md "Word alerts"); "only with my words" also narrows the feed.
const seenX = { k: null, busy: false, err: "", done: false, access: true, on: true, more: false, next: 0, old: [] };
const SEEN_KEEP = 100;
const seenW = () => { const w = (edit && edit.watch) || {}; return { notif: w.notif || {}, words: w.words || [], cam: !!w.cam, sms: !!w.sms }; };
const seenSet = (fn) => { const w = seenW(); fn(w); edit.watch = { notif: Object.assign({ on: false, kw: false, apps: "all", list: [] }, w.notif), words: w.words, cam: w.cam, sms: w.sms }; changed(); };
const seenFid = () => [...crypto.getRandomValues(new Uint8Array(8))].map(x => x.toString(16).padStart(2, "0")).join("");
const seenKey = k => "kg.seen." + (k && (k.id || k.target) || "");
let seenFeedL = { k: null, items: [] };   // live feed of the current kid, oldest first

function seenFeedFor(k) {
  if (seenFeedL.k !== k) {
    let items = [];
    try { items = JSON.parse(localStorage.getItem(seenKey(k)) || "[]"); } catch (e) { items = []; }
    seenFeedL = { k, items: Array.isArray(items) ? items : [] };
  }
  return seenFeedL;
}
// called by refresh (kid.js) with every new state
function seenFeed(k, s) {
  const n = s && s.notifs && s.notifs.n;
  if (n && n.length) {
    const f = seenFeedFor(k), have = new Set(f.items.map(x => x.id)); let add = 0;
    for (const x of n) if (!have.has(x.id)) { f.items.push(x); add++; }
    if (add) {
      f.items.sort((a, b) => a.id - b.id); f.items = f.items.slice(-SEEN_KEEP);
      try { localStorage.setItem(seenKey(k), JSON.stringify(f.items)); } catch (e) {}
    }
    if (add && tab === "seen") seenDraw();
  }
}

const seenPull = (k, before, sms) => pullPage(k, sms ? "/sms" : "/notifs", sms ? "smsPull" : "notifPull", before);
// One page on demand, 30 rows: LAN GET <lan>?n=&before=, relay command <type> {fid, before, n} + the answer on the file channel
// (also the family chat's older messages: requests.js chatOlder)
async function pullPage(k, lan, type, before) {
  const n = 30;
  if (!fxRelay(k)) return call(k, lan + "?n=" + n + (before ? "&before=" + before : ""));
  const fid = seenFid();
  await call(k, "/cmd", "POST", { type, fid, before: before || 0, n });
  const end = Date.now() + 90e3;
  for (;;) {
    await new Promise(s => setTimeout(s, 2500));
    if (Date.now() > end) throw errOf("timeout");
    const f = ((await call(k, "/file?to=parent").catch(() => ({}))).files || []).find(x => x.fid === fid && x.done);
    if (f) break;
  }
  const r = await fetch(base(k) + `/file?fid=${fid}&i=0`, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
  if (!r.ok) throw errOf("HTTP " + r.status);
  const j = await r.json();
  fetch(base(k) + "/file?fid=" + fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
  return j;
}

async function seenLoad(more) {
  const k = cur; if (!k || seenX.busy) return;
  if (seenX.k !== k) Object.assign(seenX, { k, err: "", done: false, access: true, on: true, more: false, next: 0, old: [] });
  seenX.busy = true; seenX.err = ""; seenDraw();
  try {
    const j = await seenPull(k, more ? seenX.next : 0);
    if (seenX.k !== k) return;
    seenX.on = j.on !== false; seenX.access = j.access !== false;
    seenX.old = more ? seenX.old.concat(j.items || []) : (j.items || []);
    seenX.more = !!j.more; seenX.next = +j.next || 0; seenX.done = true;
  } catch (e) { seenX.err = e.message || String(e.raw || e); }
  seenX.busy = false; seenDraw();
}

function seenRow(x) {
  const ms = (x.msgs || []).slice(-3).map(m => `<div class="mute">${m.s ? "<b>" + esc(m.s) + ":</b> " : ""}${esc(m.t)}</div>`).join("");
  return `<div class="li"><div class="sp"><b>${esc(x.app || x.pkg)}</b> <span class="mute">· ${esc(evAt(x.t))}</span>${x.hit ? ` <span class="tag warn">${esc(x.hit)}</span>` : ""}
    ${x.title ? `<div>${esc(x.title)}</div>` : ""}${ms || (x.text ? `<div class="mute">${esc(x.text)}</div>` : "")}</div></div>`;
}

function seenDraw() {
  const box = $("#seenN"); if (!box) return;
  const w = seenW(), s = seenX;
  if (!w.notif.on) { box.innerHTML = ""; return; }
  const live = seenFeedFor(cur).items.slice().reverse();
  const olds = s.old.filter(x => !live.some(l => l.t === x.t && l.pkg === x.pkg));
  const warn = s.done && !s.access ? alertBox("warn", "bell-off", t("seen.noAccess")) : "";
  box.innerHTML = `${w.notif.kw && !w.words.length ? alertBox("warn", "info", t("seen.kwNone")) : ""}
    <h3 class="mute" style="margin:12px 0 4px">${t("seen.live")}</h3>
    <div class="list">${live.map(seenRow).join("") || `<p class="mute">${t("seen.liveNone")}</p>`}</div>
    <div class="row" style="margin-top:10px"><button id="seenGo"${s.busy ? " disabled" : ""}>${ic("refresh-cw")} ${t(s.busy ? "seen.wait" : "seen.load")}</button>
    <span class="mute">${s.busy && fxRelay() ? t("seen.waitRelay") : ""}</span></div>
    ${s.err ? alertBox("bad", "triangle-alert", esc(s.err)) : ""}${warn}
    ${s.done && !olds.length && !s.err ? `<p class="mute">${t(s.on ? "seen.empty" : "seen.off")}</p>` : ""}
    <div class="list">${olds.map(seenRow).join("")}</div>
    ${s.more ? `<div class="row" style="margin-top:8px"><button id="seenMore"${s.busy ? " disabled" : ""}>${t("seen.more")}</button></div>` : ""}`;
  $("#seenGo").onclick = () => seenLoad(false);
  if ($("#seenMore")) $("#seenMore").onclick = () => seenLoad(true);
}

function seenWordsDraw() {
  const box = $("#seenWords"); if (!box) return;
  const w = seenW();
  box.innerHTML = `<div class="row" style="flex-wrap:wrap;gap:6px">${w.words.map((x, i) => `<span class="tag">${esc(x)} <button type="button" data-wdel="${i}" aria-label="x" style="all:unset;cursor:pointer;padding:0 4px">×</button></span>`).join("")}</div>
    <div class="row" style="margin-top:8px"><input id="seenWIn" aria-label="${esc(t("seen.wph"))}" maxlength="40" placeholder="${esc(t("seen.wph"))}" style="flex:1">
      <button id="${w.words.length ? "seenWAdd" : "f-ww"}">${ic("plus")} ${t("seen.wadd")}</button></div>`;
  const add = () => { const v = $("#seenWIn").value.trim(); if (!v) return; seenSet(x => { x.words = x.words.concat(v).slice(0, 30); }); seenWordsDraw(); seenDraw(); };
  $("#seenWIn").onkeydown = e => { if (e.key === "Enter") { e.preventDefault(); const b = $("#seenWAdd") || $("#f-ww"); b && b.click(); } };
  const b = $("#seenWAdd") || $("#f-ww"); b.onclick = add;   // first word: CONFIRM["f-ww"] (kid.js) asks first
  box.querySelectorAll("[data-wdel]").forEach(x => x.onclick = () => { seenSet(w2 => { w2.words = w2.words.filter((_, i) => i !== +x.dataset.wdel); }); seenWordsDraw(); seenDraw(); });
}

function seenAppsDraw() {
  const box = $("#seenApps"); if (!box) return;
  const w = seenW(), m = w.notif.apps || "all";
  const A = (st.apps || []).filter(x => !x.protected).slice().sort((a, b) => String(a.label).localeCompare(String(b.label)));
  box.innerHTML = `<div class="row"><select id="seenAm"><option value="all"${m === "all" ? " selected" : ""}>${t("seen.am.all")}</option>
      <option value="only"${m === "only" ? " selected" : ""}>${t("seen.am.only")}</option><option value="except"${m === "except" ? " selected" : ""}>${t("seen.am.except")}</option></select></div>
    ${m === "all" ? "" : `<div class="list" style="max-height:260px;overflow:auto;margin-top:6px">${A.map(x => `<label class="row li"><input type="checkbox" data-sa="${esc(x.pkg)}" ${(w.notif.list || []).includes(x.pkg) ? "checked" : ""}><span class="sp">${esc(x.label)}</span></label>`).join("")}</div>`}`;
  $("#seenAm").onchange = e => { seenSet(x => { x.notif.apps = e.target.value; }); seenAppsDraw(); };
  box.querySelectorAll("[data-sa]").forEach(c => c.onchange = () => seenSet(x => {
    const l = new Set(x.notif.list || []); c.checked ? l.add(c.dataset.sa) : l.delete(c.dataset.sa); x.notif.list = [...l].slice(0, 200);
  }));
}

function seenTab(b) {
  const w = seenW();
  b.innerHTML = `<div class="card"><h2>${ic("eye")} ${t("seen.h")}</h2><p class="mute">${t("seen.p")}</p></div>
    <div class="card" id="seenNotif"><h2>${ic("bell")} ${t("seen.nh")}</h2>
      <label class="row"><input type="checkbox" id="f-wn" ${w.notif.on ? "checked" : ""}><span class="sp">${t("seen.non")}<br><span class="mute">${t("seen.nkid")}</span></span></label>
      ${w.notif.on ? `<label class="row" style="margin-top:8px"><input type="checkbox" id="f-wk" ${w.notif.kw ? "checked" : ""}><span class="sp">${t("seen.kw")}<br><span class="mute">${t("seen.kwH")}</span></span></label>
      <h3 class="mute" style="margin:12px 0 4px">${t("seen.apps")}</h3><div id="seenApps"></div>` : ""}
      <div id="seenN"></div></div>
    <div class="card" id="seenCam"><h2>${ic("camera")} ${t("seen.ch")}</h2>
      <label class="row"><input type="checkbox" id="f-wc" ${w.cam ? "checked" : ""}><span class="sp">${t("seen.con")}<br><span class="mute">${t("seen.ckid")}</span></span></label>
      ${w.cam ? seenCamForm() : ""}</div>
    <div class="card" id="seenListenCard"><h2>${ic("ear")} ${t("lh.h")}</h2><p class="mute">${t("lh.p")}</p><div id="seenListen"></div></div>
    <div class="card" id="seenSms"><h2>${ic("message-square")} ${t("seen.sh")}</h2>
      <label class="row"><input type="checkbox" id="f-ws" ${w.sms ? "checked" : ""}><span class="sp">${t("seen.son")}<br><span class="mute">${t("seen.skid")}</span></span></label>
      ${w.sms ? `<div id="seenSmsBox"></div>` : ""}</div>
    <div class="card" id="seenWordsCard"><h2>${ic("siren")} ${t("seen.wh")}</h2><p class="mute">${t("seen.wp")}</p><div id="seenWords"></div></div>`;
  $("#f-wn").onchange = e => { seenSet(x => { x.notif.on = e.target.checked; }); seenX.done = false; seenX.old = []; seenTab(b); };   // confirm: CONFIRM["f-wn"] (kid.js)
  if ($("#f-wk")) $("#f-wk").onchange = e => { seenSet(x => { x.notif.kw = e.target.checked; }); seenDraw(); };   // widening: CONFIRM["f-wk"]
  $("#f-ws").onchange = e => { seenSet(x => { x.sms = e.target.checked; }); smsX.done = false; smsX.rows = []; seenTab(b); };   // confirm: CONFIRM["f-ws"] (kid.js)
  smsDraw();
  $("#f-wc").onchange = e => { seenSet(x => { x.cam = e.target.checked; }); seenTab(b); };   // confirm: CONFIRM["f-wc"] (kid.js)
  if ($("#snapGo")) $("#snapGo").onclick = snapGo;
  if ($("#snapHistGo")) $("#snapHistGo").onclick = () => { if (!snapH.done || snapH.k !== cur) snapLoad(false); const h = $("#snapHist"); if (h) h.scrollIntoView({ behavior: "smooth", block: "start" }); };
  seenAppsDraw(); seenWordsDraw(); seenDraw(); histWire();
}

// ---- Photo on request (rule watch.cam, command snap): form remembered in this browser, one photo per tap ----
const SNAP_KEY = "kg.snap";
function snapOpts() {
  let o = {}; try { o = JSON.parse(localStorage.getItem(SNAP_KEY) || "{}"); } catch (e) {}
  return { cam: o.cam === "back" ? "back" : "front", flash: ["on", "auto"].includes(o.flash) ? o.flash : "off", size: o.size === "medium" ? "medium" : "small", delay: Math.max(0, Math.min(5, o.delay | 0)) };
}
function snapSel(id, v, opts) { return `<label class="row"><span class="sp">${t("seen.c." + id)}</span><select id="snap-${id}" onchange="snapSave()">${opts.map(o => `<option value="${o}" ${String(v) === String(o) ? "selected" : ""}>${t("seen.c." + id + "." + o)}</option>`).join("")}</select></label>`; }
function seenCamForm() {
  const o = snapOpts();
  return `<div style="margin-top:10px">${snapSel("cam", o.cam, ["front", "back"])}${snapSel("flash", o.flash, ["off", "on", "auto"])}${snapSel("size", o.size, ["small", "medium"])}${snapSel("delay", o.delay, [0, 1, 2, 3, 5])}
    <button class="btn" id="snapGo" style="margin-top:10px"${snapGo.now.busy && snapGo.now.k === cur ? " disabled" : ""}>${ic("camera")} ${t("seen.cgo")}</button> <button class="sp" id="snapHistGo" style="margin-top:10px">${ic("history")} ${t("snap.hist")}</button><div id="snapBox" class="mute" role="status" style="margin-top:8px">${snapGo.now.k === cur ? snapGo.now.html : ""}</div><div id="snapHist"></div></div>`;
}
function snapSave() {
  const v = id => $("#snap-" + id).value;
  try { localStorage.setItem(SNAP_KEY, JSON.stringify({ cam: v("cam"), flash: v("flash"), size: v("size"), delay: +v("delay") })); } catch (e) {}
}
async function snapFetch(k, fid, relay) {
  const end = Date.now() + 60e3;
  for (;;) {
    await new Promise(s => setTimeout(s, relay ? 2500 : 1200));
    if (Date.now() > end) throw errOf("timeout");
    if (!relay) {
      const r = await fetch(base(k) + "/snap/" + fid, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
      if (r.status === 404) continue;
      if (r.status === 409) throw errOf("snap:" + ((await r.json().catch(() => ({}))).error || "failed"));
      if (!r.ok) throw errOf("HTTP " + r.status);
      return URL.createObjectURL(await r.blob());
    }
    const f = ((await call(k, "/file?to=parent").catch(() => ({}))).files || []).find(x => x.fid === fid && x.done);
    if (!f) continue;
    const r = await fetch(base(k) + `/file?fid=${fid}&i=0`, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
    if (!r.ok) throw errOf("HTTP " + r.status);
    const buf = await r.arrayBuffer();
    fetch(base(k) + "/file?fid=" + fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
    if (new Uint8Array(buf)[0] !== 0xFF) { let m = "failed"; try { m = JSON.parse(new TextDecoder().decode(buf)).error || m; } catch (e) {} throw errOf("snap:" + m); }
    return URL.createObjectURL(new Blob([buf], { type: "image/jpeg" }));
  }
}
// the result lives here, not in the box: the snap event redraws the tab while we wait (kid.js refresh), which
// replaced #snapBox and the photo landed in a detached node (owner 2026-11-20: "phải bấm Tải lại mới thấy")
async function snapGo() {
  const k = cur, n = snapGo.now; if (!k || (n.busy && n.k === k)) return;
  const show = (html, busy) => {
    Object.assign(n, { k, html, busy });
    document.querySelectorAll("#snapBox").forEach(b => { b.innerHTML = cur === k ? html : ""; });
    document.querySelectorAll("#snapGo").forEach(b => { b.disabled = busy && cur === k; });
  };
  snapSave();
  const o = snapOpts(), fid = seenFid(), relay = fxRelay(k);
  if (n.url) { URL.revokeObjectURL(n.url); n.url = ""; }
  show(esc(t(relay ? "seen.cwaitR" : "seen.cwait")), true);
  try {
    const r = await call(k, "/cmd", "POST", Object.assign({ type: "snap", fid, relay }, o));
    if (r && r.result && !["ok", "queued"].includes(r.result)) throw errOf("snap:" + r.result);
    const u = n.url = await snapFetch(k, fid, relay);
    fetch(u).then(r => r.blob()).then(b => thPut("snapf/" + k.id + "/" + fid, b)).catch(() => {});   // offline viewing later (hist.js)
    show(`<img src="${u}" alt="${esc(t("seen.ch"))}" style="max-width:100%;max-height:60vh;border-radius:12px">`, false);
    if (snapH.k === k) snapLoad(false);   // the history list gets the new photo
  } catch (e) { show(esc(/^snap:/.test(e.raw || "") ? tc("seen.cerr.", e.raw.slice(5).replace(/ /g, "_"), "") || t("seen.cerr.failed") : e.message || t("fx.e.failed")), false); }
}
snapGo.now = { k: null, html: "", busy: false, url: "" };

// ---- Text messages (rule watch.sms): on demand, newest first, 30 a page (LAN GET /sms or relay smsPull + file channel) ----
const smsX = { k: null, busy: false, err: "", done: false, access: true, more: false, rows: [] };
async function smsLoad(more) {
  const k = cur; if (!k || smsX.busy) return;
  if (smsX.k !== k) Object.assign(smsX, { k, err: "", done: false, access: true, more: false, rows: [] });
  smsX.busy = true; smsX.err = ""; smsDraw();
  try {
    const before = more && smsX.rows.length ? smsX.rows[smsX.rows.length - 1].t : 0;
    const j = await seenPull(k, before, true);
    if (smsX.k !== k) return;
    smsX.access = j.access !== false; smsX.rows = more ? smsX.rows.concat(j.sms || []) : (j.sms || []); smsX.more = !!j.more; smsX.done = true;
  } catch (e) { smsX.err = e.message || String(e.raw || e); }
  smsX.busy = false; smsDraw();
}
function smsDraw() {
  const box = $("#seenSmsBox"); if (!box) return;
  const s = smsX;
  box.innerHTML = `<p class="mute" style="margin:8px 0">${t("seen.sfall")}</p>
    <div class="row"><button id="smsGo"${s.busy ? " disabled" : ""}>${ic("refresh-cw")} ${t(s.busy ? "seen.wait" : "seen.sload")}</button></div>
    ${s.err ? alertBox("bad", "triangle-alert", esc(s.err)) : ""}${s.done && !s.access ? alertBox("warn", "info", t("seen.sNoAccess")) : ""}
    ${s.done && s.access && !s.rows.length && !s.err ? `<p class="mute">${t("seen.sempty")}</p>` : ""}
    <div class="list">${s.rows.map(x => `<div class="li"><div class="sp"><b>${esc(x.from || "?")}</b> <span class="mute">· ${esc(evAt(x.t))}${x.out ? " · " + t("seen.sout") : ""}</span><div>${esc(x.text)}</div></div></div>`).join("")}</div>
    ${s.more ? `<div class="row" style="margin-top:8px"><button id="smsMore"${s.busy ? " disabled" : ""}>${t("seen.more")}</button></div>` : ""}`;
  $("#smsGo").onclick = () => smsLoad(false);
  if ($("#smsMore")) $("#smsMore").onclick = () => smsLoad(true);
}

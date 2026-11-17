// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- "What you can see" tab (seen): what the parent reads from the child's phone. Every switch here is a Monitoring entry on the phone
// (docs/protocol.md "Monitoring"): the child is told, so each switch shows the exact wording first (CONFIRM in kid.js). ----
// Notifications (rule watch.notif): new ones arrive with the normal sync (state.notifs, newest 20, merged here by id and kept per kid in
// this browser); older ones are pulled on demand, one page of 30: LAN GET /notifs?before=&n= or relay command notifPull + the file channel.
// Keywords (rule watch.words): the phone raises an alert for a hit (docs/protocol.md "Word alerts"); "only with my words" also narrows the feed.
const seenX = { k: null, busy: false, err: "", done: false, access: true, on: true, more: false, next: 0, old: [] };
const SEEN_KEEP = 100;
const seenW = () => { const w = (edit && edit.watch) || {}; return { notif: w.notif || {}, words: w.words || [] }; };
const seenSet = (fn) => { const w = seenW(); fn(w); edit.watch = { notif: Object.assign({ on: false, kw: false, apps: "all", list: [] }, w.notif), words: w.words }; changed(); };
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

async function seenPull(k, before) {
  const n = 30;
  if (!fxRelay(k)) return call(k, "/notifs?n=" + n + (before ? "&before=" + before : ""));
  const fid = seenFid();
  await call(k, "/cmd", "POST", { type: "notifPull", fid, before: before || 0, n });
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
    <div class="card" id="seenWordsCard"><h2>${ic("siren")} ${t("seen.wh")}</h2><p class="mute">${t("seen.wp")}</p><div id="seenWords"></div></div>`;
  $("#f-wn").onchange = e => { seenSet(x => { x.notif.on = e.target.checked; }); seenX.done = false; seenX.old = []; seenTab(b); };   // confirm: CONFIRM["f-wn"] (kid.js)
  if ($("#f-wk")) $("#f-wk").onchange = e => { seenSet(x => { x.notif.kw = e.target.checked; }); seenDraw(); };   // widening: CONFIRM["f-wk"]
  seenAppsDraw(); seenWordsDraw(); seenDraw();
}

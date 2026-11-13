// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- all kids at once: cards, one map, 7 days side by side ------------------------
const KC = ["#2563eb", "#db2777", "#16a34a", "#d97706", "#7c3aed", "#0891b2"];
let ovLoading = false;
let ov = null;   // {list, back, data: [{k, st, seen, err}]}
async function overview(list, back) {
  const g = ++gen; if (askDone) askDone(false, true); clearInterval(timer); cur = null; mv = null;
  ovRes = null; ovBusy = false; ovLoading = false; ov = { list, back, data: list.map(k => ({ k, st: null, seen: 0, err: "" })) };
  ovShell();
  navTo(1);
  ovDraw();
  await ovLoad();
  if (g !== gen) return;
  clearInterval(timer); timer = setInterval(ovLoad, 15000);
}
function ovShell() {
  mv = null;
  const a = $("#app"); a.className = "";
  a.innerHTML = `<div class="shell">${sideHtml("ov")}<div class="mainc"><div class="wrap">
    <div class="head"><button type="button" id="ovBack" class="back" aria-label="${esc(t("list"))}" title="${esc(t("list"))}">${ic("chevron-left")}</button><span class="badge">${ic("users")}</span><div><div class="t1">${t("nav.family")}</div><div class="st">${t("ovN", ov.data.length)}</div></div></div>
    <div id="ovSos"></div><div id="ovAll"></div><div class="ovk" id="ovKids"></div>
    <div class="card" style="margin-top:12px"><h2>${ic("map-pin")} ${t("ov.where")}</h2><div id="ovMapBox"></div></div>
    <div class="card"><h2>${ic("chart-column")} ${t("ov.week")}</h2><div id="ovBars"></div></div>
    ${sec("ov-rep", "history", t("ov.report"), "", "", `<div id="ovRep"></div>`, false)}</div></div></div>`;
  wireSide(a);
  $("#ovBack").onclick = () => { gen++; clearInterval(timer); const b = ov.back; ov = null; b(); navDown(0); };
}
async function ovLoad() {
  const o = ov; if (!o || document.hidden || ovLoading) return;   // hidden tab: the visibilitychange handler reloads on return; a slow relay must not stack polls
  ovLoading = true;
  try { await Promise.all(o.data.map(async d => {
    try { const j = await call(d.k, "/state"); d.st = j.state !== undefined ? j.state : j; d.seen = j.state !== undefined ? +j.seen || 0 : 0; d.err = ""; }
    catch (e) { d.err = e.net ? e.message : t("ov.phoneErr", e.message); }   // a raw phone error alone ("not paired") says nothing
  })); } finally { ovLoading = false; }
  if (ov === o) ovDraw();
}
function ovOnline(d) { return d.st && (d.k.via === "lan" ? !d.err && Date.now() - d.st.t < 30000 : d.seen && Date.now() - d.seen < 120000); }
// Emergency "pause all": for every kid set rules.pausedUntil (duration picker = the per-kid one) + send `lock`.
// Results per kid are kept in ovRes ({name, ok, msg}); relay answers "queued" = stored, the phone has not done it yet.
let ovRes = null, ovBusy = false;
const NOW_IC = { lost: "siren", paused: "pause", schedule: "book-open", bedtime: "moon", daily: "timer", pass: "plus", code: "lock-open" };
const ovPausedLeft = d => d.st && d.st.rules ? (+d.st.rules.pausedUntil || 0) - Date.now() : 0;
async function ovEach(fn, only) {   // run fn(kid, freshRules) for every kid (or just `only`) in parallel; never throws, returns [{name, ok, msg}]
  const o = ov;
  return Promise.all(o.data.filter(d => !only || d === only).map(async d => {
    try { const j = await call(d.k, "/state"), s = (j.state !== undefined ? j.state : j) || {};
      // relay: rules sent but not yet taken by the phone are newer than the state's; rules are a full replace, so never build from nothing
      const base = j.state !== undefined && j.rules && j.rules.rev > ((s.rules || {}).rev || 0) ? j.rules : s.rules;
      if (!base || typeof base !== "object" || !Object.keys(base).length) throw new Error(t("ov.noRules"));
      const r = norm(structuredClone(base));
      return { name: d.k.name, ...(await fn(d.k, r)) }; }
    catch (e) { return { name: d.k.name, ok: false, msg: e.message }; }
  }));
}
function ovPauseAll(only) {
  if (ovBusy || !ov) return;
  pickTime("pause", "", async until => {
    if (!(await ask(only ? t("ov.pause1Ask", only.k.name, whenTxt(until)) : t("ov.pauseAsk", ov.data.length, whenTxt(until)), t("ov.pauseGo"), true))) return;
    ovBusy = true; ovRes = null; ovDraw();
    const res = await ovEach(async (k, r) => {
      r.pausedUntil = until; await putRules(k, r);
      const c = await call(k, "/cmd", "POST", { type: "lock" });
      if (c && c.result === "queued") return { ok: true, queued: true };
      if (c && c.result && c.result !== "ok") return { ok: false, msg: tc("cr.lock.", c.result, t("cmd.answer", c.result)) };
      return { ok: true };
    }, only);
    ovBusy = false; ovRes = { kind: "pause", res }; if (ov) { ovDraw(); ovLoad(); }
  }, null);
}
async function ovResumeAll() {
  if (ovBusy || !ov || !(await ask(t("ov.resumeAsk"), t("ov.resumeGo")))) return;
  ovBusy = true; ovRes = null; ovDraw();
  const res = await ovEach(async (k, r) => { if (!(+r.pausedUntil > 0)) return { ok: true, skip: true }; r.pausedUntil = 0; await putRules(k, r); return { ok: true }; });
  ovBusy = false; ovRes = { kind: "resume", res }; if (ov) { ovDraw(); ovLoad(); }
}
// A request answered from the family overview: read the kid's newest rules, give the time / app (same rule as the kid page: grantApply), put, then `answer`.
async function ovAnswer(d, q, ok) {
  if (ovBusy) return; ovBusy = true; ovRes = null; ovDraw();
  const kid = d.k, app = q.kind === "app", n = +q.min || 15;
  const res = await ovEach(async (k, r) => {
    if (ok) {
      if (app) { r.approved = [...new Set([...r.approved, q.pkg])]; r.blocked = r.blocked.filter(v => v !== q.pkg); } else grantApply(r, d.st, n);
      await putRules(k, r);
    }
    const c = await call(k, "/cmd", "POST", { type: "answer", req: q.id, ok, ...(ok && app ? { always: true } : {}), ...(ok && !app ? { min: n } : {}), ...(k.via === "lan" ? { id: q.id } : { id: newId("p") }) });
    if (c && c.result && !["ok", "queued"].includes(c.result)) return { ok: false, msg: tc("cr.answer.", c.result, t("cmd.answer", c.result)) };
    return { ok: true, queued: c && c.result === "queued" };
  }, d);
  ovBusy = false; ovRes = { kind: "answer", res }; if (ov) { ovDraw(); ovLoad(); }
}
function ovAllDraw() {
  const any = ov.data.some(d => ovPausedLeft(d) > 0), el = $("#ovAll");
  const bad = ovRes ? ovRes.res.filter(x => !x.ok) : [], q = ovRes ? ovRes.res.filter(x => x.ok && x.queued) : [];
  el.innerHTML = `<div class="row" style="margin-bottom:8px"><button type="button" id="ovPause" class="sp bad" ${ovBusy ? "disabled" : ""}>${ic("pause")} ${ovBusy ? t("loading") : t("ov.pauseAll")}</button>${any ? `<button type="button" id="ovResume" class="sp" ${ovBusy ? "disabled" : ""}>${ic("play")} ${t("ov.resumeAll")}</button>` : ""}</div>
    ${ovRes ? `<div class="alert ${bad.length ? "bad" : q.length ? "warn" : "ok"}">${ic(bad.length ? "triangle-alert" : "check")} <b>${ovRes.kind === "answer" ? t(bad.length ? "ov.ansFail" : "ov.ansOk") : t(ovRes.kind === "pause" ? "ov.pauseRes" : "ov.resumeRes", ovRes.res.length - bad.length, ovRes.res.length)}</b>
      ${bad.map(x => `<div>${esc(x.name)}: ${esc(x.msg || "?")}</div>`).join("")}${q.length ? `<div class="mute">${t("ov.queued", esc(q.map(x => x.name).join(", ")))}</div>` : ""}</div>` : ""}`;
  $("#ovPause").onclick = () => ovPauseAll(); if ($("#ovResume")) $("#ovResume").onclick = ovResumeAll;
}
function ovDraw() {
  const D = ov.data, col = i => KC[i % KC.length];
  ovAllDraw();
  const sos = D.filter(d => d.st && (d.st.sos || []).length);
  $("#ovSos").innerHTML = sos.map(d => `<div class="alert bad sos row">${ic("siren")} <b class="sp">${t("ov.sos", esc(d.k.name), hhmm(d.st.sos[d.st.sos.length - 1].t))}</b><button class="pri" data-o="${D.indexOf(d)}">${ic("folder-open")} ${t("open")}</button></div>`).join("");
  $("#ovKids").innerHTML = D.map((d, i) => {
    const s = d.st, on = ovOnline(d);
    if (!s) return `<div class="card"><div class="nm"><i class="sw" style="background:${col(i)}"></i><b class="sp">${esc(d.k.name)}</b></div><p class="mute">${d.err ? ic("triangle-alert") + " " + esc(d.err) : ic("hourglass") + " " + t("loading")}</p><button data-o="${i}">${ic("folder-open")} ${t("open")}</button></div>`;
    const lim = s.limitMin || 0, pct = lim ? Math.min(100, s.usedMs / (lim * 600)) : 0, rq = (s.requests || [])[0], nowTxt = s.now && s.now.why ? nowText(s.now) : "";
    return `<div class="card"><div class="nm"><i class="sw" style="background:${col(i)}"></i><b class="sp">${esc(d.k.name)}</b>${dot(on ? "on" : "bad")}<span class="mute">${on ? t("ov.online") : t("ov.offline", ago(d.k.via === "lan" ? s.t : d.seen || s.t))}</span></div>
      <div class="mute" style="margin-top:6px">${ic("battery-full")} ${+s.battery}%${s.charging ? ic("zap") : ""} · ${ic("smartphone")} ${esc(s.model)} · Android ${esc(s.android)}</div>
      <div class="mute">${s.screenOn ? ic("eye") + " " + (s.current ? t("ov.usingApp", "<b>" + esc(s.current) + "</b>") : t("ov.using")) : ic("moon") + " " + t("scrOff")}</div>
      <div style="margin-top:8px"><b>${fmt(s.usedMs)}</b> <span class="mute">${lim ? "/ " + fmt(lim * 60000) : "· " + t("noLimit")}</span></div>
      ${lim ? `<div class="bar"><i style="width:${pct}%;background:${pct >= 100 ? "var(--bad)" : col(i)}"></i></div>` : ""}
      ${nowTxt ? `<div class="${s.now.why === "lost" ? "alert bad" : "mute"}" style="margin-top:6px">${ic(NOW_IC[s.now.why] || "lock")} ${nowTxt}</div>` : ""}
      ${rq ? `<div class="alert warn" style="display:block">${ic(rq.kind === "app" ? "package" : "hand")} <b>${rq.kind === "app" ? t("req.appAsks", esc(d.k.name), esc(rq.app || rq.pkg || "?")) : t("req.asks", esc(d.k.name), +rq.min)}</b>${rq.note ? `<div class="mute">${t("req.note", esc(rq.note))}</div>` : ""}
        <div class="row" style="margin-top:8px"><button class="pri sp" data-ay="${i}" ${ovBusy ? "disabled" : ""}>${ic("circle-check")} ${rq.kind === "app" ? t("req.appYes") : t("req.yes", +rq.min)}</button><button class="sp" data-an="${i}" ${ovBusy ? "disabled" : ""}>${ic("circle-x")} ${t("req.no")}</button></div></div>` : ""}
      ${s.location ? `<div class="mute">${ic("map-pin")} ${t("ov.loc", ago(s.location.t))}</div>` : `<div class="mute">${ic("map-pin")} ${t("ov.noLoc")}</div>`}
      ${(s.events || []).length ? (e => `<div class="mute">${ic(EV_IC[e.kind] || "bell")} ${esc(noEmoji(e.text))} · ${ago(e.t)}</div>`)(s.events[s.events.length - 1]) : ""}
      <div class="row" style="margin-top:8px"><button data-o="${i}" class="sp">${ic("folder-open")} ${t("open")}</button>${ovPausedLeft(d) > 0 ? "" : `<button class="sp bad" data-ap="${i}" ${ovBusy ? "disabled" : ""}>${ic("pause")} ${t("pk.pauseBtn")}</button>`}${s.location ? `<a class="btn sp" href="https://www.google.com/maps?q=${+s.location.lat},${+s.location.lng}" target="_blank" rel="noopener">${ic("external-link")} Google Maps</a>` : ""}</div></div>`;
  }).join("");
  document.querySelectorAll("#app [data-ay]").forEach(b => b.onclick = () => { const d = D[+b.dataset.ay]; ovAnswer(d, d.st.requests[0], true); });
  document.querySelectorAll("#app [data-an]").forEach(b => b.onclick = () => { const d = D[+b.dataset.an]; ovAnswer(d, d.st.requests[0], false); });
  document.querySelectorAll("#app [data-ap]").forEach(b => b.onclick = () => ovPauseAll(D[+b.dataset.ap]));
  document.querySelectorAll("#app [data-o]").forEach(b => b.onclick = () => { clearInterval(timer); const k = D[+b.dataset.o].k; ov = null; open(k); });
  // one map, the latest fix of every kid
  const pins = D.map((d, i) => d.st && d.st.location ? { lat: d.st.location.lat, lng: d.st.location.lng, t: d.st.location.t, name: d.k.name, c: col(i) } : null).filter(Boolean);
  const mb = $("#ovMapBox");
  if (!pins.length) mb.innerHTML = `<div class="empty"><span class="e">${ic("map-pin")}</span>${t("ov.noLocs")}</div>`;
  else {
    if (!$("#map")) mb.innerHTML = `<div class="map" id="map"></div><div class="mute" style="margin-top:4px">${pins.map(p => `<span style="color:${p.c}">●</span> ${esc(p.name)} ${ago(p.t)}`).join(" · ")}</div>`;
    else mb.lastElementChild.innerHTML = pins.map(p => `<span style="color:${p.c}">●</span> ${esc(p.name)} ${ago(p.t)}`).join(" · ");
    const pts = pins.map(p => [p.t, p.lat, p.lng]);
    if (!mv) { mv = fitView(pts, $("#map").clientWidth || 340, 260); mv.z = Math.min(mv.z, 15); }
    const draw = () => ovMap(pins); draw(); mapCtl($("#map"), () => mv, draw);
  }
  // 7 days, one bar per kid per day
  $("#ovRep").innerHTML = ovReport(D);
  const today = dkey(Date.now()), days = [];
  for (let i = 6; i >= 0; i--) days.push(dkey(Date.now() - i * 86400e3));
  const msOf = (s, day) => !s ? 0 : day === s.day ? s.usedMs : ((s.history || []).find(h => h.day === day) || {}).ms || 0;
  const mx = Math.max(60000, ...days.flatMap(day => D.map(d => msOf(d.st, day)))), WD = t("wd");
  $("#ovBars").innerHTML = `<div class="ovb">${days.map(day => `<div><div class="g">${D.map((d, i) => { const ms = msOf(d.st, day);
      return `<i title="${esc(d.k.name)} ${esc(dm(day))}: ${fmt(ms)}" aria-label="${esc(d.k.name)} ${esc(day)}: ${fmt(ms)}" style="height:${Math.max(2, Math.round(ms / mx * 104))}px;background:${col(i)}${ms ? "" : ";opacity:.25"}"></i>`; }).join("")}</div>
      <span class="mute"${day === today ? ' style="font-weight:700;color:var(--fg)"' : ""}>${WD[dayIdx(day)]}</span></div>`).join("")}</div>
    <div class="mute" style="margin-top:8px">${D.map((d, i) => `<span style="color:${col(i)}">■</span> ${t("ov.sum", esc(d.k.name), d.st ? fmt(d.st.usedMs) : "—", d.st ? fmt(days.reduce((a, day) => a + msOf(d.st, day), 0)) : "—")}`).join("<br>")}</div>`;
}
// weekly report: per kid, last 7 completed days from state.history (the phone keeps no more), top apps, requests from the log
function ovReport(D) {
  const wk = 7 * 864e5, now = Date.now();
  return D.map((d, i) => {
    const s = d.st, head = `<div class="sch"><div class="nm"><i class="sw" style="background:${KC[i % KC.length]}"></i><b>${esc(d.k.name)}</b></div>`;
    if (!s) return head + `<div class="mute">${d.err ? esc(d.err) : t("loading")}</div></div>`;
    const h = s.history || [], cur7 = h.slice(0, 7), prev7 = h.slice(7, 14), sum = a => a.reduce((x, y) => x + (+y.ms || 0), 0);
    const tot = sum(cur7), avg = cur7.length ? tot / cur7.length : 0;
    const top = new Map(); cur7.forEach(x => (x.top || []).forEach(a => { const o = top.get(a.pkg) || { label: a.label || a.pkg, ms: 0 }; o.ms += +a.ms || 0; top.set(a.pkg, o); }));
    const tops = [...top.values()].filter(a => a.ms > 0).sort((a, b) => b.ms - a.ms).slice(0, 3);
    const reqs = (s.log || []).filter(l => now - l.t < wk && /xin thêm|asked for more/i.test(l.m)).length;   // phone log text is in the child phone's language (vi or en strings "log_more")
    const pt = prev7.length >= 7 ? sum(prev7) : 0, cmp = pt ? " · " + t("rep.cmp", (tot >= pt ? "+" : "−") + Math.round(Math.abs(tot - pt) / pt * 100) + "%") : "";
    return head + `<div>${t("rep.tot", cur7.length, "<b>" + fmt(tot) + "</b>", "<b>" + fmt(avg) + "</b>")}<span class="mute">${cmp}</span></div>
      <div class="mute">${tops.length ? t("rep.top", tops.map(a => esc(a.label) + " " + fmt(a.ms)).join(", ")) : t("rep.noApps")}</div>
      <div class="mute">${ic("hand")} ${t("rep.reqs", reqs)}</div></div>`;
  }).join("");
}
// Map tiles: the first host that loads wins (tileI, kept for the page's life). tile.openstreetmap.org is blocked on some
// networks (seen on a Vietnamese ISP: its DNS answers 127.0.0.1 even via 8.8.8.8, and TLS with that SNI is reset), so the
// OSM Germany server (same OSM data and style) goes first. Every host must be in img-src of the HTML CSP (phone Http.kt,
// parent app Proxy.kt, worker) AND in the WebView request filters (AdminActivity TILE_HOSTS, parent MainActivity). A host blocked by an older CSP just fails over to the next one.
const TILES = ["https://tile.openstreetmap.de/{z}/{x}/{y}.png", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"];
let tileI = 0, tileDead = false;
const tileUrl = (i, z, x, y) => TILES[i].replace("{z}", z).replace("{x}", x).replace("{y}", y);
const tileImg = (z, x, y, l, tp, sz = 256) => `<img alt="" draggable="false" data-i="${tileI}" data-z="${z}" data-x="${x}" data-y="${y}" src="${tileUrl(tileI, z, x, y)}" onerror="tileErr(this)" style="left:${l}px;top:${tp}px${sz !== 256 ? `;width:${sz}px;height:${sz}px` : ""}">`;
// Tiles for view v {z (may be fractional), fx, fy} in a w×h box: integer-zoom tiles, scaled by 2^(z - round(z)).
// Remembers what was drawn in box._r, so gestures can CSS-transform the layer until the next real draw.
function mapTiles(box, v, w, h) {
  const zi = Math.round(v.z), ts = 256 * 2 ** (v.z - zi), n = 2 ** zi, S = 256 * 2 ** v.z, left = v.fx * S - w / 2, top = v.fy * S - h / 2, sz = ts === 256 ? 256 : Math.ceil(ts) + 1;
  let html = "";
  for (let ty = Math.floor(top / ts); ty <= Math.floor((top + h) / ts); ty++) for (let tx = Math.floor(left / ts); tx <= Math.floor((left + w) / ts); tx++)
    if (ty >= 0 && ty < n) html += tileImg(zi, ((tx % n) + n) % n, ty, Math.round(tx * ts - left), Math.round(ty * ts - top), sz);
  box._r = { z: v.z, fx: v.fx, fy: v.fy, S, left, top, w, h };
  return { html, S, left, top };
}
const mapZB = () => `<div class="mz"><button type="button" data-mz="1" aria-label="${esc(t("zoomIn"))}">${ic("plus")}</button><button type="button" data-mz="-1" aria-label="${esc(t("zoomOut"))}">${ic("minus")}</button></div>`;
const mapAttr = `<div class="attr">© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a></div>`;
// One gesture set for every map (Google Maps web): mouse wheel / trackpad pinch (ctrl+wheel) / Safari gesture / touch pinch
// zoom around the pointer, two-finger trackpad scroll and drag pan, double-click / double-tap zoom in, +/- buttons.
// During a gesture the drawn layer is only CSS-transformed; the real redraw runs once it settles (120 ms), so no tile flood.
// getV() returns the live view object (mutated here), draw() renders it; o.tap(x, y) -> true to redraw, o.after() after each redraw,
// o.dblCentre: double-tap zooms at the centre (the picker moves the centre on tap).
function mapCtl(box, getV, draw, o = {}) {
  box._ctl = { getV, draw, o };
  if (box._ctlOn) return; box._ctlOn = true;
  const C = () => box._ctl, V = () => C().getV(), W = () => box.clientWidth || 340, H = () => box.clientHeight || 260;
  const fix = v => { v.z = Math.max(3, Math.min(18, Math.abs(v.z - Math.round(v.z)) < 1e-6 ? Math.round(v.z) : v.z)); v.fx = ((v.fx % 1) + 1) % 1; v.fy = Math.min(0.999, Math.max(0.001, v.fy)); };
  let tm = 0;
  const commit = () => { clearTimeout(tm); tm = 0; const v = V(); if (!v) return; fix(v); C().draw(); if (C().o.after) C().o.after(); };
  const preview = () => {
    const r = box._r, l = box.querySelector(".layer"), v = V(); if (!r || !l || !v) return;
    const k = 2 ** (v.z - r.z), px = (v.fx - r.fx) * r.S + W() / 2, py = (v.fy - r.fy) * r.S + H() / 2;
    l.style.transform = `translate(${(W() / 2 - k * px).toFixed(1)}px,${(H() / 2 - k * py).toFixed(1)}px) scale(${k.toFixed(4)})`;
  };
  const soon = () => { preview(); clearTimeout(tm); tm = setTimeout(commit, 120); };
  const zoomAt = (z1, ax, ay, v = V()) => {   // the world point under (ax, ay) stays under it
    z1 = Math.max(3, Math.min(18, z1)); const S0 = 256 * 2 ** v.z, S1 = 256 * 2 ** z1, dx = ax - W() / 2, dy = ay - H() / 2;
    v.fx += dx / S0 - dx / S1; v.fy += dy / S0 - dy / S1; v.z = z1;
  };
  const rel = e => { const b = box.getBoundingClientRect(); return [e.clientX - b.left, e.clientY - b.top]; };
  const P = new Map(); let d = null, pin = null, lastTap = null, gest = false, g0 = 0, panUntil = 0;
  box.addEventListener("wheel", e => {
    const v = V(); if (!v) return; e.preventDefault(); if (gest) return;
    const m = e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? H() : 1, dx = e.deltaX * m, dy = e.deltaY * m, [ax, ay] = rel(e);
    // a mouse wheel: line/page mode, or whole 120 notches with no sideways part; trackpad scrolls are small pixel deltas
    const mouse = !e.ctrlKey && Date.now() > panUntil && (e.deltaMode !== 0 || (!dx && e.wheelDeltaY && Math.abs(e.wheelDeltaY) % 120 === 0));
    if (e.ctrlKey) zoomAt(v.z - dy / 60, ax, ay);   // trackpad pinch (Chrome, Edge, Brave, Firefox)
    else if (mouse) zoomAt(v.z - Math.max(-1, Math.min(1, dy / 100)), ax, ay);
    else { panUntil = Date.now() + 300; const S = 256 * 2 ** v.z; v.fx += dx / S; v.fy += dy / S; }
    soon();
  }, { passive: false });
  // Safari (macOS) pinch; on iOS the touch pinch below already handles it
  box.addEventListener("gesturestart", e => { e.preventDefault(); if (P.size > 1 || !V()) return; gest = true; g0 = V().z; });
  box.addEventListener("gesturechange", e => { e.preventDefault(); if (!gest || !V()) return; const [ax, ay] = rel(e); zoomAt(g0 + Math.log2(e.scale || 1), ax, ay); soon(); });
  box.addEventListener("gestureend", e => { e.preventDefault(); if (gest) { gest = false; commit(); } });
  box.addEventListener("click", e => {
    const b = e.target.closest("[data-mz]"), v = V(); if (!b || !v) return;
    zoomAt(Math.round(v.z) + +b.dataset.mz, W() / 2, H() / 2); commit();
  });
  box.addEventListener("pointerdown", e => {
    if (e.target.closest("button,a,input") || !V()) return;
    P.set(e.pointerId, rel(e));
    if (P.size === 1) { const q = rel(e); d = { x: q[0], y: q[1], v0: { ...V() }, moved: false }; box._moved = false; }
    else if (P.size === 2) {
      const [a, b] = [...P.values()]; clearTimeout(tm);
      pin = { d0: Math.hypot(a[0] - b[0], a[1] - b[1]) || 1, m0: [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2], v0: { ...V() } }; d = null; box._moved = true;
      P.forEach((_, id) => { try { box.setPointerCapture(id); } catch {} });
    }
  });
  box.addEventListener("pointermove", e => {
    if (!P.has(e.pointerId)) return; const q = rel(e); P.set(e.pointerId, q); const v = V(); if (!v) return;
    if (pin && P.size >= 2) {
      const [a, b] = [...P.values()], k = Math.hypot(a[0] - b[0], a[1] - b[1]) / pin.d0, m = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
      const z1 = Math.max(3, Math.min(18, pin.v0.z + Math.log2(k))), S0 = 256 * 2 ** pin.v0.z, S1 = 256 * 2 ** z1;
      v.z = z1; v.fx = pin.v0.fx + (pin.m0[0] - W() / 2) / S0 - (m[0] - W() / 2) / S1; v.fy = pin.v0.fy + (pin.m0[1] - H() / 2) / S0 - (m[1] - H() / 2) / S1;
      preview();
    } else if (d) {
      const dx = q[0] - d.x, dy = q[1] - d.y;
      if (!d.moved && Math.hypot(dx, dy) > 5) { d.moved = box._moved = true; box.classList.add("drag"); try { box.setPointerCapture(e.pointerId); } catch {} }
      if (d.moved) { const S = 256 * 2 ** d.v0.z; v.fx = d.v0.fx - dx / S; v.fy = d.v0.fy - dy / S; preview(); }
    }
  });
  const up = e => {
    if (!P.has(e.pointerId)) return; P.delete(e.pointerId); const v = V();
    if (pin) {
      if (P.size < 2) {
        pin = null; commit();
        if (P.size === 1 && V()) { const q = [...P.values()][0]; d = { x: q[0], y: q[1], v0: { ...V() }, moved: true }; }   // one finger left: keep panning
        else setTimeout(() => box._moved = false, 50);
      }
      return;
    }
    if (!d) return; const dd = d; d = null; box.classList.remove("drag");
    if (dd.moved) { commit(); setTimeout(() => box._moved = false, 50); return; }
    if (e.type !== "pointerup" || !v) return;
    const [ax, ay] = rel(e), now = Date.now();
    if (lastTap && now - lastTap.t < 320 && Math.hypot(ax - lastTap.x, ay - lastTap.y) < 30) {
      lastTap = null; if (C().o.dblCentre) zoomAt(Math.round(v.z) + 1, W() / 2, H() / 2); else zoomAt(Math.round(v.z) + 1, ax, ay); commit(); return;
    }
    lastTap = { t: now, x: ax, y: ay };
    if (C().o.tap && C().o.tap(ax, ay)) commit();
  };
  box.addEventListener("pointerup", up); box.addEventListener("pointercancel", up);
}
const mapFail = () => `<div class="merr" role="status">${ic("wifi-off")} ${t("map.fail")}</div>`;
function tileErr(im) {
  const i = +im.dataset.i + 1;
  if (i < TILES.length) { if (tileI < i) tileI = i; im.dataset.i = i; im.src = tileUrl(i, im.dataset.z, im.dataset.x, im.dataset.y); return; }
  im.onerror = null; im.style.visibility = "hidden"; tileDead = true;
  const m = im.closest(".map"); if (m && !m.querySelector(".merr")) m.insertAdjacentHTML("beforeend", mapFail());
}
function ovMap(pins) {
  const box = $("#map"); if (!box) return;
  const w = box.clientWidth || 340, h = 260, { html, S, left, top } = mapTiles(box, mv, w, h);
  const xy = pins.map(p => [wx(p.lng) * S - left, wy(p.lat) * S - top]);
  box.innerHTML = `<div class="layer" id="mlayer">${html}<svg width="${w}" height="${h}" style="left:0;top:0">${xy.map((q, i) => `<circle cx="${q[0].toFixed(1)}" cy="${q[1].toFixed(1)}" r="8" fill="${pins[i].c}" stroke="#fff" stroke-width="3"/>`).join("")}</svg>
    ${xy.map((q, i) => `<span class="pin" style="left:${q[0].toFixed(0)}px;top:${(q[1] - 12).toFixed(0)}px;border-color:${pins[i].c}">${esc(pins[i].name)}</span>`).join("")}</div>
    ${mapZB()}${mapAttr}${tileDead ? mapFail() : ""}`;
}

// --- back button: one history entry per level, same URL ---
// kid page (1; 0 on the on-device page, which has no list) -> another tab (td 1) -> a screen opened from "Thêm" (td 2);
// a past day adds {day: 1}. Back walks down that stack: sub-screen -> Thêm -> Hôm nay -> list (never past the kid on-device).
const navLvl = () => (history.state && history.state.kg) || 0;
const SUB_TABS = new Set(["screen", "dev", "files", "log", "rep", "hist", "set"]);
let navSkip = null;   // goTab going down: the popstate that follows only relabels the entry it lands on
function navTab(id, from) {
  try {
    const s = history.state || {}, d = s.td || 0;
    if (s.day) return;
    const want = id === "home" ? 0 : SUB_TABS.has(id) && from === "more" ? 2 : 1;
    if (want === d) { if (d) history.replaceState({ ...s, tab: id }, ""); }
    else if (want > d) for (let i = d + 1; i <= want; i++) history.pushState({ kg: navLvl() + 1, td: i, tab: i === want ? id : from }, "");
    else { navSkip = id; history.go(want - d); }
  } catch {}
}
let navQuiet = false;   // a back the page already handled (leaving a past day for today): its popstate does nothing
const dayOff = () => { try { if (history.state && history.state.day) { navQuiet = true; history.back(); } } catch {} };
function navTo(n) { try { while (navLvl() < n) history.pushState({ kg: navLvl() + 1 }, ""); } catch {} }
function navDown(n) { try { const d = navLvl() - n; if (d > 0) history.go(-d); } catch {} }
function goList() {
  if (LOCAL && !ov) return;   // the on-device page is this one kid: there is no list behind it
  gen++; clearInterval(timer); if (askDone) askDone(false, true);
  leaveSave();
  if (ov) { const b = ov.back; ov = null; return b(); }
  if (!cur) return;
  screenStop(); earStop(); cur = null;
  if (APP || onLan()) return appBoot();
  listScreen(kids.filter(k => k.via === "relay"));
}
addEventListener("popstate", () => {
  const l = navLvl(), s = history.state || {};
  if (typeof appSetOn !== "undefined" && appSetOn && !s.appSet) { appSetOn = false; return appBoot(); }
  if (navQuiet) { navQuiet = false; return; }
  if (navSkip) { if (s.td) try { history.replaceState({ ...s, tab: navSkip }, ""); } catch {} navSkip = null; return; }
  if (cur && viewDay && !s.day) { tab = s.td ? s.tab : "home"; return open(cur); }
  const base = cur && !ov && $("#back") ? 1 : 0;   // the kid page's own level (no list behind it = 0)
  if (cur && !ov && (s.td || l === base)) { const nt = s.td ? s.tab : "home"; if (tab !== nt) { tab = nt; if (tab === "hist") histDays = null; render(true); } return; }
  if (l < base || (l === 0 && ov)) goList();
});

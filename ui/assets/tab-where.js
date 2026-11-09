// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- location history map (no external scripts: OSM tiles as <img> + SVG overlay) -----------
let trk = [], trkLoaded = 0, trkDay = "", mv = null;
// --- shared date picker: one button + one month popover (lives outside #app, so re-renders keep it open) -----------
const dpCfg = {};   // id -> { sel: "YYYY-MM-DD", days: Set|array of selectable days, onPick(day) }
let dpOpen = null, dpM = null;
function dayLabel(k) { const td = dkey(Date.now()); return k === td ? t("today") : k === dkey(Date.now() - 864e5) ? t("yesterday") : k ? dm(k) : t("pickDay"); }
function dayBtn(id, cfg) { dpCfg[id] = { ...cfg, days: new Set(cfg.days) }; return `<button class="dpb" data-dp="${id}" aria-haspopup="dialog">${ic("calendar")} ${esc(dayLabel(cfg.sel))}</button>`; }
function dpBind(root) {
  root.querySelectorAll("[data-dp]").forEach(b => b.onclick = () => { dpOpen = b.dataset.dp; const c = dpCfg[dpOpen], d = c.sel || dkey(Date.now()); dpM = { y: +d.slice(0, 4), m: +d.slice(5, 7) - 1 }; dpDraw(); });
  if (dpOpen) dpDraw();   // a re-render must not lose the open popover (data may have changed)
}
function dpClose() { dpOpen = null; $("#dpPop").style.display = "none"; }
function dpDraw() {
  const pop = $("#dpPop"), c = dpOpen && dpCfg[dpOpen];
  if (!c || !document.querySelector(`[data-dp="${dpOpen}"]`)) return dpClose();
  const p2 = x => String(x).padStart(2, "0"), today = dkey(Date.now());
  const off = (new Date(dpM.y, dpM.m, 1).getDay() + 6) % 7, n = new Date(dpM.y, dpM.m + 1, 0).getDate(), cells = Array(off).fill("<span></span>");
  for (let d = 1; d <= n; d++) { const k = `${dpM.y}-${p2(dpM.m + 1)}-${p2(d)}`;
    cells.push(c.days.has(k) ? `<button class="pri${k === c.sel ? " sel" : ""}" data-k="${k}" aria-label="${esc(new Date(dpM.y, dpM.m, d).toLocaleDateString(lang, { dateStyle: "long" }))}">${d}</button>` : `<span class="mute" aria-disabled="true">${d}</span>`); }
  const mt = new Date(dpM.y, dpM.m, 1).toLocaleDateString(lang, { month: "long", year: "numeric" });
  pop.innerHTML = `<div class="dp"><div class="row"><button id="dpP" aria-label="${esc(t("dp.prev"))}">${ic("chevron-left")}</button><b class="sp" style="text-align:center">${esc(mt.charAt(0).toUpperCase() + mt.slice(1))}</b><button id="dpN" aria-label="${esc(t("dp.next"))}">${ic("chevron-right")}</button></div>
    <div class="dpg">${t("wd").map(x => `<span class="mute">${x}</span>`).join("")}${cells.join("")}</div>
    <div class="row" style="margin-top:10px"><button id="dpT" class="sp">${ic("calendar")} ${t("today")}</button><button id="dpX">${ic("x")} ${t("close")}</button></div></div>`;
  pop.style.display = "flex";
  $("#dpP").onclick = () => { dpM = dpM.m ? { y: dpM.y, m: dpM.m - 1 } : { y: dpM.y - 1, m: 11 }; dpDraw(); };
  $("#dpN").onclick = () => { dpM = dpM.m < 11 ? { y: dpM.y, m: dpM.m + 1 } : { y: dpM.y + 1, m: 0 }; dpDraw(); };
  $("#dpT").onclick = () => { if (c.days.has(today)) { const f = c.onPick; dpClose(); f(today); } else { dpM = { y: +today.slice(0, 4), m: +today.slice(5, 7) - 1 }; dpDraw(); } };
  $("#dpX").onclick = dpClose;
  pop.querySelectorAll("[data-k]").forEach(b => b.onclick = () => { const f = c.onPick, k = b.dataset.k; dpClose(); f(k); });
}
$("#dpPop").addEventListener("click", e => { if (e.target.id === "dpPop") dpClose(); });
document.addEventListener("keydown", e => { if (e.key === "Escape" && dpOpen) dpClose(); });
const dkey = t => { const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
const hav = (a, b, c, d) => { const R = 6371000, r = Math.PI / 180, dl = (c - a) * r, dn = (d - b) * r, x = Math.sin(dl / 2) ** 2 + Math.cos(a * r) * Math.cos(c * r) * Math.sin(dn / 2) ** 2; return 2 * R * Math.asin(Math.sqrt(x)); };
const wx = lon => (lon + 180) / 360, wy = lat => { const s = Math.sin(lat * Math.PI / 180); return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI); };   // world fraction 0..1
function allTrack() { const m = new Map(); trk.concat(st.track || []).forEach(p => m.set(p[0], p)); return [...m.values()].sort((a, b) => a[0] - b[0]); }
function stopsOf(pts) {
  const out = []; let c = null;
  for (const p of pts) {
    if (c && hav(c.lat, c.lon, p[1], p[2]) < 100) { c.n++; c.end = p[0]; c.lat += (p[1] - c.lat) / c.n; c.lon += (p[2] - c.lon) / c.n; }
    else { if (c) out.push(c); c = { start: p[0], end: p[0], lat: p[1], lon: p[2], n: 1 }; }
  }
  if (c) out.push(c);
  return out.filter(s => s.n >= 2 && s.end - s.start >= 5 * 60000);
}
function fitView(pts, w, h) {
  const xs = pts.map(p => wx(p[2])), ys = pts.map(p => wy(p[1])), x0 = Math.min(...xs), x1 = Math.max(...xs), y0 = Math.min(...ys), y1 = Math.max(...ys);
  let z = 17; while (z > 2 && ((x1 - x0) * 256 * 2 ** z > w - 70 || (y1 - y0) * 256 * 2 ** z > h - 70)) z--;
  return { z, fx: (x0 + x1) / 2, fy: (y0 + y1) / 2 };
}
function drawMap(pts) {
  const box = $("#map"); if (!box) return;
  const w = box.clientWidth || 340, h = 260, { html, S, left, top } = mapTiles(box, mv, w, h);
  const xy = pts.map(p => [wx(p[2]) * S - left, wy(p[1]) * S - top]);
  const line = xy.map(q => q.map(v => v.toFixed(1)).join(",")).join(" ");
  const last = pts.length - 1, r = pts.length > 150 ? 3 : 5;
  const dots = xy.map((q, i) => `<circle data-i="${i}" cx="${q[0].toFixed(1)}" cy="${q[1].toFixed(1)}" r="${i === 0 || i === last ? 8 : r}" fill="${i === 0 ? "#16a34a" : i === last ? "#dc2626" : "#2563eb"}" stroke="#fff" stroke-width="2"/>`).join("");
  const zs = ((edit || st.rules || {}).zones || []).filter(zoneOk).map(z => {
    const mpp = 156543.03392 * Math.cos(+z.lat * Math.PI / 180) / 2 ** mv.z, cx = wx(+z.lng) * S - left, cy = wy(+z.lat) * S - top;
    return `<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="${Math.max(3, (z.r || 150) / mpp).toFixed(1)}" fill="#16a34a" fill-opacity=".14" stroke="#16a34a" stroke-width="2" style="pointer-events:none"/><text x="${cx.toFixed(1)}" y="${cy.toFixed(1)}" text-anchor="middle" font-size="12" font-weight="600" fill="#166534" stroke="#fff" stroke-width="3" paint-order="stroke" style="pointer-events:none">${esc(z.name)}</text>`;
  }).join("");
  // the whole day faded, the part already "passed" on the timeline solid, the kid's marker at the scrubber time on top
  box.innerHTML = `<div class="layer" id="mlayer">${html}<svg width="${w}" height="${h}" style="left:0;top:0">${zs}<polyline points="${line}" fill="none" stroke="#2563eb" stroke-width="3" stroke-opacity=".3" stroke-linejoin="round" style="pointer-events:none"/><polyline id="mPass" points="" fill="none" stroke="#2563eb" stroke-width="4" stroke-opacity=".9" stroke-linejoin="round" stroke-linecap="round" style="pointer-events:none"/>${dots}<g id="mNow" style="pointer-events:none"></g></svg></div>
    ${mapZB()}${mapAttr}${tileDead ? mapFail() : ""}`;
  box.querySelectorAll("circle[data-i]").forEach(c => c.onclick = () => { if (box._moved) return; const p = pts[+c.dataset.i]; $("#mapInfo").textContent = `${hhmm(p[0])} · ${t("acc", "±" + Math.round(p[3] || 0))}`; scrPlay(false); scrSet(p[0], false); });
  scrPaint(false);
}
// --- timeline scrubber (Google Maps Timeline / Life360 history): one track for the day, thumb = a moment, the map shows where ---
// tl.pts/sp/t0/t1 are set by whereTab; tl.t is the thumb time, tl.live = follow "now" (today) / the end of the day.
const tl = { day: "", t: 0, t0: 0, t1: 0, pts: [], sp: [], live: true, play: false, raf: 0, last: 0, drag: false };
function scrZone(lat, lng) { const z = ((edit || (st && st.rules) || {}).zones || []).filter(zoneOk).find(z => hav(+z.lat, +z.lng, lat, lng) <= (+z.r || 150)); return z ? z.name : ""; }
function scrAt(T) {   // where the kid was at T: null before the first point; linear between neighbours; a stop snaps to its centre
  const p = tl.pts; if (!p.length || T < p[0][0]) return null;
  let lo = 0, hi = p.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (p[m][0] <= T) lo = m; else hi = m - 1; }
  const s = tl.sp.find(s => T >= s.start && T <= s.end);
  if (s) return { lat: s.lat, lng: s.lon, i: lo, stop: s };
  if (lo === p.length - 1) return { lat: p[lo][1], lng: p[lo][2], i: lo, end: true };
  const a = p[lo], b = p[lo + 1], f = Math.min(1, (T - a[0]) / ((b[0] - a[0]) || 1));
  if (hav(a[1], a[2], b[1], b[2]) < 100) return { lat: a[1], lng: a[2], i: lo, still: true, gap: [a[0], b[0]] };
  return { lat: a[1] + (b[1] - a[1]) * f, lng: a[2] + (b[2] - a[2]) * f, i: lo, move: true, gap: [a[0], b[0]] };
}
function scrText(T, P) {
  if (!P) return t("tl.none");
  const z = scrZone(P.lat, P.lng);
  if (P.stop) return (z ? t("tl.atZ", esc(z)) : t("tl.stay")) + ` (${hhmm(P.stop.start)}–${hhmm(P.stop.end)})`;
  if (P.end) return t("tl.last", hhmm(tl.pts[P.i][0])) + (z ? " · " + t("tl.atZ", esc(z)) : "");
  if (P.still) return z ? t("tl.atZ", esc(z)) : t("tl.stay");
  return t("tl.move") + (P.gap[1] - P.gap[0] > 30 * 60000 ? ` · ${t("tl.gap", hhmm(P.gap[0]), hhmm(P.gap[1]))}` : "");
}
function scrPaint(follow) {
  const T = tl.t, P = scrAt(T), R = $("#scR");
  if (R && !tl.drag) R.value = T;
  const L = $("#scL"); if (L) L.innerHTML = `<b>${hhmm(T)}</b><span>${scrText(T, P)}</span>`;
  const pf = $("#scPf"); if (pf) pf.style.width = (tl.t1 > tl.t0 ? (T - tl.t0) / (tl.t1 - tl.t0) * 100 : 100).toFixed(2) + "%";
  if (R) R.setAttribute("aria-valuetext", `${hhmm(T)}, ${(L && L.lastChild ? L.lastChild.textContent : "")}`);
  const box = $("#map"), r = box && box._r, pass = $("#mPass"), now = $("#mNow"); if (!r || !pass || !now) return;
  const px = (lat, lng) => [wx(lng) * r.S - r.left, wy(lat) * r.S - r.top];
  const q = P ? tl.pts.slice(0, P.i + 1).map(p => px(p[1], p[2])) : [];
  if (P) q.push(px(P.lat, P.lng));
  pass.setAttribute("points", q.map(c => c[0].toFixed(1) + "," + c[1].toFixed(1)).join(" "));
  if (!P) { now.innerHTML = ""; return; }
  const [x, y] = q[q.length - 1];
  now.innerHTML = `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="17" fill="#2563eb" fill-opacity=".2"/><circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="9" fill="#2563eb" stroke="#fff" stroke-width="3"/>`;
  if (follow && (x < 30 || y < 30 || x > r.w - 30 || y > r.h - 30) && !box.classList.contains("drag")) { mv.fx = wx(P.lng); mv.fy = wy(P.lat); drawMap(tl.pts); }
}
function scrSet(T, follow = true) { tl.t = Math.max(tl.t0, Math.min(tl.t1, T)); tl.live = tl.t >= tl.t1 - 1000; scrPaint(follow); }
function scrBtn() {
  const b = $("#scP"); if (!b) return;
  b.setAttribute("aria-pressed", tl.play); b.setAttribute("aria-label", t(tl.play ? "tl.pause" : "tl.play")); b.title = t(tl.play ? "tl.pause" : "tl.play");
  b.innerHTML = ic(tl.play ? "pause" : "play");
}
function scrPlay(on) {
  cancelAnimationFrame(tl.raf); tl.play = !!on && tl.pts.length > 1;
  if (tl.play) {
    if (tl.t >= tl.t1 - 1000 || tl.t < tl.pts[0][0]) tl.t = tl.pts[0][0];   // from the start; skip the empty morning before the first fix
    tl.last = performance.now(); tl.raf = requestAnimationFrame(scrTick); scrPaint(true);
  }
  scrBtn();
}
function scrTick(now) {
  if (!tl.play) return;
  if (!$("#scR")) { tl.play = false; return; }   // left the tab
  const dt = Math.min(100, now - tl.last); tl.last = now;
  tl.t = Math.min(tl.t1, tl.t + (tl.t1 - tl.t0) * dt / 12000);   // the whole day in ~12 s
  tl.live = false; scrPaint(true);
  if (tl.t >= tl.t1) { tl.live = true; return scrPlay(false); }
  tl.raf = requestAnimationFrame(scrTick);
}
function scrHtml(pts) {
  const one = pts.length < 2, span = tl.t1 - tl.t0, pc = x => span > 0 ? Math.max(0, Math.min(100, (x - tl.t0) / span * 100)) : 0;
  return `<div class="tln"><div class="scH"><button type="button" id="scP" ${one ? `disabled title="${esc(t("tl.one"))}"` : ""} aria-pressed="${tl.play}" aria-label="${esc(t(tl.play ? "tl.pause" : "tl.play"))}">${ic(tl.play ? "pause" : "play")}</button>
    <div class="scL" id="scL" aria-live="off"></div></div>
    <div class="scW"><div class="scK"><b class="pf" id="scPf"></b>${tl.sp.map(s => `<i style="left:${pc(s.start).toFixed(2)}%;width:${Math.max(0.8, pc(s.end) - pc(s.start)).toFixed(2)}%"></i>`).join("")}</div>
    <input type="range" id="scR" min="${tl.t0}" max="${tl.t1}" step="1000" value="${tl.t}" ${span > 0 ? "" : "disabled"} aria-label="${esc(t("tl.aria"))}"></div>
    <div class="scA"><span>${hhmm(tl.t0)}</span><span>${tl.day === dkey(Date.now()) ? t("tl.now") : hhmm(tl.t1)}</span></div></div>`;
}
function scrWire() {
  const R = $("#scR"); if (!R) return;
  R.oninput = () => { if (tl.play) scrPlay(false); scrSet(+R.value, true); };
  R.onpointerdown = () => { tl.drag = true; if (tl.play) scrPlay(false); };
  R.onpointerup = R.onpointercancel = R.onchange = () => { tl.drag = false; };
  R.onkeydown = e => {   // arrows 5 min (shift: 30), page keys 1 h; Home/End stay native
    const m = { ArrowLeft: -5, ArrowDown: -5, ArrowRight: 5, ArrowUp: 5, PageDown: -60, PageUp: 60 }[e.key]; if (m == null) return;
    e.preventDefault(); if (tl.play) scrPlay(false); scrSet(tl.t + m * 60000 * (e.shiftKey && Math.abs(m) === 5 ? 6 : 1));
  };
  const b = $("#scP"); if (b) b.onclick = () => scrPlay(!tl.play);
  document.querySelectorAll("[data-stt]").forEach(x => x.onclick = () => { scrPlay(false); scrSet(+x.dataset.stt); });
  scrPaint(false);
}
// Place picker (Google Maps / Life360 "add place"): the map moves under a fixed centre pin; tap a spot to jump there.
// Coordinates are an optional fold-out. Resolves {name, lat, lng, r} or false.
const unwy = fy => Math.atan(Math.sinh(Math.PI * (1 - 2 * fy))) * 180 / Math.PI;
function pickPlace(z0) {
  const l = st && st.location, has = zoneOk(z0), c = has ? [+z0.lat, +z0.lng] : l ? [+l.lat, +l.lng] : [16, 106];
  const v = { z: has || l ? 16 : 5, fx: wx(c[1]), fy: wy(c[0]) };
  let rad = +z0.r || 150;
  const at = () => ({ lat: +unwy(v.fy).toFixed(6), lng: +(v.fx * 360 - 180).toFixed(6) });
  const others = ((edit || st.rules || {}).zones || []).filter(x => x !== z0 && zoneOk(x));
  return sheet(`<div class="dp zp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("map-pin", t(has ? "zone.edit" : "zone.add"))}
    <input id="zpN" maxlength="40" placeholder="${esc(t("zone.namePh"))}" aria-label="${esc(t("zone.name"))}" value="${esc(z0.name || "")}">
    <div class="map" id="zpM"><div class="layer" id="zpL"></div><span class="zpin" aria-hidden="true">${ic("map-pin")}</span>
      ${mapZB()}${mapAttr}</div>
    <p class="mute" style="margin:6px 0 10px">${ic("pointer")} ${t(has || l ? "zone.drag" : "zone.far")}</p>
    <label class="row zpr">${t("zone.r")} <input type="range" id="zpR" min="50" max="2000" step="50" value="${rad}"> <b id="zpRv">${rad} m</b></label>
    ${l ? `<button type="button" id="zpK" style="width:100%;margin-top:8px">${ic("user")} ${t("zone.kid")}</button>` : ""}
    <details class="zpc"><summary>${t("zone.coord")}</summary><div class="row" style="margin-top:8px"><input id="zpLa" inputmode="decimal" placeholder="${esc(t("lat"))}" aria-label="${esc(t("lat"))}" style="width:8.5em"><input id="zpLo" inputmode="decimal" placeholder="${esc(t("lng"))}" aria-label="${esc(t("lng"))}" style="width:8.5em"><button type="button" id="zpG">${t("zone.go")}</button></div></details>
    <div class="row" style="margin-top:14px"><button type="button" class="sp" id="zpX">${t("cancel")}</button><button type="button" class="pri sp" id="zpS">${ic("check")} ${t("zone.save")}</button></div></div>`, (pop, done) => {
    const box = pop.querySelector("#zpM"), lay = pop.querySelector("#zpL"), H = 260;
    const fill = () => { const p = at(); pop.querySelector("#zpLa").value = p.lat; pop.querySelector("#zpLo").value = p.lng; };
    const draw = () => {
      const w = box.clientWidth || 340, n = 2 ** v.z, { html, S, left, top } = mapTiles(box, v, w, H);
      const mpp = lat => 156543.03392 * Math.cos(lat * Math.PI / 180) / n;
      const ring = (lat, lng, r, me) => { const cx = wx(lng) * S - left, cy = wy(lat) * S - top;
        return `<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="${Math.max(4, r / mpp(lat)).toFixed(1)}" fill="${me ? "#dc2626" : "#16a34a"}" fill-opacity="${me ? ".16" : ".1"}" stroke="${me ? "#dc2626" : "#16a34a"}" stroke-width="2" ${me ? "" : 'stroke-dasharray="5 4"'}/>`; };
      const kid = l ? (() => { const cx = wx(+l.lng) * S - left, cy = wy(+l.lat) * S - top; return `<circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="7" fill="#2563eb" stroke="#fff" stroke-width="2.5"/>`; })() : "";
      const p = at();
      lay.style.transform = "";
      lay.innerHTML = html + `<svg width="${w}" height="${H}" style="left:0;top:0;pointer-events:none">${others.map(x => ring(+x.lat, +x.lng, +x.r || 150)).join("")}${ring(p.lat, p.lng, rad, 1)}${kid}</svg>`;
    };
    // tap = put the centre here; double-tap then zooms in at that (new) centre
    mapCtl(box, () => v, draw, { after: fill, dblCentre: true, tap: (x, y) => { const S = 256 * 2 ** v.z; v.fx += (x - (box.clientWidth || 340) / 2) / S; v.fy += (y - H / 2) / S; return true; } });
    const rr = pop.querySelector("#zpR"); rr.oninput = () => { rad = +rr.value; pop.querySelector("#zpRv").textContent = rad + " m"; draw(); };
    const k = pop.querySelector("#zpK"); if (k) k.onclick = () => { v.fx = wx(+l.lng); v.fy = wy(+l.lat); v.z = Math.max(v.z, 16); draw(); fill(); };
    pop.querySelector("#zpG").onclick = () => {
      const la = parseFloat(String(pop.querySelector("#zpLa").value).replace(",", ".")), lo = parseFloat(String(pop.querySelector("#zpLo").value).replace(",", "."));
      if (!(Math.abs(la) <= 85) || !(Math.abs(lo) <= 180)) return toast("warn|" + t("zone.badCoord"));
      v.fx = wx(lo); v.fy = wy(la); v.z = Math.max(v.z, 15); draw();
    };
    pop.querySelector("#zpX").onclick = () => done(false);
    pop.querySelector("#zpS").onclick = () => {
      const nm = pop.querySelector("#zpN"), name = nm.value.trim().slice(0, 40);
      if (!name) { toast("warn|" + t("zone.noName")); nm.focus(); return; }
      done({ name, ...at(), r: rad });
    };
    requestAnimationFrame(() => { draw(); fill(); if (!has) pop.querySelector("#zpN").focus(); });
  });
}
// --- app mode: history calendar -------------------------------------------------
const histPath = () => "k=" + b64u(cur.target) + "&p=" + encodeURIComponent(cur.via === "lan" ? "api/state" : "c/" + encodeURIComponent(cur.id) + "/state");
async function pickDay(d) {
  const k = cur;
  try {
    const r = await fetch("/app/history/day?" + histPath() + "&d=" + encodeURIComponent(d));
    if (!r.ok) throw 0;
    const j = (await r.json()).body; if (!j) throw 0;
    if (cur !== k) return;
    clearInterval(timer); screenStop();
    st = j.state !== undefined ? j.state : j; pendingRules = null; seenAt = 0; cachedAt = 0; lastErr = "";
    if (!st.apps || !st.rules) throw 0;
    edit = norm(structuredClone(st.rules)); viewDay = d; tab = "home"; trk = []; trkLoaded = 0; render(); navTo(2);
  } catch { toast("err|" + t("hist.fail", dm(d))); }
}
function histTab(b) {
  if (histDays === null) {
    histDays = [];
    fetch("/app/history/days?" + histPath()).then(r => r.json()).then(a => { histDays = Array.isArray(a) ? a : []; if (tab === "hist" && $("#body")) histTab($("#body")); }).catch(() => { histDays = []; });
  }
  const today = dkey(Date.now());
  b.innerHTML = `<div class="card"><h2>${ic("history")} ${t("hist.h")}</h2>
    ${dayBtn("app", { sel: viewDay || today, days: histDays, onPick: k => { if (k === today) { tab = "home"; open(cur); navDown(1); } else pickDay(k); } })}
    <p class="mute" style="margin-top:10px">${histDays.length ? t("hist.pick") : t("hist.none")}</p></div>`;
  dpBind(b);
}

function whereTab(b) {
  const l = st.location, lan = cur.via === "lan";
  if (lan && !viewDay && Date.now() - trkLoaded > 60000) {
    trkLoaded = Date.now();
    call(cur, "/track?days=7").then(j => { trk = j.track || []; const f = document.activeElement && document.activeElement.id; if (tab === "where" && $("#body") && !(f && /^f-/.test(f)) && !tl.drag) whereTab($("#body")); }).catch(() => {});
  }
  const all = allTrack(), today = dkey(Date.now()), yest = dkey(Date.now() - 864e5);
  const keys = [...new Set([today, yest].concat(all.map(p => dkey(p[0]))))].sort().reverse();
  if (!trkDay || !keys.includes(trkDay)) trkDay = today;
  const pts = all.filter(p => dkey(p[0]) === trkDay);
  let dist = 0; for (let i = 1; i < pts.length; i++) dist += hav(pts[i - 1][1], pts[i - 1][2], pts[i][1], pts[i][2]);
  const sp = stopsOf(pts), home = pts.length ? pts[0] : null;
  const S = viewDay ? null : ruleSecs(edit);
  // timeline range: today 00:00 -> now, a past day its first -> last point
  const isToday = trkDay === today, [yy, mm, dd] = trkDay.split("-").map(Number);
  if (tl.day !== trkDay) { scrPlay(false); tl.day = trkDay; tl.live = true; }
  tl.pts = pts; tl.sp = sp;
  tl.t0 = isToday ? new Date(yy, mm - 1, dd).getTime() : pts.length ? pts[0][0] : 0;
  tl.t1 = isToday ? Math.max(Date.now(), pts.length ? pts[pts.length - 1][0] : 0) : pts.length ? pts[pts.length - 1][0] : 0;
  tl.t = tl.live || tl.play ? (tl.play ? tl.t : tl.t1) : Math.max(tl.t0, Math.min(tl.t1, tl.t));
  b.innerHTML = `<div class="g2 w"><div><div class="card"><div class="row" style="margin-bottom:4px"><h2 class="sp" style="margin:0">${ic("map-pin")} ${t("where.h")}</h2><div id="days">${dayBtn("trk", { sel: trkDay, days: keys, onPick: k => { trkDay = k; mv = null; whereTab(b); } })}</div></div>
    ${pts.length ? `<div class="map" id="map"></div>${scrHtml(pts)}<div class="mute" id="mapInfo" style="margin:6px 0">${ic("pointer")} ${t("where.tap")} · ${dot("on")} ${t("where.start")} · ${dot("bad")} ${t("where.last")}</div>
      <div class="row"><b>${ic("route")} ${(dist / 1000).toLocaleString(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 })} km</b><span class="mute">· ${t("where.pts", pts.length)} · ${hhmm(pts[0][0])}–${hhmm(pts[pts.length - 1][0])}</span></div>`
    : `<div class="empty"><span class="e">${ic("map")}</span>${t("where.noPts", ic("map-pin"))}</div>`}
    <div class="row" style="margin-top:12px;flex-wrap:nowrap"><div class="sp" style="min-width:0">${l ? `<b>${ic("pin")} ${t("where.lastH")}</b><div class="mute">${ic("clock")} ${ago(l.t)} · ${t("acc", "~" + Math.round(l.acc))}</div>` : `<span class="mute">${t("where.noLoc")}</span>`}</div>
      ${l ? `<a class="btn rb" href="https://www.google.com/maps?q=${+l.lat},${+l.lng}" target="_blank" rel="noopener" aria-label="${esc(t("where.gmaps"))}" title="${esc(t("where.gmaps"))}">${ic("external-link")}</a>` : ""}</div>
    <button id="loc2" class="pri act" style="width:100%;margin-top:12px;height:52px">${ic("map-pin")} ${t("where.locNew")}</button><p class="mute" style="margin-bottom:0">${ic("refresh-cw")} ${t("where.auto")}</p></div></div>
  <div>${pts.length ? `<div class="card"><h2>${ic("pause")} ${t("where.stops")}</h2>
      ${sp.map(s => { const z = scrZone(s.lat, s.lon); return `<div class="row" style="padding:2px 0;flex-wrap:nowrap"><button type="button" class="stb" data-stt="${s.start}" aria-label="${esc(t("tl.go", hhmm(s.start)))}">${z || !home || hav(home[1], home[2], s.lat, s.lon) >= 150 ? ic("map-pin") : ic("house")} <span class="sp"><b>${hhmm(s.start)}–${hhmm(s.end)}</b> · ${fmt(s.end - s.start)}${z ? ` · ${esc(z)}` : ""}</span></button><a class="btn rb" style="width:40px;height:40px;min-height:40px" href="https://www.google.com/maps?q=${+s.lat},${+s.lon}" target="_blank" rel="noopener" aria-label="Google Maps" title="Google Maps">${ic("map")}</a></div>`; }).join("") || `<div class="mute">${t("where.noStops")}</div>`}</div>` : ""}
    ${S ? `<div id="rsx">${S.zone}${S.trk}</div>` : ""}</div></div>`;
  $("#loc2").onclick = () => act("loc2", () => cmd({ type: "locate" }, t("where.locating"), false), askOf("locate"));
  actPaint();
  if ($("#rsx")) rulesWire($("#rsx"), edit);
  dpBind(b);
  if (pts.length) {
    if (!mv) mv = fitView(pts, $("#map").clientWidth || 340, 260);
    drawMap(pts); mapCtl($("#map"), () => mv, () => drawMap(pts)); scrWire();
  } else scrPlay(false);
}

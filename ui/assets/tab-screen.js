// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- live screen + remote control -------------------------------------------
// Runs independently of the 4 s state poll (refresh() never redraws the screen tab).
const scr = { gen: 0, url: null, seq: 0, frames: [], lat: 0, liveT: null, view: false, err: "", msg: "", off: false, blob: null, idle: false };   // off: the last frame answer was "screen off"
// Nobody touched the page for 5 min: stop asking for frames (the kid's phone keeps capturing and uploading as long as we ask,
// and its "a parent is watching" notice stays up). One button brings the picture back.
const SCR_IDLE = 300000;
function screenIdle() { const b = scr.blob; screenStop(); scr.idle = true; scr.blob = b; screenMsg(""); screenCtl(); }   // the last picture can still be saved
// screen errors from the phone (Acc.lastError): keys "se.<code>"
const sleep = ms => new Promise(r => setTimeout(r, ms));
const errText = e => tc("se.", e, e ? t("se.unknown", e) : "");

function screenMsg(t) { scr.msg = t; const m = $("#s-msg"); if (m) m.textContent = t; }
function screenStat() {
  const el = $("#s-stat"); if (!el) return;
  const now = performance.now(); scr.frames = scr.frames.filter(t => now - t < 5000);
  const fps = scr.frames.length > 1 ? (scr.frames.length - 1) / ((scr.frames[scr.frames.length - 1] - scr.frames[0]) / 1000) : 0;
  el.textContent = scr.frames.length ? t("scr.watching") + " · " + t("scr.stat", fps.toLocaleString(lang, { maximumFractionDigits: 1, minimumFractionDigits: 1 }), Math.round(scr.lat)) : "";
}
function showFrame(blob, lat) {
  const old = scr.url; scr.url = URL.createObjectURL(blob);
  const img = $("#s-img"); if (img) img.src = scr.url;
  if (old) URL.revokeObjectURL(old);
  if (!scr.blob) { scr.blob = blob; screenCtl(); } else scr.blob = blob;
  scr.lat = lat; scr.frames.push(performance.now()); screenMsg(""); screenStat(); if (scr.off) { scr.off = false; screenCtl(); }
}
function screenStop() {
  scr.gen++; clearInterval(scr.liveT); scr.liveT = null;
  if (scr.url) { URL.revokeObjectURL(scr.url); scr.url = null; }
  scr.frames = []; scr.seq = 0; scr.msg = ""; scr.blob = null; scr.idle = false;
}
function screenStart() {
  if (!cur || document.hidden) return;
  const gen = ++scr.gen, k = cur; clearInterval(scr.liveT);
  // a redraw of the tab (state changed) is not somebody watching: stay paused until a tap / key (they move actAt)
  if (scr.idle && Date.now() - actAt >= SCR_IDLE) { screenCtl(); return; }
  if (scr.idle) { scr.idle = false; screenCtl(); }
  const idle = () => { if (Date.now() - actAt < SCR_IDLE) return false; if (scr.gen === gen) screenIdle(); return true; };
  const img = $("#s-img"); if (img && scr.url) img.src = scr.url;
  screenMsg(scr.msg || t("connecting"));
  const alive = () => scr.gen === gen && tab === "screen";
  let fails = 0;   // "timeout" answers in a row (the phone's capture is still waking up)
  if (k.via === "lan") (async () => {
    while (alive() && !idle()) {
      const t0 = performance.now();
      try {
        // a dead Wi-Fi link can hold the request open for minutes: give up after 12 s and ask again
        const r = await fetch(base(k) + "/screen?max=720&q=50", { headers: { "X-KG-Token": k.token }, cache: "no-store", signal: quick(12000) });
        if (!r.ok) { const j = await r.json().catch(() => ({}));
          if (j.error === "timeout" && fails < 3) { fails++; if (alive()) screenMsg(t("connecting")); await sleep(600 * 2 ** fails); continue; }   // 1.2 / 2.4 / 4.8 s
          if (alive()) { screenMsg(errText(j.error) || t("scr.cant")); screenOffErr(j.error); } await sleep(1500); continue; }
        const blob = await r.blob(); if (!alive()) break;
        fails = 0; showFrame(blob, performance.now() - t0);
      } catch { if (alive()) screenMsg(t("scr.lost")); await sleep(2000); continue; }
      await sleep(Math.max(0, 140 - (performance.now() - t0)));   // up to ~7 fps on the LAN; a slow phone just answers slower
    }
  })();
  else {
    const ping = () => call(k, "/live", "POST", {}).catch(e => { if (alive()) screenMsg(t("errX", e.message)); });
    ping(); scr.liveT = setInterval(ping, 10000);
    (async () => {
      screenMsg(t("scr.wait"));
      while (alive() && !idle()) {
        try {
          const r = await fetch(base(k) + "/frame?after=" + scr.seq, { headers: { "X-KG-Token": k.token }, cache: "no-store", signal: quick(20000) });   // the worker answers within ~5 s
          if (!alive()) break;
          if (r.status === 204) { const e = r.headers.get("X-KG-Err");
            if (e === "timeout" && fails < 3) { fails++; screenMsg(t("scr.wait")); await sleep(600 * 2 ** fails); continue; }
            screenMsg(e ? errText(e) : scr.frames.length ? "" : t("scr.wait")); screenOffErr(e); await sleep(200); continue; }
          if (!r.ok) { const j = await r.json().catch(() => ({})); screenMsg(t("errX", j.error || "HTTP " + r.status)); await sleep(2000); continue; }
          const blob = await r.blob(); if (!alive()) break;
          scr.seq = +r.headers.get("X-KG-Seq") || scr.seq; fails = 0;
          showFrame(blob, +r.headers.get("X-KG-Age") || 0);
        } catch { if (alive()) screenMsg(t("scr.retry")); await sleep(2000); }
      }
    })();
  }
}
// hidden page (tab in background, parent app paused): the poll timers skip their fetch; catch up at once when shown again
document.addEventListener("visibilitychange", () => { if (document.hidden) screenStop(); else { actAt = Date.now(); if (cur && tab === "screen") screenStart(); if (cur) refresh(); else if (ov) ovLoad(); } });

async function sendInput(o, show = m => toast("warn|" + m)) {
  if (scr.view || !cur || screenOff()) return false;
  try {
    let r;
    try { r = await call(cur, "/input", "POST", o); }
    catch (e) { if (cur.via !== "relay" || e.raw !== "not live") throw e; await call(cur, "/live", "POST", {}); r = await call(cur, "/input", "POST", o); }
    if (r.result && r.result !== "ok" && r.result !== "queued") { if (r.result === "screen off") screenOffErr("screen off"); show(errText(r.result)); return false; }
    return true;
  }
  catch (e) { toast("err|" + t("errX", e.message)); return false; }
}
// Screen off (state.screenOn false, or the phone answered "screen off"): lock every control, offer one "wake" button.
const screenOff = () => !viewDay && ((st && st.screenOn === false) || scr.off);
function screenOffErr(e) { if (e === "screen off" && !scr.off) { scr.off = true; screenCtl(); } }
function screenCtl() {
  const so = $("#s-off"); if (!so) return;
  const off = screenOff(), lock = off || scr.view;
  so.hidden = !off; $("#s-box").classList.toggle("off", off);
  const si = $("#s-idle"); if (si) si.hidden = !scr.idle || off;
  const sv = $("#s-save"); if (sv) sv.disabled = !scr.blob;
  document.querySelectorAll("#s-btns button, #s-nav button").forEach(x => x.disabled = lock);
  $("#f-stxt").disabled = lock; $("#s-send").disabled = lock || !$("#f-stxt").value;
  if (off) actPaint();
}

function screenTab(b) {
  b.innerHTML = `<div class="card"><div class="row"><h2 class="sp" style="margin:0">${ic("smartphone")} ${t("scr.h")}</h2>
    <label class="row"><input type="checkbox" id="s-view" ${scr.view ? "checked" : ""}> ${ic("eye")} ${t("scr.view")}</label></div>
    <div class="alert warn" id="s-off" hidden style="margin-top:10px">${ic("moon")}<span class="sp"><b>${t("so.h")}</b><br><span class="mute">${t("so.p")}</span></span><button type="button" id="wake" class="act pri"></button></div>
    <div class="alert info" id="s-idle" hidden style="margin-top:10px">${ic("pause")}<span class="sp">${t("scr.idle")}</span><button type="button" id="s-resume" class="pri">${ic("play")} ${t("scr.resume")}</button></div>
    <div class="scr ${scr.view ? "view" : ""}" id="s-box" style="margin-top:10px"><img id="s-img" alt="${esc(t("scr.h"))}" draggable="false"><div class="scr-msg" id="s-msg"></div><svg class="gst" id="s-gst" aria-hidden="true"></svg></div>
    <div class="snav" id="s-nav" role="group" aria-label="${esc(t("key.nav"))}"><button data-key="back" aria-label="${esc(t("back"))}" title="${esc(t("back"))}">${ic("arrow-left")}</button><button data-key="home" aria-label="${esc(t("key.home"))}" title="${esc(t("key.home"))}">${ic("circle")}</button><button data-key="recents" aria-label="${esc(t("key.recents"))}" title="${esc(t("key.recents"))}">${ic("square")}</button></div>
    <div class="row" style="margin:4px 0"><span class="mute sp" id="s-stat"></span><button type="button" id="s-save" class="sm" disabled>${ic("download")} ${t("scr.save")}</button></div>
    <div class="spad" id="s-btns">
      <div class="swp" role="group" aria-label="${esc(t("swp.h"))}"><span class="slab2">${t("swp.h")}</span>
        <button data-sw="up" class="u" aria-label="${esc(t("swp.up"))}" title="${esc(t("swp.up"))}">${ic("arrow-up")}</button>
        <button data-sw="left" class="l" aria-label="${esc(t("swp.left"))}" title="${esc(t("swp.left"))}">${ic("arrow-left")}</button>
        <button data-sw="right" class="r" aria-label="${esc(t("swp.right"))}" title="${esc(t("swp.right"))}">${ic("arrow-right")}</button>
        <button data-sw="down" class="d" aria-label="${esc(t("swp.down"))}" title="${esc(t("swp.down"))}">${ic("arrow-down")}</button></div>
      <div class="keys"><button data-key="notifications">${ic("bell")} ${t("key.notif")}</button><button data-key="quick">${ic("sliders")} ${t("key.quick")}</button><button data-key="lock">${ic("lock")} ${t("lockScr")}</button></div></div>
    <div class="row" style="margin-top:10px"><input id="f-stxt" class="sp" placeholder="${esc(t("scr.typePh"))}" aria-label="${esc(t("scr.typePh"))}"><button id="s-send" class="pri">${ic("send")} ${t("scr.sendTxt")}</button></div>
    <div class="mute" id="s-terr" role="status" style="margin-top:4px"></div>
    <label class="row" style="margin-top:10px"><input type="checkbox" id="f-sw" ${edit && edit.showWatching ? "checked" : ""}><span>${ic("bell")} ${t("scr.notify")}</span></label>
    <p class="mute">${ic("pointer")} ${t("scr.tip")}</p></div>`;
  $("#f-sw").onchange = e => { if (!edit) return; edit.showWatching = e.target.checked; changed(); };
  const box = $("#s-box"), img = $("#s-img");
  $("#s-view").onchange = e => { scr.view = e.target.checked; box.classList.toggle("view", scr.view); screenCtl(); };
  // quick buttons: work even when the picture lags (a tap on a 2-fps picture lands in the wrong place). Swipes stay away
  // from the screen edges, where Android opens the shade / back gesture instead.
  const SW = { up: [.5, .72, .5, .28], down: [.5, .28, .5, .72], left: [.82, .5, .18, .5], right: [.18, .5, .82, .5] };
  const flash = x => { x.classList.add("sent"); setTimeout(() => x.classList.remove("sent"), 450); };
  document.querySelectorAll("#s-btns [data-key], #s-nav [data-key]").forEach(x => { x.onclick = () => { flash(x); sendInput({ type: "key", key: x.dataset.key }); }; });
  // gesture preview: an arrow on the picture shows exactly what the phone will replay (start -> end, straight line),
  // white while dragging, amber while sending, green when the phone answered, red on failure / cancel
  const gst = $("#s-gst"); let gstT = 0;
  const arrow = (a, z, cls, label = "") => {
    clearTimeout(gstT); if (!img.src) return;
    const ir = img.getBoundingClientRect(), br = box.getBoundingClientRect();
    const P = f => [ir.left - br.left + f.x * ir.width, ir.top - br.top + f.y * ir.height];
    const [x1, y1] = P(a), [x2, y2] = P(z), g = Math.atan2(y2 - y1, x2 - x1), h = 16;
    const head = d => `${(x2 - h * Math.cos(g + d)).toFixed(1)} ${(y2 - h * Math.sin(g + d)).toFixed(1)}`;
    const tx = Math.min(br.width - 70, Math.max(6, x2 + 10)), ty = Math.min(br.height - 8, Math.max(16, y2 - 12));
    gst.setAttribute("viewBox", `0 0 ${br.width} ${br.height}`); gst.setAttribute("class", "gst " + cls);
    gst.innerHTML = `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}"/><path d="M${head(.5)}L${x2} ${y2}L${head(-.5)}"/><circle cx="${x1}" cy="${y1}" r="7"/>${label ? `<text x="${tx}" y="${ty}">${esc(label)}</text>` : ""}`;
  };
  const gstClear = (ms = 0) => { clearTimeout(gstT); gstT = setTimeout(() => { gst.innerHTML = ""; gst.setAttribute("class", "gst"); }, ms); };
  const replay = async (o, a, z) => {
    arrow(a, z, "send", t("swp.sending"));
    const ok = await sendInput(o);
    arrow(a, z, ok ? "ok" : "bad", ok ? "" : t("swp.fail")); gstClear(ok ? 1200 : 2500);
  };
  const swOf = k => { const [x1, y1, x2, y2] = SW[k]; return [{ x: x1, y: y1 }, { x: x2, y: y2 }]; };
  $("#s-btns").querySelectorAll("[data-sw]").forEach(x => {
    const show = () => { if (!x.disabled && !gst.classList.contains("send")) arrow(...swOf(x.dataset.sw), "hint"); };
    const hide = () => { if (gst.classList.contains("hint")) gstClear(); };
    x.onmouseenter = show; x.onfocus = show; x.onmouseleave = hide; x.onblur = hide;
    x.onclick = () => { const [a, z] = swOf(x.dataset.sw); flash(x); replay({ type: "swipe", x1: a.x, y1: a.y, x2: z.x, y2: z.y, ms: 260 }, a, z); };
  });
  const tx = $("#f-stxt"), terr = $("#s-terr");
  const send = async () => {
    const v = tx.value; if (!v || screenOff() || scr.view) return;
    terr.textContent = "";
    const ok = await sendInput({ type: "text", text: v }, m => { terr.innerHTML = `${ic("triangle-alert")} ${esc(m)}`; });
    if (ok) { tx.value = ""; screenCtl(); }   // keep the text when it did not arrive, so it can be sent again
  };
  tx.oninput = () => { terr.textContent = ""; screenCtl(); };
  tx.onkeydown = e => { if (e.key === "Enter" && !e.isComposing) { e.preventDefault(); send(); } };
  $("#s-send").onclick = send;
  $("#s-resume").onclick = () => screenStart();
  // keeps the frame on screen now (a later frame replaces scr.blob, not this copy)
  $("#s-save").onclick = () => { const b = scr.blob; if (!b) return; const d = new Date(), p = n => String(n).padStart(2, "0");
    fxSave(b, `kidguard-${(cur && cur.name || "kid").replace(/[^\p{L}\p{N}_-]+/gu, "_").slice(0, 30)}-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.jpg`); toast("ok|" + t("scr.saved")); };
  $("#wake").onclick = () => act("wake", () => cmd({ type: "wake" }, t("wake.done"), false));
  screenCtl();
  let down = null;
  const frac = (e, r) => ({ x: Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), y: Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)) });
  const outside = (e, r) => e.clientX < r.left - 4 || e.clientX > r.right + 4 || e.clientY < r.top - 4 || e.clientY > r.bottom + 4;
  img.addEventListener("pointerdown", e => { if (scr.view || !img.src || screenOff()) return; e.preventDefault(); img.setPointerCapture(e.pointerId); down = { px: e.clientX, py: e.clientY, t: performance.now(), mt: 0, r: img.getBoundingClientRect() }; });
  img.addEventListener("contextmenu", e => e.preventDefault());
  img.addEventListener("pointercancel", () => { down = null; gstClear(); });
  img.addEventListener("pointermove", e => {
    if (!down) return; const d = down;
    if (!d.mt) { if (Math.hypot(e.clientX - d.px, e.clientY - d.py) < 12) return; d.mt = performance.now(); }   // speed: first move .. last move (not the hold before or a pause before letting go)
    d.lt = performance.now();
    const a = frac({ clientX: d.px, clientY: d.py }, d.r), z = frac(e, d.r);
    outside(e, d.r) ? arrow(a, z, "cancel", t("swp.cancel")) : arrow(a, z, "drag", t("swp.release"));
  });
  img.addEventListener("pointerup", e => {
    if (!down) return; const d = down; down = null;
    const now = performance.now(), dur = now - d.t, dist = Math.hypot(e.clientX - d.px, e.clientY - d.py);
    const a = frac({ clientX: d.px, clientY: d.py }, d.r), z = frac(e, d.r);
    if (dist >= 12 && outside(e, d.r)) { arrow(a, z, "cancel", t("swp.cancelled")); gstClear(900); return; }
    if (dist < 12) {
      const dot = document.createElement("div"); dot.className = "tap"; const br = box.getBoundingClientRect();
      dot.style.left = (e.clientX - br.left) + "px"; dot.style.top = (e.clientY - br.top) + "px"; box.appendChild(dot); setTimeout(() => dot.remove(), 600);
      sendInput({ type: dur > 500 ? "long" : "tap", x: +a.x.toFixed(4), y: +a.y.toFixed(4) });
    } else replay({ type: "swipe", x1: +a.x.toFixed(4), y1: +a.y.toFixed(4), x2: +z.x.toFixed(4), y2: +z.y.toFixed(4), ms: Math.round(Math.min(3000, Math.max(80, (d.lt || now) - (d.mt || d.t)))) }, a, z);
  });
  screenStart();
}

// ---- Video call, parent side (docs/flows/call.md). Signaling through /call (the relay's, or the phone's own over LAN), media
// straight to the kid's phone. Needs a secure page (relay https, or the parent app's 127.0.0.1): a LAN http page cannot open the camera.
const VC_MAX = 15 * 60e3, VC_HIDDEN = 60e3;
let vc = null;   // the call on screen: { k, via, id, c (RTC call), st, t0, max, ... }

// Signal channel for call / listen / cam (docs/flows/remote.md 2.17), fixed per session (me.via): "lan" = the phone's own /api/call
// when this page reaches it straight (LAN, Tailscale) and its app has it (0.9.72): no relay needed, no round trip through the internet;
// else "relay" (kid reached through the relay, or a LAN kid with an older app that has a relay); "" = neither.
const vcOk = (k) => !!vcOk.via(k);
vcOk.via = (k) => !k ? "" : k.via === "lan" && k === cur && st && /^\d/.test(st.version || "") && !vLess(st.version, "0.9.72") ? "lan"
  : k.via === "relay" || k.relay ? "relay" : "";
// me = the session { k, via } (vc, earRtc, camRtc): every signal of one session goes the same way, even if the page's link changes
const vcApi = (me, path, method = "GET", body) => me.via === "lan" || me.k.via === "relay" ? call(me.k, path, method, body) : viaRelay(me.k, path, method, body);
const vcId = () => [...crypto.getRandomValues(new Uint8Array(8))].map(x => x.toString(16).padStart(2, "0")).join("");

async function vcStart() {
  const k = cur;
  if (vc || !k) return;
  if (CAM.on() || earRtc) return toast("warn|" + t("cam.busyHere"));
  if (!vcOk(k)) return ask(t("call.noRelay"), t("call.ok"), false, "", "", null, t("call.h"));
  if (!RTC.can()) return ask(t("call.secure"), t("call.ok"), false, "", "", null, t("call.h"));
  if (!(await onlineNow.gate("call"))) return;
  if (vc) return;
  vc = { k, via: vcOk.via(k), id: vcId(), st: "prep", t0: 0, max: VC_MAX, mic: true, cam: true, kidCam: true, net: null };
  vcShow();
  try {
    const me = vc, j = await vcApi(me, "/call?ice=1");
    if (vc !== me) return;
    const c = await RTC.open({ ice: j.ice || [], video: true, local: $("#vcLocal"), remote: $("#vcRemote"),
      onState: vcState, onMsg: vcMsg, onNet: n => { if (vc === me) { me.net = n; vcPaint(); } } });
    if (vc !== me) return c.close();   // hung up while the camera was opening: release camera + mic
    vc.c = c;
    vc.cam = vc.c.hasVideo;
    const r = await vcApi(me, "/call", "POST", { op: "offer", id: vc.id, sdp: vc.c.sdp });
    vc.st = "ring"; vcPaint();
    vcWait(vc, r.call);
  } catch (e) {
    const m = String(e && (e.message || e.name) || "");
    vcEnd(/busy/.test(m) ? "busy" : /NotAllowed|Permission|denied/i.test(m) ? "perm" : "fail", true);
  }
}
// ringing: long-poll the relay until the phone answers, declines or the ring times out (45 s on the relay)
async function vcWait(me, c) {
  let fails = 0;
  while (vc === me && me.st === "ring") {
    if (c && c.id === me.id && c.st === "on" && c.sdp) { me.st = "conn"; vcPaint(); try { await me.c.accept(c.sdp); } catch { return vcEnd("fail", true); } return; }
    if (c && c.id === me.id && c.st === "end") return vcEnd(c.why || "hangup", false);
    try { c = (await vcApi(me, `/call?id=${me.id}&st=ring`)).call; fails = 0; }
    catch { if (++fails > 3) return vcEnd("fail", true); await new Promise(s => setTimeout(s, 2000)); }
  }
}
function vcState(s) {
  if (!vc) return;
  if (s === "connected") { if (!vc.t0) vc.t0 = Date.now(); vc.st = "on"; vcPaint(); }
  else if (s === "disconnected") { vc.st = "weak"; vcPaint(); }   // often comes back by itself (Wi-Fi <-> 4G)
  else if (s === "failed") vcEnd("lost", true);
}
function vcMsg(m) {
  if (!vc || !m) return;
  if (m.t === "bye") vcEnd("kidEnd", false);
  else if (m.t === "cam") { vc.kidCam = !!m.on; vcPaint(); }
}
// why: hangup (me) | decline | missed | busy | noperm | nocam | kidEnd | lost | fail | perm | timeout | hidden; tell = let the relay know
function vcEnd(why, tell) {
  const me = vc; if (!me) return;
  vc = null;
  if (me.c) me.c.close();
  clearInterval(me.tick);
  if (tell && me.st !== "prep") vcApi(me, "/call", "POST", { op: "end", id: me.id, why: ["lost", "fail", "perm", "hidden"].includes(why) ? "failed" : why === "timeout" ? "timeout" : "hangup" }).catch(() => {});
  const el = $("#vcUi"); if (!el) return;
  const mins = me.t0 ? Math.max(1, Math.round((Date.now() - me.t0) / 60e3)) : 0, n = me.net;
  const line = why === "hangup" || why === "kidEnd" || why === "timeout" ? (mins ? t("call.done", mins) + (n && n.mb ? " · " + t("call.mb", n.mb) : "") : t("call.ended")) : t("call.e." + why);
  el.querySelector("#vcSt").textContent = line;
  el.querySelector(".vcbar").innerHTML = "";
  el.classList.add("over");
  setTimeout(() => { if (!vc && $("#vcUi") === el) el.remove(); }, why === "hangup" ? 1500 : 4000);
}
function vcShow() {
  $("#vcUi") && $("#vcUi").remove();
  const el = document.createElement("div");
  el.id = "vcUi"; el.className = "vc"; el.setAttribute("role", "dialog"); el.setAttribute("aria-modal", "true"); el.setAttribute("aria-label", t("call.h"));
  el.innerHTML = `<div class="vcwait" id="vcWait" aria-hidden="true">${esc([...String(vc.k.name || "?").trim()][0] || "?").toUpperCase()}</div><video id="vcRemote" autoplay playsinline></video><div class="vcoff" id="vcOff">${ic("video-off")}<span>${esc(t("call.kidCamOff"))}</span></div>
    <video id="vcLocal" autoplay playsinline muted></video>
    <div class="vctop"><b>${esc(vc.k.name || "")}</b><span id="vcSt" role="status"></span></div>
    <div class="vcbar"><button type="button" id="vcMic" class="vcb" aria-pressed="false"></button><button type="button" id="vcEnd" class="vcb end" aria-label="${esc(t("call.end"))}">${ic("phone-off")}</button><button type="button" id="vcCam" class="vcb" aria-pressed="false"></button></div>
    <button type="button" id="vcMore" class="vcmore" hidden>${ic("plus")} ${esc(t("call.more"))}</button>`;
  document.body.appendChild(el);
  el.querySelectorAll("video").forEach(v => v.addEventListener("playing", () => v.classList.add("live")));
  el.querySelector("#vcEnd").onclick = () => vcEnd("hangup", true);
  el.querySelector("#vcMic").onclick = () => { if (!vc || !vc.c) return; vc.mic = !vc.mic; vc.c.mute(!vc.mic); vcPaint(); };
  el.querySelector("#vcCam").onclick = () => { if (!vc || !vc.c) return; vc.cam = !vc.cam; vc.c.camera(vc.cam); vcPaint(); };
  el.querySelector("#vcMore").onclick = () => { if (!vc) return; vc.max += VC_MAX; vc.c && vc.c.send({ t: "more" }); vcPaint(); };
  vc.tick = setInterval(vcPaint, 1000);
  vcPaint();
}
function vcPaint() {
  const el = $("#vcUi"); if (!vc || !el) return;
  const left = vc.t0 ? vc.max - (Date.now() - vc.t0) : vc.max;
  if (vc.t0 && left <= 0) return vcEnd("timeout", true);
  const mm = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
  const n = vc.net, st = vc.st === "prep" ? t("call.prep") : vc.st === "ring" ? t("call.ring") : vc.st === "conn" ? t("call.conn")
    : vc.st === "weak" ? t("call.weak") : mm(Date.now() - vc.t0) + (n && n.low ? " · " + t("call.low") : "");
  const s = el.querySelector("#vcSt"); if (s.textContent !== st) s.textContent = st;
  const mic = el.querySelector("#vcMic"), cam = el.querySelector("#vcCam");
  const mh = ic(vc.mic ? "mic" : "mic-off"), chh = ic(vc.cam ? "video" : "video-off");
  if (mic.innerHTML !== mh) mic.innerHTML = mh;
  if (cam.innerHTML !== chh) cam.innerHTML = chh;
  mic.setAttribute("aria-pressed", String(!vc.mic)); mic.setAttribute("aria-label", t(vc.mic ? "call.mute" : "call.unmute"));
  cam.setAttribute("aria-pressed", String(!vc.cam)); cam.setAttribute("aria-label", t(vc.cam ? "call.camOff" : "call.camOn"));
  el.querySelector("#vcOff").style.display = vc.st === "on" && !vc.kidCam ? "flex" : "none";
  el.querySelector("#vcWait").style.display = vc.st === "on" ? "none" : "flex";
  el.querySelector("#vcMore").hidden = !(vc.t0 && left < 60e3);
}
// the page went to the background (another app, locked phone): a call nobody watches ends after VC_HIDDEN
document.addEventListener("visibilitychange", () => {
  if (!vc) return;
  clearTimeout(vc.hid);
  if (document.hidden) { const me = vc; me.hid = setTimeout(() => { if (vc === me) vcEnd("hidden", true); }, VC_HIDDEN); }
});
window.addEventListener("pagehide", () => { if (vc) vcEnd("hangup", true); });

// ---- Listen around over the relay (docs/flows/remote.md 2.6): the same /call slot with kind "listen" ----
// The phone sends its microphone only (no ring, no tap; the child is told by the beep + notification), we only receive.
// The bar / strip / Stop button are the ones of the LAN stream (live.js earBar / earStop); this file only does the signaling.
const EAR_MAX = 10 * 60e3;
let earRtc = null;   // { k, id, c (RTC call), st: prep | ring | on, tell?, why? }
async function earStartRtc() {
  const k = cur;
  if (earRtc || vc || CAM.on() || !k) return;
  const e = earBar(), au = $("#earAudio"), me = earRtc = { k, via: vcOk.via(k), id: vcId(), c: null, st: "prep", t0: 0 };
  earT = 0; e.style.display = "flex"; $("#earTxt").textContent = t("connecting");
  clearInterval(earStart.i);
  earStart.i = setInterval(() => {
    if (earRtc !== me) return;
    if (earT) $("#earTxt").textContent = t("ear.live", fmtClock(Date.now() - earT));
    if (me.t0 && Date.now() - me.t0 > EAR_MAX) earRtcEnd(me, "timeout", true);
  }, 1000);
  actPaint();
  try {
    const j = await vcApi(me, "/call?ice=1");
    if (earRtc !== me) return;
    me.c = await RTC.open({ listen: true, ice: j.ice || [], remote: au,
      onState: s => { if (earRtc !== me) return; if (s === "connected") { me.t0 = me.t0 || Date.now(); earT = earT || Date.now(); actSt.ear1 = { s: "done", t: Date.now() }; actPaint(); } else if (s === "failed") earRtcEnd(me, "failed", true); },
      onMsg: m => { if (m && m.t === "bye") earRtcEnd(me, "kidEnd", false); } });
    if (earRtc !== me) return me.c.close();
    const r = await vcApi(me, "/call", "POST", { op: "offer", id: me.id, kind: "listen", sdp: me.c.sdp });
    me.st = "ring";
    earRtcWait(me, r.call);
  } catch (err) { earRtcEnd(me, /busy/.test(String(err && (err.message || err))) ? "busy" : "failed", false); }
}
// long-poll the relay: the phone's answer (SDP) comes back as st "on"; an "end" carries why (refused mic, notice off, cap ...)
async function earRtcWait(me, c) {
  let fails = 0;
  while (earRtc === me) {
    if (!c || c.id !== me.id) return earRtcEnd(me, "hangup", false);   // gone (ended long ago)
    if (c.st === "end") return earRtcEnd(me, c.why || "hangup", false);
    if (c.st === "on" && c.sdp && me.st === "ring") { me.st = "on"; try { await me.c.accept(c.sdp); } catch { return earRtcEnd(me, "failed", true); } }
    try { c = (await vcApi(me, `/call?id=${me.id}&st=${me.st}`)).call; fails = 0; }
    catch { if (++fails > 3) return earRtcEnd(me, "failed", true); await new Promise(s => setTimeout(s, 2000)); }
  }
}
// why: hangup | timeout | kidEnd (normal) | missed | busy | failed | lost | a phone refusal ("no mic permission", "mic blocked", ...)
function earRtcEnd(me, why, tell) {
  if (earRtc !== me) return;
  me.tell = tell; me.why = why === "timeout" ? "timeout" : ["failed", "lost"].includes(why) ? "failed" : "hangup";
  const ok = ["hangup", "timeout", "kidEnd"].includes(why);
  earStop();
  if (ok) return toast("ok|" + t("ear.ended"));
  const text = why === "missed" ? t("ear.missed") : why === "failed" || why === "lost" ? t("ear.failed") : tc("ear.", why, t("ear.failed"));
  toast("err|" + t("ear.fail", text));
}
window.addEventListener("pagehide", () => { if (earRtc) earStop(); });

// ---- Live camera over the relay (docs/flows/remote.md 2.16): the same /call slot with kind "cam" (+ face, snd) ----
// One way like listen: the phone sends one camera (+ its mic when asked), we only receive (no camera / mic prompt here, a plain
// http page can do it). No ring, no tap; the child is told (ongoing notice + log, Android's camera dot). Rule watch.cam.
const CAM = (() => {   // one global: CAM.start(o), CAM.on() (call / listen refuse while it runs)
  const CAM_MAX = 10 * 60e3;
  let camRtc = null;   // { k, id, c (RTC call), st: prep | ring | conn | on | weak, t0, back, snd, mute, flip, tick, hid }
  async function camStart(o) {
    const k = cur;
    if (!k || camRtc) return;
    if (vc || earRtc || earOn()) return toast("warn|" + t("cam.busyHere"));
    if (!vcOk(k)) return ask(t("cam.noRelay"), t("call.ok"), false, "", "", null, t("cam.h"));
    if (!RTC.canListen()) return toast("warn|" + t("cam.noRtc"));
    const old = needOf("ui", "camGo"); if (old) return needAsk(old);
    if (!(await onlineNow.gate("cam"))) return;
    if (!(await ask(t(o.snd ? "cam.askSnd" : "cam.ask"), t("cam.on")))) return;
    if (camRtc || cur !== k) return;
    const me = camRtc = { k, via: vcOk.via(k), id: vcId(), c: null, st: "prep", t0: 0, back: o.cam === "back", snd: !!o.snd, mute: false, flip: false };
    camShow(me);
    try {
      const j = await vcApi(me, "/call?ice=1");
      if (camRtc !== me) return;
      me.c = await RTC.open({ cam: { back: me.back, snd: me.snd }, ice: j.ice || [], remote: $("#camVideo"),
        onState: s => camState(me, s), onMsg: m => camMsg(me, m) });
      if (camRtc !== me) return me.c.close();
      const r = await vcApi(me, "/call", "POST", { op: "offer", id: me.id, kind: "cam", face: me.back ? "back" : "front", snd: me.snd, sdp: me.c.sdp });
      me.st = "ring"; camPaint();
      camWait(me, r.call);
    } catch (err) {
      const m = String(err && (err.raw || err.message || err));
      camEnd(me, /busy/.test(m) ? "busy" : /\bold\b/.test(m) ? "old" : "failed", false);
    }
  }
  // long-poll the relay: the phone's answer comes back as st "on"; an "end" carries why (rule off, no permission, screen off ...)
  async function camWait(me, c) {
    let fails = 0;
    while (camRtc === me) {
      if (!c || c.id !== me.id) return camEnd(me, "hangup", false);
      if (c.st === "end") return camEnd(me, c.why || "hangup", false);
      if (c.st === "on" && c.sdp && me.st === "ring") { me.st = "conn"; camPaint(); try { await me.c.accept(c.sdp); } catch { return camEnd(me, "failed", true); } }
      try { c = (await vcApi(me, `/call?id=${me.id}&st=${me.st === "ring" ? "ring" : "on"}`)).call; fails = 0; }
      catch { if (++fails > 3) return camEnd(me, "failed", true); await new Promise(s => setTimeout(s, 2000)); }
    }
  }
  function camState(me, s) {
    if (camRtc !== me) return;
    if (s === "connected") { me.t0 = me.t0 || Date.now(); me.st = "on"; }
    else if (s === "disconnected") me.st = "weak";   // often comes back by itself (Wi-Fi <-> 4G)
    else if (s === "failed") return camEnd(me, "lost", true);
    camPaint();
  }
  function camMsg(me, m) {
    if (camRtc !== me || !m) return;
    if (m.t === "bye") camEnd(me, "kidEnd", false);
    else if (m.t === "face") { me.flip = false; me.back = !!m.back; if (m.ok === false) toast("warn|" + t("cam.flipFail")); camPaint(); }
  }
  // why: hangup (me) | timeout | kidEnd | hidden | lost | failed | busy | old | a phone refusal ("off", "screen off", "no camera permission", ...)
  function camEnd(me, why, tell) {
    if (camRtc !== me) return;
    camRtc = null;
    if (me.c) me.c.close();
    clearInterval(me.tick); clearTimeout(me.hid);
    if (tell && me.st !== "prep") vcApi(me, "/call", "POST", { op: "end", id: me.id, why: ["lost", "failed", "hidden"].includes(why) ? "failed" : why === "timeout" ? "timeout" : "hangup" }).catch(() => {});
    const el = $("#camUi");
    if (why === "old") { if (el) el.remove(); needAsk(needOf("ui", "camGo") || CAM_KID); return; }
    if (!el) return;
    const ok = ["hangup", "timeout", "kidEnd"].includes(why);
    const mins = me.t0 ? Math.max(1, Math.round((Date.now() - me.t0) / 60e3)) : 0;
    el.querySelector("#camSt").textContent = ok ? (why === "timeout" ? t("cam.e.timeout") : mins ? t("cam.done", mins) : t("call.ended"))
      : tc("cam.e.", why.replace(/ /g, "_"), t("cam.e.failed"));
    el.querySelector(".vcbar").innerHTML = `<button type="button" class="vcb end" id="camClose" aria-label="${esc(t("cam.close"))}">${ic("x")}</button>`;
    el.querySelector("#camClose").onclick = () => el.remove();
    el.classList.add("over");
    if (ok) setTimeout(() => { if ($("#camUi") === el && !camRtc) el.remove(); }, 1500);   // an error stays until closed: it says what to do
  }
  const CAM_KID = "0.9.71";   // first kid app with the live camera (NEED in kid.js, CAM_KID in the worker)
  function camShow(me) {
    $("#camUi") && $("#camUi").remove();
    const el = document.createElement("div");
    el.id = "camUi"; el.className = "vc"; el.setAttribute("role", "dialog"); el.setAttribute("aria-modal", "true"); el.setAttribute("aria-label", t("cam.h"));
    el.innerHTML = `<div class="vcwait" id="camWait" aria-hidden="true">${ic("camera")}</div><video id="camVideo" autoplay playsinline></video>
      <div class="vctop"><b>${esc(me.k.name || "")}</b><span id="camSt" role="status"></span></div>
      <div class="vcbar"><button type="button" id="camFlip" class="vcb" aria-label="${esc(t("cam.flip"))}">${ic("refresh-cw")}</button><button type="button" id="camEnd" class="vcb end" aria-label="${esc(t("cam.stop"))}">${ic("square")}</button>${me.snd ? `<button type="button" id="camSnd" class="vcb" aria-pressed="false"></button>` : ""}</div>`;
    document.body.appendChild(el);
    const v = el.querySelector("#camVideo");
    v.addEventListener("playing", () => v.classList.add("live"));
    el.querySelector("#camEnd").onclick = () => camEnd(me, "hangup", true);
    el.querySelector("#camFlip").onclick = () => { if (camRtc !== me || me.st !== "on" || me.flip || !me.c) return; me.flip = true; me.c.send({ t: "flip" }); camPaint(); setTimeout(() => { if (me.flip) { me.flip = false; camPaint(); } }, 8000); };
    const sb = el.querySelector("#camSnd"); if (sb) sb.onclick = () => { me.mute = !me.mute; v.muted = me.mute; camPaint(); };
    me.tick = setInterval(camPaint, 1000);
    camPaint();
  }
  function camPaint() {
    const me = camRtc, el = $("#camUi"); if (!me || !el) return;
    const left = me.t0 ? CAM_MAX - (Date.now() - me.t0) : CAM_MAX;
    if (me.t0 && left <= 0) return camEnd(me, "timeout", true);
    const mm = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
    const st = me.st === "prep" ? t("cam.prep") : me.st === "ring" ? t("cam.ring") : me.st === "conn" ? t("call.conn") : me.st === "weak" ? t("call.weak")
      : t("cam.live", t(me.back ? "cam.back" : "cam.front"), mm(Date.now() - me.t0), mm(left)) + (me.flip ? " · " + t("cam.flipping") : "");
    const s = el.querySelector("#camSt"); if (s.textContent !== st) s.textContent = st;
    const f = el.querySelector("#camFlip"); if (f) f.disabled = me.st !== "on" || me.flip;
    const sb = el.querySelector("#camSnd");
    if (sb) { const h = ic(me.mute ? "mic-off" : "mic"); if (sb.innerHTML !== h) sb.innerHTML = h; sb.setAttribute("aria-pressed", String(me.mute)); sb.setAttribute("aria-label", t(me.mute ? "cam.sndOn" : "cam.sndOff")); }
    el.querySelector("#camWait").style.display = me.st === "on" ? "none" : "flex";
  }
  // nobody watching (another app, locked phone): ends after VC_HIDDEN like the call
  document.addEventListener("visibilitychange", () => {
    const me = camRtc; if (!me) return;
    clearTimeout(me.hid);
    if (document.hidden) me.hid = setTimeout(() => { if (camRtc === me) camEnd(me, "hidden", true); }, VC_HIDDEN);
  });
  window.addEventListener("pagehide", () => { if (camRtc) camEnd(camRtc, "hangup", true); });
  return { start: camStart, on: () => !!camRtc };
})();

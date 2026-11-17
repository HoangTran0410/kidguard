// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- live status: PIN gate, banners, phone card, strip, check-in, SOS, listen, per-kid action buttons ----
// Legacy phone (paired, no PIN): a screen with no way around it until the PIN is saved. Setting it on the child phone
// itself is not offered (the child holds it); the on-device admin session reaches this only via the Authenticator.
function pinGate(a) {
  screenStop();
  a.className = ""; a.innerHTML = `<div class="solo">${localBar()}
  <div class="head">${!LOCAL && (APP || kids.length > 1 || cur.via === "relay") ? `<button type="button" id="back" class="back" aria-label="${esc(t("list"))}">${ic("chevron-left")}</button>` : ""}${kidAv(cur)}<div><div class="t1">${esc(cur.name)}</div></div></div>
  <div class="card"><h2>${ic("key-round")} ${t("pinGate.h")}</h2><p>${t("pinGate.p")}</p>
    <div class="pairf"><input id="f-gp1" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin1"))}" aria-label="${esc(t("pair.pin1"))}">
    <input id="f-gp2" type="password" inputmode="numeric" maxlength="8" autocomplete="new-password" placeholder="${esc(t("pair.pin2"))}" aria-label="${esc(t("pair.pin2"))}">
    <button class="pri" id="gpGo">${ic("key-round")} ${t("pinGate.go")}</button></div><p class="err" id="gpE" role="alert"></p></div></div>`;
  localWire();
  if ($("#back")) $("#back").onclick = () => { goList(); navDown(0); };
  const go = $("#gpGo"), err = $("#gpE");
  go.onclick = async () => {
    const x = $("#f-gp1").value, y = $("#f-gp2").value;
    if (!/^\d{4,8}$/.test(x)) return err.textContent = t("pe.bad pin");
    if (x !== y) return err.textContent = t("pe.mismatch");
    if (APP && cachedAt) return err.textContent = t("pinGate.off");
    go.disabled = true; err.textContent = t("pinGate.wait"); clearTimeout(saveT);
    const k = cur, e = edit;
    try { e.pin = x; await putRules(k, e); if (k === cur) { lastSig = ""; render(); } }
    catch (er) { delete e.pin; go.disabled = false; err.textContent = er.message; }
  };
}

// Header status line + banner stack (priority order, 1 shown + "N more"). SOS and check-ins sit on top and never fold.
let banAll = false;
const banHid = new Set();   // banners hidden with their x: this page session only, they come back next time
// "Điện thoại của con" card on Hôm nay: connection, battery, screen (was one long line under the kid's name)
function phoneCard(online) {
  const appOf = p => p && p.startsWith("dev.kidguard") ? "KidGuard" : ((st.apps || []).find(a => a.pkg === p) || {}).label || p;
  const cell = (tone, i, big, sub, id) => `<${id ? `button type="button" id="${id}"` : "div"} class="pc"><span class="pci t-${tone}">${ic(i)}</span><b>${big}</b><span class="mute">${sub}</span></${id ? "button" : "div"}>`;
  const bat = +st.battery, cur0 = st.screenOn && st.current ? appOf(st.current) : "", n = st.net;
  // network cell: the real link (Wi-Fi name / carrier / data), not just "online"; tap = details. Old apps send no net.
  const nIcon = !online || (n && n.type === "none") ? "wifi-off" : n && n.type === "cell" ? "signal" : n && n.type !== "wifi" ? "globe" : "wifi";
  const nBig = !n ? (online ? t("live.on") : t("live.off")) : esc(n.ssid || n.carrier || tc("dv.n.", n.type, n.type));
  return `<div class="lhead"><h2>${ic("smartphone")} ${t("pc.title")}</h2></div><div class="pcg">`
    + cell(online ? "ok" : "bad", nIcon, nBig, n ? (online ? "" : t("live.off") + " · ") + ago(lastSeen()) : ago(lastSeen()), n ? "pcNet" : "")
    + cell(st.charging ? "ok" : bat <= 15 ? "bad" : bat <= 30 ? "warn" : "info", st.charging ? "zap" : "battery-full", bat + "%", st.charging ? t("pc.charging") : t("pc.battery"))
    + cell(st.screenOn ? "info" : "mute", "smartphone", st.screenOn ? t("pc.on") : t("pc.off"), cur0 ? esc(cur0) : t("pc.screen"))
    + `</div>`;
}
function netSheet() {
  const n = st && st.net; if (!n) return;
  const kv = (k, v) => v === undefined || v === null || v === "" ? "" : `<div class="dvkv"><span>${k}</span><b>${v}</b></div>`;
  // Tailscale: its address whenever the phone has one (basic mode: set up by hand, state stays "off" but ip is found);
  // else say why in words (was the bare JS value "false", owner 2026-11-17)
  const T = st.ts || {}, ts = T.ip ? esc(T.ip) : `<span class="mute" style="font-weight:400">${t(T.installed ? "net.tsOff" : "net.tsNone")}</span>`;
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead(n.type === "cell" ? "signal" : "wifi", t("net.h"))}
    ${kv(t("dv.net"), esc(tc("dv.n.", n.type, n.type)) + (n.metered && n.type !== "none" ? ` <span class="mute" style="font-weight:400">· ${t("dv.metered")}</span>` : ""))}
    ${n.type === "wifi" ? kv(t("net.ssid"), n.ssid ? esc(n.ssid) : `<span class="mute" style="font-weight:400">${t("net.noSsid")}</span>`) : ""}
    ${kv(t("net.carrier"), n.carrier && esc(n.carrier))}
    ${n.rssi != null ? kv(t("net.signal"), (n.bars != null ? n.bars + "/4 · " : "") + n.rssi + " dBm") : ""}
    ${kv(t("net.band"), n.ghz && n.ghz + " GHz")}${kv(t("net.speed"), n.mbps && n.mbps + " Mbps")}
    ${kv(t("net.ip"), n.ip && esc(n.ip))}${st.ts ? kv(t("net.ts"), ts) : ""}
    ${kv(t("net.mac"), n.hwMac && esc(n.hwMac))}${n.hwMac && n.type === "wifi" ? `<p class="mute">${t("net.macNote")}</p>` : ""}
    ${kv(t("net.seen"), ago(lastSeen()))}</div>`);
}
document.addEventListener("click", e => { if (e.target.closest && e.target.closest("#pcNet")) netSheet(); });
function updateLive() {
  paintSave(); if (tab === "screen") screenCtl();
  const el = $("#live"), hs = $("#hst"); if (!el) return;
  if (!st) { if (hs) hs.innerHTML = `${dot(lastErr ? "bad" : "")}<span>${lastErr ? esc(lastErr) : t("connecting")}</span>`; el.innerHTML = ""; return; }
  if (viewDay) { if (hs) { delete hs.dataset.h; hs.innerHTML = `${ic("clock")}<span>${t("hist.snap", hhmm(st.t))} · ${+st.battery}%</span>`; } el.innerHTML = ""; return; }
  const lan = cur.via === "lan", online = onlineNow();
  const lost = lan ? (lastErr || Date.now() - st.t > 300000) : (seenAt && Date.now() - seenAt > 300000);
  const lostFrom = lan ? st.t : seenAt;
  const sk = document.querySelector(".kidn.on");   // sidebar dot is rendered once; keep it in step with the header
  if (sk) { sk.querySelector(".dot")?.remove(); sk.insertAdjacentHTML("beforeend", dot(online ? "on" : "bad")); }
  if (hs) { const h = `${dot(online ? "on" : "bad")}<span>${online ? t("live.on") : t("live.off")} · ${ago(lastSeen())}</span>`; if (hs.dataset.h !== h) { hs.dataset.h = h; hs.innerHTML = h; } }
  const pc = $("#phoneCard"); if (pc) { const h = phoneCard(online); if (pc.dataset.h !== h) { pc.dataset.h = h; pc.innerHTML = h; } }
  const B = [];   // [tone, icon, text]
  if (APP && cachedAt) B.push(["warn", "wifi-off", t("live.cached", hhmm(cachedAt))]);
  if (!lan && lastNet) B.push(["warn", "wifi-off", t("live.selfOff")]);
  // LAN, the phone answered with an error: it is on and reachable, so not "off / no network"
  if (lan && lastErr && !lastNet && retryShow) B.push(["info", "loader", t("live.retrying")]);   // a retry is in flight: not the old error
  else if (lan && lastErr && !lastNet) B.push(["bad", "siren", t("live.phoneErr", esc(lastErr))]);
  else if (lost && !(!lan && lastNet)) B.push(["bad", "siren", t("live.lost", hhmm(lostFrom)) + (lastErr && lan ? " (" + esc(lastErr) + ")" : "")]);
  B.unshift(...rcBans());   // command receipts: what the parent just sent comes first
  B.push(...modeBans());
  { const rb = rebootLine(), lc = locLine(); if (rb) B.push(rb); if (lc) B.push(lc); }
  // bank-app pauses (audit 2026-11-13): what is off, for which app, when it comes back, and "turn back on now"
  const bkApps = [...new Set([...(st.accPaused || []), ...(st.vpnPaused || []), ...(st.devPaused || [])])];
  if (bkApps.length) {
    const what = [(st.accPaused || []).length && t("bk.acc"), (st.vpnPaused || []).length && "VPN", (st.devPaused || []).length && t("bk.dev")].filter(Boolean).join(", ");
    const when = st.bankBackAt ? t("bk.backAt", hhmm(st.bankBackAt)) : t("bk.backLeave");
    B.push(["info", "landmark", t("bk.live", esc(bkApps.map(p => ((st.apps || []).find(a => a.pkg === p) || {}).label || p).join(", ")), what) + " " + when
      + (st.bankBackAt !== undefined ? ` <button type="button" class="pill" id="bankEndBtn">${ic("refresh-cw")} ${t("bk.endNow")}</button>` : "")]);
  }
  else if (st.acc === false && !Array.isArray(st.missing)) B.push(["warn", "triangle-alert", t("live.acc")]);
  if (Array.isArray(st.missing) && st.missing.length) {   // phones >= 0.9.22: one band, one Fix (docs/protocol.md "missing")
    const m = st.missing, fp = st.fixPerms || {}, names = m.map(capName).join(", "), tap = (fp.tap || []).filter(x => m.includes(x));
    B.push(["bad", "triangle-alert", (m.length === 1 ? t("fp.live1", esc(names)) : t("fp.live", m.length, esc(names)))
      + (fp.busy ? ` <span class="mute">${t("fp.busy")}</span>` : tap.length && Date.now() - fp.at < 3600e3 ? ` <span class="mute">${t("fp.tap", esc(tap.map(capName).join(", ")))}</span>` : "")
      , undefined, undefined, fp.busy ? "" : `<button type="button" class="pill" id="fixPermsBtn">${ic("wrench")} ${t("fp.btn")}</button>`]);   // [5] = right-side action: inside the text it wrapped to its own line
  }
  if ((st.usageAccess === false || st.usageOff) && !Array.isArray(st.missing)) B.push(["bad", "triangle-alert", t("live.usage")]);
  if (st.dnsError) B.push(["warn", "triangle-alert", t("live.dns", esc(st.dnsError))]);
  if (st.locationOn === false) B.push(["warn", "triangle-alert", t("live.locOff")]);
  // worst first (stable): only the first band shows folded, and an info band ("basic mode") used to hide the red "N things are off" + Fix (owner 2026-11-16)
  const RANK = { bad: 0, warn: 1 }, B2 = B.filter(x => !(x[3] && banHid.has(x[3]))).map((x, i) => [x, i]).sort((a, c) => ((RANK[a[0][0]] ?? 2) - (RANK[c[0][0]] ?? 2)) || a[1] - c[1]).map(x => x[0]);
  const ban = x => `<div class="ban t-${x[0]}" role="status"><span class="bi">${ic(x[1])}</span><div class="x">${x[2]}${x[4] ? ` <button type="button" class="banlink" data-band="${x[3]}">${t("ban.why")}</button>` : ""}</div>${x[5] || ""}${x[4] ? `<button type="button" class="banx" data-banx="${x[3]}" aria-label="${esc(t("ban.hide"))}" title="${esc(t("ban.hide"))}">${ic("x")}</button>` : ""}</div>`;
  const strip = $("#strip") ? "" : stripHtml();   // home has its own spot under the round buttons
  el.innerHTML = sosHtml() + ciHtml() + strip + (banAll ? B2 : B2.slice(0, 1)).map(ban).join("")
    + (B2.length > 1 ? `<button type="button" class="more-ban" id="banMore" aria-expanded="${banAll}">${ic(banAll ? "chevron-down" : "chevron-right")} ${banAll ? t("ban.less") : t("ban.more", B2.length - 1)}</button>` : "");
  el.querySelectorAll("[data-band]").forEach(x => x.onclick = () => { const d = B.find(y => y[3] === x.dataset.band); if (d) ask(d[4], "OK", false, "", "", null, d[2]); });
  el.querySelectorAll("[data-banx]").forEach(x => x.onclick = () => { banHid.add(x.dataset.banx); updateLive(); });
  if ($("#banMore")) $("#banMore").onclick = () => { banAll = !banAll; updateLive(); };
  wireSos(el);
  wireCi(el);
  wireStrip(el);
  actPaint();
}
// ring / listen "active strip": what is on right now on the kid's phone + one Stop button
function stripHtml() {
  if (!st || viewDay) return "";
  const local = $("#earAudio") && $("#earAudio").getAttribute("src");   // our own player has its own bar (#ear)
  return (earOn() && !local ? `<div class="actbar" role="status">${ic("ear")}<span class="x">${t("live.mic", hhmm(st.listen && st.listen.since || Date.now()))}</span><button type="button" id="micStop" class="stopb" ${actSt.ear1 && actSt.ear1.s === "pend" ? 'disabled aria-busy="true"' : ""}>${actSt.ear1 && actSt.ear1.s === "pend" ? '<i class="spin" style="width:14px;height:14px"></i> ' + t("act.sending") : ic("square") + " " + t("act.off")}</button></div>` : "")
    + (ringOn() ? `<div class="actbar" role="status">${ic("bell")}<span class="x">${t("act.ringOn")}</span><button type="button" id="ringStop2" class="stopb" ${actSt.ring && actSt.ring.s === "pend" ? 'disabled aria-busy="true"' : ""}>${actSt.ring && actSt.ring.s === "pend" ? '<i class="spin" style="width:14px;height:14px"></i> ' + t("act.sending") : ic("square") + " " + t("act.off")}</button></div>` : "");
}
function wireStrip(root) {
  const m = root.querySelector("#micStop"), r = root.querySelector("#ringStop2");
  if (m) m.onclick = micStop; if (r) r.onclick = ringStop;
}
function paintStrip() {
  const s = $("#strip"); if (!s) return;
  const h = stripHtml(); if (s.dataset.h === h) return;
  s.dataset.h = h; s.innerHTML = h; wireStrip(s);
}

// ---- v0.9.7 check-in (child tapped "Con tới nơi rồi" / "Đón con với"): banner until "Đã xem" (per browser), 3 h max ----
let ciSeen = (() => { try { return new Set(JSON.parse(localStorage.getItem("kg.ciSeen") || "[]")); } catch { return new Set(); } })();
function ciLast() {
  const e = ((st && st.events) || []).filter(x => x.kind === "checkin" && Date.now() - x.t < 3 * 3600e3 && !ciSeen.has(x.id));
  return e[e.length - 1];
}
function ciHtml() {
  const e = !viewDay && ciLast(); if (!e) return "";
  const l = e.loc, pk = e.ck === "pickup";
  return `<div class="alert${pk ? " warn" : ""}" role="status"${pk ? "" : ' style="background:var(--ok-bg);color:var(--ok-fg)"'}>${ic(pk ? "hand" : "map-pin")} <b>${t(pk ? "ci.pickup" : "ci.arrived", esc(cur.name), hhmm(e.t))}</b> <span class="mute">· ${l ? t("ci.loc", Math.round(+l.acc || 0)) : t("ci.noLoc")}</span>
    <div class="row" style="margin-top:8px;font-weight:400"><button data-ci="where" ${l || st.location ? "" : "disabled"}>${ic("map-pin")} ${t("ci.where")}</button><button data-ci="seen" class="pri" data-id="${esc(e.id)}">${ic("check")} ${t("ci.seen")}</button></div></div>`;
}
function wireCi(el) {
  el.querySelectorAll("[data-ci]").forEach(b => b.onclick = () => {
    if (b.dataset.ci === "where") { tab = "where"; render(); return; }
    ciSeen.add(b.dataset.id); try { localStorage.setItem("kg.ciSeen", JSON.stringify([...ciSeen].slice(-50))); } catch {}
    updateLive();
  });
}

// ---- SOS (child pressed the button) ----
function sosHtml() {
  const list = (st && st.sos) || [];
  if (!list.length || viewDay) return "";
  const last = list[list.length - 1];
  return `<div class="alert sos">${ic("siren")} ${t("sos.at", hhmm(last.t))}${list.length > 1 ? " " + t("sos.n", list.length) : ""}
    <div class="row" style="margin-top:8px;font-weight:400"><button data-sos="where">${ic("map-pin")} ${t("sos.where")}</button>
    ${cur.via === "lan" ? `<button data-sos="ear">${ic("ear")} ${t("listen")}</button>` : ""}
    <button data-sos="ack" class="pri">${ic("check")} ${t("sos.ack")}</button></div></div>`;
}
function wireSos(el) {
  el.querySelectorAll("[data-sos]").forEach(b => b.onclick = async () => {
    const w = b.dataset.sos;
    if (w === "where") { tab = "where"; render(); }
    else if (w === "ear") earStart();
    else if (await cmd({ type: "sosAck" }, t("sos.cleared"), [t("sos.askAck"), t("sos.ack")])) { st.sos = []; updateLive(); }
  });
}

// ---- Listen around (LAN / Tailscale only; the child always hears a beep and sees a notification) ----
// listen errors from the phone: keys "ear.<code>"
let earT = 0, earErr0 = "";
function earBar() {
  let e = $("#ear");
  if (!e) {
    e = document.createElement("div"); e.id = "ear"; e.setAttribute("role", "status");
    e.innerHTML = `<span style="color:var(--warn)">${ic("ear")}</span><span id="earTxt" class="sp">${t("connecting")}</span><button id="earStop" class="bad">${ic("square")} ${t("stop")}</button><audio id="earAudio" preload="none"></audio>`;
    document.body.appendChild(e);
    $("#earStop").onclick = () => earStop();
    const au = $("#earAudio");
    au.onplaying = () => { earT = earT || Date.now(); const w = want.listen; if (w && w.on) { delete want.listen; liveOn.listen = true; actSt[w.id] = { s: "done", t: Date.now() }; actPaint(); } };
    // the phone ends the stream itself on 10 min, a blocked mic ("mic silenced") or a stuck one: say which
    const ended = async (fail) => { if (!au.getAttribute("src")) return; earStop(); await refresh().catch(() => {}); const e0 = st && st.listen && st.listen.err, er = e0 && (e0 !== earErr0 || e0 === "no mic permission") ? e0 : "";   // a missing permission is never "old news"
      if (er || fail) toast("err|" + t("ear.fail", er ? tc("ear.", er, er) : t("e.lost"))); else toast("ok|" + t("ear.ended")); };
    au.onended = () => ended(false);
    au.onerror = () => ended(true);
    au._end = ended;
  }
  return e;
}
async function earStart() {
  if (!cur || cur.via !== "lan") return toast("warn|" + t("ear.lanOnly"));
  if (ringOn()) return toast("warn|" + t("act.noEar"));
  // no mic: offer the same Fix as the "N things are off" banner (self-adb grant, else the phone opens the Permissions screen)
  if (st && st.listen && st.listen.mic === false) { if (await ask(t("ear.no mic permission"), t("fp.btn"), false, "", "", null, t("ear.cant"))) cmd({ type: "fixPerms" }, t("fp.sent"), false); return; }
  if (!(await ask(t("ear.ask"), t("ear.on")))) return;
  earStart.k = cur;
  const e = earBar(), au = $("#earAudio");
  earT = 0; e.style.display = "flex"; $("#earTxt").textContent = t("connecting");
  earErr0 = st && st.listen ? st.listen.err || "" : "";   // only an error that shows up after this press is ours
  expect("listen", true, "ear1");
  earPlay(`${base(cur)}/listen?k=${encodeURIComponent(cur.token)}&t=${Date.now()}`, au);
  clearInterval(earStart.i);
  earStart.i = setInterval(() => { if (earT) $("#earTxt").textContent = t("ear.live", fmtClock(Date.now() - earT)); }, 1000);
}
// A plain <audio src> waits ~8 s before the first sound on this 32 kbit/s live stream (the browser buffers ~32 KB to
// probe it) and then stays 8 s behind. MediaSource: we feed the bytes ourselves, first sound ~0.4 s, and playback is kept
// at the live edge (a Wi-Fi hiccup jumps forward instead of adding delay for the rest of the session).
function earPlay(url, au) {
  const MS = window.ManagedMediaSource || window.MediaSource;
  if (!MS || !MS.isTypeSupported || !MS.isTypeSupported("audio/aac")) { au.src = url; au.play().catch(() => {}); return; }
  const ctl = new AbortController(), ms = new MS(); earPlay.ctl = ctl;
  au.disableRemotePlayback = true;   // required by ManagedMediaSource (iOS Safari)
  au.src = earPlay.url = URL.createObjectURL(ms);
  const fail = () => { if (!ctl.signal.aborted) { ctl.abort(); au._end && au._end(true); } };
  ms.addEventListener("sourceopen", async () => {
    let sb; try { sb = ms.addSourceBuffer("audio/aac"); sb.mode = "sequence"; } catch { return fail(); }
    const q = []; let started = false, done = false;
    const pump = () => {
      if (sb.updating || ms.readyState !== "open") return;
      if (q.length) { try { sb.appendBuffer(q.shift()); } catch { fail(); } }
      else if (done) { try { ms.endOfStream(); } catch {} }   // the phone ended it: play out, then "ended"
    };
    sb.addEventListener("updateend", () => {
      const b = au.buffered;
      if (b.length) {
        const end = b.end(b.length - 1);
        if (!started) { started = true; au.currentTime = Math.max(b.start(0), end - 0.3); au.play().catch(() => {}); }
        else if (end - au.currentTime > 1.2) au.currentTime = end - 0.3;
        if (b.start(0) < au.currentTime - 45) { try { sb.remove(0, au.currentTime - 30); return; } catch {} }   // memory stays small over 10 min
      }
      pump();
    });
    try {
      const r = await fetch(url, { cache: "no-store", signal: ctl.signal });
      if (!r.ok || !r.body) return fail();
      const rd = r.body.getReader();
      for (;;) { const { value, done: d } = await rd.read(); if (d) break; q.push(value); pump(); }
      done = true; pump();
    } catch { fail(); }
  }, { once: true });
}
function earStop(msg) {
  clearInterval(earStart.i); earT = 0;
  if (earPlay.ctl) { earPlay.ctl.abort(); earPlay.ctl = null; }
  const au = $("#earAudio"), had = au && au.getAttribute("src"); if (au) { au.pause(); au.removeAttribute("src"); au.load(); }
  if (earPlay.url) { URL.revokeObjectURL(earPlay.url); earPlay.url = ""; }
  const e = $("#ear"); if (e) e.style.display = "none";
  // closing the player is not enough when the Wi-Fi dropped: tell the phone too, so its mic (and banner) never sticks
  if (had && earStart.k) call(earStart.k, "/cmd", "POST", { type: "listenStop" }).catch(() => {});
  earStart.k = null;
  if (msg) toast("ok|" + msg);
  updateLive(); actPaint();
}

// ---- per-kid action buttons: idle / pending (spinner, disabled) / active (accent, tap = confirm stop) / done / error ----
// Active comes from the phone's own state (st.ring, st.listen), never from a local guess. Conflicts (docs/protocol.md
// "Action states"): no ring while listening, no listening while ringing (the phone refuses too).
const actSt = {};   // button id -> {s: "pend" | "done" | "err", t: ms}
// The phone's /state can lag a command by a poll or two. So: a sent ring/listen (or its stop) keeps the button pending
// until /state shows the wanted on/off (~8 s, then error); without a pending command, a change needs 2 polls in a row.
const liveOn = { ring: null, listen: null };   // shown state; null = nothing seen yet for this kid (take the first poll as is)
const want = {}, flips = {};                    // key -> {on, until, id}; key -> polls in a row that disagree
const rawOn = key => !!(st && st[key] && st[key].on);
function settle() {
  if (!st) return;
  for (const key of ["ring", "listen"]) {
    const raw = rawOn(key), w = want[key];
    if (w) {
      if (raw === w.on) { liveOn[key] = raw; flips[key] = 0; delete want[key]; actSt[w.id] = { s: "done", t: Date.now() }; }
      else if (Date.now() > w.until) { delete want[key]; liveOn[key] = raw; flips[key] = 0; actSt[w.id] = { s: "err", t: Date.now() }; toast("err|" + t("act.noConfirm")); setTimeout(actPaint, 6100); }
      continue;
    }
    if (liveOn[key] === null) { liveOn[key] = raw; continue; }
    if (raw !== liveOn[key]) { flips[key] = (flips[key] || 0) + 1; if (flips[key] >= 2) { liveOn[key] = raw; flips[key] = 0; } } else flips[key] = 0;
  }
}
// wait for /state to show `on` for `key`; polls every second meanwhile
function expect(key, on, id) {
  want[key] = { on, until: Date.now() + 8000, id }; actSt[id] = { s: "pend", t: Date.now() }; actPaint();
  const k = cur;
  const tick = async () => { if (!want[key] || k !== cur) return; await refresh().catch(() => {}); if (k !== cur) { delete want[key]; return; } updateLive(); if (want[key]) setTimeout(tick, 1000); else setTimeout(actPaint, 2600); };
  setTimeout(tick, 700);
}
function wantReset() { for (const k of Object.keys(want)) delete want[k]; liveOn.ring = liveOn.listen = null; flips.ring = flips.listen = 0; }
const ringOn = () => !!liveOn.ring;
const earOn = () => !!liveOn.listen || !!($("#earAudio") && $("#earAudio").getAttribute("src"));
const ACT = { lock: ["lock", "lockScr"], wake: ["lightbulb", "wake"], ring: ["bell", "ring"], loc: ["map-pin", "locate"], loc2: ["map-pin", "where.locNew"], ear1: ["ear", "listen"] };
const ACT_TO = {};   // 20 s without an answer = error ("the phone did not answer")
// q: the confirm question ([text, ok, danger]); asked first, so the 20 s answer timer starts only once the parent agreed
async function act(id, fn, q) {
  if (actSt[id] && actSt[id].s === "pend") return;
  if (q && !(await ask(...q))) return;
  actSt[id] = { s: "pend", t: Date.now() }; actPaint();
  const r = await Promise.race([fn(), new Promise(res => setTimeout(() => res(ACT_TO), 20000))]);
  if (r === ACT_TO) toast("err|" + t("act.timeout"));
  if (r && r !== ACT_TO && Object.values(want).some(w => w.id === id)) return actPaint();   // waiting for /state to confirm
  actSt[id] = r === undefined || r === ACT_TO ? { s: "err", t: Date.now() } : { s: r.result === "queued" ? "sent" : "done", t: Date.now() };
  actPaint(); setTimeout(actPaint, 2600); if (actSt[id].s === "err") setTimeout(actPaint, 6100);
}
// .tile = round button (icon in <b>, label under it); other .act buttons = icon + label inline (the "Thêm" sheet)
function actPaint() {
  for (const id of Object.keys(ACT)) {
    const b = $("#" + id); if (!b || !b.classList.contains("act")) continue;
    const a = actSt[id], fresh = a && (a.s === "pend" || Date.now() - a.t < (a.s === "err" ? 6000 : 2500)), tile = b.classList.contains("tile");
    let cls = "", icn = ACT[id][0], lab = t(ACT[id][1]), why = "";
    if (fresh && a.s === "pend") { cls = "pend"; lab = t(id === "loc" || id === "loc2" ? "act.finding" : "act.sending"); }
    else if (id === "ring" && ringOn()) { cls = "on"; lab = t(tile ? "act.ringing" : "act.ringOn"); }
    else if (id === "ear1" && earOn()) { cls = "on"; lab = t(tile ? "act.listening" : "act.earOn"); }
    else if (id === "ring" && earOn()) { cls = "dis"; why = t("act.noRing"); }
    else if (id === "ear1" && ringOn()) { cls = "dis"; why = t("act.noEar"); }
    else if (fresh && a.s === "err") { cls = "err"; icn = "triangle-alert"; lab = t("act.err"); }
    else if (fresh) { cls = "done"; icn = "check"; lab = t(a.s === "sent" ? "act.sent" : "act.done"); }
    else if (id === "lock" && screenOff()) { icn = ACT.wake[0]; lab = t(ACT.wake[1]); }   // already locked: the useful action is waking it
    b.classList.remove("on", "pend", "done", "err", "dis"); if (cls) b.classList.add(cls);
    const h = tile ? `<b>${cls === "pend" ? '<i class="spin"></i>' : ic(icn)}</b><span>${lab}</span>` : `${cls === "pend" ? "" : ic(icn)} ${lab}`;
    if (b.innerHTML !== h) b.innerHTML = h;
    b.disabled = cls === "pend"; b.setAttribute("aria-busy", cls === "pend"); b.title = why;
    if (why) b.setAttribute("aria-disabled", "true"); else b.removeAttribute("aria-disabled");
  }
  // listening is reached through "Thêm": that round button turns amber while the mic is on
  const m = $("#moreAct");
  if (m) { const on = earOn(), h = on ? `<b>${ic("ear")}</b><span>${t("act.listening")}</span>` : `<b>${ic("ellipsis")}</b><span>${t("nav.more")}</span>`; m.classList.toggle("on", on); if (m.innerHTML !== h) m.innerHTML = h; }
  paintStrip();
}
async function ringStop() {
  if (!cur || !(await ask(t("ring.askStop"), t("ringStop")))) return;
  actSt.ring = { s: "pend", t: Date.now() }; actPaint();
  const r = await cmd({ type: "ringStop" }, t("ring.stopped"), false);
  if (r && r.result !== "queued") expect("ring", false, "ring"); else { actSt.ring = r ? { s: "sent", t: Date.now() } : { s: "err", t: Date.now() }; actPaint(); }
}
async function micStop() {
  if (!cur || !(await ask(t("ear.askStop"), t("micStop")))) return;
  const had = $("#earAudio") && $("#earAudio").getAttribute("src"); earStop();
  if (had) return expect("listen", false, "ear1");   // earStop already sent listenStop
  actSt.ear1 = { s: "pend", t: Date.now() }; actPaint();
  const r = await cmd({ type: "listenStop" }, t("turnedOff"), false);
  if (r && r.result !== "queued") expect("listen", false, "ear1"); else { actSt.ear1 = r ? { s: "sent", t: Date.now() } : { s: "err", t: Date.now() }; actPaint(); }
}
function fmtClock(ms) { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); }


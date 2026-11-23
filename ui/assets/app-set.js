// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- parent app only: "Cài đặt app" (language, theme, notifications, update, guide); the gear in head() and the "Thêm" sheet open it ----
let appUpd = { st: "idle" };   // idle | checking | ok | new | fail | perm | dl(pct) | inst | dlfail; pushed by the app through kgUpd()
window.kgUpd = o => { appUpd = o || { st: "idle" }; if ($("#updBox")) $("#updBox").innerHTML = updBoxHtml(); };
function updBoxHtml() {
  const u = appUpd, v = kgCall("appVersion") || "";
  const busy = u.st === "checking" || u.st === "dl";
  const msg = { checking: t("au.checking"), ok: t("au.ok"), fail: t("au.fail"), new: t("au.new", u.tag), perm: t("au.perm"), dl: t("au.dl", u.pct || 0), inst: t("au.inst"), dlfail: t("au.dlfail") }[u.st] || "";
  const inst = u.st === "new" || u.st === "perm" || u.st === "dlfail" || u.st === "inst";
  return `${msg ? `<p class="${u.st === "fail" || u.st === "dlfail" ? "err" : "mute"}" role="status">${busy ? '<i class="spin"></i> ' : ""}${msg}</p>` : ""}
    <div class="row">${inst ? `<button class="pri sp" id="updInst" data-tag="${esc(u.tag || "")}">${ic("download")} ${t("au.install", u.tag || "")}</button>` : ""}
    <button class="${inst ? "" : "pri "}sp" id="updChk" ${busy ? "disabled" : ""}>${ic("refresh-cw")} ${t("au.check", v)}</button></div>`;
}
// "Do alerts arrive?" card: only in the parent app build that has notifHealth
function notifHealthHtml() {
  let h = null;
  try { if (APP && window.KGApp && typeof KGApp.notifHealth === "function") h = JSON.parse(kgCall("notifHealth") || "null"); } catch {}
  if (!h) return "";
  const line = (ok, txt) => `<p class="${ok ? "mute" : "err"}">${ic(ok ? "check" : "triangle-alert")} ${txt}</p>`;
  const push = { off: t("nh.pushOff"), none: t("nh.pushNone"), wait: t("nh.pushWait"), ok: t("nh.pushOk", h.pushAt ? hhmm(h.pushAt) : ""), part: t("nh.pushPart", esc(h.pushBad || "?")), fail: h.pushBad ? t("nh.pushFailN", esc(h.pushBad)) : t("nh.pushFail") }[h.push] || "";
  return `<div class="card" id="nhBox"><h2>${ic("bell")} ${t("nh.h")}</h2>
    ${line(!!h.lastCheck, h.lastCheck ? t("nh.check", hhmm(h.lastCheck)) : t("nh.checkNever"))}
    ${push ? line(h.push !== "fail" && h.push !== "none" && h.push !== "part", push) : ""}
    ${line(!!h.battery, h.battery ? t("nh.batOk") : t("nh.batOn"))}
    ${line(h.enabled && h.perm, h.enabled && h.perm ? t("nh.permOk") : t("nh.permOff"))}
    <div class="row">${h.battery ? "" : `<button class="sp" id="nhBat">${ic("battery-full")} ${t("nh.batBtn")}</button>`}
    ${h.perm ? "" : `<button class="sp" id="nhPerm">${ic("bell")} ${t("nh.permBtn")}</button>`}
    <button class="sp" id="nhTest">${ic("send")} ${t("nh.test")}</button></div></div>`;
}
// one history entry while it is open, so Android Back returns to the list (popstate in overview.js) instead of leaving the app
let appSetOn = false;
function appSetClose() { if (!appSetOn) return; if (history.state && history.state.appSet) history.back(); else { appSetOn = false; appBoot(); } }   // popstate does the rest
// evening summary (parent app notification): on/off + time, kept by the app (KGApp.getDigest / setDigest), off by default
function digestCardHtml() {
  let d; try { d = JSON.parse(kgCall("getDigest") || ""); } catch { d = null; }
  if (!d) return "";
  return `<div class="card"><h2>${ic("moon")} ${t("dg.h")}</h2>
    <label class="row"><input type="checkbox" id="f-dg" ${d.on ? "checked" : ""}><span class="sp">${t("dg.p")}</span></label>
    <label class="row"><span class="sp">${t("dg.at")}</span><input type="time" id="f-dgat" value="${esc(d.at || "20:00")}"></label>
    <p class="mute">${t("dg.n")}</p></div>`;
}
// app lock (AppLock.kt): the phone's own fingerprint / face / PIN when the app opens or comes back after 5 min
function lockCardHtml() {
  let L = null; try { L = JSON.parse(kgCall("getLock") || "null"); } catch {}
  if (!L) return "";   // an older app without the lock
  return `<div class="card"><h2>${ic("lock")} ${t("lk.h")}</h2>
    <label class="row"><input type="checkbox" id="f-lock" ${L.on ? "checked" : ""}><span class="sp">${t("lk.p")}</span></label>
    ${L.on && !L.secure ? `<p class="mute">${ic("triangle-alert")} ${t("lk.noSecure")}</p>` : ""}</div>`;
}
function kgLock() { if (appSetOn) appSettings(); }
function appSettings() {
  reScreen = appSettings;
  if (!appSetOn) { appSetOn = true; try { history.pushState({ kg: navLvl() + 1, appSet: 1 }, ""); } catch {} }
  const notifOn = kgCall("notifOn") === "true", wantOn = kgCall("getNotify") !== "false";
  const kidNf = kgCall("getKidNotif");   // null = an older app without the mirror of the kid's notifications
  $("#app").innerHTML = head(t("au.h"), true) + `
  <div class="card"><h2>${ic("globe")} ${t("lang")}</h2>${langSel()}</div>
  <div class="card"><h2>${ic("sun-moon")} ${t("theme.h")}</h2><div class="seg" role="tablist">${THEMES.map(x => `<button type="button" role="tab" data-th="${x}" aria-selected="${x === theme}">${t("theme." + x)}</button>`).join("")}</div></div>
  <div class="card"><h2>${ic("bell")} ${t("notif.h")}</h2>
    <label class="row"><input type="checkbox" id="f-notify" ${wantOn ? "checked" : ""}><span class="sp">${t("notif.p")}</span></label>
    <p class="mute">${notifOn ? t("au.nOn") : wantOn ? ic("triangle-alert") + " " + t("notif.blocked") : t("au.nOff")}</p>
    ${kidNf !== null && wantOn ? `<label class="row"><input type="checkbox" id="f-kidnf" ${kidNf === "true" ? "checked" : ""}><span class="sp">${t("notif.kid")}<br><span class="mute">${t("notif.kidN")}</span></span></label>` : ""}
    <button id="nSet">${ic("settings")} ${t("au.nOpen")}</button></div>
  ${lockCardHtml()}
  ${xfer.card()}
  ${digestCardHtml()}
  ${notifHealthHtml()}
  <div class="card"><h2>${ic("download")} ${t("au.upd")}</h2><div id="updBox">${updBoxHtml()}</div>${uiPackHtml()}</div>
  <div class="card row"><b class="sp">${ic("book-open")} ${t("au.guide")}</b><a class="btn" href="${SITE}/${lang === "en" ? "en/" : ""}guide.html" target="_blank" rel="noopener">${ic("external-link")} ${t("open")}</a></div>
  <button id="abk">${ic("list")} ${t("back")}</button>`;
  $("#abk").onclick = $("#appSetBack").onclick = appSetClose;
  $("#app").querySelectorAll("[data-th]").forEach(b => b.onclick = () => {
    theme = b.dataset.th; try { localStorage.setItem("kg.theme", theme); } catch {}
    applyTheme(); appSettings();
  });
  $("#f-notify").onchange = e => { kgCall("setNotify", e.target.checked ? "true" : "false"); if (e.target.checked) setTimeout(() => { if (kgCall("canNotify") === "false") toast("warn|" + t("notif.blockedShort")); appSettings(); }, 1500); else appSettings(); };
  $("#nSet").onclick = () => kgCall("openNotifSettings");
  xfer.wire();
  if ($("#f-kidnf")) $("#f-kidnf").onchange = e => kgCall("setKidNotif", e.target.checked ? "true" : "false");
  if ($("#f-lock")) $("#f-lock").onchange = e => kgCall("setLock", e.target.checked ? "true" : "false");   // off: the phone asks first, then kgLock()
  const dgSave = () => kgCall("setDigest", $("#f-dg").checked ? "true" : "false", $("#f-dgat").value || "20:00");
  if ($("#f-dg")) { $("#f-dg").onchange = dgSave; $("#f-dgat").onchange = dgSave; }
  // one delegated handler: updBox is redrawn by kgUpd
  $("#app").onclick = e => {
    const n = e.target.closest && e.target.closest("#nhBat, #nhPerm, #nhTest");
    if (n) {
      if (n.id === "nhBat") kgCall("openBatterySettings");
      else if (n.id === "nhPerm") { const r = kgCall("askNotif"); if (r === "ok") toast("ok|" + t("nh.permOk")); setTimeout(appSettings, 1500); }
      else toast(kgCall("sendTest") === "ok" ? "ok|" + t("nh.sent") : "warn|" + t("notif.blockedShort"));
      return;
    }
    const b = e.target.closest && e.target.closest("#updChk, #updInst"); if (!b || b.disabled) return;
    if (b.id === "updChk") kgCall("checkUpdate"); else kgCall("installUpdate", b.dataset.tag);
  };
}
// from the kid screens: leave the kid, then show the settings on the list's place
function goAppSettings() { if (cur || ov) goList(); appSettings(); }

// Long help text (owner 2026-11-11: "chữ nhiều quá, giấu vào nút info"): every plain p.mute in the page longer than
// TIP_MIN chars shows only its first sentence + an ⓘ button; a tap opens the whole text in a sheet (bottom sheet on
// phones, dialog on desktop) titled after the nearest heading / checkbox. One place for every tab: a MutationObserver
// on #app. Left alone: lines with an id (code updates them), controls or links inside, and text inside sheets.
const TIP_MIN = 90, tipFull = new WeakMap();
function tipShort(s) {
  const m = s.match(/^(.{20,110}?[.!?])(\s|$)/);
  if (m && m[1].length < s.length - 10) return m[1];
  const c = s.slice(0, 90); return c.slice(0, Math.max(c.lastIndexOf(" "), 60)) + "…";
}
function tipTitle(p) {
  const lb = p.tagName === "SPAN" && p.parentElement && p.parentElement.querySelector("b");
  if (lb && lb.textContent.trim()) return lb.textContent.trim().slice(0, 90);
  const prev = p.previousElementSibling;   // the line right under a checkbox / sub-heading explains that one
  if (prev && /^(H3|H4|LABEL)$/.test(prev.tagName) && prev.textContent.trim()) return prev.textContent.trim().slice(0, 90);
  for (let e = p; e && e.id !== "app"; e = e.parentElement) {
    if (e.classList && e.classList.contains("cl")) { const b = e.querySelector(".clr b"); if (b) return b.textContent.trim(); }
    if (e.classList && e.classList.contains("card")) { const h = e.querySelector("h2, h3"); if (h && h.textContent.trim()) return h.textContent.trim().slice(0, 90); }
  }
  return t("tip.h");
}
function tipScan(root) {
  // also the grey line under a toggle (label > span > br + span.mute, owner 2026-11-17: "desc dày đặc"): first sentence + ⓘ
  root.querySelectorAll("p.mute:not([data-tip]):not([id]):not(.keep), label br + span.mute:not([data-tip]):not([id])").forEach(p => {
    p.dataset.tip = "";
    if (p.closest("#askPop, .dp") || p.querySelector("button, input, select, textarea, a, details")) return;
    const txt = p.textContent.replace(/\s+/g, " ").trim();
    if (txt.length <= TIP_MIN) return;
    tipFull.set(p, p.innerHTML);
    // keep the line's own icon, unless it is a plain "info" / "tip" one: the ⓘ at the end already says that (owner 2026-11-17: two ⓘ looked bad)
    const f = p.firstElementChild, own = f && f.tagName.toLowerCase() === "svg" && p.firstChild === f ? f : null;
    const lead = own && !/#i-(info|lightbulb)"/.test(own.outerHTML) ? own.outerHTML + " " : "";
    const short = tipShort(txt), cut = short.lastIndexOf(" ");   // the last word rides with the button: it never sits alone on a line
    p.innerHTML = `${lead}${esc(short.slice(0, cut + 1))}<span class="tipe">${esc(short.slice(cut + 1))} <button type="button" class="tipb" aria-label="${esc(t("tip.more"))}">${ic("info")}</button></span>`;
    p.classList.add("tipc");
  });
}
// range fill: the track left of the thumb in the accent colour (style.css --p); WebKit has no ::range-progress
const rangeFill = r => { const lo = +r.min || 0, hi = r.max === "" ? 100 : +r.max; r.style.setProperty("--p", (hi > lo ? (r.value - lo) / (hi - lo) * 100 : 0) + "%"); };
document.addEventListener("input", e => { if (e.target.type === "range") rangeFill(e.target); });
let tipQ = false;
const tipObs = new MutationObserver(() => { if (tipQ) return; tipQ = true; requestAnimationFrame(() => { tipQ = false; const a = $("#app"); if (a) tipScan(a); document.querySelectorAll("input[type=range]").forEach(rangeFill); }); });
tipObs.observe(document.body, { childList: true, subtree: true });   // body: sheets (#askPop) have sliders too
if ($("#app")) tipScan($("#app"));
document.addEventListener("click", e => {
  const p = e.target.closest && e.target.closest(".tipc"); if (!p || !tipFull.has(p)) return;
  if (p.tagName !== "P" && !e.target.closest(".tipb")) return;   // in a toggle's label: the text still flips the switch, only ⓘ opens the sheet
  e.preventDefault();
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("info", esc(tipTitle(p)))}<div class="tipx">${tipFull.get(p)}</div></div>`);
});

// live-bar / save-chip buttons (their HTML is rebuilt on every poll: delegated once)
document.addEventListener("click", e => {
  if (e.target.closest("#bankEndBtn")) cmd({ type: "bankEnd" }, t("bk.endSent")).then(r => {
    if (r && r.result === "in use") toast(t("bk.inUse"));
    [2000, 8000].forEach(ms => setTimeout(refresh, ms));
  });
  const rfx = e.target.closest("[data-rfx]");   // a false hit: the phone drops it and never warns for this app again
  if (rfx && cur && st && Array.isArray(st.refused)) {
    const pkg = rfx.dataset.rfx, k = cur; rfx.disabled = true;
    cmd({ type: "refusedDrop", pkg }).then(r => { if (k !== cur) return; if (r) { st.refused = st.refused.filter(x => x.pkg !== pkg); lastSig = ""; render(); } else rfx.disabled = false; });
  }
  const rf = e.target.closest("[data-rf]");
  if (rf && cur && st && Array.isArray(st.refused)) (async () => {
    const f = st.refused.find(x => x.pkg === rf.dataset.rf), k = cur; if (!f) return;
    rf.disabled = true;
    try {
      const r = edit || await kidRules(k);
      if (!Array.isArray(r.accPause)) throw new Error(t("noData"));
      const has = x => Array.isArray(r[x]), col = { acc: "accPause", dev: has("devPause") ? "devPause" : "accPause", vpn: has("vpnPause") ? "vpnPause" : "" };
      for (const x of f.kinds || []) { const c = col[x]; if (c && !r[c].includes(f.pkg) && r[c].length < 100) r[c] = r[c].concat(f.pkg); }
      await putRules(k, r);
      if (k !== cur) return;   // kid switched while saving: the new kid's list is not ours to touch
      if (st && st.refused) st.refused = st.refused.filter(x => x.pkg !== f.pkg);
      toast("ok|" + t("rf.added", f.label || f.pkg)); lastSig = ""; render();
      bankShow(f.pkg);   // show where the list lives, so the parent can switch it off later
    } catch (er) { rf.disabled = false; toast("err|" + t("errX", er.message)); }
  })();
  if (e.target.closest("#saveResend") && cur && edit) { pendSince = Date.now(); putRules(cur, edit).catch(er => { toast("err|" + t("errX", er.message)); saveFail(true); }); }
});

// ---- UI pack: the page itself updates without an app update (android/shared/UiPack.kt, docs/flows/remote.md 2.9b) ----
// The phone / parent app that serves this page keeps a signed copy of ui/ and answers /ui/pack.json (which page it serves, since when).
// On the relay (always current) there is no such file: uiPk stays null, and the row only offers "update the phone's page".
let uiPk = null, uiLoaded = "";
async function uiPkInfo() {
  try { const r = await fetch("/ui/pack.json", { cache: "no-store" }); if (!r.ok) return null; const j = await r.json(); return j && j.src ? j : null; } catch { return null; }
}
function uiPackHtml() {
  const j = uiPk;
  const v = !j ? t("ui.relay") : j.src === "pack" ? t("ui.pack", esc(j.ver), esc(String(j.seq))) : t("ui.bundled", esc(j.app || ""));
  return `<div class="row" id="uiPk" style="margin-top:8px"><span class="sp mute" style="overflow-wrap:anywhere">${ic("layout-grid")} ${v}</span>${j || window.KGApp || cur ? `<button type="button" class="sm" id="uiNow" aria-label="${esc(t("ui.now"))}">${ic("refresh-cw")} ${t("ui.nowS")}</button>` : ""}</div>`;
}
function uiNewBar() {
  if ($("#uiNew")) return;
  const d = document.createElement("div"); d.id = "uiNew"; d.setAttribute("role", "status");
  d.className = "card row"; d.style.cssText = "position:fixed;left:12px;right:12px;top:8px;z-index:70;margin:0";
  d.innerHTML = `<b class="sp">${ic("sparkles")} ${t("ui.new")}</b><button type="button" class="pri" id="uiReload">${t("ui.reload")}</button>`;
  document.body.appendChild(d);
  $("#uiReload").onclick = () => location.reload();
}
async function uiNow() {
  const b = $("#uiNow"); if (b) b.disabled = true;
  toast(t("ui.checking"));
  if (window.KGApp && KGApp.uiUpdate) kgCall("uiUpdate");   // parent app: its own copy
  else if (cur) { try { await cmd({ type: "uiUpdate" }, "", false); } catch {} }   // page opened from the phone (LAN): the phone's copy
  let j = null;
  for (let i = 0; i < 14; i++) {
    await new Promise(r => setTimeout(r, 1500));
    j = await uiPkInfo();
    if (j && j.ver !== uiLoaded) { toast("ok|" + t("ui.reloading")); setTimeout(() => location.reload(), 700); return; }
    if ((j && !j.checking && i > 0) || (!j && i > 2)) break;
  }
  if (b) b.disabled = false;
  const r = j && j.result;
  toast(r === "net" ? "warn|" + t("ui.net") : r === "sig" || r === "hash" || r === "zip" || r === "files" || r === "name" || r === "manifest" ? "err|" + t("ui.badpack") : r === "min" ? "warn|" + t("ui.min") : "ok|" + t("ui.same"));
}
async function uiPackBoot() {
  uiPk = await uiPkInfo(); uiLoaded = uiPk ? uiPk.ver : "";
  if (uiPk && uiPk.ver) fetch("/ui/ok?v=" + encodeURIComponent(uiPk.ver)).catch(() => {});   // "this page booted": a pack that never gets here is rolled back by the phone
  const row = $("#uiPk"); if (row) row.outerHTML = uiPackHtml();
  document.addEventListener("click", e => { const b = e.target.closest && e.target.closest("#uiNow"); if (b && !b.disabled) uiNow(); });
  // the phone checks for a newer pack when this page is opened (a few seconds): offer it, never reload by itself (an edit may be open)
  const w = async () => { const j = await uiPkInfo(); if (j && j.ver !== uiLoaded) uiNewBar(); };
  if (uiPk) { setTimeout(w, 8000); setTimeout(w, 40000); document.addEventListener("visibilitychange", () => { if (!document.hidden) setTimeout(w, 4000); }); }
}

// Jump to Rules > bank-app list and flash this app's row (after "allow this app" in the refused band)
function bankShow(pkg) {
  qsGo({ tab: "rules", sec: "r-bank" });
  let n = 0;
  const find = () => {
    const c = [...document.querySelectorAll("#bk-list [data-bank]")].find(x => x.dataset.bank === pkg), row = c && c.closest(".bkr");
    if (!row) { if (++n < 30) setTimeout(find, 100); return; }
    row.scrollIntoView({ block: "center", behavior: "smooth" });
    row.classList.add("qsHit"); setTimeout(() => row.classList.remove("qsHit"), 2400);
  };
  setTimeout(find, 300);
}

// ---- Move to a new phone (owner 2026-11-23). Two ways, both in the parent-app settings:
// 1. One QR = a fresh one-use relay invite (POST /c/<id>/invite, 10 min) per kid that has a relay link:
//    ${SITE}/app/#mv=<JSON [[relay origin, id, inv, name], ...]>; the new phone asks a name once and joins each (own token).
// 2. A backup file with every kid's access (tokens, LAN kids too), AES-GCM with a key from the parent's own password
//    (PBKDF2-SHA256): {kidguard: "backup", v: 1, it, salt, iv, data}. Nothing leaves the phone unencrypted.
const xfer = (() => {
  let mv = null;   // {link, exp, ins: [names], outs: [names]}
  const relayKids = () => kids.filter(k => k.via === "relay" || (k.relay && k.relay.url && k.relay.id));
  const ub = u => { let s = ""; for (const x of u) s += String.fromCharCode(x); return btoa(s); };
  const bu = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
  const keyOf = async (pw, salt, it) => crypto.subtle.deriveKey({ name: "PBKDF2", salt, iterations: it, hash: "SHA-256" },
    await crypto.subtle.importKey("raw", new TextEncoder().encode(pw), "PBKDF2", false, ["deriveKey"]), { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  const kidOut = x => ({ name: x.name, target: x.target, via: x.via, id: x.id, token: x.token, lv: x.lv, relay: x.relay });
  const kidOk = x => x && (x.via === "relay" || x.via === "lan") && typeof x.id === "string" && typeof x.token === "string" && typeof x.target === "string";
  const home = () => { reScreen = null; if (APP || onLan()) appBoot(); else listScreen(kids.filter(k => k.via === "relay")); };
  const pwField = (id, ph) => `<input id="${id}" type="password" maxlength="64" autocomplete="new-password" placeholder="${esc(ph)}" aria-label="${esc(ph)}" style="width:100%;margin-bottom:10px">`;

  function card() {
    const n = relayKids().length;
    return `<div class="card"><h2>${ic("smartphone")} ${t("mv.h")}</h2><p class="mute">${t("mv.p")}</p>
      ${n ? `<button id="mvQr">${ic("scan-line")} ${t("mv.qr")}</button>` : `<p class="mute">${t("mv.none")}</p>`}<div id="mvBox">${mvHtml()}</div>
      <h3 style="margin:16px 0 4px">${ic("lock")} ${t("bk.h")}</h3><p class="mute">${t("bk.p")}</p>
      <div class="row" style="flex-wrap:wrap;gap:8px"><button id="bkSave">${ic("download")} ${t("bk.save")}</button><button id="bkLoad">${ic("upload")} ${t("bk.load")}</button></div>
      <input type="file" id="bkFile" hidden></div>`;
  }
  function mvHtml() {
    if (!mv || mv.exp <= Date.now()) return "";
    return `<div class="totpqr"><div class="qrw" role="img" aria-label="${esc(t("mv.h"))}">${qrSvg(mv.link)}</div></div>
      <p class="mute">${t("mv.in", mv.ins.map(esc).join(", "))} · <span id="mvLeft"></span></p>
      ${mv.outs.length ? `<p class="mute">${ic("triangle-alert")} ${t("mv.out", mv.outs.map(esc).join(", "))}</p>` : ""}`;
  }
  function tick() {
    const e = $("#mvLeft"); if (!e || !mv) return;
    const s = Math.max(0, Math.round((mv.exp - Date.now()) / 1000));
    if (!s) { mv = null; $("#mvBox").innerHTML = ""; return; }
    e.textContent = t("mv.left", Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0")); setTimeout(tick, 1000);
  }
  async function makeQr(b) {
    b.disabled = true;
    const ins = [], names = [], outs = kids.filter(k => !relayKids().includes(k)).map(k => k.name || t("kid"));
    for (const k of relayKids()) {
      try {
        const j = await rcall(k, "/invite", "POST", {});
        if (!j || !/^[0-9a-f]{32}$/.test(j.inv || "")) throw new Error("x");
        ins.push([rpOrigin(k), rpCred(k).id, j.inv, (k.name || "").slice(0, 40)]); names.push(k.name || t("kid"));
      } catch { outs.push(k.name || t("kid")); }
    }
    b.disabled = false;
    if (!ins.length) return toast("err|" + t("mv.fail"));
    mv = { link: `${SITE}/app/#mv=${encodeURIComponent(JSON.stringify(ins))}`, exp: Date.now() + 600000, ins: names, outs };
    if ($("#mvBox")) { $("#mvBox").innerHTML = mvHtml(); tick(); }
  }
  // the new phone: #mv=... (scanned in the parent app, or opened as a link)
  function of(h) {
    try {
      const a = JSON.parse(h.get("mv") || "");
      const l = Array.isArray(a) ? a.slice(0, 20).map(x => Array.isArray(x) && inviteOf(new URLSearchParams({ ri: x[0], rc: x[1], inv: x[2], n: x[3] || "" }))).filter(Boolean) : [];
      return l.length ? l : null;
    } catch { return null; }
  }
  function screen(list) {
    reScreen = () => screen(list);
    let nm = ""; try { nm = localStorage.getItem("kg.pname") || ""; } catch {}
    $("#app").innerHTML = head(t("mv.h")) + `<div class="card"><h2>${ic("users")} ${t("mv.recv", list.length)}</h2>
      <p>${list.map(o => esc(o.n || t("kid"))).join(", ")}</p><p class="mute">${t("mv.recvP")}</p>
      <div class="pairf"><input id="mvN" maxlength="30" autocomplete="off" placeholder="${esc(t("jn.name"))}" aria-label="${esc(t("jn.name"))}" value="${esc(nm || KG_NAME)}"><button class="pri" id="mvGo">${ic("check")} ${t("mv.go", list.length)}</button></div>
      <p class="err" id="mvE" role="alert"></p></div>`;
    const go = $("#mvGo"), say = m => { $("#mvE").textContent = m; };
    $("#mvN").onkeydown = e => { if (e.key === "Enter") go.click(); };
    go.onclick = async () => {
      const name = $("#mvN").value.trim(); if (!name) return say(t("jn.needName"));
      say(""); go.disabled = true;
      let added = 0; const dead = [], bad = [];
      for (const o of list) {
        try {
          const r = await fetch((APP ? "/k/" + b64u(o.ri) : o.ri) + "/c/" + o.rc + "/join", { method: "POST", headers: { "Content-Type": "application/json", "X-KG-Name": KG_NAME }, body: JSON.stringify({ name, kind: APP ? "app" : "web", inv: o.inv }) });
          const j = await r.json().catch(() => ({}));
          if (!r.ok || !j.token) { (j.error === "invite used or expired" ? dead : bad).push(o.n || t("kid")); continue; }
          const k = { id: o.rc, token: j.token, name: o.n || t("kid"), via: "relay", target: o.ri, own: 1 };
          kids = kids.filter(x => !(x.id === k.id && x.via === "relay")).concat(k); added++;
        } catch { bad.push(o.n || t("kid")); }
      }
      if (added) { save(kids); try { localStorage.setItem("kg.pname", name); } catch {} }
      if (!dead.length && !bad.length) { toast("ok|" + t("mv.done", added)); return home(); }
      go.disabled = false;
      say([dead.length ? t("mv.dead", dead.join(", ")) : "", bad.length ? t("mv.bad", bad.join(", ")) : ""].filter(Boolean).join(" "));
      if (added) toast("ok|" + t("mv.done", added));
    };
  }
  async function bkSave() {
    if (!(window.crypto && crypto.subtle)) return toast("err|" + t("bk.noCrypto"));
    let pw = "";
    const ok = await ask(t("bk.askSave"), t("bk.save"), false, "", pwField("bkP1", t("bk.pw1")) + pwField("bkP2", t("bk.pw2")),
      () => { const a = $("#bkP1").value, b2 = $("#bkP2").value; if (a.length >= 8 && a === b2) { pw = a; return true; } return false; });
    if (!ok) return;
    const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12)), it = 310000;
    let pn = ""; try { pn = localStorage.getItem("kg.pname") || ""; } catch {}
    const plain = new TextEncoder().encode(JSON.stringify({ v: 1, at: Date.now(), pname: pn, kids: kids.filter(kidOk).map(kidOut) }));
    const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await keyOf(pw, salt, it), plain));
    const text = JSON.stringify({ kidguard: "backup", v: 1, it, salt: ub(salt), iv: ub(iv), data: ub(data) });
    const name = "kidguard-backup-" + new Date().toISOString().slice(0, 10) + ".json";
    if (APP && window.KGApp && KGApp.dlBegin) {
      const id = KGApp.dlBegin(name, "application/json");
      if (!id || !KGApp.dlChunk(id, ub(new TextEncoder().encode(text)))) { if (id) KGApp.dlEnd(id, "false"); return toast("err|" + t("bk.fail")); }
      KGApp.dlEnd(id, "true");
    } else fxSave(new Blob([text], { type: "application/json" }), name);
    toast("ok|" + t("bk.saved", name));
  }
  async function bkLoad(file) {
    if (!(window.crypto && crypto.subtle)) return toast("err|" + t("bk.noCrypto"));
    let o; try { o = JSON.parse(await file.text()); } catch {}
    if (!o || o.kidguard !== "backup" || !o.data || !o.salt || !o.iv) return toast("err|" + t("bk.bad"));
    let pw = "";
    const ok = await ask(t("bk.askLoad"), t("bk.load"), false, "", pwField("bkP", t("bk.pw")), () => { pw = $("#bkP").value; return !!pw; });
    if (!ok) return;
    let p;
    try { p = JSON.parse(new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-GCM", iv: bu(o.iv) }, await keyOf(pw, bu(o.salt), +o.it || 310000), bu(o.data)))); }
    catch { return toast("err|" + t("bk.wrong")); }
    const list = (p && Array.isArray(p.kids) ? p.kids : []).filter(kidOk).map(kidOut);
    if (!list.length) return toast("err|" + t("bk.bad"));
    for (const k of list) kids = kids.filter(x => !(x.id === k.id && x.via === k.via)).concat(k);
    save(kids);
    if (p.pname) try { if (!localStorage.getItem("kg.pname")) localStorage.setItem("kg.pname", String(p.pname).slice(0, 30)); } catch {}
    toast("ok|" + t("bk.done", list.length));
    appSetOn = false; home();
  }
  function wire() {
    if ($("#mvQr")) $("#mvQr").onclick = e => makeQr(e.currentTarget);
    if (mv) tick();
    if ($("#bkSave")) $("#bkSave").onclick = bkSave;
    if ($("#bkLoad")) $("#bkLoad").onclick = () => $("#bkFile").click();
    if ($("#bkFile")) $("#bkFile").onchange = e => { const f = e.target.files && e.target.files[0]; e.target.value = ""; if (f) bkLoad(f); };
  }
  return { card, wire, of, screen };
})();

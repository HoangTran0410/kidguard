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
  const push = { off: t("nh.pushOff"), none: t("nh.pushNone"), wait: t("nh.pushWait"), ok: t("nh.pushOk", h.pushAt ? hhmm(h.pushAt) : ""), fail: t("nh.pushFail") }[h.push] || "";
  return `<div class="card" id="nhBox"><h2>${ic("bell")} ${t("nh.h")}</h2>
    ${line(!!h.lastCheck, h.lastCheck ? t("nh.check", hhmm(h.lastCheck)) : t("nh.checkNever"))}
    ${push ? line(h.push !== "fail" && h.push !== "none", push) : ""}
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
  $("#app").innerHTML = head(t("au.h"), true) + `
  <div class="card"><h2>${ic("globe")} ${t("lang")}</h2>${langSel()}</div>
  <div class="card"><h2>${ic("sun-moon")} ${t("theme.h")}</h2><div class="seg" role="tablist">${THEMES.map(x => `<button type="button" role="tab" data-th="${x}" aria-selected="${x === theme}">${t("theme." + x)}</button>`).join("")}</div></div>
  <div class="card"><h2>${ic("bell")} ${t("notif.h")}</h2>
    <label class="row"><input type="checkbox" id="f-notify" ${wantOn ? "checked" : ""}><span class="sp">${t("notif.p")}</span></label>
    <p class="mute">${notifOn ? t("au.nOn") : wantOn ? ic("triangle-alert") + " " + t("notif.blocked") : t("au.nOff")}</p>
    <button id="nSet">${ic("settings")} ${t("au.nOpen")}</button></div>
  ${lockCardHtml()}
  ${digestCardHtml()}
  ${notifHealthHtml()}
  <div class="card"><h2>${ic("download")} ${t("au.upd")}</h2><div id="updBox">${updBoxHtml()}</div></div>
  <div class="card row"><b class="sp">${ic("book-open")} ${t("au.guide")}</b><a class="btn" href="https://kid-guard.pages.dev/${lang === "en" ? "en/" : ""}guide.html" target="_blank" rel="noopener">${ic("external-link")} ${t("open")}</a></div>
  <button id="abk">${ic("list")} ${t("back")}</button>`;
  $("#abk").onclick = $("#appSetBack").onclick = appSetClose;
  $("#app").querySelectorAll("[data-th]").forEach(b => b.onclick = () => {
    theme = b.dataset.th; try { localStorage.setItem("kg.theme", theme); } catch {}
    applyTheme(); appSettings();
  });
  $("#f-notify").onchange = e => { kgCall("setNotify", e.target.checked ? "true" : "false"); if (e.target.checked) setTimeout(() => { if (kgCall("canNotify") === "false") toast("warn|" + t("notif.blockedShort")); appSettings(); }, 1500); else appSettings(); };
  $("#nSet").onclick = () => kgCall("openNotifSettings");
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
const TIP_MIN = 120, tipFull = new WeakMap();
function tipShort(s) {
  const m = s.match(/^(.{20,110}?[.!?])(\s|$)/);
  if (m && m[1].length < s.length - 10) return m[1];
  const c = s.slice(0, 90); return c.slice(0, Math.max(c.lastIndexOf(" "), 60)) + "…";
}
function tipTitle(p) {
  const prev = p.previousElementSibling;   // the line right under a checkbox / sub-heading explains that one
  if (prev && /^(H3|H4|LABEL)$/.test(prev.tagName) && prev.textContent.trim()) return prev.textContent.trim().slice(0, 90);
  for (let e = p; e && e.id !== "app"; e = e.parentElement) {
    if (e.classList && e.classList.contains("cl")) { const b = e.querySelector(".clr b"); if (b) return b.textContent.trim(); }
    if (e.classList && e.classList.contains("card")) { const h = e.querySelector("h2, h3"); if (h && h.textContent.trim()) return h.textContent.trim().slice(0, 90); }
  }
  return t("tip.h");
}
function tipScan(root) {
  root.querySelectorAll("p.mute:not([data-tip]):not([id]):not(.keep)").forEach(p => {
    p.dataset.tip = "";
    if (p.closest("#askPop, .dp") || p.querySelector("button, input, select, textarea, a, details")) return;
    const txt = p.textContent.replace(/\s+/g, " ").trim();
    if (txt.length <= TIP_MIN) return;
    tipFull.set(p, p.innerHTML);
    const f = p.firstElementChild, lead = f && f.tagName.toLowerCase() === "svg" && p.firstChild === f ? f.outerHTML + " " : "";   // keep the line's own icon
    p.innerHTML = `${lead}${esc(tipShort(txt))} <button type="button" class="tipb" aria-label="${esc(t("tip.more"))}">${ic("info")}</button>`;
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
  const p = e.target.closest && e.target.closest("p.tipc"); if (!p || !tipFull.has(p)) return;
  e.preventDefault();
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("info", esc(tipTitle(p)))}<div class="tipx">${tipFull.get(p)}</div></div>`);
});

// live-bar / save-chip buttons (their HTML is rebuilt on every poll: delegated once)
document.addEventListener("click", e => {
  if (e.target.closest("#bankEndBtn")) cmd({ type: "bankEnd" }, t("bk.endSent")).then(r => {
    if (r && r.result === "in use") toast(t("bk.inUse"));
    [2000, 8000].forEach(ms => setTimeout(refresh, ms));
  });
  if (e.target.closest("#saveResend") && cur && edit) { pendSince = Date.now(); putRules(cur, edit).catch(er => { toast("err|" + t("errX", er.message)); saveFail(true); }); }
});

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
  <div class="card"><h2>${ic("download")} ${t("au.upd")}</h2><div id="updBox">${updBoxHtml()}</div>${uiPackHtml()}</div>
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
    const pkg = rfx.dataset.rfx; rfx.disabled = true;
    cmd({ type: "refusedDrop", pkg }).then(r => { if (r) { st.refused = st.refused.filter(x => x.pkg !== pkg); lastSig = ""; render(); } else rfx.disabled = false; });
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
  return `<div class="row" id="uiPk" style="margin-top:8px"><span class="sp mute" style="overflow-wrap:anywhere">${ic("layout-grid")} ${v}</span>${j || window.KGApp || cur ? `<button type="button" id="uiNow">${ic("refresh-cw")} ${t("ui.now")}</button>` : ""}</div>`;
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

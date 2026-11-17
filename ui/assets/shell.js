// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- shell: one NAV list feeds the desktop sidebar and the phone bottom bar ("Thêm" holds the rest) ----
const NAV = [["home", "house"], ["apps", "puzzle"], ["rules", "ruler"], ["where", "map-pin"], ["screen", "smartphone"], ["dev", "cpu"], ["files", "folder-open"], ["seen", "eye"], ["log", "notebook-pen"], ["rep", "chart-column"], ["hist", "history"], ["set", "settings"], ["sup", "message-square"]];
const BNAV = [["home", "house"], ["apps", "puzzle"], ["rules", "ruler"], ["where", "map-pin"], ["more", "ellipsis"]];
const MORE_TABS = new Set(["more", "screen", "dev", "files", "seen", "log", "rep", "hist", "set", "sup"]);
const navOk = id => !(viewDay && (id === "screen" || id === "dev" || id === "files" || id === "seen")) && (id !== "hist" || APP);
// the kids this page can switch between (same list the old "Danh sách" screen showed)
const kidList = () => APP ? kids.filter(k => k.target) : kids.filter(k => k.via === (onLan() ? "lan" : "relay"));
function kidAv(k) {
  const L = kidList(), i = Math.max(0, L.indexOf(k)), c = (((k && k.name) || "?").trim()[0] || "?").toUpperCase();
  return `<span class="av" style="background:${KC[i % KC.length]}" aria-hidden="true">${esc(c)}</span>`;
}
// theme: auto (follows the system) / light / dark, per browser
const THEMES = ["auto", "light", "dark"];
let theme = (() => { try { return THEMES.includes(localStorage.getItem("kg.theme")) ? localStorage.getItem("kg.theme") : "auto"; } catch { return "auto"; } })();
function applyTheme() { if (theme === "auto") document.documentElement.removeAttribute("data-theme"); else document.documentElement.setAttribute("data-theme", theme); }
applyTheme();
const themeBtn = () => `<button type="button" class="rb" data-theme-btn aria-label="${esc(t("theme.h") + ": " + t("theme." + theme))}" title="${esc(t("theme.h") + ": " + t("theme." + theme))}">${ic(theme === "auto" ? "sun-moon" : theme === "dark" ? "moon" : "sun")}</button>`;
document.addEventListener("click", e => {
  const fx = e.target.closest("#fixPermsBtn, [data-fixperms]");
  if (fx && !fx.disabled) { fx.disabled = true; cmd({ type: "fixPerms" }, t("fp.sent"), false).then(r => {
    if (r && r.result === "none") toast(t("fp.none"));
    else if (r && r.result === "retry") toast(t("fp.retry"));
    [2000, 6000, 15000].forEach(ms => setTimeout(refresh, ms));
  }).finally(() => { fx.disabled = false; }); }
  const b = e.target.closest && e.target.closest("[data-theme-btn]"); if (!b) return;
  theme = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]; try { localStorage.setItem("kg.theme", theme); } catch {}
  applyTheme(); document.querySelectorAll("[data-theme-btn]").forEach(x => { x.outerHTML = themeBtn(); });
  toast("ok|" + t("theme.h") + ": " + t("theme." + theme));
});
// browser screens without a kid open (head()): one gear for language + theme, so the title keeps its line
function viewSet() {
  const body = () => `<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("settings", t("vs.h"))}<p class="mute" style="margin:0 0 6px">${t("lang")}</p>${langSel("langVs")}
    <p class="mute" style="margin:14px 0 6px">${t("theme.h")}</p><div class="seg" role="tablist">${THEMES.map(x => `<button type="button" role="tab" data-th="${x}" aria-selected="${x === theme}">${t("theme." + x)}</button>`).join("")}</div></div>`;
  const wire = pop => pop.querySelectorAll("[data-th]").forEach(b => b.onclick = () => {
    theme = b.dataset.th; try { localStorage.setItem("kg.theme", theme); } catch {}
    applyTheme(); pop.querySelectorAll("[data-th]").forEach(x => x.setAttribute("aria-selected", x.dataset.th === theme));
  });
  sheet(body(), wire);
}
document.addEventListener("click", e => { if (e.target.closest && e.target.closest("[data-view-set]")) viewSet(); });
const bonusGiven = new Set();   // "kid:requestId" whose extra-time bonus is already saved
const reqN = () => st && !viewDay ? (st.requests || []).filter(q => !answered.has(q.id)).length : 0;
// LAN: the last poll failing counts at once (else the header stays green under a red "lost" banner)
const onlineNow = () => st && !(cur && cur.via === "lan" && lastErr) && Date.now() - lastSeen() < (cur && cur.via === "lan" ? 30000 : 120000);
// --- "waiting for the phone" helpers (audit 2026-11-13): a wait always says WHY (offline since / adb link) and offers a retry.
// pendHtml(id, html(), act?): a span the 5 s ticker refreshes by itself (the text depends on time, a state poll may not redraw it);
// a button with data-pact="<id>" runs act().
const PEND = {};
function pendHtml(id, html, act) { PEND[id] = { html, act }; return `<span data-pend="${id}">${html()}</span>`; }
document.addEventListener("click", e => { const b = e.target.closest && e.target.closest("[data-pact]"); const p = b && PEND[b.dataset.pact]; if (p && p.act) p.act(b); });
// "" = the phone answers; else why it does not (offline since ...)
function offWhy() {
  if (!cur || !st || viewDay) return "";
  const seen = lastSeen();
  return cur.via === "lan" ? (lastErr ? t("pw.off", ago(seen)) : "") : seen && Date.now() - seen > 180000 ? t("pw.off", ago(seen)) : "";
}
// why a change the phone has to apply is not applied yet: offline first, else the adb link reason (st.adbWhy, basic mode)
const waitWhy = adb => offWhy() || (adb && st && st.adbWhy && hasT("clean.why." + st.adbWhy) ? t("clean.why." + st.adbWhy) : "");
// Reboot: no boot time in the state, so "restarted" = the phone went quiet (> GAP) and then synced again
let rbPend = null;
function rebootStart() { rbPend = { kid: cur, at: Date.now(), gap: 0, done: 0 }; updateLive(); }
function rebootLine() {
  const p = rbPend; if (!p) return null;
  if (p.kid !== cur || !st || viewDay) return null;
  const now = Date.now(), seen = lastSeen(), GAP = cur.via === "lan" ? 20000 : 40000;
  if (!p.done) {
    if (!p.gap && now - seen > GAP && seen >= p.at - 60000) p.gap = seen;
    else if (p.gap && seen > p.gap && now - seen < GAP) p.done = now;
  }
  if (p.done) { if (now - p.done > 20000) { rbPend = null; return null; } return ["info", "check", t("pw.rbDone")]; }
  if (now - p.at > 180000) { if (now - p.at > 240000) { rbPend = null; return null; } return ["warn", "triangle-alert", t("pw.rbNo")]; }
  return ["info", "hourglass", t("pw.rbWait")];
}
// Locate: no new fix 60 s after pressing = say why
let locPend = null;
function locStart() { locPend = { kid: cur, at: Date.now() }; }
function locLine() {
  const p = locPend; if (!p) return null;
  if (p.kid !== cur || !st || viewDay) return null;
  const now = Date.now();
  if (st.location && st.location.t >= p.at) { locPend = null; return null; }
  if (now - p.at > 300000) { locPend = null; return null; }
  if (now - p.at < 60000) return null;
  const why = st.locationOn === false ? t("pw.locOff") : !canFeat("location") ? t("pw.locPerm") : !onlineNow() ? t("pw.locOffline", ago(lastSeen())) : t("pw.locSlow");
  return ["warn", "map-pin", why];
}
setInterval(() => {
  document.querySelectorAll("[data-pend]").forEach(e => { const p = PEND[e.dataset.pend]; if (p) { const h = p.html(); if (e.innerHTML !== h) e.innerHTML = h; } });
  if (cur && st && (rbPend || locPend)) updateLive();
}, 5000);
function sideHtml(on) {
  const L = kidList();
  const kidsH = L.length > 6
    ? `<select class="kids" id="sideKid" aria-label="${esc(t("nav.kids"))}">${cur ? "" : `<option value="">${t("nav.pick")}</option>`}${L.map((k, i) => `<option value="${i}" ${k === cur ? "selected" : ""}>${esc(k.name)}</option>`).join("")}</select>`
    : L.map((k, i) => `<button type="button" class="kidn${k === cur ? " on" : ""}" data-sk="${i}" ${k === cur ? 'aria-current="true"' : ""}>${kidAv(k)}<span>${esc(k.name)}</span>${k === cur && st ? dot(onlineNow() ? "on" : "bad") : ""}</button>`).join("");
  return `<nav class="side" aria-label="KidGuard"><div class="brand"><span class="badge">${ic("shield")}</span>KidGuard</div>
    ${L.length > 1 ? `<button type="button" class="nav${on === "ov" ? " on" : ""}" id="sideOv">${ic("users")} ${t("nav.family")}</button>` : ""}
    ${L.length ? `<div class="slab">${t("nav.kids")}</div>${kidsH}` : ""}
    ${APP || onLan() ? `<button type="button" class="nav" id="sideAdd">${ic("plus")} ${t("addKid")}</button>` : ""}
    ${cur && on !== "ov" ? `<div class="slab">${esc(cur.name)}</div>${NAV.filter(([id]) => navOk(id)).map(([id, i]) => `<button type="button" class="nav${tab === id ? " on" : ""}" data-tab="${id}" ${tab === id ? 'aria-current="page"' : ""}>${ic(i)} ${t("tab." + id)}${id === "home" && reqN() ? `<span class="n">${reqN()}</span>` : ""}</button>`).join("")}` : ""}
    <div class="foot">${langSel("lang2")}${themeBtn()}</div></nav>`;
}
function wireSide(root) {
  const L = kidList();
  const go = k => { if (!k || (k === cur && !ov)) return; gen++; clearInterval(timer); ov = null; open(k); };
  root.querySelectorAll("[data-sk]").forEach(b => b.onclick = () => go(L[+b.dataset.sk]));
  const s = root.querySelector("#sideKid"); if (s) s.onchange = () => go(L[+s.value]);
  const o = root.querySelector("#sideOv"); if (o) o.onclick = () => { if (ov) return; screenStop(); earStop(); overview(L, APP || onLan() ? appBoot : () => listScreen(L)); };
  const a = root.querySelector("#sideAdd"); if (a) a.onclick = () => { gen++; clearInterval(timer); screenStop(); earStop(); cur = null; ov = null; addScreen(); };
}
const bnavHtml = () => `<nav class="bnav" aria-label="${esc(t("nav.main"))}">${BNAV.map(([id, i]) => { const on = id === "more" ? MORE_TABS.has(tab) : tab === id;
  return `<button type="button" data-tab="${id}" class="${on ? "on" : ""}" ${on ? 'aria-current="page"' : ""}><b>${ic(i)}${id === "home" && reqN() ? `<span class="n">${reqN()}</span>` : ""}</b><span>${t(id === "more" ? "nav.more" : "tab." + id)}</span></button>`; }).join("")}</nav>`;
function headHtml() {
  const back = !LOCAL && (APP || onLan() || kids.length > 1 || cur.via === "relay");
  return `<div class="head">${back ? `<button type="button" id="back" class="back${kidList().length > 1 || APP || onLan() ? " mOnly" : ""}" aria-label="${esc(t("list"))}" title="${esc(t("list"))}">${ic("chevron-left")}</button>` : ""}${kidAv(cur)}
    <div><div class="t1">${esc(cur.name)}</div><div class="st" id="hst"></div></div><span class="savechip" id="saveChip" role="status"></span></div>`;
}
// header save chip: saving / failed / waiting for the kid's phone (relay) / saved (3 s)
let savedAt = 0, pendSince = 0;
function paintSave() {
  const c = $("#saveChip"); if (!c) return;
  const wait = pendingRules && st && st.rules && pendingRules.rev > st.rules.rev;
  if (!wait) pendSince = 0;
  const h = saveFailed ? tagEl("bad", "triangle-alert", t("save.failed"))
    : saveT || saveBusy ? `<span class="tag t-info"><i class="spin" style="width:12px;height:12px"></i> ${t("save.saving")}</span>`
    : wait ? `<span class="tag warn">${ic("hourglass")} ${t("save.pending")}${(pendSince = pendSince || Date.now()) && Date.now() - pendSince > 90000
        ? " · " + t("save.lastSeen", lastSeen() ? ago(lastSeen()) : "?") + ` <button type="button" class="pill" id="saveResend">${t("save.resend")}</button>` : ""}</span>`
    : Date.now() - savedAt < 3000 ? tagEl("ok", "check", t("save.saved")) : "";
  if (c.innerHTML !== h) c.innerHTML = h;
}
function render(tabClick) {
  const pageY = window.scrollY;
  if (!cur) return;
  const a = $("#app");
  const focus = document.activeElement && document.activeElement.id;   // keep the field being typed in
  if (focus && /^(f-|msg|relay)/.test(focus)) { updateLive(); return; }
  if (tab !== "screen") screenStop();
  if (st && edit && !viewDay && !edit.pinSet && !(st.caps && st.caps.paired === false)) return pinGate(a);   // preview: the PIN is asked on the first save (putRules)   // paired before PINs were required: nothing else until one is set
  a.className = ""; a.innerHTML = `<div class="shell">${sideHtml()}<div class="mainc"><div class="wrap">${localBar()}
  ${headHtml()}
  ${viewDay ? `<div class="ban t-warn" style="position:sticky;top:8px;z-index:15;margin-bottom:12px"><span class="bi">${ic("history")}</span><div class="x">${t("hist.viewing", esc(dm(viewDay)))}</div><button id="goLive" class="pri pill">${ic("zap")} ${t("hist.live")}</button></div>` : ""}
  ${st && edit && !viewDay && !edit.pinSet && tab !== "set" ? `<div class="ban t-warn"><span class="bi">${ic("key-round")}</span><div class="x">${t("pin.warn")}</div><button id="goPin" class="pri pill">${t("pin.setNow")}</button></div>` : ""}
  <div id="live"></div>
  <button type="button" id="qsBtn" class="qsbar" title="${esc(t("qs.h"))} ( / )">${ic("search")}<span>${esc(t("qs.bar"))}</span><kbd>/</kbd></button>
  <div id="body"></div></div></div>${bnavHtml()}</div>`;
  a.querySelectorAll("[data-tab]").forEach(b => b.onclick = () => goTab(b.dataset.tab));
  if ($("#qsBtn")) $("#qsBtn").onclick = qsOpen;
  wireSide(a);
  if ($("#goLive")) $("#goLive").onclick = () => { tab = "home"; open(cur); dayOff(); };
  if ($("#back")) $("#back").onclick = () => { goList(); navDown(0); };
  localWire();
  if ($("#goPin")) $("#goPin").onclick = () => { goTab("set"); subOpen("s-sec"); const f = $("#f-pin"); if (f) { f.scrollIntoView({ block: "center" }); f.focus(); } };
  const body = $("#body");
  if (!st) { updateLive(); body.innerHTML = `<div class="card mute">${lastErr ? dot("bad") + " " + t("cantConnect", esc(lastErr)) : noState ? ic("hourglass") + " " + t("noState") : ic("hourglass") + " " + t("loading")}${lastErr || noState ? `<p style="margin:10px 0 0"><button id="forgetGone" class="sp">${ic("eraser")} ${APP ? t("forget.app") : t("forget.web")}</button></p>` : ""}</div>`; if ($("#forgetGone")) $("#forgetGone").onclick = forgetKid; return; }
  if (!navOk(tab)) tab = "home";
  if (tab === "more" && !isWide()) tab = "set";
  ({ home: homeTab, screen: screenTab, apps: appsTab, rules: rulesTab, where: whereTab, dev: devTab, set: setTab, hist: histTab, files: filesTab, seen: seenTab, more: moreTab, log: logTab, rep: repTab, sup: supTab })[tab](body);
  if (viewDay && tab !== "hist") body.querySelectorAll("input,select,textarea").forEach(x => { x.disabled = true; });   // a past day is read-only: any edit would fail "viewOnly" and raise the save-failed bar
  const gn = FEAT_TAB[tab] ? gateNote(FEAT_TAB[tab]) : "";
  if (gn) {   // move the nodes (not innerHTML) so the handlers the tab just wired survive
    const w = document.createElement("div"); w.className = "gated"; while (body.firstChild) w.appendChild(body.firstChild);
    body.insertAdjacentHTML("afterbegin", gn); body.appendChild(w);
  }
  updateLive();
  // a refresh keeps the page where it was; a tab switch lands on the top of the new tab
  secWatch();
  window.scrollTo(0, tabClick ? 0 : pageY);
}
function goTab(id) {
  if (subId && (id === tab || (id === "more" && MORE_TABS.has(tab)))) return subClose(true);   // the current tab again: back to its list, scrolled to the top
  if (id === "more" && !isWide()) id = "set";   // phones: "Thêm" IS the settings list (owner 2026-11-16: one level less)
  const from = tab; tab = id; subId = null; if (tab === "hist") histDays = null; render(true); navTab(id, from); }

// "Thêm" (phones): everything that is not in the bottom bar, grouped. On phones the settings tab draws these groups around
// its own (setTab, moreParts): one list, no "Cài đặt" hop. moreTab is only left for a wide screen landing on "more".
function moreParts() {
  const L = kidList();
  const li = (id, icn, sub) => navOk(id) ? navRow(`data-go="${id}"`, icn, t("tab." + id), sub) : "";
  const grp = (h, rows) => rows.join("") ? `<div class="sec">${h}</div><div class="list">${rows.join("")}</div>` : "";
  return {
    kid: [li("screen", "smartphone", t("more.screenS")), li("dev", "cpu", t("more.devS")), li("files", "folder-open", t("more.filesS")), li("seen", "eye", t("more.seenS"))].join(""),
    track: grp(t("sec.track"), [li("log", "notebook-pen", t("more.logS")), li("rep", "chart-column", t("more.repS")), li("hist", "history", t("hist.h"))]),
    general: `<div class="sec">${t("set.general")}</div><div class="list">${[li("sup", "message-square", t("more.supS")),
      L.length > 1 && !LOCAL ? navRow('id="moreOv"', "users", t("nav.family"), t("ovN", L.length)) : "",
      !LOCAL && (APP || kids.length > 1 || cur.via === "relay") ? navRow('id="moreList"', "list", t("list")) : "",
      APP ? navRow('id="moreApp"', "settings", t("au.h")) : ""].join("")}</div>${APP ? "" : `<div class="card row">${langSel()}<span class="sp"></span>${themeBtn()}</div>`}`,
  };
}
function moreWire(b) {
  const L = kidList();
  b.querySelectorAll("[data-go]").forEach(x => x.onclick = () => goTab(x.dataset.go));
  if ($("#moreOv")) $("#moreOv").onclick = () => { screenStop(); earStop(); overview(L, APP || onLan() ? appBoot : () => listScreen(L)); };
  if ($("#moreApp")) $("#moreApp").onclick = () => goAppSettings();
  if ($("#moreList")) $("#moreList").onclick = () => { goList(); navDown(0); };
}
function moreTab(b) {
  const M = moreParts();
  b.innerHTML = `<div class="sec">${t("sec.kid")}</div><div class="list">${M.kid}${navOk("set") ? navRow('data-go="set"', "settings", t("tab.set"), t("more.setS")) : ""}</div>` + M.track + M.general;
  moreWire(b);
}

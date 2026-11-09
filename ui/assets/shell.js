// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- shell: one NAV list feeds the desktop sidebar and the phone bottom bar ("Thêm" holds the rest) ----
const NAV = [["home", "house"], ["apps", "puzzle"], ["rules", "ruler"], ["where", "map-pin"], ["screen", "smartphone"], ["dev", "cpu"], ["files", "folder-open"], ["log", "notebook-pen"], ["rep", "chart-column"], ["hist", "history"], ["set", "settings"]];
const BNAV = [["home", "house"], ["apps", "puzzle"], ["rules", "ruler"], ["where", "map-pin"], ["more", "ellipsis"]];
const MORE_TABS = new Set(["more", "screen", "dev", "files", "log", "rep", "hist", "set"]);
const navOk = id => !(viewDay && (id === "screen" || id === "dev" || id === "files")) && (id !== "hist" || APP);
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
  const b = e.target.closest && e.target.closest("[data-theme-btn]"); if (!b) return;
  theme = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]; try { localStorage.setItem("kg.theme", theme); } catch {}
  applyTheme(); document.querySelectorAll("[data-theme-btn]").forEach(x => { x.outerHTML = themeBtn(); });
  toast("ok|" + t("theme.h") + ": " + t("theme." + theme));
});
const bonusGiven = new Set();   // "kid:requestId" whose extra-time bonus is already saved
const reqN = () => st && !viewDay ? (st.requests || []).filter(q => !answered.has(q.id)).length : 0;
const onlineNow = () => st && Date.now() - lastSeen() < (cur && cur.via === "lan" ? 30000 : 120000);
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
let savedAt = 0;
function paintSave() {
  const c = $("#saveChip"); if (!c) return;
  const wait = pendingRules && st && st.rules && pendingRules.rev > st.rules.rev;
  const h = saveFailed ? `<span class="tag bad">${ic("triangle-alert")} ${t("save.failed")}</span>`
    : saveT || saveBusy ? `<span class="tag t-info"><i class="spin" style="width:12px;height:12px"></i> ${t("save.saving")}</span>`
    : wait ? `<span class="tag warn">${ic("hourglass")} ${t("save.pending")}</span>`
    : Date.now() - savedAt < 3000 ? `<span class="tag ok">${ic("check")} ${t("save.saved")}</span>` : "";
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
  <div id="body"></div></div></div>${bnavHtml()}</div>`;
  a.querySelectorAll("[data-tab]").forEach(b => b.onclick = () => goTab(b.dataset.tab));
  wireSide(a);
  if ($("#goLive")) $("#goLive").onclick = () => { tab = "home"; open(cur); dayOff(); };
  if ($("#back")) $("#back").onclick = () => { goList(); navDown(0); };
  localWire();
  if ($("#goPin")) $("#goPin").onclick = () => { tab = "set"; render(true); const f = $("#f-pin"); if (f) { f.scrollIntoView({ block: "center" }); f.focus(); } };
  const body = $("#body");
  if (!st) { updateLive(); body.innerHTML = `<div class="card mute">${lastErr ? dot("bad") + " " + t("cantConnect", esc(lastErr)) : noState ? ic("hourglass") + " " + t("noState") : ic("hourglass") + " " + t("loading")}</div>`; return; }
  if (!navOk(tab)) tab = "home";
  ({ home: homeTab, screen: screenTab, apps: appsTab, rules: rulesTab, where: whereTab, dev: devTab, set: setTab, hist: histTab, files: filesTab, more: moreTab, log: logTab, rep: repTab })[tab](body);
  if (viewDay && tab !== "hist") body.querySelectorAll("input,select,textarea").forEach(x => { x.disabled = true; });   // a past day is read-only: any edit would fail "viewOnly" and raise the save-failed bar
  const gn = FEAT_TAB[tab] ? gateNote(FEAT_TAB[tab]) : "";
  if (gn) {   // move the nodes (not innerHTML) so the handlers the tab just wired survive
    const w = document.createElement("div"); w.className = "gated"; while (body.firstChild) w.appendChild(body.firstChild);
    body.insertAdjacentHTML("afterbegin", gn); body.appendChild(w);
  }
  updateLive();
  // a refresh keeps the page where it was; a tab switch lands on the top of the new tab
  window.scrollTo(0, tabClick ? 0 : pageY);
}
function goTab(id) { const from = tab; tab = id; if (tab === "hist") histDays = null; render(true); navTab(id, from); }

// "Thêm" (phones): everything that is not in the bottom bar, grouped
function moreTab(b) {
  const L = kidList();
  const li = (id, icn, sub) => navOk(id) ? `<button type="button" class="li" data-go="${id}"><span class="ib">${ic(icn)}</span><span class="x"><b>${t("tab." + id)}</b>${sub ? `<span class="sub">${sub}</span>` : ""}</span>${ic("chevron-right")}</button>` : "";
  const grp = (h, rows) => rows.join("") ? `<div class="sec">${h}</div><div class="list">${rows.join("")}</div>` : "";
  b.innerHTML = grp(t("sec.kid"), [li("screen", "smartphone", t("more.screenS")), li("dev", "cpu", t("more.devS")), li("files", "folder-open", t("more.filesS"))])
    + grp(t("sec.track"), [li("log", "notebook-pen", t("more.logS")), li("rep", "chart-column", t("more.repS")), li("hist", "history", t("hist.h"))])
    + grp(t("sec.manage"), [li("set", "settings", t("more.setS")),
      L.length > 1 && !LOCAL ? `<button type="button" class="li" id="moreOv"><span class="ib">${ic("users")}</span><span class="x"><b>${t("nav.family")}</b><span class="sub">${t("ovN", L.length)}</span></span>${ic("chevron-right")}</button>` : "",
      !LOCAL && (APP || kids.length > 1 || cur.via === "relay") ? `<button type="button" class="li" id="moreList"><span class="ib">${ic("list")}</span><span class="x"><b>${t("list")}</b></span>${ic("chevron-right")}</button>` : ""])
    + `<div class="sec">${t("set.general")}</div>${APP ? `<div class="list"><button type="button" class="li" id="moreApp"><span class="ib">${ic("settings")}</span><span class="x"><b>${t("au.h")}</b></span>${ic("chevron-right")}</button></div>` : `<div class="card row">${langSel()}<span class="sp"></span>${themeBtn()}</div>`}`;
  b.querySelectorAll("[data-go]").forEach(x => x.onclick = () => goTab(x.dataset.go));
  if ($("#moreOv")) $("#moreOv").onclick = () => { screenStop(); earStop(); overview(L, APP || onLan() ? appBoot : () => listScreen(L)); };
  if ($("#moreApp")) $("#moreApp").onclick = () => goAppSettings();
  if ($("#moreList")) $("#moreList").onclick = () => { goList(); navDown(0); };
}
// "Nhật ký": events + the phone's own log
function logTab(b) {
  const ev = (st.events || []).slice().reverse(), lg = (st.log || []).slice(-60).reverse();
  b.innerHTML = `<div class="card"><h2>${ic("bell")} ${t("events")}</h2>${ev.map(evHtml).join("") || `<div class="empty"><span class="e">${ic("inbox")}</span>${t("empty")}</div>`}</div>
  <div class="card"><h2>${ic("notebook-pen")} ${t("log")}</h2>${byDay(lg, l => l.t, logRow) || `<div class="empty"><span class="e">${ic("inbox")}</span>${t("empty")}</div>`}</div>`;
}
// "Báo cáo tuần": 7-day bars + this kid's weekly report + most used today
function repTab(b) {
  b.innerHTML = `<div class="g2"><div><div class="card"><h2>${ic("chart-column")} ${t("last7")}</h2><div id="histPick" style="margin-bottom:8px"></div><div class="hist" id="hist"></div><div id="histTop" class="mute"></div></div>
    <div class="card"><h2>${ic("history")} ${t("ov.report")}</h2>${ovReport([{ k: cur, st, err: "" }])}</div></div>
    <div class="card"><h2>${ic("flame")} ${t("topApps")}</h2>${st.apps.filter(x => x.ms > 60000).slice(0, 12).map(appRow).join("") || `<div class="empty"><span class="e">${ic("sprout")}</span>${t("none")}</div>`}</div></div>`;
  drawHist();
}

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
  const cell = (tone, i, big, sub) => `<div class="pc"><span class="pci t-${tone}">${ic(i)}</span><b>${big}</b><span class="mute">${sub}</span></div>`;
  const bat = +st.battery, cur0 = st.screenOn && st.current ? appOf(st.current) : "";
  return `<div class="lhead"><h2>${ic("smartphone")} ${t("pc.title")}</h2></div><div class="pcg">`
    + cell(online ? "ok" : "bad", online ? "wifi" : "wifi-off", online ? t("live.on") : t("live.off"), ago(lastSeen()))
    + cell(st.charging ? "ok" : bat <= 15 ? "bad" : bat <= 30 ? "warn" : "info", st.charging ? "zap" : "battery-full", bat + "%", st.charging ? t("pc.charging") : t("pc.battery"))
    + cell(st.screenOn ? "info" : "mute", "smartphone", st.screenOn ? t("pc.on") : t("pc.off"), cur0 ? esc(cur0) : t("pc.screen"))
    + `</div>`;
}
function updateLive() {
  paintSave(); if (tab === "screen") screenCtl();
  const el = $("#live"), hs = $("#hst"); if (!el) return;
  if (!st) { if (hs) hs.innerHTML = `${dot(lastErr ? "bad" : "")}<span>${lastErr ? esc(lastErr) : t("connecting")}</span>`; el.innerHTML = ""; return; }
  if (viewDay) { if (hs) hs.innerHTML = `${ic("clock")}<span>${t("hist.snap", hhmm(st.t))} · ${+st.battery}%</span>`; el.innerHTML = ""; return; }
  const lan = cur.via === "lan", online = onlineNow();
  const lost = lan ? (lastErr || Date.now() - st.t > 300000) : (seenAt && Date.now() - seenAt > 300000);
  const lostFrom = lan ? st.t : seenAt;
  const sk = document.querySelector(".kidn.on");   // sidebar dot is rendered once; keep it in step with the header
  if (sk) { sk.querySelector(".dot")?.remove(); sk.insertAdjacentHTML("beforeend", dot(online ? "on" : "bad")); }
  if (hs) hs.innerHTML = `${dot(online ? "on" : "bad")}<span>${online ? t("live.on") : t("live.off")} · ${ago(lastSeen())}</span>`;
  const pc = $("#phoneCard"); if (pc) pc.innerHTML = phoneCard(online);
  const B = [];   // [tone, icon, text]
  if (APP && cachedAt) B.push(["warn", "wifi-off", t("live.cached", hhmm(cachedAt))]);
  if (!lan && lastNet) B.push(["warn", "wifi-off", t("live.selfOff")]);
  if (lost && !(!lan && lastNet)) B.push(["bad", "siren", t("live.lost", hhmm(lostFrom)) + (lastErr && lan ? " (" + esc(lastErr) + ")" : "")]);
  B.push(...modeBans());
  if ((st.accPaused || []).length) B.push(["info", "landmark", t("live.accBank", esc(st.accPaused.map(p => ((st.apps || []).find(a => a.pkg === p) || {}).label || p).join(", ")))]);
  else if (st.acc === false) B.push(["warn", "triangle-alert", t("live.acc")]);
  if (st.usageAccess === false || st.usageOff) B.push(["bad", "triangle-alert", t("live.usage")]);
  if (st.dnsError) B.push(["warn", "triangle-alert", t("live.dns", esc(st.dnsError))]);
  if (st.locationOn === false) B.push(["warn", "triangle-alert", t("live.locOff")]);
  const B2 = B.filter(x => !(x[3] && banHid.has(x[3])));
  const ban = x => `<div class="ban t-${x[0]}" role="status"><span class="bi">${ic(x[1])}</span><div class="x">${x[2]}</div>${x[4] ? `<button type="button" class="pill" data-band="${x[3]}">${t("ban.why")}</button><button type="button" class="banx" data-banx="${x[3]}" aria-label="${esc(t("ban.hide"))}" title="${esc(t("ban.hide"))}">${ic("x")}</button>` : ""}</div>`;
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
  if (st && st.listen && st.listen.mic === false) return ask(t("ear.no mic permission"), "OK", false, "", "", null, t("ear.cant"));
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

function modeBans() {
  const m = modeBans0();
  return st.rules && st.rules.monitor ? [["warn", "eye", t("mon.ban")]].concat(m) : m;
}
function modeBans0() {   // basic mode / unfinished setup: [tone, icon, text] banners
  if (st.mode === "setup" && st.features) return [["info", "eye", t("mode.preview")]];
  if (st.owner || !st.mode && st.owner !== false) return [];
  const m = st.basic || {}, miss = ["acc", "admin", "usage", "location", "notify", "battery"].filter(k => m[k] === false).map(k => t("perm." + k));
  if (st.mode !== "basic") return [["bad", "triangle-alert", t("mode.setup", esc(miss.join(", ") || t("mode.items")))]];
  return (m.acc === false ? [["bad", "siren", t("mode.accOff")]] : []).concat([["warn", "info", t("mode.basicS") + (miss.length ? " " + t("mode.miss", esc(miss.join(", "))) : ""), "basic", t("mode.basic")]]);
}
// "Cấm dùng…" / "Cho thêm giờ…" modal on the #askPop layer (Esc / backdrop close it through askDone).
// pause: rules.pausedUntil = end time (ms), "for N min" or "until HH:MM" (passed today = tomorrow), both computed here.
// bonus: rules.bonus = {day, min}, today only, so minutes only.
const partOfDay = h => t(h < 11 ? "pk.p0" : h < 13 ? "pk.p1" : h < 18 ? "pk.p2" : "pk.p3");
function whenTxt(ts) {
  const d = new Date(ts), n = new Date(), tm = new Date(n.getFullYear(), n.getMonth(), n.getDate() + 1);
  const same = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  return same(d, n) ? hhmm(ts) : same(d, tm) ? t("pk.tmr", hhmm(ts), partOfDay(d.getHours())) : hhmm(ts);
}
function untilOf(hm) {   // "HH:MM" -> next such moment (today if still ahead, else tomorrow)
  const [h, m] = String(hm).split(":").map(Number); if (!(h >= 0 && m >= 0)) return 0;
  const d = new Date(); d.setHours(h, m, 0, 0); if (d.getTime() <= Date.now() + 30000) d.setDate(d.getDate() + 1); return d.getTime();
}
function pickTime(kind, active, onOk, onClear) {
  if (askDone) askDone(false, true);
  const pause = kind === "pause", pop = $("#askPop");
  const s = { mode: "dur", n: pause ? 30 : 15, unit: "m", at: "06:00" };
  const chips = pause ? [[10], [30], [60], ["06:00"]] : [[10], [15], [30], [60]];
  const ms = () => s.mode === "at" ? untilOf(s.at) - Date.now() : Math.round(s.n * (s.unit === "h" ? 60 : 1)) * 60000;
  const ok = () => { const v = ms(); return v >= 60000 && v <= 24 * 3600e3; };
  const draw = () => {
    const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v;
    pop.innerHTML = `<div class="dp pk" role="dialog" aria-modal="true" aria-labelledby="askT"><h2 id="askT" style="margin:0 0 4px">${ic(pause ? "pause" : "plus")} ${t(pause ? "pk.pauseH" : "pk.bonusH")}</h2>
      ${active ? `<div class="alert warn" style="font-weight:400;margin:4px 0 10px">${ic(pause ? "pause" : "timer")} ${active}</div>` : ""}
      ${pause ? `<div class="seg" role="tablist" aria-label="${esc(t("pk.pauseH"))}">${[["dur", "timer", t("pk.dur")], ["at", "clock", t("pk.at")]].map(([id, i, n]) => `<button type="button" role="tab" data-pm="${id}" aria-selected="${s.mode === id}">${ic(i)}<span>${n}</span></button>`).join("")}</div>` : ""}
      ${s.mode === "dur" ? `<div class="row stp"><button type="button" id="pkMinus" aria-label="${esc(t("pk.less"))}">${ic("minus")}</button><input id="pkN" type="number" inputmode="decimal" min="1" max="${s.unit === "h" ? 24 : 1440}" step="${s.unit === "h" ? 0.5 : 5}" value="${s.n}" aria-label="${esc(t("pk.amount"))}"><button type="button" id="pkPlus" aria-label="${esc(t("pk.more"))}">${ic("plus")}</button>
        <select id="pkU" aria-label="${esc(t("pk.unit"))}"><option value="m" ${s.unit === "m" ? "selected" : ""}>${t("pk.mins")}</option><option value="h" ${s.unit === "h" ? "selected" : ""}>${t("pk.hours")}</option></select></div>`
      : `<label class="row stp"><span>${t("pk.untilL")}</span><input id="pkAt" type="time" value="${esc(s.at)}" aria-label="${esc(t("pk.at"))}"></label>`}
      <div class="chips" style="margin:10px 0" role="group" aria-label="${esc(t("pk.quick"))}">${chips.map(([c]) => { const on = typeof c === "string" ? s.mode === "at" && s.at === c : s.mode === "dur" && ms() === c * 60000;
        return `<button type="button" data-pc="${c}" class="${on ? "on" : ""}" aria-pressed="${on}">${typeof c === "string" ? t("pk.am6") : fmt(c * 60000)}</button>`; }).join("")}</div>
      <div class="pks" role="status">${ok() ? `<b>${pause ? t("pk.sumPause", whenTxt(end)) : t("pk.sumBonus", fmt(v))}</b> <span class="mute">· ${pause ? fmt(v) : t("pk.today")}</span>` : `<span class="err">${t("pk.bad")}</span>`}</div>
      ${pause ? `<p class="mute" style="margin:6px 0 12px">${t("pause.askNote")}</p>` : `<p class="mute" style="margin:6px 0 12px">${t("pk.bonusNote")}</p>`}
      <div class="row"><button type="button" id="askNo" class="sp">${ic("x")} ${t("cancel")}</button><button type="button" id="askOk" class="sp pri" ${ok() ? "" : "disabled"}>${ic("check")} ${t(pause ? (active ? "pk.update" : "pause.ok") : "pk.give")}</button></div>
      ${onClear ? `<button type="button" id="pkClear" class="bad" style="width:100%;margin-top:8px">${ic(pause ? "play" : "trash-2")} ${t(pause ? "unpause" : "home.unbonus")}</button>` : ""}</div>`;
    pop.querySelectorAll("[data-pm]").forEach(x => x.onclick = () => { s.mode = x.dataset.pm; redraw("[data-pm][aria-selected=true]"); });
    pop.querySelectorAll("[data-pc]").forEach(x => x.onclick = () => { const c = x.dataset.pc; if (c.includes(":")) { s.mode = "at"; s.at = c; } else { s.mode = "dur"; if (+c % 60 === 0) { s.unit = "h"; s.n = c / 60; } else { s.unit = "m"; s.n = +c; } } redraw(`[data-pc="${c}"]`); });
    const N = $("#pkN");
    if (N) {
      N.oninput = () => { s.n = +String(N.value).replace(",", ".") || 0; sum(); };
      $("#pkU").onchange = e => { const m = ms(); s.unit = e.target.value; s.n = s.unit === "h" ? Math.max(0.5, Math.round(m / 1800000) / 2) : Math.round(m / 60000); redraw("#pkU"); };
      const step = d => { const st0 = s.unit === "h" ? 0.5 : 5; s.n = Math.max(st0, Math.min(s.unit === "h" ? 24 : 1440, Math.round((s.n + d * st0) / st0) * st0)); redraw(d > 0 ? "#pkPlus" : "#pkMinus"); };
      $("#pkMinus").onclick = () => step(-1); $("#pkPlus").onclick = () => step(1);
    }
    if ($("#pkAt")) $("#pkAt").onchange = $("#pkAt").oninput = e => { if (e.target.value) { s.at = e.target.value; sum(); } };
    $("#askNo").onclick = () => askDone && askDone(false);
    $("#askOk").onclick = () => { if (!ok()) return; const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v; askDone && askDone(true); onOk(pause ? end : Math.round(v / 60000)); };
    if ($("#pkClear")) $("#pkClear").onclick = () => { askDone && askDone(true); onClear(); };
  };
  // the summary line follows typing without redrawing (the input keeps focus)
  const sum = () => { const v = ms(), end = s.mode === "at" ? untilOf(s.at) : Date.now() + v, el = pop.querySelector(".pks");
    el.innerHTML = ok() ? `<b>${pause ? t("pk.sumPause", whenTxt(end)) : t("pk.sumBonus", fmt(v))}</b> <span class="mute">· ${pause ? fmt(v) : t("pk.today")}</span>` : `<span class="err">${t("pk.bad")}</span>`;
    $("#askOk").disabled = !ok();
    pop.querySelectorAll("[data-pc]").forEach(x => { const c = x.dataset.pc, on = c.includes(":") ? s.mode === "at" && s.at === c : s.mode === "dur" && v === c * 60000; x.classList.toggle("on", on); x.setAttribute("aria-pressed", on); }); };
  const redraw = f => { draw(); const x = f && pop.querySelector(f); if (x) x.focus(); };
  draw(); pop.style.display = "flex";
  return new Promise(res => { const done = v => { if (askDone !== done) return; askDone = null; pop.style.display = "none"; pop.innerHTML = ""; res(v); }; askDone = done; const f = $("#pkN") || $("#askOk"); if (f) f.focus(); });
}
// generic sheet on the #askPop layer (bottom sheet on phones, dialog on desktop); Esc / backdrop / #askNo close it
function sheet(html, wire) {
  if (askDone) askDone(false, true);
  const pop = $("#askPop"); pop.innerHTML = html; pop.style.display = "flex";
  return new Promise(res => {
    const done = v => { if (askDone !== done) return; askDone = null; pop.style.display = "none"; pop.innerHTML = ""; res(v); };
    askDone = done;
    const x = pop.querySelector("#askNo"); if (x) x.onclick = () => done(false);
    if (wire) wire(pop, done);
    const f = pop.querySelector(".close") || pop.querySelector("button"); if (f) f.focus();
  });
}
const sheetHead = (icn, title) => `<div class="shh"><h2 id="askT">${ic(icn)} ${title}</h2><button type="button" class="close" id="askNo" aria-label="${esc(t("close"))}">${ic("x")}</button></div>`;

// time request: compact card on home; note, canned replies and minute chips live in the "Trả lời…" sheet
const reqChips = q => [15, 30, 45, 60].includes(+q.min) ? [15, 30, 45, 60] : [15, 30, 45, 60, +q.min].sort((a, c) => a - c);
function reqCard(q, r) {
  const ch = q.chore ? (r.chores || []).find(c => c.id === q.chore) : null, m = reqMin[q.id] || +q.min, id = esc(q.id), note = (reqNote[q.id] || "").trim();
  return `<div class="card req" role="group" aria-label="${esc(t("req.aria"))}"><div class="row" style="flex-wrap:nowrap;align-items:flex-start"><span class="ib t-warn" style="color:var(--warn);background:rgba(217,119,6,.16)">${ic("hand")}</span>
    <div class="sp" style="min-width:0"><b>${t("req.asks", esc(cur.name), +q.min)}</b>${q.t ? ` <span class="mute">· ${ago(q.t)}</span>` : ""}
    ${q.chore ? `<div style="margin-top:4px"><span class="tag warn" style="margin:0">${ic("star")} ${t("req.chore")}</span> <span class="tag">${esc(ch ? ch.name : t("req.choreGone"))}</span></div>` : ""}
    ${q.note ? `<div class="mute" style="margin-top:4px">${t("req.note", esc(q.note))}</div>` : ""}
    ${note ? `<div class="mute" style="margin-top:4px">${ic("message-square")} ${esc(note)}</div>` : ""}</div></div>
    <div class="row" style="margin-top:12px"><button class="pri" data-yes="${id}" data-min="${m}">${ic("circle-check")} ${t("req.yes", m)}</button><button data-no="${id}">${ic("circle-x")} ${t("req.no")}</button><button data-reply="${id}">${ic("message-square")} ${t("req.replyBtn")}</button></div></div>`;
}
function replySheet(q, today) {
  const m = reqMin[q.id] || +q.min, id = esc(q.id);
  return sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("hand", t("req.asks", esc(cur.name), +q.min))}
    ${q.note ? `<p class="mute" style="margin-top:0">${t("req.note", esc(q.note))}</p>` : ""}
    <label class="mute" for="f-rn-${id}" style="display:block;margin-top:6px">${t("req.reply")}</label>
    <input id="f-rn-${id}" data-rn="${id}" maxlength="200" autocomplete="off" placeholder="${esc(t("req.replyPh"))}" style="width:100%;margin-top:6px" value="${esc(reqNote[q.id] || "")}">
    <div class="chips" style="margin-top:8px" role="group" aria-label="${esc(t("req.cannedAria"))}">${t("req.canned").map(c => `<button type="button" data-cr="${id}" data-v="${esc(c)}" class="${reqNote[q.id] === c ? "on" : ""}" aria-pressed="${reqNote[q.id] === c}">${esc(c)}</button>`).join("")}</div>
    <div class="mute" style="margin-top:14px">${t("minutes")}</div>
    <div class="chips" style="margin-top:6px" role="group" aria-label="${esc(t("minutes"))}">${reqChips(q).map(v => `<button type="button" data-rm="${id}" data-v="${v}" class="${v === m ? "on" : ""}" aria-pressed="${v === m}">${t("fmt.m", v)}</button>`).join("")}</div>
    <div class="row" style="margin-top:16px"><button class="sp" data-no="${id}">${ic("circle-x")} ${t("req.no")}</button><button class="sp pri" data-yes="${id}" data-min="${m}">${ic("circle-check")} ${t("req.yes", m)}</button></div></div>`,
    pop => wireReq(pop, today, true));
}
function wireReq(b, today, inSheet) {
  b.querySelectorAll("[data-rn]").forEach(i => i.oninput = () => { reqNote[i.dataset.rn] = i.value; });
  // canned reply: fills the message (tap again = clear); the chip that matches the text shows as pressed
  const crPaint = id => b.querySelectorAll("[data-cr]").forEach(o => { if (o.dataset.cr !== id) return; const on = (reqNote[id] || "") === o.dataset.v; o.classList.toggle("on", on); o.setAttribute("aria-pressed", on); });
  b.querySelectorAll("[data-rn]").forEach(i => i.addEventListener("input", () => crPaint(i.dataset.rn)));
  b.querySelectorAll("[data-cr]").forEach(c => c.onclick = () => {
    const id = c.dataset.cr, v = reqNote[id] === c.dataset.v ? "" : c.dataset.v; reqNote[id] = v;
    const i = [...b.querySelectorAll("[data-rn]")].find(o => o.dataset.rn === id); if (i) i.value = v;
    crPaint(id);
  });
  b.querySelectorAll("[data-rm]").forEach(c => c.onclick = () => {
    const id = c.dataset.rm, v = +c.dataset.v; reqMin[id] = v;
    b.querySelectorAll("[data-rm]").forEach(o => { if (o.dataset.rm !== id) return; const on = +o.dataset.v === v; o.classList.toggle("on", on); o.setAttribute("aria-pressed", on); });
    document.querySelectorAll("[data-yes]").forEach(y => { if (y.dataset.yes === id) { y.dataset.min = v; y.innerHTML = ic("circle-check") + " " + t("req.yes", v); } });
  });
  const noteOf = id => (reqNote[id] || "").trim().slice(0, 200);
  const doneReq = id => { delete reqMin[id]; delete reqNote[id]; };
  const after = () => { if (cur) render(); };
  b.querySelectorAll("[data-yes]").forEach(x => x.onclick = async () => {
    const id = x.dataset.yes, note = noteOf(id);
    if (!(await ask(t("req.askYes", +x.dataset.min) + (note ? "\n" + t("req.askNote", note) : "")))) return;
    const n = +x.dataset.min, cur0 = edit.bonus && edit.bonus.day === today ? edit.bonus.min : 0;
    const k = cur, e = edit, prev = e.bonus;
    // idempotent per request: if the bonus was saved but `answer` failed, the next tap only re-sends the answer (never adds the bonus twice)
    if (!bonusGiven.has((k.target || "") + k.id + ":" + id)) {
      clearTimeout(saveT); saveT = null;
      e.bonus = { day: today, min: cur0 + n };
      try { await putRules(k, e); } catch (er) { if (e === edit) e.bonus = prev; toast("err|" + t("req.bonusFail", er.message)); return; }   // card stays
      bonusGiven.add((k.target || "") + k.id + ":" + id);
    }
    if (k !== cur) return;
    if (await cmd({ type: "answer", id, ok: true, min: n, ...(note ? { note } : {}) }, t("req.yesDone", n), false)) { answered.add(id); doneReq(id); }
    after();
  });
  b.querySelectorAll("[data-no]").forEach(x => x.onclick = async () => {
    const id = x.dataset.no, note = noteOf(id);
    if (!(await ask(t("req.askNo") + (note ? "\n" + t("req.askNote", note) : ""), t("req.no"), true))) return;
    if (await cmd({ type: "answer", id, ok: false, ...(note ? { note } : {}) }, t("req.noDone"), false)) { answered.add(id); doneReq(id); }
    after();
  });
  if (!inSheet) b.querySelectorAll("[data-reply]").forEach(x => x.onclick = () => { const q = (st.requests || []).find(o => o.id === x.dataset.reply); if (q) replySheet(q, today).then(() => { if (cur && tab === "home") render(); }); });
}
// "Thêm" round button: wake, listen, message, restart
function moreSheet() {
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("zap", t("now"))}
    <div class="grid"><button type="button" id="wake" class="act"></button>${cur.via === "lan" && !viewDay ? `<button type="button" id="ear1" class="act"></button>` : ""}${isBasic() ? "" : `<button type="button" id="reboot" class="bad">${ic("refresh-cw")} ${t("reboot")}</button>`}</div>
    ${cur.via === "lan" ? "" : `<p class="mute">${ic("ear")} ${t("ear.lanOnly")}</p>`}
    <label class="mute" for="msg" style="display:block;margin:16px 0 6px">${t("msg.h")}</label>
    <div class="row"><input id="msg" class="sp" placeholder="${esc(t("msgPh"))}" maxlength="300"><button type="button" id="send" class="pri">${ic("send")} ${t("send")}</button></div></div>`,
    pop => {
      pop.querySelector("#wake").onclick = () => act("wake", () => cmd({ type: "wake" }, t("wake.done"), false));
      const e1 = pop.querySelector("#ear1"); if (e1) e1.onclick = () => earOn() ? micStop() : ringOn() ? toast("warn|" + t("act.noEar")) : earStart();
      const rb = pop.querySelector("#reboot"); if (rb) rb.onclick = () => cmd({ type: "reboot" }, t("reboot.done"));
      pop.querySelector("#send").onclick = () => { const tx = $("#msg").value.trim(); if (!tx) return $("#msg").focus();
        const sb = pop.querySelector("#send"); if (sb.disabled) return; sb.disabled = true;   // no double send while the dialog / request is in flight
        cmd({ type: "message", text: tx }, t("msg.done")).then(r => { if (r && $("#msg") && $("#msg").value.trim() === tx) $("#msg").value = ""; }).finally(() => { sb.disabled = false; }); };
      pop.querySelector("#msg").onkeydown = e => { if (e.key === "Enter") pop.querySelector("#send").click(); };
      actPaint();
    });
}
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
// one history entry while it is open, so Android Back returns to the list (popstate in overview.js) instead of leaving the app
let appSetOn = false;
function appSetClose() { if (!appSetOn) return; if (history.state && history.state.appSet) history.back(); else { appSetOn = false; appBoot(); } }   // popstate does the rest
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
  // one delegated handler: updBox is redrawn by kgUpd
  $("#app").onclick = e => {
    const b = e.target.closest && e.target.closest("#updChk, #updInst"); if (!b || b.disabled) return;
    if (b.id === "updChk") kgCall("checkUpdate"); else kgCall("installUpdate", b.dataset.tag);
  };
}
// from the kid screens: leave the kid, then show the settings on the list's place
function goAppSettings() { if (cur || ov) goList(); appSettings(); }

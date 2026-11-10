// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// Once per kid, at home: nothing set up for when the kid is out (no Tailscale, no relay) -> one tip card.
function awayTip() {
  if (cur.via !== "lan" || isBasic() || (st.ts && st.ts.state !== "off") || st.relay || kidFlag(cur, "away")) return "";
  return `<div class="card mt16"><h2>${ic("route")} ${t("away.h")}</h2><p class="mute">${t("away.p")}</p>
    <div class="row"><button type="button" id="awayGo" class="pri">${ic("chevron-right")} ${t("away.go")}</button><button type="button" id="awayNo">${t("away.later")}</button></div></div>`;
}
function homeTab(b) {
  const r = edit || st.rules, today = st.day, limit = edit ? todayLimit(r, today) : (st.limitMin ?? r.dailyMin);
  const reqs = (st.requests || []).filter(q => !answered.has(q.id));
  const used = st.usedMs, left = st.leftMs, pct = limit ? Math.min(100, used / (limit * 600)) : 0;
  const pausedLeft = r.pausedUntil - Date.now();
  const bonusMin = r.bonus && r.bonus.day === today ? +r.bonus.min || 0 : 0;
  const blockedNow = st.apps.filter(x => x.suspended);
  // the solid button follows the situation: can use the phone -> "Cấm dùng…"; paused / out of time -> "Cho thêm giờ…"
  const out = pausedLeft > 0 || (limit && left <= 0);
  const pauseB = cls => `<button type="button" id="pauseGo" class="${pausedLeft > 0 ? "pausing " + cls.replace("pri ", "") : cls}">${pausedLeft > 0 ? `${ic("pause")} <span><b>${t("pk.active", whenTxt(r.pausedUntil))}</b> · ${t("pk.tapEdit")}</span>` : `${ic("pause")} ${t("pk.pauseBtn")}`}</button>`;
  const bonusB = cls => limit ? `<button type="button" id="bonusGo" class="${cls}">${ic("plus")} ${t("pk.bonusBtn")}${bonusMin ? ` <span class="tag ok">+${t("fmt.m", bonusMin)}</span>` : ""}</button>` : "";
  const top = st.apps.filter(x => x.ms > 60000).slice(0, 3), mx = Math.max(1, ...top.map(x => x.ms));
  const ev = (st.events || []).slice(-3).reverse();
  b.innerHTML = `<div class="g2 w"><div>
  ${reqs.map(q => reqCard(q, r)).join("")}
  <section class="hero" aria-label="${esc(t("today"))}">
    <div class="lab">${limit ? t(left > 0 ? "hero.left" : "hero.out") : t("hero.used")}</div>
    <div class="big">${limit ? fmt(Math.max(0, left)) : fmt(used)}</div>
    <div class="meta"><span>${limit ? t("hero.usedOf", fmt(used), fmt(limit * 60000)) + (bonusMin ? " " + t("home.incl", bonusMin) : "") : t("home.noLim")}</span>${blockedNow.length ? `<button type="button" class="chip t-bad" id="blkGo">${ic("ban")} ${t("lockedN", blockedNow.length)} ›</button>` : ""}</div>
    ${limit ? `<div class="bar"><i style="width:${pct}%"></i></div>` : ""}
    ${out ? bonusB("pri hb") + pauseB(limit ? "hb2" : "pri hb") : pauseB("pri hb") + bonusB("hb2")}
    ${limit ? "" : `<p class="mute" style="margin:10px 0 0">${t("home.noLimHint")}</p>`}
  </section>
  <div class="quick" role="group" aria-label="${esc(t("now"))}">
    <button type="button" id="lock" class="tile act"></button><button type="button" id="ring" class="tile act"></button><button type="button" id="loc" class="tile act"></button>
    <button type="button" id="scrGo" class="tile" ${navOk("screen") ? "" : "disabled"}><b>${ic("smartphone")}</b><span>${t("tab.screen")}</span></button>
    <button type="button" id="moreAct" class="tile" aria-haspopup="dialog"><b>${ic("ellipsis")}</b><span>${t("nav.more")}</span></button>
  </div>
  <div id="strip"></div>
  ${awayTip()}
  </div><div>
  <section class="list mt16" id="phoneCard" aria-label="${esc(t("pc.title"))}">${phoneCard(onlineNow())}</section>
  <div class="list mt16"><div class="lhead"><h2>${ic("flame")} ${t("home.topToday")}</h2></div>
    ${top.map(x => `<div class="li">${icon(x)}<span class="x"><b>${esc(x.label)}</b><span class="mbar"><i style="width:${Math.round(x.ms / mx * 100)}%"></i></span></span><span class="v">${fmt(x.ms)}</span></div>`).join("") || `<div class="empty"><span class="e">${ic("sprout")}</span>${t("none")}</div>`}
    <button type="button" class="lfoot" data-go="apps">${t("home.allApps")} ${ic("chevron-right")}</button></div>
  <div class="list"><div class="lhead"><h2>${ic("bell")} ${t("home.recent")}</h2></div>
    ${ev.map(e => `<div class="li"><span class="ib">${ic(EV_IC[e.kind] || "bell")}</span><span class="x"><b style="white-space:normal">${esc(noEmoji(e.text))}</b><span class="sub mute">${ago(e.t)}${e.shot ? ` <button type="button" class="chip" data-shot="${esc(e.shot)}">${ic("camera")} ${t("shot.btn")}</button>` : ""}</span></span></div>`).join("") || `<div class="empty"><span class="e">${ic("inbox")}</span>${t("empty")}</div>`}
    <button type="button" class="lfoot" data-go="log">${t("tab.log")} ${ic("chevron-right")}</button></div>
  </div></div>`;
  wireReq(b, today, false);
  if ($("#awayGo")) $("#awayGo").onclick = () => { tab = "set"; render(true); const c = $("#awaySec"); if (c) c.scrollIntoView({ block: "start" }); };
  if ($("#awayNo")) $("#awayNo").onclick = () => { kidFlag(cur, "away", 1); render(); };
  b.querySelectorAll("[data-go]").forEach(x => x.onclick = () => goTab(x.dataset.go));
  if ($("#blkGo")) $("#blkGo").onclick = () => { sel.f = "blocked"; appsSub = "list"; goTab("apps"); };
  if ($("#bonusGo")) $("#bonusGo").onclick = () => pickTime("bonus", bonusMin ? t("pk.bonusNow", bonusMin) : "",
    m => { const c0 = edit.bonus && edit.bonus.day === today ? edit.bonus.min : 0; edit.bonus = { day: today, min: c0 + m }; changed(); toast("ok|" + t("pk.bonusDone", fmt(m * 60000))); render(); },
    bonusMin ? () => { edit.bonus = { day: today, min: 0 }; changed(); render(); } : null);
  $("#pauseGo").onclick = () => pickTime("pause", pausedLeft > 0 ? t("pk.active", whenTxt(r.pausedUntil)) + " · " + t("pk.leftN", fmt(pausedLeft)) : "",
    until => { edit.pausedUntil = until; changed(); toast("ok|" + t("pk.sumPause", whenTxt(until))); render(); },
    pausedLeft > 0 ? () => { edit.pausedUntil = 0; changed(); toast("ok|" + t("pk.unpaused")); render(); } : null);
  $("#lock").onclick = () => act("lock", () => screenOff() ? cmd({ type: "wake" }, t("wake.done"), false) : cmd({ type: "lock" }, t("lock.done"), false), screenOff() ? null : askOf("lock"));
  $("#ring").onclick = () => ringOn() ? ringStop() : earOn() ? toast("warn|" + t("act.noRing")) : act("ring", async () => { const r = await cmd({ type: "ring" }, t("ring.done"), false); if (r && r.result !== "queued") expect("ring", true, "ring"); return r; }, askOf("ring"));
  $("#loc").onclick = () => act("loc", () => cmd({ type: "locate" }, t("locate.done"), false), askOf("locate"));
  $("#scrGo").onclick = () => goTab("screen");
  $("#moreAct").onclick = moreSheet;
  actPaint();
}

// device log lines "Lệnh: <type> → <result>" (the phone writes the prefix in Vietnamese, type/result as English codes): keys "cl.<type>", "cr.<result>".
// Every other log line is free text written by the phone and is shown as is.
function logTr(m) { const x = /^Lệnh: (\S+) → (.+)$/.exec(m); return x ? t("cl", tc("cl.", x[1], x[1]), tc("cr.", x[2], x[2])) : m; }
function drawHist() {
  const days = (st.history || []).slice().reverse().concat([{ day: st.day, ms: st.usedMs, top: st.apps.filter(x => x.ms > 0).slice(0, 5) }]);
  const mx = Math.max(60000, ...days.map(d => d.ms)), WD = t("wd");
  if (histSel < 0 || histSel >= days.length) histSel = days.length - 1;
  const show = () => {
    const d = days[histSel];
    $("#histPick").innerHTML = dayBtn("hist", { sel: d.day, days: days.map(x => x.day), onPick: k => { histSel = days.findIndex(x => x.day === k); show(); } }); dpBind($("#histPick"));
    $("#hist").querySelectorAll("button").forEach((x, i) => x.classList.toggle("on", i === histSel));
    $("#histTop").innerHTML = `<b>${esc(dm(d.day))}: ${fmt(d.ms)}</b>` + ((d.top || []).length ? (d.top || []).map(a => `<div>${esc(a.label || a.pkg)} · ${fmt(a.ms)}</div>`).join("") : "<div>" + ic("circle-dashed") + " " + t("noAppData") + "</div>");
  };
  $("#hist").innerHTML = days.map((d, i) => `<button data-h="${i}" aria-label="${esc(dm(d.day))}: ${fmt(d.ms)}"><i style="height:${Math.max(2, Math.round(d.ms / mx * 90))}px"></i>${WD[dayIdx(d.day)]}</button>`).join("");
  $("#hist").querySelectorAll("button").forEach(x => x.onclick = () => { histSel = +x.dataset.h; show(); });
  show();
}

function icon(x) {
  const l = ((x.label || "?").trim()[0] || "?").toUpperCase(), letter = esc(l), hue = (l.charCodeAt(0) * 47) % 360;
  const st0 = `background:hsl(${hue} 55% 45%);color:#fff`;
  // LAN: the phone draws it; relay: the 48 px copy the phone uploaded; app mode: same paths through the proxy.
  // lazy: a 200-app list fetches only the ~10 on screen first (LAN = one phone-side draw per icon); cached 24 h after
  return `<span class="ic" style="${st0}"><img alt="" loading="lazy" decoding="async" data-l="${letter}" src="${base(cur)}/icon?pkg=${encodeURIComponent(x.pkg)}&k=${encodeURIComponent(cur.token)}" onload="this.parentNode.style.background='none'" onerror="this.parentNode.textContent=this.dataset.l"></span>`;
}
function appRow(x) {
  return `<div class="app">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${fmt(x.ms)}${x.suspended || x.blocked ? `<span class="tag bad">${reason(x.blocked)}</span>` : ""}</span></div></div>`;
}

// Collapsible section: a heading button (aria-expanded) over a body that stays in the DOM (controls keep their wiring).
// Open/closed per id is remembered in localStorage "kg.sec" ({id: 1|0}); def = open when never toggled.
const secOpen = (() => { try { const o = JSON.parse(localStorage.getItem("kg.sec") || "{}"); return o && typeof o === "object" ? o : {}; } catch { return {}; } })();
const isOpen = (id, def) => id in secOpen ? !!secOpen[id] : !!def;
const chipHtml = c => c ? `<span class="tag ${c[1] || ""}" data-chip>${c[0]}</span>` : `<span data-chip></span>`;
function sec(id, icn, title, desc, chip, body, def) {
  const o = isOpen(id, def), g = FEAT_SEC[id] ? gateNote(FEAT_SEC[id]) : "";
  if (g) body = g + `<div class="gated">${body}</div>`;
  return `<section class="card cl${o ? " open" : ""}${g ? " gate" : ""}" data-sec="${id}"><h2><button type="button" class="clh" id="sh-${id}" aria-expanded="${o}" aria-controls="sb-${id}">
    <span class="cli">${ic(icn)}</span><span class="clt"><span class="clr"><b>${title}</b>${chipHtml(chip)}</span>${desc ? `<span>${desc}</span>` : ""}</span><svg class="si chev" aria-hidden="true"><use href="#i-chevron-down"/></svg></button></h2>
    <div class="clb" id="sb-${id}" role="region" aria-labelledby="sh-${id}"><div><div>${body}</div></div></div></section>`;
}
const setChip = (root, id, c) => { const el = root.querySelector(`[data-sec="${id}"] [data-chip]`); if (el) el.outerHTML = chipHtml(c); };
document.addEventListener("click", e => {
  const h = e.target.closest && e.target.closest(".clh"); if (!h) return;
  const s = h.closest(".cl"), o = !s.classList.contains("open");
  s.classList.toggle("open", o); h.setAttribute("aria-expanded", o);
  secOpen[s.dataset.sec] = o ? 1 : 0; try { localStorage.setItem("kg.sec", JSON.stringify(secOpen)); } catch {}
});

// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// warning under a time range (bedtime / schedule) that would never block anything
function timeWarn(x, on, sch) {
  if (!on) return "";
  const m = [];
  if (!x.start || !x.end) m.push(t("tw.missing"));
  else if (x.start === x.end) m.push(t("tw.same"));
  if (sch && !(x.days || []).length) m.push(t("tw.noDays"));
  return m.map(w => `<div class="alert warn" style="margin-top:8px">${ic("triangle-alert")} ${w}</div>`).join("");
}
// ---- v0.9.7 wind-down + messages on the kid's phone (rules tab) ----
function windChip(r) {
  const w = r.windDown, ws = st && st.windDown, bad = !r.bedtime.on || (ws && (ws.perm === false || ws.err));
  if (!w.gray && !w.nudge) return [t("off")];
  return [t("on") + " · " + [w.gray && t("chip.gray"), w.nudge && t("chip.nudge")].filter(Boolean).join(", "), bad ? "warn" : "ok"];
}
function windStatus(r) {   // idle / active / failure / conflict, below the controls
  const w = r.windDown, ws = st && st.windDown, saved = st && st.rules && st.rules.windDown, out = [];
  const al = (k, i, m) => `<div class="alert ${k}" style="margin-top:8px">${ic(i)} ${m}</div>`;
  if ((w.gray || w.nudge) && !r.bedtime.on) out.push(al("warn", "triangle-alert", t("wd.needBed")));
  if (w.gray && ws && ws.perm === false) out.push(al("warn", "triangle-alert", t("wd.perm")));
  else if (w.gray && ws && ws.err) out.push(al("warn", "triangle-alert", t("wd.fail", esc(ws.err))));
  if (saved && (saved.gray !== w.gray || +saved.min !== +w.min || saved.nudge !== w.nudge)) out.push(`<p class="mute">${ic("hourglass")} ${t("wd.unsaved")}</p>`);
  else if (w.gray && r.bedtime.on && ws) {
    const s = String(r.bedtime.start || "21:30").split(":").map(Number), m = ((s[0] * 60 + s[1] - w.min) % 1440 + 1440) % 1440;
    out.push(ws.gray ? `<p><span class="tag ok">${ic("moon")} ${t("wd.nowOn")}</span></p>` : `<p class="mute">${ic("clock")} ${t("wd.wait", String(Math.floor(m / 60)).padStart(2, "0") + ":" + String(m % 60).padStart(2, "0"))}</p>`);
  }
  return out.join("");
}
function windBody(r) {
  const w = r.windDown;
  return `    <label class="row"><input type="checkbox" id="f-wg" ${w.gray ? "checked" : ""}><span class="sp">${ic("moon")} ${t("wd.gray")}</span></label>
    <label class="row" style="margin-top:8px">${t("wd.lead")} <select id="f-wm" ${w.gray ? "" : "disabled"}>${WD_STEPS.map(n => `<option value="${n}" ${w.min === n ? "selected" : ""}>${n ? t("wd.leadN", n) : t("wd.at")}</option>`).join("")}</select></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-wn" ${w.nudge ? "checked" : ""}><span class="sp">${ic("bell")} ${t("wd.nudge")}</span></label>
    <div id="wdS">${windStatus(r)}</div>
    <p class="mute">${ic("lightbulb")} ${t("wd.tip")}</p>`;
}
const lockPrev = r => [r.lockInfo.msg, r.lockInfo.contact].map(s => s.replace(/\s+/g, " ").trim()).filter(Boolean).join(" · ");
function kindStatus(r) {
  const p = lockPrev(r), basic = isBasic();
  return (p ? `<p class="mute">${ic("eye")} ${t("kn.preview", esc(p))}</p>` : `<p class="mute">${t("kn.noLock")}</p>`) + (basic ? needOwner(t("kn.lock")) : "");
}
function kindBody(r) {
  return `    <b>${ic("lock")} ${t("kn.lock")}</b>
    <div class="row" style="margin-top:8px"><input class="sp" id="f-lm" maxlength="60" autocomplete="off" placeholder="${esc(t("kn.msgPh"))}" aria-label="${esc(t("kn.msg"))}" value="${esc(r.lockInfo.msg)}"></div>
    <div class="row" style="margin-top:8px"><input class="sp" id="f-lc" maxlength="60" autocomplete="off" inputmode="text" placeholder="${esc(t("kn.contactPh"))}" aria-label="${esc(t("kn.contact"))}" value="${esc(r.lockInfo.contact)}"></div>
    <div id="knS">${kindStatus(r)}</div>
    <label for="f-bnote" style="display:block;margin-top:12px"><b>${ic("notebook-pen")} ${t("kn.note")}</b></label>
    <input id="f-bnote" maxlength="120" autocomplete="off" placeholder="${esc(t("kn.notePh"))}" style="width:100%;margin-top:8px" value="${esc(r.blockNote)}">
    <p class="mute">${t("kn.noteDef")}</p>
    <p class="mute">${ic("lightbulb")} ${t("kn.tip")}</p>`;
}
function wireComfort(b, r) {
  const $ = s => b.querySelector(s) || document.createElement("i");
  const ws = () => { const e = $("#wdS"); if (e) e.innerHTML = windStatus(r); };
  $("#f-wg").onchange = e => { r.windDown.gray = e.target.checked; $("#f-wm").disabled = !e.target.checked; ws(); changed(); };
  $("#f-wm").onchange = e => { r.windDown.min = +e.target.value; ws(); changed(); };
  $("#f-wn").onchange = e => { r.windDown.nudge = e.target.checked; ws(); changed(); };
  $("#f-bed").addEventListener("change", ws);
  const ks = () => { const e = $("#knS"); if (e) e.innerHTML = kindStatus(r); };
  $("#f-lm").oninput = e => { r.lockInfo.msg = e.target.value.slice(0, 60); ks(); changed(); };
  $("#f-lc").oninput = e => { r.lockInfo.contact = e.target.value.slice(0, 60); ks(); changed(); };
  $("#f-bnote").oninput = e => { r.blockNote = e.target.value.slice(0, 120); changed(); };
}

// one-line status chip per rules section: [text, tone] (tone: ok = active, warn = active but has a problem)
function ruleChips(r) {
  const on = (x, w) => [t("on") + (x ? " · " + x : ""), w ? "warn" : "ok"], off = [t("off")], none = [t("chip.none")];
  const hm = s => esc(s || "?"), basic = isBasic();
  const dns = DNS().find(d => d[0] === r.safeDns);
  const schOn = r.schedules.filter(x => x.on), more = [r.blockNew, r.lockLocation, r.noAirplane].filter(Boolean).length, anti = [r.noInstall, r.noDebug].filter(Boolean).length;
  return {
    tpl: [t("chip.nTpl", allTpl().length)],
    daily: r.dailyByDay.length ? on(t("daily.by")) : r.dailyMin ? on(t("chip.perDay", fmt(r.dailyMin * 60000))) : off,
    grp: r.groups.length ? [t("chip.nGrp", r.groups.length), r.groups.some(g => !(g.pkgs || []).length) ? "warn" : "ok"] : none,
    totp: st.totpOn === undefined ? null : st.totpOn ? on() : off,
    chore: r.chores.length ? [t("chip.nChore", r.chores.length), "ok"] : none,
    bed: r.bedtime.on ? on(hm(r.bedtime.start) + "–" + hm(r.bedtime.end), !!timeWarn(r.bedtime, true)) : off,
    sch: schOn.length ? [t("chip.nSch", schOn.length), schOn.some(x => timeWarn(x, true, true)) ? "warn" : "ok"] : r.schedules.length ? off : none,
    zone: r.zones.length ? [t("chip.nZone", r.zones.length), "ok"] : none,
    trk: r.trackMin ? on(t("chip.every", fmt(r.trackMin * 60000))) : off,
    more: more ? on(more + "/3", basic) : off,
    dns: r.safeDns ? on(esc(dns ? dns[1] : r.safeDns), basic) : off,
    anti: anti ? on(anti + "/2", basic) : off,
    wind: windChip(r),
    home: r.launcher.on ? on(t({ be: "hm.sBe", teen: "hm.sTeen", gon: "hm.sGon" }[r.launcher.style] || "hm.sAuto"), basic || !!(st.launcher && st.launcher.err)) : off,
    kind: (n => n.length ? [n.join(" · "), "ok"] : off)([(r.lockInfo.msg || r.lockInfo.contact) && t("chip.lock"), r.blockNote && t("chip.note")].filter(Boolean)),
  };
}
// Rules: two sub-tabs (time / blocking & safety). Zones + location log live in the "Vị trí" tab, the Authenticator in
// "Cài đặt"; the same section HTML (ruleSecs) and wiring (rulesWire) serve all three, wiring only what is on screen.
let rseg = (() => { try { return localStorage.getItem("kg.rseg") === "safe" ? "safe" : "time"; } catch { return "time"; } })();
const RSEG = { time: ["daily", "grp", "bed", "wind", "sch", "chore"], safe: ["more", "dns", "anti", "home", "kind"] };
// v0.9.9 kid launcher: the phone answers with state.launcher {on, home, err, style, locked, pages}
function homeStatus(r) {
  const L = st.launcher;
  if (!r.launcher.on && !(L && L.on)) return "";
  const [k, w] = !L ? ["hm.stOld", 1] : L.err === "not owner" ? ["hm.stNoOwner", 1] : L.err === "crash" ? ["hm.stCrash", 1] : L.err === "home" ? ["hm.stHome", 1]
    : L.on && r.launcher.on ? ["hm.stOn", 0] : ["hm.stWait", 0];
  return `<p class="${w ? "" : "mute"}" id="hmSt">${ic(w ? "triangle-alert" : k === "hm.stOn" ? "circle-check" : "loader")} ${t(k)}</p>`;
}
// the kid's own notes marked "Bố mẹ xem được" on their phone (state.notes, newest first); read-only here
function kidNotes() {
  const n = Array.isArray(st.notes) ? st.notes.filter(x => x && typeof x.text === "string") : [];
  if (!n.length) return "";
  return `<div style="margin-top:10px"><b>${ic("notebook-pen")} ${t("hm.kidNotes")}</b>${n.slice(0, 20).map(x =>
    `<p style="margin:6px 0 0;white-space:pre-wrap;overflow-wrap:anywhere">${esc(x.text)}<br><span class="mute">${esc(new Date(+x.t || 0).toLocaleString())}</span></p>`).join("")}</div>`;
}
function homeBody(r) {
  const L = r.launcher, opt = (v, k) => `<option value="${v}" ${L.style === v ? "selected" : ""}>${t(k)}</option>`;
  return `    <label class="row"><input type="checkbox" id="f-hm" ${L.on ? "checked" : ""}><span class="sp">${ic("house")} ${t("hm.on")}</span></label>
    <label class="row" style="margin-top:8px">${t("hm.style")} <select id="f-hms" aria-label="${esc(t("hm.style"))}">${opt("", "hm.sAuto")}${opt("be", "hm.sBe")}${opt("teen", "hm.sTeen")}${opt("gon", "hm.sGon")}</select></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-hmset" ${L.settings ? "checked" : ""}><span class="sp">${ic("settings")} ${t("hm.settings")}</span></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-hmn" ${L.notes ? "checked" : ""}><span class="sp">${ic("message-square")} ${t("hm.notes")}</span></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-hmlock" ${L.lockCustom ? "checked" : ""}><span class="sp">${ic("lock")} ${t("hm.lock")}</span></label>
    ${homeStatus(r)}${kidNotes()}
    <p class="mute">${ic("lightbulb")} ${t("hm.tip")}</p>${isBasic() ? needOwner(t("hm.owner")) : ""}`;
}
function ruleSecs(r) {
  const C = ruleChips(r);
  return {
    daily: sec("r-daily", "timer", t("daily.h"), t("sd.daily"), C.daily, `    <div class="row"><select id="f-dmode" aria-label="${esc(t("daily.h"))}"><option value="same" ${r.dailyByDay.length ? "" : "selected"}>${t("daily.same")}</option><option value="by" ${r.dailyByDay.length ? "selected" : ""}>${t("daily.by")}</option></select></div>
    ${r.dailyByDay.length ? `<div class="grid" style="margin-top:8px">${t("wdl").map((n, i) => `<label class="mute">${t("daily.dayLbl", n)}<input type="number" min="0" max="1440" step="15" inputmode="numeric" data-d="${i}" id="f-d${i}" value="${+r.dailyByDay[i] || 0}" style="width:100%"></label>`).join("")}</div>`
    : `<div class="row" style="margin-top:8px"><select id="f-daily" aria-label="${esc(t("daily.h"))}">${[0, 30, 60, 90, 120, 150, 180, 240, 300].map(n => `<option value="${n}" ${r.dailyMin === n ? "selected" : ""}>${n ? fmt(n * 60000) : t("noLimitC")}</option>`).join("")}
    ${[0, 30, 60, 90, 120, 150, 180, 240, 300].includes(r.dailyMin) ? "" : `<option selected value="${r.dailyMin}">${fmt(r.dailyMin * 60000)}</option>`}</select></div>`}
    <p class="mute">${ic("lightbulb")} ${t("daily.tip")}</p>`),
    grp: sec("r-grp", "layout-grid", t("grp.h"), t("sd.grp"), C.grp, `    ${r.groups.map((g, i) => { const h = (g.hours || [])[0], have = new Set(g.pkgs || []), list = (st.apps || []).map(a => [a.pkg, a.label]).concat((g.pkgs || []).filter(p => !(st.apps || []).some(a => a.pkg === p)).map(p => [p, p]));
      return `<div class="sch" data-g="${i}"><div class="row"><input class="sp" id="f-gn${i}" maxlength="40" placeholder="${esc(t("grp.namePh"))}" aria-label="${esc(t("grp.name"))}" value="${esc(g.name)}"><button class="bad" data-gdel="${i}" aria-label="${esc(t("grp.del"))}">${ic("trash-2")}</button></div>
      <label class="row" style="margin-top:8px">${t("grp.max")} <input type="number" id="f-gm${i}" min="0" max="1440" step="15" inputmode="numeric" style="width:5.5em" value="${+g.min || 0}"> ${t("minPerDay")} <span class="mute">${t("zeroNoLim")}</span></label>
      <label class="row" style="margin-top:8px"><input type="checkbox" id="g-hon${i}" ${h ? "checked" : ""}><span class="sp">${ic("clock")} ${t("grp.hours")}</span></label>
      ${h ? `<div class="chips" style="margin:8px 0">${t("wd").map((n, d) => `<button data-gday="${d + 1}" class="${(h.days || []).includes(d + 1) ? "on" : ""}" aria-pressed="${(h.days || []).includes(d + 1)}">${n}</button>`).join("")}</div>
      <span class="row tm">${t("from")} <input type="time" id="f-gs${i}" aria-label="${esc(t("start"))}" value="${esc(h.start)}"> ${t("to")} <input type="time" id="f-ge${i}" aria-label="${esc(t("end"))}" value="${esc(h.end)}"></span>${g.hours.length > 1 ? `<p class="mute">${t("grp.moreHours", g.hours.length - 1)}</p>` : ""}` : ""}
      <details data-gopen="${esc(g.id)}" ${gOpen.has(g.id) || !have.size ? "open" : ""} style="margin-top:8px"><summary>${grpSum(have.size)}</summary>
      <div style="max-height:260px;overflow:auto;margin-top:6px">${list.map(([p, l]) => `<label class="row"><input type="checkbox" data-gp="${esc(p)}" ${have.has(p) ? "checked" : ""}><span class="sp">${esc(l)}</span></label>`).join("") || `<p class="mute">${t("grp.noList")}</p>`}</div></details>
      ${have.size ? "" : `<p class="mute">${ic("triangle-alert")} ${t("grp.empty")}</p>`}</div>`; }).join("") || `<p class="mute">${ic("inbox")} ${t("grp.none")}</p>`}
    <button id="addGroup" ${r.groups.length >= 10 ? "disabled" : ""}>${ic("plus")} ${t("grp.add")}</button>
    <p class="mute">${ic("lightbulb")} ${t("grp.tip")}</p>`),
    totp: sec("r-totp", "key-round", t("totp.h"), t("sd.totp"), C.totp, `    ${st.totpOn === undefined ? `<p class="mute">${ic("triangle-alert")} ${t("totp.old")}</p>` : st.totpOn
      ? `<p>${ic("circle-check")} ${t("totp.onP")}${st.unlockUntil > Date.now() ? " " + t("totp.until", hhmm(st.unlockUntil)) : ""}</p>
        <div class="row"><button class="sp" id="totpNew">${ic("refresh-cw")} ${t("totp.new")}</button><button class="bad" id="totpOff">${ic("x")} ${t("turnOff")}</button></div>`
      : `<p class="mute">${t("totp.offP")}</p><button class="pri" id="totpNew">${ic("key-round")} ${t("totp.enable")}</button>`}
    <label class="row" style="margin-top:10px">${t("totp.each")} <select id="f-um">${[15, 30, 60, 120].map(n => `<option value="${n}" ${+r.unlockMin === n ? "selected" : ""}>${fmt(n * 60000)}</option>`).join("")}</select></label>
    <div id="totpOut">${totpShown && totpShown.k === cur ? totpHtml(totpShown) : ""}</div>
    <p class="mute">${ic("lightbulb")} ${t("totp.tip")}</p>`),
    chore: sec("r-chore", "star", t("chore.h"), t("sd.chore"), C.chore, `    ${r.chores.map((c, i) => `<div class="app" data-c="${i}"><input class="sp" id="f-cn${i}" data-f="name" maxlength="40" placeholder="${esc(t("chore.namePh"))}" aria-label="${esc(t("chore.name"))}" value="${esc(c.name)}"><label class="row">+<input type="number" id="f-cm${i}" data-f="min" min="5" max="120" step="5" inputmode="numeric" aria-label="${esc(t("minutes"))}" style="width:5.5em" value="${+c.min || 20}"> ${t("minShort")}</label><button class="bad" data-cdel="${i}" aria-label="${esc(t("chore.del"))}">${ic("trash-2")}</button></div>`).join("") || `<p class="mute">${ic("inbox")} ${t("chore.none")}</p>`}
    <button id="addChore" ${r.chores.length >= 20 ? "disabled" : ""}>${ic("plus")} ${t("chore.add")}</button>
    <p class="mute">${ic("lightbulb")} ${t("chore.tip")}</p>`),
    bed: sec("r-bed", "moon", t("bed.h"), t("sd.bed"), C.bed, `    <div class="row"><label class="row"><input type="checkbox" id="f-bed" ${r.bedtime.on ? "checked" : ""}> ${ic("moon")} ${t("on")}</label>
    <span class="sp"></span><span class="row tm">${t("from")} <input type="time" id="f-bs" aria-label="${esc(t("start"))}" value="${esc(r.bedtime.start)}"> ${t("to")} <input type="time" id="f-be" aria-label="${esc(t("end"))}" value="${esc(r.bedtime.end)}"></span></div>
    <div id="bedW">${timeWarn(r.bedtime, r.bedtime.on)}</div>`),
    wind: sec("r-wind", "moon", t("wd.h"), t("sd.wind"), C.wind, windBody(r)),
    kind: sec("r-kind", "notebook-pen", t("kn.h"), t("sd.kind"), C.kind, kindBody(r)),
    home: sec("r-home", "house", t("hm.h"), t("sd.home"), C.home, homeBody(r)),
    sch: sec("r-sch", "book-open", t("sch.h"), t("sd.sch"), C.sch, `    ${r.schedules.map((x, i) => `<div class="sch" data-s="${i}">
      <div class="row"><input id="f-sn${i}" class="sp" data-f="name" placeholder="${esc(t("sch.namePh"))}" aria-label="${esc(t("sch.name"))}" value="${esc(x.name)}"><label class="row"><input type="checkbox" data-f="on" ${x.on ? "checked" : ""}> ${t("on")}</label></div>
      <div class="chips" style="margin:8px 0">${t("wd").map((n, d) => `<button data-day="${d + 1}" class="${(x.days || []).includes(d + 1) ? "on" : ""}" aria-pressed="${(x.days || []).includes(d + 1)}">${n}</button>`).join("")}</div>
      <div class="row"><span class="row tm">${t("from")} <input type="time" id="f-ss${i}" data-f="start" aria-label="${esc(t("start"))}" value="${esc(x.start)}"> ${t("to")} <input type="time" id="f-se${i}" data-f="end" aria-label="${esc(t("end"))}" value="${esc(x.end)}"></span><span class="sp"></span><button class="bad" data-del="${i}">${ic("trash-2")} ${t("delete")}</button></div><div data-sw>${timeWarn(x, x.on, true)}</div></div>`).join("") || `<p class="mute">${ic("inbox")} ${t("sch.none")}</p>`}
    <button id="addSch">${ic("plus")} ${t("sch.add")}</button>`),
    zone: sec("r-zone", "map-pin", t("zone.h"), t("sd.zone"), C.zone, `    ${r.zones.map((z, i) => `<div class="sch zrow" data-z="${i}"><span class="ib">${ic("map-pin")}</span><span class="x"><b>${esc(z.name || t("zone.newName"))}</b><span class="mute">${t("zone.r")} ${+z.r || 150} m${zoneOk(z) ? "" : " · " + t("zone.badCoord")}</span></span><button data-zedit="${i}">${ic("map")} ${t("zone.editB")}</button><button class="bad" data-zdel="${i}" aria-label="${esc(t("delete"))}">${ic("trash-2")}</button></div>`).join("") || `<p class="mute">${ic("inbox")} ${t("zone.none")}</p>`}
    <button id="addZone" ${r.zones.length >= 10 ? "disabled" : ""}>${ic("plus")} ${t("zone.add")}</button>
    <p class="mute">${ic("lightbulb")} ${t("zone.tip")}</p>`),
    trk: sec("r-trk", "route", t("trk.h"), t("sd.trk"), C.trk, `    <select id="f-tm" aria-label="${esc(t("trk.h"))}">${[0, 5, 10, 15, 30, 60].map(v => `<option value="${v}" ${r.trackMin === v ? "selected" : ""}>${v ? fmt(v * 60000) : t("off")}</option>`).join("")}${[0, 5, 10, 15, 30, 60].includes(r.trackMin) ? "" : `<option selected value="${r.trackMin}">${fmt(r.trackMin * 60000)}</option>`}</select>
    <p class="mute">${ic("map")} ${t("trk.tip")}</p>`),
    more: sec("r-more", "construction", t("more.h"), t("sd.more"), C.more, `    <label class="row"><input type="checkbox" id="f-bn" ${r.blockNew ? "checked" : ""}><span class="sp">${ic("sparkles")} ${t("more.new")}</span></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-ll" ${r.lockLocation ? "checked" : ""}><span class="sp">${ic("map-pin")} ${t("more.loc")}</span></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-na" ${r.noAirplane ? "checked" : ""}><span class="sp">${ic("plane")} ${t("more.air")}</span></label>${isBasic() ? needOwner(t("no.locAir")) : ""}`),
    dns: sec("r-dns", "shield", t("dns.h"), t("sd.dns"), C.dns, `    <select id="f-dns" aria-label="${esc(t("dns.h"))}">${DNS().map(([v, n]) => `<option value="${v}" ${r.safeDns === v ? "selected" : ""}>${n}</option>`).join("")}${DNS().some(d => d[0] === r.safeDns) ? "" : `<option selected value="${esc(r.safeDns)}">${esc(r.safeDns)}</option>`}</select>
    <p class="mute">${ic("eye-off")} ${t("dns.tip")}</p>${isBasic() ? needOwner() : ""}`),
    anti: sec("r-anti", "lock-keyhole", t("anti.h"), t("sd.anti"), C.anti, `    <label class="row"><input type="checkbox" id="f-ni" ${r.noInstall ? "checked" : ""}><span class="sp">${ic("package")} ${t("anti.install")}</span></label>
    <label class="row" style="margin-top:8px"><input type="checkbox" id="f-nd" ${r.noDebug ? "checked" : ""}><span class="sp">${ic("code")} ${t("anti.debug")}</span></label>
    ${isBasic() ? needOwner() + `<p class="mute">${ic("shield")} ${t("anti.basic")}</p>`
      : `<p class="mute">${ic("lock")} ${t("anti.owner")}</p>`}`),
  };
}
function rulesTab(b) {
  const r = edit, S = ruleSecs(r), segs = [["time", "timer", t("rules.segTime")], ["safe", "shield", t("rules.segSafe")]];
  b.innerHTML = `<div class="rtop"><div class="seg" role="tablist" aria-label="${esc(t("tab.rules"))}">${segs.map(([id, i, n]) => `<button type="button" role="tab" data-rseg="${id}" aria-selected="${rseg === id}">${ic(i)}<span>${n}</span></button>`).join("")}</div>
    <button type="button" class="pill" id="tplOpen" aria-haspopup="dialog">${ic("layers")} ${t("tpl.apply")}</button></div>
    <div id="rsx" role="tabpanel">${RSEG[rseg].map(k => S[k]).join("")}</div>`;
  b.querySelectorAll("[data-rseg]").forEach(x => x.onclick = () => { rseg = x.dataset.rseg; try { localStorage.setItem("kg.rseg", rseg); } catch {} rulesTab(b); const f = b.querySelector(`[data-rseg="${rseg}"]`); if (f) f.focus(); });
  $("#tplOpen").onclick = tplSheet;
  rulesWire($("#rsx"), r);
}
// "Áp mẫu…": the templates card as a sheet
function tplSheet() {
  const body = () => { const T = allTpl(); if (tplSel >= T.length) tplSel = 0;
    const tcur = T[tplSel], nUser = T.length - PRESET_D.length, multi = reachKids().length > 1;
    return { tcur, html: `<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("layers", t("tpl.h"))}    <p class="mute" style="margin:0 0 8px">${t("tpl.p")}</p>
    <div class="chips" role="group" aria-label="${esc(t("tpl.h"))}">${T.map((t, i) => `<button data-tpl="${i}" class="${i === tplSel ? "on" : ""}" aria-pressed="${i === tplSel}">${esc(t.name)}</button>`).join("")}</div>
    <div class="mute" style="margin-top:8px">${esc(tcur.name)}: ${tplSummary(tcur)}</div>
    <div class="row" style="margin-top:10px"><button class="pri sp" id="tplGo">${ic("check")} ${t("tpl.applyTo", esc(cur.name))}</button>${multi ? `<button class="sp" id="tplMulti">${ic("clipboard-copy")} ${t("tpl.applyMany")}</button>` : ""}</div>
    <div class="row" style="margin-top:8px"><button class="sp" id="tplSave">${ic("save")} ${t("tpl.saveCur")}</button>${tplSel >= PRESET_D.length ? `<button class="bad" id="tplDel">${ic("trash-2")} ${t("tpl.del")}</button>` : ""}</div>
    ${nUser ? "" : `<p class="mute">${t("tpl.noOwn")}</p>`}</div>` }; };
  const wire = (pop, done) => {
    const { tcur } = body();
    pop.querySelector("#askNo").onclick = () => done(false);
    pop.querySelectorAll("[data-tpl]").forEach(x => x.onclick = () => { tplSel = +x.dataset.tpl; pop.innerHTML = body().html; wire(pop, done); const f = pop.querySelector(`[data-tpl="${tplSel}"]`); if (f) f.focus(); });
    pop.querySelector("#tplGo").onclick = () => tplApply(tcur);
    if (pop.querySelector("#tplMulti")) pop.querySelector("#tplMulti").onclick = () => tplMulti(tcur);
    pop.querySelector("#tplSave").onclick = tplSave;
    if (pop.querySelector("#tplDel")) pop.querySelector("#tplDel").onclick = async () => {
      if (!(await ask(t("tpl.askDel", tcur.name), t("delete"), true))) return;
      saveTpl(userTpl().filter(t => t.name !== tcur.name)); tplSel = 0; if (cur) render();
    };
  };
  sheet(body().html, wire);
}
function rulesWire(b, r) {
  // only some sections are on screen: a missing control gets a throwaway element instead of a null
  const $ = s => b.querySelector(s) || document.createElement("i");
  // chips follow edits that don't redraw the tab (selects, checkboxes, typing)
  const chips = () => setTimeout(() => { if (!b.isConnected || !edit) return; const c = ruleChips(edit); Object.keys(c).forEach(k => setChip(b, "r-" + k, c[k])); });
  b.addEventListener("change", chips); b.addEventListener("input", chips);
  b.querySelectorAll("[data-g]").forEach(box => {
    const i = +box.dataset.g, g = r.groups[i];
    $("#f-gn" + i).oninput = e => { g.name = e.target.value.slice(0, 40); changed(); };
    $("#f-gm" + i).onchange = e => { g.min = clampN(e.target.value, 0, 1440, 0); e.target.value = g.min; changed(); };
    $("#g-hon" + i).onchange = e => { g.hours = e.target.checked ? [{ days: [1, 2, 3, 4, 5, 6, 7], start: "19:00", end: "20:00" }] : []; changed(); document.activeElement.blur(); render(); };
    const h = (g.hours || [])[0];
    if (h) {
      box.querySelectorAll("[data-gday]").forEach(d => d.onclick = () => { const n = +d.dataset.gday; h.days = h.days.includes(n) ? h.days.filter(v => v !== n) : h.days.concat(n).sort(); if (!h.days.length) { toast("warn|" + t("grp.oneDay")); h.days = [n]; } changed(); document.activeElement.blur(); render(); });
      $("#f-gs" + i).onchange = e => { h.start = e.target.value || h.start; changed(); };
      $("#f-ge" + i).onchange = e => { h.end = e.target.value || h.end; changed(); };
    }
    const det = box.querySelector("[data-gopen]");
    det.ontoggle = () => { det.open ? gOpen.add(g.id) : gOpen.delete(g.id); };
    box.querySelectorAll("[data-gp]").forEach(c => c.onchange = () => {
      const p = c.dataset.gp, s = new Set(g.pkgs || []);
      c.checked ? s.add(p) : s.delete(p);
      if (s.size > 100) { c.checked = false; return toast("warn|" + t("grp.max100")); }
      g.pkgs = [...s]; changed();
      box.querySelector("summary").textContent = grpSum(s.size);
    });
    box.querySelector("[data-gdel]").onclick = async () => { if (!(await ask(t("grp.askDel", g.name), t("delete"), true))) return; r.groups.splice(i, 1); changed(); render(); };
  });
  $("#addGroup").onclick = () => {
    if (r.groups.length >= 10) return;
    const g = { id: newId("g"), name: t("grp.newName"), pkgs: [], min: 60, hours: [] };
    r.groups.push(g); gOpen.add(g.id); changed(); document.activeElement.blur(); render();
    const n = $("#f-gn" + (r.groups.length - 1)); if (n) { n.focus(); n.select(); }
  };
  $("#f-um").onchange = e => { r.unlockMin = +e.target.value; changed(); };
  if ($("#totpNew")) $("#totpNew").onclick = totpSetup;
  if ($("#totpDone")) $("#totpDone").onclick = () => { totpShown = null; render(); };
  if ($("#totpOff")) $("#totpOff").onclick = async () => { if (await cmd({ type: "totpSet", secret: "" }, t("totp.offDone"), [t("totp.askOff"), t("turnOff"), true])) { totpShown = null; render(); } };
  b.querySelectorAll("[data-c]").forEach(box => {
    const c = r.chores[+box.dataset.c], i = +box.dataset.c;
    $("#f-cn" + i).oninput = e => { c.name = e.target.value.slice(0, 40); changed(); };
    $("#f-cm" + i).onchange = e => { c.min = clampN(e.target.value, 5, 120, 20); e.target.value = c.min; changed(); };
    box.querySelector("[data-cdel]").onclick = async () => { if (!(await ask(t("chore.askDel", c.name), t("delete"), true))) return; r.chores.splice(i, 1); changed(); render(); };
  });
  $("#addChore").onclick = () => {
    if (r.chores.length >= 20) return;
    r.chores.push({ id: newId("c"), name: t("chore.newName"), min: 20 }); changed(); render();
    const n = $("#f-cn" + (r.chores.length - 1)); if (n) { n.focus(); n.select(); }
  };
  b.querySelectorAll("[data-z]").forEach(box => {
    const i = +box.dataset.z, z = r.zones[i];
    box.querySelector("[data-zedit]").onclick = async () => { const v = await pickPlace(z); if (!v) return; Object.assign(z, v); changed(); render(); };
    box.querySelector("[data-zdel]").onclick = async () => { if (!(await ask(t("zone.askDel", z.name), t("delete"), true))) return; r.zones.splice(i, 1); changed(); render(); };
  });
  $("#addZone").onclick = async () => {
    if (r.zones.length >= 10) return;
    const v = await pickPlace({ name: "", r: 150 }); if (!v) return;
    r.zones.push({ id: newId("z"), ...v }); changed(); render();
  };
  $("#f-dmode").onchange = e => { r.dailyByDay = e.target.value === "by" ? Array(7).fill(r.dailyMin || 0) : []; changed(); document.activeElement.blur(); render(); };
  if ($("#f-daily")) $("#f-daily").onchange = e => { r.dailyMin = +e.target.value; changed(); };
  b.querySelectorAll("[data-d]").forEach(x => x.oninput = () => { const v = Math.min(1440, Math.max(0, Math.round(+x.value || 0))); r.dailyByDay[+x.dataset.d] = v; if (x.value !== "" && +x.value !== v) x.value = v; changed(); });
  b.querySelectorAll("[data-s]").forEach(box => {
    const x = r.schedules[+box.dataset.s];
    const sw = () => { box.querySelector("[data-sw]").innerHTML = timeWarn(x, x.on, true); };
    box.querySelectorAll("[data-f]").forEach(f => f.onchange = f.oninput = () => { x[f.dataset.f] = f.type === "checkbox" ? f.checked : f.value; sw(); changed(); });
    box.querySelectorAll("[data-day]").forEach(d => d.onclick = () => { const n = +d.dataset.day; x.days = (x.days || []).includes(n) ? x.days.filter(v => v !== n) : (x.days || []).concat(n).sort(); changed(); render(); });
    box.querySelector("[data-del]").onclick = async () => { if (!(await ask(t("sch.askDel"), t("delete"), true))) return; r.schedules.splice(+box.dataset.s, 1); changed(); render(); };
  });
  $("#addSch").onclick = () => { r.schedules.push({ name: t("sch.class"), on: true, days: [1, 2, 3, 4, 5], start: "07:00", end: "11:30" }); changed(); render(); };
  $("#f-tm").onchange = e => { r.trackMin = +e.target.value; changed(); };
  $("#f-bn").onchange = e => { r.blockNew = e.target.checked; changed(); };
  $("#f-ll").onchange = e => { r.lockLocation = e.target.checked; changed(); };
  $("#f-na").onchange = e => { r.noAirplane = e.target.checked; changed(); };
  $("#f-dns").onchange = e => { r.safeDns = e.target.value; changed(); };
  const bw = () => { $("#bedW").innerHTML = timeWarn(r.bedtime, r.bedtime.on); };
  $("#f-bed").onchange = e => { r.bedtime.on = e.target.checked; bw(); changed(); };
  $("#f-bs").onchange = e => { r.bedtime.start = e.target.value; bw(); changed(); };
  $("#f-be").onchange = e => { r.bedtime.end = e.target.value; bw(); changed(); };
  wireComfort(b, r);
  $("#f-ni").onchange = e => { r.noInstall = e.target.checked; changed(); };
  $("#f-hm").onchange = e => { r.launcher.on = e.target.checked; changed(); };
  $("#f-hms").onchange = e => { r.launcher.style = e.target.value; changed(); };
  $("#f-hmset").onchange = e => { r.launcher.settings = e.target.checked; changed(); };
  $("#f-hmn").onchange = e => { r.launcher.notes = e.target.checked; changed(); };
  $("#f-hmlock").onchange = e => { r.launcher.lockCustom = e.target.checked; changed(); };
  $("#f-nd").onchange = e => { r.noDebug = e.target.checked; changed(); };
}

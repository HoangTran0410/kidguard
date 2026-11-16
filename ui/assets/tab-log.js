// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- Nhật ký (log) + Hỗ trợ (support) + Báo cáo (report) tabs ----
// "Nhật ký": events + the phone's own log, one day at a time with a day strip + date picker, counts by type as filter chips,
// paged rows. The phone keeps 90 days (docs/protocol.md "Log history"): the page asks it for the chosen day (LAN GET
// /logday?day=, relay POST /logday + poll) and gets the per-day counts of the strip with it. A phone / worker without
// that route (404) falls back to what the state carries (last 50 each) kept per kid in localStorage (90 days / 1500 each).
const LG_PAGE = 30, LG_KEEP = 1500, LG_DAYS = 90;
const LG_CAT = { alert: ["pin_wrong", "usage_off", "totp_off", "release_fail", "released", "sos", "acc_settings", "proj_denied", "listen_err", "dns_fallback"],
  parent: ["cmd", "rules", "control", "view_start", "listen_start", "listen_done", "ring_stopped", "checkin", "admin_open", "dev_off", "dev_on", "cleared", "totp_on", "paired", "unlock"],
  apps: ["new_app", "suspended", "unsuspended", "recover_open", "recover_req", "acc_rebind", "acc_reenabled", "acc_paused", "acc_resumed"],
  loc: ["loc_fixed", "zone", "tz"] };
const lgCat = l => { const k = l.k || ""; for (const c in LG_CAT) if (LG_CAT[c].indexOf(k) >= 0) return c; return /^setting_/.test(k) ? "parent" : "other"; };
let lgChipX = null, lgDay = "", lgEv = "", lgLg = "", lgNEv = LG_PAGE, lgNLg = LG_PAGE, lgMem = null;
function lgMerge() {
  if (!cur || !st) return null;
  const key = "kg.lg." + cur.id;
  if (!lgMem || lgMem.key !== key) {
    let o = null; try { o = JSON.parse(localStorage.getItem(key) || "null"); } catch {}
    lgMem = { key, ev: o && Array.isArray(o.ev) ? o.ev : [], lg: o && Array.isArray(o.lg) ? o.lg : [] };
  }
  let ch = false;
  const add = (dst, src, id) => { const seen = new Set(dst.map(id)); for (const x of src || []) if (x && x.t && !seen.has(id(x))) { dst.push(x); seen.add(id(x)); ch = true; } };
  add(lgMem.ev, st.events, e => e.id || e.t + "|" + e.text);
  add(lgMem.lg, st.log, l => l.t + "|" + l.m);
  if (ch) {
    const min = Date.now() - LG_DAYS * 864e5;
    lgMem.ev = lgMem.ev.filter(x => x.t >= min).sort((a, b) => a.t - b.t).slice(-LG_KEEP);
    lgMem.lg = lgMem.lg.filter(x => x.t >= min).sort((a, b) => a.t - b.t).slice(-LG_KEEP);
    try { localStorage.setItem(key, JSON.stringify({ ev: lgMem.ev, lg: lgMem.lg })); } catch {}
  }
  return lgMem;
}
let lgx = null;   // {k, cache: {day: {d, tried, busy, err}}, days: {day: rows} (last answer), old}
async function lgLoad(day, force) {
  const k = cur; if (!k) return;
  if (!lgx || lgx.k !== k) lgx = { k, cache: {}, days: null, old: false };
  const me = lgx, c = me.cache[day] || (me.cache[day] = {});
  if (c.busy || me.old) return;
  if (!force && c.tried && (day !== dkey(Date.now()) || Date.now() - c.tried < 60000)) return;   // a past day is final; today refreshes at most every minute
  c.busy = true; c.err = ""; c.tried = Date.now();
  const gone = () => lgx !== me || cur !== k || tab !== "log";
  const draw = () => { if (!gone() && lgDay === day && $("#body")) logTab($("#body")); };
  const take = j => { c.d = j; if (j.days) me.days = j.days; };
  const old = e => e.raw === "not found" || e.raw === "HTTP 404";
  try {
    if (k.via === "lan") take(await call(k, "/logday?day=" + day));
    else {
      let j = await call(k, "/logday", "POST", { day });
      const asked = +j.asked || 0, fresh = x => x && (!asked || +x.at >= asked);
      if (j.logday) take(j.logday);
      draw();
      for (let i = 0; !fresh(j.logday) && i < 25 && !gone(); i++) {   // the phone answers on its next sync (<= ~1 min)
        await new Promise(r => setTimeout(r, 3000));
        j = await call(k, "/logday?day=" + day).catch(() => ({}));
        if (fresh(j.logday)) take(j.logday);
      }
      if (!fresh(j.logday) && !gone() && !c.d) throw { raw: "timeout", message: t("dv.old") };
    }
  } catch (e) { if (old(e)) me.old = true; else c.err = e.message || String(e.raw || e); }
  c.busy = false; draw();
}
const lgLabel = p => ((st.apps || []).find(a => a.pkg === p) || {}).label || p;
const useDur = ms => ms < 60000 ? "< " + t("fmt.m", 1) : fmt(ms);
function lgUse(u) {
  if (!u || !Array.isArray(u.p) || !Array.isArray(u.s)) return [];
  const [y, m, d] = lgDay.split("-").map(Number), d0 = new Date(y, m - 1, d).getTime();
  return u.s.filter(r => Array.isArray(r) && u.p[r[0]]).map(r => ({ pkg: u.p[r[0]], a: d0 + r[1] * 1000, b: d0 + r[2] * 1000 }));
}
// day timeline of app use (owner 2026-11-16: "timeline, màu riêng mỗi app, bấm app thì highlight"): one bar per 6 hours that
// has use, each session a coloured piece (top 8 apps by time get their own colour, the rest grey); a picked app keeps its
// colour, the others fade. A piece is a button for that app too.
const USE_COL = ["#3b82f6", "#f59e0b", "#10b981", "#ef4444", "#8b5cf6", "#ec4899", "#14b8a6", "#f97316"], USE_OTHER = "#94a3b8";
function useTl(U, col, pick) {
  if (!U.length) return "";
  const [y, m, d] = lgDay.split("-").map(Number), d0 = new Date(y, m - 1, d).getTime(), B = 6 * 3600e3, rows = [];
  for (let i = 0; i < 4; i++) {
    const a0 = d0 + i * B, a1 = a0 + B, seg = U.filter(u => u.b > a0 && u.a < a1);
    if (!seg.length) continue;
    const pc = x => ((x - a0) / B * 100).toFixed(2);
    rows.push(`<div class="utr"><span class="utl">${hhmm(a0)}</span><div class="utb">${seg.map(u => { const s = Math.max(u.a, a0), e = Math.min(u.b, a1);
      return `<i data-fe="u:${esc(u.pkg)}" role="button" aria-label="${esc(lgLabel(u.pkg) + " " + hhmm(u.a) + "–" + hhmm(u.b))}" class="${pick && pick !== u.pkg ? "dim" : ""}" style="left:${pc(s)}%;width:${(pc(e) - pc(s)).toFixed(2)}%;background:${col(u.pkg)}"></i>`; }).join("")}</div></div>`);
  }
  return `<div class="ut">${rows.join("")}</div>`;
}
// a picked app is highlighted, not filtered (owner 2026-11-16: tap a row = highlight every row of that app; the order of the day stays)
const useRow = (u, c, pick) => `<div class="lgr use${pick && pick !== u.pkg ? " dim" : ""}" data-fe="u:${esc(u.pkg)}" role="button" tabindex="0"><i class="ub" style="background:${c}"></i>${icon({ pkg: u.pkg, label: lgLabel(u.pkg) })}<span class="sp" style="min-width:0;overflow-wrap:anywhere"><b>${esc(lgLabel(u.pkg))}</b> <span class="mute">· ${esc(useDur(u.b - u.a))}</span></span><span class="mute lgt">${hhmm(u.a)}–${hhmm(u.b)}</span></div>`;
const lgCount = (rows, f) => { const m = {}; for (const r of rows) { const k = f(r); m[k] = (m[k] || 0) + 1; } return m; };
function logTab(b) {
  const today = dkey(Date.now());
  if (!lgDay) lgDay = today;
  lgLoad(lgDay);
  const c = lgx && lgx.k === cur && lgx.cache[lgDay] || {}, oldPhone = !!(lgx && lgx.k === cur && lgx.old);
  const m = oldPhone ? lgMerge() || { ev: st.events || [], lg: st.log || [] } : null;
  const evAll = m ? m.ev.filter(e => dkey(e.t) === lgDay) : c.d && c.d.ev || [], lgAll = m ? m.lg.filter(l => dkey(l.t) === lgDay) : c.d && c.d.lg || [];
  const evC = lgCount(evAll, e => e.kind || "other"), lgC = lgCount(lgAll, lgCat);
  const C = {}; for (const k in evC) C["e:" + k] = evC[k]; for (const k in lgC) C["l:" + k] = lgC[k];
  // app sessions ("Mở app", owner 2026-11-16): {p: [pkg], s: [[i, start s, end s] from the day's start]}; own chip, not in "Tất cả"
  const U = lgUse(c.d && c.d.use), uT = {}; for (const u of U) uT[u.pkg] = (uT[u.pkg] || 0) + u.b - u.a;
  const uRank = Object.keys(uT).sort((a, c) => uT[c] - uT[a]), uCol = p => { const i = uRank.indexOf(p); return i >= 0 && i < USE_COL.length ? USE_COL[i] : USE_OTHER; };
  const uAll = lgEv === "u:", uPkg = lgEv.startsWith("u:") ? lgEv.slice(2) : null;
  if (lgEv && !C[lgEv] && !(uPkg !== null && (!c.d || (uAll ? U.length : uT[uPkg])))) lgEv = "";   // still loading: keep "Mở app"
  // one timeline, newest first: events (bold) and log lines, each with its clock time (filter "" | "e:<kind>" | "l:<cat>")
  const rows = lgEv.startsWith("u:") ? U.map(u => ({ t: u.a, h: useRow(u, uCol(u.pkg), uPkg) })).reverse() : [...(!lgEv || lgEv.startsWith("e:") ? evAll.filter(e => !lgEv || "e:" + (e.kind || "other") === lgEv).map(e => ({ t: +e.t || 0, h: evRow(e) })) : []),
    ...(!lgEv || lgEv.startsWith("l:") ? lgAll.filter(l => !lgEv || "l:" + lgCat(l) === lgEv).map(l => ({ t: +l.t || 0, h: logRow(l) })) : [])]
    .sort((a, c) => c.t - a.t);
  let tot = lgx && lgx.k === cur && lgx.days;
  if (m) { tot = {}; for (const e of m.ev) { const k = dkey(e.t); tot[k] = (tot[k] || 0) + 1; } for (const l of m.lg) { const k = dkey(l.t); tot[k] = (tot[k] || 0) + 1; } }
  tot = tot || {};
  const first = dkey(Math.min(Date.now() - 90 * 864e5, ...Object.keys(tot).map(d => new Date(+d.slice(0, 4), +d.slice(5, 7) - 1, +d.slice(8, 10), 12).getTime())));
  const chip = (attr, v, cur_, n, label) => chipBtn(`data-${attr}="${v}"`, `${label} <b>${n}</b>`, cur_ === v);
  const appChips = () => !lgEv.startsWith("u:") ? "" : useTl(U, uCol, uPkg) + `<div style="margin-top:6px">${chipRow(uRank
    .map(p => chipBtn(`data-fe="u:${esc(p)}"`, `<i class="ud" style="background:${uCol(p)}"></i>${esc(lgLabel(p))} <b>${esc(useDur(uT[p]))}</b>`, lgEv === "u:" + p)), { scroll: 1, aria: t("lg.useApps") })}</div>`;
  const chips = () => chipRow([chip("fe", "", lgEv, evAll.length + lgAll.length, t("lg.all")), ...(U.length ? [chip("fe", "u:", lgEv.startsWith("u:") ? "u:" : lgEv, U.length, `${ic("layout-grid")} ${t("lg.use")}`)] : []), ...Object.keys(C).sort((a, c) => a[0] === c[0] ? C[c] - C[a] : a < c ? -1 : 1).map(k => chip("fe", k, lgEv, C[k], k[0] === "e" ? `${ic(EV_IC[k.slice(2)] || "bell")} ${tc("lg.k.", k.slice(2), k.slice(2))}` : t("lg.c." + k.slice(2))))], { scroll: 1, aria: t("lg.h") });
  const more = (rows, n, id) => rows.length > n ? `<button type="button" class="sm" id="${id}" style="width:100%;margin-top:8px">${t("lg.more", Math.min(LG_PAGE, rows.length - n), rows.length - n)}</button>` : "";
  const wait = !m && !c.d, none = wait ? (c.err ? emptyBox("triangle-alert", `${esc(t("lg.err", c.err))}<br><button type="button" class="sm" data-lgretry>${t("lg.retry")}</button>`) : emptyBox("hourglass", t("lg.loading"))) : emptyBox("inbox", t("lg.none"));
  b.innerHTML = `<div class="card">${dayBtn("log", { sel: lgDay, min: first, max: today, counts: tot, nav: true, onPick: k => go(k) })}
    <div style="margin-top:10px">${evAll.length + lgAll.length + U.length ? chips() + appChips() : ""}</div>
    <div style="margin-top:6px">${rows.slice(0, lgNEv).map(r => r.h).join("") || none}</div>${more(rows, lgNEv, "lgMoreE")}
    <p class="mute" style="margin-top:10px">${t(oldPhone ? "lg.noteOld" : lgEv.startsWith("u:") ? "lg.useNote" : "lg.note")}</p>${c.d && c.d.cut ? `<p class="mute">${t("lg.cut")}</p>` : ""}</div>`;
  const go = d => { lgDay = d; lgEv = lgEv.startsWith("u:") ? "u:" : ""; lgLg = ""; lgNEv = lgNLg = LG_PAGE; render(); };
  dpBind(b);
  b.querySelectorAll("[data-lgretry]").forEach(x => x.onclick = () => lgLoad(lgDay, true));
  // an app chip / timeline piece again = all apps again
  b.querySelectorAll("[data-fe]").forEach(x => x.onclick = () => { lgChipX = [...b.querySelectorAll(".chips.scroll")].map(r => r.scrollLeft); lgEv = x.dataset.fe.length > 2 && x.dataset.fe === lgEv && lgEv.startsWith("u:") ? "u:" : x.dataset.fe; lgNEv = LG_PAGE; render(); });
  // render() rebuilds the whole page, so a chip tap saves each chip row's sideways scroll first (owner: rows jumped back to the start)
  if (lgChipX) { b.querySelectorAll(".chips.scroll").forEach((r, i) => { if (lgChipX[i]) r.scrollLeft = lgChipX[i]; }); lgChipX = null; }
  { const on = uPkg && b.querySelector(`button.on[data-fe="u:${CSS.escape(uPkg)}"]`), r = on && on.parentElement;   // picked app chip into view only if it is off-screen (picked from the list / timeline)
    if (r && r.scrollWidth > r.clientWidth) { const x = on.offsetLeft - r.offsetLeft; if (x < r.scrollLeft || x + on.offsetWidth > r.scrollLeft + r.clientWidth) r.scrollLeft = x - 8; } }
  b.querySelectorAll(".lgr.use").forEach(x => x.onkeydown = e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); x.click(); } });
  if ($("#lgMoreE")) $("#lgMoreE").onclick = () => { lgNEv += LG_PAGE; render(); };
}
// "Gửi lỗi": a report to the developer (phone command `support`, Support.kt) and the thread of replies (`supportSay`)
const SUP_KINDS = ["block", "time", "relay", "location", "launcher", "bank", "battery", "other"];
let supKind = "other", supText = "", supPkg = "", supReply = "";
function supTab(b) {
  const s = st.support || {}, msgs = s.msgs || [];
  const apps = (st.apps || []).slice().sort((x, y) => (x.label || x.pkg).localeCompare(y.label || y.pkg));
  const status = s.busy ? `<p class="mute">${ic("hourglass")} ${t("sup.busy")}</p>`
    : s.err ? `<p class="t-bad">${ic("triangle-alert")} ${esc(tc("sup.err.", s.err, t("errX", s.err)))}</p>` : "";
  b.innerHTML = `<div class="card"><h2>${ic("message-square")} ${t("sup.h")}</h2><p class="mute">${t("sup.intro")}</p>
    <details><summary>${t("sup.whatH")}</summary><p class="mute">${t("sup.what")}</p><p class="mute">${t("sup.never")}</p></details>
    <div class="sec">${t("sup.kind")}</div>
    ${chipRow(SUP_KINDS.map(k => chipBtn(`data-sk="${k}"`, t("sup.k." + k), supKind === k)), { aria: t("sup.kind") })}
    <label class="sec" for="f-supText">${t("sup.say")}</label>
    <textarea id="f-supText" rows="3" maxlength="1000" style="width:100%" placeholder="${esc(t("sup.sayPh"))}">${esc(supText)}</textarea>
    <label class="sec" for="f-supPkg">${t("sup.app")}</label>
    <select id="f-supPkg" style="width:100%"><option value="">${esc(t("sup.appNone"))}</option>${apps.map(a => `<option value="${esc(a.pkg)}" ${a.pkg === supPkg ? "selected" : ""}>${esc(a.label || a.pkg)}</option>`).join("")}</select>
    <p class="mute">${t("sup.appNote")}</p>
    <div class="row" style="margin-top:10px"><button type="button" id="supSend" class="pri" ${s.busy ? "disabled" : ""}>${ic("send")} ${t("sup.send")}</button></div>
    ${status}
    ${s.code ? `<div class="ban t-ok" style="margin-top:10px"><span class="bi">${ic("hash")}</span><div class="x"><b>${t("sup.code", esc(s.code))}</b><br>${t("sup.codeNote")}${s.left >= 0 ? " " + t("sup.left", s.left) : ""}</div></div>` : ""}</div>
  ${s.code ? `<div class="card"><h2>${ic("message-square")} ${t("sup.thread")}</h2>
    ${msgs.map(m => `<div class="li"><span class="x"><b>${t(m.who === "dev" ? "sup.dev" : "sup.you")}</b> <span class="mute">${esc(dtFull(m.at))}</span><span class="sub" style="white-space:pre-wrap">${esc(m.text)}</span></span></div>`).join("") || `<p class="mute">${t("sup.noReply")}</p>`}
    <textarea id="f-supReply" rows="2" maxlength="1000" style="width:100%;margin-top:8px" placeholder="${esc(t("sup.replyPh"))}">${esc(supReply)}</textarea>
    <div class="row" style="margin-top:8px"><button type="button" id="supSay">${ic("send")} ${t("sup.reply")}</button></div></div>` : ""}`;
  b.querySelectorAll("[data-sk]").forEach(x => x.onclick = () => { supKind = x.dataset.sk; render(); });
  $("#f-supText").oninput = e => { supText = e.target.value; };
  $("#f-supPkg").onchange = e => { supPkg = e.target.value; };
  $("#supSend").onclick = async () => {
    const r = await cmd({ type: "support", kind: supKind, text: supText.trim(), pkg: supPkg }, t("sup.sent"), [t("sup.ask"), t("sup.send")]);
    if (r) { supText = ""; supPkg = ""; render(); }
  };
  if ($("#f-supReply")) $("#f-supReply").oninput = e => { supReply = e.target.value; };
  if ($("#supSay")) $("#supSay").onclick = async () => {
    if (!supReply.trim()) return;
    const r = await cmd({ type: "supportSay", text: supReply.trim() }, t("sent"), false);
    if (r) { supReply = ""; render(); }
  };
}
// "Báo cáo tuần": 7-day bars + this kid's weekly report + most used today
function repTab(b) {
  b.innerHTML = `<div class="g2"><div><div class="card"><h2>${ic("chart-column")} ${t("last7")}</h2><div id="histPick" style="margin-bottom:8px"></div><div class="hist" id="hist"></div><div id="histTop" class="mute"></div></div>
    <div class="card"><h2>${ic("history")} ${t("ov.report")}</h2>${ovReport([{ k: cur, st, err: "" }])}</div></div>
    <div class="card"><h2>${ic("flame")} ${t("topApps")}</h2>${st.apps.filter(x => x.ms > 60000).slice(0, 12).map(appRow).join("") || emptyBox("sprout", t("none"))}</div></div>`;
  drawHist();
}


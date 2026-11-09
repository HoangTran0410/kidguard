// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
const sel = { on: false, set: new Set(), f: "all", limOpen: false };
// "Ứng dụng" tab: segmented sub-tabs (app list / hide preinstalled / storage), last one remembered
let appsSub = (() => { try { return localStorage.getItem("kg.appsSub") || "list"; } catch { return "list"; } })();
function appsTab(b) {
  const subs = [["list", "puzzle", t("as.list")], ["clean", "eye-off", t("as.clean")], ["space", "hard-drive", t("as.space")]];
  if (!subs.some(x => x[0] === appsSub)) appsSub = "list";
  const ref = cleanStat().ref;
  b.innerHTML = `<div class="seg" role="tablist" aria-label="${esc(t("tab.apps"))}">${subs.map(([id, i, n]) => `<button type="button" role="tab" id="st-${id}" data-sub="${id}" aria-selected="${appsSub === id}" aria-controls="subp" tabindex="${appsSub === id ? 0 : -1}">${ic(i)}<span>${n}</span>${id === "clean" && ref ? `<span class="n" title="${esc(t("hd.refN", ref))}">${ref}</span>` : ""}</button>`).join("")}</div>
    <div id="subp" role="tabpanel" aria-labelledby="st-${appsSub}"></div>`;
  const tabs = [...b.querySelectorAll("[data-sub]")];
  const pick = (id, f) => { appsSub = id; try { localStorage.setItem("kg.appsSub", id); } catch {} appsTab(b); if (f) { const x = b.querySelector(`[data-sub="${id}"]`); if (x) x.focus(); } };
  tabs.forEach((x, i) => {
    x.onclick = () => pick(x.dataset.sub);
    x.onkeydown = e => { const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0; if (e.key === "Home" || e.key === "End") { e.preventDefault(); pick(tabs[e.key === "Home" ? 0 : tabs.length - 1].dataset.sub, true); } else if (d) { e.preventDefault(); pick(tabs[(i + d + tabs.length) % tabs.length].dataset.sub, true); } };
  });
  const p = $("#subp");
  (appsSub === "clean" ? cleanTab : appsSub === "space" ? spaceTab : appsList)(p);
}
function appsList(b) {
  const mode = p => edit.blocked.includes(p) ? "block" : edit.allow.includes(p) ? "allow" : edit.appMin[p] ? "limit" : "normal";
  const isNew = x => x.new && !edit.approved.includes(x.pkg);
  const FILT = [["all", t("f.all")], ["new", ic("sparkles") + " " + t("f.new")], ["blocked", ic("octagon-x") + " " + t("f.blocked")], ["limited", ic("timer") + " " + t("f.limited")]];
  const pass = x => { const m = mode(x.pkg); return sel.f === "all" || (sel.f === "new" && isNew(x)) || (sel.f === "blocked" && (m === "block" || x.suspended)) || (sel.f === "limited" && m === "limit"); };
  const visible = () => { const q = ($("#f-q").value || "").toLowerCase(); return st.apps.filter(x => !x.protected && pass(x) && (!q || x.label.toLowerCase().includes(q) || x.pkg.includes(q))); };
  b.innerHTML = `<div class="card"><div class="row"><h2 class="sp" style="margin:0">${ic("puzzle")} ${t("apps.h", st.apps.length)}</h2><button id="selTog" class="${sel.on ? "pri" : ""}">${sel.on ? ic("x") + " " + t("sel.exit") : ic("square-check") + " " + t("sel.on")}</button></div>
    <input id="f-q" placeholder="${esc(t("apps.search"))}" aria-label="${esc(t("apps.search"))}" style="width:100%;margin:10px 0 6px">
    <div class="chips" id="flt">${FILT.map(([v, n]) => `<button data-f="${v}" class="${sel.f === v ? "on" : ""}">${n}</button>`).join("")}</div>
    ${sel.on ? `<div class="row" style="margin-top:8px"><button id="selAll">${ic("square-check")} ${t("sel.all")}</button><button id="selNone">${ic("square")} ${t("sel.none")}</button></div>` : ""}
    <div id="list"></div><p class="mute">${ic("lightbulb")} ${t("apps.tip")}</p></div>
    ${sel.on ? `<div class="bulk" id="bulk"></div>` : ""}`;
  const drawBar = () => {
    const bar = $("#bulk"); if (!bar) return;
    const n = sel.set.size, dis = n ? "" : "disabled";
    bar.innerHTML = `<div class="row"><b class="sp">${ic("check")} ${t("sel.n", n)}</b></div>
      <div class="row" style="margin-top:6px"><button ${dis} data-a="block" class="bad">${ic("octagon-x")} ${t("block")}</button><button ${dis} data-a="unblock">${ic("circle-check")} ${t("unblock")}</button><button ${dis} data-a="allow">${ic("star")} ${t("allow")}</button><button ${dis} data-a="lim">${ic("timer")} ${t("limitX")}</button><button ${dis} data-a="unlim">${ic("trash-2")} ${t("unlimit")}</button><button ${dis} data-a="approve">${ic("thumbs-up")} ${t("approve")}</button></div>
      ${sel.limOpen ? `<div class="row" style="margin-top:6px"><input id="f-lim" type="number" min="1" max="1440" step="5" inputmode="numeric" placeholder="${esc(t("minPerDay"))}" aria-label="${esc(t("minPerDay"))}" style="width:9em"><button id="limOk" class="pri" ${dis}>${ic("circle-check")} ${t("apply")}</button></div>` : ""}`;
    const clr = p => { edit.blocked = edit.blocked.filter(x => x !== p); edit.allow = edit.allow.filter(x => x !== p); delete edit.appMin[p]; };
    // k: "bulk.<k>" asks "Block 3 apps?", "bulk.<k>Done" says "Blocked 3 apps"; m: minutes for "lim"
    const apply = async (k, fn, m) => {
      const pk = [...sel.set].filter(p => { const a = st.apps.find(x => x.pkg === p); return a && !a.protected; });
      if (!pk.length) return toast("warn|" + t("sel.noneYet"));
      if (!(await ask(t("bulk." + k, pk.length, m)))) return;
      pk.forEach(fn); changed(); toast("ok|" + t("bulk." + k + "Done", pk.length, m)); sel.set.clear(); sel.limOpen = false; draw(); setTimeout(refresh, 1200);   // one PUT (debounced), then refresh
    };
    bar.querySelectorAll("[data-a]").forEach(x => x.onclick = () => {
      const a = x.dataset.a;
      if (a === "block") apply("block", p => { clr(p); edit.blocked.push(p); });
      else if (a === "unblock") apply("unblock", p => { edit.blocked = edit.blocked.filter(v => v !== p); });
      else if (a === "allow") apply("allow", p => { clr(p); edit.allow.push(p); });
      else if (a === "unlim") apply("unlim", p => { delete edit.appMin[p]; });
      else if (a === "approve") apply("approve", p => { if (!edit.approved.includes(p)) edit.approved.push(p); });
      else if (a === "lim") { sel.limOpen = !sel.limOpen; drawBar(); const i = $("#f-lim"); if (i) i.focus(); }
    });
    if ($("#limOk")) $("#limOk").onclick = () => {
      const m = Math.round(+$("#f-lim").value); if (!(m >= 1 && m <= 1440)) return toast("warn|" + t("lim.range"));
      apply("lim", p => { edit.blocked = edit.blocked.filter(v => v !== p); edit.allow = edit.allow.filter(v => v !== p); edit.appMin[p] = m; }, m);
    };
  };
  const draw = () => {
    const vis = visible();
    $("#list").innerHTML = vis.map(x => {
      const m = mode(x.pkg), nw = isNew(x);
      const info = `<div class="nm"><b>${esc(x.label)}</b><span class="mute">${t("todayV", fmt(x.ms))}${x.suspended || x.blocked ? `<span class="tag bad">${reason(x.blocked)}</span>` : ""}${nw ? `<span class="tag" style="background:#fef3c7;color:#92400e">${ic("sparkles")} ${t("newInst")}</span>` : ""}${m === "block" ? `<span class="tag bad">${ic("octagon-x")} ${t("block")}</span>` : m === "allow" ? `<span class="tag" title="${esc(t("allow"))}">${ic("star")}</span>` : m === "limit" ? `<span class="tag">${ic("timer")} ${t("fmt.m", edit.appMin[x.pkg])}</span>` : ""}</span></div>`;
      if (sel.on) return `<label class="app"><input type="checkbox" data-c="${esc(x.pkg)}" ${sel.set.has(x.pkg) ? "checked" : ""}>${icon(x)}${info}</label>`;
      return `<div class="app">${icon(x)}${info}
      ${nw ? `<button class="pri" data-ok="${esc(x.pkg)}">${ic("circle-check")} ${t("permit")}</button>` : ""}
      <select data-p="${esc(x.pkg)}" aria-label="${esc(t("apps.rule", x.label))}">
        <option value="normal" ${m === "normal" ? "selected" : ""}>${t("normal")}</option>
        ${[15, 30, 45, 60, 90, 120].map(n => `<option value="${n}" ${m === "limit" && edit.appMin[x.pkg] === n ? "selected" : ""}>${t("perDay", n)}</option>`).join("")}
        ${m === "limit" && ![15, 30, 45, 60, 90, 120].includes(edit.appMin[x.pkg]) ? `<option value="${edit.appMin[x.pkg]}" selected>${t("perDay", edit.appMin[x.pkg])}</option>` : ""}
        <option value="block" ${m === "block" ? "selected" : ""}>${t("block")}</option>
        <option value="allow" ${m === "allow" ? "selected" : ""}>${t("allow")}</option>
      </select></div>`;
    }).join("") || `<div class="empty"><span class="e">${ic("circle-dashed")}</span>${t("apps.none")}</div>`;
    $("#list").querySelectorAll("[data-ok]").forEach(bt => bt.onclick = () => { edit.approved.push(bt.dataset.ok); changed(); draw(); toast("ok|" + t("permitted")); });
    $("#list").querySelectorAll("[data-c]").forEach(c => c.onchange = () => { c.checked ? sel.set.add(c.dataset.c) : sel.set.delete(c.dataset.c); drawBar(); });
    $("#list").querySelectorAll("select").forEach(s => s.onchange = () => {
      const p = s.dataset.p, v = s.value;
      edit.blocked = edit.blocked.filter(x => x !== p); edit.allow = edit.allow.filter(x => x !== p); delete edit.appMin[p];
      if (v === "block") edit.blocked.push(p); else if (v === "allow") edit.allow.push(p); else if (v !== "normal") edit.appMin[p] = +v;
      changed();
    });
    drawBar();
  };
  $("#f-q").oninput = draw;
  $("#selTog").onclick = () => { sel.on = !sel.on; sel.set.clear(); sel.limOpen = false; appsList(b); };
  b.querySelectorAll("#flt [data-f]").forEach(x => x.onclick = () => { sel.f = x.dataset.f; appsList(b); });
  if ($("#selAll")) $("#selAll").onclick = () => { visible().forEach(x => sel.set.add(x.pkg)); draw(); };
  if ($("#selNone")) $("#selNone").onclick = () => { sel.set.clear(); draw(); };
  draw();
}

// v0.8 phone clean-up: hide preinstalled bloat (owner), clear an app's data (owner, Android 9+), heaviest apps (both modes)
// Hiding is edited as a draft (hideDraft, only while it differs from rules.hidden) and saved with ONE rules update.
// Per-app result comes from the phone's report: state.rules.hidden = what the phone has taken, preinstalled[].hidden = isApplicationHidden.
// Wanted + taken + still not hidden = the phone refused (ColorOS refuses some of its own apps, e.g. the game center / store / themes).
let hideDraft = null;
const mbTxt = m => m >= 1024 ? (m / 1024).toLocaleString(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + " GB" : m + " MB";
function cleanStat() {
  const pre = (st && st.preinstalled) || [], saved = new Set((edit && edit.hidden) || []), took = new Set((st && st.rules && st.rules.hidden) || []);
  // preinstalled[].refused (phone >= 0.9.8): the OS said no to hiding it, so it counts as not hidden whatever "hidden" says
  const of = x => { const h = x.hidden && !x.refused; return saved.has(x.pkg) ? (x.refused ? "ref" : took.has(x.pkg) ? (h ? "ok" : "ref") : "wait") : (h && took.has(x.pkg) ? "show" : ""); };
  const n = { want: 0, ok: 0, ref: 0, wait: 0, of };
  pre.forEach(x => { const s = of(x); if (saved.has(x.pkg)) { n.want++; if (s === "ok" || s === "ref" || s === "wait") n[s]++; } });
  return n;
}
const sameSet = (a, b) => a.size === b.size && [...a].every(x => b.has(x));
// Android 9+ (API 28) for DevicePolicyManager.clearApplicationUserData; state has only the release string ("8.1.0")
const canClear = () => !st || !st.android || !(parseInt(st.android, 10) < 9);
function cleanTab(b) {
  const owner = !isBasic() && st.owner !== false;
  if (!owner) { b.innerHTML = `<div class="card">${needOwner(t("no.hide"))}</div>`; return; }
  if (!st.preinstalled) { b.innerHTML = `<div class="card"><p class="mute">${ic("hourglass")} ${t("clean.noList")}</p></div>`; return; }
  const pre = st.preinstalled, saved = new Set(edit.hidden), S = cleanStat();
  const draft = () => hideDraft || saved;
  const sug = pre.filter(x => x.suggest || saved.has(x.pkg) || x.hidden), rest = pre.filter(x => !sug.includes(x));
  const TAG = { ok: `<span class="tag ok">${ic("check")} ${t("hd.ok")}</span>`, ref: `<span class="tag bad">${ic("ban")} ${t("hd.refused")}</span>`,
    wait: `<span class="tag">${ic("hourglass")} ${t("hd.waiting")}</span>`, show: `<span class="tag">${ic("hourglass")} ${t("hd.showing")}</span>` };
  const row = x => `<label class="app"><input type="checkbox" data-hd="${esc(x.pkg)}" ${draft().has(x.pkg) ? "checked" : ""}><div class="nm"><b>${esc(x.label)}</b><span class="mute">${esc(x.pkg)}${x.mb ? " · " + mbTxt(x.mb) : ""}${x.suggest ? `<span class="tag warn">${t("clean.sug")}</span>` : ""}${TAG[S.of(x)] || ""}</span></div></label>`;
  const pc = v => S.want ? (v / S.want * 100).toFixed(1) + "%" : "0";
  const nSug = pre.filter(x => x.suggest).length;
  b.innerHTML = `<div class="card"><h2>${ic("sparkles")} ${t("clean.h")}</h2>
    <p class="mute" style="margin:0">${t("clean.p", "<b>" + pre.filter(x => x.hidden && !x.refused).length + "</b>")}</p>
    ${S.want ? `<div class="prog" role="img" aria-label="${esc(t("hd.sum", S.ok, S.want))}"><i style="width:${pc(S.ok)}"></i><i class="b" style="width:${pc(S.ref)}"></i><i class="w" style="width:${pc(S.wait)}"></i></div>
      <div><b>${t("hd.sum", S.ok, S.want)}</b>${S.ref ? ` · <span class="err">${t("hd.refN", S.ref)}</span>` : ""}${S.wait ? ` · <span class="mute">${t("hd.waitN", S.wait)}</span>` : ""}</div>
      ${S.ref ? `<div class="alert warn" style="font-weight:400;font-size:13px">${ic("triangle-alert")} ${t("hd.refTip")}</div>` : ""}` : ""}</div>
  ${sec("cl-sug", "sparkles", t("hd.sugH"), t("hd.sugD"), [t("hd.nApps", sug.length)], sug.map(row).join("") || `<p class="mute">${t("clean.noSug")}</p>`, true)}
  ${rest.length ? sec("cl-rest", "layers", t("hd.restH"), t("hd.restD"), [t("hd.nApps", rest.length)], rest.map(row).join("")) : ""}
  <div class="hbar" id="hbar"></div>`;
  const bar = () => {
    const d = draft(), add = [...d].filter(p => !saved.has(p)).length, rem = [...saved].filter(p => !d.has(p)).length, dirty = !!hideDraft;
    $("#hbar").innerHTML = `<div class="row"><span class="sp mute">${ic("eye-off")} ${t("hd.sel", [...d].filter(p => pre.some(x => x.pkg === p)).length)}</span></div>
      <div class="row" style="margin-top:6px"><button type="button" id="hdSug" ${nSug ? "" : "disabled"}>${ic("square-check")} ${t("hd.allSug")}</button><button type="button" id="hdNone">${ic("square")} ${t("sel.none")}</button></div>
      ${dirty ? `<div class="row" style="margin-top:6px"><button type="button" class="pri sp" id="hdSave">${ic("save")} ${t("hd.save", add, rem)}</button><button type="button" id="hdUndo">${ic("x")} ${t("cancel")}</button></div>` : ""}`;
    $("#hdSug").onclick = () => { const s = new Set(draft()); pre.forEach(x => x.suggest && s.add(x.pkg)); setD(s); cleanTab(b); };
    $("#hdNone").onclick = () => { setD(new Set()); cleanTab(b); };
    if ($("#hdUndo")) $("#hdUndo").onclick = () => { hideDraft = null; cleanTab(b); };
    if ($("#hdSave")) $("#hdSave").onclick = async () => {
      if (!(await ask(t("hd.ask", add, rem), t("save")))) return;
      edit.hidden = [...draft()]; hideDraft = null; changed();   // one PUT with the whole rules.hidden
      toast("ok|" + t("hd.sent")); cleanTab(b); setTimeout(refresh, 1500);
    };
  };
  const setD = s => { hideDraft = sameSet(s, saved) ? null : s; };
  b.querySelectorAll("[data-hd]").forEach(c => c.onchange = () => { const s = new Set(draft()); c.checked ? s.add(c.dataset.hd) : s.delete(c.dataset.hd); setD(s); bar(); });
  bar();
}
function spaceTab(b) {
  const heavy = (st.apps || []).filter(x => x.mb > 0).sort((a, b) => b.mb - a.mb).slice(0, 15);
  const owner = !isBasic() && st.owner !== false, cc = owner && canClear();
  b.innerHTML = `<div class="card"><h2>${ic("hard-drive")} ${t("clean.heavy")}</h2>
    ${owner && !cc ? `<div class="alert warn" style="margin:0 0 8px;font-weight:400;font-size:13px">${ic("triangle-alert")} ${t("clean.oldOs", esc(st.android))}</div>` : ""}
    ${heavy.length ? heavy.map(x => `<div class="app">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${mbTxt(x.mb)}</span></div>${cc && !x.protected ? `<button type="button" data-clr="${esc(x.pkg)}" class="bad">${ic("trash-2")} ${t("clean.clear")}</button>` : ""}</div>`).join("")
      : `<p class="mute">${t("clean.noSize")}</p>`}
    <p class="mute">${ic("lightbulb")} ${cc ? t("clean.tipOwner") + " " : ""}${owner ? "" : t("clean.tipBasic") + " "}${t("clean.tipRam")}</p></div>`;
  b.querySelectorAll("[data-clr]").forEach(x => x.onclick = () => {
    const a = (st.apps || []).find(y => y.pkg === x.dataset.clr); if (!a) return;
    cmd({ type: "clearData", pkg: a.pkg }, t("clean.cleared", a.label),
      [t("clean.askClear", a.label), t("delete"), true]).then(() => setTimeout(refresh, 2000));
  });
}

// ---- "Máy" tab: device health, on demand (docs/protocol.md "Device health"). LAN: GET /api/device (phone caches 60 s).
// Relay: POST /device asks + returns the last stored snapshot; the phone PUTs a new one on its next sync (<= ~1 min).
let dv = null;      // {k, d: snapshot, tried, busy, wait, err}
const dvAct = {};   // button id -> {s: "pend" | "done" | "sent" | "err", t}
const byt = n => { n = +n || 0; const f = (v, d) => v.toLocaleString(lang, { maximumFractionDigits: d });
  return n >= 1e9 ? f(n / 1e9, n >= 1e11 ? 0 : 1) + " GB" : n >= 1e6 ? f(n / 1e6, 0) + " MB" : n >= 1e3 ? f(n / 1e3, 0) + " KB" : f(n, 0) + " B"; };
const dvDur = ms => ms >= 864e5 ? t("dv.days", Math.floor(ms / 864e5)) + (ms % 864e5 >= 36e5 ? " " + fmt(Math.floor(ms % 864e5 / 36e5) * 36e5) : "") : fmt(ms);
const dvDraw = () => { if (tab === "dev" && $("#body")) devTab($("#body")); };
function dvBtn(id, icn, label, cls, dis, title) {
  const a = dvAct[id], fresh = a && (a.s === "pend" || Date.now() - a.t < 2500);
  const st2 = fresh ? a.s : "", txt = st2 === "err" ? ic("triangle-alert") + " " + t("act.err") : st2 === "done" || st2 === "sent" ? ic("check") + " " + t(st2 === "sent" ? "act.sent" : "act.done") : ic(icn) + " " + label;
  return `<button type="button" id="${id}" class="act ${cls || ""} ${st2 === "sent" ? "done" : st2}" ${dis || st2 === "pend" ? "disabled" : ""} ${title ? `title="${esc(title)}"` : ""} aria-live="polite">${txt}</button>`;
}
async function dvRun(id, fn) {
  if (dvAct[id] && dvAct[id].s === "pend") return;
  dvAct[id] = { s: "pend", t: Date.now() }; dvDraw();
  const r = await fn();
  dvAct[id] = { s: r === undefined ? "err" : r.result === "queued" ? "sent" : "done", t: Date.now() };
  dvDraw(); setTimeout(dvDraw, 2600);
}
async function devLoad(force) {
  const k = cur; if (!k) return;
  if (!dv || dv.k !== k) dv = { k };
  if (dv.busy || (!force && dv.tried && Date.now() - dv.tried < 60000)) return;
  const me = dv; me.busy = true; me.err = ""; me.tried = Date.now(); dvAct.dvRef = { s: "pend", t: Date.now() };
  const gone = () => dv !== me || cur !== k;
  const old = e => e.raw === "not found" || e.raw === "HTTP 404";
  try {
    if (k.via === "lan") me.d = await call(k, force ? "/device?fresh=1" : "/device");
    else {
      let j = await call(k, "/device", "POST", {}).catch(e => { if (old(e)) throw e; return call(k, "/device"); });
      const asked = +j.asked || 0;
      if (j.device) me.d = j.device;
      me.wait = !(asked && j.device && +j.device.at >= asked); if (!gone()) dvDraw();
      for (let i = 0; me.wait && i < 30 && !gone() && tab === "dev"; i++) {
        await new Promise(r => setTimeout(r, 4000));
        if (gone() || tab !== "dev") break;
        j = await call(k, "/device").catch(() => ({}));
        if (j.device && (!asked || +j.device.at >= asked)) { me.d = j.device; me.wait = false; }
      }
      if (me.wait && !me.d) me.err = t("dv.old");
      me.wait = false;
    }
    dvAct.dvRef = { s: me.err ? "err" : "done", t: Date.now() };
  } catch (e) { me.err = old(e) ? t(k.via === "lan" ? "dv.old" : "dv.oldRelay") : t("dv.err", e.message); dvAct.dvRef = { s: "err", t: Date.now() }; }
  me.busy = false;
  if (!gone()) { dvDraw(); setTimeout(dvDraw, 2600); }
}
function devTab(b) {
  if (!dv || dv.k !== cur) dv = { k: cur };
  if (!dv.busy && (!dv.tried || Date.now() - dv.tried > 60000)) { devLoad(false); }
  const d = dv.d, now = Date.now();
  const kv = (k, v) => `<div class="dvkv"><span>${k}</span><b>${v}</b></div>`;
  const bar = (pct, tone, label) => `<div class="prog" role="progressbar" aria-label="${esc(label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(pct)}"><i class="${tone}" style="width:${Math.max(0, Math.min(100, pct)).toFixed(1)}%"></i></div>`;
  const head = `<div class="card row"><span class="sp mute" role="status">${dv.err ? `<span style="color:var(--bad)">${ic("triangle-alert")}</span> ${esc(dv.err)}`
      : dv.wait ? ic("hourglass") + " " + t(d ? "dv.stale" : "dv.wait") : !d ? ic("hourglass") + " " + t("dv.loading") : ic("clock") + " " + t("dv.at", hhmm(+d.t || now))}</span>
    ${dvBtn("dvRef", "refresh-cw", t("dv.refresh"), "", dv.busy)}</div>`;
  if (!d) { b.innerHTML = head; wireDev(b); return; }
  const s = d.storage || {}, used = Math.max(0, (+s.total || 0) - (+s.free || 0)), sp = s.total ? used / s.total * 100 : 0;
  const owner = !isBasic() && st.owner !== false, mine = new Map((st.apps || []).map(x => [x.pkg, x]));
  const stoTone = s.total && (s.free < 1e9 || sp > 95) ? "b" : sp > 85 ? "w" : "";
  const appRow = (x, i) => { const a = mine.get(x.pkg), can = owner && a && !a.protected;
    const btn = !can ? "" : d.clearData ? dvBtn("dvc" + i, "trash-2", t("clean.clear"), "bad") : dvBtn("dvc" + i, "trash-2", t("clean.clear"), "", true, t("dv.clearOld"));
    return `<div class="app">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute"><b style="display:inline;color:var(--fg)">${byt(x.app + x.data + x.cache)}</b> · ${t("dv.appRow", byt(x.app), byt(x.data), byt(x.cache))}</span></div>${btn}</div>`; };
  const apps = Array.isArray(s.apps) ? s.apps : null;
  const sto = `${bar(sp, stoTone, t("dv.sto"))}<p style="margin:4px 0 8px">${t("dv.stoD", byt(used), byt(s.total), Math.round(sp))}</p>
    <h3 style="margin:12px 0 4px;font-size:14px">${t("dv.apps")}</h3>
    ${apps ? apps.map(appRow).join("") || `<p class="mute">—</p>` : `<p class="mute">${t("dv.noUsage")}</p>`}
    ${owner && !d.clearData && apps ? `<p class="mute">${ic("triangle-alert")} ${t("clean.oldOs", esc(d.android))}</p>` : ""}
    <p class="mute">${ic("lightbulb")} ${t("dv.cacheTip")}</p>
    <button type="button" id="dvClean">${ic("eye-off")} ${t("dv.clean")}</button>`;
  const r = d.ram || {}, rp = r.total ? (r.total - r.avail) / r.total * 100 : 0, rTone = r.low ? "b" : rp > 85 ? "w" : "";
  const top = (st.apps || []).filter(x => x.ms >= 30000).sort((a, c) => c.ms - a.ms).slice(0, 5);
  const ram = `${bar(rp, rTone, t("dv.ram"))}<p style="margin:4px 0 8px">${t("dv.ramD", byt(r.avail), byt(r.total))}</p>
    ${r.low ? `<div class="alert warn">${ic("triangle-alert")} ${t("dv.ramLow")}</div>` : ""}
    ${r.perApp ? "" : `<p class="mute">${ic("circle-help")} ${t("dv.ramWhy")}</p>`}
    <h3 style="margin:12px 0 4px;font-size:14px">${t("dv.topUse")}</h3>
    ${top.map(x => `<div class="app">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${t("todayV", fmt(x.ms))}</span></div></div>`).join("") || `<p class="mute">${t("dv.noUse")}</p>`}`;
  const bt = d.battery || {}, tc2 = bt.tempC, hot = tc2 != null && tc2 >= 40, badH = bt.health && !["good", "unknown"].includes(bt.health);
  const deg = v => v.toLocaleString(lang, { maximumFractionDigits: 1 }) + "°C";
  const bTone = bt.pct >= 0 && bt.pct <= 15 && !bt.charging ? "b" : bt.pct <= 30 ? "w" : "";
  const bat = `${bt.pct >= 0 ? bar(bt.pct, bTone, t("dv.bat")) : ""}
    ${hot ? `<div class="alert warn">${ic("flame")} ${t("dv.hot", deg(tc2))}</div>` : ""}
    ${kv(t("dv.plug"), bt.charging ? t("dv.charging", tc("dv.plug.", bt.plug, bt.plug)) : t("dv.notCharging"))}
    ${tc2 != null ? kv(t("dv.temp"), deg(tc2)) : ""}
    ${kv(t("dv.health"), `<span class="tag ${badH ? "bad" : bt.health === "good" ? "ok" : ""}" style="margin:0">${tc("dv.h.", bt.health, bt.health || "?")}</span>`)}
    ${bt.voltageMv > 0 ? kv(t("dv.volt"), (bt.voltageMv / 1000).toLocaleString(lang, { maximumFractionDigits: 2 }) + " V") : ""}
    ${kv(t("dv.scr"), bt.screenOnMs != null ? fmt(bt.screenOnMs) : `<span class="mute" style="font-weight:400">${t("dv.scrOld")}</span>`)}`;
  const n = d.net || {};
  const sys = `${kv(t("dv.model"), esc(d.model))}${kv(t("dv.os"), esc(d.android) + " · API " + (+d.sdk || "?"))}${kv(t("dv.up"), dvDur(+d.uptimeMs || 0))}
    ${kv(t("dv.kg"), esc(d.version))}${kv(t("dv.net"), tc("dv.n.", n.type, esc(n.type)) + (n.metered && n.type !== "none" ? ` <span class="mute" style="font-weight:400">· ${t("dv.metered")}</span>` : ""))}
    ${n.type === "wifi" && n.bars != null ? kv(t("dv.sig"), t("dv.sigN", n.bars, n.rssi)) : ""}`;
  const act = `${isBasic() ? needOwner(t("dv.reboot")) : `<div class="row">${dvBtn("dvBoot", "refresh-cw", t("dv.reboot"), "bad")}</div><p class="mute">${t("dv.rebootD")}</p>`}
    <p class="mute">${ic("lightbulb")} ${t("dv.noBoost")}</p>`;
  b.innerHTML = head
    + sec("d-sto", "hard-drive", t("dv.sto"), "", [t("dv.stoChip", byt(s.free)), stoTone === "b" ? "bad" : stoTone === "w" ? "warn" : "ok"], sto, true)
    + sec("d-ram", "memory-stick", t("dv.ram"), "", [t("dv.ramChip", Math.round(rp)), rTone === "b" ? "bad" : rTone === "w" ? "warn" : "ok"], ram, false)
    + sec("d-bat", "battery-full", t("dv.bat"), "", [t("dv.batChip", bt.pct) + (tc2 != null ? " · " + deg(tc2) : ""), badH || bTone === "b" ? "bad" : hot || bTone === "w" ? "warn" : "ok"], bat, false)
    + sec("d-sys", "smartphone", t("dv.sys"), "", ["Android " + esc(d.android)], sys, false)
    + sec("d-act", "zap", t("dv.act"), "", null, act, true);
  wireDev(b, apps);
}
function wireDev(b, apps) {
  const x = $("#dvRef"); if (x) x.onclick = () => devLoad(true);
  const c = $("#dvClean"); if (c) c.onclick = () => { tab = "apps"; appsSub = "clean"; try { localStorage.setItem("kg.appsSub", "clean"); } catch {} render(true); };
  const r = $("#dvBoot"); if (r) r.onclick = async () => { const k0 = cur, ar = askOf("reboot"); if (!(await ask(k0.name + "\n" + ar[0], ...ar.slice(1))) || k0 !== cur) return; dvRun("dvBoot", () => cmd({ type: "reboot" }, t("reboot.done"), false)); };
  (apps || []).forEach((a, i) => { const e = $("#dvc" + i); if (e && !e.disabled) e.onclick = async () => {
    if (!(await ask(t("clean.askClear", a.label), t("delete"), true))) return;
    dvRun("dvc" + i, () => cmd({ type: "clearData", pkg: a.pkg }, t("clean.cleared", a.label), false)).then(() => setTimeout(() => devLoad(true), 3000));
  }; });
}

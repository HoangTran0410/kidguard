// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
const sel = { on: false, set: new Set(), f: "all", limOpen: false, q: "", n: 40 };   // n: rows built so far (kept across a state redraw)   // q: the search text (kept across redraws: a filter chip, a state refresh)
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
  // "trinh duyet" finds "Trình duyệt"; the package name matches as typed
  const visible = () => { const raw = sel.q.trim().toLowerCase(), q = qsFold(raw); return st.apps.filter(x => !x.protected && pass(x) && (!raw || (q && qsFold(x.label).includes(q)) || x.pkg.toLowerCase().includes(raw))); };
  b.innerHTML = `<div class="card"><div class="row"><h2 class="sp" style="margin:0">${ic("puzzle")} ${t("apps.h", st.apps.length)}</h2><button id="selTog" class="sm${sel.on ? " pri" : ""}">${sel.on ? ic("x") + " " + t("sel.exit") : ic("square-check") + " " + t("sel.on")}</button></div>
    <input id="f-q" type="search" value="${esc(sel.q)}" placeholder="${esc(t("apps.search"))}" aria-label="${esc(t("apps.search"))}" style="width:100%;margin:10px 0 6px">
    <div class="chips scroll" id="flt">${FILT.map(([v, n]) => `<button data-f="${v}" class="${sel.f === v ? "on" : ""}">${n}</button>`).join("")}</div>
    <div id="list"></div><p class="mute">${ic("lightbulb")} ${t("apps.tip")}</p></div>
    ${sel.on ? `<div class="bulk" id="bulk"></div>` : ""}`;
  const drawBar = () => {
    const bar = $("#bulk"); if (!bar) return;
    const n = sel.set.size, dis = n ? "" : "disabled";
    // one slim header (count + select all/none) and one sideways-scrolling row of actions: ~2 rows on a phone, not 5
    const all = visible().length > 0 && visible().every(x => sel.set.has(x.pkg));
    bar.innerHTML = `<div class="row nw"><b class="sp">${ic("check")} ${t("sel.n", n)}</b><button class="sm" id="selAllNone">${all ? ic("square") + " " + t("sel.none") : ic("square-check") + " " + t("sel.all")}</button></div>
      <div class="chips scroll" style="margin-top:6px"><button ${dis} data-a="block" class="bad">${ic("octagon-x")} ${t("block")}</button><button ${dis} data-a="unblock">${ic("circle-check")} ${t("unblock")}</button><button ${dis} data-a="allow">${ic("star")} ${t("allow")}</button><button ${dis} data-a="lim">${ic("timer")} ${t("limitX")}</button><button ${dis} data-a="unlim">${ic("trash-2")} ${t("unlimit")}</button><button ${dis} data-a="approve">${ic("thumbs-up")} ${t("approve")}</button></div>
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
    $("#selAllNone").onclick = () => { if (all) sel.set.clear(); else visible().forEach(x => sel.set.add(x.pkg)); draw(); };
    if ($("#limOk")) $("#limOk").onclick = () => {
      const m = Math.round(+$("#f-lim").value); if (!(m >= 1 && m <= 1440)) return toast("warn|" + t("lim.range"));
      apply("lim", p => { edit.blocked = edit.blocked.filter(v => v !== p); edit.allow = edit.allow.filter(v => v !== p); edit.appMin[p] = m; }, m);
    };
  };
  // a 150-app list = ~1200 nodes (a <select> per row): build 40 rows, then 40 more when the end scrolls near
  let vis = [], shown = 0, io = null;
  const rowHtml = x => {
      const m = mode(x.pkg), nw = isNew(x);
      // ONE tag: the enforced state, only when the dropdown beside it does not already say it (A2.7)
      const on = x.suspended || x.blocked, tag = nw ? `<span class="tag" style="background:#fef3c7;color:#92400e">${ic("sparkles")} ${t("newInst")}</span>`
        : on ? (m === "block" && x.blocked === "blocked" ? "" : `<span class="tag bad">${reason(x.blocked)}</span>`)
        : m === "block" ? `<span class="tag warn">${ic("hourglass")} ${t("app.waitPhone")}</span>` : "";
      const info = `<div class="nm"><b>${esc(x.label)}</b><span class="mute">${t("todayV", fmt(x.ms))}${tag}</span></div>`;
      if (sel.on) return `<label class="app r"><input type="checkbox" data-c="${esc(x.pkg)}" ${sel.set.has(x.pkg) ? "checked" : ""}>${icon(x)}${info}</label>`;
      return `<div class="app${nw ? "" : " r"}">${icon(x)}${info}
      ${nw ? `<button class="pri" data-ok="${esc(x.pkg)}">${ic("circle-check")} ${t("permit")}</button>` : ""}
      <select data-p="${esc(x.pkg)}" aria-label="${esc(t("apps.rule", x.label))}">
        <option value="normal" ${m === "normal" ? "selected" : ""}>${t("normal")}</option>
        ${[15, 30, 45, 60, 90, 120].map(n => `<option value="${n}" ${m === "limit" && edit.appMin[x.pkg] === n ? "selected" : ""}>${t("perDay", n)}</option>`).join("")}
        ${m === "limit" && ![15, 30, 45, 60, 90, 120].includes(edit.appMin[x.pkg]) ? `<option value="${edit.appMin[x.pkg]}" selected>${t("perDay", edit.appMin[x.pkg])}</option>` : ""}
        <option value="block" ${m === "block" ? "selected" : ""}>${t("block")}</option>
        <option value="allow" ${m === "allow" ? "selected" : ""}>${t("allow")}</option>
      </select></div>`;
  };
  const bindRows = box => {
    box.querySelectorAll("[data-ok]").forEach(bt => bt.onclick = () => { edit.approved.push(bt.dataset.ok); changed(); draw(); toast("ok|" + t("permitted")); });
    box.querySelectorAll("[data-c]").forEach(c => c.onchange = () => { c.checked ? sel.set.add(c.dataset.c) : sel.set.delete(c.dataset.c); drawBar(); });
    box.querySelectorAll("select").forEach(s => s.onchange = () => {
      const p = s.dataset.p, v = s.value;
      edit.blocked = edit.blocked.filter(x => x !== p); edit.allow = edit.allow.filter(x => x !== p); delete edit.appMin[p];
      if (v === "block") edit.blocked.push(p); else if (v === "allow") edit.allow.push(p); else if (v !== "normal") edit.appMin[p] = +v;
      changed();
    });
  };
  const more = () => {
    const l = $("#list"); if (!l) return;
    const old = l.querySelector(".lmore"); if (old) old.remove();
    const box = document.createElement("div"); box.innerHTML = vis.slice(shown, shown + 40).map(rowHtml).join(""); shown = Math.min(vis.length, shown + 40); sel.n = Math.max(40, shown);
    bindRows(box); l.append(...box.childNodes);
    if (shown < vis.length) {
      const m = document.createElement("button"); m.type = "button"; m.className = "sm lmore"; m.style.marginTop = "8px";
      m.innerHTML = `${ic("chevron-down")} ${t("apps.more", vis.length - shown)}`; m.onclick = more; l.append(m);   // also a button: no IntersectionObserver / a screen reader
      if (io) io.observe(m);
    }
  };
  const draw = () => {
    vis = visible(); shown = 0;
    if (io) io.disconnect();
    io = window.IntersectionObserver ? new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) more(); }, { rootMargin: "600px" }) : null;
    const l = $("#list"); l.innerHTML = vis.length ? "" : `<div class="empty"><span class="e">${ic("circle-dashed")}</span>${t("apps.none")}</div>`;
    const want = sel.n; if (vis.length) do more(); while (shown < Math.min(want, vis.length));
    drawBar();
  };
  $("#f-q").oninput = e => { sel.q = e.target.value; sel.n = 40; draw(); };
  $("#selTog").onclick = () => { sel.on = !sel.on; sel.set.clear(); sel.limOpen = false; appsList(b); };
  b.querySelectorAll("#flt [data-f]").forEach(x => x.onclick = () => { sel.f = x.dataset.f; sel.n = 40; appsList(b); });
  draw();
}

// v0.8 phone clean-up: hide preinstalled bloat (owner), clear an app's data (owner, Android 9+), heaviest apps (both modes)
// Hiding is edited as a draft (hideDraft, only while it differs from rules.hidden) and saved with ONE rules update.
// Per-app result comes from the phone's report: state.rules.hidden = what the phone has taken, preinstalled[].hidden = isApplicationHidden.
// Wanted + taken + still not hidden = the phone refused (ColorOS refuses some of its own apps, e.g. the game center / store / themes).
let hideDraft = null;
let hideSentAt = null;   // {k, at}: when the hide list was last sent; a wait older than 10 min reads "not applied"
const mbTxt = m => m >= 1024 ? (m / 1024).toLocaleString(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) + " GB" : m + " MB";
function cleanStat() {
  const pre = (st && st.preinstalled) || [], saved = new Set((edit && edit.hidden) || []), took = new Set((st && st.rules && st.rules.hidden) || []);
  // preinstalled[].refused (phone >= 0.9.8): the OS said no to hiding it, so it counts as not hidden whatever "hidden" says
  // basic mode without its own adb (adbWhy set): nothing can be hidden yet; that is "waiting for adb", not "the phone refused"
  const noAdb = !!(st && st.adbWhy && (isBasic() || st.owner === false));
  const of = x => { const h = x.hidden && !x.refused; const s = saved.has(x.pkg) ? (x.refused ? "ref" : took.has(x.pkg) ? (h ? "ok" : "ref") : "wait") : (h && took.has(x.pkg) ? "show" : ""); return s === "ref" && noAdb && !x.refused ? "adb" : s; };
  const n = { want: 0, ok: 0, ref: 0, wait: 0, of };
  pre.forEach(x => { const s = of(x); if (saved.has(x.pkg)) { n.want++; if (s === "ok" || s === "ref" || s === "wait") n[s]++; else if (s === "adb") n.wait++; } });
  return n;
}
const sameSet = (a, b) => a.size === b.size && [...a].every(x => b.has(x));
// Android 9+ (API 28) for DevicePolicyManager.clearApplicationUserData; state has only the release string ("8.1.0")
const canClear = () => !st || !st.android || !(parseInt(st.android, 10) < 9);
function cleanTab(b) {
  // No Device Owner: the phone hides via its own Wireless-debugging adb and sends state.preinstalled only while that works
  const owner = !isBasic() && st.owner !== false;
  // basic: adbWhy (sdk / key / wss / blocked / wifi) says what is missing; old phones send none (generic text)
  const why = !owner && st.adbWhy ? `<p class="mute">${ic("wifi")} ${t("clean.why." + st.adbWhy)}</p>` : "";
  if (!owner && !st.preinstalled) { b.innerHTML = `<div class="card"><p class="mute">${ic("lock")} ${t(st.adbWhy ? "clean.why." + st.adbWhy : "clean.needAdb")}</p></div>`; return; }
  if (!st.preinstalled) { b.innerHTML = `<div class="card"><p class="mute">${ic("hourglass")} ${t("clean.noList")}</p></div>`; return; }
  const pre = st.preinstalled, saved = new Set(edit.hidden), S = cleanStat();
  const draft = () => hideDraft || saved;
  // waiting rows: say why (phone offline since / adb link) + Retry; 10 min after the send it reads "not applied"
  const hdWait = () => { const n = cleanStat().wait; if (!n) return ""; const late = hideSentAt && hideSentAt.k === cur && Date.now() - hideSentAt.at > 600000, why = waitWhy(true);
    return `<div class="alert ${late ? "warn" : "info"}" style="font-weight:400;font-size:13px">${ic(late ? "triangle-alert" : "hourglass")} <span>${t(late ? "pw.hdLate" : "pw.hdWait", n)}${why ? " · " + esc(why) : ""}</span> <button type="button" data-pact="hdwait">${ic("refresh-cw")} ${t("pw.retry")}</button></div>`; };
  const hdRetry = () => { const k = cur; if (!k || !edit || viewDay) return; putRules(k, edit).then(() => { hideSentAt = { k, at: Date.now() }; toast("send|" + t("pw.resent")); setTimeout(refresh, 1500); }).catch(e => toast("err|" + t("errX", e.message))); };
  const sug = pre.filter(x => x.suggest || saved.has(x.pkg) || x.hidden), rest = pre.filter(x => !sug.includes(x));
  const TAG = { ok: `<span class="tag ok">${ic("check")} ${t("hd.ok")}</span>`, ref: `<span class="tag bad">${ic("ban")} ${t("hd.refused")}</span>`,
    wait: `<span class="tag">${ic("hourglass")} ${t("hd.waiting")}</span>`, adb: `<span class="tag warn">${ic("lock")} ${t("hd.noAdb")}</span>`, show: `<span class="tag">${ic("hourglass")} ${t("hd.showing")}</span>` };
  const row = x => `<label class="app"><input type="checkbox" data-hd="${esc(x.pkg)}" ${draft().has(x.pkg) ? "checked" : ""}><div class="nm"><b>${esc(x.label)}</b><span class="mute">${esc(x.pkg)}${x.mb ? " · " + mbTxt(x.mb) : ""}${x.suggest ? `<span class="tag warn">${t("clean.sug")}</span>` : ""}${TAG[S.of(x)] || ""}</span></div></label>`;
  const pc = v => S.want ? (v / S.want * 100).toFixed(1) + "%" : "0";
  const nSug = pre.filter(x => x.suggest).length;
  b.innerHTML = `<div class="card"><h2>${ic("sparkles")} ${t("clean.h")}</h2>${why}
    <p class="mute" style="margin:0">${t("clean.p", "<b>" + pre.filter(x => x.hidden && !x.refused).length + "</b>")}</p>
    ${S.want ? `<div class="prog" role="img" aria-label="${esc(t("hd.sum", S.ok, S.want))}"><i style="width:${pc(S.ok)}"></i><i class="b" style="width:${pc(S.ref)}"></i><i class="w" style="width:${pc(S.wait)}"></i></div>
      <div><b>${t("hd.sum", S.ok, S.want)}</b>${S.ref ? ` · <span class="err">${t("hd.refN", S.ref)}</span>` : ""}${S.wait ? ` · <span class="mute">${t("hd.waitN", S.wait)}</span>` : ""}</div>
      ${S.wait ? pendHtml("hdwait", hdWait, hdRetry) : ""}
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
      hideSentAt = { k: cur, at: Date.now() }; { const w = waitWhy(true); toast(w ? "warn|" + t("hd.sentOff", w) : "ok|" + t("hd.sent")); } cleanTab(b); setTimeout(refresh, 1500);
    };
  };
  const setD = s => { hideDraft = sameSet(s, saved) ? null : s; };
  b.querySelectorAll("[data-hd]").forEach(c => c.onchange = () => { const s = new Set(draft()); c.checked ? s.add(c.dataset.hd) : s.delete(c.dataset.hd); setD(s); bar(); });
  bar();
}
let spaceN = 15;   // heavy list rows shown: starts at 15, "Xem thêm" adds 20
let cacheBusy = 0;      // clearCache sent at (ms): animated busy box until the answer, across redraws; the list is locked
let healthBusy = "";   // fix-all / single fix running: the i18n key of the step now; the list is locked, one command at a time
let scanBusy = 0;       // rescan sent at (ms): the "Quét lại" button spins, the list is locked
// ---- "Sức khoẻ máy": state.health {score, checks[{id, ok, w, fix, v}], limited} (docs/protocol.md "Phone health"). The score comes from the phone.
const HL_GROUPS = [["safe", "shield", ["devOpts", "installs", "admins", "sideload", "patch"]], ["speed", "zap", ["storage", "cache", "bg"]], ["bat", "battery-full", ["battery"]]];
const HL_ORDER = ["cache", "bg", "devOpts"];   // review order: devOpts last (it takes KidGuard's own adb away)
const HL_STEP = {   // id -> the command and the i18n key of the busy line
  cache: () => ({ id: "cache", c: { type: "clearCache" }, busy: "hl.s.cache" }),
  bg: pkgs => ({ id: "bg", n: pkgs.length, c: { type: "bgLimit", pkgs }, busy: "hl.s.bg" }),
  devOpts: () => ({ id: "dev", c: { type: "devOff" }, busy: "hl.s.dev" }),
};
let hlResult = null;   // {at, items:[{id, n, r: ok | queued | fail | noadb}]}: what the last run did, per step
let hlSheetDraw = null;   // redraw of the open review / background sheet (a command finished)
const hlOwn = c => !!c && c.id === "devOpts" && c.v === 2;   // on, but KidGuard's own adb uses it: info, not an issue
function hlVal(c) { return c.id === "cache" ? mbTxt(c.v) : c.id === "patch" ? t("hl.days", c.v) : c.id === "storage" ? c.v + "%" : c.id === "battery" ? c.v + "%" : c.v; }
const bgIdleList = () => (st.apps || []).filter(x => x.bgIdle && x.bg === true && !x.bgKeep).map(x => x.pkg);
const bgMineList = () => (st.apps || []).filter(x => x.bgMine).map(x => x.pkg);
// one confirm text for every fix: what it does, what the child notices, what changes in KidGuard, how to undo
function hlAskText(id) {
  const own = id === "devOpts" && hlOwn(((st.health || {}).checks || []).find(c => c.id === "devOpts")), k = own ? "devOwn" : id;
  const undo = id === "devOpts" ? t(st && st.dev && st.dev.canOn ? "dev.askOffBack" : "dev.askOffNoBack") : t("hl.i." + k + ".undo");
  return [["what", t("hl.i." + k + ".what")], ["notice", t("hl.i." + k + ".notice")], ["kg", t("hl.i." + k + ".kg")], ["undo", undo]].map(([a, s]) => t("hl.k." + a) + " " + s).join("\n");
}
const hlResLine = it => t("hl.ri." + it.id, it.n) + ": " + t("hl.r." + it.r);
function hlBusyBox(key, icn) { return `<div class="cbusy" role="status" aria-live="polite" style="margin:12px 0"><div class="cbusy-h"><span class="cbusy-ic">${ic(icn || "sparkles")}</span><span><b>${t(key)}</b><br><span class="mute">${t("hl.busy")}</span></span></div><div class="cbusy-bar"><i></i></div></div>`; }
function hlResBox(since) {
  if (!hlResult || hlResult.at < (since || Date.now() - 6 * 3600000)) return "";
  const bad = hlResult.items.some(i => i.r === "fail" || i.r === "noadb");
  return `<div class="alert ${bad ? "warn" : "ok"}" style="margin:10px 0;font-weight:400;font-size:14px;white-space:pre-line">${ic(bad ? "triangle-alert" : "circle-check")} ${hhmm(hlResult.at)}\n${hlResult.items.map(hlResLine).map(esc).join("\n")}</div>`;
}
function healthCard() {
  const h = st && st.health;
  if (!h || !Array.isArray(h.checks)) return `<div class="card"><h2 style="margin:0">${ic("heart-pulse")} ${t("hl.h")}</h2><p class="mute">${ic("hourglass")} ${t("hl.old")}</p></div>`;
  const sc = Math.max(0, Math.min(100, +h.score || 0)), tone = sc >= 80 ? "var(--ok)" : sc >= 50 ? "var(--warn)" : "var(--bad)", C = 2 * Math.PI * 52;
  const by = Object.fromEntries(h.checks.map(c => [c.id, c])), fixes = HL_ORDER.filter(i => by[i] && !by[i].ok && by[i].fix), busy = !!(healthBusy || cacheBusy || scanBusy);
  const lim = +h.limited || 0;
  const btn = c => {
    if (c.id === "bg") return c.fix || lim ? `<button type="button" class="sm" data-hlbg="1">${ic("list-checks")} ${t("hl.bgOpen")}</button>` : "";
    if (!c.fix) return "";
    return `<button type="button" class="sm${hlOwn(c) ? " bad" : ""}" data-hlfix="${c.id}"${busy ? " disabled" : ""}>${ic("sparkles")} ${t(hlOwn(c) ? "hl.devOffOwn" : "hl.fix")}</button>`;
  };
  const row = c => { const own = hlOwn(c);
    return `<div class="app" style="align-items:center"><span style="color:${own ? "var(--accent)" : c.ok ? "var(--ok)" : "var(--warn)"};flex:none">${ic(own ? "info" : c.ok ? "check" : "triangle-alert")}</span>
    <div class="nm"><b>${own ? t("hl.c.devOpts.own") : t("hl.c." + c.id + (c.ok ? ".ok" : ".bad"), hlVal(c))}</b>${own ? `<span class="mute">${t("hl.c.devOpts.ownWhy")}</span>` : c.ok ? "" : `<span class="mute">${t("hl.c." + c.id + ".why")}</span>`}</div>
    ${btn(c)}</div>`; };
  const groups = HL_GROUPS.map(([g, i, ids]) => { const cs = ids.map(x => by[x]).filter(Boolean); return cs.length ? `<h3 style="margin:14px 0 2px;font-size:14px">${ic(i)} ${t("hl.g." + g)}</h3>${cs.map(row).join("")}` : ""; }).join("");
  return `<div class="card" id="healthCard"><h2 style="margin:0 0 8px">${ic("heart-pulse")} ${t("hl.h")}</h2>
    <div style="display:flex;align-items:center;gap:16px"><svg viewBox="0 0 120 120" width="112" height="112" role="img" aria-label="${esc(t("hl.score", sc))}" style="flex:none"><circle cx="60" cy="60" r="52" fill="none" stroke="var(--track)" stroke-width="10"/>
      <circle cx="60" cy="60" r="52" fill="none" stroke="${tone}" stroke-width="10" stroke-linecap="round" stroke-dasharray="${(C * sc / 100).toFixed(1)} ${C.toFixed(1)}" transform="rotate(-90 60 60)"/>
      <text x="60" y="68" text-anchor="middle" font-size="30" font-weight="700" fill="currentColor">${sc}</text></svg>
      <div class="sp"><b style="font-size:17px">${t(sc >= 80 ? "hl.good" : sc >= 50 ? "hl.mid" : "hl.low")}</b><p class="mute" style="margin:2px 0 8px;font-size:13px">${t(fixes.length ? "hl.nFix" : "hl.noFix", fixes.length)}</p>
      ${fixes.length ? `<button type="button" id="hlAll" class="pri"${busy ? " disabled" : ""}>${ic("list-checks")} ${t("hl.review")}</button>` : ""}</div></div>
    ${healthBusy ? hlBusyBox(healthBusy) : hlResBox()}
    <div${busy ? ' class="cbusy-lock" inert aria-disabled="true"' : ""}>${groups}
    ${lim ? `<button type="button" id="hlBack" class="sm" style="margin-top:10px">${ic("undo-2")} ${t("hl.back", lim)}</button>` : ""}
    <label class="row" style="margin-top:14px"><input type="checkbox" id="f-kc" ${edit && edit.kidClean ? "checked" : ""}${edit ? "" : " disabled"}><span class="sp">${ic("eraser")} ${t("hl.kc")}<br><span class="mute" style="font-size:13px">${t("hl.kcP")}</span></span></label></div>
    <p class="mute" style="margin-bottom:0">${ic("lightbulb")} ${t("hl.note")}</p></div>`;
}
function hlDraw() { if (tab === "apps") spaceTabRedraw(); if (hlSheetDraw) hlSheetDraw(); }
// runs the steps one by one (the phone's adb runs commands in turn); busy line + locked list the whole time, result per step at the end
async function hlExec(steps) {
  if (healthBusy || cacheBusy || scanBusy || !steps.length) return false;
  const items = [];
  for (const s of steps) {
    healthBusy = s.busy; hlDraw();
    const r = await cmd(s.c, null, false);
    items.push({ id: s.id, n: s.n, r: !r ? (st && st.clearCache ? "fail" : "noadb") : r.result === "queued" ? "queued" : "ok" });
    if (!r) break;
  }
  healthBusy = ""; hlResult = { at: Date.now(), items }; hlDraw();
  // the phone rebuilds its state on the tick the command kicks: the poll right after may still carry the old app list
  await refresh(); hlDraw();
  setTimeout(() => refresh().then(hlDraw), 2500);
  return items.every(i => i.r === "ok" || i.r === "queued");
}
function spaceTabRedraw() { const p = $("#subp"); if (p && appsSub === "space") spaceTab(p); }
// "Xem & sửa": every proposed change with a switch, its effect and its undo; nothing runs before "Chạy"
function hlReview() {
  const h = st && st.health; if (!h) return;
  const by = Object.fromEntries(h.checks.map(c => [c.id, c])), ids = HL_ORDER.filter(i => by[i] && !by[i].ok && by[i].fix);
  const idle = bgIdleList(), sel = new Set(ids.filter(i => i === "cache"));   // only the safe one starts ticked
  const info = {
    cache: [t("hl.rv.cache", mbTxt(by.cache ? by.cache.v : 0)), t("hl.rv.cache.e"), t("hl.rv.cache.u")],
    bg: [t("hl.rv.bg", idle.length), t("hl.rv.bg.e"), t("hl.rv.bg.u")],
    devOpts: [t("hl.rv.dev"), t("hl.rv.dev.e") + (st.dev && st.dev.canOn ? "" : " " + t("dev.askOffNoBack")), t(st.dev && st.dev.canOn ? "dev.askOffBack" : "dev.askOffNoBack")],
  };
  const body = () => `<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("list-checks", t("hl.review"))}
    <p class="mute" style="margin:0 0 8px">${t("hl.reviewP")}</p>
    ${ids.map(i => i === "bg" && !idle.length
      ? `<div class="row" style="margin:8px 0;align-items:flex-start;flex-wrap:nowrap"><span class="sp"><b>${t("hl.rv.bgPick")}</b><br><span class="mute">${t("hl.rv.bgNone")}</span></span><button type="button" class="sm" data-rvbg="1">${ic("list-checks")} ${t("hl.bgOpen")}</button></div>`
      : `<label class="row" style="margin:8px 0;align-items:flex-start;flex-wrap:nowrap"><input type="checkbox" data-rv="${i}"${sel.has(i) ? " checked" : ""}><span class="sp"><b>${esc(info[i][0])}</b><br><span class="mute">${esc(info[i][1])}</span><br><span class="mute">${ic("undo-2")} ${esc(info[i][2])}</span></span></label>`).join("")}
    <div class="row" style="margin-top:14px"><button type="button" class="pri sp" id="rvGo">${ic("check")} <span id="rvGoT"></span></button></div></div>`;
  sheet(body(), (pop, done) => {
    const upd = () => { const go = pop.querySelector("#rvGo"); go.disabled = !sel.size; pop.querySelector("#rvGoT").textContent = sel.size ? t("hl.run", sel.size) : t("hl.runNone"); };
    pop.querySelectorAll("[data-rv]").forEach(c => c.onchange = () => { c.checked ? sel.add(c.dataset.rv) : sel.delete(c.dataset.rv); upd(); });
    const pk = pop.querySelector("[data-rvbg]"); if (pk) pk.onclick = () => { done(false); hlBgSheet(); };
    pop.querySelector("#rvGo").onclick = () => { const steps = HL_ORDER.filter(i => sel.has(i)).map(i => HL_STEP[i](idle)); done(true); hlExec(steps); };
    upd();
  });
}
// "Hạn chế app chạy nền": every app with its own switch; locked rows say why; per-app undo; nothing runs before the confirm step
function hlBgSheet() {
  let q = "", stage = "list", live = true; const sel = new Set(), t0 = Date.now();
  const apps = () => (st.apps || []).filter(x => x.bg !== undefined || x.bgKeep || x.bgMine).sort((a, b) => (a.label || "").localeCompare(b.label || ""));
  const rowOf = x => {
    if (x.bgKeep) return `<div class="app" style="align-items:center">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${ic("lock")} ${t("hl.bg.keep")}</span></div></div>`;
    if (x.bg === false) return `<div class="app" style="align-items:center">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${t(x.bgMine ? "hl.bg.mine" : "hl.bg.other")}</span></div>${x.bgMine ? `<button type="button" class="sm" data-bgun="${esc(x.pkg)}" aria-label="${esc(t("hl.bg.undo") + " " + x.label)}">${ic("undo-2")} ${t("hl.bg.undo")}</button>` : ""}</div>`;
    return `<label class="row" style="flex-wrap:nowrap;padding:4px 0"><input type="checkbox" data-bgs="${esc(x.pkg)}"${sel.has(x.pkg) ? " checked" : ""}>${icon(x)}<span class="sp"><b>${esc(x.label)}</b>${x.bgIdle ? `<br><span class="mute">${t("hl.bg.idle")}</span>` : ""}</span></label>`;
  };
  const list = () => { const s = q.trim().toLowerCase(), a = apps().filter(x => !s || (x.label || "").toLowerCase().includes(s) || x.pkg.toLowerCase().includes(s)); return a.length ? a.map(rowOf).join("") : `<p class="mute">${t("hl.bg.none")}</p>`; };
  const nameOf = p => ((st.apps || []).find(x => x.pkg === p) || { label: p }).label;
  const pop = () => $("#askPop");
  const draw = () => {
    const p = pop(); if (!live || !p) return;
    const all = apps(), run = all.filter(x => x.bg === true).length, mine = bgMineList(), idle = bgIdleList(), n = sel.size, adb = !!st.clearCache;
    if (stage === "confirm") {
      const names = [...sel].map(nameOf), shown = names.slice(0, 8).join(", ") + (names.length > 8 ? " +" + (names.length - 8) : "");
      p.innerHTML = `<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("triangle-alert", t("hl.bg.confirmH", n))}
        <p style="margin:0 0 8px">${esc(shown)}</p><div style="white-space:pre-line;margin-bottom:12px">${esc(["what", "notice", "kg", "undo"].map(a => t("hl.k." + a) + " " + t("hl.i.bg." + a)).join("\n"))}</div>
        <div class="row"><button type="button" class="sp" id="bgBack">${ic("arrow-left")} ${t("hl.bg.back")}</button><button type="button" class="sp bad" id="bgOk">${ic("check")} ${t("hl.bg.go", n)}</button></div></div>`;
      p.querySelector("#askNo").onclick = () => { stage = "list"; draw(); };
      p.querySelector("#bgBack").onclick = () => { stage = "list"; draw(); };
      p.querySelector("#bgOk").onclick = () => { const pk = [...sel]; sel.clear(); stage = "list"; run2([HL_STEP.bg(pk)]); };
      return;
    }
    const busy = !!(healthBusy || cacheBusy || scanBusy);
    p.innerHTML = `<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("zap", t("hl.bg.h"))}
      <p class="mute" style="margin:0 0 8px">${t("hl.bg.sub", run, mine.length)}</p>
      ${healthBusy ? hlBusyBox(healthBusy, "zap") : hlResBox(t0)}
      ${adb ? "" : `<div class="alert warn" style="margin:0 0 8px;font-weight:400;font-size:14px">${ic("triangle-alert")} ${t("hl.bg.noAdb")}</div>`}
      <div${busy || !adb ? ' class="cbusy-lock" inert aria-disabled="true"' : ""}>
      <input id="bgQ" type="search" placeholder="${esc(t("hl.bg.search"))}" aria-label="${esc(t("hl.bg.search"))}" value="${esc(q)}" style="width:100%;margin-bottom:8px">
      <div class="chips" style="margin-bottom:8px"><button type="button" id="bgSug"${idle.length ? "" : " disabled"}>${ic("sparkles")} ${t("hl.bg.sug", idle.length)}</button><button type="button" id="bgClr"${n ? "" : " hidden"}>${ic("x")} ${t("hl.bg.clr")}</button></div>
      ${idle.length ? "" : `<p class="mute" style="margin:0 0 8px;font-size:13px">${t("hl.bg.sugWhy")}</p>`}
      <div id="bgList">${list()}</div>
      <div class="row" style="margin-top:14px"><button type="button" class="pri sp" id="bgGo"${n ? "" : " disabled"}>${ic("check")} ${n ? t("hl.bg.limit", n) : t("hl.bg.pick")}</button></div>
      ${mine.length ? `<div class="row" style="margin-top:8px"><button type="button" class="sp" id="bgAll">${ic("undo-2")} ${t("hl.bg.undoAll", mine.length)}</button></div>` : ""}</div></div>`;
    wire(p);
  };
  const wire = p => {
    p.querySelector("#askNo").onclick = () => { if (askDone) askDone(false); };
    const bind = () => {
      p.querySelectorAll("[data-bgs]").forEach(c => c.onchange = () => { c.checked ? sel.add(c.dataset.bgs) : sel.delete(c.dataset.bgs); upd(); });
      p.querySelectorAll("[data-bgun]").forEach(b => b.onclick = () => run2([{ id: "back", n: 1, c: { type: "bgRestore", pkgs: [b.dataset.bgun] }, busy: "hl.s.back" }]));
    };
    const upd = () => { const g = p.querySelector("#bgGo"); g.disabled = !sel.size; g.lastChild.textContent = " " + (sel.size ? t("hl.bg.limit", sel.size) : t("hl.bg.pick")); p.querySelector("#bgClr").hidden = !sel.size; };
    bind();
    const qi = p.querySelector("#bgQ"); if (qi) qi.oninput = () => { q = qi.value; p.querySelector("#bgList").innerHTML = list(); bind(); };   // only the list: the box keeps its focus
    const sg = p.querySelector("#bgSug"); if (sg) sg.onclick = () => { bgIdleList().forEach(x => sel.add(x)); draw(); };
    const cl = p.querySelector("#bgClr"); if (cl) cl.onclick = () => { sel.clear(); draw(); };
    const go = p.querySelector("#bgGo"); if (go) go.onclick = () => { if (sel.size) { stage = "confirm"; draw(); } };
    const al = p.querySelector("#bgAll"); if (al) al.onclick = () => { const m = bgMineList(); run2([{ id: "back", n: m.length, c: { type: "bgRestore" }, busy: "hl.s.back" }]); };
  };
  const run2 = async steps => { await hlExec(steps); };
  hlSheetDraw = draw;
  sheet("").then(() => { live = false; if (hlSheetDraw === draw) hlSheetDraw = null; });
  draw();
}
function healthBind(b) {
  const all = b.querySelector("#hlAll"); if (all) all.onclick = hlReview;
  b.querySelectorAll("[data-hlbg]").forEach(x => x.onclick = hlBgSheet);
  b.querySelectorAll("[data-hlfix]").forEach(x => x.onclick = async () => {
    const id = x.dataset.hlfix, c = ((st.health || {}).checks || []).find(y => y.id === id), own = hlOwn(c);
    if (await ask(hlAskText(id), t(own ? "hl.devOffOwn" : id === "devOpts" ? "dev.btn" : "hl.fix"), id === "devOpts")) hlExec([HL_STEP[id]([])]);
  });
  const bk = b.querySelector("#hlBack"); if (bk) bk.onclick = async () => {
    const m = bgMineList().length || +(st.health || {}).limited || 0;
    if (await ask(t("hl.askBack", m), t("hl.bg.undoAll", m))) hlExec([{ id: "back", n: m, c: { type: "bgRestore" }, busy: "hl.s.back" }]);
  };
  const kc = b.querySelector("#f-kc"); if (kc) kc.onchange = e => { if (!edit) return; edit.kidClean = e.target.checked; changed(); };
}
function spaceTab(b) {
  const all = (st.apps || []).filter(x => x.mb > 0).sort((a, b) => b.mb - a.mb), heavy = all.slice(0, spaceN);
  // basic mode: `pm clear` through the self-adb shell (any Android version), usable while state.preinstalled is sent
  const owner = !isBasic() && st.owner !== false, cc = owner ? canClear() : !!st.preinstalled;
  // clearCache: every app's cache at once (`pm trim-caches` via KidGuard's own adb, both modes); per app is not possible
  const cacheMb = (st.apps || []).reduce((n, x) => n + (+x.cache || 0), 0), cache = !!st.clearCache && cacheMb >= 50;
  const busy = !!(cacheBusy || scanBusy || healthBusy);
  b.innerHTML = healthCard() + `<div class="card"><div style="display:flex;align-items:center;gap:8px"><h2 style="flex:1;margin:0">${ic("hard-drive")} ${t("clean.heavy")}</h2>
    ${st.sizesAt !== undefined ? `<button type="button" id="spScan" class="sm"${busy ? " disabled" : ""}>${scanBusy ? '<i class="spin"></i>' : ic("refresh-cw")} ${t(scanBusy ? "clean.scanning" : "clean.scan")}</button>` : ""}</div>
    ${st.sizesAt ? `<p class="mute" style="margin:4px 0 10px;font-size:13px">${t("clean.measured", hhmm(st.sizesAt))}</p>` : `<div style="height:10px"></div>`}
    ${owner && !cc ? `<div class="alert warn" style="margin:0 0 8px;font-weight:400;font-size:13px">${ic("triangle-alert")} ${t("clean.oldOs", esc(st.android))}</div>` : ""}
    ${cacheBusy && !healthBusy ? `<div class="cbusy" role="status" aria-live="polite"><div class="cbusy-h"><span class="cbusy-ic">${ic("eraser")}</span><span><b>${t("clean.cacheBusyT")}</b><br><span class="mute">${t("clean.cacheBusy")}</span></span></div><div class="cbusy-bar"><i></i></div></div>`
      : cache && !healthBusy ? `<button type="button" id="spCache"${scanBusy ? " disabled" : ""} class="sp" style="width:100%;display:flex;gap:12px;align-items:center;text-align:start;margin:0 0 6px">${ic("eraser")}<span><b>${t("clean.cacheBtn", mbTxt(cacheMb))}</b><br><span class="mute" style="font-size:13px;font-weight:400">${t("clean.cacheSub")}</span></span></button>` : ""}
    ${!cacheBusy && st.cacheCleared && Date.now() - st.cacheCleared.at < 6 * 3600000 ? `<p class="mute" style="margin:0 0 8px">${ic("circle-check")} ${t("clean.cacheDone", hhmm(st.cacheCleared.at), mbTxt(+st.cacheCleared.mb || 0))}</p>` : ""}
    <div${busy ? ' class="cbusy-lock" inert aria-disabled="true"' : ""}>${heavy.length ? heavy.map(x => `<div class="app">${icon(x)}<div class="nm"><b>${esc(x.label)}</b><span class="mute">${mbTxt(x.mb)}${x.cache > 0 ? " · " + t("clean.cacheOf", mbTxt(x.cache)) : ""}</span></div>${cc && !x.protected && !x.noClear ? `<button type="button" data-clr="${esc(x.pkg)}" class="sm bad" aria-label="${esc(t("clean.clear") + " " + x.label)}">${ic("trash-2")} ${t("delete")}</button>` : ""}</div>`).join("")
      + (all.length > heavy.length ? `<button type="button" id="spAll" class="sm" style="margin-top:8px">${ic("chevron-down")} ${t("clean.more", Math.min(20, all.length - heavy.length), all.length - heavy.length)}</button>` : "")
      : `<p class="mute">${t("clean.noSize")}</p>`}</div>
    <p class="mute">${ic("lightbulb")} ${cc ? t("clean.tipOwner") + " " : ""}${cc ? "" : t("clean.tipBasic") + " "}${t("clean.tipRam")}</p></div>`;
  healthBind(b);
  const more = b.querySelector("#spAll"); if (more) more.onclick = () => { spaceN += 20; spaceTab(b); };
  const cb = b.querySelector("#spCache");
  const sc = b.querySelector("#spScan");
  if (sc) sc.onclick = async () => {
    if (cacheBusy || scanBusy || healthBusy) return;
    scanBusy = Date.now(); spaceTab(b);
    try { await cmd({ type: "rescan" }, t("clean.scanned"), false); } finally { scanBusy = 0; }
    await refresh();
  };
  if (cb) cb.onclick = async () => {
    if (cacheBusy || scanBusy || healthBusy) return;
    cacheBusy = Date.now(); spaceTab(b);   // one run at a time: the phone's adb runs commands one by one
    try { await cmd({ type: "clearCache" }, t("clean.clearedCache"), false); } finally { cacheBusy = 0; }
    await refresh();   // the phone rebuilds its state right after (kick), sizes measured again
  };
  b.querySelectorAll("[data-clr]").forEach(x => x.onclick = () => {
    if (cacheBusy || scanBusy || healthBusy) return;
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
  const r = $("#dvBoot"); if (r) r.onclick = async () => { const k0 = cur, ar = askOf("reboot"); if (!(await ask(k0.name + "\n" + ar[0], ...ar.slice(1))) || k0 !== cur) return; dvRun("dvBoot", () => cmd({ type: "reboot" }, t("reboot.done"), false).then(r => { if (r) rebootStart(); return r; })); };
  (apps || []).forEach((a, i) => { const e = $("#dvc" + i); if (e && !e.disabled) e.onclick = async () => {
    if (!(await ask(t("clean.askClear", a.label), t("delete"), true))) return;
    dvRun("dvc" + i, () => cmd({ type: "clearData", pkg: a.pkg }, t("clean.cleared", a.label), false)).then(() => setTimeout(() => devLoad(true), 3000));
  }; });
}

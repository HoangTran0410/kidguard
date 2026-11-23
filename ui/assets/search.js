// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- quick search (QS): one row per section, '/' key + header button; qsGo opens the section ----
// Quick search ("Tìm chức năng"): every section of the page by its title, description and the switches inside,
// accent-free (also used by the kid app's own admin page, which loads this page). Row: [tab, section id, rules
// segment, icon, i18n keys (0 = title, 1 = description, 2+ = switches / words inside)]. New section = one row here.
const QS = [
  ["rules", "r-day", "time", "timer", ["daily.h", "sd.daily"]], ["rules", "r-grp", "time", "layout-grid", ["grp.h", "sd.grp"]],
  ["rules", "r-night", "time", "moon", ["bed.h", "sd.bed"]], ["rules", "r-night", "time", "moon", ["wd.h", "sd.wind", "wd.gray", "wd.nudge"]],
  ["rules", "r-sch", "time", "book-open", ["sch.h", "sd.sch"]], ["rules", "r-day", "time", "star", ["chore.h", "sd.chore"]],
  ["rules", "r-block", "safe", "construction", ["more.h", "sd.more", "more.loc", "more.air", "ni.h", "ni.ask", "ni.none"]],
  ["rules", "r-net", "safe", "shield", ["dns.h", "sd.dns", "dns.novpn"]],
  ["rules", "r-net", "safe", "globe", ["web.h", "sd.web", "web.inc", "web.block"]],
  ["rules", "r-calls", "safe", "phone", ["calls.h", "sd.calls", "calls.unknown", "calls.hidden", "calls.allowH"]],
  ["rules", "r-block", "safe", "lock-keyhole", ["anti.h", "sd.anti", "anti.debug", "anti.shot"]],
  ["rules", "r-bank", "safe", "landmark", ["bank.t", "sd.bank", "bank.h", "bank.colAcc", "bank.exp", "bank.imp"]],
  ["rules", "r-kid", "safe", "house", ["hm.h", "sd.home", "hm.on", "hm.settings", "hm.shade", "hm.lock", "hm.notes"]],
  ["rules", "r-kid", "safe", "notebook-pen", ["kn.h", "sd.kind"]], ["rules", "#monCard", "", "eye", ["mon.h", "mon.p"]],
  ["where", "r-lost", "", "siren", ["lost.h", "sd.lost", "lost.on", "lost.msg", "lost.phone"]], ["where", "r-zone", "", "map-pin", ["zone.h", "sd.zone"]], ["where", "r-trk", "", "route", ["trk.h", "sd.trk"]],
  ["set", "s-sec", "", "key-round", ["pin.h", "pin.p"]], ["set", "s-sec", "", "key-round", ["totp.h", "sd.totp"]],
  ["set", "s-sys", "", "sliders-horizontal", ["sys.h", "sd.sys", "sys.ring", "sys.media", "sys.alarm", "sys.bright", "sys.timeout", "sys.dnd"]],
  ["set", "s-adv", "", "code", ["dev.h", "sd.dev", "anti.debug"]], ["set", "s-adv", "", "terminal", ["sh.h", "sd.sh", "sh.tcp"]],
  ["set", "s-remote", "", "globe", ["relay.h", "sd.relay"]], ["set", "s-remote", "", "route", ["ts.h", "sd.ts"]],
  ["set", "s-watch", "", "watch", ["watch.h", "sd.watch", "watch.go"]], ["set", "s-ai", "", "bot", ["ai.h", "sd.ai"]],
  ["set", "s-upd", "", "refresh-cw", ["upd.h", "sd.upd", "upd.auto"]], ["set", "s-kid", "", "user", ["set.name", "keep.h", "wz.redo", "rx.h"]],
  ["set", "s-tg", "", "send", ["tg.h", "tg.d"]], ["set", "s-feat", "", "list-checks", ["ft.h", "ft.d"]],
  ["set", "s-paired", "", "smartphone", ["paired.h", "sd.paired"]], ["set", "s-danger", "", "octagon-x", ["set.danger", "sd.danger"]],
  ["apps", "a-apps", "", "puzzle", ["as.list", "ap.appsD", "apps.search"]], ["apps", "a-clean", "", "eye-off", ["as.clean", "ap.cleanD", "clean.h"]],
  ["apps", "a-health", "", "heart-pulse", ["hl.h", "hl.sub", "hl.review", "hl.bg.h", "hl.kc"]], ["apps", "a-space", "", "hard-drive", ["clean.heavy", "ap.spaceD"]],
  ["seen", "#seenNotif", "", "bell", ["seen.nh", "seen.p", "seen.non"]],
  ["seen", "#seenCam", "", "camera", ["seen.ch", "seen.con", "snap.h", "snap.hist", "cam.h", "cam.go"]],
  ["seen", "#seenListenCard", "", "ear", ["lh.h", "lh.p", "lh.always"]],
  ["seen", "#seenSms", "", "message-square", ["seen.sh", "seen.son"]],
  ["dev", "d-act", "", "zap", ["dv.act"]], ["dev", "d-bat", "", "battery-full", ["dv.bat"]], ["dev", "d-ram", "", "memory-stick", ["dv.ram"]],
  ["dev", "d-sto", "", "hard-drive", ["dv.sto"]], ["dev", "d-sys", "", "smartphone", ["dv.sys"]],
];
const qsFold = s => String(s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/g, "d").replace(/[^\p{L}\p{N}\s]/gu, "");   // "Wi-Fi" = "wifi"
const qsShort = s => s.split(/ \(|\. |: /)[0].slice(0, 80);   // a long switch label: its first clause
const qsTxt = k => hasT(k) ? String(t(k)).replace(/<[^>]*>/g, "").replace(/\{\d\}/g, "") : "";
// one typo / missing letter allowed (3+ letters: whole words; 4+: also word starts) ("ngan hag", "tro nang", "vnp"): edit distance <= 1
const qsNear = (a, b) => { if (Math.abs(a.length - b.length) > 1) return false; let i = 0, j = 0, d = 0;
  while (i < a.length && j < b.length) { if (a[i] === b[j]) { i++; j++; continue; } if (++d > 1) return false;
    if (a.length > b.length) i++; else if (b.length > a.length) j++; else { i++; j++; } }
  return d + (a.length - i) + (b.length - j) <= 1; };
const qsWord = (x, f, toks) => f.includes(x) || (x.length >= 3 && toks.some(k => (k.length >= 3 && qsNear(x, k)) || (x.length >= 4 && qsNear(x, k.slice(0, x.length)))));
// exact (accent-free substring) matches first; only when none, allow a typo per word
function qsFind(q) { const r = qsFind1(q, false); return r.length || !q.trim() ? r : qsFind1(q, true); }
function qsFind1(q, near) {
  const w = qsFold(q).split(/\s+/).filter(Boolean), out = [];
  const has = s => { const f = qsFold(s), toks = f.split(/\s+/); return w.every(x => f.includes(x) || (near && qsWord(x, f, toks))); };
  // kw.<title key>: words people search with that the UI doesn't say (i18n, comma list), e.g. "momo" finds the app-pause list
  const kw = k0 => hasT("kw." + k0) ? String(t("kw." + k0)).replace(/,/g, " ") : "";
  for (const [id, i] of NAV) if (navOk(id) && has(t("tab." + id))) out.push({ tab: id, icon: i, title: t("tab." + id), where: "" });
  for (const [tb, sid, seg, i, keys] of QS) {
    if (!navOk(tb) || !qsTxt(keys[0])) continue;
    const where = t("tab." + tb) + (seg ? " › " + t(seg === "time" ? "rules.segTime" : "rules.segSafe") : "");
    const sw = keys.slice(2).map(qsTxt).filter(Boolean), hit = !has(qsTxt(keys[0])) && sw.find(s => w.length && has(s));
    if (hit) out.push({ tab: tb, sec: sid, seg, icon: i, hit, title: qsShort(hit), where: where + " › " + qsTxt(keys[0]) });
    else if (has(keys.map(qsTxt).join(" ") + " " + kw(keys[0]))) out.push({ tab: tb, sec: sid, seg, icon: i, title: qsTxt(keys[0]), where });
  }
  return out;
}
function qsGo(e) {
  if (!(tab === e.tab && subId)) goTab(e.tab);   // a sub-screen of this very tab is open: subOpen below swaps it
  if (!e.sec) return;
  let n = 0;
  const find = () => {   // a tab may draw after its data arrives: retry for ~2 s
    const el = e.sec[0] === "#" ? $(e.sec) : document.querySelector(`[data-sec="${e.sec}"]`);
    if (!el) { if (++n < 20) setTimeout(find, 100); return; }
    if (el.classList.contains("cl")) {   // a section: open its sub-screen (phone) / select it (wide), flash the control the words came from, else the title bar
      subOpen(e.sec);
      const w = e.hit ? qsFold(e.hit) : "", bar = el.querySelector(".clh");
      let tg = null;
      if (w) for (const x of el.querySelectorAll(".clb label, .clb button, .clb b, .clb summary, .clb p, .clb span, .clb option")) { if (x.children.length > 3 || !qsFold(x.textContent).includes(w)) continue; if (!tg || tg.contains(x)) tg = x; }
      if (tg && tg.tagName === "OPTION") tg = tg.closest("select") || tg;
      const f = tg || bar;
      f.classList.add("qsHit"); setTimeout(() => f.classList.remove("qsHit"), 1800);
      setTimeout(() => { if (tg && el.isConnected) tg.scrollIntoView({ block: "center", behavior: "smooth" }); }, 60);
      return;
    }
    el.scrollIntoView({ block: "start", behavior: "smooth" });
    el.classList.add("qsHit"); setTimeout(() => el.classList.remove("qsHit"), 1800);
    // first visit of a tab: sections above still grow after the jump (data, icons, the keyboard closing), so the smooth
    // scroll lands short (owner 2026-11-13: had to search twice). Re-aim while the target's page position moves, ~2 s,
    // until the finger touches the page.
    let last = null, k = 0, quit = false;
    const stop = () => { quit = true; };
    for (const ev of ["touchstart", "wheel", "keydown"]) addEventListener(ev, stop, { once: true, passive: true });
    const again = () => {
      if (quit || ++k > 10 || !el.isConnected) return;
      const y = el.getBoundingClientRect().top + scrollY;
      if (last !== null && Math.abs(y - last) > 4) el.scrollIntoView({ block: "start" });
      last = y; setTimeout(again, 200);
    };
    setTimeout(again, 250);
  };
  setTimeout(find, 30);
}
function qsOpen() {
  if (!cur) return;
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("search", t("qs.h"))}
    <input id="qsIn" type="search" placeholder="${esc(t("qs.ph"))}" autocomplete="off" aria-label="${esc(t("qs.h"))}" style="width:100%;margin:2px 0 10px">
    <div class="list" id="qsList" style="max-height:60vh;overflow:auto"></div></div>`, (pop, done) => {
    const inp = pop.querySelector("#qsIn"), L = pop.querySelector("#qsList");
    const draw = () => {
      const r = qsFind(inp.value);
      L.innerHTML = r.length ? r.map((e, i) => navRow(`data-qi="${i}"`, e.icon, esc(e.title), e.where ? esc(e.where) : "")).join("")
        : `<p class="mute">${t("qs.none")}</p>`;
      L.querySelectorAll("[data-qi]").forEach(b => b.onclick = () => { done(true); qsGo(r[+b.dataset.qi]); });
    };
    inp.oninput = draw;
    inp.onkeydown = ev => { if (ev.key === "Enter") { const f = L.querySelector("[data-qi]"); if (f) f.click(); } };
    draw(); setTimeout(() => inp.focus(), 0);
  });
}
document.addEventListener("keydown", e => {   // "/" opens the quick search (not while typing)
  if (e.key !== "/" || e.ctrlKey || e.metaKey || e.altKey || !cur || askDone) return;
  const a = document.activeElement; if (a && (/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) || a.isContentEditable)) return;
  e.preventDefault(); qsOpen();
});

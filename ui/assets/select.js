// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- Select mode shared by every bulk delete (chat messages, chat photos & files, Files tab, camera history, listen recordings).
// Google Photos pattern: long press (or the "Chọn" button) starts it, tap toggles, a bar at the bottom says "Đã chọn N · X MB" with ONE
// Delete, "select all" + "select by date" (today / 7 days / 30 days / older than 30 days / from-to) + a check per day.
// Ranges follow K/remote/SelLogic.kt (unit-tested there; scripts/check-select.mjs runs these functions on the same cases).
// A caller makes one state with selNew({ get, paint, del }) and then: puts selBar(S) at the end of its container, gives every selectable
// element data-sid="<id>" (+ class "selmode" on the container while S.on), calls selClick(S, e) / selChange(S, e) first in its own
// click / change handler and selPress(container, S) once. get() = [{ id, t, size? }] of what is on screen (the current filter).
const selStart = t => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
const selDayKey = t => { const d = new Date(t), p = n => String(n).padStart(2, "0"); return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()); };
const selDate = v => { const m = /^(\d{4})-(\d\d)-(\d\d)$/.exec(v || ""); return m ? new Date(+m[1], +m[2] - 1, +m[3]).getTime() : 0; };   // <input type=date> value -> local midnight, 0 = empty
// ids of items [{id, t}] in a range: "today" | "7" | "30" (calendar days incl. today) | "old" (before the 30 days) | "custom" (from / to = ms, any time inside the day, both days included, 0 = open end)
function selRange(items, r, now, from, to) {
  const today = selStart(now), back = n => { const d = new Date(today); d.setDate(d.getDate() - n); return d.getTime(); };
  const day1 = to ? (() => { const d = new Date(selStart(to)); d.setDate(d.getDate() + 1); return d.getTime(); })() : 0;
  const ok = { today: x => x >= today, 7: x => x >= back(6), 30: x => x >= back(29), old: x => x < back(29), custom: x => (!from || x >= selStart(from)) && (!to || x < day1) }[r];
  return ok ? items.filter(i => ok(i.t)).map(i => i.id) : [];
}
// items grouped by local day, newest first: [{ key, t, ids }]
function selDays(items) {
  const m = new Map();
  for (const i of items.slice().sort((a, b) => b.t - a.t)) { const k = selDayKey(i.t); if (!m.has(k)) m.set(k, { key: k, t: i.t, ids: [] }); m.get(k).ids.push(i.id); }
  return [...m.values()];
}
const selDayLabel = key => key === selDayKey(Date.now()) ? t("sel.r.today") : key === selDayKey(Date.now() - 864e5) ? t("yesterday") : dm(key);
const selNew =o => ({ on: false, ids: new Set(), range: "", from: "", to: "", swallow: 0, ...o });
const selSize = S => S.get().reduce((n, i) => n + (S.ids.has(i.id) ? i.size || 0 : 0), 0);
function selStartMode(S, id) { S.on = true; if (id) S.ids.add(id); S.paint(); }
function selOff(S) { S.on = false; S.ids.clear(); S.range = ""; S.from = S.to = ""; S.paint(); }
// "Chọn" button (not in select mode) / nothing in select mode: the bar below takes over
const selBtn = S => S.on ? "" : `<button type="button" class="sp selgo" data-sel="start">${ic("check")} ${t("sel.start")}</button>`;
// checkbox row that opens a day: all of that day's ids
const selDayHead = (S, d, label) => { const all = d.ids.every(i => S.ids.has(i)); return S.on ? `<label class="seldy"><input type="checkbox" data-sel-day="${d.key}" ${all ? "checked" : ""}> <b>${label}</b> <span class="mute">${d.ids.length}</span></label>` : `<div class="seldy"><b>${label}</b></div>`; };
function selBar(S) {
  if (!S.on) return "";
  const n = S.ids.size, sz = selSize(S), total = S.get().length;
  return `<div class="selbar" role="toolbar" aria-label="${esc(t("sel.range"))}">
    <div class="selrow"><button type="button" class="sp" data-sel="all">${ic(n && n === total ? "square" : "check")} ${t(n && n === total ? "sel.none" : "sel.all")}</button>
      <select data-sel-r aria-label="${esc(t("sel.range"))}"><option value="">${t("sel.range")}</option>${["today", "7", "30", "old", "custom"].map(r => `<option value="${r}" ${S.range === r ? "selected" : ""}>${t("sel.r." + r)}</option>`).join("")}</select></div>
    ${S.range === "custom" ? `<div class="selrow"><label>${t("sel.from")} <input type="date" data-sel-d="from" value="${esc(S.from)}"></label><label>${t("sel.to")} <input type="date" data-sel-d="to" value="${esc(S.to)}"></label></div>` : ""}
    <div class="selrow"><b class="sp" role="status">${n ? t("sel.nsz", n, fxSize(sz)) : t("sel.pick")}</b>
      <button type="button" class="sp" data-sel="off">${ic("x")} ${t("sel.off")}</button><button type="button" class="sp bad" data-sel="del"${n ? "" : " disabled"}>${ic("trash-2")} ${t("sel.del")}</button></div></div>`;
}
// click: true = handled (the caller returns). In select mode a tap on a data-sid element only toggles.
function selClick(S, e) {
  const b = e.target.closest("[data-sel]");
  if (b) {
    const a = b.dataset.sel;
    if (a === "start") selStartMode(S);
    else if (a === "off") selOff(S);
    else if (a === "all") { const all = S.get(); if (S.ids.size && S.ids.size >= all.length) S.ids.clear(); else all.forEach(i => S.ids.add(i.id)); S.range = ""; S.paint(); }
    else if (a === "del") { const ids = [...S.ids]; if (ids.length) Promise.resolve(S.del(ids)).then(done => { if (done) selOff(S); else S.paint(); }); }
    return true;
  }
  const el = S.on && e.target.closest("[data-sid]");
  if (el) { const id = el.dataset.sid; S.ids.has(id) ? S.ids.delete(id) : S.ids.add(id); S.range = ""; S.paint(); return true; }
  return false;
}
// change: the range picker, the custom dates, a day checkbox
function selChange(S, e) {
  const r = e.target.closest("[data-sel-r]"), d = e.target.closest("[data-sel-d]"), dy = e.target.closest("[data-sel-day]");
  if (r) { S.range = r.value; if (S.range && S.range !== "custom") S.ids = new Set(selRange(S.get(), S.range, Date.now())); else S.ids.clear(); S.paint(); return true; }
  if (d) { S[d.dataset.selD] = d.value; S.ids = new Set(selRange(S.get(), "custom", Date.now(), selDate(S.from), selDate(S.to))); S.paint(); return true; }
  if (dy) { const day = selDays(S.get()).find(x => x.key === dy.dataset.selDay); if (day) day.ids.forEach(i => dy.checked ? S.ids.add(i) : S.ids.delete(i)); S.range = ""; S.paint(); return true; }
  return false;
}
// long press on a data-sid element starts select mode with it selected (the click that follows the release is swallowed); also stops the Android context menu
function selPress(root, S) {
  let tm = 0, x0 = 0, y0 = 0;
  const pick = e => e.target.closest && e.target.closest("[data-sid]");
  root.addEventListener("pointerdown", e => { const el = pick(e); if (!el || S.on) return; x0 = e.clientX; y0 = e.clientY; clearTimeout(tm); tm = setTimeout(() => { S.swallow = Date.now(); selStartMode(S, el.dataset.sid); }, 480); });
  root.addEventListener("pointermove", e => { if (Math.abs(e.clientX - x0) > 10 || Math.abs(e.clientY - y0) > 10) clearTimeout(tm); });
  ["pointerup", "pointercancel"].forEach(n => root.addEventListener(n, () => clearTimeout(tm)));
  root.addEventListener("contextmenu", e => { if (pick(e)) e.preventDefault(); });
  root.addEventListener("click", e => { if (S.swallow && Date.now() - S.swallow < 800) { S.swallow = 0; e.stopPropagation(); e.preventDefault(); } }, true);
}
// delete in chunks of 200 ids (the phone's limit per command): fn(chunk) -> truthy = sent. One confirm is the caller's.
async function selChunks(ids, fn) {
  for (let i = 0; i < ids.length; i += 200) { if (!(await fn(ids.slice(i, i + 200)))) return false; }
  return true;
}

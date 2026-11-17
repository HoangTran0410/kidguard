// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// ---- Seen tab, two histories (docs/protocol.md "Photo history", "Listen history"):
// 1. "Ảnh đã chụp": every photo taken with "Take photo" stays on the child's phone (no cap) with its meta, failed tries too. This page lists them
//    (LAN GET /snaps or relay snapList + file channel, like pullPage), thumbs fetched lazily and cached in IndexedDB "kgchat" (thGet/thPut of
//    requests.js), a tap opens the photo, select mode = bulk delete (snapDel {ids}).
// 2. "Lịch sử nghe": Listen around can be recorded in THIS browser only (MediaRecorder on the incoming audio, IndexedDB "kglisten"); one row per
//    listen session, recorded rows play / download / delete. Nothing is recorded or stored on the phone besides its own session list (state listen.hist).
const snapH = { k: null, rows: [], more: false, busy: false, err: "", done: false };
const snapTh = {}, snapFull = {};   // "<kid>/<id>" -> blob url ("" loading, 0 failed)
const snapKey = (k, id) => "snap/" + k.id + "/" + id;
const snapTf = id => id.replace(/[0-9a-f]/g, d => (15 - parseInt(d, 16)).toString(16));   // thumb fid: every hex digit complemented (same as chat thumbs)
const snapPick = selNew({
  get: () => snapH.rows.map(r => ({ id: r.id, t: r.at, size: r.size || 0 })),
  paint: () => snapDraw(),
  del: ids => snapDelAsk(ids),
});
// one photo (or its thumb) as a Blob: LAN GET /snap/<id>[?thumb=1], or relay snapUp {id, thumb?} + poll the parent channel
async function snapBlob(k, id, thumb) {
  if (!fxRelay(k)) {
    const r = await fetch(base(k) + "/snap/" + id + (thumb ? "?thumb=1" : ""), { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
    if (r.status === 404 || r.status === 409) throw errOf("snap:gone");
    if (!r.ok) throw errOf("HTTP " + r.status);
    return r.blob();
  }
  await call(k, "/cmd", "POST", thumb ? { type: "snapUp", id, thumb: 1 } : { type: "snapUp", id });
  const fid = thumb ? snapTf(id) : id, end = Date.now() + (thumb ? 90e3 : 3 * 60e3);
  let meta = null;
  while (!meta) {
    await new Promise(s => setTimeout(s, thumb ? 2500 : 3000));
    if (Date.now() > end) throw errOf("timeout");
    meta = (((await call(k, "/file?to=parent").catch(() => ({}))).files) || []).find(x => x.fid === fid && x.done) || null;
  }
  const parts = [];
  for (let i = 0; i < meta.n; i++) {
    const r = await fetch(base(k) + `/file?fid=${fid}&i=${i}`, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
    if (!r.ok) throw errOf("HTTP " + r.status);
    parts.push(await r.arrayBuffer());
  }
  fetch(base(k) + "/file?fid=" + fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
  return new Blob(parts, { type: "image/jpeg" });
}
async function snapLoad(more) {
  const k = cur; if (!k || snapH.busy) return;
  if (snapH.k !== k) Object.assign(snapH, { k, rows: [], more: false, err: "", done: false });
  snapH.busy = true; snapH.err = ""; snapDraw();
  try {
    const before = more && snapH.rows.length ? snapH.rows[snapH.rows.length - 1].at : 0;
    const j = await pullPage(k, "/snaps", "snapList", before);
    if (snapH.k !== k) return;
    const have = new Set(snapH.rows.map(r => r.id)), add = (j.snaps || []).filter(r => !have.has(r.id));
    snapH.rows = more ? snapH.rows.concat(add) : add; snapH.more = !!j.more; snapH.done = true;
  } catch (e) { snapH.err = e.message || String(e.raw || e); }
  snapH.busy = false; snapDraw();
}
// thumbs: only when the tile is visible, 2 (relay) / 4 (LAN) at a time, memory + IndexedDB
const snapQ = []; let snapRun = 0, snapIO = null;
function snapPump() { while (snapQ.length && snapRun < (fxRelay() ? 2 : 4)) { snapRun++; snapQ.shift()().finally(() => { snapRun--; snapPump(); }); } }
function snapThumb(k, id) {
  const key = snapKey(k, id); if (key in snapTh) return; snapTh[key] = "";
  snapQ.push(async () => {
    try {
      let b = await thGet(key);
      if (!b) { b = await snapBlob(k, id, true); thPut(key, b); }
      snapTh[key] = URL.createObjectURL(b);
    } catch { snapTh[key] = 0; setTimeout(() => { if (snapTh[key] === 0) delete snapTh[key]; }, 60000); }
    document.querySelectorAll("[data-sth]").forEach(el => { if (el.dataset.sth === id && snapTh[key]) el.querySelector(".cthi").innerHTML = `<img src="${snapTh[key]}" alt="" decoding="async">`; });
  });
  snapPump();
}
function snapObs() {
  const els = [...document.querySelectorAll("[data-sth]")]; if (!els.length || !cur) return;
  const go = el => { if (cur) snapThumb(cur, el.dataset.sth); };
  if (!window.IntersectionObserver) return els.forEach(go);
  if (!snapIO) snapIO = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { snapIO.unobserve(e.target); go(e.target); } }), { rootMargin: "80px" });
  els.forEach(el => snapIO.observe(el));
}
const snapWhy = e => tc("seen.cerr.", String(e || "failed").replace(/ /g, "_"), String(e || t("seen.cerr.failed")));
const snapLbl = r => hhmm(r.at) + " · " + t("seen.c.cam." + (r.cam === "back" ? "back" : "front")) + (r.flash === "on" || r.flash === "auto" ? " · " + ic("zap") : "");
function snapDraw() {
  const box = $("#snapHist"); if (!box) return;
  const s = snapH, S = snapPick, mine = s.k === cur, rows = mine ? s.rows : [], sn = st && st.snaps, f = st && st.freeMb;
  box.classList.toggle("selmode", S.on);
  const days = selDays(S.get());
  const tile = r => `<button type="button" class="gt${selCls(S, r.id)}${r.ok ? "" : " snapbad"}" data-sid="${esc(r.id)}" data-snap="${esc(r.id)}"${r.ok ? ` data-sth="${esc(r.id)}"` : ""} aria-label="${esc(hhmm(r.at))}"><span class="cthi">${r.ok ? (snapTh[snapKey(cur, r.id)] ? `<img src="${snapTh[snapKey(cur, r.id)]}" alt="" decoding="async">` : ic("image")) : `<span class="snapw">${ic("camera")}<span>${esc(snapWhy(r.err))}</span></span>`}</span><i>${snapLbl(r)}</i></button>`;
  box.innerHTML = `<h3 class="mute" style="margin:16px 0 4px">${ic("image")} ${t("snap.h")}${sn ? ` <span class="mute">· ${t("snap.count", sn.n, fxSize((sn.mb || 0) * 1048576))}</span>` : ""}</h3>
    ${typeof f === "number" && f >= 0 && f < 1024 ? alertBox("warn", "hard-drive", esc(t("chat.low", Math.round(f))), "margin:0 0 8px") : ""}
    <div class="row">${mine && s.done && rows.length && !S.on ? selBtn(S) : ""}<button type="button" id="snapLoad" class="sp"${s.busy ? " disabled" : ""}>${ic("refresh-cw")} ${t(s.busy ? "seen.wait" : s.done ? "snap.reload" : "snap.load")}</button></div>
    ${s.busy && fxRelay() ? `<p class="mute">${t("seen.waitRelay")}</p>` : ""}${s.err ? alertBox("bad", "triangle-alert", esc(s.err)) : ""}
    ${mine && s.done && !rows.length ? `<p class="mute">${t("snap.empty")}</p>` : ""}
    ${days.map(d => selDayHead(S, d, esc(selDayLabel(d.key))) + `<div class="ggrid">${rows.filter(r => selDayKey(r.at) === d.key).map(tile).join("")}</div>`).join("")}
    ${mine && s.more ? `<div class="row"><button type="button" id="snapMore" class="sp"${s.busy ? " disabled" : ""}>${ic("history")} ${t("snap.more")}</button></div>` : ""}
    <div class="selpg">${selBar(S)}</div>`;
  snapObs();
}
function snapWire(box) {
  selPress(box, snapPick);
  box.addEventListener("change", e => { selChange(snapPick, e); });
  box.addEventListener("click", e => {
    if (selClick(snapPick, e)) return;
    if (e.target.closest("#snapLoad")) return snapLoad(false);
    if (e.target.closest("#snapMore")) return snapLoad(true);
    const b = e.target.closest("[data-snap]"); if (b) { const r = snapH.rows.find(x => x.id === b.dataset.snap); if (r) snapView(r); }
  });
}
async function snapView(r) {
  const k = cur;
  if (!r.ok) {
    sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("camera", esc(evAt(r.at)))}<p>${esc(snapWhy(r.err))}</p>
      <div class="row"><button type="button" id="svDel" class="sp bad">${ic("trash-2")} ${t("sel.del")}</button></div></div>`, (pop, done) => { pop.querySelector("#svDel").onclick = () => { done(false); snapDelAsk([r.id]); }; });
    return;
  }
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("image", esc(evAt(r.at)))}
    <div id="svBox" class="avbox mute" role="status">${t("chat.loading", 0)}</div><p class="mute">${snapLbl(r)}${r.size ? " · " + fxSize(r.size) : ""}</p>
    <div class="row"><button type="button" id="svGet" class="sp" disabled>${ic("download")} ${t("chat.get")}</button><button type="button" id="svDel" class="sp bad">${ic("trash-2")} ${t("sel.del")}</button></div></div>`, (pop, done) => {
    let blob = null;
    pop.querySelector("#svGet").onclick = () => blob && fxSave(blob, "kidguard-" + selDayKey(r.at) + "-" + r.id.slice(0, 6) + ".jpg");
    pop.querySelector("#svDel").onclick = () => { done(false); snapDelAsk([r.id]); };
    (async () => {
      try {
        blob = await snapBlob(k, r.id, false);
        const box = $("#svBox"); if (!box || k !== cur) return;
        box.classList.remove("mute"); box.innerHTML = `<img src="${URL.createObjectURL(blob)}" alt="${esc(evAt(r.at))}">`;
        const g = $("#svGet"); if (g) g.disabled = false;
      } catch (e) { const box = $("#svBox"); if (box) box.textContent = e.raw === "snap:gone" ? t("snap.gone") : e.message || t("fx.e.failed"); }
    })();
  });
}
async function snapDelAsk(ids) {
  const k = cur, set = new Set(ids), sz = snapH.rows.reduce((n, r) => n + (set.has(r.id) ? r.size || 0 : 0), 0);
  if (!(await ask(t("sel.snapAsk", ids.length, fxSize(sz)), t("sel.del"), true))) { snapDraw(); return false; }
  const done = await selChunks(ids, c => cmd({ type: "snapDel", ids: c }, t("sel.done", ids.length), false));
  if (done && snapH.k === k) { snapH.rows = snapH.rows.filter(r => !set.has(r.id)); ids.forEach(i => { delete snapTh[snapKey(k, i)]; }); }
  snapDraw();
  return !!done;
}

// ---- Listen around: the recording is made here, in the page (the phone only streams) ----
const LH_ALWAYS = "kg.ear.always";
const lhAlways = () => { try { return localStorage.getItem(LH_ALWAYS) === "1"; } catch { return false; } };
const lh = { s: null, mr: null, chunks: [], want: false, rows: [], k: null, play: null };
const lhDb = () => lhDb.p || (lhDb.p = new Promise(res => { try { const q = indexedDB.open("kglisten", 1); q.onupgradeneeded = () => { q.result.createObjectStore("s", { keyPath: "id" }); q.result.createObjectStore("b"); }; q.onsuccess = () => res(q.result); q.onerror = () => res(null); } catch { res(null); } }));
async function lhTx(mode, fn) {
  const d = await lhDb(); if (!d) return null;
  return new Promise(r => { try { const tx = d.transaction(["s", "b"], mode); let out = null; fn(tx.objectStore("s"), tx.objectStore("b"), v => { out = v; }); tx.oncomplete = () => r(out === null ? true : out); tx.onerror = tx.onabort = () => r(null); } catch { r(null); } });
}
const lhAll = async kid => ((await lhTx("readonly", (s, b, set) => { const q = s.getAll(); q.onsuccess = () => set(q.result); })) || []).filter(x => x && x.kid === kid).sort((a, b) => b.at - a.at);
const lhPut = (row, blob) => lhTx("readwrite", (s, b) => { s.put(row); if (blob) b.put(blob, row.id); });
const lhDel = ids => lhTx("readwrite", (s, b) => { ids.forEach(i => { s.delete(i); b.delete(i); }); });
const lhBlob = id => lhTx("readonly", (s, b, set) => { const q = b.get(id); q.onsuccess = () => set(q.result || false); });
const lhCan = () => !!(window.MediaRecorder && window.HTMLMediaElement && (HTMLMediaElement.prototype.captureStream || HTMLMediaElement.prototype.mozCaptureStream));
const lhRecording = () => !!(lh.mr && lh.mr.state === "recording");
function lhBtn() {
  const b = $("#earRec"); if (!b) return;
  b.hidden = !lhCan();
  const on = lh.s ? lhRecording() : lh.want || lhAlways();
  b.classList.toggle("on", on); b.setAttribute("aria-pressed", String(on));
  b.innerHTML = `${ic("circle")} ${t(on ? "lh.recOn" : "lh.rec")}`;
}
// the audio started playing (live.js au.onplaying): this is the start of a listen session
function lhPlaying(au) {
  if (lh.s || !cur) return;
  lh.s = { id: Date.now(), kid: cur.id, at: Date.now(), via: earRtc ? "relay" : "lan", rec: false, mime: "" };
  if (lh.want || lhAlways()) lhRecStart(au);
  lhBtn();
}
function lhRecStart(au) {
  if (lh.mr || !lh.s) return;
  const cs = au.captureStream || au.mozCaptureStream; if (!cs || !window.MediaRecorder) return toast("warn|" + t("lh.unsup"));
  try {
    const ms = cs.call(au), mime = ["audio/webm;codecs=opus", "audio/mp4", "audio/webm"].find(m => MediaRecorder.isTypeSupported(m)) || "";
    lh.mr = new MediaRecorder(ms, mime ? { mimeType: mime, audioBitsPerSecond: 32000 } : undefined);
    lh.s.mime = lh.mr.mimeType || mime || "audio/webm";
    lh.mr.ondataavailable = e => { if (e.data && e.data.size) lh.chunks.push(e.data); };
    lh.mr.start(2000); lh.s.rec = true;
  } catch { lh.mr = null; toast("warn|" + t("lh.unsup")); }
}
function lhToggle() {
  const au = $("#earAudio");
  if (!lh.s) { lh.want = !lh.want; return lhBtn(); }   // before the sound starts: armed for this session
  if (lhRecording()) { try { lh.mr.stop(); } catch {} lh.mr = null; } else lhRecStart(au);
  lhBtn();
}
// the session ended (earStop): save the row (+ the recording) in this browser
async function lhEnd() {
  const s = lh.s; if (!s) return; lh.s = null; lh.want = false;
  s.dur = Date.now() - s.at;
  const mr = lh.mr; lh.mr = null;
  if (mr && mr.state !== "inactive") await new Promise(res => { mr.onstop = res; try { mr.stop(); } catch { res(); } });
  const blob = lh.chunks.length ? new Blob(lh.chunks, { type: s.mime || "audio/webm" }) : null; lh.chunks = [];
  lhBtn();
  if (s.dur < 1000) return;
  const row = { id: s.id, kid: s.kid, at: s.at, dur: s.dur, via: s.via, rec: !!(blob && blob.size), mime: s.mime, size: blob ? blob.size : 0 };
  const ok = await lhPut(row, row.rec ? blob : null);
  if (row.rec) toast(ok ? "ok|" + t("lh.saved", fxSize(row.size)) : "err|" + t("lh.saveFail"));
  if (cur && cur.id === s.kid) lhLoad();
}
async function lhLoad() { const k = cur; if (!k) return; lh.rows = await lhAll(k.id); lh.k = k; lhDraw(); }
const lhSel = selNew({
  get: () => lh.rows.map(r => ({ id: String(r.id), t: r.at, size: r.size || 0 })),
  paint: () => lhDraw(),
  del: async ids => {
    const set = new Set(ids), sz = lh.rows.reduce((n, r) => n + (set.has(String(r.id)) ? r.size || 0 : 0), 0);
    if (!(await ask(t("sel.lhAsk", ids.length, fxSize(sz)), t("sel.del"), true))) return false;
    await lhDel(ids.map(Number)); await lhLoad(); toast("ok|" + t("sel.done", ids.length)); return true;
  },
});
const lhDur = ms => fmtClock(ms);
function lhRow(r) {
  const S = lhSel, id = String(r.id);
  return `<div class="fxrow${selCls(S, id)}" data-sid="${id}"><div class="sp fxm"><b class="fxn">${esc(hhmm(r.at))} · ${esc(lhDur(r.dur))}</b> <span class="tag">${t(r.via === "relay" ? "lh.relay" : "lh.lan")}</span>
    <div class="mute">${r.rec ? esc(t("lh.rec1", fxSize(r.size || 0))) : esc(t("lh.none"))}</div></div>
    <div class="fxb">${r.rec ? `<button type="button" class="pri" data-lh="play" data-id="${id}" aria-label="${esc(t("lh.play"))}">${ic("play")}</button><button type="button" data-lh="get" data-id="${id}" aria-label="${esc(t("chat.get"))}">${ic("download")}</button>` : ""}<button type="button" class="bad" data-lh="del" data-id="${id}" aria-label="${esc(t("sel.del"))}">${ic("trash-2")}</button></div></div>`;
}
function lhDraw() {
  const box = $("#seenListen"); if (!box || !cur) return;
  const S = lhSel, mine = lh.k === cur, rows = mine ? lh.rows : [];
  // the phone's own list (state listen.hist: sessions of every parent): rows without a local twin are other devices' sessions, nothing to play here
  const near = at => rows.some(r => Math.abs(r.at - at) < 90e3);
  const other = ((st && st.listen && st.listen.hist) || []).filter(h => h && h.at && !near(h.at)).map(h => ({ at: h.at, dur: (h.dur || 0) * 1000, via: h.via, other: 1 }));
  const days = selDays(S.get());
  box.classList.toggle("selmode", S.on);
  const otherHtml = other.length ? `<h4 class="mute" style="margin:12px 0 4px">${t("lh.other")}</h4>` + other.map(r => `<div class="fxrow"><div class="sp fxm"><b class="fxn">${esc(evAt(r.at))} · ${esc(lhDur(r.dur))}</b> <span class="tag">${t(r.via === "relay" ? "lh.relay" : "lh.lan")}</span></div></div>`).join("") : "";
  box.innerHTML = `<label class="row"><input type="checkbox" id="lhAlways" ${lhAlways() ? "checked" : ""}${lhCan() ? "" : " disabled"}><span class="sp">${t("lh.always")}<br><span class="mute">${t(lhCan() ? "lh.alwaysH" : "lh.unsup")}</span></span></label>
    ${rows.length && !S.on ? `<div class="row" style="margin-top:8px">${selBtn(S)}</div>` : ""}
    ${mine && !rows.length ? `<p class="mute">${t("lh.empty")}</p>` : ""}
    ${days.map(d => selDayHead(S, d, esc(selDayLabel(d.key))) + rows.filter(r => selDayKey(r.at) === d.key).map(lhRow).join("")).join("")}
    ${otherHtml}<div class="selpg">${selBar(S)}</div>`;
}
function lhWire(box) {
  selPress(box, lhSel);
  box.addEventListener("change", e => {
    if (selChange(lhSel, e)) return;
    const a = e.target.closest("#lhAlways"); if (a) { try { localStorage.setItem(LH_ALWAYS, a.checked ? "1" : "0"); } catch {} lhBtn(); }
  });
  box.addEventListener("click", async e => {
    if (selClick(lhSel, e)) return;
    const b = e.target.closest("[data-lh]"); if (!b) return;
    const id = +b.dataset.id, r = lh.rows.find(x => x.id === id); if (!r) return;
    if (b.dataset.lh === "del") {
      if (await ask(t("sel.lhAsk", 1, fxSize(r.size || 0)), t("sel.del"), true)) { await lhDel([id]); await lhLoad(); toast("ok|" + t("sel.done", 1)); }
      return;
    }
    const blob = await lhBlob(id); if (!blob) return toast("err|" + t("lh.gone"));
    if (b.dataset.lh === "get") return fxSave(blob, "kidguard-" + selDayKey(r.at) + "-" + hhmm(r.at).replace(/\D+/g, "") + "." + (/mp4/.test(r.mime) ? "m4a" : "webm"));
    const u = URL.createObjectURL(blob);
    sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("ear", esc(evAt(r.at)))}<audio controls autoplay src="${u}" style="width:100%"></audio></div>`, () => {}).then(() => URL.revokeObjectURL(u));
  });
}
// the Seen tab calls this once its HTML is there
function histWire() {
  const sh = $("#snapHist"); if (sh && !sh.dataset.w) { sh.dataset.w = 1; snapWire(sh); }
  const ll = $("#seenListen"); if (ll && !ll.dataset.w) { ll.dataset.w = 1; lhWire(ll); }
  snapDraw(); lhLoad(); lhDraw();
}

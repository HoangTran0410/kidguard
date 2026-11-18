// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// --- Files tab: the shared folder (docs/protocol.md "Shared files"). Only what was sent, never a browser of the phone. ---
// Jobs live outside render(): a tab switch or a state refresh keeps transfers running; progress bars update in place.
const FX = { lan: 200 * 1048576, relay: 50 * 1048576, chunk: 1048576 };
// parent app >= the file bridge: KGApp.files() === "true" (picker + downloads work in its WebView); older app: no files
const FXNO = APP && !(() => { try { return !!(window.KGApp && KGApp.files && KGApp.files() === "true"); } catch { return false; } })();
const fx = { jobs: [], list: null, kid: null, sig: "", raf: 0, hide: new Set() };
const fxSize = n => n < 1024 ? n + " B" : n < 1048576 ? Math.round(n / 1024) + " KB" : (n / 1048576).toFixed(n < 10485760 ? 1 : 0) + " MB";
const fxHex = () => [...crypto.getRandomValues(new Uint8Array(8))].map(b => b.toString(16).padStart(2, "0")).join("");
const fxRelay = (k = cur) => !!k && k.via !== "lan";
const fxHdr = k => k.token ? { "X-KG-Token": k.token } : {};   // on-device page: the HttpOnly cookie instead
const fxErr = e => e && e.raw === "abort" ? t("fx.stopped") : tc("fx.e.", (e && e.raw) || "", (e && e.message) || t("fx.e.failed"));
const fxStop = () => errOf("abort", t("fx.stopped"));
function fxFiles() {
  const l = !fxRelay() && fx.kid === cur && fx.list ? fx.list : (st && st.files) || [];
  return l.filter(f => !fx.hide.has(f.id));
}
function fxLoad(k = cur) {
  if (!k || fxRelay(k)) return;
  call(k, "/files").then(j => { if (k === cur && Array.isArray(j.files)) { fx.list = j.files; fx.kid = k; fxPaint(); } }).catch(() => {});
}
// one XHR (upload progress needs it): LAN PUT of the whole file, or one relay chunk
function fxXhr(job, method, url, body, onUp) {
  return new Promise((res, rej) => {
    const x = new XMLHttpRequest(); job.abort = () => x.abort();
    x.open(method, url);
    for (const [h, v] of Object.entries(fxHdr(job.kid))) x.setRequestHeader(h, v);
    if (onUp) x.upload.onprogress = e => { if (e.lengthComputable) onUp(e.loaded); };
    x.onload = () => {
      let j = {}; try { j = JSON.parse(x.responseText); } catch {}
      x.status >= 200 && x.status < 300 ? res(j) : rej(errOf(j.error || (x.status === 413 ? "too big" : "HTTP " + x.status)));
    };
    x.onerror = () => rej(errOf("net")); x.onabort = () => rej(fxStop());
    x.send(body);
  });
}
function fxSend(list, o) {
  const k = cur, max = fxRelay(k) ? FX.relay : FX.lan;
  for (const f of [...list]) {
    const job = { id: fxHex(), kid: k, kind: "up", name: f.name, size: f.size, pct: 0, state: "queue", err: "", file: f, chat: !!(o && o.chat), at0: Date.now() };
    if (f.size > max) { job.state = "err"; job.big = true; job.err = t(fxRelay(k) ? "fx.e.relayBig" : "fx.e.big", fxSize(max)); }
    fx.jobs.unshift(job);
  }
  fxPaint(); fxPump();
}
// one upload at a time (the phone serves 4 connections; the relay wants chunks in order)
async function fxPump() {
  if (fxPump.on) return; fxPump.on = true;
  try { for (let j; (j = fx.jobs.slice().reverse().find(x => x.kind === "up" && x.state === "queue"));) await fxUp(j); }
  finally { fxPump.on = false; }
}
async function fxUp(job) {
  const k = job.kid, f = job.file; job.state = "run"; job.pct = 0; job.err = ""; fxPaint();
  try {
    if (!fxRelay(k)) {
      const j = await fxXhr(job, "PUT", base(k) + "/files/" + encodeURIComponent(f.name) + (job.chat ? "?chat=1" : ""), f, n => { job.pct = n / Math.max(1, f.size); fxPaint(); });
      job.fid = j.file && j.file.id; job.state = "done"; job.pct = 1; fxLoad(k); if (job.chat) refresh();
    } else {
      const fid = job.fid = fxHex(), n = Math.max(1, Math.ceil(f.size / FX.chunk));
      for (let i = 0; i < n; i++) {
        if (job.state === "stop") throw fxStop();
        const q = `?fid=${fid}&i=${i}&n=${n}&to=kid&size=${f.size}&name=${encodeURIComponent(f.name)}${job.chat ? "&chat=1" : ""}`;
        // a dropped chunk is sent again (same fid: the relay takes a repeat chunk as is) instead of dropping the whole file:
        // each new start costs the relay's daily quota (review 2026-11-18)
        for (let a = 0; ; a++) {
          try { await fxXhr(job, "POST", base(k) + "/file" + q, f.slice(i * FX.chunk, (i + 1) * FX.chunk), b => { job.pct = (i * FX.chunk + b) / Math.max(1, f.size); fxPaint(); }); break; }
          catch (e) {
            if (a >= 2 || job.state === "stop" || !(e.raw === "net" || /^HTTP 5/.test(e.raw || ""))) throw e;
            await new Promise(s => setTimeout(s, 3000 * (a + 1)));
          }
        }
      }
      job.pct = 1; job.state = "wait"; job.at = Date.now();   // "done" once the phone lists it (state files, next sync)
    }
  } catch (e) {
    job.state = e.raw === "abort" ? "stop" : "err"; job.err = fxErr(e);
    if (job.fid && fxRelay(k)) fetch(base(k) + "/file?fid=" + job.fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
  }
  job.abort = null; fxPaint();
}
function fxSave(blob, name) {
  const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = name;
  document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 60000);
}
// One file (or its thumbnail) from the phone as chunks: LAN GET /files/<id>[?thumb=1], or relay fileUp {file, thumb?} + poll the parent channel.
// job = the progress object (any {state, pct}); returns { parts, drop } (drop = delete the relay copy once the caller has used it).
// The parent app's download bridge (KGApp.dlBegin/dlChunk/dlEnd, Downloads.kt): ~768 KiB base64 pieces; null = none.
function fxSink(name) {
  if (!APP || !(window.KGApp && KGApp.dlBegin)) return null;
  const id = KGApp.dlBegin(name, "application/octet-stream"); if (!id) return null;
  let buf = [], n = 0;
  const flush = async () => {
    if (!n) return;
    const d = await new Promise((ok, no) => { const r = new FileReader(); r.onload = () => ok(r.result); r.onerror = () => no(r.error); r.readAsDataURL(new Blob(buf)); });
    buf = []; n = 0;
    if (!KGApp.dlChunk(id, d.slice(d.indexOf(",") + 1))) throw errOf("failed");
  };
  return {
    put: async u => { buf.push(u); n += u.length; if (n >= 786432) await flush(); },
    end: async ok => { if (ok) await flush(); KGApp.dlEnd(id, ok ? "true" : "false"); },
  };
}
async function fxFetch(k, f, job, thumb, toDisk) {
  const parts = [];
  if (!fxRelay(k)) {
    const ctl = new AbortController(); job.abort = () => ctl.abort();
    const r = await fetch(base(k) + "/files/" + f.id + (thumb ? "?thumb=1" : ""), { headers: fxHdr(k), signal: ctl.signal }).catch(e => { throw e.name === "AbortError" ? fxStop() : errOf("net"); });
    if (!r.ok) { const j = await r.json().catch(() => ({})); throw errOf(j.error || "HTTP " + r.status); }
    const total = +r.headers.get("content-length") || f.size || 1, rd = r.body.getReader(); let got = 0;
    // parent app: written straight to Downloads as it comes (a 200 MB file held twice in the WebView, ~400 MB, could
    // crash it on a small phone; review 2026-11-18), the browser keeps the whole file for its own save
    const sink = toDisk && !thumb && fxSink(f.name);
    try {
      for (;;) {
        const { done, value } = await rd.read().catch(e => { throw e.name === "AbortError" ? fxStop() : errOf("incomplete"); });
        if (done) break; got += value.length; job.pct = got / total; fxPaint();
        if (sink) await sink.put(value); else parts.push(value);
      }
      if (!thumb && got < f.size) throw errOf("incomplete");
      if (sink) await sink.end(true);
    } catch (e) { if (sink) sink.end(false); throw e; }
    return { parts, drop: null, saved: !!sink };
  }
  if (!thumb && f.size > FX.relay) throw errOf("relayBig", t("fx.e.relayBig", fxSize(FX.relay)));
  job.state = "wait"; job.at = Date.now(); fxPaint();
  await call(k, "/cmd", "POST", thumb ? { type: "fileUp", file: f.id, thumb: 1 } : { type: "fileUp", file: f.id });   // the phone puts it on the relay (next sync)
  // a thumb travels under its own fid: every hex digit of the id complemented (0<->f, 1<->e ...), protocol "Family chat" -> "Attachments"
  const tf = String(f.id).replace(/[0-9a-f]/g, d => (15 - parseInt(d, 16)).toString(16));
  const want = x => x.done && x.fid === (thumb ? tf : f.id);
  let meta = null; const end = Date.now() + (thumb ? 90e3 : 5 * 60e3);
  while (!meta) {
    await new Promise(s => setTimeout(s, thumb ? 2500 : 3000));
    if (job.state === "stop") throw fxStop();
    if (Date.now() > end) throw errOf("timeout");
    const l = await call(k, "/file?to=parent").catch(() => ({}));
    meta = (l.files || []).find(want) || null;
  }
  job.state = "run"; fxPaint();
  for (let i = 0; i < meta.n; i++) {
    if (job.state === "stop") throw fxStop();
    const ctl = new AbortController(); job.abort = () => ctl.abort();
    const r = await fetch(base(k) + `/file?fid=${meta.fid}&i=${i}`, { headers: fxHdr(k), signal: ctl.signal }).catch(e => { throw e.name === "AbortError" ? fxStop() : errOf("net"); });
    if (!r.ok) throw errOf("HTTP " + r.status);
    parts.push(await r.arrayBuffer()); job.pct = (i + 1) / meta.n; fxPaint();
  }
  return { parts, drop: () => fetch(base(k) + "/file?fid=" + meta.fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {}) };
}
async function fxGet(f) {
  const k = cur;
  if (fx.jobs.some(j => j.kid === k && j.kind === "down" && j.fid === f.id && /^(run|wait)$/.test(j.state))) return;
  fx.jobs = fx.jobs.filter(j => !(j.kid === k && j.kind === "down" && j.fid === f.id));
  const job = { id: fxHex(), kid: k, kind: "down", name: f.name, size: f.size, fid: f.id, pct: 0, state: "run", err: "" };
  fx.jobs.unshift(job); fxPaint();
  try {
    let parts = [], relayDel = null;
    const got = await fxFetch(k, f, job, false, true); parts = got.parts; relayDel = got.drop;
    if (!got.saved) fxSave(new Blob(parts), f.name); parts = null; job.state = "done"; job.pct = 1;
    // relay: drop the relay copy only after the browser took the file (a failed save keeps it for a retry)
    if (relayDel) relayDel();
  } catch (e) { job.state = e.raw === "abort" ? "stop" : "err"; job.err = fxErr(e); }
  job.abort = null; fxPaint(); fxLoad(k);
}
// "waiting for the phone" too long: download = ask the phone again (fileUp); upload = drop the relay copy and send the file again
function fxResend(job) {
  if (job.state !== "wait") return; const k = job.kid;
  if (job.kind === "down") { call(k, "/cmd", "POST", { type: "fileUp", file: job.fid }).then(() => toast("send|" + t("pw.resent"))).catch(e => toast("err|" + fxErr(e))); return; }
  if (job.fid) fetch(base(k) + "/file?fid=" + job.fid, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
  job.state = "queue"; job.pct = 0; job.fid = null; fxPaint(); fxPump(); toast("send|" + t("pw.resent"));
}
function fxCancel(job) { if (!/^(queue|run|wait)$/.test(job.state)) return; const a = job.abort; job.state = "stop"; job.err = t("fx.stopped"); if (a) a(); else if (job.kind === "up" && job.fid && fxRelay(job.kid)) fetch(base(job.kid) + "/file?fid=" + job.fid, { method: "DELETE", headers: fxHdr(job.kid) }).catch(() => {}); fxPaint(); }
async function fxDel(f) {
  if (!(await ask(t(f.dir === "in" ? "fx.delInAsk" : "fx.delOutAsk", f.name), t("fx.del"), true))) return;
  const k = cur;
  try {
    if (!fxRelay(k)) { await call(k, "/files/" + f.id, "DELETE"); fxLoad(k); }
    else await call(k, "/cmd", "POST", { type: "fileDel", file: f.id });
    fx.hide.add(f.id); toast("ok|" + t(fxRelay(k) ? "fx.delQueued" : "fx.deleted"));
  } catch (e) { toast("err|" + fxErr(e)); }
  fxPaint();
}
// bulk delete (select.js): files of both lists, one fileDel {files:[ids]} per 200 (a plain command: LAN posts it to /cmd, relay queues it)
const fxSel = selNew({
  get: () => fxFiles().map(f => ({ id: f.id, t: f.at, size: f.size || 0 })),
  paint: () => { fx.sig = ""; fxDraw(); },
  del: async ids => {
    const set = new Set(ids), sz = fxSel.get().reduce((n, i) => n + (set.has(i.id) ? i.size : 0), 0);
    if (!(await ask(t("sel.filesAsk", ids.length, fxSize(sz)), t("sel.del"), true))) return false;
    const k = cur;
    try { await selChunks(ids, c => call(k, "/cmd", "POST", { type: "fileDel", files: c }).then(() => true)); }
    catch (e) { toast("err|" + fxErr(e)); return false; }
    ids.forEach(i => fx.hide.add(i)); toast("ok|" + t(fxRelay(k) ? "fx.delQueued" : "sel.done", ids.length)); if (!fxRelay(k)) fxLoad(k);
    return true;
  },
});
// relay uploads: done when the phone's state lists the id; stale after the relay TTL
function fxSync() {
  const have = new Set(((st && st.files) || []).concat(fx.list || []).map(f => f.id));
  for (const j of fx.jobs) if (j.kind === "up" && j.state === "wait") {
    if (have.has(j.fid)) j.state = "done"; else if (Date.now() - j.at > 3600e3) { j.state = "err"; j.err = t("fx.e.expired"); }
  }
  fxLoad(); fxPaint();
}
const fxTxt = j => j.state === "queue" ? t("fx.q") : j.state === "run" ? t(j.kind === "up" ? "fx.up" : "fx.getting", Math.floor(j.pct * 100))
  : j.state === "wait" ? t(j.kind === "up" ? "fx.upWait" : "fx.getWait") + (offWhy() ? " · " + offWhy() : "") : j.state === "done" ? t(j.kind === "up" ? "fx.upDone" : "fx.got")
  : j.state === "stop" ? t("fx.stopped") : t("fx.err", j.err);
const fxBar = j => /^(run|wait|queue)$/.test(j.state) ? `<div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.floor(j.pct * 100)}" data-fxb="${j.id}"><i data-fxp="${j.id}" style="width:${Math.floor(j.pct * 100)}%"></i></div>` : "";
const fxTone = j => j.state === "done" ? "ok" : j.state === "err" ? "bad" : "";
function fxPaint() { if (!fx.raf) fx.raf = requestAnimationFrame(() => { fx.raf = 0; fxDraw(); if (typeof chatDraw === "function") chatDraw(); }); }
function fxDraw() {
  const J = $("#fxJobs"), O = $("#fxOut"), I = $("#fxIn"); if (!J || !cur) return;
  const jobs = fx.jobs.filter(j => j.kid === cur), files = fxFiles(), dl = id => jobs.find(j => j.kind === "down" && j.fid === id);
  // full redraw only when the structure changed; a progress tick only moves bars / text (a click is never lost mid-press)
  const sig = JSON.stringify([jobs.map(j => [j.id, j.state, j.err]), files.map(f => [f.id, f.st, f.name]), fxSel.on, [...fxSel.ids], fxSel.range, fxSel.from, fxSel.to]);
  if (sig === fx.sig && J.dataset.ok) {
    for (const j of jobs) { const p = $(`[data-fxp="${j.id}"]`), x = $(`[data-fxt="${j.id}"]`), b = $(`[data-fxb="${j.id}"]`);
      if (p) p.style.width = Math.floor(j.pct * 100) + "%"; if (b) b.setAttribute("aria-valuenow", Math.floor(j.pct * 100)); if (x) x.textContent = fxTxt(j); }
    return;
  }
  fx.sig = sig; J.dataset.ok = 1;
  const S = fxSel, days = new Map(selDays(S.get()).map(d => [d.key, d])), dh = (f, i, arr) => S.on && (!i || selDayKey(f.at) !== selDayKey(arr[i - 1].at)) && days.get(selDayKey(f.at)) ? selDayHead(S, days.get(selDayKey(f.at)), esc(selDayLabel(selDayKey(f.at)))) : "";
  O.classList.toggle("selmode", S.on); I.classList.toggle("selmode", S.on);
  const SB = $("#fxSelBar"); if (SB) SB.innerHTML = S.on ? selBar(S) : files.length ? selBtn(S) : "";
  const row = (name, sub, status, bar, btns, id) => `<div class="fxrow${id ? selCls(S, id) : ""}"${id ? ` data-sid="${id}"` : ""}><div class="sp fxm"><b class="fxn">${esc(name)}</b> <span class="mute">${sub}</span>${status}${bar}</div><div class="fxb">${btns}</div></div>`;
  const xb = (act, id, icon, label, cls = "") => `<button data-fx="${act}" data-id="${id}" class="${cls}" aria-label="${esc(label)}" title="${esc(label)}">${ic(icon)}</button>`;
  J.innerHTML = jobs.filter(j => j.kind === "up").map(j => row(j.name, fxSize(j.size),
    `<div class="mute" role="status" data-fxt="${j.id}"${fxTone(j) ? ` style="color:var(--${fxTone(j)})"` : ""}>${esc(fxTxt(j))}</div>`, fxBar(j),
    (/^(queue|run|wait)$/.test(j.state) ? `<button data-fx="cancel" data-id="${j.id}">${ic("x")} ${t("fx.cancel")}</button>` : "") +
    (j.state === "wait" && j.file && !j.big ? `<button data-fx="resend" data-id="${j.id}">${ic("refresh-cw")} ${t("pw.resend")}</button>` : "") +
    (/^(err|stop)$/.test(j.state) && j.file && !j.big ? xb("retry", j.id, "refresh-cw", t("fx.retry")) : "") +
    (/^(done|err|stop)$/.test(j.state) ? xb("dismiss", j.id, "x", t("fx.dismiss")) : ""))).join("");
  const out = files.filter(f => f.dir === "out"), inn = files.filter(f => f.dir === "in");
  const meta = f => fxSize(f.size) + " · " + esc(ago(f.at)) + ` <span class="tag ${/^(opened|fetched)$/.test(f.st) ? "ok" : f.st === "gone" ? "warn" : ""}">${esc(tc("fx.st.", f.st, f.st))}</span>`;
  O.innerHTML = out.length ? out.map((f, i) => { const j = dl(f.id);
    const st2 = j ? `<div class="mute" role="status" data-fxt="${j.id}"${fxTone(j) ? ` style="color:var(--${fxTone(j)})"` : ""}>${esc(fxTxt(j))}</div>` : "";
    const busy = j && /^(run|wait)$/.test(j.state);
    return dh(f, i, out) + row(f.name, meta(f), st2, j ? fxBar(j) : "",
      (FXNO ? "" : busy ? `<button data-fx="cancel" data-id="${j.id}">${ic("x")} ${t("fx.cancel")}</button>${j.state === "wait" ? `<button data-fx="resend" data-id="${j.id}">${ic("refresh-cw")} ${t("pw.resend")}</button>` : ""}`
        : `<button class="pri" data-fx="get" data-id="${f.id}">${ic(j && j.state === "done" ? "check" : j && j.state === "err" ? "refresh-cw" : "download")} ${t(j && j.state === "done" ? "fx.got" : j && j.state === "err" ? "fx.retry" : "fx.get")}</button>`) +
      xb("del", f.id, "trash-2", t("fx.del"), "bad"), f.id);
  }).join("") : `<p class="mute">${t("fx.none")} ${t("fx.fromKidP")}</p>`;
  I.innerHTML = inn.length ? inn.map((f, i) => dh(f, i, inn) + row(f.name, meta(f), "", "", xb("del", f.id, "trash-2", t("fx.del"), "bad"), f.id)).join("") : `<p class="mute">${t("fx.none")}</p>`;
}
function filesTab(b) {
  const relay = fxRelay(), max = relay ? FX.relay : FX.lan;
  fx.sig = ""; fxSel.on = false; fxSel.ids.clear(); fxSel.range = "";
  b.innerHTML = `<div class="card"><h2>${ic("upload")} ${t("fx.sendH")}</h2>
    <p class="mute">${t(relay ? "fx.sendPr" : "fx.sendP", fxSize(max))}</p>
    ${FXNO ? alertBox("warn", "triangle-alert", t("fx.app")) : `<div id="fxDrop" class="fxdrop"><input type="file" id="fxPick" multiple hidden>
      <button class="pri" id="fxBtn">${ic("upload")} ${t("fx.pick")}</button><span class="mute">${t("fx.drop")}</span></div>`}
    <div id="fxJobs"></div></div>
  <div class="card"><h2>${ic("inbox")} ${t("fx.fromKid")}</h2><div id="fxOut"></div></div><div class="selpg" id="fxSelBar"></div>
  <div class="card"><h2>${ic("folder-open")} ${t("fx.sent")}</h2><div id="fxIn"></div></div>
  <p class="mute">${ic("shield")} ${t("fx.scope")}</p>
  <p class="mute">${ic("message-square")} ${t("fx.chatNote")} <button type="button" class="sp" id="fxChat">${t("chat.gal")}</button></p>`;
  $("#fxChat").onclick = () => galOpen();
  const pick = $("#fxPick"), drop = $("#fxDrop");
  if (pick) { $("#fxBtn").onclick = () => pick.click(); pick.onchange = () => { if (pick.files.length) fxSend(pick.files); pick.value = ""; }; }
  if (drop) {
    drop.ondragover = e => { e.preventDefault(); drop.classList.add("on"); };
    drop.ondragleave = () => drop.classList.remove("on");
    drop.ondrop = e => { e.preventDefault(); e.stopPropagation(); drop.classList.remove("on"); if (e.dataTransfer.files.length) fxSend(e.dataTransfer.files); };
  }
  selPress(b, fxSel); b.onchange = e => { selChange(fxSel, e); };
  b.onclick = e => {
    if (selClick(fxSel, e)) return;
    if (fxSel.on && e.target.closest(".fxrow")) return;
    const el = e.target.closest("[data-fx]"); if (!el) return;
    const id = el.dataset.id, job = fx.jobs.find(j => j.id === id), f = fxFiles().find(x => x.id === id);
    ({ cancel: () => job && fxCancel(job), dismiss: () => { fx.jobs = fx.jobs.filter(j => j !== job); fxPaint(); },
       retry: () => { if (job) { job.state = "queue"; job.pct = 0; job.fid = null; fxPaint(); fxPump(); } },
       resend: () => job && fxResend(job),
       get: () => f && fxGet(f), del: () => f && fxDel(f) })[el.dataset.fx]();
  };
  fxDraw(); fxLoad();
}
// a file dropped outside the drop zone must not navigate the page away (and drop the transfers)
["dragover", "drop"].forEach(n => window.addEventListener(n, e => { if (tab === "files" && cur && e.dataTransfer && [...e.dataTransfer.types].includes("Files")) { e.preventDefault(); if (n === "drop" && !FXNO) fxSend(e.dataTransfer.files); } }));
window.addEventListener("beforeunload", e => { if (fx.jobs.some(j => /^(run|wait|queue)$/.test(j.state))) { e.preventDefault(); e.returnValue = ""; } });

// Wrong-PIN photo (rule pinShot): an event with "shot" -> LAN GET /shot/<id>, or relay shotUp + the file channel (one chunk)
const shotUrls = {};
async function shotGet(k, id) {
  if (shotUrls[id]) return shotUrls[id];
  let blob;
  if (!fxRelay(k)) {
    const r = await fetch(base(k) + "/shot/" + id, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
    if (!r.ok) throw errOf(r.status === 404 ? "shot.gone" : "HTTP " + r.status);
    blob = await r.blob();
  } else {
    let meta = ((await call(k, "/file?to=parent").catch(() => ({}))).files || []).find(x => x.fid === id && x.done);
    if (!meta) await call(k, "/cmd", "POST", { type: "shotUp", shot: id });
    const end = Date.now() + 3 * 60e3;
    while (!meta) {
      await new Promise(s => setTimeout(s, 3000));
      if (Date.now() > end) throw errOf("timeout");
      meta = ((await call(k, "/file?to=parent").catch(() => ({}))).files || []).find(x => x.fid === id && x.done) || null;
    }
    const r = await fetch(base(k) + `/file?fid=${id}&i=0`, { headers: fxHdr(k) }).catch(() => { throw errOf("net"); });
    if (!r.ok) throw errOf("HTTP " + r.status);
    blob = new Blob([await r.arrayBuffer()], { type: "image/jpeg" });
    fetch(base(k) + "/file?fid=" + id, { method: "DELETE", headers: fxHdr(k) }).catch(() => {});
  }
  return (shotUrls[id] = URL.createObjectURL(blob));
}
function shotOpen(id) {
  const k = cur; if (!k || !/^[0-9a-f]{16}$/.test(id)) return;
  sheet(`<div class="dp" role="dialog" aria-modal="true" aria-labelledby="askT">${sheetHead("camera", t("shot.h"))}
    <div id="shotBox" class="mute" style="text-align:center;padding:12px 0">${t(fxRelay(k) ? "shot.waitRelay" : "shot.wait")}</div>
    <p class="mute">${ic("info")} ${t("shot.note")}</p></div>`);
  shotGet(k, id).then(u => { const b = $("#shotBox"); if (b) b.innerHTML = `<img src="${u}" alt="${esc(t("shot.h"))}" style="max-width:100%;max-height:60vh;border-radius:12px">`; },
    e => { const b = $("#shotBox"); if (b) b.textContent = e.raw === "shot.gone" ? t("shot.gone") : e.message || t("fx.e.failed"); });
}
document.addEventListener("click", e => { const b = e.target.closest && e.target.closest("[data-shot]"); if (b) shotOpen(b.dataset.shot); });

// KidGuard parent page, part of ui/index.html (classic script, shared globals, load order = the <script> tags there).
// per-kid "seen" flags kept in this browser: the away-from-home tip, the manual key-expiry step
const kidFlag = (k, n, v) => { const key = "kg." + n + "." + (k.id || k.target || ""); try { if (v === undefined) return localStorage.getItem(key) === "1"; localStorage.setItem(key, "1"); } catch {} return false; };
// Remote access over Tailscale (state.ts). The auth key is only typed at home (LAN): it never goes through the relay.
function tsCard(lan) {
  if (isBasic()) return sec("s-ts", "route", t("ts.h"), t("sd.ts"), [t("off")], `${needOwner("Tailscale")}<p class="mute">${t("ts.basic")}</p>`);
  const ts = (st && st.ts) || { state: "off" };
  const label = tc("ts.", ts.state, ts.state);
  const cls = { on: "ok", error: "bad", installing: "warn" }[ts.state] || "";
  const url = ts.ip ? `http://${ts.ip}:8770` : "";
  if (!lan && ts.state === "off") return "";   // relay page: it can only be enabled at home, so an "off" row says nothing
  const exp = ts.state === "on" && ts.noExpiry === false && !kidFlag(cur, "tsExp");   // false only from phones that report it
  return sec("s-ts", "route", t("ts.h"), t("sd.ts"), [esc(label), cls], `
    <p class="mute">${t("ts.p")}</p>
    ${ts.host ? `<p>${t("ts.host", "<b>" + esc(ts.host) + "</b>")}</p>` : ""}
    ${ts.err ? `<div class="alert warn">${ic("triangle-alert")} ${esc(ts.err)}</div>` : ""}
    ${url ? `<p>${ic("link")} <code>${esc(url)}</code>${ts.noExpiry ? ` <span class="tag ok">${t("ts.noExp")}</span>` : ""}</p>` : ""}
    ${exp ? `<div class="alert warn">${ic("triangle-alert")} <span>${t("ts.expP", "<b>" + esc(ts.host || "kid") + "</b>")}
      <span class="row" style="margin-top:8px"><a class="lnkbtn" href="https://login.tailscale.com/admin/machines" target="_blank" rel="noopener">${ic("link")} ${t("ts.machines")}</a><button type="button" id="tsExpOk">${ic("check")} ${t("ts.expDone")}</button></span></span></div>` : ""}
    ${lan ? `<p class="mute">${t("ts.keyP")}</p>
    <ol class="mute" style="margin:6px 0 10px;padding-left:20px"><li>${t("ts.s1")}</li><li>${t("ts.s2")} <a href="https://login.tailscale.com/admin/settings/keys" target="_blank" rel="noopener">${t("ts.keysPage")}</a></li><li>${t("ts.s3")}</li></ol>
    <div class="row"><input id="f-ts-key" class="sp" type="password" autocomplete="off" placeholder="tskey-api-… / tskey-auth-…" aria-label="${esc(t("ts.key"))}"></div>
    <div class="row" style="margin-top:8px"><input id="f-ts-host" class="sp" placeholder="${esc(t("ts.hostPh"))}" aria-label="${esc(t("ts.hostPh"))}" value="${esc(ts.host || "")}"><button id="tsOn" class="pri">${ic("route")} ${ts.state === "off" ? t("turnOn") : t("ts.again")}</button></div>`
      : `<p class="mute">${t("ts.homeOnly")}</p>`}
    ${ts.state !== "off" ? `<button id="tsOff" class="bad" style="margin-top:8px">${ic("x")} ${t("ts.remove")}</button>
    <p class="mute">${t("ts.after", esc(ts.host || "kid"))}</p>` : ""}`, ts.state === "error" || exp);
}

// Self-update of the child app (state.update, v0.9): status line, "update now", rule autoUpdate.
// Countdown under the "pair another parent phone" button: from the child's state, or from our own command until a newer state arrives.
let pairLocal = null;
function pairTick() {
  const el = $("#pairOut"); if (!el || !st) { clearInterval(pairTick.i); return; }
  const end = pairLocal && st.t < pairLocal.at ? pairLocal.end : (st.pairUntil || 0), left = end - Date.now();
  el.innerHTML = left > 0 ? `${ic("hourglass")} ${t("pairT.on", "<b>" + fmtClock(left) + "</b>")}` : t("pairT.off");
}
// Developer options (state.dev, v0.9.6): banking apps refuse to open while on; "devOff" turns it off, "devOn" (0.9.7) back on.
// devPend {kid, want, at, ok, fail}: from pressing a button until a state shows `want`, an error answer, or a verdict:
// "ok" (the phone checked it) but a state taken 5 s later still says otherwise = something switched it back;
// queued on the relay: still on in a state 90 s after the press; no state at all: 3 min (a timer redraws, refresh may not).
let devPend = null;
function devCard() {
  const d = st.dev; if (!d) return "";   // child older than 0.9.6
  const on = !!(d.on || d.adb), p = devPend && devPend.kid === cur ? devPend : null;
  if (p && !p.fail && on === p.want) devPend = null;
  else if (p && !p.fail && p.ok && st.t > p.ok + 5000) p.fail = t("dev.reOn");
  else if (p && !p.fail && st.t > p.at + 90000) p.fail = t("dev.stillOn");
  else if (p && !p.fail && Date.now() - p.at > 180000) p.fail = t("dev.timeout");
  const busy = p && !p.fail && on !== p.want, fail = p && p.fail ? `<div class="alert warn">${ic("triangle-alert")} ${t(p.want ? "dev.failOn" : "dev.fail", esc(p.fail))}</div>` : "";
  return sec("s-dev", "code", t("dev.h"), t("sd.dev"), [on ? t("dev.on") : t("dev.off"), on ? "warn" : "ok"], on ? `<p class="mute">${t("dev.p")}${d.adb ? " " + t("dev.adb") : ""}</p>
    ${fail}
    ${d.canOff ? `<button id="devOff" ${busy ? "disabled" : ""}>${ic(busy ? "hourglass" : "x")} ${busy ? t("dev.pend") : t("dev.btn")}</button>` : `<p class="mute">${ic("circle-help")} ${t("dev.cant")}</p>`}` : `<p class="mute">${ic("check")} ${t("dev.offP")}</p>${fail}
    ${d.canOn ? `<button id="devOn" ${busy ? "disabled" : ""}>${ic(busy ? "hourglass" : "code")} ${busy ? t("dev.pendOn") : t("dev.onBtn")}</button>` : ""}`, on || !!fail);
}
async function devOff(want) {
  want = want === true;
  if (want && !(await ask(t("dev.askOn"), t("dev.onBtn")))) return;
  const k = cur, p = devPend = { kid: k, want, at: Date.now(), ok: 0, fail: "" }; render();
  const r = await cmd({ type: want ? "devOn" : "devOff" }, t(want ? "dev.doneOn" : "dev.done"), false);
  // cmd() already toasted the reason; "ok" / "queued" stay pending until a state settles it (see devPend)
  if (devPend === p) { if (!r) p.fail = t("upd.unknown"); else if (r.result === "ok") p.ok = Date.now(); else if (r.result !== "queued") p.fail = r.result; }
  clearTimeout(devOff.t); devOff.t = setTimeout(() => { if (devPend === p && tab === "set") render(); }, 181000);
  render(); setTimeout(refresh, r && r.result === "queued" ? 15000 : 1500);
}
// "Chức năng & quyền": every feature with ok / the permissions it still needs (state.features, phone >= 0.9.9)
function featCard() {
  const F = Array.isArray(st.features) ? st.features : null; if (!F) return "";
  const n = F.filter(f => f.ok).length;
  const rows = F.map(f => `<tr class="${f.ok ? "" : "no"}"><th scope="row">${esc(hasT("ft_" + f.id) ? t("ft_" + f.id) : f.id)}</th>
    <td>${f.ok ? `<span class="tag ok">${ic("check")} ${t("ft.ok")}</span>` : `<span class="tag warn">${ic("lock")} ${t("cap_need", esc((f.missing || []).map(capName).join(", ") || "?"))}</span>`}</td></tr>`).join("");
  return sec("s-feat", "list-checks", t("ft.h"), t("ft.d"), [`${n}/${F.length}`, n === F.length ? "ok" : "warn"], `<div class="tblw"><table class="ft"><tbody>${rows}</tbody></table></div>
    <p class="mute">${t("ft.p")}</p>`, n < F.length);
}
function updCard() {
  const u = st.update; if (!u) return "";   // child older than 0.9: no self-update
  const newer = (a, b) => { const x = String(a).split(".").map(Number), y = String(b).split(".").map(Number); for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; } return false; };
  const has = u.latest && newer(u.latest, st.version);
  const v = esc(u.latest);
  const line = u.status === "checking" ? t("upd.checking") : u.status === "downloading" ? t("upd.dl", v) : u.status === "installing" ? t("upd.inst", v)
    : u.status === "ready" ? t("upd.ready", v)
    : u.status === "failed" ? `${ic("triangle-alert")} ${t("errX", esc(u.err || t("upd.unknown")))}` : has ? t("upd.has", v) : t("upd.latest");
  const busy = u.status === "downloading" || u.status === "installing" || u.status === "checking";
  return sec("s-upd", "refresh-cw", t("upd.h"), t("sd.upd"), has ? [t("upd.has", v), "warn"] : [esc(st.version || "?"), "ok"], `
    <p>${t("upd.kid", "<b>" + esc(st.version || "?") + "</b>")} · ${line}${u.checkedAt ? ` <span class="mute">· ${t("upd.checked", ago(u.checkedAt))}</span>` : ""}</p>
    <label class="row"><input type="checkbox" id="f-au" ${edit && edit.autoUpdate ? "checked" : ""}><span class="sp">${t("upd.auto")}</span></label>
    <div class="row" style="margin-top:8px"><button id="updNow" ${busy ? "disabled" : ""}>${ic("refresh-cw")} ${t("upd.now")}</button></div>`, has || busy || u.status === "failed");
}
// paired parent devices (state.devices, phone >= 0.9.8). Our own row: "self" on LAN; on the relay page, sha256(token)[:8].
const devIds = new Map();
async function devIdOf(k) {
  if (devIds.has(k.token)) return devIds.get(k.token);
  try { const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(k.token)));
    const id = [...h.slice(0, 4)].map(x => x.toString(16).padStart(2, "0")).join(""); devIds.set(k.token, id); return id; }
  catch { devIds.set(k.token, ""); return ""; }   // no WebCrypto (plain http): just no "this device" mark
}
function devsHtml() {
  const D = st.devices; if (!Array.isArray(D)) return "";
  const me = devIds.get(cur.token);
  return D.map(d => { const self = d.self || (!!me && d.id === me);
    return `<div class="app"><span class="ib">${ic(d.name === "relay" ? "globe" : "smartphone")}</span><div class="nm"><b>${d.name ? esc(d.name) : t("dev.noName")}</b>
      <span class="mute">${self ? `<span class="tag t-info" style="margin:0 4px 0 0">${t("dev.self")}</span>` : ""}${t("dev.at", d.at ? new Date(d.at).toLocaleDateString(lang) : "—")} · ${t("dev.last", d.last ? ago(d.last) : "—")}</span></div>
      <button type="button" class="bad" data-unp="${esc(d.id)}">${ic("unlink")} ${t("dev.unpair")}</button></div>`; }).join("") || `<p class="mute">${t("none")}</p>`;
}
// ---- "Cài đặt máy": volume, brightness, screen timeout, DND (command `setting`, state `sys`) ----
// The phone's state is the truth: a control shows the phone's value unless a change of ours is still on its way.
// pend[key] = {v, id, at, s: "pending" | "sent" | "fail", why}. "sent" = the phone answered ok (LAN); every change waits
// for a state carrying its result (sys.last[key].id), then the control follows the phone again. A failed one rolls back.
const SYS_TO = [15, 30, 60, 120, 300, 600].map(s => s * 1000);
const sysUi = { k: null, pend: {}, done: {}, drag: null, deb: {} };
function sysOf() { if (sysUi.k !== cur) { sysUi.k = cur; sysUi.pend = {}; sysUi.done = {}; } return st && st.sys; }
function sysPhone(s, key) {   // the phone's value for a key
  if (key.startsWith("vol.")) { const a = s.vol && s.vol[key.slice(4)]; return a ? +a[0] : null; }
  if (key === "bright") return s.bright ? +s.bright[0] : null;
  return s[key] ?? null;
}
// settle our changes against the phone's state: its result for our id, else a timeout once the phone has clearly synced since
function sysSettle(s) {
  const now = Date.now(), last = s.last || {}, lan = cur.via === "lan";
  for (const [key, p] of Object.entries(sysUi.pend)) {
    const r = last[key];
    if (r && r.id === p.id) {
      if (r.res === "ok") { delete sysUi.pend[key]; sysUi.done[key] = now; } else Object.assign(p, { s: "fail", why: r.res });
    } else if (p.s === "sent" && now - p.at > 30000) { delete sysUi.pend[key]; sysUi.done[key] = now; }   // said ok, state just lagging
    else if (p.s === "pending" && (lan ? now - p.at > 30000 : now - p.at > 90000 && lastSeen() > p.at + 45000)) Object.assign(p, { s: "fail", why: "timeout" });
  }
}
function sysCtl(s, key) {   // value shown + status html
  const p = sysUi.pend[key], v = p && p.s !== "fail" ? p.v : sysPhone(s, key);
  const stt = p ? (p.s === "fail" ? `<span class="tag bad" role="status">${ic("triangle-alert")} ${esc(t("sys.fail", tc("sr.", p.why, p.why)))}</span>`
    : `<span class="tag" role="status">${ic("hourglass")} ${t(cur.via === "lan" ? "sys.pending" : "sys.queued")}</span>`)
    : Date.now() - (sysUi.done[key] || 0) < 4000 ? `<span class="tag ok" role="status">${ic("check")} ${t("sys.done")}</span>` : "";
  return { v, stt };
}
function sysHtml() {
  const s = sysOf();
  if (!s || viewDay) return "";
  sysSettle(s);
  const can = s.can || {}, rows = [];
  const range = (key, icon, label, min, max, shown, off, why) => {
    const { v, stt } = sysCtl(s, key), id = "f-sys-" + key.replace(".", "-");
    return `<div style="margin:10px 0"><label class="row" for="${id}"><span>${ic(icon)}</span><span class="sp">${label}</span><b data-sysv="${key}">${v == null ? "—" : shown(v)}</b>${stt}</label>
      <input type="range" id="${id}" data-sys="${key}" min="${min}" max="${max}" step="1" value="${v ?? min}" style="width:100%" ${off || v == null ? "disabled" : ""} aria-label="${esc(label)}">
      ${why ? `<div class="mute">${why}</div>` : ""}</div>`;
  };
  for (const [key, icon] of [["vol.ring", "bell"], ["vol.media", "music"], ["vol.alarm", "alarm-clock"]]) {
    const a = s.vol && s.vol[key.slice(4)]; if (!a) continue;
    rows.push(range(key, icon, t("sys." + key.slice(4)), 0, +a[1], v => v + "/" + +a[1], !can.vol, !can.vol ? t("sys.volFixed") : key === "vol.ring" && s.dnd ? t("sys.ringDnd") : ""));
  }
  if (s.bright) {
    const max = +s.bright[1] || 255, auto = sysCtl(s, "autoBright"), autoOn = !!auto.v;
    rows.push(range("bright", "sun", t("sys.bright"), 1, max, v => Math.round(v * 100 / max) + "%", !can.bright || autoOn,
      !can.bright ? t("sys.noWrite") : autoOn ? t("sys.autoHint") : ""));
    if (s.autoBright != null) rows.push(`<label class="row" style="margin:10px 0"><input type="checkbox" data-sys="autoBright" ${autoOn ? "checked" : ""} ${can.bright ? "" : "disabled"}><span class="sp">${t("sys.auto")}</span>${auto.stt}</label>`);
  }
  if (s.timeoutMs != null) {
    const { v, stt } = sysCtl(s, "timeoutMs"), opts = SYS_TO.includes(+v) ? SYS_TO : SYS_TO.concat(+v).sort((a, b) => a - b);
    const lbl = ms => ms < 60000 || ms % 60000 ? t("sys.sec", Math.round(ms / 1000)) : t("fmt.m", ms / 60000);
    rows.push(`<div style="margin:10px 0"><label class="row" for="f-sys-to"><span>${ic("timer")}</span><span class="sp">${t("sys.timeout")}</span>${stt}</label>
      <select id="f-sys-to" data-sys="timeoutMs" style="width:100%" ${can.timeout ? "" : "disabled"}>${opts.map(o => `<option value="${o}" ${o === +v ? "selected" : ""}>${esc(lbl(o))}</option>`).join("")}</select>
      ${can.timeout ? "" : `<div class="mute">${t("sys.noWrite")}</div>`}</div>`);
  }
  if (s.dnd != null) {
    const { v, stt } = sysCtl(s, "dnd");
    rows.push(`<label class="row" style="margin:10px 0"><input type="checkbox" data-sys="dnd" ${v ? "checked" : ""} ${can.dnd ? "" : "disabled"}><span>${ic("bell-off")}</span><span class="sp">${t("sys.dnd")}</span>${stt}</label>
      ${can.dnd ? "" : `<div class="mute">${t("sys.noDnd")}</div>`}`);
  }
  const off = cur.via !== "lan" && Date.now() - lastSeen() > 120000;
  return `${off ? `<div class="alert warn">${ic("wifi-off")} ${t("sys.offline")}</div>` : ""}${rows.join("") || `<p class="mute">${t("sys.none")}</p>`}`;
}
// the card redraws in place on every refresh (the tab is not redrawn while a control has focus), never while a slider is held
function sysDraw() {
  const box = $("#sysCard"); if (!box) return;
  if (sysUi.drag || Object.keys(sysUi.deb).length) return;
  const h = sysHtml(); (box.closest(".cl") || box).style.display = h ? "" : "none";
  const f = document.activeElement && box.contains(document.activeElement) ? document.activeElement.id : "";
  box.innerHTML = h;
  if (f && $("#" + f)) $("#" + f).focus();
  box.querySelectorAll("[data-sys]").forEach(el => {
    const key = el.dataset.sys;
    if (el.type === "range") {
      el.onpointerdown = () => { sysUi.drag = key; };
      el.oninput = () => { const b = box.querySelector(`[data-sysv="${key}"]`), s = st.sys;   // live number while sliding, nothing sent
        if (b) b.textContent = key === "bright" ? Math.round(el.value * 100 / (+s.bright[1] || 255)) + "%" : el.value + "/" + el.max; };
    }
    // "change" = released (mouse / touch) or a key step: a short pause so held arrow keys send once
    el.onchange = () => {
      sysUi.drag = null;
      const v = el.type === "checkbox" ? el.checked : +el.value;
      clearTimeout(sysUi.deb[key]);
      sysUi.deb[key] = setTimeout(() => { delete sysUi.deb[key]; sysSend(key, v); }, el.type === "range" ? 400 : 0);
    };
  });
}
// a slider touched but not moved: no change event, so let go of it here
document.addEventListener("pointerup", () => { if (sysUi.drag) setTimeout(() => { if (sysUi.drag) { sysUi.drag = null; sysDraw(); } }, 50); });
async function sysSend(key, v) {
  const k = cur, s = st && st.sys;
  if (!s) return;
  if (v === sysPhone(s, key) && !sysUi.pend[key]) return sysDraw();
  const id = "s" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  sysUi.pend[key] = { v, id, at: Date.now(), s: "pending" }; delete sysUi.done[key];
  sysDraw();
  let r;
  try { r = await call(k, "/cmd", "POST", { type: "setting", key, value: v, id }); }
  catch (e) { r = { result: e.message || "failed" }; }
  const p = sysUi.pend[key];
  if (k !== cur || !p || p.id !== id) return;   // another kid open now, or a newer change took over
  if (r.result === "ok") { p.s = "sent"; refresh(); }
  else if (r.result !== "queued") Object.assign(p, { s: "fail", why: r.result || "failed" });
  sysDraw();
}

function setTab(b) {
  const lan = cur.via === "lan", S = ruleSecs(edit);
  if (Array.isArray(st.devices) && !st.devices.some(d => d.self) && !devIds.has(cur.token)) { const k = cur; devIdOf(k).then(id => { if (id && k === cur && tab === "set") { lastSig = ""; render(); } }); }
  b.innerHTML = `<div class="g2"><div>
  <div class="sec">${t("set.security")}</div>
  ${sec("s-pin", "key-round", t("pin.h"), t("pin.p"), edit.pinSet ? [t("pin.isSet"), "ok"] : [t("pin.notSet"), "warn"], `
    ${edit.pinSet ? "" : `<div class="alert warn">${ic("triangle-alert")} ${t("pin.none")}</div>`}
    <p class="mute">${edit.pinSet ? t("pin.change") : t("pin.set")}</p>
    <div class="row"><input class="sp" style="min-width:8em" id="f-pin" inputmode="numeric" type="password" placeholder="${esc(t("pin.ph"))}" aria-label="${esc(t("pin.new"))}" maxlength="8" autocomplete="new-password"><button id="savePin">${ic("key-round")} ${edit.pinSet ? t("pin.changeB") : t("pin.setB")}</button></div>`, !edit.pinSet)}
  <div id="rsx">${S.totp}</div>
  ${sec("s-paired", "smartphone", t("paired.h"), t("sd.paired"), Array.isArray(st.devices) ? [String(st.devices.length)] : null, `${Array.isArray(st.devices) ? devsHtml() : `<p class="mute">${t("dev.old")}</p>`}
    <div class="row" style="margin-top:10px"><button id="openPair" class="sp">${ic("lock-open")} ${t("paired.add")}</button><button id="forget" class="sp">${ic("eraser")} ${APP ? t("forget.app") : t("forget.web")}</button></div><div id="pairOut" class="mute" style="margin-top:8px"></div>`, st.pairUntil > Date.now())}
  <div class="sec" id="awaySec">${t("set.conn")}</div>
  ${lan ? `<p class="mute" style="margin:0 4px 10px">${t("away.cmp")}</p>` : ""}
  ${tsCard(lan)}
  ${lan ? sec("s-relay", "globe", t("relay.h"), t("sd.relay"), st.relay ? [t("on"), "ok"] : [t("off")], `<p class="mute">${t("relay.p")}</p>
    <div class="row"><input id="relay" class="sp" placeholder="${esc(t("relay.ph"))}" aria-label="${esc(t("relay.addr"))}" value="${esc(st.relay || "")}"><button id="saveRelay" class="pri">${ic("globe")} ${t("turnOn")}</button></div>
    <div id="relayOut"></div>`, !!st.relay) : `<div class="card"><h2>${ic("globe")} ${t("conn.h")}</h2><p class="mute">${t("conn.p")}</p></div>`}
  ${lan ? "" : `<div id="tgBox"></div>`}
  <div class="dz" style="margin-top:22px">${sec("s-danger", "octagon-x", t("set.danger"), t("sd.danger"), null, `<h2>${ic("unlink")} ${t("paired.unpair")}</h2><p class="mute">${t("ask.unpairAll")}</p><button id="unpair" class="bad">${ic("unlink")} ${t("paired.unpair")}</button>
    <h2 style="margin-top:18px">${ic("octagon-x")} ${t("rel.h")}</h2><p class="mute">${t("rel.p")}</p>
    <button id="release" class="bad">${ic("octagon-x")} ${t("rel.btn")}</button>`)}</div>
  </div><div>
  <div class="sec">${t("set.general")}</div>
  <div class="card"><h2>${ic("user")} ${t("set.name")}</h2><div class="row"><input id="f-name" class="sp" aria-label="${esc(t("set.name"))}" value="${esc(cur.name)}"><button id="saveName">${ic("save")} ${t("save")}</button></div></div>
  ${APP ? `<div class="card"><h2>${ic("history")} ${t("keep.h")}</h2><select id="f-days" aria-label="${esc(t("keep.h"))}">${["0", "30", "90", "365"].map(v => `<option value="${v}" ${String(kgCall("getHistoryDays") || "0") === v ? "selected" : ""}>${+v ? t("days", +v) : t("noLimitC")}</option>`).join("")}</select>
    <p class="mute">${t("keep.p")}</p></div>` : ""}
  ${featCard()}
  ${updCard()}
  ${sec("s-sys", "sliders-horizontal", t("sys.h"), t("sd.sys"), null, `<div id="sysCard"></div>`)}
  ${devCard()}
  ${APP ? "" : `<div class="card"><h2>${ic("smartphone")} ${t("a2hs.h")}</h2><p class="mute">${t("a2hs.p")}</p></div>`}
  </div></div>`;
  rulesWire($("#rsx"), edit);
  b.querySelectorAll("[data-unp]").forEach(x => x.onclick = async () => {
    const d = (st.devices || []).find(o => o.id === x.dataset.unp); if (!d) return;
    const k = cur, self = d.self || d.id === devIds.get(k.token), nm = d.name || t("dev.noName");
    if (!(await ask(t(self ? "dev.askSelf" : "dev.ask", nm), t("dev.unpair"), true))) return;
    x.disabled = true;
    let r;
    try { r = await call(k, "/cmd", "POST", { type: "unpair", id: d.id }); }
    catch (e) {   // LAN answers 404 {"result":"no such device"} when it is already gone
      if (/404|no such device/.test(e.message)) { toast("warn|" + t("cr.unpair.no such device")); refresh().catch(() => {}); }
      else toast("err|" + t("errX", e.message));
      x.disabled = false; return;
    }
    if (r && r.result && r.result !== "ok" && r.result !== "queued") { toast("err|" + tc("cr.unpair.", r.result, t("cmd.answer", r.result))); x.disabled = false; return; }
    toast((r && r.result === "queued" ? "send|" : "ok|") + t(r && r.result === "queued" ? "dev.queued" : "dev.unpaired", nm));
    if (k !== cur) return;
    if (self) {   // this browser's token is gone: back to pairing, never a dead page
      gen++; clearInterval(timer); screenStop(); earStop(); cur = null; kids = kids.filter(o => o !== k); save(kids); navDown(0); return setTimeout(boot, 800);
    }
    st.devices = st.devices.filter(o => o !== d); lastSig = ""; render();
  });
  sysDraw();
  if (APP) $("#f-days").onchange = e => kgCall("setHistoryDays", e.target.value);
  $("#saveName").onclick = () => { cur.name = $("#f-name").value || t("kid"); save(kids); render(); };
  // saved right away (not the 0.5 s debounce) so the button can show sending -> saved / failed
  $("#savePin").onclick = async () => {
    const f = $("#f-pin"), bt = $("#savePin"), p = f.value, k = cur, e = edit;
    if (!/^\d{4,8}$/.test(p)) { toast("warn|" + t("pin.bad")); f.focus(); return; }
    clearTimeout(saveT); saveT = null; e.pin = p;
    const h0 = bt.innerHTML; bt.disabled = f.disabled = true; bt.setAttribute("aria-busy", "true"); bt.innerHTML = `<i class="spin"></i> ${t("act.sending")}`;
    try {
      await putRules(k, e); f.value = "";
      toast(k.via === "lan" ? "ok|" + t("pin.saved") : "send|" + t("sentRelay"));
      if (k === cur && tab === "set") render(); else { bt.innerHTML = h0; bt.disabled = f.disabled = false; }
    } catch (er) {
      delete e.pin; toast("err|" + t("errX", er.message)); if (k === cur) saveFail(true);
      bt.innerHTML = h0; bt.disabled = f.disabled = false; f.focus();   // the PIN stays typed: press again to retry
    } finally { bt.removeAttribute("aria-busy"); }
  };
  if (lan) $("#saveRelay").onclick = async () => {
    const url = $("#relay").value.trim().replace(/\/+$/, "");
    if (!/^https:\/\//.test(url)) return toast("warn|" + t("relay.https"));
    const r = await cmd({ type: "relay", url }, t("turnedOn"));
    if (r && r.token) {
      const link = `${url}/#c=${encodeURIComponent(r.id)}&k=${encodeURIComponent(r.token)}&n=${encodeURIComponent(cur.name)}`;
      $("#relayOut").innerHTML = `<p>${ic("smartphone")} ${t("relay.link")}</p><code>${esc(link)}</code>
        <div class="row" style="margin-top:8px"><button id="copy">${ic("clipboard-copy")} ${t("relay.copy")}</button><a class="lnkbtn" href="${esc(link)}" target="_blank" rel="noopener">${ic("link")} ${t("open")}</a></div>`;
      $("#copy").onclick = () => { navigator.clipboard?.writeText(link).then(() => toast("ok|" + t("copied")), () => toast("warn|" + t("copyFail"))); };
    }
  };
  if ($("#tsOn")) $("#tsOn").onclick = async () => {
    const authKey = $("#f-ts-key").value.trim();
    if (!/^[A-Za-z0-9_-]{10,300}$/.test(authKey)) return toast("warn|" + t("ts.badKey"));
    const r = await cmd({ type: "tailscale", authKey, hostname: $("#f-ts-host").value.trim() }, t("ts.installTx"));
    if (r && r.result && r.result !== "ok") toast("err|" + t("cmd.answer", r.result));
    $("#f-ts-key").value = "";
  };
  if ($("#tsExpOk")) $("#tsExpOk").onclick = () => { kidFlag(cur, "tsExp", 1); render(); };
  if ($("#tsOff")) $("#tsOff").onclick = async () => { await cmd({ type: "tailscaleOff" }, t("turnedOff")); };
  $("#openPair").onclick = async () => {
    const r = await cmd({ type: "openPairing" }, t("pairT.shown"));
    if (r && (r.result === "ok" || r.result === "queued")) { pairLocal = { at: Date.now(), end: Date.now() + 600000 }; pairTick(); }
    if (r && r.result === "set PIN first") toast("warn|" + t("pairT.pinFirst"));
  };
  clearInterval(pairTick.i); pairTick.i = setInterval(pairTick, 1000); pairTick();
  $("#unpair").onclick = async () => { await cmd({ type: "unpairAll" }, undefined, [t("ask.unpairAll"), t("askOk.unpairAll"), true, t("unp.word")]); };
  $("#forget").onclick = async () => {
    const fk = cur;   // typed word: forgetting a relay kid destroys the only token
    if (!(await ask(t(fk.via === "relay" ? "forget.askRelay" : APP ? "forget.askApp" : "forget.askWeb", fk.name), APP ? t("delete") : t("forget"), true, t("forget.word"))) || fk !== cur) return;
    screenStop(); kids = kids.filter(x => x !== cur); save(kids); cur = null; clearInterval(timer); boot(); navDown(0); };
  if ($("#f-au")) $("#f-au").onchange = e => { if (!edit) return; edit.autoUpdate = e.target.checked; changed(); };
  if ($("#devOff")) $("#devOff").onclick = () => devOff(false);
  if ($("#devOn")) $("#devOn").onclick = () => devOff(true);
  if ($("#updNow")) $("#updNow").onclick = () => cmd({ type: "update" }, t("upd.sent"), [t("upd.ask"), t("upd.ok")]).then(() => setTimeout(refresh, 4000));
  if ($("#tgBox")) tgDraw();
  $("#release").onclick = async () => {
    await cmd({ type: "release" }, t("rel.done"), [t("rel.ask"), t("rel.ok"), true, t("rel.word")]);
  };
}

// Telegram alerts (relay kids only; the worker has the bot token). The box stays empty while the worker has no TG_BOT.
async function tgDraw() {
  const k = cur, box = $("#tgBox");
  let st; try { st = await call(k, "/tg"); } catch { return; }
  if (k !== cur || tab !== "set" || !st || !st.enabled || !box.isConnected) return;
  box.innerHTML = sec("s-tg", "send", t("tg.h"), t("tg.d"), st.linked ? [t("on"), "ok"] : [t("off")], st.linked
    ? `<p class="mute">${t("tg.on")}</p><button id="tgOff">${ic("unlink")} ${t("tg.unlink")}</button>`
    : `<p class="mute">${t("tg.p")}</p><button id="tgLink" class="pri">${ic("send")} ${t("tg.link")}</button><div id="tgOut" class="mute"></div>`, false);
  if ($("#tgOff")) $("#tgOff").onclick = async () => {
    try { await call(k, "/tg", "POST", { off: true }); toast("ok|" + t("tg.done")); } catch (e) { toast("err|" + e.message); }
    tgDraw();
  };
  if ($("#tgLink")) $("#tgLink").onclick = async () => {
    try {
      const r = await call(k, "/tg", "POST", { name: k.name, lang });
      if (!/^https:\/\/t\.me\/\w+\?start=[0-9a-f]{32}$/.test(r.link || "")) throw new Error("bad link");
      $("#tgOut").innerHTML = `<p>${t("tg.open")}</p><a class="lnkbtn" href="${esc(r.link)}" target="_blank" rel="noopener">${ic("send")} ${t("tg.openBtn")}</a>`;
      clearTimeout(tgDraw.t);
      const poll = n => { tgDraw.t = setTimeout(() => { if (tab === "set" && k === cur && n < 20) call(k, "/tg").then(x => x.linked ? tgDraw() : poll(n + 1), () => {}); }, 4000); };
      poll(0);
    } catch (e) { toast("err|" + e.message); }
  };
}

// First-pairing guided setup (0.9.17): level -> apps -> bedtime + daily limit -> what your kid will see.
// Works on a copy (`w`) of the rules fields it owns; "Done" merges them into `edit` and saves once (changed() -> putRules).
// "Skip setup" closes without touching the rules. The step is kept per kid in localStorage (resumable); Settings can reopen it.
// Shares globals with kid.js / tab-rules.js (edit, LV_D, LV_F, tplFields, lvLines, rulesUntouched, timeWarn, icon): do not redeclare.
const WIZ_N = 4, WIZ_F = ["allow", "blocked", "dailyMin", "dailyByDay", "bedtime", "bedByDay", "schedules", "blockNew", "safeDns", "noInstall"];
let wiz = null;   // {k, step, pick, applied, w}
const wizKey = k => "kg.wiz." + (k.id || k.token || k.name);
const wizGet = k => { try { return Math.min(WIZ_N, Math.max(1, +localStorage.getItem(wizKey(k)) || 1)); } catch { return 1; } };
const wizPut = (k, n) => { try { if (n) localStorage.setItem(wizKey(k), String(n)); else localStorage.removeItem(wizKey(k)); } catch {} };
const wizMark = k => { k.lv = 1; save(kids); if (LOCAL) try { window.KGLocal.lvDone(); } catch {} };
const wizWin = w => /^\d\d:\d\d-\d\d:\d\d$/.test(w.bedtime.start + "-" + w.bedtime.end) ? w.bedtime.start + "-" + w.bedtime.end : "21:30-06:30";
// replaces the old one-dialog levelsOffer: called by refresh1() once per kid when the rules are first loaded
function wizAuto(k) {
  if (wiz || k.lv || !edit) return;
  if (!rulesUntouched(edit)) return wizMark(k);   // rules already set: never offer, never overwrite
  wizOpen(k, wizGet(k), false);
}
function wizOpen(k, step, again) {
  if (!k || !edit || wiz) return;
  const w = {}; for (const f of WIZ_F) w[f] = structuredClone(edit[f]);
  wiz = { k, step: Math.min(WIZ_N, Math.max(1, step || 1)), pick: again ? "keep" : "balanced", applied: "keep", w };
  wizDraw(); document.addEventListener("keydown", wizKeys);
}
function wizKeys(e) { if (e.key === "Escape" && wiz && !askDone) wizClose(false); }
function wizClose(done) {
  const z = wiz; if (!z) return; wiz = null;
  document.removeEventListener("keydown", wizKeys);
  const pop = $("#wizPop"); pop.style.display = "none"; pop.innerHTML = "";
  wizMark(z.k);
  if (!done) return wizPut(z.k, z.step);
  wizPut(z.k, 0);
  if (z.k !== cur || !edit) return toast("warn|" + t("wz.lost"));
  Object.assign(edit, z.w); changed(); render();
}
function wizLevel() {   // put the picked level on the copy once per pick; "keep" leaves everything as it is
  const z = wiz, lv = LV_D.find(x => x.id === z.pick);
  if (!lv || z.applied === z.pick) return;
  Object.assign(z.w, tplFields(lv.rules, LV_F)); z.w.bedByDay = []; z.applied = z.pick;
}
function wizApps() {   // the apps worth a decision: busiest today first, then by name; no system / protected ones
  return (st.apps || []).filter(a => !a.protected && !a.sys && !a.system).sort((a, b) => (+b.ms || 0) - (+a.ms || 0) || String(a.label).localeCompare(String(b.label))).slice(0, 8);
}
const wizMode = (w, p) => w.allow.includes(p) ? "allow" : w.blocked.includes(p) ? "block" : "normal";
function wizBody(z) {
  const w = z.w;
  if (z.step === 1) {
    const row = id => { const d = LV_D.find(x => x.id === id);
      return `<label class="app"><input type="radio" name="wz-lv" value="${id}" ${id === z.pick ? "checked" : ""}><span class="nm"><b>${esc(t("lv." + id))}</b><span class="mute">${lvLines(d.rules).map(esc).join("<br>")}</span></span></label>`; };
    return `<p class="mute">${t("wz.s1p")}</p><div role="radiogroup" aria-label="${esc(t("wz.s1"))}">${LV_D.map(d => row(d.id)).join("")}<label class="app"><input type="radio" name="wz-lv" value="keep" ${z.pick === "keep" ? "checked" : ""}><span class="nm"><b>${esc(t("wz.keep"))}</b></span></label></div>`;
  }
  if (z.step === 2) {
    const list = wizApps();
    if (!list.length) return `<p class="mute">${t("wz.noApps")}</p>`;
    return `<p class="mute">${t("wz.s2p")}</p>${list.map(a => { const m = wizMode(w, a.pkg);
      return `<div class="wza" data-p="${esc(a.pkg)}"><div class="app">${icon(a)}<div class="nm"><b>${esc(a.label)}</b></div></div>
        <div class="chips wzs" role="group" aria-label="${esc(a.label)}">${["allow", "normal", "block"].map(v => `<button type="button" data-m="${v}" class="${m === v ? "on" : ""}" aria-pressed="${m === v}">${t("wz.m." + v)}</button>`).join("")}</div></div>`; }).join("")}
      <p class="mute">${ic("info")} ${t("wz.s2h")}</p>`;
  }
  if (z.step === 3) {
    const by = w.bedByDay.length === 7, wdl = t("wdl"), L = [0, 30, 60, 90, 120, 150, 180, 240, 300];
    const rows = by ? w.bedByDay.map((v, i) => { const [a, b] = v.split("-"), on = !!v;
      return `<div class="row bn" style="margin-top:6px"><label class="row bnl"><input type="checkbox" data-wn="${i}" ${on ? "checked" : ""} aria-label="${esc(t("bed.nightOn", wdl[i]))}"> ${esc(wdl[i])}</label>
        <span class="row tm">${on ? `<input type="time" data-ws="${i}" aria-label="${esc(t("start") + " " + wdl[i])}" value="${esc(a)}"> – <input type="time" data-we="${i}" aria-label="${esc(t("end") + " " + wdl[i])}" value="${esc(b)}">` : `<span class="mute">${t("bed.noNight")}</span>`}</span></div>`; }).join("") : "";
    const dBy = w.dailyByDay.length === 7;
    return `<p class="mute">${t("wz.s3p")}</p>
      <label class="row"><input type="checkbox" id="wz-bed" ${w.bedtime.on ? "checked" : ""}> ${ic("moon")} ${t("wz.bedOn")}</label>
      ${w.bedtime.on ? `<div class="row" style="margin-top:8px"><select id="wz-bm" aria-label="${esc(t("bed.h"))}"><option value="same" ${by ? "" : "selected"}>${t("bed.same")}</option><option value="by" ${by ? "selected" : ""}>${t("bed.by")}</option></select>
        ${by ? "" : `<span class="sp"></span><span class="row tm">${t("from")} <input type="time" id="wz-bs" aria-label="${esc(t("start"))}" value="${esc(w.bedtime.start)}"> ${t("to")} <input type="time" id="wz-be" aria-label="${esc(t("end"))}" value="${esc(w.bedtime.end)}"></span>`}</div>
        ${rows}<div>${by ? "" : timeWarn(w.bedtime, true)}</div>` : ""}
      <label for="wz-d" style="display:block;margin-top:14px">${ic("timer")} <b>${t("daily.h")}</b></label>
      <div class="row" style="margin-top:6px"><select id="wz-d" aria-label="${esc(t("daily.h"))}">${dBy ? `<option value="by" selected>${t("wz.perDay")}</option>` : ""}${L.map(n => `<option value="${n}" ${!dBy && w.dailyMin === n ? "selected" : ""}>${n ? fmt(n * 60000) : t("noLimitC")}</option>`).join("")}${dBy || L.includes(w.dailyMin) ? "" : `<option selected value="${w.dailyMin}">${fmt(w.dailyMin * 60000)}</option>`}</select></div>`;
  }
  const it = [["ban", "wz.i1"], ["siren", "wz.i2"], ["hourglass", "wz.i3"]];
  return `<p class="mute">${t("wz.s4p")}</p>${it.map(([i, k]) => `<div class="wzi"><span class="cli">${ic(i)}</span><span>${t(k)}</span></div>`).join("")}<p class="mute">${ic("lightbulb")} ${t("wz.s4h")}</p>`;
}
function wizDraw() {
  const z = wiz; if (!z) return;
  const pop = $("#wizPop"), old = pop.querySelector(".dp"), sc = old ? old.scrollTop : 0, last = z.step === WIZ_N;
  const dots = Array.from({ length: WIZ_N }, (_, i) => `<span class="${i + 1 === z.step ? "on" : i + 1 < z.step ? "dn" : ""}"></span>`).join("");
  pop.innerHTML = `<div class="dp wz" role="dialog" aria-modal="true" aria-labelledby="wizT">
    <div class="row"><span class="mute sp">${t("wz.step", z.step, WIZ_N)}</span><button type="button" id="wizX" class="sm">${ic("x")} ${t("wz.skip")}</button></div>
    <div class="wzd" aria-hidden="true">${dots}</div>
    <h2 id="wizT" style="margin:0 0 8px">${t("wz.s" + z.step)}${z.step === 1 ? " · " + esc(z.k.name) : ""}</h2>
    <div id="wizB">${wizBody(z)}</div>
    <div class="row" style="margin-top:14px">${z.step > 1 ? `<button type="button" id="wizBack" class="sp">${ic("arrow-left")} ${t("wz.back")}</button>` : ""}<button type="button" id="wizNext" class="sp pri">${ic(last ? "check" : "arrow-right")} ${t(last ? "wz.done" : "wz.next")}</button></div></div>`;
  pop.style.display = "flex";
  const dp = pop.querySelector(".dp"); dp.scrollTop = sc;
  const w = z.w, go = n => { z.step = n; wizPut(z.k, n); wizDraw(); pop.querySelector(".dp").scrollTop = 0; }, redraw = () => wizDraw();
  $("#wizX").onclick = () => wizClose(false);
  $("#wizNext").onclick = () => { if (last) return wizClose(true); if (z.step === 1) wizLevel(); go(z.step + 1); };
  if ($("#wizBack")) $("#wizBack").onclick = () => { if (z.step === 1) return; go(z.step - 1); };
  if (z.step === 1) pop.querySelectorAll('input[name="wz-lv"]').forEach(x => x.onchange = () => { z.pick = x.value; });
  if (z.step === 2) pop.querySelectorAll(".wza [data-m]").forEach(b => b.onclick = () => {
    const p = b.closest(".wza").dataset.p, m = b.dataset.m;
    w.allow = w.allow.filter(x => x !== p); w.blocked = w.blocked.filter(x => x !== p);
    if (m === "allow") w.allow.push(p); else if (m === "block") w.blocked.push(p);
    redraw();
  });
  if (z.step === 3) {
    const on = (id, f) => { const e = pop.querySelector(id); if (e) e.onchange = f; };
    on("#wz-bed", e => { w.bedtime.on = e.target.checked; redraw(); });
    on("#wz-bm", e => { w.bedByDay = e.target.value === "by" ? Array(7).fill(wizWin(w)) : []; redraw(); });
    on("#wz-bs", e => { w.bedtime.start = e.target.value; redraw(); });
    on("#wz-be", e => { w.bedtime.end = e.target.value; redraw(); });
    on("#wz-d", e => { if (e.target.value === "by") return; w.dailyMin = +e.target.value; w.dailyByDay = []; });
    pop.querySelectorAll("[data-wn]").forEach(x => x.onchange = () => { w.bedByDay[+x.dataset.wn] = x.checked ? wizWin(w) : ""; redraw(); });
    const night = (i, a, b) => { w.bedByDay[i] = a && b ? a + "-" + b : ""; };
    pop.querySelectorAll("[data-ws]").forEach(x => x.onchange = () => { const i = +x.dataset.ws; night(i, x.value, pop.querySelector(`[data-we="${i}"]`).value); });
    pop.querySelectorAll("[data-we]").forEach(x => x.onchange = () => { const i = +x.dataset.we; night(i, pop.querySelector(`[data-ws="${i}"]`).value, x.value); });
  }
  if (!old) { dp.tabIndex = -1; dp.focus({ preventScroll: true }); }
}
document.addEventListener("click", e => { if (e.target.closest && e.target.closest("#wizRedo")) wizOpen(cur, 1, true); });

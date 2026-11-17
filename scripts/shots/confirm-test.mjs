#!/usr/bin/env node
// Regression test for the confirm gate (kid.js confirmGate). INCIDENT 2026-11-17: on a relay kid the Seen toggles did
// nothing after "Turn on", because a state poll re-rendered the tab while the dialog was open and the gate dropped the
// answer for the old (gone) checkbox. Taps #f-wn, re-renders while the dialog is open, says yes, expects the rule
// saved. Needs Chrome (CHROME=path to override). Usage: node scripts/shots/confirm-test.mjs
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CHROME = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 8781, CDP = 9444;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const srv = spawn(process.execPath, [path.join(HERE, "server.mjs"), "--lang", "vi", "--kids", `bin:${PORT}`], { stdio: "ignore" });
const prof = fs.mkdtempSync(path.join(os.tmpdir(), "kgconfirm"));
const ch = spawn(CHROME, ["--headless=new", `--remote-debugging-port=${CDP}`, `--user-data-dir=${prof}`, "--no-first-run", "--disable-gpu", "about:blank"], { stdio: "ignore" });
let fail = "";
try {
  let up = false;
  for (let i = 0; i < 60 && !up; i++) { try { await fetch(`http://127.0.0.1:${CDP}/json/version`); up = true; } catch { await sleep(300); } }
  if (!up) throw new Error("Chrome did not start");
  const tgt = await (await fetch(`http://127.0.0.1:${CDP}/json/new?about:blank`, { method: "PUT" })).json();
  const ws = new WebSocket(tgt.webSocketDebuggerUrl); let id = 0; const wait = new Map();
  await new Promise(r => ws.onopen = r);
  ws.onmessage = m => { const j = JSON.parse(m.data); if (j.id && wait.has(j.id)) { wait.get(j.id)(j); wait.delete(j.id); } };
  const send = (method, params = {}) => new Promise(r => { const i = ++id; wait.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async e => { const r = await send("Runtime.evaluate", { expression: e, awaitPromise: true, returnByValue: true }); return r.result?.result?.value ?? r.result?.exceptionDetails?.exception?.description; };
  await send("Page.enable");
  await send("Emulation.setDeviceMetricsOverride", { width: 360, height: 780, deviceScaleFactor: 2, mobile: true });
  await send("Page.addScriptToEvaluateOnNewDocument", { source: `localStorage.setItem("kg.kids", JSON.stringify([{id:"5b1e0c9a7d3f4e21",token:"${"b".repeat(48)}",name:"Bin",via:"lan",lv:1,target:"http://127.0.0.1:${PORT}"}])); localStorage.setItem("kg.lang","vi");
    window.__puts=[]; const F=window.fetch; window.fetch=(u,o)=>{ if(o&&o.method==="PUT") window.__puts.push(String(u)); return F(u,o); };` });
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  for (let i = 0; i < 60 && !(await ev(`typeof st!=="undefined" && !!st && !!st.apps && typeof edit!=="undefined" && !!edit && !!document.querySelector(".hero")`)); i++) await sleep(300);
  await ev(`goTab("seen")`); await sleep(800);
  const r = JSON.parse(await ev(`(() => { const e = document.querySelector("#f-wn"); e.scrollIntoView({block:"center"}); const b = e.getBoundingClientRect(); return JSON.stringify({x: b.x + b.width/2, y: b.y + b.height/2}); })()`));
  for (const type of ["mousePressed", "mouseReleased"]) await send("Input.dispatchMouseEvent", { type, x: r.x, y: r.y, button: "left", clickCount: 1 });
  await sleep(400);
  if (await ev(`getComputedStyle(document.querySelector("#askPop")).display`) !== "flex") fail = "no confirm dialog after the tap";
  else {
    await ev(`render(); 0`);   // what a state poll does while the dialog is open
    await ev(`document.querySelector("#askOk").click(); 0`); await sleep(1500);
    const out = JSON.parse(await ev(`JSON.stringify({checked: document.querySelector("#f-wn")?.checked, on: !!(edit.watch && edit.watch.notif && edit.watch.notif.on), puts: window.__puts})`));
    if (!out.checked || !out.on || !out.puts.length) fail = "after a re-render + Turn on: " + JSON.stringify(out);
  }
} catch (e) { fail = String(e && e.message || e); }
finally { ch.kill(); srv.kill(); try { fs.rmSync(prof, { recursive: true, force: true }); } catch {} }
if (fail) { console.error("confirm-test FAILED: " + fail); process.exit(1); }
console.log("confirm-test ok: a toggle confirmed after a re-render is saved");

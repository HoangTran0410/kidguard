#!/usr/bin/env node
// Record the live screen (tab-screen.js scr.recToggle + call.js vrec, 2026-11-21): starts viewing the fake kid's screen,
// taps Record, waits, taps again, expects a non-empty .webm handed to fxSave; then the same for a stream ended by Stop
// (the part recorded so far is saved). Needs Chrome (CHROME=path to override). Usage: node --experimental-websocket scripts/shots/rec-test.mjs
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CHROME = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 8782, CDP = 9445;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const srv = spawn(process.execPath, [path.join(HERE, "server.mjs"), "--lang", "vi", "--kids", `bin:${PORT}`], { stdio: "ignore" });
const prof = fs.mkdtempSync(path.join(os.tmpdir(), "kgrec"));
const ch = spawn(CHROME, ["--headless=new", `--remote-debugging-port=${CDP}`, `--user-data-dir=${prof}`, "--no-first-run", "--disable-gpu", "--autoplay-policy=no-user-gesture-required", "about:blank"], { stdio: "ignore" });
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
    window.__saved=[];` });
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  for (let i = 0; i < 60 && !(await ev(`typeof st!=="undefined" && !!st && !!st.apps && !!document.querySelector(".hero")`)); i++) await sleep(300);
  await ev(`window.fxSave = (b, n) => window.__saved.push({ n, size: b.size, type: b.type }); goTab("screen"); scr.on = cur; screenStart(); 0`);
  for (let i = 0; i < 40 && !(await ev(`!!scr.blob && !document.querySelector("#s-rec").disabled`)); i++) await sleep(300);
  if (!(await ev(`!document.querySelector("#s-rec").hidden && !document.querySelector("#s-rec").disabled`))) throw new Error("Record button not ready: " + await ev(`JSON.stringify({blob: !!scr.blob, hidden: document.querySelector("#s-rec").hidden})`));
  await ev(`document.querySelector("#s-rec").click(); 0`); await sleep(2500);
  const on = await ev(`JSON.stringify({rec: !!scr.rec, pressed: document.querySelector("#s-rec").getAttribute("aria-pressed"), label: document.querySelector("#s-rec").textContent.trim()})`);
  if (!JSON.parse(on).rec) throw new Error("not recording after the tap: " + on + " " + await ev(`(async () => { try { const bm = await createImageBitmap(scr.blob); return "bm ok " + bm.width + " MR " + typeof MediaRecorder + " cs " + typeof HTMLCanvasElement.prototype.captureStream + " toast " + (document.querySelector(".toast,#toast")||{}).textContent; } catch (e) { return "ERR " + e; } })()`));
  await ev(`document.querySelector("#s-rec").click(); 0`); await sleep(1200);
  let s = JSON.parse(await ev(`JSON.stringify({saved: window.__saved, rec: !!scr.rec})`));
  if (s.rec || s.saved.length !== 1 || !(s.saved[0].size > 1000) || !/\.webm$|\.mp4$/.test(s.saved[0].n)) throw new Error("button stop: " + JSON.stringify(s));
  await ev(`document.querySelector("#s-rec").click(); 0`); await sleep(1500);
  await ev(`scr.end(); 0`); await sleep(1200);
  s = JSON.parse(await ev(`JSON.stringify({saved: window.__saved, rec: !!scr.rec})`));
  if (s.rec || s.saved.length !== 2 || !(s.saved[1].size > 1000)) throw new Error("Stop viewing while recording: " + JSON.stringify(s));
  // the live camera path (call.js camRec): vrec on a video + audio stream, like the WebRTC one
  const cam = JSON.parse(await ev(`(async () => { const cv = document.createElement("canvas"); cv.width = 320; cv.height = 240; const cx = cv.getContext("2d");
    const ac = new AudioContext(); await ac.resume(); const o = ac.createOscillator(), d = ac.createMediaStreamDestination(); o.connect(d); o.start();
    const ms = new MediaStream([...cv.captureStream(10).getVideoTracks(), ...d.stream.getAudioTracks()]);
    let n = 0; const tk = setInterval(() => { cx.fillStyle = n++ % 2 ? "#f00" : "#00f"; cx.fillRect(0, 0, 320, 240); }, 100);
    const r = vrec.start(ms, vrec.name(cur, "camera")); if (!r) return JSON.stringify({ err: "no recorder" });
    await new Promise(z => setTimeout(z, 2000)); r.stop(); await new Promise(z => setTimeout(z, 800)); clearInterval(tk); o.stop();
    const last = window.__saved[window.__saved.length - 1]; return JSON.stringify(last && /-camera-/.test(last.n) ? last : { err: "not saved", n: window.__saved.length, ac: ac.state, tracks: ms.getTracks().map(t => t.kind + ":" + t.readyState), toast: (document.querySelector("#toast,.toast") || {}).textContent }); })()`));
  if (!cam || !/-camera-.*\.(webm|mp4)$/.test(cam.n) || !(cam.size > 1000) || !/audio|opus|video/.test(cam.type)) throw new Error("camera stream: " + JSON.stringify(cam));
  s.saved.push(cam);
  console.log("saved:", s.saved.map(x => `${x.n} ${x.size} B`).join(", "));
} catch (e) { fail = String(e && e.message || e); }
finally { ch.kill(); srv.kill(); try { fs.rmSync(prof, { recursive: true, force: true }); } catch {} }
if (fail) { console.error("rec-test FAILED: " + fail); process.exit(1); }
console.log("rec-test ok: the live screen records to a file (button stop + Stop viewing)");

// node scripts/e2e-listen.mjs  (CHROME=/path/to/chrome to override). Needs Chrome; no relay: the relay routes are in relay/test.
// E2E: rtc.js listen mode, a "parent" (recvonly) and a "kid" (mic only, fake device) in two pages of one Chrome, SDP passed
// through this server like the relay does. Proves: the kid sends audio, the parent receives it, the kid receives nothing.
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { spawn } from "node:child_process";
const ROOT = new URL("..", import.meta.url).pathname, TMP = (await import("node:os")).tmpdir() + "/kg-e2e-listen";
let offer = null, answer = null, results = {};
const page = (role) => `<!doctype html><meta charset=utf-8><audio id=a autoplay></audio><script src="/rtc.js"></script><script>
const role=${JSON.stringify(role)};const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const post=(p,b)=>fetch(p,{method:"POST",body:JSON.stringify(b)}).then(r=>r.json());
(async()=>{try{
 let c, ice=[];
 if(role==="parent"){
  c=await RTC.open({listen:true,ice,remote:document.getElementById("a"),onState:s=>0});
  await post("/offer",{sdp:c.sdp});
  let a; while(!(a=(await post("/getanswer",{})).sdp)) await sleep(200);
  await c.accept(a);
 } else {
  let o; while(!(o=(await post("/getoffer",{})).sdp)) await sleep(200);
  c=await RTC.open({listen:true,ice,offer:o});
  await post("/answer",{sdp:c.sdp});
 }
 await sleep(9000);
 // stats through a second look at the connection: RTC hides pc, so read via getStats on all senders/receivers using the global hook
 const pcs=window.__pcs||[];let inb=0,outb=0,inKinds=[],level=0,codec="";
 for(const pc of pcs){const rep=await pc.getStats();rep.forEach(r=>{if(r.type==="inbound-rtp"){inb+=r.bytesReceived||0;inKinds.push(r.kind);level=Math.max(level,r.audioLevel||0)}if(r.type==="outbound-rtp")outb+=(r.bytesSent||0)+(r.packetsSent||0)*0;if(r.type==="outbound-rtp")window.__o=JSON.stringify(r);if(r.type==="codec"&&/opus/i.test(r.mimeType))codec=r.sdpFmtpLine||""})}
 const dirs=pcs.flatMap(pc=>pc.getTransceivers().map(t=>t.currentDirection));
 await post("/result",{role,o:window.__o,st:pcs.map(p=>p.connectionState),inb,outb,inKinds,level,codec,dirs,sdpHasVideo:/m=video/.test(c.sdp)});
}catch(e){await post("/result",{role,err:String(e&&e.stack||e)})}})();
</script>`;
const hook = `<script>window.__pcs=[];const O=window.RTCPeerConnection;window.RTCPeerConnection=function(...a){const p=new O(...a);window.__pcs.push(p);return p};window.RTCPeerConnection.prototype=O.prototype;</script>`;
const srv = createServer((q, s) => {
  let b = ""; q.on("data", d => b += d); q.on("end", () => {
    const j = (o) => { s.writeHead(200, { "content-type": "application/json" }); s.end(JSON.stringify(o)); };
    if (q.url === "/rtc.js") { s.writeHead(200, { "content-type": "text/javascript" }); return s.end(readFileSync(ROOT + "/ui/assets/rtc.js")); }
    if (q.url === "/parent" || q.url === "/kid") { s.writeHead(200, { "content-type": "text/html" }); return s.end(hook + page(q.url.slice(1))); }
    if (q.url === "/offer") { offer = JSON.parse(b).sdp; return j({}); }
    if (q.url === "/getoffer") return j({ sdp: offer });
    if (q.url === "/answer") { answer = JSON.parse(b).sdp; return j({}); }
    if (q.url === "/getanswer") return j({ sdp: answer });
    if (q.url === "/result") { const r = JSON.parse(b); results[r.role] = r; console.log(JSON.stringify(r)); return j({}); }
    s.writeHead(404); s.end();
  });
}).listen(8899, "127.0.0.1");
const flags = ["--headless=new", "--no-sandbox", "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--autoplay-policy=no-user-gesture-required", "--allow-loopback-in-peer-connection", "--disable-features=WebRtcHideLocalIpsWithMdns"];
const CH = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const procs = ["parent", "kid"].map((r, i) => spawn(CH, [...flags, "--user-data-dir=" + TMP + i, `http://localhost:8899/${r}`], { stdio: "ignore" }));
const t0 = Date.now();
while (Object.keys(results).length < 2 && Date.now() - t0 < 40000) await new Promise(r => setTimeout(r, 300));
procs.forEach(p => p.kill()); srv.close();
const P = results.parent, K = results.kid;
const ok = P && K && !P.err && !K.err && P.inb > 1000 && P.inKinds.includes("audio") && !P.sdpHasVideo && K.inb === 0 && K.outb > 1000 && /usedtx=1/.test(P.codec + K.codec);
console.log(ok ? "E2E PASS: parent received audio, kid received nothing, no video" : "E2E FAIL");
process.exit(ok ? 0 : 1);

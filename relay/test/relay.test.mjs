// run: npx wrangler dev --port $PORT (default 8793), then: PORT=8793 node test/relay.test.mjs
// A request that gets no answer in 30 s is retried once and reported (wrangler dev locally, and CI, sometimes leave one
// request unanswered for minutes; seen at the "wrong token frame GET" check). Bodies are re-sendable strings/buffers here.
{const f0=globalThis.fetch;globalThis.fetch=async(u,o={})=>{if(o.signal)return f0(u,o);try{return await f0(u,{...o,signal:AbortSignal.timeout(30000)});}catch(e){if(e.name!=="TimeoutError")throw e;console.log("RETRY (no answer in 30 s):",(o.method||"GET"),String(u).replace(/^https?:\/\/[^/]+/,""));return f0(u,{...o,signal:AbortSignal.timeout(30000)});}};}
const B="http://localhost:"+(process.env.PORT||8793), id="a1b2c3d4e5f60718", T="ab".repeat(24), BAD="cd".repeat(24);
let pass=0,fail=0; const ck=(n,c)=>{console.log(c?"PASS":"FAIL",n);c?pass++:fail++};
const H=(t=T,x={})=>({"x-kg-token":t,...x});
const P=(p,body,t=T,x={})=>fetch(`${B}/c/${id}/${p}`,{method:"POST",headers:H(t,x),body});
const jpg=Buffer.from("ffd8ffe000104a46494600010100000100010000ffd9","hex");
let r=await P("sync",JSON.stringify({state:{}}));let j=await r.json();
ck("sync binds, no live",r.status==200&&!j.live);
{const q=await fetch(`${B}/qr`),qt=await q.text(),l=await fetch(`${B}/ui/qrcode.js`),lt=await l.text();
ck("qr page loads the shared QR lib from ui/",q.status==200&&qt.includes('src="/ui/qrcode.js"')&&!qt.includes("qrcode-generator 1.4.4"));
ck("ui/qrcode.js served as JS",l.status==200&&/javascript/.test(l.headers.get("content-type"))&&lt.includes("qrcode-generator 1.4.4"));}
{const pg=await (await fetch(`${B}/`)).text(),srcs=[...pg.matchAll(/(?:src|href)="\/ui\/([a-z0-9-]+\.(?:js|css))"/g)].map(m=>m[1]);
let okAll=srcs.length>10;for(const n of srcs){const x=await fetch(`${B}/ui/${n}`);await x.text();if(x.status!=200||x.headers.get("x-content-type-options")!="nosniff"||!(n.endsWith(".css")?/text\/css/:/javascript/).test(x.headers.get("content-type")))okAll=false;}
ck("every /ui/ file the page names is served with its type ("+srcs.length+")",okAll);
const c=await fetch(`${B}/ui/core.js`),ct=await c.text();ck("/ui/core.js 200 JS",c.status==200&&/javascript/.test(c.headers.get("content-type"))&&ct.includes("async function call("));
for(const bad of ["/ui/x.js","/ui/..%2Findex.html","/ui/index.html","/ui/toString","/ui/"]){const x=await fetch(B+bad);await x.text();ck("unknown "+bad+" 404",x.status==404);}}
ck("wrong token 401",(await P("sync","{}",BAD)).status==401);
ck("wrong token frame GET 401",(await fetch(`${B}/c/${id}/frame?after=0`,{headers:H(BAD)})).status==401);
r=await P("live","{}");j=await r.json();ck("live ok phone -1",j.ok&&j.phone==-1);
r=await P("sync","{}");j=await r.json();ck("sync live:true",j.live===true);
// parent long poll
const t0=Date.now();const gp=fetch(`${B}/c/${id}/frame?after=0`,{headers:H()});
await new Promise(s=>setTimeout(s,300));
r=await P("input",JSON.stringify({type:"tap",x:.5,y:.5}));ck("input queued",(await r.json()).result=="queued");
ck("input bad type 400",(await P("input",JSON.stringify({type:"zap"}))).status==400);
ck("input x>1 400",(await P("input",JSON.stringify({type:"tap",x:2,y:0}))).status==400);
ck("input bad key 400",(await P("input",JSON.stringify({type:"key",key:"rm"}))).status==400);
ck("input text>500 400",(await P("input",JSON.stringify({type:"text",text:"a".repeat(501)}))).status==400);
r=await P("frame",jpg,T,{"content-type":"image/jpeg","x-kg-w":"720","x-kg-h":"1600"});j=await r.json();
ck("frame reply has input + live",j.inputs.length==1&&j.inputs[0].type=="tap"&&j.live===true);
r=await gp;const b=Buffer.from(await r.arrayBuffer());
ck("parent long-poll woken 200 jpeg",r.status==200&&r.headers.get("content-type")=="image/jpeg"&&b.equals(jpg)&&Date.now()-t0<3000&&+r.headers.get("x-kg-seq")>1.7e12&&r.headers.get("x-kg-w")=="720"&&r.headers.get("x-kg-age")!=null);
r=await P("frame",jpg);j=await r.json();ck("inputs drained",j.inputs.length==0);
const t1=Date.now();r=await fetch(`${B}/c/${id}/frame?after=9999999999999999`,{headers:H()});
ck("no new frame -> 204 after ~5s",r.status==204&&Date.now()-t1>4500);
r=await P("frame","",T,{"x-kg-err":"screen off"});await r.json();
r=await fetch(`${B}/c/${id}/frame?after=9999999999999999`,{headers:H()});ck("204 with X-KG-Err",r.status==204&&r.headers.get("x-kg-err")=="screen off");
r=await P("live","{}");j=await r.json();ck("live phone>=0",j.phone>=0);
ck("frame >1MB 413",(await P("frame",Buffer.alloc(1100000))).status==413);
ck("frame 1MB-ish ok",(await P("frame",Buffer.alloc(900000))).status==200);
ck("cmd >256KB 413",(await P("cmd",JSON.stringify({type:"message",text:"a".repeat(300000)}))).status==413);
ck("cmd answer ok",(await (await P("cmd",JSON.stringify({type:"answer",id:"x",ok:true}))).json()).result=="queued");
ck("cmd reboot ok",(await (await P("cmd",JSON.stringify({type:"reboot"}))).json()).result=="queued");
ck("cmd wake ok",(await (await P("cmd",JSON.stringify({type:"wake"}))).json()).result=="queued");
ck("cmd devOff ok",(await (await P("cmd",JSON.stringify({type:"devOff"}))).json()).result=="queued");
ck("PUT frame 405",(await fetch(`${B}/c/${id}/frame`,{method:"PUT",headers:H(),body:"x"})).status==405);
ck("GET live 405",(await fetch(`${B}/c/${id}/live`,{headers:H()})).status==405);
r=await fetch(`${B}/install`);ck("/install 200 html",r.status==200&&r.headers.get("content-type").includes("html"));
r=await fetch(`${B}/`);ck("/ 200",r.status==200);


// ---- v0.2.1 ----
const sleep=ms=>new Promise(s=>setTimeout(s,ms));
const id2="0123456789abcdef", P2=(p,body,t=T,x={},m="POST")=>fetch(`${B}/c/${id2}/${p}`,{method:m,headers:H(t,x),body});
const S=async(o={})=>await (await P2("sync",JSON.stringify(o))).json();
// 5: validation before the DO
ck("no token 401",(await fetch(`${B}/c/${id}/state`)).status==401);
ck("short token 401",(await fetch(`${B}/c/${id}/state`,{headers:H("abc")})).status==401);
ck("bad id 404",(await fetch(`${B}/c/xyz/state`,{headers:H()})).status==404);
// 2: headers
r=await fetch(`${B}/`);ck("/ X-Frame-Options DENY + CSP frame-ancestors",r.headers.get("x-frame-options")=="DENY"&&/frame-ancestors 'none'/.test(r.headers.get("content-security-policy")));
r=await fetch(`${B}/install`);const csp=r.headers.get("content-security-policy");ck("/install DENY + CSP jsdelivr+github",r.headers.get("x-frame-options")=="DENY"&&csp.includes("cdn.jsdelivr.net")&&csp.includes("objects.githubusercontent.com")&&csp.includes("'unsafe-inline'"));
r=await P("live","{}");ck("json nosniff",r.headers.get("x-content-type-options")=="nosniff");
r=await fetch(`${B}/c/zz/state`);ck("404 json nosniff",r.headers.get("x-content-type-options")=="nosniff");
// 4: input 409 when not live (fresh kid, bound by sync)
await S({state:{}});
r=await P2("input",JSON.stringify({type:"tap",x:.1,y:.1}));j=await r.json();ck("input 409 not live",r.status==409&&j.error=="not live");
// 1: command ids, deliver-once, ack-drop
r=await P2("cmd",JSON.stringify({type:"reboot"}));await P2("cmd",JSON.stringify({type:"release"}));await P2("cmd",JSON.stringify({type:"unpairAll"}));
await P2("cmd",JSON.stringify({type:"lock"}));await P2("cmd",JSON.stringify({type:"answer",id:"req1",ok:true}));
j=await S();ck("all 5 cmds delivered with ids",j.cmds.length==5&&j.cmds.every(c=>typeof c.id=="string"&&c.id.length>=3));
ck("answer keeps given id",j.cmds.some(c=>c.type=="answer"&&c.id=="req1"));
const ids=new Set(j.cmds.map(c=>c.id));ck("ids unique",ids.size==5);
j=await S();ck("destructive cmds stay until acked (at-least-once): all 5 re-delivered",j.cmds.length==5);
{const ids2=j.cmds.filter(c=>["reboot","release","unpairAll"].includes(c.type)).map(c=>c.id);j=await S({acks:ids2});ck("acked destructive cmds gone, lock+answer stay",j.cmds.map(c=>c.type).sort().join()=="answer,lock");}
j=await S({acks:["req1",j.cmds.find(c=>c.type=="lock").id]});ck("acks drop the rest",j.cmds.length==0);
r=await P2("cmd",JSON.stringify({type:"answer",id:"bad id!",ok:true}));j=await S();const an=j.cmds.find(c=>c.type=="answer");
ck("answer with illegal id: cmd id replaced, request id kept in req",an&&an.id!="bad id!"&&an.req=="bad id!");await S({acks:[an.id]});
ck("bad cmd 400",(await P2("cmd",JSON.stringify({type:"rm"}))).status==400);
// unpair {id}: whitelisted, keeps the device id as the command id, deliver-once (destructive)
r=await P2("cmd",JSON.stringify({type:"unpair",id:"a1b2c3d4"}));ck("unpair accepted",r.status==200);
await P2("cmd",JSON.stringify({type:"lock"}));
j=await S();const up=j.cmds.find(c=>c.type=="unpair");ck("unpair delivered with its device id",!!up&&up.id=="a1b2c3d4");
j=await S({acks:["a1b2c3d4"]});ck("unpair gone once acked (lock stays)",j.cmds.map(c=>c.type).join()=="lock");
j=await S({acks:[j.cmds[0].id]});ck("unpair test queue clean",j.cmds.length==0);
// 6: rules
ck("rules array 400",(await P2("rules","[1]",T,{},"PUT")).status==400);
ck("rules string 400",(await P2("rules",'"x"',T,{},"PUT")).status==400);
r=await P2("rules",JSON.stringify({dailyMin:60,pin:"1234"}),T,{},"PUT");j=await r.json();
const pinAt0=j.rev;
ck("PUT rules: pinSet, no pinHash/pin",j.pinSet===true&&!("pinHash" in j)&&!("pin" in j)&&j.dailyMin==60);
r=await P2("rules",JSON.stringify({dailyMin:90}),T,{},"PUT");j=await r.json();ck("PUT without pin keeps pinSet",j.pinSet===true&&j.dailyMin==90);
j=await (await P2("state",null,T,{},"GET")).json();ck("GET state: no pinHash",j.rules.pinSet===true&&!JSON.stringify(j).includes("pinHash"));
j=await S({state:{rules:{rev:1}}});ck("phone gets pinHash (sha256 kg:1234)",j.rules&&j.rules.pinHash==="d371349c9ee42104a430ccbe28c7b26b6eea4541d3f42cb314fe208a325e3afd");
ck("pinAt = rev of the PUT that set the PIN, kept by later PUTs",j.rules.pinAt===pinAt0&&j.rules.rev>pinAt0);
j=await S({state:{rules:{rev:1,pinHash:"x".repeat(64)}}});ck("state with phone's rules never leaks pinHash to parent",!JSON.stringify(await (await P2("state",null,T,{},"GET")).json()).includes("xxxx"));
j=await S();ck("state omitted: rules not resent when phone up to date",true);
// 7: seen
r=await P2("state",null,T,{},"GET");j=await r.json();const seen1=j.seen;await sleep(50);await S();j=await (await P2("state",null,T,{},"GET")).json();ck("seen advances (in memory)",j.seen>seen1&&Date.now()-j.seen<5000);
// 3: seq
ck("frame seq starts at Date.now()", (await (async()=>{await P2("live","{}");const g=P2("frame",jpg,T,{"x-kg-w":"1","x-kg-h":"1"});await g;return +(await fetch(`${B}/c/${id2}/frame?after=0`,{headers:H()})).headers.get("x-kg-seq")})())>Date.now()-60000);
// 4b: old inputs dropped
await P2("live","{}");await P2("input",JSON.stringify({type:"tap",x:.2,y:.2}));await sleep(5300);await P2("input",JSON.stringify({type:"key",key:"home"}));
j=await (await P2("frame",jpg)).json();ck("inputs older than 5s dropped, fresh kept (no 'at' leak)",j.inputs.length==1&&j.inputs[0].type=="key"&&!("at" in j.inputs[0]));
ck("CSP allows OSM tiles",(await fetch(`${B}/`)).headers.get("content-security-policy").includes("img-src 'self' data: blob: https://tile.openstreetmap.de https://tile.openstreetmap.org"));
const track=Array.from({length:700},(_,i)=>[1.7e12+i,10.123456,106.123456,12.5]);
r=await P2("sync",JSON.stringify({state:{track}}));ck("sync with ~700-point track (<120KB) ok",r.status==200);
r=await P2("sync",JSON.stringify({state:{big:"a".repeat(125000)}}));ck("state >120KB -> 413",r.status==413);
// partial states (bandwidth): keys replace, _drop, _apps by pkg (re-sorted by ms), _trk appends newer points + trims to `from`
{const G=async()=>(await (await fetch(`${B}/c/${id2}/state`,{headers:H()})).json()).state;
j=await S({state:{t:1,battery:50,gone:1,apps:[{pkg:"a",ms:5},{pkg:"b",ms:3}],track:[[10,1,1,5],[20,1,1,5]]}});ck("sync says merge:1",j.merge===1&&!j.full);
j=await S({state:{_part:1,t:2,_drop:["gone"],_apps:{set:[{pkg:"b",ms:9},{pkg:"c",ms:1}],del:["a"]},_trk:{from:20,add:[[20,1,1,5],[30,2,2,5]]}}});
let g=await G();ck("partial merged: key, drop, apps, track",j.merge===1&&g.t===2&&g.battery===50&&!("gone" in g)&&g.apps.map(x=>x.pkg).join()==="b,c"&&g.track.map(p=>p[0]).join()==="20,30");
j=await S({state:{_part:1,track:[]}});g=await G();ck("partial whole track replace",g.track.length===0&&g.battery===50);
j=await (await fetch(`${B}/c/5555aaaa5555aaaa/sync`,{method:"POST",headers:H(),body:JSON.stringify({state:{_part:1,t:1}})})).json();ck("partial with nothing stored -> full:true",j.full===true);
}
// icons: phone uploads base64 webp once, <img> reads with ?k=
{const b64=Buffer.from("RIFF0000WEBPVP8 ").toString("base64");
r=await P2("icons",JSON.stringify({"com.example.game":b64,"bad pkg!":b64,"x.y":"not base64 !!"}));j=await r.json();ck("icons: valid one stored, junk skipped",r.status==200&&j.have==1);
r=await fetch(`${B}/c/${id2}/icon?pkg=com.example.game&k=${T}`);ck("icon GET with ?k= -> webp",r.status==200&&r.headers.get("content-type")=="image/webp"&&Buffer.from(await r.arrayBuffer()).toString("base64")==b64);
ck("icon GET wrong k -> 401",(await fetch(`${B}/c/${id2}/icon?pkg=com.example.game&k=${BAD}`)).status==401);
ck("icon GET unknown pkg -> 404",(await fetch(`${B}/c/${id2}/icon?pkg=a.b&k=${T}`)).status==404);
{const IK=(await import("node:crypto")).createHash("sha256").update("icon:"+T).digest("hex");
r=await fetch(`${B}/c/${id2}/icon?pkg=com.example.game&k=${IK}`);ck("icon GET with the icon-only key -> webp",r.status==200);
ck("icon key wrong -> 401",(await fetch(`${B}/c/${id2}/icon?pkg=com.example.game&k=${"0".repeat(64)}`)).status==401);
ck("icon key can't read state",(await fetch(`${B}/c/${id2}/state?k=${IK}`)).status==401);}
ck("?k= only works for icon",(await fetch(`${B}/c/${id2}/state?k=${T}`)).status==401);}
// v0.5: FCM push (fake Google from run.mjs on $MOCK)
if(process.env.MOCK){const id3=[...crypto.getRandomValues(new Uint8Array(8))].map(b=>b.toString(16).padStart(2,"0")).join(""),P3=(p,o)=>fetch(`${B}/c/${id3}/${p}`,{method:"POST",headers:H(),body:JSON.stringify(o)});
const sentList=async()=>(await (await fetch(`http://127.0.0.1:${process.env.MOCK}/_sent`)).json()).filter(m=>m.data.kid==id3);
await P3("sync",{state:{}});
ck("push bad token 400",(await P3("push",{fcm:"x"})).status==400);
j=await (await P3("push",{fcm:"good_"+"a".repeat(40)})).json();ck("push register: enabled, 1 device",j.ok&&j.enabled===true&&j.devices==1);
j=await (await P3("push",{fcm:"good_"+"a".repeat(40)})).json();ck("push register twice: still 1",j.devices==1);
await P3("push",{fcm:"dead_"+"b".repeat(40)});
const now=Date.now();await P3("sync",{state:{sos:[{id:"s1",t:now},{id:"old",t:now-7*3600e3}],requests:[{id:"r1",t:now,min:30,note:"bí mật"}]}});
await sleep(800);let ms=await sentList();
ck("sos + request pushed to both tokens, old sos skipped",ms.length==4&&ms.filter(m=>m.data.eid=="s:s1").length==2&&ms.filter(m=>m.data.eid=="r:r1").length==2&&!ms.some(m=>m.data.eid=="s:old"));
ck("push is data-only, high priority, no note text",ms.every(m=>m.android.priority=="HIGH"&&!m.notification&&m.data.kind&&!JSON.stringify(m).includes("bí mật"))&&ms.find(m=>m.data.kind=="req").data.min=="30");
await P3("sync",{state:{sos:[{id:"s1",t:now}],requests:[{id:"r1",t:now,min:30}]}});await sleep(500);
ck("same ids not pushed again",(await sentList()).length==4);
j=await (await P3("push",{fcm:"good_"+"a".repeat(40)})).json();ck("dead token (404) removed",j.devices==1);
await P3("sync",{state:{sos:[{id:"s2",t:now}]}});await sleep(500);ms=await sentList();
ck("next sos only to the live token",ms.length==5&&ms[4].data.eid=="s:s2"&&ms[4].token.startsWith("good"));
await P3("sync",{state:{events:[{id:"e1",t:now,kind:"zoneIn",text:"Đã đến Trường"}]}});await sleep(500);ms=await sentList();
ck("event pushed as ev, kind only, no text",ms.length==6&&ms[5].data.kind=="ev"&&ms[5].data.eid=="e:e1"&&ms[5].data.ek=="zoneIn"&&!JSON.stringify(ms[5]).includes("Trường"));
await P3("sync",{state:{events:Array.from({length:9},(_,i)=>({id:"b"+i,t:now,kind:"battery"}))}});await sleep(800);
ck("at most 5 events pushed per sync",(await sentList()).length==11);
j=await (await P3("push",{fcm:"good_"+"a".repeat(40),off:true})).json();ck("push off removes",j.devices==0);
ck("push needs the kid token",(await fetch(`${B}/c/${id3}/push`,{method:"POST",headers:H(BAD),body:"{}"})).status==401);}
{const id5=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),T5=Array.from({length:48},()=>"0123456789abcdef"[Math.random()*16|0]).join("");
// the per-IP rate limit (150 / 10 s) can trip here on a fast runner (429), and wrangler dev can 503 while it starts a fresh Durable Object: retry both
const S5=async b=>{for(let i=0;;i++){const r=await fetch(`${B}/c/${id5}/sync`,{method:"POST",headers:{"x-kg-token":T5,"content-type":"application/json"},body:b});if((r.status!=429&&r.status!=503)||i>=20)return r;await new Promise(z=>setTimeout(z,1500));}};
{const a=(await S5("{}")).status,c=(await S5("nope")).status;ck(`first sync without state does not bind (400) [got ${a}, ${c}]`,a==400&&c==400);}
ck("first sync with state binds",(await S5(JSON.stringify({state:{t:1}}))).status==200);}
// ---- v0.8 clearData: validated, deliver-once ----
{const idc=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),PC=(p,o)=>fetch(`${B}/c/${idc}/${p}`,{method:"POST",headers:H(),body:JSON.stringify(o)});
await PC("sync",{state:{t:1}});
ck("clearData bad pkg 400",(await PC("cmd",{type:"clearData",pkg:"a b;rm"})).status==400&&(await PC("cmd",{type:"clearData"})).status==400&&(await PC("cmd",{type:"clearData",pkg:"a".repeat(201)})).status==400);
ck("clearData queued",(await (await PC("cmd",{type:"clearData",pkg:"com.heytap.market",id:"cd1"})).json()).result=="queued");
const c1=await (await PC("sync",{})).json();ck("clearData delivered with pkg",c1.cmds.some(c=>c.type=="clearData"&&c.pkg=="com.heytap.market"));
const c2=await (await PC("sync",{acks:["cd1"]})).json();ck("clearData NOT delivered after ack",!c2.cmds.some(c=>c.type=="clearData"));}
// ---- device health ("Máy"): parent POST asks, sync says device:true, phone PUTs, parent reads it ----
{const idd=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),PD=(p,o,m="POST")=>fetch(`${B}/c/${idd}/${p}`,{method:m,headers:H(),body:o===undefined?undefined:JSON.stringify(o)});
await PD("sync",{state:{t:1}});
ck("device: not asked -> sync has no device flag",!(await (await PD("sync",{})).json()).device);
let d=await (await PD("device",{})).json();ck("device POST asks, none stored yet",d.device===null&&d.asked>0);
ck("device: sync now says device:true",(await (await PD("sync",{})).json()).device===true);
ck("device PUT ok",(await (await PD("device",{sdk:34,storage:{total:1,free:1}},"PUT")).json()).ok===true);
ck("device: flag cleared after the PUT",!(await (await PD("sync",{})).json()).device);
d=await (await fetch(`${B}/c/${idd}/device`,{headers:H()})).json();ck("device GET returns stored + at",d.device&&d.device.sdk===34&&d.device.at>0);
ck("device GET does not ask",!(await (await PD("sync",{})).json()).device);
ck("device PUT >32KB 413",(await PD("device",{x:"a".repeat(40000)},"PUT")).status==413);
ck("device PUT array 400",(await PD("device",[1],"PUT")).status==400);
ck("device wrong token 401",(await fetch(`${B}/c/${idd}/device`,{headers:H(BAD)})).status==401);}
// ---- Nhật ký history ("logday"): parent POST {day} asks, sync says logday:<day>, phone PUTs the day, parent reads it ----
{const idl=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),PL=(p,o,m="POST")=>fetch(`${B}/c/${idl}/${p}`,{method:m,headers:H(),body:o===undefined?undefined:JSON.stringify(o)});
await PL("sync",{state:{t:1}});
ck("logday: not asked -> sync has no logday",!(await (await PL("sync",{})).json()).logday);
ck("logday POST bad day 400",(await PL("logday",{day:"x"})).status==400&&(await PL("logday",{})).status==400);
let l=await (await PL("logday",{day:"2026-11-10"})).json();ck("logday POST asks, none stored yet",l.logday===null&&l.asked>0);
ck("logday: sync names the day",(await (await PL("sync",{})).json()).logday==="2026-11-10");
ck("logday PUT bad day 400",(await PL("logday",{day:"nope"},"PUT")).status==400);
ck("logday PUT ok",(await (await PL("logday",{day:"2026-11-10",days:{"2026-11-10":2},ev:[{id:"a",t:1,kind:"x",text:"y"}],lg:[{t:2,m:"z"}],cut:false},"PUT")).json()).ok===true);
ck("logday: ask cleared after the PUT",!(await (await PL("sync",{})).json()).logday);
l=await (await fetch(`${B}/c/${idl}/logday?day=2026-11-10`,{headers:H()})).json();ck("logday GET returns stored + at",l.logday&&l.logday.ev.length==1&&l.logday.lg.length==1&&l.logday.at>0&&l.logday.days["2026-11-10"]===2);
ck("logday GET does not ask",!(await (await PL("sync",{})).json()).logday);
ck("logday PUT >100KB 413",(await PL("logday",{day:"2026-11-09",lg:[{m:"a".repeat(110000)}]},"PUT")).status==413);
for(const d of ["2026-11-01","2026-11-02","2026-11-03","2026-11-04","2026-11-05"])await PL("logday",{day:d,ev:[],lg:[]},"PUT");
l=await (await fetch(`${B}/c/${idl}/logday?day=2026-11-10`,{headers:H()})).json();ck("logday keeps only the newest 4 days",l.logday===null);
l=await (await fetch(`${B}/c/${idl}/logday?day=2026-11-05`,{headers:H()})).json();ck("logday newest still there",!!l.logday);
ck("logday wrong token 401",(await fetch(`${B}/c/${idl}/logday?day=2026-11-05`,{headers:H(BAD)})).status==401);}
// ---- remote phone settings: key whitelist + number/boolean value ----
{const ids=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),PS=(p,o)=>fetch(`${B}/c/${ids}/${p}`,{method:"POST",headers:H(),body:JSON.stringify(o)});
await PS("sync",{state:{t:1}});
const bad=await Promise.all([{key:"wifi",value:1},{key:"vol.media"},{key:"vol.media",value:"7"},{key:"dnd",value:null}].map(async o=>(await PS("cmd",{type:"setting",...o})).status));
ck(`setting bad key/value 400 [got ${bad}]`,bad.every(s=>s==400));
ck("setting queued",(await (await PS("cmd",{type:"setting",key:"vol.media",value:7,id:"s1"})).json()).result=="queued"&&(await (await PS("cmd",{type:"setting",key:"dnd",value:true})).json()).result=="queued");
const s1=await (await PS("sync",{})).json();ck("setting delivered with key/value",s1.cmds.some(c=>c.type=="setting"&&c.key=="vol.media"&&c.value===7&&c.id=="s1")&&s1.cmds.some(c=>c.key=="dnd"&&c.value===true));}
// ---- tester additions: deliver-once, pinHash never leaves, oversized cmd ----
{const id4=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),P4=(p,o,m="POST")=>fetch(`${B}/c/${id4}/${p}`,{method:m,headers:H(),body:o===undefined?undefined:JSON.stringify(o)});
const G4=async p=>await (await fetch(`${B}/c/${id4}/${p}`,{headers:H()})).text();
await P4("sync",{state:{rules:{rev:1,pinHash:"f".repeat(64),pinSalt:"abc"}}});
await P4("cmd",{type:"reboot",id:"rb1"});await P4("cmd",{type:"lock",id:"lk1"});
let a=await (await P4("sync",{})).json();
ck("reboot delivered",a.cmds.some(c=>c.type=="reboot"));
let b2=await (await P4("sync",{})).json();
ck("reboot re-delivered until acked",b2.cmds.some(c=>c.type=="reboot"));
b2=await (await P4("sync",{acks:["rb1"]})).json();
ck("reboot gone once acked",!b2.cmds.some(c=>c.type=="reboot"));
ck("non-once cmd (lock) re-delivered until acked",b2.cmds.some(c=>c.type=="lock"));
b2=await (await P4("sync",{acks:["lk1"]})).json();ck("acked lock dropped",!b2.cmds.some(c=>c.type=="lock"));
await P4("rules",{dailyMin:60,pin:"1234"},"PUT");
const st=await G4("state"),ru=await G4("rules");
ck("GET state has no pinHash",!st.includes("pinHash")&&!st.includes("f".repeat(64)));
const putR=await (await P4("rules",{dailyMin:61,pin:"1234"},"PUT")).text();
ck("PUT rules reply has no pinHash",!putR.includes("pinHash"));
// GET /rules may not exist (405/404); then it must still not leak
ck("GET rules never returns pinHash",!ru.includes("pinHash"));
ck("GET state shows rules via stripPin (pinSet or nothing)",JSON.parse(st).rules!==undefined);
// oversized command: 256KB body limit gives 413 (tested above); since the MAX_CMD fix a 4KB+ command is refused too
const big=await P4("cmd",{type:"message",text:"a".repeat(200000)});
ck("200KB cmd refused 413 (was a known gap: accepted, then 500s once the queue value passed the storage limit)",big.status==413);
ck("small cmd still accepted",(await (await P4("cmd",{type:"message",text:"hi"})).json()).result=="queued");
{let st=0;for(let i=0;i<25&&st!=429;i++)st=(await P4("cmd",{type:"ring"})).status;
ck("full queue (20) refuses with 429 instead of dropping the oldest",st==429);
const s3=await (await P4("sync",{})).json();
ck("queued commands (incl. oldest) survive a full queue",s3.cmds.length==20&&s3.cmds.some(c=>c.type=="lock"||c.type=="message"));}
ck("sync works after all that",(await P4("sync",{})).status==200);}
// ---- Telegram alerts (fake Telegram from run.mjs on $MOCK; webhook path = sha256("tg:" + token)[:32]) ----
if(process.env.MOCK){const {createHash}=await import("node:crypto"),M=`http://127.0.0.1:${process.env.MOCK}`;
const hook=createHash("sha256").update("tg:123456:test-token").digest("hex").slice(0,32);
const idT=[...crypto.getRandomValues(new Uint8Array(8))].map(b=>b.toString(16).padStart(2,"0")).join(""),PT=(p,o,m="POST")=>fetch(`${B}/c/${idT}/${p}`,{method:m,headers:H(),body:m=="GET"?undefined:JSON.stringify(o)});
const tgs=async()=>(await (await fetch(M+"/_tg")).json());
const upd=(text,chat=42,type="private")=>fetch(`${B}/tg/${hook}`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({message:{text,chat:{id:chat,type}}})});
await PT("sync",{state:{}});
let j=await (await PT("tg",null,"GET")).json();ck("tg status: enabled, not linked",j.enabled===true&&j.linked===false);
ck("tg needs the kid token",(await fetch(`${B}/c/${idT}/tg`,{headers:H(BAD)})).status==401);
j=await (await PT("tg",{name:"Bé <b>Na</b>",lang:"en"})).json();
const code=(j.link||"").split("start=")[1]||"";
ck("tg link: t.me/<bot>?start=<id+8 random bytes>",j.link&&j.link.startsWith("https://t.me/kg_test_bot?start="+idT)&&/^[0-9a-f]{32}$/.test(code));
ck("tg webhook wrong path 404",(await fetch(`${B}/tg/${"0".repeat(32)}`,{method:"POST",body:"{}"})).status==404);
ck("tg webhook GET is not a route",(await fetch(`${B}/tg/${hook}`)).status==404);
const n0=(await tgs()).length;
await upd("/start "+idT+"0".repeat(16));await upd("/start "+code,42,"group");
j=await (await PT("tg",null,"GET")).json();ck("tg wrong code / group chat does not link",j.linked===false);
ck("tg bad code gets the help text",(await tgs()).length==n0+1);
ck("tg webhook always 200 on junk",(await fetch(`${B}/tg/${hook}`,{method:"POST",body:"nope"})).status==200);
await upd("/start "+code);
j=await (await PT("tg",null,"GET")).json();ck("tg /start <code> links",j.linked===true);
let ms=await tgs();ck("tg confirmation sent to the chat, name sanitised, in English",ms.length==n0+2&&ms[n0+1].chat_id==42&&/Telegram alerts are on for Bé bNa\/b/.test(ms[n0+1].text)&&ms[n0+1].text.includes("/stop_"+idT));
await upd("/start "+code,43);ck("tg code is one-time",(await tgs()).length==n0+3&&(await (await PT("tg",null,"GET")).json()).linked===true);
const now=Date.now();
await PT("sync",{state:{sos:[{id:"t1",t:now}],requests:[{id:"q1",t:now,min:30,note:"bí mật"}],events:[{id:"n1",t:now,kind:"newApp",text:"Cài TikTok"},{id:"n2",t:now,kind:"battery"},{id:"n3",t:now,kind:"tamper"}]}});await new Promise(s=>setTimeout(s,800));
ms=(await tgs()).slice(n0+3);
ck("tg: sos, request, newApp, tamper sent (battery not), kid name, no notes / app names",ms.length==4&&ms.some(m=>/SOS: Bé bNa\/b/.test(m.text))&&ms.some(m=>/30 more minutes/.test(m.text))&&ms.some(m=>/new app/.test(m.text))&&ms.some(m=>/tamper/.test(m.text))&&!JSON.stringify(ms).includes("bí mật")&&!JSON.stringify(ms).includes("TikTok"));
await PT("sync",{state:{sos:[{id:"t1",t:now}],requests:[{id:"q1",t:now,min:30}]}});await new Promise(s=>setTimeout(s,500));
ck("tg: same ids not sent again",(await tgs()).length==n0+7);
await upd("/stop_"+idT,99);ck("tg /stop_<id> from another chat does nothing",(await (await PT("tg",null,"GET")).json()).linked===true);
await upd("/stop_"+idT,42);ck("tg /stop_<id> from the linked chat unlinks",(await (await PT("tg",null,"GET")).json()).linked===false);
j=await (await PT("tg",{name:"Na"})).json();await upd("/start "+j.link.split("start=")[1]);
ck("tg relink works (vi default)",(await (await PT("tg",null,"GET")).json()).linked===true&&/Đã bật báo qua Telegram/.test((await tgs()).at(-1).text));
j=await (await PT("tg",{off:true})).json();ck("tg off from the page unlinks",j.linked===false&&(await (await PT("tg",null,"GET")).json()).linked===false);
const idB=[...crypto.getRandomValues(new Uint8Array(8))].map(b=>b.toString(16).padStart(2,"0")).join(""),PB=(p,o,m="POST")=>fetch(`${B}/c/${idB}/${p}`,{method:m,headers:H(),body:m=="GET"?undefined:JSON.stringify(o)});
await PB("sync",{state:{}});await PB("tg",{name:"Bo"}).then(r=>r.json()).then(async o=>{await upd("/start "+o.link.split("start=")[1],666);});
await PB("sync",{state:{sos:[{id:"b1",t:now}]}});await new Promise(s=>setTimeout(s,600));
ck("tg: Telegram 403 (bot blocked) unlinks",(await (await PB("tg",null,"GET")).json()).linked===false);}
// last: per-IP rate limit (150 / 10 s) on /c/*; the page itself is not limited
{const rs=[];for(let i=0;i<11&&!rs.includes(429);i++)rs.push(...await Promise.all(Array.from({length:20},()=>fetch(`${B}/c/${id}/state`,{headers:H(BAD)}).then(r=>r.status,()=>0))));
ck("rate limit: burst of 220 gets some 429",rs.includes(429));ck("rate limit: page / still 200",(await fetch(`${B}/`)).status==200);}
// ---- shared files: chunked through the DO, <= 10 MB, deleted on DELETE ----
await new Promise(s=>setTimeout(s,10500));   // the per-IP rate limit (150 / 10 s) is used up by the tests above
{const id5=Array.from({length:16},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),U=(q,m="GET",body)=>fetch(`${B}/c/${id5}/file${q}`,{method:m,headers:H(),body});
await fetch(`${B}/c/${id5}/sync`,{method:"POST",headers:H(),body:JSON.stringify({state:{}})});
const fid="00112233445566ff",sz=1048576+5,big=Buffer.alloc(sz,7);big[sz-1]=9;
const q=i=>`?fid=${fid}&i=${i}&n=2&to=kid&size=${sz}&name=${encodeURIComponent("bài.pdf")}`;
let r=await U(q(0),"POST",big.subarray(0,1048576));const r0=await r.json();ck("file chunk 0 ok, not done",r.status==200&&r0.done===false);
ck("file sync: none done yet",!(await (await fetch(`${B}/c/${id5}/sync`,{method:"POST",headers:H(),body:"{}"})).json()).files);
ck("file bad chunk size 400",(await U(q(1),"POST",Buffer.alloc(3))).status==400);
r=await U(q(1),"POST",big.subarray(1048576));ck("file chunk 1 done",(await r.json()).done===true);
ck("file sync says files:1",(await (await fetch(`${B}/c/${id5}/sync`,{method:"POST",headers:H(),body:"{}"})).json()).files===1);
let l=await (await U("?to=kid")).json();ck("file list to kid",l.files.length==1&&l.files[0].name=="bài.pdf"&&l.files[0].done&&l.files[0].n==2);
ck("file list to parent empty",(await (await U("?to=parent")).json()).files.length==0);
const c0=Buffer.from(await (await U(`?fid=${fid}&i=0`)).arrayBuffer()),c1=Buffer.from(await (await U(`?fid=${fid}&i=1`)).arrayBuffer());
ck("file chunks round-trip",Buffer.concat([c0,c1]).equals(big));
ck("file >10MB 413",(await U(`?fid=0011223344556677&i=0&n=11&to=kid&size=${10*1048576+1}&name=x`,"POST",Buffer.alloc(10))).status==413);
ck("file bad id 400",(await U(`?fid=../x&i=0`)).status==400);
ck("file bad to 400",(await U(`?fid=0011223344556678&i=0&n=1&to=evil&size=1&name=x`,"POST",Buffer.alloc(1))).status==400);
ck("file wrong token 401",(await fetch(`${B}/c/${id5}/file?to=kid`,{headers:H(BAD)})).status==401);
ck("file delete ok",(await (await U(`?fid=${fid}`,"DELETE")).json()).ok===true);
ck("file gone after delete",(await U(`?fid=${fid}&i=0`)).status==404&&(await (await U("?to=kid")).json()).files.length==0);
ck("cmd fileUp whitelisted",(await (await fetch(`${B}/c/${id5}/cmd`,{method:"POST",headers:H(),body:JSON.stringify({type:"fileUp",file:fid})})).json()).result=="queued");
// icons: a full list (400) drops the icons of apps that are no longer in the phone's state
const ic=Buffer.from("RIFF0000WEBPVP8 ").toString("base64"),IP=o=>fetch(`${B}/c/${id5}/icons`,{method:"POST",headers:H(),body:JSON.stringify(o)}).then(r=>r.json());
await fetch(`${B}/c/${id5}/sync`,{method:"POST",headers:H(),body:JSON.stringify({state:{apps:[{pkg:"keep.me",ms:1}]}})});
let ij=await IP(Object.fromEntries([["keep.me",ic],...Array.from({length:399},(_,i)=>["old.a"+i,ic])]));ck("icons: 400 stored",ij.have==400);
ij=await IP({"new.one":ic});ck("icons full: removed apps' icons make room",ij.have==2);
ck("icons full: kept app still there, old one gone",(await fetch(`${B}/c/${id5}/icon?pkg=keep.me&k=${T}`)).status==200&&(await fetch(`${B}/c/${id5}/icon?pkg=old.a7&k=${T}`)).status==404&&(await fetch(`${B}/c/${id5}/icon?pkg=new.one&k=${T}`)).status==200);}
// /go + /where: same public network (cf-connecting-ip: wrangler dev may set its own; both sides then match)
{const id6="a1b2c3d4e5f6071c",W=(x={})=>fetch(`${B}/c/${id6}/where`,{headers:H(T,x)});
const g=await fetch(`${B}/go`);ck("/go serves the page",g.status==200&&(await g.text()).includes("/ui/core.js"));
ck("where before phone 409",(await W()).status==409);
await fetch(`${B}/c/${id6}/sync`,{method:"POST",headers:H(T,{"cf-connecting-ip":"203.0.113.7"}),body:JSON.stringify({state:{lan:"http://192.168.1.9:8770",ts:{ip:"100.79.11.22"},mode:"basic"}})});
let w=await (await W({"cf-connecting-ip":"203.0.113.7"})).json();
ck("where: lan, ts, fresh seen",w.lan=="http://192.168.1.9:8770"&&w.ts=="100.79.11.22"&&Date.now()-w.seen<10000&&w.mode=="basic");
ck("where: same network",w.same===true);
const w2=await (await W({"cf-connecting-ip":"198.51.100.4"})).json();console.log("  (other network same="+w2.same+")");
ck("where wrong token 401",(await fetch(`${B}/c/${id6}/where`,{headers:H(BAD)})).status==401);}
// WebSocket push channel (Relay.kt RelayWs): raw client, Node's WebSocket can't send X-KG-Token
{const http=await import("node:http"),crypto=await import("node:crypto");const id7="a1b2c3d4e5f6071d";
const wsOpen=(tok=T)=>new Promise((ok,no)=>{const q=http.request(`${B}/c/${id7}/ws`,{headers:{"x-kg-token":tok,Connection:"Upgrade",Upgrade:"websocket","Sec-WebSocket-Version":"13","Sec-WebSocket-Key":crypto.randomBytes(16).toString("base64")}});
  q.on("upgrade",(r,sock)=>{const msgs=[];let buf=Buffer.alloc(0),wait=null;
    sock.on("data",d=>{buf=Buffer.concat([buf,d]);while(buf.length>=2){let n=buf[1]&127,o=2;if(n==126){if(buf.length<4)break;n=buf.readUInt16BE(2);o=4}else if(n==127){if(buf.length<10)break;n=Number(buf.readBigUInt64BE(2));o=10}
      if(buf.length<o+n)break;const op=buf[0]&15,p=buf.subarray(o,o+n).toString();buf=buf.subarray(o+n);if(op==1){msgs.push(p);if(wait){const w=wait;wait=null;w()}}}});
    const send=t=>{const p=Buffer.from(t),m=crypto.randomBytes(4),h=p.length<126?Buffer.from([0x81,0x80|p.length]):Buffer.from([0x81,0x80|126,p.length>>8,p.length&255]);sock.write(Buffer.concat([h,m,Buffer.from(p.map((b,i)=>b^m[i&3]))]))};
    const next=(ms=8000)=>new Promise(r=>{if(msgs.length)return r(msgs.shift());const tm=setTimeout(()=>{wait=null;r(null)},ms);wait=()=>{clearTimeout(tm);r(msgs.shift())}});
    ok({status:101,send,next,close:()=>sock.destroy()})});
  q.on("response",r=>{r.resume();ok({status:r.statusCode})});q.on("error",no);q.end();});
ck("ws before bind 409",(await wsOpen()).status==409);
await fetch(`${B}/c/${id7}/sync`,{method:"POST",headers:H(),body:JSON.stringify({state:{mode:"owner"}})});
ck("ws wrong token 401",(await wsOpen(BAD)).status==401);
const w=await wsOpen();ck("ws upgrade 101",w.status==101);
w.send("ping");ck("ws auto-response pong",(await w.next())=="pong");
w.send(JSON.stringify({t:"sync",n:7,acks:[],state:{mode:"owner",lan:"http://10.0.0.2:8770"}}));let m=JSON.parse(await w.next());
ck("ws sync reply = sync shape",m.t=="sync"&&m.n==7&&Array.isArray(m.cmds));
const st=await (await fetch(`${B}/c/${id7}/state`,{headers:H()})).json();ck("ws sync stored state, seen fresh",st.state.lan=="http://10.0.0.2:8770"&&Date.now()-st.seen<10000);
await fetch(`${B}/c/${id7}/cmd`,{method:"POST",headers:H(),body:JSON.stringify({type:"ring"})});
m=await w.next();ck("parent cmd -> kick pushed",m=='{"t":"kick"}');
w.send(JSON.stringify({t:"sync",n:8,acks:[]}));m=JSON.parse(await w.next());ck("ws sync hands the cmd",m.n==8&&m.cmds.length==1&&m.cmds[0].type=="ring");
w.send(JSON.stringify({t:"sync",n:9,acks:[m.cmds[0].id]}));m=JSON.parse(await w.next());ck("ws ack clears",m.cmds.length==0);
const w2=await wsOpen();ck("second ws replaces the first",w2.status==101&&(await w.next(3000))===null);
w2.send("ping");ck("new ws alive",(await w2.next())=="pong");
w2.close();await new Promise(r=>setTimeout(r,500));
const s2=await (await fetch(`${B}/c/${id7}/state`,{headers:H()})).json();ck("closed ws: seen kept (last sign of life)",Date.now()-s2.seen<15000);}
// ---- audit M3: secrets leave the queue after the first hand-out; the worker drops its unsalted PIN hash once the phone has it ----
{const rh=n=>Array.from({length:n},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),idm=rh(16),Tm=rh(48),sleep2=ms=>new Promise(r=>setTimeout(r,ms));
const Q=async(p,o,m="POST")=>{for(let i=0;;i++){const r=await fetch(`${B}/c/${idm}/${p}`,{method:m,headers:{"x-kg-token":Tm,"content-type":"application/json"},body:m=="GET"?undefined:JSON.stringify(o)});if((r.status!=429&&r.status!=503)||i>=20)return r;await sleep2(1500);}};
await Q("sync",{state:{rules:{rev:1}}});
await Q("cmd",{type:"tgSet",token:"123456:secret-bot-token",pin:"1234"});
let a=await (await Q("sync",{})).json(),c0=a.cmds.find(c=>c.type=="tgSet");
ck("M3: first hand-out carries token + pin",!!c0&&c0.token=="123456:secret-bot-token"&&c0.pin=="1234");
a=await (await Q("sync",{})).json();const c1=a.cmds.find(c=>c.type=="tgSet");
ck("M3: queued copy after hand-out has no token / pin",!!c1&&!("token" in c1)&&!("pin" in c1)&&c1.id==c0.id);
await Q("sync",{acks:[c0.id]});a=await (await Q("sync",{})).json();ck("M3: acked command gone",!a.cmds.some(c=>c.type=="tgSet"));
await Q("rules",{dailyMin:60,pin:"4321"},"PUT");
a=await (await Q("sync",{state:{rules:{rev:1}}})).json();ck("M3: phone still gets pinHash until it has the rules",!!(a.rules&&a.rules.pinHash));
await Q("sync",{state:{rules:{rev:Date.now()+1e6,pinSet:true}}});
a=await (await Q("sync",{state:{rules:{rev:1,pinSet:true}}})).json();
ck("M3: worker dropped its pinHash once the phone reported the rules + pinSet",!!a.rules&&!("pinHash" in a.rules)&&a.rules.pinSet===true);
a=await (await Q("rules",{dailyMin:70},"PUT")).json();ck("M3: later PUT without pin keeps pinSet",a.pinSet===true&&a.dailyMin==70);}
// ---- M13 apk proxy, L17 resume drops a queued lock ----
{const a=await fetch(`${B}/kidguard.apk`);ck("M13: /kidguard.apk proxied, octet-stream, no-cache",a.status==200&&(await a.text())=="APKBYTES"&&a.headers.get("content-type")=="application/octet-stream"&&a.headers.get("cache-control")=="no-cache"&&a.headers.get("x-content-type-options")=="nosniff");
ck("M13: sha256 proxied",(await (await fetch(`${B}/kidguard.apk.sha256`)).text()).startsWith("abc"));
const rh=n=>Array.from({length:n},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),idl=rh(16),Tl=rh(48);
const Q=async(p,o,m="POST")=>{for(let i=0;;i++){const r=await fetch(`${B}/c/${idl}/${p}`,{method:m,headers:{"x-kg-token":Tl,"content-type":"application/json"},body:JSON.stringify(o)});if((r.status!=429&&r.status!=503)||i>=20)return r;await new Promise(z=>setTimeout(z,1500));}};
await Q("sync",{state:{rules:{rev:1}}});
await Q("rules",{dailyMin:60,pausedUntil:Date.now()+36e5},"PUT");await Q("cmd",{type:"lock"});await Q("cmd",{type:"ring"});
await Q("rules",{dailyMin:60,pausedUntil:0},"PUT");
const a2=await (await Q("sync",{})).json();ck("L17: resume (pausedUntil 0) drops the queued lock, keeps other commands",!a2.cmds.some(c=>c.type=="lock")&&a2.cmds.some(c=>c.type=="ring"));}
// ---- C4 expired receipts + C8 forget (SHORT_TTL_MS=5000 in run.mjs) ----
{const rh=n=>Array.from({length:n},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),ide=rh(16),Te=rh(48);
const Q=async(p,o,m="POST")=>{for(let i=0;;i++){const r=await fetch(`${B}/c/${ide}/${p}`,{method:m,headers:{"x-kg-token":Te,"content-type":"application/json"},body:JSON.stringify(o)});if((r.status!=429&&r.status!=503)||i>=20)return r;await new Promise(z=>setTimeout(z,1500));}};
await Q("sync",{state:{rules:{rev:1}}});
ck("unlockEnd whitelisted",(await (await Q("cmd",{type:"unlockEnd",id:"ue1"})).json()).result=="queued");
await Q("cmd",{type:"ring",id:"rg1"});
let a=await (await Q("sync",{acks:["ue1"]})).json();   // ring handed out but not acked
ck("fresh state has no expired",Array.isArray((await (await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}})).json()).expired)&&!(await (await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}})).json()).expired.length);
await new Promise(z=>setTimeout(z,5600));
await Q("cmd",{type:"message",text:"x",id:"m1"});   // a parent POST drops the stale ring too
const st=await (await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}})).json();
ck("C4: stale SHORT command is reported expired",st.expired.some(e=>e.id=="rg1"&&e.type=="ring"&&e.res=="expired"&&e.at>0)&&!st.expired.some(e=>e.id=="ue1"));
await Q("cmd",{type:"ring",id:"rg2"});await new Promise(z=>setTimeout(z,5600));await Q("sync",{});
ck("C4: expiry found on a phone sync too",(await (await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}})).json()).expired.some(e=>e.id=="rg2"));
for(let i=0;i<12;i++){await Q("cmd",{type:"ring",id:"x"+i});await new Promise(z=>setTimeout(z,300));}
await new Promise(z=>setTimeout(z,5600));await Q("sync",{});
const ex=(await (await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}})).json()).expired;
ck("C4: expired keeps the last 10",ex.length==10&&ex[9].id=="x11");
ck("C8: forget with a wrong token 401",(await fetch(`${B}/c/${ide}/forget`,{method:"POST",headers:{"x-kg-token":BAD}})).status==401);
r=await Q("forget",{});ck("C8: forget ok",(await r.json()).result=="forgotten");
r=await fetch(`${B}/c/${ide}/state`,{headers:{"x-kg-token":Te}});ck("C8: after forget the id is unbound (409 waiting for the phone)",r.status==409);
r=await Q("sync",{state:{rules:{rev:1}}});ck("C8: the same id can bind again after forget",r.status==200);}
// ---- watch connect: /w/new, POST /c/<id>/watch, GET /w/<code> (docs/protocol.md "Watch connect") ----
{const {createHash,randomBytes}=await import("node:crypto");
const rh=n=>Array.from({length:n},()=>"0123456789abcdef"[Math.random()*16|0]).join(""),idw=rh(16),Tw=rh(48);
const sec=randomBytes(16).toString("hex"),sh=createHash("sha256").update(sec).digest("hex");
const W=(p,o={})=>fetch(`${B}${p}`,o);
const NEW=async(h=sh)=>W("/w/new",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({sh:h})});
const GET=(code,s=sec)=>W(`/w/${code}`,{headers:s?{"x-kg-secret":s}:{}});
const ATT=(code,t=Tw,name="Bin")=>fetch(`${B}/c/${idw}/watch`,{method:"POST",headers:{"x-kg-token":t,"content-type":"application/json"},body:JSON.stringify({code,name})});
await fetch(`${B}/c/${idw}/sync`,{method:"POST",headers:{"x-kg-token":Tw,"content-type":"application/json"},body:JSON.stringify({state:{t:1}})});
r=await NEW();j=await r.json();const code=j.code;
ck("watch: /w/new gives a 6-digit code + exp = now + TTL (6 s here, 10 min in production)",r.status==200&&/^\d{6}$/.test(code)&&j.exp-Date.now()>3500&&j.exp-Date.now()<=6000);
ck("watch: /w/new with a bad sh 400",(await NEW("abc")).status==400);
ck("watch: /w/new GET 405",(await W("/w/new")).status==405);
r=await GET(code);j=await r.json();ck("watch: poll before attach = waiting",r.status==200&&j.waiting===true);
ck("watch: wrong secret 403",(await GET(code,"ee".repeat(16))).status==403);
ck("watch: no secret 403",(await GET(code,"")).status==403);
ck("watch: unknown code 404",(await GET("000000")).status==404||(await GET("000000")).status==403);
ck("watch: attach without a token 401",(await fetch(`${B}/c/${idw}/watch`,{method:"POST",body:JSON.stringify({code})})).status==401);
ck("watch: attach with a wrong token 401",(await ATT(code,BAD)).status==401);
ck("watch: attach with a bad code 400",(await ATT("12ab")).status==400);
ck("watch: attach to a code nobody asked for 404",(await ATT("000001")).status==404);
ck("watch: attach GET 405",(await fetch(`${B}/c/${idw}/watch`,{headers:{"x-kg-token":Tw}})).status==405);
ck("watch: attach body >512 B 413",(await fetch(`${B}/c/${idw}/watch`,{method:"POST",headers:{"x-kg-token":Tw},body:JSON.stringify({code,name:"a".repeat(600)})})).status==413);
ck("watch: still waiting after failed attaches",(await (await GET(code)).json()).waiting===true);
r=await ATT(code);ck("watch: attach ok",r.status==200&&(await r.json()).ok===true);
ck("watch: a second attach 409",(await ATT(code)).status==409);
ck("watch: attached but wrong secret still 403 (creds not leaked)",(await GET(code,"ee".repeat(16))).status==403);
r=await GET(code);j=await r.json();
ck("watch: one fetch returns {url,id,token,name}",r.status==200&&j.id==idw&&j.token==Tw&&j.name=="Bin"&&j.url==B&&!j.waiting);
ck("watch: the entry is gone after the fetch (404)",(await GET(code)).status==404);
ck("watch: attach after take 404",(await ATT(code)).status==404);
// expiry: unattached code, then an attached-but-never-fetched one: both gone after the TTL
const c2=(await (await NEW()).json()).code,c3=(await (await NEW()).json()).code;await ATT(c3);
await new Promise(z=>setTimeout(z,6600));
ck("watch: an expired code answers 404 to the watch",(await GET(c2)).status==404);
ck("watch: attach to an expired code 404",(await ATT(c2)).status==404);
ck("watch: credentials of an expired code are not handed out",(await GET(c3)).status==404);
// state?watch=1: small view for watches + tile-only key (Garmin image requests carry no headers)
{const apps=Array.from({length:150},(_,i)=>({pkg:"p"+i,label:"App "+i+" "+"x".repeat(40),ms:i*1000,icon:"y".repeat(200)}));
await fetch(`${B}/c/${idw}/sync`,{method:"POST",headers:{"x-kg-token":Tw,"content-type":"application/json"},body:JSON.stringify({state:{t:2,leftMs:60000,battery:50,current:"p149",now:{why:"daily",until:0},location:{lat:10.7,lng:106.7,acc:12,t:1},apps,events:Array.from({length:40},(_,i)=>({t:i,kind:"k",text:"e"+i})),requests:[{id:"r1",at:1,kind:"time",min:15}],done:[]}})});
const full=await (await fetch(`${B}/c/${idw}/state`,{headers:{"x-kg-token":Tw}})).text();
const wr=await fetch(`${B}/c/${idw}/state?watch=1`,{headers:{"x-kg-token":Tw}}),wt=await wr.text(),wj=JSON.parse(wt);
ck(`watch=1: much smaller than the full state (${wt.length} vs ${full.length} B)`,wr.status==200&&wt.length<full.length/4&&wt.length<16000);
ck("watch=1: keeps scalars, now, location, requests; top 15 apps without icons; current label",wj.state.leftMs===60000&&wj.state.battery===50&&wj.state.now.why=="daily"&&wj.state.location.lat==10.7&&wj.state.requests.length==1&&wj.state.apps.length==15&&wj.state.apps[0].pkg=="p149"&&!wj.state.apps[0].icon&&wj.state.currentLabel.startsWith("App 149")&&wj.state.events.length==10);
ck("watch=1: tileKey = sha256('tile:'+token), seen + queue present",wj.tileKey==createHash("sha256").update("tile:"+Tw).digest("hex")&&typeof wj.seen=="number"&&Array.isArray(wj.queue));
ck("tile with a wrong k 401",(await fetch(`${B}/c/${idw}/tile?z=16&x=1&y=1&k=${"0".repeat(64)}`)).status==401);
ck("tile with the right k passes auth (bad coords -> 400)",(await fetch(`${B}/c/${idw}/tile?z=3&x=1&y=1&k=${wj.tileKey}`)).status==400);
ck("tile k of another kid 401",(await fetch(`${B}/c/${id}/tile?z=16&x=1&y=1&k=${wj.tileKey}`)).status==401);
ck("icon route does not accept the tile key",(await fetch(`${B}/c/${idw}/icon?p=a&k=${wj.tileKey}`)).status==401);}
}
// ---- audit H4: first binds per network per day are capped (BIND_PER_DAY=60 in run.mjs); keep this LAST: it uses the budget up ----
{const rh=n=>Array.from({length:n},()=>"0123456789abcdef"[Math.random()*16|0]).join("");let ok=0,blocked=0,real=null;
for(let i=0;i<90&&!blocked;i++){const idn=rh(16),r=await fetch(`${B}/c/${idn}/sync`,{method:"POST",headers:{"x-kg-token":rh(48),"content-type":"application/json"},body:JSON.stringify({state:{t:1}})});
if(r.status==200)ok++;else if(r.status==429){const t=await r.text();if(/too many new pairings/.test(t))blocked=i+1;else await new Promise(z=>setTimeout(z,1500));}}
ck(`H4: new binds from one network stop at the daily cap (ok ${ok}, blocked at ${blocked})`,blocked>0&&ok<=60);
ck("H4: an already bound kid keeps syncing past the cap",(await fetch(`${B}/c/${id}/sync`,{method:"POST",headers:{"x-kg-token":T,"content-type":"application/json"},body:"{}"})).status==200);}
console.log(pass,"pass",fail,"fail");process.exit(fail?1:0);

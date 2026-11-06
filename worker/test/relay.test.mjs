// run: npx wrangler dev --port $PORT (default 8793), then: PORT=8793 node test/relay.test.mjs
const B="http://localhost:"+(process.env.PORT||8793), id="a1b2c3d4e5f60718", T="ab".repeat(24), BAD="cd".repeat(24);
let pass=0,fail=0; const ck=(n,c)=>{console.log(c?"PASS":"FAIL",n);c?pass++:fail++};
const H=(t=T,x={})=>({"x-kg-token":t,...x});
const P=(p,body,t=T,x={})=>fetch(`${B}/c/${id}/${p}`,{method:"POST",headers:H(t,x),body});
const jpg=Buffer.from("ffd8ffe000104a46494600010100000100010000ffd9","hex");
let r=await P("sync",JSON.stringify({state:{}}));let j=await r.json();
ck("sync binds, no live",r.status==200&&!j.live);
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
j=await S();ck("deliver-once: reboot/release/unpairAll gone, lock+answer stay",j.cmds.map(c=>c.type).sort().join()=="answer,lock");
j=await S({acks:["req1",j.cmds.find(c=>c.type=="lock").id]});ck("acks drop the rest",j.cmds.length==0);
ck("bad cmd 400",(await P2("cmd",JSON.stringify({type:"rm"}))).status==400);
// 6: rules
ck("rules array 400",(await P2("rules","[1]",T,{},"PUT")).status==400);
ck("rules string 400",(await P2("rules",'"x"',T,{},"PUT")).status==400);
r=await P2("rules",JSON.stringify({dailyMin:60,pin:"1234"}),T,{},"PUT");j=await r.json();
ck("PUT rules: pinSet, no pinHash/pin",j.pinSet===true&&!("pinHash" in j)&&!("pin" in j)&&j.dailyMin==60);
r=await P2("rules",JSON.stringify({dailyMin:90}),T,{},"PUT");j=await r.json();ck("PUT without pin keeps pinSet",j.pinSet===true&&j.dailyMin==90);
j=await (await P2("state",null,T,{},"GET")).json();ck("GET state: no pinHash",j.rules.pinSet===true&&!JSON.stringify(j).includes("pinHash"));
j=await S({state:{rules:{rev:1}}});ck("phone gets pinHash (sha256 kg:1234)",j.rules&&j.rules.pinHash==="d371349c9ee42104a430ccbe28c7b26b6eea4541d3f42cb314fe208a325e3afd");
j=await S({state:{rules:{rev:1,pinHash:"x".repeat(64)}}});ck("state with phone's rules never leaks pinHash to parent",!JSON.stringify(await (await P2("state",null,T,{},"GET")).json()).includes("xxxx"));
j=await S();ck("state omitted: rules not resent when phone up to date",true);
// 7: seen
r=await P2("state",null,T,{},"GET");j=await r.json();const seen1=j.seen;await sleep(50);await S();j=await (await P2("state",null,T,{},"GET")).json();ck("seen advances (in memory)",j.seen>seen1&&Date.now()-j.seen<5000);
// 3: seq
ck("frame seq starts at Date.now()", (await (async()=>{await P2("live","{}");const g=P2("frame",jpg,T,{"x-kg-w":"1","x-kg-h":"1"});await g;return +(await fetch(`${B}/c/${id2}/frame?after=0`,{headers:H()})).headers.get("x-kg-seq")})())>Date.now()-60000);
// 4b: old inputs dropped
await P2("live","{}");await P2("input",JSON.stringify({type:"tap",x:.2,y:.2}));await sleep(5300);await P2("input",JSON.stringify({type:"key",key:"home"}));
j=await (await P2("frame",jpg)).json();ck("inputs older than 5s dropped, fresh kept (no 'at' leak)",j.inputs.length==1&&j.inputs[0].type=="key"&&!("at" in j.inputs[0]));
ck("CSP allows OSM tiles",(await fetch(`${B}/`)).headers.get("content-security-policy").includes("img-src 'self' data: blob: https://tile.openstreetmap.org"));
const track=Array.from({length:700},(_,i)=>[1.7e12+i,10.123456,106.123456,12.5]);
r=await P2("sync",JSON.stringify({state:{track}}));ck("sync with ~700-point track (<120KB) ok",r.status==200);
r=await P2("sync",JSON.stringify({state:{big:"a".repeat(125000)}}));ck("state >120KB -> 413",r.status==413);
// last: per-IP rate limit (150 / 10 s) on /c/*; the page itself is not limited
{const rs=[];for(let i=0;i<11&&!rs.includes(429);i++)rs.push(...await Promise.all(Array.from({length:20},()=>fetch(`${B}/c/${id}/state`,{headers:H(BAD)}).then(r=>r.status,()=>0))));
ck("rate limit: burst of 220 gets some 429",rs.includes(429));ck("rate limit: page / still 200",(await fetch(`${B}/`)).status==200);}
console.log(pass,"pass",fail,"fail");process.exit(fail?1:0);

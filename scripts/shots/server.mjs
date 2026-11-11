// Fake child phone for screenshots: serves ui/ like Api.kt does and answers the page's API from fixtures/*.json.
// Node 20, no deps. Usage: node server.mjs [--lang vi|en] [--now <epoch ms>] [--kids bin:8771,mia:8772,tom:8773]
// Every port answers as one kid (the first port is also the page's own origin). Sample data only: invented kids,
// a generic central-Hanoi map area, no real person or address.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const UI = path.resolve(HERE, "../../ui");
const FIX = path.join(HERE, "fixtures");
const arg = (n, d) => { const i = process.argv.indexOf("--" + n); return i > 0 ? process.argv[i + 1] : d; };
const LANG = arg("lang", "vi");
// --patch file.json: fields merged over every /state (e.g. {"mode":"basic","owner":false} to see basic mode)
const PATCH = arg("patch") ? JSON.parse(fs.readFileSync(arg("patch"), "utf8")) : {};
const KIDS = arg("kids", "bin:8771").split(",").map(s => s.split(":")).map(([k, p]) => [k, +p]);

// "now" of the story: today 16:20 local unless --now is given (the page clock is shifted to the same instant by shoot.mjs)
const NOW = +arg("now", 0) || (() => { const d = new Date(); d.setHours(16, 20, 0, 0); return d.getTime(); })();
const midnight = new Date(NOW); midnight.setHours(0, 0, 0, 0);
const dkey = ms => { const d = new Date(ms); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
const U = { s: 1e3, m: 6e4, h: 36e5, d: 864e5 };

// "@0" / "@-5m" / "@-2d" = NOW + offset; "@07:30" = today at that time; "@day:-1" = date key; {vi,en} = text in --lang
function resolve(v) {
  if (Array.isArray(v)) return v.map(resolve);
  if (v && typeof v === "object") {
    if (typeof v.vi === "string" && typeof v.en === "string" && Object.keys(v).length === 2) return v[LANG];
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, resolve(x)]));
  }
  if (typeof v !== "string" || v[0] !== "@") return v;
  let m;
  if ((m = /^@day:(-?\d+)$/.exec(v))) return dkey(NOW + m[1] * U.d);
  if ((m = /^@(\d\d):(\d\d)$/.exec(v))) return midnight.getTime() + m[1] * U.h + m[2] * U.m;
  if ((m = /^@(-?\d+)([smhd])?$/.exec(v))) return NOW + m[1] * (U[m[2]] || 0);
  return v;
}
const merge = (a, b) => {
  if (!b || typeof b !== "object" || Array.isArray(b)) return b;
  const o = { ...a };
  for (const [k, v] of Object.entries(b)) o[k] = v && typeof v === "object" && !Array.isArray(v) && a && typeof a[k] === "object" && !Array.isArray(a[k]) && !("vi" in v) ? merge(a[k], v) : v;
  return o;
};
const readFix = n => JSON.parse(fs.readFileSync(path.join(FIX, n + ".json"), "utf8"));
function kidFixture(name) {
  let j = readFix(name);
  if (j.$extends) { const { $extends, ...rest } = j; j = merge(readFix($extends), rest); }
  return j;
}

// the phone keeps its rules in memory so edits made while shooting (none by default) behave
const rulesOf = {};
function stateOf(name) {
  const f = resolve(kidFixture(name));
  if (rulesOf[name]) f.rules = rulesOf[name];
  f.t = NOW - 18e3;
  if (name === "bin") f.track = trackOf().filter(p => p[0] >= midnight.getTime() - U.d);
  return f;
}
// today's walk: waypoints (minutes since midnight) + a few metres of jitter, deterministic
function trackOf() {
  const pts = readFix("track").points;
  let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647 - 0.5;
  return pts.map(([min, lat, lng]) => [midnight.getTime() + min * U.m, +(lat + rnd() * 0.00006).toFixed(5), +(lng + rnd() * 0.00006).toFixed(5), 12 + Math.round(Math.abs(rnd()) * 20)]);
}

// ---- generated pictures --------------------------------------------------------------
const BRAND = {
  "com.roblox.client": ["#e2231a", "R"], "com.google.android.youtube": ["#ff0000", "▶"], "com.mojang.minecraftpe": ["#5b8731", "M"],
  "com.zhiliaoapp.musically": ["#111111", "♪"], "com.zing.zalo": ["#0068ff", "Z"], "com.android.chrome": ["#4285f4", "C"],
  "com.duolingo": ["#58cc02", "D"], "org.khanacademy.android": ["#14bf96", "K"], "com.supercell.clashroyale": ["#2b6cb0", "C"],
  "com.google.android.apps.photos": ["#ea4335", "P"], "com.google.android.apps.maps": ["#34a853", "M"], "com.spotify.music": ["#1db954", "S"],
  "com.google.android.calculator": ["#5f6368", "="], "com.google.android.apps.classroom": ["#0f9d58", "G"], "com.facebook.orca": ["#0084ff", "m"],
  "com.google.android.apps.docs": ["#1a73e8", "D"], "com.instagram.android": ["#c13584", "I"], "dev.kidguard": ["#2563eb", "K"],
};
function iconSvg(pkg, size = 96) {
  const [c, g] = BRAND[pkg] || ["#64748b", (pkg.split(".").pop() || "?")[0].toUpperCase()];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 96 96"><rect width="96" height="96" rx="22" fill="${c}"/>` +
    `<text x="48" y="64" text-anchor="middle" font-family="Helvetica,Arial,sans-serif" font-weight="700" font-size="52" fill="#fff">${g}</text></svg>`;
}
// what the "Screen" tab mirrors: the kid's home screen (a launcher with a few apps), 360x760
function screenSvg() {
  const apps = ["com.zing.zalo", "com.android.chrome", "com.duolingo", "org.khanacademy.android", "com.google.android.apps.classroom", "com.google.android.apps.photos",
    "com.google.android.apps.maps", "com.google.android.calculator", "com.spotify.music", "com.mojang.minecraftpe", "com.roblox.client", "com.google.android.youtube"];
  const names = ["Zalo", "Chrome", "Duolingo", "Khan Academy", "Classroom", "Photos", "Maps", "Calculator", "Spotify", "Minecraft", "Roblox", "YouTube"];
  let cells = "";
  apps.forEach((p, i) => {
    const x = 28 + (i % 4) * 82, y = 218 + Math.floor(i / 4) * 100, c = BRAND[p][0];
    cells += `<rect x="${x}" y="${y}" width="56" height="56" rx="14" fill="${c}"/><text x="${x + 28}" y="${y + 38}" text-anchor="middle" font-size="30" font-weight="700" fill="#fff" font-family="Helvetica,Arial,sans-serif">${BRAND[p][1]}</text>` +
      `<text x="${x + 28}" y="${y + 76}" text-anchor="middle" font-size="11" fill="#fff" font-family="Helvetica,Arial,sans-serif">${names[i]}</text>`;
  });
  const d = new Date(NOW), hh = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
  const dock = ["com.zing.zalo", "com.android.chrome", "com.google.android.apps.photos", "com.duolingo"].map((p, i) =>
    `<rect x="${32 + i * 78}" y="560" width="52" height="52" rx="14" fill="${BRAND[p][0]}"/><text x="${58 + i * 78}" y="594" text-anchor="middle" font-size="28" font-weight="700" fill="#fff" font-family="Helvetica,Arial,sans-serif">${BRAND[p][1]}</text>`).join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="360" height="657" viewBox="0 0 360 657"><defs><linearGradient id="w" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1e3a8a"/><stop offset=".55" stop-color="#6d28d9"/><stop offset="1" stop-color="#db2777"/></linearGradient></defs>
<rect width="360" height="657" fill="url(#w)"/><text x="18" y="24" font-size="13" fill="#fff" font-family="Helvetica,Arial,sans-serif" font-weight="600">${hh}</text><text x="342" y="24" text-anchor="end" font-size="13" fill="#fff" font-family="Helvetica,Arial,sans-serif">77%</text>
<text x="180" y="112" text-anchor="middle" font-size="64" fill="#fff" font-weight="300" font-family="Helvetica,Arial,sans-serif">${hh}</text>
<text x="180" y="146" text-anchor="middle" font-size="16" fill="#e2e8f0" font-family="Helvetica,Arial,sans-serif">${d.toLocaleDateString(LANG === "vi" ? "vi-VN" : "en-US", { weekday: "long", day: "numeric", month: "long" })}</text>
<rect x="30" y="164" width="300" height="34" rx="17" fill="#ffffff33"/><text x="180" y="186" text-anchor="middle" font-size="13" fill="#fff" font-family="Helvetica,Arial,sans-serif">${LANG === "vi" ? "Còn 45 phút hôm nay" : "45 min left today"}</text>
${cells}<rect x="14" y="548" width="332" height="76" rx="26" fill="#ffffff33"/>${dock}</svg>`;
}

// ---- http ------------------------------------------------------------------------------
const TYPES = { ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8" };
function serve(name) {
  return http.createServer((req, res) => {
    const u = new URL(req.url, "http://x"), p = u.pathname;
    const cors = { "Access-Control-Allow-Origin": req.headers.origin || "*", "Access-Control-Allow-Headers": "X-KG-Token, X-KG-Name, Content-Type", "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE",
      "Access-Control-Allow-Private-Network": "true", "Access-Control-Expose-Headers": "X-KG-Seq, X-KG-Age, X-KG-Err", "Cache-Control": "no-store", Vary: "Origin" };
    const send = (code, type, body) => { res.writeHead(code, { "Content-Type": type, ...cors }); res.end(body); };
    const json = (o, code = 200) => send(code, "application/json; charset=utf-8", JSON.stringify(o));
    if (req.method === "OPTIONS") { res.writeHead(204, cors); return res.end(); }
    let body = []; req.on("data", c => body.push(c));
    req.on("end", () => {
      const text = Buffer.concat(body).toString();
      if (p === "/" || p === "/index.html") return send(200, "text/html; charset=utf-8", fs.readFileSync(path.join(UI, "index.html")));
      let m = /^\/ui\/([a-z0-9][a-z0-9-]*)\.(js|css)$/.exec(p);
      if (m) { const f = path.join(UI, "assets", m[1] + "." + m[2]); return fs.existsSync(f) ? send(200, TYPES["." + m[2]], fs.readFileSync(f)) : send(404, "text/plain", "not found"); }
      if (!p.startsWith("/api/")) return send(404, "text/plain", "not found");
      const a = p.slice(4);
      const st = () => Object.assign(stateOf(name), PATCH);
      if (a === "/hello") return json({ id: st().id, pairing: false, pin: true, pairPin: true, owner: true, mode: "owner" });
      if (a === "/state") return json(st());
      if (a === "/rules" && req.method === "PUT") { try { const r = JSON.parse(text); delete r.pin; r.pinSet = true; r.rev = Date.now(); rulesOf[name] = r; return json({ result: "ok", rules: r }); } catch { return json({ error: "bad json" }, 400); } }
      if (a === "/cmd") return json({ result: "ok" });
      if (a === "/live") return json({ result: "ok" });
      if (a === "/track") return json({ track: name === "bin" ? trackOf() : [] });
      if (a === "/device") return json(resolve(readFix("device")));
      if (a === "/files") return json(resolve(readFix("files")));
      if (a === "/icon") return send(200, "image/svg+xml", iconSvg(u.searchParams.get("pkg") || ""));
      if (a === "/screen") return send(200, "image/svg+xml", screenSvg());
      if (a === "/input") return json({ result: "ok" });
      return json({ error: "not found" }, 404);
    });
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const [name, port] of KIDS) serve(name).listen(port, "127.0.0.1", () => console.log(`kid ${name}: http://127.0.0.1:${port}/  (${LANG})`));
}
export { serve, NOW };

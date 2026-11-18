// node mcp/test.mjs: runs kidguard-mcp.mjs against a fake relay and a fake LAN phone (node:http).
import http from "node:http";
import { spawn } from "node:child_process";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";

const SERVER = fileURLToPath(new URL("./kidguard-mcp.mjs", import.meta.url));
const TOKEN = "t".repeat(48), ID = "0123456789abcdef";

function fake(mode) {   // mode "relay" (/c/<id>/...) or "lan" (/api/...)
  const seen = { puts: [], cmds: [], headers: [] };
  const rules = { rev: 5, dailyMin: 120, blocked: ["com.x"], zones: [{ name: "Home", lat: 1, lng: 2, r: 100 }], pinSet: true, bonus: { day: "2026-11-15", min: 10 } };
  const state = { day: "2026-11-15", battery: 80, charging: false, screenOn: true, current: "com.y", usedMs: 3600000, leftMs: 3600000, limitMin: 120, version: "0.9.0",
    apps: [{ pkg: "com.y", label: "Y", ms: 1800000 }, { pkg: "com.z", label: "Z", ms: 600000 }], location: { lat: 1, lng: 2, acc: 12, t: Date.now() },
    events: [{ kind: "zoneIn", t: Date.now(), text: "Home" }], requests: [{ id: "r1", kind: "time", min: 30, at: Date.now() }], rules: { ...rules, pinHash: "abc" } };
  const srv = http.createServer((q, s) => {
    let b = ""; q.on("data", (d) => (b += d)); q.on("end", () => {
      seen.headers.push(q.headers);
      const pre = mode === "relay" ? "/c/" + ID : "/api";
      const out = (c, j) => { s.writeHead(c, { "Content-Type": "application/json" }); s.end(JSON.stringify(j)); };
      if (q.headers["x-kg-token"] !== TOKEN) return out(401, { error: "bad token" });
      if (!q.url.startsWith(pre)) return out(404, { error: "nope" });
      const p = q.url.slice(pre.length);
      if (p === "/state") return out(200, mode === "relay" ? { state, rules, seen: Date.now() - 60000, pending: 0 } : { ...state, t: Date.now() });
      if (p === "/rules" && q.method === "PUT") { const j = JSON.parse(b); seen.puts.push(j); return out(200, { rev: 6 }); }
      if (p === "/cmd" && q.method === "POST") { seen.cmds.push(JSON.parse(b)); return out(200, { result: "queued" }); }
      out(404, { error: "nope" });
    });
  });
  return new Promise((res) => srv.listen(0, "127.0.0.1", () => res({ srv, seen, port: srv.address().port })));
}

function client(env) {
  const p = spawn(process.execPath, [SERVER], { env: { PATH: process.env.PATH, ...env }, stdio: ["pipe", "pipe", "inherit"] });
  let buf = "", n = 0; const waits = new Map();
  p.stdout.setEncoding("utf8");
  p.stdout.on("data", (d) => { buf += d; let i; while ((i = buf.indexOf("\n")) >= 0) { const m = JSON.parse(buf.slice(0, i)); buf = buf.slice(i + 1); waits.get(m.id)?.(m); } });
  const rpc = (method, params) => new Promise((res) => { const id = ++n; waits.set(id, res); p.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n"); });
  const tool = async (name, args = {}) => { const r = await rpc("tools/call", { name, arguments: args }); const t = r.result.content[0].text; return { err: !!r.result.isError, text: t, json: r.result.isError ? null : JSON.parse(t) }; };
  return { rpc, tool, p, note: (m) => p.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: m }) + "\n") };
}

async function suite(mode) {
  const { srv, seen, port } = await fake(mode);
  const env = mode === "relay" ? { KG_RELAY: `http://127.0.0.1:${port}/`, KG_ID: ID, KG_TOKEN: TOKEN } : { KG_URL: `http://127.0.0.1:${port}`, KG_TOKEN: TOKEN };
  const c = client(env);
  const W = n => mode === "relay" ? { answer: "an", message: "ms", lock: "lk" }[n] : n;   // relay = command codes
  try {
    const init = await c.rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "0" } });
    assert.equal(init.result.protocolVersion, "2025-06-18");
    c.note("notifications/initialized");
    assert.deepEqual((await c.rpc("ping")).result, {});
    const names = (await c.rpc("tools/list")).result.tools.map((t) => t.name).sort();
    assert.deepEqual(names, ["answer_request", "get_rules", "grant_time", "guide", "kid_status", "recent_events", "send_command", "update_rules", "usage_today"]);
    assert.equal((await c.rpc("nope")).error.code, -32601);

    const st = (await c.tool("kid_status")).json;
    assert.equal(st.battery_pct, 80); assert.equal(st.used_min, 60); assert.equal(st.left_min, 60); assert.equal(st.current_app, "Y");
    assert.equal(st.open_requests[0].id, "r1"); assert.equal(st.last_zone_event.text, "Home");
    if (mode === "relay") assert.equal(st.online, true);

    const u = (await c.tool("usage_today")).json;
    assert.deepEqual(u.apps.map((a) => a.minutes), [30, 10]);

    const gr = (await c.tool("get_rules")).json;
    assert.equal(gr.dailyMin, 120); assert.ok(!("pinHash" in gr) && !("pinSet" in gr));

    await c.tool("update_rules", { patch: { dailyMin: 90, blocked: ["com.q"], bonus: null } });
    const put = seen.puts.at(-1);
    assert.equal(put.dailyMin, 90); assert.deepEqual(put.blocked, ["com.q"]); assert.ok(!("bonus" in put));
    assert.deepEqual(put.zones, [{ name: "Home", lat: 1, lng: 2, r: 100 }], "whole object sent (zones kept)");
    assert.ok(!("pinHash" in put) && !("pinSet" in put));
    assert.equal((await c.tool("update_rules", { patch: { pinHash: "x" } })).err, true);
    assert.equal((await c.tool("update_rules", { patch: { rev: 1 } })).err, true);

    await c.tool("grant_time", { minutes: 15 });
    assert.deepEqual(seen.puts.at(-1).bonus, { day: "2026-11-15", min: 25 });
    assert.equal(seen.puts.at(-1).dailyMin, 120);
    assert.equal((await c.tool("grant_time", { minutes: 9999 })).err, true);

    const n0 = seen.puts.length;
    await c.tool("answer_request", { req: "r1", ok: true, min: 20 });
    assert.deepEqual(seen.cmds.at(-1), { type: W("answer"), req: "r1", ok: true, min: 20 });
    assert.equal(seen.puts.length, n0 + 1); assert.deepEqual(seen.puts.at(-1).bonus, { day: "2026-11-15", min: 30 });
    assert.equal((await c.tool("answer_request", { req: "nope", ok: true })).err, true);

    await c.tool("send_command", { type: "message", text: "dinner" });
    assert.deepEqual(seen.cmds.at(-1), { type: W("message"), text: "dinner" });
    await c.tool("send_command", { type: "lock" });
    assert.deepEqual(seen.cmds.at(-1), { type: W("lock") });
    const nc = seen.cmds.length;
    for (const bad of ["release", "adbShell", "reboot", "clearData", "unpair", "unpairAll", "pinSet", "relay", "openPairing", "wake", "listen", "toString"]) {
      const r = await c.tool("send_command", { type: bad, cmd: "id", pin: "1234" });
      assert.equal(r.err, true, bad + " must be rejected");
    }
    assert.equal(seen.cmds.length, nc, "no forbidden command reached the phone");
    assert.ok(seen.headers.every((h) => h["x-kg-name"] === "AI assistant (MCP)"));

    // bad token -> tool error, not a crash
    const bad = client({ ...env, KG_TOKEN: "wrong" });
    assert.equal((await bad.tool("kid_status")).err, true); bad.p.kill();
    const none = client({});
    assert.equal((await none.tool("kid_status")).err, true); none.p.kill();
    console.log("ok", mode);
  } finally { c.p.kill(); srv.close(); }
}

await suite("relay");
await suite("lan");
console.log("all passed");

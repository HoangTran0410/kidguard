// npm test: starts `wrangler dev` on a free-ish port, waits until it answers, runs relay.test.mjs, stops wrangler.
import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:http";
import { generateKeyPairSync, verify } from "node:crypto";
import { writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
// contract test first (plain node, no wrangler)
if (spawnSync(process.execPath, [new URL("./contract.test.mjs", import.meta.url).pathname], { stdio: "inherit" }).status) process.exit(1);
const PORT = process.env.PORT || "8793", MOCK = 8794;

// Fake Google: a throwaway RSA service account, an OAuth token endpoint that checks the JWT signature, and an FCM
// send endpoint that records messages (GET /_sent). Token "dead..." answers 404 like an uninstalled app.
const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const sa = { project_id: "kg-test", client_email: "kg@kg-test.iam.gserviceaccount.com", token_uri: `http://127.0.0.1:${MOCK}/token`,
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }) };
const sent = [];
createServer((q, s) => {
  let b = ""; q.on("data", d => (b += d)); q.on("end", () => {
    const out = (st, o) => { s.writeHead(st, { "content-type": "application/json" }); s.end(JSON.stringify(o)); };
    if (q.url === "/token") {
      const jwt = new URLSearchParams(b).get("assertion") || "", [h, c, g] = jwt.split(".");
      const ok = g && verify("sha256", Buffer.from(h + "." + c), publicKey, Buffer.from(g, "base64url"));
      return ok ? out(200, { access_token: "at-1", expires_in: 3600 }) : out(400, { error: "bad jwt" });
    }
    if (q.url === "/v1/projects/kg-test/messages:send") {
      if (q.headers.authorization !== "Bearer at-1") return out(401, {});
      const m = JSON.parse(b).message; sent.push(m);
      return m.token.startsWith("dead") ? out(404, { error: { status: "NOT_FOUND" } }) : out(200, { name: "x" });
    }
    if (q.url === "/_sent") return out(200, sent);
    out(404, {});
  });
}).listen(MOCK, "127.0.0.1");
const envFile = join(mkdtempSync(join(tmpdir(), "kg-")), ".env");
writeFileSync(envFile, `FCM_SA='${JSON.stringify(sa)}'\nFCM_URL=http://127.0.0.1:${MOCK}\n`);

// fresh local Durable Object storage every run: tests with fixed kid ids must not see the last run's queue / rules
rmSync(".wrangler/state", { recursive: true, force: true });
const dev = spawn("npx", ["wrangler", "dev", "--port", PORT, "--ip", "127.0.0.1", "--env-file", envFile], { stdio: ["ignore", "pipe", "pipe"], detached: true });
let log = "";
dev.stdout.on("data", d => (log += d));
dev.stderr.on("data", d => (log += d));
const stop = () => { try { process.kill(-dev.pid, "SIGTERM"); } catch {} };
process.on("exit", stop);

let up = false;
for (let i = 0; i < 120 && !up; i++) {
  try { up = (await fetch(`http://127.0.0.1:${PORT}/`)).ok; } catch {}
  if (!up) await new Promise(r => setTimeout(r, 500));
}
if (!up) { console.error("wrangler dev did not start:\n" + log.slice(-2000)); process.exit(1); }

const t = spawn(process.execPath, ["test/relay.test.mjs"], { stdio: "inherit", env: { ...process.env, PORT, MOCK: String(MOCK) } });
t.on("exit", code => { if (code) console.error("--- wrangler dev log (last 3000 chars) ---\n" + log.slice(-3000)); process.exit(code ?? 1); });

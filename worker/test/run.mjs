// npm test: starts `wrangler dev` on a free-ish port, waits until it answers, runs relay.test.mjs, stops wrangler.
import { spawn } from "node:child_process";
const PORT = process.env.PORT || "8793";
const dev = spawn("npx", ["wrangler", "dev", "--port", PORT, "--ip", "127.0.0.1"], { stdio: ["ignore", "pipe", "pipe"], detached: true });
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

const t = spawn(process.execPath, ["test/relay.test.mjs"], { stdio: "inherit", env: { ...process.env, PORT } });
t.on("exit", code => process.exit(code ?? 1));

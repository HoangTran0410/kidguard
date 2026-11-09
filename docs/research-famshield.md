# FamShield review (2026-11-09)

Competitor study of FamShield (web console famshield.ttcantho.io.vn, agent repo github.com/nntu/famshield-agent, MIT).
Static reading only: nothing of theirs was built or run. Paths below are inside their repo.

## What it is
- Windows-only parental control: Rust Windows Service (SYSTEM) + per-user session helper + tray app.
- Filtering: local DNS proxy + NRPT, WFP rules, SafeSearch / YouTube-restricted rewrite (`dns/src/safesearch.rs`).
- Local web admin on `127.0.0.1:38123`, SQLite storage, Argon2id PIN + TOTP + recovery key.
- Cloud mode: NATS over WebSocket via Cloudflare Tunnel; the server (`nntu/famshield-server`, not read) is partly mock
  per their own `docs/14-GAP-ANALYSIS-AND-ROADMAP.md`.

## In code but not in their README
| Feature | State | Where |
|---|---|---|
| Keystroke keyword monitoring (self-harm / bullying / adult / profanity, Telex/VNI aware), clear screenshot on match | real, wired | `session/src/keyboard_monitor.rs`, `core/src/keyword.rs` |
| Blurred screenshot "evidence" on block / time request, 48 h TTL | real | `session/src/capture.rs`, `storage/src/engine/evidence.rs` |
| Monitor-only, silent mode (no toasts), timed filter pause | real | `local_admin/.../handlers/settings.rs` |
| Anti-tamper: service <-> session watchdog, NRPT self-heal, WFP blocks DoT/OpenVPN/WireGuard, Chrome/Edge DoH off, PIN/TOTP-gated uninstall | real | `service/src/watchdog.rs`, `windows/src/wfp.rs`, `nrpt.rs` |
| Signed update (ECDSA P-256) with rollback + offline USB update | real (embedded key not checked) | `updater/` |
| Per-app daily quota | real | `service/src/controller.rs` |
| Install approval (approve/deny) | stub: handlers only, nothing submits requests | `windows/src/installer.rs` |
| Emergency lock-all, co-parenting, Telegram, child quota profiles | web UI / i18n only, backend unconfirmed | console bundle |
| "AI filtering" | does not exist (domain lists + keyword automaton) | - |

## What KidGuard took from it (all merged 2026-11-09)
- Pause all / resume all kids on the overview (`overview.js`).
- Telegram alerts from the relay worker (secret `TG_BOT`, off when unset).
- Starting levels Light / Balanced / Strict for a kid with untouched rules.
- Rule `monitor` (measure and report `state.wouldBlock`, never block).
- Safe DNS presets that force SafeSearch, Chrome/Edge DoH off via managed config, rule `noVpn`.

## Not taken
- Keystroke monitoring: privacy cost too high for a product whose rule is "the child always knows". Not planned.
- Blurred screenshot on blocked attempt: considered, not built.
- Windows agent for KidGuard: assessed as feasible (same protocol / relay / page, child must be a standard user,
  code signing needed against SmartScreen), not started.

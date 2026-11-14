@AGENTS.md

## Claude Code specifics
- Start from `docs/flows.md` (how it works) and `docs/decisions/` (one file per area; index in `README.md`) (what's settled) instead of re-reading big files;
  `HomeActivity.kt` (~1500 lines) and `docs/protocol.md` (~900) are read by grep + offset, never whole.
- Builds and the relay test take minutes: run them in the background.
- Device testing: physical test phones belong to the owner; check the memory notes on which devices adb may touch
  before any `adb` command, and never release / unpair / clear data on an enrolled phone.
- Sub-agents: give each one narrow area and a path:line report; tell them to skip `.claude/worktrees/`.
- Learned something durable (decision, rejected approach, OEM quirk)? One line in `docs/decisions/<area>.md` (see its README), same commit.

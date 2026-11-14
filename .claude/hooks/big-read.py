#!/usr/bin/env python3
# PreToolUse(Read): files that must be read by grep + offset, never whole (CLAUDE.md / AGENTS.md rule, enforced here).
# A Read of one of them without `limit` (or with limit > MAX) is blocked with a hint. Exit 2 = block, stderr goes to the agent.
import json, sys

MAX = 200
BIG = {
    "docs/protocol.md": 'grep -n "^## \\|^### " docs/protocol.md, then Read with offset/limit (or grep the field/route name)',
    "kotlin/dev/kidguard/launcher/HomeActivity.kt": "grep -n the function name, then Read with offset/limit",
    "ui/install.html": "inline bundles, lines of 13-25k chars: grep -n -o with a short context only",
    "ui/qr.html": "inline bundles, lines of 13-25k chars: grep -n -o with a short context only",
}

try:
    inp = json.load(sys.stdin).get("tool_input", {})
except Exception:
    sys.exit(0)
path = inp.get("file_path", "")
if "/.claude/worktrees/" in path:
    sys.exit(0)
for tail, hint in BIG.items():
    if path.endswith(tail):
        lim = inp.get("limit")
        if lim is None or lim > MAX:
            print(f"Blocked: {tail} is too big to read whole (token cost). {hint}. Max limit {MAX}.", file=sys.stderr)
            sys.exit(2)
sys.exit(0)

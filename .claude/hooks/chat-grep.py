#!/usr/bin/env python3
"""Search past chats of this project cheaply: only the OWNER's messages (no tool dumps), dated, one line each.
  python3 .claude/hooks/chat-grep.py 'momo|ngân hàng' [max=30]
Use before saying "that was a different bug" / "never reported" about something from an earlier session."""
import glob, json, os, re, sys

pat = re.compile(sys.argv[1], re.I)
mx = int(sys.argv[2]) if len(sys.argv) > 2 else 30
root = os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd())
d = os.path.expanduser("~/.claude/projects/" + root.replace("/", "-"))
rows = []
for f in glob.glob(d + "/*.jsonl"):
    for l in open(f, encoding="utf-8", errors="ignore"):
        if '"type":"user"' not in l or not pat.search(l): continue
        try: j = json.loads(l)
        except Exception: continue
        c = j.get("message", {}).get("content")
        if isinstance(c, str) and not c.startswith(("<", "This session is being continued")) and pat.search(c):
            rows.append((j.get("timestamp", "")[:16], os.path.basename(f)[:8], c.replace("\n", " ")[:260]))
for r in sorted(set(rows))[-mx:]: print(*r)

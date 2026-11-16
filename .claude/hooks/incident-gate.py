#!/usr/bin/env python3
"""Stop: a bug the owner reported got a code change but no line in docs/decisions -> block once and say so.
Trigger: a user message in this session has bug words AND the working tree / today's commits touch code
(android/, ui/, relay/) but nothing in docs/decisions. Blocks at most once per stop chain (stop_hook_active)."""
import json, re, subprocess, sys

BUG = re.compile(r"\b(lỗi|kẹt|stuck|crash|hỏng|bug|ko được|không được|ko hiện|không hiện|treo|đơ)\b", re.I)


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout


def main():
    try: d = json.load(sys.stdin)
    except Exception: return
    if d.get("stop_hook_active"): return
    said = False
    try:
        for l in open(d.get("transcript_path", ""), encoding="utf-8"):
            if '"type":"user"' not in l: continue
            m = json.loads(l).get("message", {})
            c = m.get("content")
            if isinstance(c, str) and not c.startswith("<") and BUG.search(c): said = True
    except Exception: return
    if not said: return
    changed = set(sh("git", "diff", "--name-only", "HEAD").split()) | set(
        sh("git", "log", "--since=midnight", "--name-only", "--pretty=format:").split())
    code = [f for f in changed if f.startswith(("android/", "ui/", "relay/")) and not f.endswith(".md")]
    docs = [f for f in changed if f.startswith("docs/decisions/")]
    if code and not docs:
        print(json.dumps({"decision": "block", "reason":
            "Owner reported a bug this session and code changed, but docs/decisions has no new line. Add one line to "
            "docs/decisions/<area>.md: date, device, symptom, root cause, fix, how it is tested (or say why none)."}))


main()

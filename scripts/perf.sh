#!/usr/bin/env bash
# Launcher perf helper (docs/launcher-perf.md). Usage:
#   scripts/perf.sh <serial> on        turn the kgperf probe on (setprop, survives until reboot)
#   scripts/perf.sh <serial> off       turn it off
#   scripts/perf.sh <serial> reset     clear frame stats + logcat (do this right before the action)
#   scripts/perf.sh <serial> frames    frame stats since reset (total, janky %, p50/p90/p99)
#   scripts/perf.sh <serial> log       probe lines (kgperf) + slow renders (KG.perf) since reset
# Measure a RELEASE build (debug isn't compiled the same way). Read docs first: which phones adb may touch.
set -euo pipefail
S="${1:?serial}"; CMD="${2:?on|off|reset|frames|log}"; PKG="${PKG:-dev.kidguard}"
a() { adb -s "$S" "$@"; }
case "$CMD" in
  on)     a shell setprop log.tag.kgperf VERBOSE; echo "kgperf on" ;;
  off)    a shell setprop log.tag.kgperf '""'; echo "kgperf off" ;;
  reset)  a shell dumpsys gfxinfo "$PKG" reset >/dev/null; a logcat -c ;;
  frames) a shell dumpsys gfxinfo "$PKG" | grep -E "Total frames|Janky frames|50th|90th|99th|Slow UI thread|Slow bitmap|Slow draw" ;;
  log)    a logcat -d -s kgperf:V KG.perf:I | grep -v "^-----" ;;
  *) echo "unknown: $CMD" >&2; exit 2 ;;
esac

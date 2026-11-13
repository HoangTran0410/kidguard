#!/usr/bin/env bash
# Page scripts: runtime bugs `node --check` misses (2026-11-13: a `const` reassigned in ruleChips blanked the Rules,
# Location and Settings tabs). esbuild flags assign-to-constant, duplicate keys/cases, `x == NaN`, typeof typos, ...
# The scripts share one global scope, so they are checked concatenated in index.html order.
set -euo pipefail
cd "$(dirname "$0")/.."
files=$(grep -o 'src="/ui/[a-z0-9-]*\.js"' ui/index.html | sed 's|src="/ui/|ui/assets/|; s|"$||')
esb=relay/node_modules/.bin/esbuild
[ -x "$esb" ] || esb="npx --yes esbuild@0.25.4"
out=$(cat $files | $esb --loader=js --log-level=warning --color=false 2>&1 >/dev/null || true)
if [ -n "$out" ]; then
  echo "$out"
  echo "check-js: esbuild warnings in ui/assets (line numbers are in the concatenated file: grep the code shown)" >&2
  exit 1
fi
echo "Page script check ok: $(echo $files | wc -w | tr -d ' ') files"

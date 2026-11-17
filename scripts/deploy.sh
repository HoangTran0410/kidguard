#!/usr/bin/env bash
# Deploys the maintainer's relay (Worker) AND the website incl. the signed UI pack (Pages) in one go, in the right order:
#   1. relay first: a page that talks to a new relay endpoint must never reach a relay that lacks it
#   2. then Pages: website/ui/{manifest.json,manifest.sig,pack-<ver>.zip}; phones fetch it the next time the page is opened
# Needs the maintainer's Cloudflare login (wrangler) and android/app/ui-pack-key.pem. NOT run by build.sh or CI.
#   scripts/deploy.sh              relay + Pages
#   scripts/deploy.sh --pages      only the website + pack (UI-only change: no relay change)
#   scripts/deploy.sh --dry        build + verify the pack and print what would run
set -euo pipefail
cd "$(dirname "$0")/.."
PAGES_PROJECT="${PAGES_PROJECT:-kid-guard}"
DRY=0; ONLY_PAGES=0
for a in "$@"; do case "$a" in --dry) DRY=1;; --pages) ONLY_PAGES=1;; *) echo "unknown option $a" >&2; exit 1;; esac; done

node scripts/check-search.mjs && node scripts/check-assets.mjs && scripts/check-js.sh
node scripts/ui-pack.mjs                                  # website/ui/ (same content = same seq, nothing to redo)
node scripts/ui-pack.mjs --check

if [ "$DRY" = 1 ]; then
  [ "$ONLY_PAGES" = 1 ] || echo "would run: (cd relay && npm ci && npx wrangler deploy)"
  echo "would run: npx wrangler pages deploy website --project-name $PAGES_PROJECT"; exit 0
fi
if [ "$ONLY_PAGES" = 0 ]; then
  (cd relay && npm ci --silent --no-audit --no-fund && npx wrangler deploy)
  # a deploy can "succeed" and the worker die 1 s later (INCIDENT 2026-11-17: abuse-report kill): check it really answers
  sleep 15; scripts/relay-health.sh || { echo "Relay deployed but NOT answering: Pages not deployed. See above." >&2; exit 1; }
fi
npx --yes wrangler pages deploy website --project-name "$PAGES_PROJECT"
echo "Pages deployed. Phones pick the new page up when it is opened (or: Settings > Update the page)."
echo "Check: curl -s https://$PAGES_PROJECT.pages.dev/ui/manifest.json"

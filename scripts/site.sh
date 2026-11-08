#!/usr/bin/env bash
# Deploy kid-guard.pages.dev. The APKs are served from the site itself (/dl/*.apk): a github.com link is opened by the
# GitHub app on phones that have it, whose download never finishes. Run after scripts/build.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p website/dl
cp build/kidguard.apk build/kidguard-parent.apk website/dl/
ls -l website/dl
npx wrangler pages deploy website --project-name kid-guard --branch main

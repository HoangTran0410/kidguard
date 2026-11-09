#!/usr/bin/env bash
# Deploys your own KidGuard relay (relay/) to YOUR Cloudflare account (free plan) and prints its address.
# Your kids' data then only goes through your own account. Needs Node.js 18+; opens a browser once to log in.
#   scripts/relay.sh            deploy (or update) the relay
#   scripts/relay.sh --delete   remove it from your account
set -euo pipefail
cd "$(dirname "$0")/../relay"

command -v node >/dev/null || { echo "Need Node.js 18+ (https://nodejs.org)"; exit 1; }
[ "$(node -p 'process.versions.node.split(".")[0]')" -ge 18 ] || { echo "Need Node.js 18+, found $(node -v)"; exit 1; }

echo "1/3 Installing the relay's tools (first time ~1 min)…"
npm ci --silent --no-audit --no-fund

echo "2/3 Cloudflare login (a browser window opens if you are not logged in yet)…"
npx wrangler whoami 2>/dev/null | grep -q "associated with the email" || npx wrangler login

if [ "${1:-}" = "--delete" ]; then npx wrangler delete --name kidguard; exit; fi

echo "3/3 Deploying…"
# first deploy on a new account may ask you to pick a workers.dev subdomain: answer it, it is shown in the address
out=$(npx wrangler deploy 2>&1 | tee /dev/stderr)
url=$(printf '%s\n' "$out" | grep -Eo 'https://[A-Za-z0-9.-]+\.workers\.dev' | head -1)
[ -n "$url" ] || { echo "Deployed, but no address found in the output above. Look for https://kidguard.<you>.workers.dev"; exit 1; }

# Optional Telegram alerts: TG_BOT_TOKEN=<token from @BotFather> scripts/relay.sh (sets the secret, then registers the webhook)
if [ -n "${TG_BOT_TOKEN:-}" ]; then
  printf '%s' "$TG_BOT_TOKEN" | npx wrangler secret put TG_BOT >/dev/null
  hk=$(printf 'tg:%s' "$TG_BOT_TOKEN" | node -e 'process.stdout.write(require("crypto").createHash("sha256").update(require("fs").readFileSync(0)).digest("hex").slice(0,32))')
  curl -fsS "https://api.telegram.org/bot${TG_BOT_TOKEN}/setWebhook" -d "url=$url/tg/$hk" >/dev/null && echo "Telegram alerts on (webhook registered)." || echo "Telegram: setWebhook failed, check the token."
fi

cat <<EOF

Relay ready: $url

Next (parent page, on home Wi-Fi): Cài đặt → relay Cloudflare Worker → paste, press Bật:
  $url
Free plan: 100,000 requests/day, each kid's phone uses ~4,300. Data stays in your Cloudflare account.
Update later: run this script again. Remove: scripts/relay.sh --delete
EOF

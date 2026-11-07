# Deploys your own KidGuard relay (worker/) to YOUR Cloudflare account (free plan) and prints its address.
# Same as scripts/relay.sh. Needs Node.js 18+; opens a browser once to log in.
#   powershell -ExecutionPolicy Bypass -File scripts\relay.ps1            deploy (or update)
#   powershell -ExecutionPolicy Bypass -File scripts\relay.ps1 -Delete    remove it from your account
param([switch]$Delete)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\worker")

if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Write-Host "Need Node.js 18+ (https://nodejs.org)"; exit 1 }
if ([int](node -p "process.versions.node.split('.')[0]") -lt 18) { Write-Host "Need Node.js 18+, found $(node -v)"; exit 1 }

Write-Host "1/3 Installing the relay's tools (first time ~1 min)..."
npm ci --silent --no-audit --no-fund
if ($LASTEXITCODE) { exit 1 }

Write-Host "2/3 Cloudflare login (a browser window opens if you are not logged in yet)..."
$who = (npx wrangler whoami 2>&1) -join "`n"
if ($who -notmatch "associated with the email") { npx wrangler login; if ($LASTEXITCODE) { exit 1 } }

if ($Delete) { npx wrangler delete --name kidguard; exit $LASTEXITCODE }

Write-Host "3/3 Deploying..."
# first deploy on a new account may ask you to pick a workers.dev subdomain: answer it
npx wrangler deploy 2>&1 | Tee-Object -Variable out | Out-Host
if ($LASTEXITCODE) { exit 1 }
$m = [regex]::Match(($out -join "`n"), 'https://[A-Za-z0-9.-]+\.workers\.dev')
if (-not $m.Success) { Write-Host "Deployed, but no address found above. Look for https://kidguard.<you>.workers.dev"; exit 1 }
$url = $m.Value

Write-Host ""
Write-Host "Relay ready: $url"
Write-Host ""
Write-Host "Next (parent page, on home Wi-Fi): Cai dat -> relay Cloudflare Worker -> paste, press Bat:"
Write-Host "  $url"
Write-Host "Free plan: 100,000 requests/day, each kid's phone uses ~4,300. Data stays in your Cloudflare account."
Write-Host "Update later: run this script again. Remove: scripts\relay.ps1 -Delete"

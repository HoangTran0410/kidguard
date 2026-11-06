# Privacy

KidGuard has no accounts, no analytics, no ads and no server of its own. Data stays on the phones and on whatever
relay **you** choose. This page says exactly what is kept where.

## Use it only on a child's phone, and tell the child

KidGuard is for a parent or guardian managing their own minor child's phone. Do not install it on an adult's phone, a
partner's or an employee's: that is covert surveillance and is illegal in most countries. KidGuard is built to be
visible: the child's screen shows its rules ("Luật của con") and a log of what the parents changed, viewing the screen
shows a notice, and listening always beeps, shows a notification and Android's mic dot (it refuses to start if the
child's notifications are turned off). Talk to your child about it before you set it up.

## What is stored where

| Where | What | How long |
|---|---|---|
| **Child's phone** (KidGuard app storage) | Rules, usage per app (from Android), the last 7 days of history, location history if turned on (7 days), SOS / time requests, alerts (7 days), the log (last 50 lines), pairing tokens | Until KidGuard is removed |
| **Parent's browser** | The kid list with each kid's address and token (`localStorage`), rule templates | Until you forget the kid / clear site data |
| **Parent app** (`kidguard-parent.apk`) | The kid list and tokens, the last state snapshot, one snapshot per day per kid (history, default unlimited, settable 30 / 90 / 365 days) | Until you remove the kid or the app |
| **Cloudflare Worker relay** (only if you use one) | Per kid: a hash of the token, the latest state (includes usage, battery, location, location history if on, the log), rules (PIN only as a salted hash), queued commands, app icons, the parent app's push token(s). The live screen is kept in memory only, never stored | Overwritten on every sync; kept until the Worker's Durable Object is deleted |
| **Google (Firebase push)** (only parent app + relay + push set up) | A data message "SOS / request / alert for kid <random relay id> at <time>". No names, notes, messages, places or locations | Google's FCM retention |
| **OpenStreetMap** | Map tiles for the area you look at (your browser asks for them, like any map site) | OSM's policy |

On home Wi-Fi or Tailscale nothing leaves your network except map tiles. Listening (microphone) never goes through
the relay.

## Who controls it

You. The relay is a Cloudflare Worker: whoever runs it can read what it stores. **Run your own** (free plan, see
README → *Cloudflare Worker*) if you don't want to trust someone else's. The shared relay in the official release is
run by the author as a convenience, as is, with no guarantee, for a small number of families.

## Removing everything

1. Parent page → Settings → **Gỡ quyền quản lý** (remove management): every restriction is lifted and the tokens on
   the phone are erased. Then uninstall KidGuard on the child's phone like any app.
2. Parent app: remove each kid (or uninstall the app). Browser: "Quên" (forget) each kid, or clear the site's data.
3. Relay: if it is your own Worker, delete it (`npx wrangler delete`) or its Durable Object. On the shared relay the
   kid's last state stays until it is cleared by the operator: open an issue with your relay id to have it deleted.

## No warranty

KidGuard is open source under the MIT license, provided as is. It does not make a phone safe on its own and can be
removed by a factory reset from recovery.

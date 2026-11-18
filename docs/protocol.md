# KidGuard protocol (v0.4)

The child app, the relay worker, the parent page and the installer all follow this file. If you need to change a
shape, say so in your report instead of silently diverging.

## Rules JSON (parent -> phone). New fields in v0.2 marked NEW. Unknown fields are ignored; MISSING fields reset to their default (full replace: `hidden`, `groups`, `launcher`, `lockInfo`, `zones`, `chores`... are wiped by a partial PUT, so always send the whole object).

```jsonc
{
  "rev": 1793940898493,            // set by the phone / worker, newest wins. A rules push (LAN PUT or relay) applies only if its rev >= the stored one; LAN saves stamp rev = max(old+1, phone clock), so a phone clock ahead of the worker's makes later relay edits lose until the worker's rev catches up
  "dailyMin": 120,                 // 0 = no daily limit
  "dailyByDay": [120,120,120,120,120,240,240], // NEW optional: Monday..Sunday; when length 7 it replaces dailyMin for that day; 0 = no limit that day
  "appMin": {"com.android.chrome": 15},
  "blocked": ["com.google.android.youtube"],
  "allow": ["com.duolingo"],       // never blocked by daily / bedtime / schedules / pause / new
  "bedtime": {"on": true, "start": "21:30", "end": "06:30"},
  "schedules": [                   // NEW: like bedtime but on chosen days; window may cross midnight (it belongs to the day it starts)
    {"name": "Giờ học", "on": true, "days": [1,2,3,4,5], "start": "07:00", "end": "11:30"}   // days: ISO 1=Mon..7=Sun
  ],
  "pausedUntil": 0,
  "bonus": {"day": "2026-11-06", "min": 30},   // extra minutes on top of that day's limit; ignored when the day has no limit (base 0)
  "blockNew": true,                // NEW: apps installed after setup stay blocked (reason "new") until approved
  "approved": ["com.new.game"],    // NEW: approved new apps
  "lockLocation": true,            // NEW (default true): location forced on, child cannot change location settings.
                                   //      Basic mode with `caps.wss`: Guard re-writes Settings.Secure location_mode when it goes off (observer + tick); release stops it
  "noAirplane": true,              // NEW (default true): airplane mode disallowed
  "showWatching": true,            // v0.4 (default true): child sees the "watching / controlling" notification
  "kidClean": false,               // v0.9.18 (default false): the child gets a "Dọn dẹp" tile on the main screen = Policy.clearCaches only (once per 10 min, never bgLimit)
  "watch": {"notif": {"on": false, "kw": false, "apps": "all", "list": []}, "words": [], "cam": false, "sms": false},   // 0.9.29 (default all off): what the parent may read. Each key is a `Monitoring` registry entry the child is told about (see "Monitoring"). `notif.kw` = only notifications with a word; `notif.apps` = "all"|"only"|"except" + package `list` (<= 200). `words` <= 30 x 40 chars. A mode cannot change it
  "monitor": false,                // v0.9.11 (default false): "monitor only". Phone still measures + reports; decide() still runs but NOTHING is enforced (no suspend / bounce / grey launcher / heads-up warnings). Device-level restrictions (Policy.apply: noInstall, DNS, location lock, ...) stay as configured. Turning it on unsuspends our suspensions on the next tick
  "autoUpdate": true,              // v0.9 (default true): daily self-update from GitHub releases (see "Self-update")
  "safeDns": "",                   // NEW: "" = off, else a Private DNS host, e.g. "family.adguard-dns.com",
                                   //      "family.cloudflare-dns.com", "family-filter-dns.cleanbrowsing.org"
                                   //      Basic mode with `caps.wss`: Guard writes Settings.Global private_dns_mode=hostname + private_dns_specifier itself
                                   //      (no DPM), re-applies on drift (ContentObserver + tick), restores the prior values on pause / "" / release.
                                   //      Also (Device Owner): Chrome/Edge get managed config DnsOverHttpsMode=off while set, cleared when "" / release.
  "safeDnsPauseUntil": 0,          // epoch ms (parent clock), 0 = none. While now < it the phone acts as if safeDns were "":
                                   //      Private DNS opportunistic, Chrome/Edge DoH config cleared. Guard's 10 s tick re-applies safe DNS
                                   //      by itself at expiry (no rules change needed). The page's "Pause 30 min" sets now+30 min.
  "tsKeep": false,                 // NEW 0.9.12 (default false): Ts.keep brings a dropped Tailscale back (CONNECT_VPN broadcast, then open once on home); always on for a Tailscale KidGuard installed (tsWanted)
  "calls": {"block": "off", "allow": []},   // NEW 2026-11-15 (default off): incoming-call screening, see "Calls" below. Not in the timed-mode override list
  "noVpn": false,                  // NEW (default false): DISALLOW_CONFIG_VPN (Device Owner); also kept while our Tailscale access needs it
  "noIncognito": false,            // 0.9.24 (default false, Device Owner): Chrome/Edge managed config IncognitoModeAvailability="1" (no incognito tabs)
  "webBlock": [],                  // 0.9.24 hosts (max 300, cleaned by WebLogic.host: no scheme/path/www., subdomains included). ["*"] = every
                                   //      site except webAllow ("only allowed sites"). Chrome/Edge managed config URLBlocklist (String[]), Device Owner only.
  "webAllow": [],                  // 0.9.24 hosts, Chrome/Edge URLAllowlist (sent only with a non-empty webBlock; the more specific entry wins).
                                   //      All three are one bundle per browser with DnsOverHttpsMode (Policy.applyBrowsers); release clears it.
                                   //      Timed modes may override them. Other browsers ignore them: state `webOther`.
  "frpIds": [],                    // NEW (default []): Google account IDs (digits, 10..30 chars, max 5) for factory reset protection
                                   //   (Device Owner, Android 11+: setFactoryResetProtectionPolicy); [] = policy cleared. Undone in release.
  "noInstall": false, "noDebug": false,
  "pinShot": true,                 // NEW (default true): 3rd/6th/9th wrong on-device admin PIN / code in a row (>= 2 min apart) ->
                                   //      one front-camera JPEG (long side <= 960 px), told on the PIN dialog; the newest `tamper`
                                   //      event gets `"shot": "<16 hex>"`. Kept 7 days / 20 on the phone, deleted by release.
                                   //      LAN `GET /api/shot/<id>` -> image/jpeg (404 gone); relay: command `shotUp {shot: <id>}` ->
                                   //      the phone posts it on the file channel (fid = id, to = parent, n = 1). Device Owner grants
                                   //      CAMERA; basic mode asks once after a right admin PIN. Not in the Home flavor.
  "accPause": ["com.VCB", "…"],    // NEW: apps that refuse to run while any accessibility service is on (banks by default; parents add any package). The phone also adds new entries from kid-guard.pages.dev/data/bank-apps.json once a day (never re-adds one the parent removed); that file is `{"v":1,"apps":[{"pkg","name","acc":true,"vpn":false}],"drop":[]}`. Absent = the phone's
  "vpnPause": ["xyz.be.cake"],    // NEW (v0.9.13): apps that refuse to run with a VPN up: Tailscale DISCONNECT_VPN while used (owner mode also lifts always-on VPN, put back after); independent of accPause. Absent = ["xyz.be.cake"]. Also fed by the website list (apps[].vpn).
  "devPause": ["vms.com.vn.mymobifone"], // NEW (0.9.14): apps that refuse USB / wireless debugging: Developer options + adb off while used, exactly what was on comes back after (DevOpts.pause); the launcher holds the start until adbd is down. Absent = old phone: accPause also pauses debugging
  "tcpPause": ["vms.com.vn.mymobifone"], // NEW (2026-11-13): apps that also refuse adb over the network: `adb usb` while used (service.adb.tcp.port -> 0). The port is NOT restored on mobile data; Shell reopens 5555 (rule-less adbTcp) once on Wi-Fi. Kid home asks first (what goes off, how it comes back). Absent = old phone: devPause also closed the port
                                   //      default list (AccPauseLogic.DEFAULT, ~15 VN banks / wallets); [] = feature off; max 100.
                                   //      Owner mode + WRITE_SECURE_SETTINGS only: when one opens (launcher tap or its window) the
                                   //      phone removes its own accessibility service, and puts it back once none has been in front
                                   //      for 10 min (their background processes are killed then). Nothing for release to undo
                                   //      (release forgets the pause; the service is removed by release anyway).
  "pinHash": "…",                  // the page may send "pin": "1234" instead; phone/worker store only the hash
  "mode": {"name": "Sick day", "until": 1790000000000, "rules": {"dailyMin": 240}}, // NEW (0.9.22) timed mode, optional, see below
}
```

**Timed mode (`mode`, 0.9.22).** The rules above are the NORMAL rules. While `now < mode.until` (epoch ms, one instant: no timezone on the
phone side) the phone enforces the normal rules with every key of `mode.rules` laid over them (whole value replaces, e.g. `bedtime`
as a whole object). When `until` passes the override is ignored at once (`Rules.effective`), works offline, and the next tick removes `mode`
from the stored rules (rev bumped; the page then shows no `mode`). Allowed keys in `mode.rules` (others are dropped on parse, so the PIN,
`rev`, lost mode, zones, DNS, launcher can never be overridden): `dailyMin dailyByDay appMin blocked allow bedtime bedByDay schedules
groups blockNew noInstall monitor windDown oneMore`. `name` 1-24 chars and `until` > 0 are required, else the whole `mode` is dropped.
`state.rules` and `GET /api/rules` carry the NORMAL rules + `mode` (never the laid-over copy), so a full-replace PUT from the page or a
watch never bakes the override in; a PUT without `mode` ends it ("End now"). Release clears it. Page limit: `until` within 14 days.
Telegram / BankList edits work on the normal rules too (`Store.baseRules`); `Store.rules` is the read-only enforced view.

Block reasons in `decide()` (priority): `blocked` > `appLimit` > `groupHours` > `groupLimit` (v0.9.4) > `new` > `paused` > `schedule` > `bedtime` > `daily`.

## State JSON (phone -> parent). New fields marked NEW.

Everything in v0.1 stays (`id t day model android version owner usageAccess battery charging screenOn current usedMs
leftMs apps rules location lan relay log`), plus:

```jsonc
{
  "version": "0.9.4",
  "acc": true,                     // NEW: accessibility service connected (live screen / remote control available)
  "accPaused": [],                 // NEW: packages it is turned off for right now (rules.accPause); non-empty = acc false on purpose
  "tamperOn": ["accoff"],          // 0.9.25: tamper conditions still true (accoff | usageoff | home); an event with that "key" stops being news once its key is gone here (the parent app hides its notification). accoff waits 30 s after a bank pause ends
  "vpnPaused": [],                 // NEW: packages the VPN is off for right now (rules.vpnPause)
  "refused": [{"pkg": "vn.com.lpbank", "label": "LPBank", "kinds": ["acc", "vpn"], "at": 1763364000000}],   // 0.9.28: apps seen saying they won't run while accessibility (acc) / debugging (dev) / VPN (vpn) is on, kinds the rules don't cover yet; newest first, <= 6, 7 days (RefuseHint). Page: band + "Add to the list" (acc -> accPause, dev -> devPause, vpn -> vpnPause; a full rules PUT). Missing = older phone. Each hit is also an event `refused` (parent app push via FCM like any event; not sent on Telegram)
  "devPaused": [],                 // NEW: packages debugging is off for right now (rules.devPause)
  "vpnStuck": false,               // NEW: a VPN stayed up after the pause (another VPN app, or always-on set in Settings in basic mode)
  "accReEnable": true,             // NEW: the app holds WRITE_SECURE_SETTINGS (can re-enable acc itself)
  "locationOn": true,              // NEW: system location switch
  "dev": {"on": true, "adb": false, "canOff": true},   // v0.9.6: Developer options on; USB or (API 30+) wireless debugging on; app holds WRITE_SECURE_SETTINGS or is Device Owner
  "limitMin": 120,                 // NEW: today's limit in minutes incl. bonus (0 = none)
  "pairUntil": 1793940898493,      // v0.9.2: an opened pairing window (openPairing / PIN on the child) closes at this ms; 0 = closed (also before the first pairing)
  "apps": [{"pkg":"…","label":"…","ms":0,"blocked":"new|…|null","suspended":true,"protected":false,
            "new": true}],         // NEW "new": installed after setup and not approved. With rules.monitor, "blocked" is always null (nothing enforced)
  "wouldBlock": {"pkg": "reason"}, // v0.9.11: what decide() computed but did not enforce (same reasons as apps[].blocked); {} unless rules.monitor
  "history": [                     // NEW: last 7 days before today, newest first
    {"day": "2026-11-05", "ms": 5400000, "top": [{"pkg":"…","label":"…","ms":1200000}]}   // top: up to 5
  ],
  "net": {"type": "wifi|cell|ethernet|vpn|other|none", "metered": false, "ip": "192.168.1.5",   // 2026-11-10: phone card (tap = details)
          "ssid": "Home", "rssi": -60, "bars": 3, "ghz": 5.0, "mbps": 433,   // Wi-Fi; ssid only with location permission + location on
          "carrier": "Viettel",                                             // cell
          "hwMac": "aa:bb:…"},                                              // Device Owner only (factory MAC); rssi/bars/mbps left out of the relay WS change hash
  "ts": {"state": "off|installing|on|error", "installed": true, "up": false, "ip": "100.x.y.z", "host": "kid-an", "err": "", "noExpiry": false, "doze": false},  // v0.4: Tailscale (never the key); noExpiry v0.9.6: node key expiry turned off; doze v0.9.24: Tailscale installed but battery-optimised (drops in Doze; AutoGrant whitelists it when the phone has its own adb)
  "ts.why": "",  // NEW 2026-11-13: why Tailscale is not being revived now: "bank" (VPN pause) | "bg-start" (no right to open an app from the background) | "hidden" (app suspended) | "screen-off" | "not-home" (our launcher not in front) | "wait" (10-min gate between opens; command `tailscale` resets it) | "" (nothing / reviving)
  "grayErr": "",  // NEW: last wind-down grayscale error ("" = fine, "no permission" | "write failed")
  "autoGrant": {"left": 0, "at": 0, "next": 0, "err": ""},  // NEW: permissions still missing at the last tick, last real attempt (ms), next allowed attempt (ms), err "" | "no adb"
  "bankList": {"at": 0, "err": ""},  // NEW: last successful fetch of the website bank-app list (ms, 0 = never), last fetch error ("" = fine)
  "calls": [{"n": "+84912345678", "name": "Mẹ", "type": "in|out|missed|rejected|blocked", "t": 1793940898493, "dur": 62}],   // NEW 2026-11-15: last 30 calls of 7 days (CallLog), newest first, only with READ_CALL_LOG; dropped from a trimmed relay state
  "callsCap": {"api": true, "screen": false, "log": false, "contacts": false},   // NEW: api = Android 10+, screen = CALL_SCREENING role held, log / contacts = runtime permissions
  "relayOk": 0,  // NEW: epoch ms of the last successful relay sync (0 = never). Over the relay the worker raises it to when it
                 //   last heard the phone (sync or socket ping): the phone leaves it out of its "changed?" checks (2026-11-17)
  "digest": {"days": [{"day": "2026-11-14", "asked": 2, "answered": 1, "blocked": 3, "tamper": 0}],   // 2026-11-14 evening summary: today first, up to 7 days, counters kept 8 days by the phone
             "bedKept": true},     // last night's bedtime window: true = under 1 min of use, false = used, null = no bedtime / per-night times (bedByDay) / no usage access
  "webOther": ["org.mozilla.firefox"],  // 0.9.24, only while noIncognito / webBlock is set: installed browsers that ignore the website rules (page offers to block them)
  "requests": [                    // NEW: the child asked for more time (button on the child's KidGuard screen)
    {"id": "a1b2c3", "t": 1793940898493, "min": 30, "note": "con làm bài tập"}
  ]
}
```

## Commands (`POST /api/cmd` on LAN, queued via `POST /c/<id>/cmd` on the relay)

v0.1: `lock locate message{text} ring relay{url} openPairing unpairAll release`. NEW:
- `refusedDrop {pkg}` (2026-11-17): the parent says a `state.refused` entry is a false alarm ("Bỏ qua, báo nhầm"). Phone drops it and never warns for that package again (prefs `refusedIgnore`). Result `ok` | `bad pkg`. Not destructive, not ONCE.
- `unpair {id}`: revoke ONE paired parent device (`id` from `state.devices[].id`). Result `ok` or `no such device`.
  Destructive: the worker whitelists it and keeps it until acked (see v0.2.1 "Commands are idempotent"; in the `ONCE` list like `unpairAll`).
  The worker keeps `id` as the command id (it is 8 hex chars, so it passes the id check); do not send other ids. Revoking
  the device you are using is allowed (it gets 401 next request and must re-pair). If that device was the relay link
  (the one named `relay`), the relay token / id are cleared too, so the phone stops syncing until the relay is set up again.
  Any other device removed while a relay link exists sets state `relayShared: true` (the relay id + token are shared by every
  relay parent, so the removed device may still hold them): the page offers `relay {url, renew:true}` (PIN-gated, LAN only like
  `relay`), which makes a new relay id + token, revokes the old token and clears `relayShared`; the reply carries the new
  `id`, `token`, `url` like a plain `relay` call. Old relay links stop working.
  LAN also has `POST /api/unpair {"id", "pin"}` (PIN-gated like the command, 2026-11-13) -> `{"result":"ok"}`, 404 `{"result":"no such device"}`,
  403 `{"result":"failed: pin needed"|"failed: pin"}`, 429 `failed: pin locked`. `release` is not affected
  (it still clears every token).
- **State** `devices: [{"id","name","at","last","self"?:true}]`: paired parent devices, oldest pairing first. `id` = first 8 hex
  of sha256(token) (non-secret; the token never leaves the parent's browser). `name` = the page's optional `X-KG-Name`
  header (<=40 chars, taken the first time it is seen; the relay link is named `relay`; may be ""), `at` / `last` = epoch ms of
  pairing / last LAN request (`last` moves at most every 5 min; 0 for phones paired before this). `self: true` marks the
  caller's own row on LAN `/api/state` only; the relay-cached state has no `self`, the page can compute its own id as
  sha256(token).slice(0,8).
- `answer {req, ok: bool, min?, note?, always?}`: the parent answered the open request (`req` = request id; `id` is the fallback, since the
  relay fills `id` with the command id). The command only removes the request, stores `lastAnswer` and tells the child (notification); the
  parent side ALSO edits the rules (full replace). Time request (`kind` "time"): grant `min` = if `now.why` is "" or "daily" raise today's
  `bonus`, else `passUntil = max(now, passUntil) + min`. App request (`kind` "app", `pkg`/`app`): on ok add `pkg` to `approved` and remove it from
  `blocked`; `always: true` only changes the child's wording (the approval is permanent either way).
- `reboot`: DevicePolicyManager.reboot.
`release` must also undo every v0.2 restriction (location, airplane, private DNS, accessibility allowlist).
It also disables the child's lock / time-left Quick Settings tiles, home-screen widgets and lock shortcuts (`Quick.kt`;
a pinned shortcut can only be disabled, not removed). No wire change: they read nothing but the phone's own state.
The worker's command whitelist must include `answer` and `reboot`.
v0.4:
- While pairing is open the child screen also shows a QR of `http://<ip>:8770/#pair=<code>`; the page prefills the code
  from `#pair=` (6 digits) and drops the hash.
- `openPairing` returns `set PIN first` (and does nothing) when a parent was already paired and no PIN is set.
- v0.9.6: `/api/pair` also takes `newPin` and refuses to pair without a PIN when none is set; see "v0.9.6" below.
- LAN `GET /api/hello` adds `pin: bool`. `POST /api/pair {code, pin?}`: `pin` is required once a PIN is set (the child
  can see the code, never the PIN). Errors `{error, wait}`: 403 `wrong code | wrong pin`, 429 `wait | pin locked`,
  403 `closed`. Every 3rd wrong guess makes a new code and blocks pairing for 30 s, doubling (cap 5 min).
  The PIN (child screen and /pair share one counter): 5 free tries, then locked 5 min, doubling (cap 30 min).
- The LAN server answers 403 `bad host` unless `Host` is an IP literal, `localhost`, a single-label name or `*.ts.net`
  (DNS rebinding).
- `tailscale {authKey, hostname?}`: phone downloads the official Tailscale APK (SHA-256 checked), installs it silently,
  sets managed config + always-on VPN (no lockdown) + uninstall block + `DISALLOW_CONFIG_VPN`, hides the app once the
  tunnel is up. Result `ok | bad key | busy | not owner`. **LAN only**: not in the worker whitelist, so the key never
  sits in the relay queue.
  `authKey` may instead be an **API access token** (`tskey-api-…`, v0.9.6): the phone keeps it in memory only, uses it to
  `POST api.tailscale.com/api/v2/tailnet/-/keys` (one-off, pre-approved, not ephemeral, 1 h) for its own auth key, and
  once its 100.x address is up finds itself in `GET /tailnet/-/devices` and `POST /device/<id>/key {"keyExpiryDisabled": true}`
  (state `ts.noExpiry` true), then drops the token (also after 5 failed tries, or a 401/403). With a plain auth key
  `noExpiry` stays false and the page shows the manual "Disable key expiry" step (Tailscale signs a node out after 180 days).
- `tsInstall` (2026-11-16, repeatable, SHORT, not PIN-gated, `Ts.openInstall`): open Tailscale on the child's screen: the app if installed, else its Play Store page (`market://details?id=com.tailscale.ipn`, https if no Store). Needs the background-start right (Device Owner, or "display over other apps" in basic mode); result `ok | no bg start | failed | unsupported`. A person at the child phone still taps Install and signs in (basic mode cannot do that for them). State `ts.installed` / `ts.up` / `ts.ip` are read live from the phone (package + a 100.64/10 address on a `tun*` interface), also when KidGuard never set Tailscale up; `ts.up` false with `installed` = signed out or switched off (not told apart).
- `tailscaleOff`: undo all of it and uninstall Tailscale (also done by `release`). In the worker whitelist.
v0.9.6:
- `devOff`: turns off wireless debugging (`adb_wifi_enabled`, API 30+), USB debugging (`ADB_ENABLED`) and Developer
  options (`DEVELOPMENT_SETTINGS_ENABLED`) with `Settings.Global.putInt` (WRITE_SECURE_SETTINGS); as Device Owner it
  falls back to `DevicePolicyManager.setGlobalSetting` where Android allows it. Banking apps often refuse to open while
  these are on. Result `ok | not allowed | failed: <reason>` (`not allowed` = `dev.canOff` false). Logs `log_dev_off`.
  Not a new restriction (nothing for `release` to undo); the child can turn them on again unless `noDebug` is set.
  In the worker whitelist (repeatable, not deliver-once). Over LAN through an adb-forwarded port the reply can be lost
  (adb goes down with it); normal Wi-Fi LAN and the relay are fine.
- Saving rules that switch `noDebug` from false to true also runs `devOff` once. Switching it back to false runs `devOn` once when debugging was on before (the phone remembers).
v0.9.7:
- `devOn`: the way back after `devOff`: turns Developer options and USB debugging (`ADB_ENABLED`) on with
  `Settings.Global.putInt` (needs WRITE_SECURE_SETTINGS; the Device Owner fallback can't write these). Result
  `ok | not allowed | blocked | failed: <reason>` (`blocked` = the `noDebug` rule's DISALLOW_DEBUGGING_FEATURES is set).
  State `dev.canOn` (bool, v0.9.7) says whether it can work; the page shows the button only then. Logs `log_dev_on`.
  In the worker whitelist (repeatable).
- `adbShell {cmd, pin}` (gated, v0.9.14, `remote/Shell.kt`): runs `cmd` (≤ 2000 chars) as the adb shell user through the
  phone's own paired adbd (`SelfAdb`: Android 11+, WRITE_SECURE_SETTINGS, Wi-Fi; opens the debugging window itself).
  Answers at once `ok | busy | empty | no adb`; the output comes in state `sh.log` (LAN and relay alike). 25 s timeout.
  Not on Telegram. Worker: whitelist, ONCE, SHORT (a shell command 10 min late is dropped; ONCE window 1 h).
- `selfOpen {on}` (2026-11-16, PIN-gated when `on:true`, ONCE, SHORT): basic mode stops closing KidGuard's own app-info
  page (BasicLogic.selfProtect: Uninstall / Force stop) for 5 min (`CmdGate.SELF_OPEN_MS`), e.g. to turn a notification
  channel back on; `on:false` ends it. State `selfOpenUntil` (epoch ms, 0 = closed). Device Owner never closes that page.
- `bankEnd` (2026-11-13): end the bank-app pauses now (accessibility, VPN, debugging) instead of after the 10-minute wait; result `in use` while that app is in front. State `bankBackAt` = when they end by themselves (epoch ms, 0 = an app still in front / none). `ts.why` = "bank" while the VPN pause holds Tailscale.
- `uiUpdate` (2026-11-17, `remote/UiServe.kt`, repeatable, SHORT, not PIN-gated: the phone only fetches a signed page): check for a newer UI pack now (below, "UI pack"); result `ok` or `busy`. The page then polls `GET /ui/pack.json` of the phone for the new `ver` and reloads.
- `fixPerms` (2026-11-14, `guard/FixPerms.kt`, repeatable, SHORT, not PIN-gated: it only turns our own permissions back on): the page's "N things are off" Fix. The phone first grants what it can alone (Device Owner: location + notifications via `setPermissionGrantState`; our accessibility service via WRITE_SECURE_SETTINGS; the setup's grants over self-adb = `AutoGrant`), then for what is left opens the exact Settings screen on the kid phone (background start: Device Owner or overlay right) and posts a tap-to-open notification (id 8, channel `fix`, cleared when nothing is missing and on release). Answers `ok | working | none | retry` (`working` = the grants run in the background; read state `fixPerms`; `none` = nothing was missing; `retry` = nothing for the Settings screens but adb grants are still due; both moves the next `autoGrant` attempt to now: the page's "Try now" in Features). Runtime permissions (`mic`, `notify` 33+) open KidGuard with the system "Allow?" dialog (one tap) instead of App info; when the child chose "don't ask again" the dialog is skipped and App info opens.
- State `missing` (2026-11-14): ids still off, in order, of `admin acc` (basic mode only) `usage listener location notify battery mic calls cam sms` (`calls` = READ_CALL_LOG + READ_CONTACTS for the Calls card; `cam` = CAMERA while rule `watch.cam`; `sms` = READ_SMS while `watch.sms`; all three one-tap dialogs; `listener` = notification access, `battery` = battery optimisation, `mic` = RECORD_AUDIO for listen / Telegram recording, 2026-11-16). `[]` = all fine; an old phone has no field (page: nothing shown). State `fixPerms` `{busy, at, fixed: [ids], tap: [ids]}`: the last Fix (`fixed` = got back by the phone itself, `tap` = still need a tap on the phone; only ids still in `missing`). OEM auto-start pages are not detectable, so they are not listed.
- `shClear`: empties `sh.log`. `adbTcp {on, pin}` (gated): on (also sent again as "Open now": retries at once and ends a debugging pause whose app is no longer in front) = USB debugging kept on and adbd `tcpip:5555`
  (classic `adb connect <ip>:5555`), re-opened by the Guard tick after a reboot (every 10 min while closed), paused while
  a bank app has debugging off; off (also `devOff` and release) = `usb:` then Developer options / USB debugging back as
  before; port 5555 survives adbd restarts until a reboot, so a pending `usb:` (`Store.adbTcpReset`) is retried by the tick
  while 5555 is still served. Answers `ok | no adb | blocked`. While 5555 is served, `adbShell` itself connects to `127.0.0.1:5555` first (paired key, no prompt wait), so it runs without Wi-Fi / Wireless debugging (mobile data); `sh.can` is then true even without WSS. Worker: ONCE but not SHORT (an "off" must never be dropped).
  `support` is ONCE (not SHORT since 2026-11-15: 24 h life, 1 h ONCE window) (a leaked link must not replay reports into the 20/day census quota).
- (removed 2026-11-15) State `adbTcpUntil` / the 2 h auto-close: `adbTcp on` keeps port 5555 open until `adbTcp off`, `devOff` or release.
- State `sh` (v0.9.14): `{can, busy, tcp, tcpUp, tcpWhy, port, log: [{at, cmd, ms, out | err}]}` (`tcpWhy` 2026-11-13: "bank" = held by a bank pause, "link" = no adb link, needs Wi-Fi, "" = trying), last 8, memory only;
  `out` ≤ 6000 chars for the newest two, 300 for older ones (state stays < ~14 KB for the relay); `err` = `adb` (no answer / timeout) or an `adbWhy` code (`sdk key wss blocked wifi`).

### PIN-gated commands (v0.9.13, `guard/CmdGate.kt`)
`release unpairAll unpair clearData reboot relay tailscaleOff openPairing totpSet tgSet tgCode tgOff pinSet pinResetCancel adbShell adbTcp aiToken selfOpen`
(`adbTcp` / `selfOpen` only with `on:true`; `on:false` = closing needs no PIN, 2026-11-15 / 2026-11-16) carry `pin`: the parent PIN or a current Authenticator code (its backup, also while the PIN is locked; an admin open by code raises a `tamper` event). The PHONE checks it (LAN and relay alike), sharing the lockout
of the on-device admin (5 free tries, then 5, 10, 20, 30 min, then 30 min per try; PIN and Authenticator locks are separate; lockouts run on the monotonic clock, a reboot falls back to the wall clock capped at the original length).
`relay {url}` with the URL the phone already has is not gated (the page re-reads the relay link with it); `renew:true` always is. No PIN and no
Authenticator set = nothing to check. Results: `failed: pin needed` (no `pin`, not counted), `failed: pin` (wrong, a
`tamper` event names the command), `failed: pin locked`; `failed…` frees the id, so the same id may be re-sent with the
right PIN. LAN `relay` returns `id/token/url` only when the result is `ok`. The worker only checks the shape
(`pin` 0–8 digits, `new` 4–8 digits). Phones before 0.9.13 ignore `pin`; the page asks for it only when
`state.pinResetAt` exists.
- `pinSet {pin, new}` (gated): the ONLY way to replace a set PIN. Rules PUTs (LAN, relay `pinHash`, Telegram) may set the
  FIRST PIN but never replace one (`Guard.setRules(pinChange=false)` keeps the old hash).
- `pinReset {new}` (not gated, "forgot the PIN"): stored on the phone, applied after 24 h (`state.pinResetAt`, 0 = none),
  `tamper` event to every parent at once; the right PIN entered anywhere (admin screen, any gated command) or
  `pinResetCancel` cancels it. A new `pinReset` replaces the pending one and restarts the 24 h.
- Worker: `pinSet pinReset pinResetCancel` are in `CMDS`, `ONCE` and `SHORT` (10 min; a PIN change nobody waits for must not fire later).

## Live screen and remote control

### Acc companion API (Kotlin, in `Acc.kt`)
```kotlin
Acc.isOn(ctx): Boolean
Acc.ensureEnabled(ctx): Boolean          // core calls this every tick
Acc.capture(maxSide = 720, quality = 50): Acc.Frame?   // Frame(jpeg, w, h) w/h = real screen px; null => Acc.lastError
Acc.input(o: JSONObject): String         // "ok" or error
Acc.lastError: String                    // "acc off" | "screen off" | "busy" | "need consent" | "denied" | "timeout" | …
Acc.frameGapMs(): Long                   // relay loop pause: 333 (API 30+), 1000 (API 26-29, projection)
```
While frames are being taken (any capture in the last 5 s) the child sees an ongoing notification
"Bố mẹ đang xem màn hình" (after the first input: "Bố mẹ đang điều khiển máy"), unless rule `showWatching` is false.
The first capture / first input of a session is logged in the phone log.

### Input object (same on LAN and relay). Coordinates are fractions 0..1 of the screen.
```jsonc
{"type": "tap", "x": 0.5, "y": 0.8}
{"type": "long", "x": 0.5, "y": 0.8}                        // ~600 ms press
{"type": "swipe", "x1": 0.5, "y1": 0.8, "x2": 0.5, "y2": 0.2, "ms": 300}
{"type": "key", "key": "back|home|recents|notifications|quick|lock|power"}
{"type": "text", "text": "xin chào"}                        // replaces the text of the focused editable field
```

### LAN (phone HTTP server, token header `X-KG-Token`)
- `GET /api/screen?max=720&q=50` -> `200 image/jpeg` with headers `X-KG-W`, `X-KG-H`; or `503 {"error": "<lastError>"}`.
- `POST /api/input` body = input object -> `{"result": "ok"}` or `{"result": "<error>"}`.
- Capacity: 16 worker threads + 32 queued connections; long-running routes (`/api/listen`, `/api/screen`, file bodies) share
  6 slots, so `/api/state` always finds a thread. Over the limit: `503` (`{"error":"busy"}` for a long route, empty body +
  `Retry-After: 2` when the whole pool is full) instead of a silently closed socket.
- `/api/hello` answers on any method (no token; the pairing probe). `?n=<16-64 chars>` (0.9.39) adds
  `proof: [hex]` = HMAC-SHA256(key = sha256-hex(token), "kg-lan:" + n), one per paired token (`LanName.proof`).
- **LAN address heal** (page, `core.js` `relocate` / `learnAddrs`, no phone change): after each LAN `state` the page keeps
  the phone's other addresses on the saved kid (`alt`, up to 4: `state.lan`, `http://<state.ts.ip>:8770`) and, when
  `state.relay` is set, sends the `relay` command once per page load with that same url: the phone keeps its relay id/token
  and returns them, saved as `kid.relay = {url (origin), id, token}`. When a LAN call fails (or the parent app answers
  with a cached snapshot): probe `alt` then the `lan` / `ts.ip` of the relay state with `GET /api/hello` (4 s, `id` must
  match), switch `target` and retry ("đã tự kết nối lại" toast). Nothing found: `GET /state`, `POST /cmd`, `PUT /rules`
  go to the relay for 60 s at a time (toast "đang đi qua relay"; relay reply shapes, commands `queued`), other routes
  (screen, files, listen) fail as before. One heal attempt per kid per 20 s. Browser: probing works (CORS on `/api`),
  the relay fallback only in the parent app (the worker sends no CORS). A page served from the phone's old address
  dies with it (reload from the new one).
- **LAN name** (0.9.38, `android/shared/LanName.kt`): while the guard runs, the kid phone advertises NSD (mDNS / DNS-SD)
  service type `_kidguard._tcp`, name `kg-` + first 12 hex of SHA-256(kid id) (Android may append ` (2)`), port 8770
  (`LanAd.kt`; kid flavor, no permission). The parent app (`Lan.kt`) scans for it when the saved LAN address fails:
  page `relocate` step 2 (after `alt`, before the relay) via `KGApp.lanFind(id)` → `"http://ip:port"` or `""` (a scan
  starts; asked every 1.5 s, up to 8×) or `"none"` (nothing / no Wi-Fi: no wait), and `KGApp.lanMoved(id, url)` after the
  switch; the background check, notification and widget actions try the found address (pref hint per kid id) before
  the relay. An NSD-found address gets the token only after `GET /api/hello?n=<nonce>` returns the same `id` AND a
  `proof` made from that token (0.9.39: the name and id are public, a look-alike must not get the token). 0.9.40: the
  page's own probes (`alt`, relay-state addresses) send `?n=` too and require the proof where `crypto.subtle` exists
  (parent app, localhost); a plain-http browser page keeps the id check. A failed search is remembered 60 s (page) /
  2 min (background) per kid. Browser pages have no NSD (alt + relay only).
- **Relay on by default** (`Store.defaultRelay`, each guard tick): once a parent has paired, a phone that never had a
  relay setting (pref `relayUrl` absent) gets `Relay.DEFAULT_URL` (the author's shared worker, same constant as
  `DEFAULT_RELAY` in `core.js`) with a new relay id/token (device row "relay"). `relay{url:""}` = relay off: the url is
  stored empty, the token kept, no default again. Home flavor: never. The page's relay section prefills the default url,
  shows the `/go` link and a "Tắt relay" button; self-hosting (`scripts/relay.sh|ps1`) is the advanced line.
- **`/go` link** = `https://kid-guard.pages.dev/go#c=<relay id>&k=<relay token>&n=<name>[&r=<relay origin>]` (the parent
  page on the website, plan B: the relay serves no page; `r` only when not `DEFAULT_RELAY`, the page keeps it in localStorage
  `kg.relay` and stores `target` = relay origin on the kid; `/go` = `/app/` via `website/_redirects`). `core.js goBoot`
  asks `GET /c/<id>/where` -> `{seen, lan, ts, same, mode}` (`same` = the parent's public address equals one of the
  phone's last 2 sync addresses: IPv4, or IPv6 /64; stored as sha256 hashes `ipk`). Seen < 3 min + same + lan ->
  `location.assign(<lan>/#lk=<token>&rc=<relay id>&ru=<relay origin>&n=<name>)`; the phone's page (`lanFromGo`) saves a
  LAN kid with that token (the relay token is one of the phone's LAN tokens) and `kid.relay`. Back to `/go` within 90 s
  (sessionStorage `kg.go.<id>`) = the LAN page did not load -> stays on the relay with a toast. Seen >= 3 min / never /
  401 / relay error -> an error screen (last seen, last LAN address, Tailscale ip, likely causes, Try again / See old
  data / Try home network / Try Tailscale). Otherwise the normal page over the relay.

### AI token (2026-11-15, `guard/AiScope.kt`, worker `AI_CMDS` / `AI_LOCKED`)
A second, limited credential for an AI assistant (`mcp/kidguard-mcp.mjs`, env `KG_AI_TOKEN`). The page makes it (24 random bytes = 48 hex,
like a paired token) and sends only its sha256: command `aiToken {op:"set", hash:<64 hex>, pin}` or `{op:"revoke", pin}` (PIN-gated, so a leaked
parent link cannot mint one; the token itself never reaches the phone or the relay). State `ai: bool` = one exists (absent = old phone).
- Phone: stored hash `Store.aiHash`; `X-KG-Token` matching it = role `ai` (not in `state.devices`). Dropped by `unpairAll` / release.
- Relay: every phone sync body carries `ai` (the hash, `""` = none); the worker keeps it (`aiHash`) and gives a request with that token role `ai`.
  A parent `aiToken` (revoke or set) through the worker deletes the old hash at once; a new hash works there after the phone syncs it. One code per phone: set replaces.
- Role `ai` may: `GET state` (and `icon`; no `?watch=1`), `PUT rules`, `POST cmd` with `lock locate message ring ringStop sosAck answer` (relay: their codes, "Command codes").
  `PUT rules`: `pin pinHash pinSalt pinAt pinSet noInstall noDebug pinShot lockLocation noAirplane frpIds safeDns safeDnsPauseUntil noVpn tsKeep
  vpnPause accPause devPause tcpPause autoUpdate showWatching unlockMin hidden approved mode` keep the stored value whatever is sent (a key the phone
  has not stored is dropped). Anything else (other routes, other commands, `aiToken`, pairing, PIN, files, screen, listen) = `403 {"error":"ai token: not allowed"}`.
  LAN: `Api.kt` checks the same lists; relay: the worker (the phone does not know who queued a command, so the worker is the gate there).

### Command codes (relay wire, plan B 2026-11-18)
On the relay a command's `type` is a short code, never the name: `POST /c/<id>/cmd {"type":"lk"}`, and the phone's sync reply,
`queue` and `expired` carry the codes too. The worker knows codes only (its bundle holds no feature names: an upload-time scan
killed the old worker, docs/decisions/remote.md 2026-11-17/18); a name there = `400 {"error":"bad command"}`. LAN (`/api/cmd`) keeps
the names. Encoded / decoded at the edges: page `ui/assets/core.js` `WIRE` (`call1`, `viaRelay`), phone `K/remote/Wire.kt`
(`Relay.kt` sync), parent app `Act.RELAY_*`, watches and `mcp/` send the codes of their few commands. All other fields stay as named.
`relay/test/contract.test.mjs` checks this table == `WIRE` == `Wire.kt` and the worker lists. Commands that never go through the
relay (`relay`, `tailscale`) have no code. A new relay command = a new row here, in `WIRE` and in `Wire.kt`.


| name | code |
|---|---|
| `aiToken` | `ai` |
| `refusedDrop` | `rd` |
| `uiUpdate` | `uu` |
| `smsPull` | `sp` |
| `chatPull` | `cp` |
| `chatClear` | `cc` |
| `chatFileDel` | `cf` |
| `chatDel` | `cd` |
| `snapList` | `sl` |
| `snapUp` | `su` |
| `snapDel` | `sd` |
| `snap` | `sn` |
| `lock` | `lk` |
| `locate` | `lc` |
| `message` | `ms` |
| `ring` | `rg` |
| `openPairing` | `op` |
| `unpairAll` | `ua` |
| `unpair` | `up` |
| `release` | `rl` |
| `answer` | `an` |
| `reboot` | `rb` |
| `tailscaleOff` | `to` |
| `sosAck` | `sa` |
| `clearData` | `cl` |
| `update` | `ud` |
| `totpSet` | `ts` |
| `wake` | `wk` |
| `ringStop` | `rs` |
| `listenStop` | `ls` |
| `devOff` | `d0` |
| `devOn` | `d1` |
| `setting` | `st` |
| `fileUp` | `fu` |
| `fileDel` | `fd` |
| `shotUp` | `sh` |
| `notifPull` | `np` |
| `tgSet` | `gs` |
| `tgCode` | `gc` |
| `tgUnlink` | `gu` |
| `tgOff` | `go` |
| `pinSet` | `ps` |
| `pinReset` | `pr` |
| `pinResetCancel` | `pc` |
| `support` | `sx` |
| `supportSay` | `sy` |
| `adbShell` | `ab` |
| `shClear` | `hc` |
| `adbTcp` | `ap` |
| `bankEnd` | `be` |
| `unlockEnd` | `ue` |
| `clearCache` | `ca` |
| `rescan` | `re` |
| `bgLimit` | `bl` |
| `bgRestore` | `br` |
| `fixPerms` | `fp` |
| `selfOpen` | `so` |
| `tsInstall` | `ti` |

### Relay (worker), token header `X-KG-Token`, id = 16 hex
JSON only since plan B (2026-11-18): no page, `/ui`, `/install`, `/qr` or APK (all `404 {"error":"not found"}`; the website
serves them: `scripts/site-app.mjs`). Every reply carries CORS `*` (the page runs on another origin; tokens are headers, never
cookies), `OPTIONS` = `204`. Command `type`s are codes ("Command codes" above).
- **WebSocket push channel** (load: an idle socket costs nothing; phone `remote/RelayWs.kt`, worker `Kid.webSocketMessage`):
  phone `GET /c/<id>/ws` (Upgrade, `X-KG-Token`; only after the first HTTP sync bound the token, else 409) -> 101,
  hibernatable (`acceptWebSocket`, tag `phone`, a new socket closes the old one). Text `"ping"` -> `"pong"` by the runtime
  (auto-response: no wake-up, not billed), every 45 s; no byte for 120 s = dead, reconnect. Phone -> `{"t":"sync","n":<int>,
  acks, state?}` = the `POST /sync` body; reply `{"t":"sync","n":<same>, ...POST /sync reply}` or `{..., "error"}`.
  Relay -> `{"t":"kick"}` (at most every 2 s) after a parent `POST cmd|live|device|file`, `PUT rules`, or `GET state` when
  the stored state is > 30 s old: the phone syncs at once (commands now near-instant). Socket up, the phone sends a sync
  only on a kick, a Guard kick, pending acks, a change outside time/battery/foreground-app fields (`t usedMs leftMs
  current battery charging screenOn apps[].ms/mb`), or every 5 min (screen on) / 15 min (off). `seen` = last HTTP
  sync / socket message / auto-response ping (`getWebSocketAutoResponseTimestamp`); written to storage on close only.
  Socket down or a reply timeout (15 s): the same sync over HTTP, the old 20 s / 60 s polling, reconnect backoff
  1 min .. 30 min. Live screen frames and files stay on HTTP.
- Limits: `POST /c/<id>/cmd` -> 429 `queue full` with 20 commands waiting that a new one cannot replace (a full queue first drops a still-queued, not yet handed out older copy of the same repeatable type, same `key` for `setting` / same `file` for `fileUp`; never a destructive ONCE one), 413 `too big` over 4 KB of JSON; `PUT /c/<id>/rules`
  -> 413 over 64 KB (the sync reply carries both, and the phone refuses replies over 256 KB).
- Parent `POST /c/<id>/live` `{}` -> `{"ok":true, "phone": <ms since the phone's last frame or -1>}`; sets
  `liveUntil = now + 20 s`. The page calls it every 10 s while the viewer is open.
- `POST /c/<id>/sync` reply gets `"live": true` while `liveUntil > now`.
- Phone, while live: loop { capture; `POST /c/<id>/frame` (body = JPEG, `Content-Type: image/jpeg`, headers
  `X-KG-W`, `X-KG-H`; when capture failed: empty body + header `X-KG-Err: <lastError>`) -> `{"inputs":[…], "live": bool}`;
  run inputs; stop when `live` is false }. At most ~3 frames/s; frame body max 1 MB.
- Parent `GET /c/<id>/frame?after=<seq>` -> newest frame if its seq > after: `200 image/jpeg`, headers `X-KG-Seq`,
  `X-KG-W`, `X-KG-H`, `X-KG-Age` (ms); else waits up to 5 s, then `204` (with `X-KG-Err` if the phone reported one).
  Also extends `liveUntil`.
- Parent `POST /c/<id>/input` body = input object -> `{"result":"queued"}` (queue max 50, delivered on the next frame reply).
- Frames live only in Durable Object memory (never storage).
- Phone `POST /c/<id>/sync` body `{state?, acks: [cmd ids], ai, fcm?}` (`fcm`: kid push wake, see FCM section) (`state` omitted when unchanged, see v0.2.1) -> reply
  `{cmds: [...], merge: 1, full?, rules?, live?, files?, device?}`: `rules` only when the worker's `rules.rev` is higher than the `state.rules.rev`
  it knows; the phone takes them only if `rules.rev` > its own (so a phone clock ahead of the worker's makes relay edits
  wait until the worker rev catches up; LAN saves stamp `rev = max(old+1, phone now)`); `files` = number of
  parent -> kid files waiting; `device` = the parent asked for a device-info upload.
- Parent `GET /c/<id>/state` -> `{state, rules, seen, pending, expired}`: `expired` = last 10 commands the worker dropped unrun
  (SHORT 10 min / 24 h / ONCE window 1 h): `[{id, type, res: "expired", at}]`; the page turns "sent" into "expired, not run".
  The phone's own receipts are `state.done`. `state` the last synced state (`rules` inside it with `pinSet`
  instead of `pinHash`), `rules` the worker's copy, `seen` ms of the last sync, `pending` = queued command count, `queue` = the live queued commands `[{id, type, at}]` (last 20, no payload: the watch lists what has not reached the phone yet).
- Command `unlockEnd` (0.9.16): ends an Authenticator unlock now (`unlockUntil = 0`); not PIN-gated, not ONCE, SHORT.
- `POST /c/<id>/forget` (token header, same auth as sync): deletes everything stored for this id (state, rules, queue, push
  tokens, Telegram link) and closes the sockets; sent by the phone when it is released. A later sync of the same id binds anew.
- Input `ms` (swipe duration) is clamped to **50..3000** by both the worker and `Acc.kt`.
- `GET /c/<id>/state` already returns `seen` (ms of the phone's last sync): the page warns when `now - seen > 5 min`.
- `/install` and `/qr` are website pages (`kid-guard.pages.dev`, copies of `ui/install.html` / `ui/qr.html`); `qr.html` makes its
  invite on `DEFAULT_RELAY` (cross-origin).

### Parents (2026-11-17): own relay token per parent, family PIN, invites
- Each parent (page, parent app, watch) holds its OWN relay token (48 hex) with a name; the worker keeps
  `par = {sha256(token): {n name, k "app"|"web"|"watch", at, seen, ul}}` (<= 12). The phone's token (the one in the old
  `/go` link / QR) opens the parent routes only while `par` is empty AND the phone has not sent `pv` (phones before 0.9.27; a PIN set by the page alone does not lock it, or an old page would lock itself out); else it is phone-only
  (`sync ws file forget join`, `POST icons|frame`, `PUT device|logday`), anything else `403 {"error":"join"}`.
- `POST /c/<id>/join {name, kind:"app"|"web", pin}` with the phone's token -> `{token, name, kind, pin:"ok"|"none"}`.
  The PIN is the family PIN, checked by the worker against `pv` (below) or a PIN the page set in a rules PUT.
  `403 {"error":"bad pin","left":n}`; 5 wrong (join + unlock together, per kid) = `429 {"error":"pin locked","retryAt"}` for 15 min.
  No PIN known: only the FIRST parent may join (`pin:"none"`), later ones `403 {"error":"invite needed"}`.
- `POST /c/<id>/invite {}` (a parent token) -> `{inv: 32 hex, exp}`: one use, 10 min, <= 5 open. `POST /c/<id>/join
  {name, kind, inv}` with NO `X-KG-Token` -> same reply with `pin:"inv"`; used / expired / unknown `403`. Two parents
  scanning one invite: the Durable Object runs one request at a time, the first one wins.
- `GET /c/<id>/parents` -> `{parents:[{id (12 hex), name, kind, at, seen, me}], pinKnown}`; `POST parents {revoke:id}`
  (any parent, the token stops at once) | `{name}` (rename self).
- Browser tokens (`kind:"web"`) open for 12 h after a join / `POST /c/<id>/unlock {pin}` -> `{until}`; else every route
  but `unlock` / `parents` answers `423 {"error":"locked","name"}`. App tokens (the parent app has its own biometric lock)
  and watch tokens never lock. `kind` is the page's own claim: the PIN or an invite was needed to get any token.
- Remote pairing (`/p/<key>/result`, "Pair over relay") and watch connect (`POST /c/<id>/watch`) hand out a NEW parent
  token (named as typed, `?kind=app` on the result from the parent app), never the phone's token.
- Phone sync `pv`: `{h: pinHash, s: pinSalt}` (Rules.hashPin: "" salt = sha256("kg:"+pin), else 10 000 rounds; the worker
  checks with node:crypto, ~9 ms) sent when it changes, `""` = no PIN. Old phones send none: a PIN the page set via a
  rules PUT counts (`pv` = its legacy hash); else `pinKnown:false` and joins work by the link alone until a parent exists.
- `by`: a command or rules PUT from a parent token carries `by: <name>` (AI token: "AI"); the phone logs it
  (`log_cmd_by`, rules diff line ` · <name>`); state `queue` items carry `by`. `POST forget` is the phone's token only.
- Icon / tile keys: one per parent token (`iconKeys` / `tileKeys`, plus the older single `iconKey` / `tileKey`).

### UI pack (2026-11-17: the page updates without an app update; `android/shared/UiPack.kt`, `scripts/ui-pack.mjs`)
- Why: the page lives in 3 copies (APK of the phone, APK of the parent app, worker). The worker is redeployed at once; the apps used to show the page of their own build. Now the phone and the parent app serve the newest **verified** pack, else their bundled page.
- Files on Pages (`https://kid-guard.pages.dev/ui/`, built by `scripts/ui-pack.mjs`, deployed by `scripts/deploy.sh`): `manifest.json`, `manifest.sig`, `pack-<ver>.zip`. `manifest.json` = `{"v":1,"ver":<12 hex>,"seq":<unix s, rises>,"minKid":<n>,"minParent":<n>,"files":{"index.html":<sha256>,"assets/<name>.js|css":<sha256>}}`; `ver` = first 12 hex of sha256 over the sorted `name:sha\n` lines (content hash). `manifest.sig` = base64 DER ECDSA P-256 / SHA-256 over the exact manifest bytes. The zip holds `manifest.json`, `manifest.sig` and exactly the files named in the manifest (names `index.html` or `assets/[a-z0-9-]+.(js|css)` only).
- Accept rules (phone and parent app, `UiPackStore`): signature by one of the baked public keys (`UiPackCfg.KEYS`: the signing key + an offline backup); every file's sha256; `seq` > the current pack's and >= the highest seq ever installed (`floor`: no replay of an older pack, even after the cache was dropped); `ver` not on the bad list; `minKid` <= the phone's versionCode (parent app: `minParent` <= its versionCode). `minKid` / `minParent` are set by hand in `scripts/ui-pack.json`: raise when the page starts to need a native bridge method, endpoint or CSP origin an older app lacks.
- Storage: `cur/` + `prev/` + `meta.json` in the app's files dir (phone: device-protected). Written to a temp dir, renamed. Re-verified when loaded after a restart; a bad cache is dropped (previous pack, else bundled). An app update drops the cache (the new APK's bundled page is at least as new).
- Rollback: a new pack is *pending* until the page it served reports `GET /ui/ok?v=<ver>` (sent by `uiPackBoot()` after `boot()` ran); the 3rd index load without that report blacklists the pack and the previous one comes back. A bad published pack is also replaced by publishing a newer one (a rollback = a new pack with the old content and a higher seq).
- When it checks: when the index is served (debounced 60 s; phone: rule `autoUpdate` on), every 6 h from the Guard tick / parent `CheckJob`, parent app `onResume` (60 s), and at once on command `uiUpdate` / `KGApp.uiUpdate()`. A check = `manifest.json` with `If-None-Match` (304 when nothing changed), then `manifest.sig`, then the zip if worth it. https only, no redirects, caps (zip 4 MB).
- Routes (phone `Api.kt`, parent `Proxy.kt`, public like the page): `GET /ui/pack.json` -> `{src:"pack"|"bundled",ver,seq,app,checking,last,result}` (`result` = `same new net sig hash min bad seq ...`); `GET /ui/ok?v=<ver>` -> 204; phone only: `POST /ui/push` (body = a pack zip, <= 4 MB; same verification, so no token is needed: only a pack signed with our key installs; `scripts/ui-push.sh`). The worker has no `/ui/pack.json` (404): it always serves the page it was deployed with.
- Not covered: the call screen's `ui/call.html` + `rtc.js` stay bundled in the phone APK; a new external origin in the CSP (phone `Http.kt`, parent `Proxy.kt`) needs an app release, so raise `minKid` / `minParent` with it.

### Parent page files (served next to the page by the phone, the parent app and the worker)
- `GET /` (or `/index.html`) -> `ui/index.html`; it loads `GET /ui/<name>.css|js` (files of `ui/assets/`, name `[a-z0-9-]+`).
  No token (public like the page). `text/css` / `text/javascript; charset=utf-8`, `nosniff`; phone + parent app `no-store`,
  worker `no-cache`. Any other `/ui/...` path -> 404. The parent app's proxy also wants its `kgs` cookie, like `/`.

### API levels (v0.9.6)
- **API 30+**: frames from `AccessibilityService.takeScreenshot` (unchanged).
- **API 26-29**: frames from **MediaProjection** (`Proj.kt`), same JPEG / headers / routes as above. The first capture
  opens the system consent dialog on the child phone (`ProjActivity`); we never auto-click it. Android 8-9 shows
  "Don't show again" (then later sessions start silently); Android 10 asks every session. Until the child taps Allow
  `lastError` is `"need consent"` (page: "Cần con bấm Cho phép trên máy"); after Cancel `"denied"`, asked again after 60 s.
  While it runs a foreground service (`ProjService`, type `mediaProjection`) shows "Đang chia sẻ màn hình cho bố mẹ"
  (always, whatever `showWatching` says). At most ~1 real frame/s (an unchanged screen re-sends the last JPEG); the
  projection stops 20 s after the last capture (parent stopped viewing), when the system ends it, and on `release`.
  The page shows "Đang xem · N fps" while frames arrive.
- Remote input (`dispatchGesture`, global actions, set text) works on API 26+; it was never gated at 30. Key `lock`
  on API 26-27 (no `GLOBAL_ACTION_LOCK_SCREEN`) falls back to DPM `lockNow`.

## Action states (v0.9.6)
- `wake`: turns the screen on (`SCREEN_BRIGHT|ACQUIRE_CAUSES_WAKEUP` wakelock 3 s + an invisible `WakeActivity` with
  `setTurnScreenOn`/`setShowWhenLocked`, flags on API 26), then `requestDismissKeyguard` only when the keyguard has no
  secure lock (a PIN/pattern stays on the lock screen). Result `ok | failed` (screen still off after 2 s). Not deliver-once.
  On the relay a sleeping phone only polls every ~60 s, so a relay `wake` can take that long.
- `ring` (20 s alarm, full alarm volume, restored after): the phone shows a heads-up notification "Bố mẹ đang đổ chuông
  máy này" with **Tắt chuông**; a volume key also stops it (accessibility key filter, only while ringing). Result
  `ok | listening | failed`. Again while ringing = restart the 20 s.
- `ringStop`: stops it. Idempotent, always `ok`. Not deliver-once (stopping twice is harmless).
- `listenStop`: ends a listen session (below). Idempotent, `ok`.
- State adds `"ring": {"on": bool, "since": ms}` (the real player state).
- Conflicts (`ActionLogic.conflict`, also on the page): `ring` while listening -> `listening`; listen while ringing ->
  `503 {"error":"ringing"}`. Everything else (wake, lock, locate, screen view) can run together.
- Page: every per-kid action button has idle / pending (spinner, disabled) / done or sent (2.5 s) / error states; ring and
  listen show "Đang … · bấm để tắt" from the phone's state (`ring.on`, `listen.on`) and stop after a confirm. The mic and
  ring banners have their own stop button.
- All three (`wake`, `ringStop`, `listenStop`) are in the worker whitelist. `release` also stops ringing, the screen
  projection, listening, and drops the Wi-Fi lock.
- **Wi-Fi lock**: Android 8-9: the service holds a `WIFI_MODE_FULL_HIGH_PERF` lock while it runs (ColorOS 8.1 puts
  Wi-Fi to sleep with the screen off and the LAN page drops). Android 10+ (0.9.18): held only for 3 min after each
  `/api` call and while listen / screen view runs (`PowerLogic.wifiPerf`); idle hours keep Wi-Fi power save, so the
  first LAN call after a long pause may take a moment longer. Released on stop / release.

## Staying alive (v0.9.6)
- **ColorOS Recents swipe = real force-stop** (`com.coloros.athena` calls force-stop): process dead, package
  `stopped=true`, alarms (Watchdog) cancelled, and AOSP removes the force-stopped package from
  `enabled_accessibility_services`. Nothing of ours runs again until someone opens the app. AOSP (emulator) only
  removes the task: Guard (foreground service) keeps running.
- So: no KidGuard card in Recents. Every activity has `excludeFromRecents`, and the accessibility service finishes all
  our tasks (`ActivityManager.appTasks[].finishAndRemoveTask()`) when the launcher or a Recents screen comes to the
  front (excludeFromRecents alone still shows the front-most task).
- Recovery when killed but not force-stopped: Guard is `START_STICKY` + restarts in `onTaskRemoved`; the system
  rebinds Acc after a kill and `Acc.onServiceConnected` starts Guard + re-arms the Watchdog; Acc restarts Guard on
  `SCREEN_ON` / `USER_PRESENT`. Every Guard tick calls `Acc.ensureEnabled` (re-adds our accessibility entry when
  `accReEnable`, i.e. WRITE_SECURE_SETTINGS was granted).
- State `acc: false` = our accessibility service is not connected; the page and the child app show "turn it back on
  in Settings > Accessibility". Force stop / Clear data in Settings: `setUserControlDisabledPackages` (API 30+),
  `DISALLOW_APPS_CONTROL` (below); both undone by release. No DO API whitelists us from athena.
- Lost contact (`live.lost`) names the likely cause: KidGuard stopped, maybe swiped out of Recents; open the app on
  the child's phone.
## Several kids from one browser page (LAN)
- The page served by phone A (`http://<A>:8770/`) can add phone B by address ("Thêm máy của con" in the list, same
  `addScreen` / `lanHello` / `pairScreen` flow as the parent app). A LAN kid saved with a `target` other than
  `location.origin` is called directly at `<target>/api/...`; A's own kid keeps relative `/api`. The kid list lives in
  A's origin's localStorage (the add screen says so: always open the page from the same phone).
- Phone `/api/*` CORS (`Api.handle`): `OPTIONS` -> `204` with `Access-Control-Allow-Origin: <origin>`,
  `-Allow-Methods: GET, POST, PUT, DELETE`, `-Allow-Headers: X-KG-Token, X-KG-Name, Content-Type`, `-Max-Age: 600`,
  and `-Allow-Private-Network: true` when the request has `Access-Control-Request-Private-Network`. Other `/api`
  answers get `Access-Control-Allow-Origin: <origin>` + `Access-Control-Expose-Headers: X-KG-Seq, X-KG-Age, X-KG-Err,
  X-KG-W, X-KG-H`. Always `Vary: Origin`. Non-`/api` paths (the HTML) get no CORS.
- The origin is echoed only when `Safety.corsOriginOk`: `http://` + a host `hostOk` takes (localhost, IP literal,
  single-label name, `*.ts.net`) and, for IP literals, private / CGNAT (`100.64/10`, Tailscale) / loopback / link-local
  only. Otherwise no CORS header (the browser blocks the read), not `*`.
- Why it is safe: every `/api` call except `hello` and `pair` needs the `X-KG-Token`, never a cookie, and we never send
  `Access-Control-Allow-Credentials`, so CORS only lets a page read what it can already authenticate for. `hello`
  shows only id / pairing open / pin set / mode. `pair` needs the 6-digit code on the child's screen while pairing is
  open, with the existing backoff (`Safety.pairBlockMs`, new code every 3 wrong guesses); the origin allowlist keeps a
  public web site from even reading the answer. The Host check (DNS rebinding) is unchanged.
- Phone HTML CSP: `connect-src 'self' http://*:8770; img-src 'self' data: blob: https://tile.openstreetmap.org
  http://*:8770` (other phones' API: state, cmd, screen frames; `<img>` app icons). `<audio>` (listen) is not limited
  (no `media-src` / `default-src`). The worker and the parent app proxy CSPs are unchanged (no direct cross-origin calls).
- Unreachable phone: direct calls abort after 15 s (`hello` 8 s) and show the usual "can't reach" UI; `401 not paired`
  forgets the kid as before.

## Setup steps (scripts/setup.sh, scripts/setup.ps1, the web installer and the in-app self-setup `SelfSetup.kt` do the same, in this order)
Steps 1-6 are identical on all four paths. `SelfSetup` omits steps 7-8 (`am start`, LAN URL: it already runs inside the app) and Wi-Fi-off is only in it; step 1b (app accounts) is on all four.
1. `adb install -r -g kidguard.apk` (SelfSetup: the app is installed already)
1b. **Keep app accounts** (skipped when already Device Owner or when `dumpsys account` lists no account). Parse `dumpsys account`:
   accounts = `Account {name=N, type=T}`; authenticator of T = the package in
   `AuthenticatorDescription {type=T}, ComponentInfo{PKG/CLASS}` (RegisteredServicesCache section; no entry = the account is
   hidden already, ignore it). PKG listed by `pm list packages -3` (non-system) = keepable, otherwise (system app: Google,
   Xiaomi, Samsung, Oppo/HeyTap, or a preinstalled app) blocks Device Owner: since v0.9.35 all four paths then set up
   **basic mode** in the same run (no app disabling, no reboot; step 2 becomes `dpm set-active-admin --user current
   dev.kidguard/.Admin`, steps 3-6 unchanged) and the final message names `name (type)` with "remove these phone accounts
   and run again for full mode" (a rerun without them upgrades to Device Owner; an active admin does not block it). Keepable packages:
   `pm disable-user --user 0 PKG` (output contains `disabled`) each, wait **>= 14 s** (Android writes the app state ~10 s
   later; a reboot sooner loses it), `sync`, reboot (every Android version), wait for `sys.boot_completed`=1, go on with
   step 2. Right after the boot Android still answers "already some accounts" for up to ~30 s (DPMS re-checks in the
   background): retry `set-device-owner` every 5 s (about 2 min). Afterwards, **always** (also after any error / Ctrl-C /
   page reload): `pm enable PKG` for each, verified (`pm list packages -d`). Where the list is kept: setup.sh
   `~/.kidguard/apps-off.txt`, setup.ps1 `%LOCALAPPDATA%\kidguard\apps-off.txt` (the next run enables them first),
   install.html `localStorage["kg.appsOff"]` (banner "Bật lại các app"; after the reboot the page reconnects through
   `manager.getDevices()` or asks to press Connect again), SelfSetup `Store.ownerPending` = `startedAt|wifiOff|pkg,pkg`.
   SelfSetup cannot survive the reboot in-process: it grants WRITE_SECURE_SETTINGS, stores `ownerPending`, reboots; `Boot`
   (BOOT_COMPLETED) and `Guard.onCreate` call `SelfSetup.resume`: turn Wireless debugging on (`SelfAdb.ensure`, the paired
   key persists), steps 2-6 over self-adb, enable the apps, notification with the result. A pending record older than 10 min
   skips the Device Owner steps and only enables the apps (the apps stay listed, and are retried at every boot / app start,
   while no shell is available). The resume waits for the first unlock after boot (BOOT_COMPLETED is not direct-boot aware).
2. `adb shell dpm set-device-owner dev.kidguard/.Admin` (verify with `dumpsys device_policy`; retry after 1b, see there).
   A known refusal (output mentions accounts / users / already / provisioned: second user, another Device Owner, set up
   already) falls back to basic mode as in 1b; any other failure stops with the Device Owner help as before.
3. `adb shell appops set dev.kidguard GET_USAGE_STATS allow`
4. `adb shell pm grant dev.kidguard android.permission.WRITE_SECURE_SETTINGS`
   then, optional (errors ignored, for remote phone settings): `appops set dev.kidguard WRITE_SETTINGS allow` and
   `cmd notification allow_dnd dev.kidguard` (Do Not Disturb access) and
   `pm grant dev.kidguard android.permission.RECORD_AUDIO` + `appops set dev.kidguard RECORD_AUDIO allow` ("listen" after an SOS;
   the app also grants both itself through its own adb, `AutoGrant`, every 30 min and when a listen starts) and
   `cmd notification allow_listener dev.kidguard/dev.kidguard.NotifDots` (launcher notification dots: counts per app
   only, never stored or sent; writing `enabled_notification_listeners` does not work since Android 8, measured on 8.1)
   and `appops set dev.kidguard SYSTEM_ALERT_WINDOW allow` (basic mode: lets the bank-app pause reopen the bank from the
   background; phones set up earlier get it through the self-adb shell, `AccPause.grantOverlay`, or the Features sheet row)
   and `appops set dev.kidguard REQUEST_INSTALL_PACKAGES allow` (basic mode, Android 12+: updates install without the
   child's tap; phones set up earlier get it through `AutoGrant` or the on-phone step "Let KidGuard update itself")
5. accessibility on: read `settings get secure enabled_accessibility_services`, append
   `dev.kidguard/dev.kidguard.Acc` (`:`-separated, skip if present, treat `null` as empty), write it back, then
   `settings put secure accessibility_enabled 1`
6. `adb shell dumpsys deviceidle whitelist +dev.kidguard`
7. `adb shell am start -n dev.kidguard/.MainActivity`
8. print the LAN URL (`http://<wlan0 ip>:8770`) and tell the parent to enter the 6-digit code.
9. ColorOS only (`getprop ro.build.version.opporom` or `ro.build.version.oplusrom` not empty): hint only, no adb
   setting for it: lock the KidGuard card in Recents (pull it down) and allow "Tự khởi chạy" + background running in
   Phone Manager (a Recents swipe is a real force-stop, see "Staying alive").

## v0.2.1 changes (after review)
- **PIN hash never leaves the phone/worker to the parent.** `state.rules` and every worker response carry
  `"pinSet": true|false` instead of `pinHash`. A rules PUT with neither `pin` nor `pinHash` keeps the old hash
  (phone `Api.kt` and worker both). Once paired, the child's "pair another device" button refuses while no PIN is set (before the first pairing it
  opens pairing; "Mở ghép lại" recovery waits 24 h, see `pairing` events).
- **Commands are idempotent.** Relay commands carry `id`; the phone remembers the last 100 executed ids in Store
  (written with `commit()` before running `reboot`/`release`) and skips repeats but still acks them. Each command runs
  in its own try/catch and is acked even if it fails (a bad `rules` in the same reply cannot stop the commands: rules
  are applied inside `runCatching`). **Destructive commands** (the worker `ONCE` list: `reboot release unpairAll unpair
  clearData update totpSet fileDel`) are delivered **at-least-once**: the worker keeps them until the phone acks the id
  (so a lost reply does not drop a `release`) and the phone de-dups by id. The worker stamps `sent` at the first hand-out
  and drops an unacked one 1 h later (the phone may have released and stopped syncing). "Now" commands (`lock locate
  uiUpdate lock locate message ring wake ringStop listenStop reboot clearData pinSet pinReset pinResetCancel openPairing adbShell unlockEnd fixPerms selfOpen tsInstall`; list = `SHORT`, TTL `SHORT_TTL` = 10 min, reasons in the worker comment) expire on the worker after **10 min**; everything else (`setting devOn devOff bgLimit bgRestore rescan clearCache support ...`) lives 24 h (2026-11-15: friction that does not stop abuse), so a
  `ring` queued overnight does not fire in the morning. The queue holds 20 entries: when full, a new repeatable command replaces its older queued copy (see Limits), else `429 queue full`.
  `ONCE_WINDOW` (1 h): a command handed out and not acked within it is dropped (also any command that carried a secret).
  Secrets in a queued command (`pin`, `new`, `secret`, `token`): the first hand-out to the phone carries them, the copy that
  stays queued until the ack has none (a re-send after a lost reply fails `pin needed` on the phone: ask again).
  `answer`: the worker keeps `id` as command id only if it matches `^[\w.-]{1,64}$`, else it makes a UUID; the request id
  is always copied to `req` and the phone answers `req` (falling back to `id` on LAN).
- **State** adds `frp` (`unsupported` < Android 11 or not owner | `off` | `on` | `error` set refused) and `dnsError` (`""` or a short reason when the Private DNS host was rejected; the phone then falls back
  to opportunistic DNS and clears the restriction so the child keeps internet) and keeps `usageAccess` (page warns when false).
  Also `usageOff` (= `!usageAccess`; the phone raised one `tamper` event for this revoke, shows the child a notification
  to turn it back on, and logs `usage_on` when it returns) and `dnsPausedUntil` (ms, 0 when no pause is running).
  A `tamper` event is also raised once per package when a non-system app becomes the default HOME (it stays exempt from limits).
- **Relay sync**: the phone may omit `state` when nothing but `t` and sub-minute time counters (`usedMs`, `leftMs`, `apps[].ms`)
  changed; it sends a state at least every 5 min. So on the relay `state.t` can be minutes old: "online" = `seen`, not `t`.
- **Partial state** (0.9.14, bandwidth): every sync reply carries `"merge": 1` from a worker that can merge. Until the phone
  has seen it (and after a relay change) it sends whole states; then only what changed since the last state the worker took:
  `{"_part":1, <changed top-level keys>, "_drop":[removed keys], "_apps":{"set":[app objects],"del":[pkg]},
  "_trk":{"from":<first point t>,"add":[new points]}}` (or a whole `"track"` when the old last point is gone). The worker
  merges onto the stored state (`mergeState`: apps by pkg then sorted by `ms` desc, track points only newer than the last
  stored one, then `t >= from`). Nothing stored => reply `"full": true` (state ignored, acks still taken) and the phone sends
  a whole state next. A whole state at least every 6 h.
  The worker writes `seen` at most once per minute.
- **Live input**: the worker answers `POST /input` with `409 {"error":"not live"}` when `liveUntil < now`, and drops
  queued inputs older than 5 s when delivering. Frame `seq` starts at `Date.now()` so it stays increasing across restarts.
- **Release** puts back what the phone had before our first apply (saved once in `Store`: auto-time on/off and Private DNS
  mode + host): auto time via `setAutoTimeEnabled` (API 30+), Private DNS host re-set when it was `hostname`, `off` written
  only if WRITE_SECURE_SETTINGS is held (DPM cannot set "off"; otherwise it stays opportunistic). Nothing saved = the old
  behaviour (auto time on, opportunistic).
- **Release** also: removes our accessibility entry, clears paired tokens, unsuspends every launchable app, cancels the
  watchdog, stops Guard; returns `"failed"` if still Device Owner afterwards.
- **HTML pages** (website `/app/`, `/go`, `/install`, `/qr`: `website/_headers`; phone LAN `/`) send `X-Frame-Options: DENY` and a CSP with `frame-ancestors 'none'`.

## Location history (v0.2.1)
- Rules: `"trackMin": 5` (0 = no history points, allowed 0/1/5/10/15/30/60; default 5 since 0.9.14, was 10; a phone before 0.9.14 turns 1 into its default). Independently the Guard refreshes `state.location` (last fix, zone in/out events) every 15 min, also with `trackMin=0`. Since 0.9.18 a good history fix (accuracy <= 100 m) is also written to `state.location`, and the separate 15-min fix is skipped while those keep it fresh. A phone lying still (2 same-spot fixes with the screen off, significant-motion sensor armed) drops to one history fix per 15 min and one refresh per hour until the sensor fires or the screen turns on (`LocPace`); no sensor = no slow pace.
- Phone (Guard): every `trackMin` minutes takes one fix (getCurrentLocation API 30+, else requestSingleUpdate,
  timeout 30 s, else lastKnown if < 2× trackMin old). Store a point only if it MOVED from the last stored point (distance ≥ max(30 m, the larger accuracy of the two
  fixes); a fix worse than ±150 m never counts as moving) OR the same-spot gap passed (max(trackMin − 1.5 min, 10 min), cap 60 min).
  Leaving: the newest unstored same-spot fix (memory) is stored right before the first moved point, so a stop's last point = the leave time.
  Page "Hành trình": stops (100 m cluster, ≥ 5 min) named by zone, with arrive = first point, leave = last point, trip time + km between. Keep 7 days, max 3000 points (drop oldest). Point = `[t, lat, lon, acc]`
  (t ms, lat/lon 5 decimals, acc m int). Permissions: manifest declares ACCESS_BACKGROUND_LOCATION; as Device Owner
  call `setPermissionGrantState(GRANTED)` for FINE/COARSE/BACKGROUND location.
- State: `"track": [[t,lat,lon,acc], …]` = points since local midnight of YESTERDAY (keeps state small).
- LAN: `GET /api/track?days=7` -> `{"track": [...]}` (all stored points, max 7 days).
- Page "Vị trí" tab: map of the chosen day (Hôm nay / Hôm qua; via LAN also older days) drawn WITHOUT external
  scripts: OSM tiles as <img> (`https://tile.openstreetmap.de/{z}/{x}/{y}.png` first, `https://tile.openstreetmap.org/...`
  as fallback per tile: .org is blocked on some ISPs) + an SVG polyline/dots, auto-fit zoom, point list with times, tap a
  dot -> time + accuracy. CSP of HTML pages (phone, parent app proxy, worker) must allow `img-src 'self' data: blob:
  https://tile.openstreetmap.de https://tile.openstreetmap.org`.

## SOS and "listen around" (v0.5)
- **SOS**: the child's KidGuard screen has a red "SOS" button (one confirm). The phone stores `{id, t}` (max 10;
  a second press within 30 s is ignored), logs "Con bấm SOS", takes a location fix (`locate`) and syncs the relay at
  once (and again 15 s later, for the fix). State adds `"sos": [{"id": "a1b2c3", "t": 1793940898493}]`.
- Command `sosAck {sos?}` (no `sos` = all; `sos` = one SOS id, or 0.9.41 several ids joined by `,`, the page sends the ones it showed): removes them. v0.9.3: the SOS id moved from `id` to `sos`,
  because `id` is the command's dedupe key and the relay fills in a random one (so a relay ack never matched anything).
  Result `ok | no such sos`. In the worker whitelist.
- Page: red banner on every tab while `sos` is not empty: time, "Xem vị trí", "Nghe xung quanh" (LAN only), "Đã xử lý" (= `sosAck` without `id`).
- Parent app: an SOS id not seen before and younger than 6 h is notified on channel `kg_sos` (high importance),
  also on the first look at a kid. The background check runs ~every 15 min; relay kids also get FCM (below, seconds).
- **Listen around** (one way; LAN / Tailscale = the HTTP stream below, relay = WebRTC audio, see "Listen around over the relay" under "Video call"):
  `GET /api/listen?k=<token>` -> `200 audio/aac` (ADTS, AAC-LC 16 kHz mono 32 kbit/s, ~14 MB/h), no Content-Length,
  streamed until the client closes or 10 min pass (the page can start again). v0.9.6: a new request **replaces** a running
  session (a parent whose Wi-Fi dropped can start again at once; `busy` is no longer answered). Errors: `503 {"error": "no mic permission" | "mic blocked" | "mic failed" | "notice blocked"}` (v0.6: refused when
  the child's notifications or the `kg_listen` channel are off, so listening can never be silent). Each session is logged
  ("Bố mẹ đã nghe âm thanh xung quanh từ HH:mm (…)") and the child screen shows "Lần nghe gần nhất".
- The child is **always** told; it does not depend on `showWatching`: a beep, an ongoing high-importance notification
  "Bố mẹ đang nghe âm thanh xung quanh máy", a status line on the KidGuard screen, and log lines at start and stop.
  Android itself also shows its mic indicator.
- State adds `"listen": {"on": bool, "since": ms, "err": "<last error or empty>", "mic": bool}`. `mic` false = KidGuard has no
  microphone permission (basic mode: the user allows it in Settings): the page says so before starting instead of failing.
- v0.9.6: the phone ends a session by itself, with `listen.err`, when the mic gives only amplitude 0 for 2.5 s
  (`"mic silenced"`: a ROM privacy layer such as ColorOS blocks it although RECORD_AUDIO is granted) or no encoded bytes
  are read for 4 s (`"mic stalled"`: also when nobody reads the stream). `on` is cleared by whichever session is current,
  so it never sticks. A partial wakelock is held while a session runs. The page sends `listenStop` when the parent stops.
- The phone gets RECORD_AUDIO through `setPermissionGrantState` (with the location ones) and adds the `microphone` type to
  its foreground service only while listening. `release` revokes RECORD_AUDIO (DENIED, then DEFAULT) and stops a session.
- Parent-app proxy: `GET /k/<t>/api/listen` is passed through as a stream (never cached).
- Over the relay (after 0.9.27) the same state / notice / log / `listenStop` apply; the audio is not this stream, see "Video call".

## Video call (after 0.9.27, relay only, parent -> kid)
Signaling goes through the relay; picture and sound go straight between the two devices (WebRTC P2P; a TURN server only
when no direct path works). Flow: `docs/flows/remote.md` §2.15.
- `GET /c/<id>/call[?ice=1]` (parent or phone token) -> `{call, ice?}`; `call` = `null` or `{id, st, by, at, sdp?, why?}`:
  `id` 16 hex (made by the page), `st` `ring | on | end`, `by` the calling parent's name, `sdp` = the offer while `ring`,
  the answer while `on`; `why` (end only) one of `hangup decline busy timeout failed noperm nocam missed`.
  `ice` = `[{urls, username?, credential?}]`: Cloudflare + Google STUN, plus short-lived Cloudflare TURN credentials
  (4 h, minted with worker secrets `TURN_KEY_ID` / `TURN_KEY_TOKEN`, cached 1 h; without the secrets STUN only).
  `?id=<id>&st=<st>` long-polls up to 12 s while the call is still `id` in state `st`. `call.kind` = `"listen"` for
  "listen around" (absent = a video call).
- `POST /c/<id>/call` `{op, id, sdp?, why?}`:
  `offer` (parent; optional `kind: "listen"`; `sdp` starts with `v=0`, max 16 000 chars, non-trickle: all candidates inside) -> 409 `busy` when
  another call is not ended; the phone is kicked (socket, else FCM wake). A shared phone token may not offer once the
  family has parents or a PIN (403 `join`).
  `answer` (phone, only while `ring`, else 409) -> `on`. `end` (`why`) -> `end`; the phone is kicked when a parent ended it.
  Wrong `id` -> 404 `no call`; bad id / sdp / op -> 400.
- Timeouts in the worker: `ring` > 45 s -> `end` `missed`; `on` > 1 h (`kind: "listen"`: 10 min + 15 s) -> `end` `timeout`; an `end` is dropped after 60 s.
  `why` also takes the listen refusals `no mic permission`, `notice blocked`, `mic blocked`, `mic failed`, `ringing` (same words as `listen.err`).
- Phone `POST /sync` reply adds `call: {id, st, by, kind?, sdp?, ice?}` while a call exists (`sdp` + `ice` only while `ring`).
- Kid screen: `CallActivity` (WebView, `ui/call.html` + `ui/assets/rtc.js` served from the APK at
  `https://appassets.androidplatform.net`, CSP `connect-src` none: all network goes through the `KG` bridge). The child
  always answers by hand (no auto-answer). Logs `log_call_in`, `log_call_done`, `log_call_missed`.
- Media profile (both sides, `rtc.js`): video 320x240, 15 fps, max 150 kbps (`b=AS:150` + `maxBitrate`); Opus mono
  24 kbps, DTX, FEC, `maxplaybackrate=16000`. DataChannel `kg`: `{t:"bye"}`, `{t:"cam", on}`, `{t:"mic", on}`,
  `{t:"more"}` (parent gave 15 more minutes). Calls stop at 15 min unless extended; the parent page ends a call that is
  hidden for 60 s; the kid ends it when the call screen leaves the front.

### Listen around over the relay (after 0.9.27)
Same `/call` slot and routes with `kind: "listen"` (so a video call and a listen exclude each other: 409 `busy`). The parent's
offer has one `recvonly` audio m-line + the `kg` DataChannel (no camera, no mic prompt on the parent; a plain http page can do it).
The phone does NOT ring and nobody taps Answer: `K/remote/ListenCall.kt` runs `Listen.openRtc` first (RECORD_AUDIO grant paths
as the LAN stream, notice-visible check, microphone foreground service, **beep + ongoing notification + log line**, state
`listen.on/since`), then a headless WebView (`ui/listen.html` + `assets/rtc.js` from the APK, no activity, nothing on screen)
answers with the microphone only (`sendonly`; echo cancel / noise suppression off, gain on). Opus mono 24 kbps DTX/FEC like the
call; ~11 MB per 10 minutes at the cap, far less in silence (DTX). A refusal ends the call at once with the listen `why`
(`ear.<why>` in the page). Ends: parent Stop (`end` + `bye`), `cmd listenStop` (-> `ListenCall.stop`), 10 min cap on the page,
phone (`ListenCall.cap`) and relay, `release`. LAN / Tailscale kids keep `GET /api/listen`; the page picks by how the kid is reached.

## Instant push via FCM (v0.5, optional, relay kids only)
- "Relay kid" = saved with `via: "relay"`, or a LAN / Tailscale kid saved with `relay {url, id, token}` (the parent app
  registers with that relay and matches the push `kid` against its id; before 2026-11-16 such kids never got push).
- Worker secret `FCM_SA` = the Firebase service-account JSON (`project_id`, `client_email`, `private_key`, `token_uri`).
  Not set = push off; everything else works. `FCM_URL` (var, tests only) overrides `https://fcm.googleapis.com`.
- `POST /c/<id>/push` (kid token header) `{"fcm": "<FCM registration token>"}` registers, `{"fcm": "...", "off": true}`
  removes -> `{"ok": true, "enabled": <FCM_SA set>, "devices": n}`. Max 5 tokens per kid (oldest dropped); token
  `^[\w:.-]{20,400}$` else 400.
- On every `sync` with a state: each `sos` younger than 6 h and each `requests` entry younger than 1 h whose event id
  (`s:<id>` / `r:<id>`) is not in the last 50 pushed is sent once to every token, as an FCM HTTP v1 **data-only**
  message, `android.priority: HIGH`, ttl 6 h: `{"kind": "sos"|"req", "kid": "<relay id>", "eid": "s:a1b2c3", "t": "<ms>", "min": "30"|""}`.
  No names, notes or locations go to Google. An FCM 404 for a token removes it (a 400 may be our config: kept).
  At most 5 events per sync. Binding: the first `sync` for an id must carry a `state` object, else 400.
- Parent app: registers its token with every relay kid on start, on `setKids` (removed kids: `off`) and on
  `setNotify` (off = unregister). A message for a known relay kid whose id the background check has not seen yet is
  posted with the same key as the check would use (`sos:<id>` / `req:<id>`, same notification id) and marked seen,
  so the two never double-notify. Build: `android/parent/google-services.json` (git-ignored) present = FCM on; Gradle turns
  it into the `google_app_id` / `gcm_defaultSenderId` / `google_api_key` / `project_id` resources (no google-services
  plugin). Needs Google Play services on the parent's phone.
- **Kid push wake (2026-11-17):** the kid app (kid flavor, `KidPush.kt`, `android/app/google-services.json` client
  `dev.kidguard`, git-ignored; missing = off) sends its own FCM token in the sync body `fcm` (each process start / token
  change, until one sync took it). The worker keeps it (`kfcm`, one per kid, 404 drops it) and sends the data-only
  high-priority message `{"kind": "wake", "kid": "<id>"}` (ttl 600 s) when (a) the phone has not been heard for 20 min
  (DO alarm; then every 20 min 9 times, then hourly, up to 2 days quiet; `wk {at, n}` rate limit, reset when heard), or
  (b) a parent reads `GET state` while the phone is quiet >= 3 min (`WAKE_PARENT_MS`, tests 1500; once per 3 min).
  Phone: `KidPushService` -> `Relay.wake` (wakelock, drop a quiet socket, sync now) or `Guard.start`. Does not reach a
  force-stopped app or a phone that is off / offline.

## Telegram bot on the child phone (v0.9.x, Tg.kt)
- The family makes its own bot at @BotFather; the token goes to the phone with command `tgSet {token, lang: "vi"|"en", name}`
  (LAN or relay; relay: deliver-once). The phone checks it with `getMe` (results `bad token` / `telegram unreachable`), calls
  `deleteWebhook` + `setMyCommands`, makes a 6-digit link code (15 min, 5 wrong tries). `tgSet {}` without a token = new code / new lang.
  `tgCode` = new code, `tgUnlink {chat: "<id>"}` = drop one chat (`no such chat`), `tgOff` = forget token, chats, code (deliver-once).
  The token never leaves the phone: not in state, not in rules. Release (both modes) = `tgOff`.
- The phone long-polls `getUpdates` (timeout 50 s, offset kept), from Guard start; idle without a token. 401/404 -> `err: "bad token"`,
  409 -> `err: "conflict"` (another poller / webhook; one deleteWebhook try). Private chats only. An unlinked chat may only send the code
  (`/start <code>` or the 6 digits); max 5 chats; a chat that blocks the bot (403) is dropped.
  Other getUpdates errors: 429 waits `parameters.retry_after` (1 s .. 5 min), anything else 5 s doubling to 60 s (0.9.18).
  A text above 4000 characters goes out as several messages split at line ends (buttons under the last). 4 or more plain
  events waiting at once (phone was offline) = one message per chat ("N new alerts" + one line each); tamper, new app,
  events with a photo, SOS and requests are always their own message.
- State: `"tg": {"on", "bot": "<username>", "err": "", "chats": [{"id": "<string>", "name", "at"}], "code": "123456"|null, "codeUntil"}`.
- Commands in the chat (TgLogic, vi/en texts): /status /apps /locate (last fix + a fresh one within ~30 s, sendLocation) /events
  /code (current Authenticator code + seconds left + next code, read-only; "off" text when no secret) /bonus N (bonus today += N, 1-600) /pause N (pausedUntil = now+N min) /resume /lock /ring /msg <text>
  /block, /unblock (app buttons `b:<pkg>` / `u:<pkg>`) /limit N (dailyMin, or 7 x N when dailyByDay is set; 0 = none) /bed on|off
  /screen (Acc.capture 1280 px -> sendPhoto) /rec N (5-60 s mic, same notice as "listen", AAC document) /files (buttons `f:<id>` ->
  sendDocument, <= 50 MB) ; a document sent to the bot (<= 20 MB) is saved like a LAN upload (Download/KidGuard) /help /unlink.
  Rule edits go through `Guard.setRules` with `rev = max(now, rev + 1)`, so the page sees them as a newer rules object.
- Buttons (TgLogic.menu/sub/statusKb/replyKb): /menu = inline panel of every command; callback data `c:<command line>` runs it,
  `n:<panel>` (main, bonus, pause, limit, bed, rec) swaps the panel in place (editMessageText), `r:status` refreshes /status in place.
  A persistent reply keyboard (Status, Location, More time, Lock, Menu) is sent on /start, /help and link; its labels (vi or en) map back
  to commands. /bonus /pause /limit /bed /rec without a number show their panel; /msg without text asks with force_reply and takes
  the next plain text (10 min). Any other plain text = the menu. tgSet also sets setMyCommands (menu first), setMyDescription,
  setMyShortDescription. Pickers (block / unblock / files) and panels opened from a button replace that message and end with « Menu;
  a picked app turns the picker into the answer (+ "another one"). Every tapped button is acked at once on its own thread
  (answerCallbackQuery, "working…" toast) and commands send sendChatAction (typing / find_location / upload_photo / record_voice).
  setMyShortDescription. The page shows `https://t.me/<bot>?start=<code>` (+ QR): Telegram sends `/start <code>`, which links.
- 0.9.16 (Tg): every "more time" tap (request buttons, /bonus, panel +N) goes through `Grant.apply` with `now.why` (pass during
  bedtime / schedule / pause, today's bonus otherwise). Request alert buttons are N/2, N, 2N (rounded to 5) `ry:<req>:<min>` + `rn:<req>`;
  kind "app" requests and "installed X" alerts get `ra:<req>` / `rb:<req>` / `ay:<pkg>` / `an:<pkg>` (rules `approved` / `blocked` edit; the
  answer command gets `always: true`). `/requests` (menu button) re-sends the open requests with buttons. Each alert's message ids are kept per chat
  (memory): any answer, from any chat or the page / app (the request vanishing from `requests[]`, `lastAnswer`), edits every copy to
  "<who> đã cho 15 phút lúc 20:14". A getUpdates batch after a gap > 10 min (`tgLastOk`) does not run action callbacks: "máy con vừa online lại sau N phút".
  A double tap (same chat+message+data within 4 s) runs once. Panel +N / pause / limit edit the panel into the result + `z:<key>` Undo (restores
  only the fields that edit changed). SOS alert: buttons `k:<sos>` (runs `sosAck`), the last stored fix as a pin + a "Vị trí lúc HH:mm, sai số ~N m"
  label (old = "cũ"), a second pin when a fix newer than the press arrives (the SOS already triggers `locate`). `/limit N` with differing per-weekday
  limits asks "mọi ngày / chỉ hôm nay" (`/limit all|today N`). `/quiet all|important|sos` per chat (`chats[].quiet`): info events are sent with
  `disable_notification`, below the level they are skipped (`Events.important`); SOS always. `/lost [on <msg>]` = confirm buttons, `c:lost go` sets
  rules `lostOn` (+ `lostMsg`), `/lost off`. `/status` shows `now` (why + until). A command in a group chat gets one reply per 10 min
  ("open a private chat"). 401/404 raises event kind `tg` once. setMyCommands has requests, quiet, lost.
- /rec answers as a voice message (sendVoice, M4A remuxed from the ADTS stream by `Adts.kt`, plays inline); fallback: the raw .aac as a document.
  The voice carries a "Download" button (callback `a:<16 hex>`): the same M4A as a document, kept 7 days in files/tgrec (max 100 MB, pruned early under 500 MB free; deleted on release).
- Reliability: an alert counts as seen only once at least one chat got it (else retried every 30 s, dropped after 24 h);
  alerts have their own sender thread; one /locate and one /rec at a time ("busy" otherwise); 429 waits retry_after once.
  Link codes: max 3 wrong tries per not-linked chat, then that chat is ignored for 15 min (in memory) and may try again (plus 5 per code). Typed action commands older than 10 min (lock, ring, wake, release, reboot, clearData, unpair, unlink, lost, pin*) or 60 min (all others) are refused; a new link is announced to the other chats.
  `unpairAll` also unlinks every Telegram chat (the bot token stays). A photo sent to the bot is saved like a file.
- Language per chat (`chats[].lang`): set at link from the Telegram user's `language_code` (vi* -> vi, else en; none -> the bot
  language from tgSet), changed with /lang [vi|en] or the 🌐 menu button; alerts and answers use it. setMyCommands /
  setMyDescription / setMyShortDescription are set for language_code vi, en and the default, at tgSet and once per app start.
- Alerts, every Guard tick, each id once (`tgSeen`, last 80; linking a chat marks the past as seen): every SOS (+ location),
  every request with buttons `ry:<id>:<min>` (bonus + `answer ok`) / `rn:<id>` (`answer` no), every event; a `tamper` event waits up
  to 10 s for its wrong-PIN photo and is sent as a photo.

## Telegram alerts via the relay worker (older, unused by the page since the phone bot)
- Worker secret `TG_BOT` = bot token from @BotFather. Not set = feature off (`GET /c/<id>/tg` -> `{"enabled": false}`, the page hides
  the card). `TG_URL` (var, tests only) overrides `https://api.telegram.org`. Each family self-hosts the relay with its own bot.
  LAN-only families cannot use it (the phone never talks to the worker).
- `GET /c/<id>/tg` (kid token) -> `{"enabled": true, "linked": bool}`. `POST /c/<id>/tg {"name": "<kid>", "lang": "vi|en"}` ->
  `{"enabled", "linked", "link": "https://t.me/<bot>?start=<code>"}`; code = the 16-hex relay id + 16 random hex, one-time, valid 15 min;
  `name` (<= 30 chars, `<>` and control chars stripped) and `lang` are used in the messages. `POST {"off": true}` unlinks. 502 = Telegram unreachable.
- Webhook `POST /tg/<sha256("tg:" + TG_BOT)[:32]>` (other path 404; only registered when `TG_BOT` is set). Register once:
  `curl "https://api.telegram.org/bot$TOKEN/setWebhook" -d url=https://<worker>/tg/$(printf 'tg:%s' "$TOKEN" | shasum -a 256 | cut -c1-32)`
  (`scripts/relay.sh` does it when run with `TG_BOT_TOKEN=...`). Always answers 200. Only private chats are read:
  `/start <code>` links that chat to the kid in the code (unknown / expired / used code: a help text); `/stop_<relay id>` from the linked chat unlinks.
  The webhook reaches the kid's Durable Object through an internal-only path (`x-kg-internal` header; the public route regex never matches it).
- DO storage: `tg` = `{chat, name, lang, at}` (linked), `tgCode` = `{code, exp, name, lang}` (pending), `tgSeen` = last 50 event ids sent.
- On every `sync` with a state, the same moments as FCM, each id once (own dedupe list, <= 5 per sync, a retry never double-sends):
  `sos` < 6 h, `requests` < 1 h, events < 1 h of kind `newApp` or `tamper`. Plain text, no buttons; kid name + kind (+ minutes), no location,
  note or app name. Telegram answers 403 (bot blocked) = unlinked.

## App icons (v0.5)
- LAN: `GET /api/icon?pkg=<pkg>&k=<token>` -> 96 px PNG drawn by the phone.
- Relay: after each successful sync the phone sends up to 30 not-yet-sent icons:
  `POST /c/<id>/icons` (token header) body `{"<pkg>": "<base64 48 px WebP>", ...}` -> `{"have": n}`.
  pkg must match `[A-Za-z0-9._]{1,200}`, junk entries are skipped, max 400 icons per kid (`icon:<pkg>` in DO storage).
  Sent pkgs are remembered per relay url+id (prefs `relayIcons`), so each icon goes up once; a removed app is forgotten.
  A full list drops the icons of pkgs that are not in the stored `state.apps` (0.9.18).
- `GET /c/<id>/icon?pkg=<pkg>&k=<token>` -> `image/webp`, `cache-control: private, max-age=86400`; 404 if unknown.
- `GET /c/<id>/icons?k=<iconKey>&v=<state.iconV>` -> JSON `{pkg: base64 webp}`, every icon in one reply,
  `cache-control: private, max-age=31536000, immutable`. `state.iconV` (relay GET state only) = time of the last icon
  upload, so a new upload changes the URL. The page uses it for relay kids (one request, not one per app).
  The `?k=` token is accepted only on this GET (an `<img>` can't send headers); every other route needs the header.
  Relay (2026-11-10): the page sends `?k=<sha256("icon:" + token) hex>` instead (request URLs are logged); the worker stores
  that key at the first authenticated request after each wake. The raw token in `?k=` still works for older pages.
- Relay storage GC: a phone not seen for 30 days -> the Durable Object deletes everything (DO alarm armed by sync); the
  phone re-binds on its next sync. An id bound but never heard again (no second sync, no rules, no queue) within 7 days is
  deleted too (`UNUSED_MS`, `born`).
- No APK on the relay (plan B). `ui/install.html` fetches `kidguard.apk(.sha256)` relative: : the website has none, so it shows "download
  the latest from GitHub (`releases/latest/download/kidguard.apk`), then pick the file" (GitHub assets have no CORS).
- Resume drops a queued `lock` (`lk`): a rules PUT with `pausedUntil` not in the future, while the stored rules or the phone's state
  had one in the future ("Resume all" only PUTs rules), deletes every still-queued `lock` of that kid (other commands stay).
- First bind cap (audit H4): a first sync that would create an id counts against the client network (`netKey`: IPv4 or
  IPv6 /64) in a `Gate` Durable Object, `BIND_PER_DAY` = 30 a day (env var overrides, tests use 60); beyond it
  `429 {"error":"too many new pairings from this network, try tomorrow", "retryAt": <ms epoch, next UTC midnight>}`. Every daily-cap 429 (bind, `PAIR_PER_DAY`, `INVITE_PER_DAY`, watch 30/day) carries `retryAt`; the page appends "Try again at <time>" (`e.retryAt`). Already bound ids are never affected.
- Rules `pinHash` on the worker (unsalted `sha256("kg:"+pin)`, handed to the phone once): dropped as soon as the phone's
  state shows `rules.pinSet: true` and `rules.rev` >= the stored one; the stored rules then carry `pinSet: true` only.
- Sync state over 120 KB: `413 state too big`. The phone then sends a trimmed state (no `events`, `track`; apps reduced to
  `pkg label ms blocked suspended protected new`) with **`relayBig: true`** for 30 min, then tries the whole one again.
  A kick after a failed sync no longer skips the retry backoff.
- The page uses `${base}/icon?pkg=..&k=..` on LAN; relay kids (also through the parent-app proxy) use `/icons?v=` above.

## Parent app (v0.5, module `:parent`, `dev.kidguard.parent`)
Shows the same `ui/index.html` in a WebView (`scripts/build.sh` -> `build/kidguard-parent.apk`). The page only uses same-origin
URLs, so the app runs a small HTTP server on `127.0.0.1:<port>` (cleartext is allowed app-wide because the proxy calls
the child over plain http; the WebView itself may only reach `127.0.0.1:<port>` and the OSM tiles).
- **Port**: random free port chosen once and stored; reused while free. localStorage is per origin, so if the port
  changes the page loses its kid list: the app mirrors it via `KGApp.setKids` (the page should restore from it).
- **Access**: the WebView first loads `/s/<secret>` (secret random per app start) which sets cookie
  `kgs=<secret>` (HttpOnly, SameSite=Strict) and 302s to `/`. Without the cookie or with a `Host` other than
  `127.0.0.1:<port>`: 403.
- `GET /` -> index.html (same headers as the phone, CSP also `connect-src 'self'`); `GET /ui/<name>.js|css` -> its files.
- **Proxy** `ANY /k/<t>/<rest>` -> `<target>/<rest>`; `t` = base64url (no padding) of `http://<host>:<port>` (LAN /
  Tailscale) or `https://<worker host>` (relay); other schemes, userinfo, paths -> 400. Forwards method, query, body and
  `X-KG-Token`, `X-KG-W`, `X-KG-H`, `Content-Type`; returns status, body, `Content-Type` and all `X-KG-*` headers.
  Timeouts: connect 6 s, read 40 s. Network error with no snapshot -> `502 {"error":"offline"}`.
- **Snapshot cache**: a 200 JSON `GET /api/state` (LAN) or `GET /c/<id>/state` (relay) is stored per target+path. If the
  same request later fails (network error / timeout / 5xx) the stored body is returned with status 200 and
  `X-KG-Cached: <epoch ms stored>` and `X-KG-Why: net | cf:<Cloudflare error code> | http:<status>` (why the live request
  failed; no snapshot -> 502 `{"error":"offline","why":...}`). The page shows "relay server down" for `cf:` / `http:`.
- **History**: one file per kid per day (`files/history/<sha256(target+path)>/<yyyy-mm-dd>.json`, last snapshot of the
  day). Retention is a setting (default unlimited; 30 / 90 / 365). Storage guard: before writing, if free space < 200 MB
  the oldest days (all kids) are deleted until >= 200 MB or only 7 days remain.
  - `GET /app/history/days?k=<t>&p=<rest>` -> `["2026-11-06","2026-11-05",...]` newest first (`<rest>` = `api/state` or `c/<id>/state`).
  - `GET /app/history/day?k=<t>&p=<rest>&d=2026-11-05` -> `{"day","t","body"}`, 404 if none.
- **JS bridge** `window.KGApp` (strings): `version()` -> `"1"`; `files()` -> `"true"` (v0.9.8+: the WebView has a file chooser
  (multiple, `ACTION_OPEN_DOCUMENT`) and saves `<a download>` blob/data/same-origin downloads into `Download/KidGuard`; the page checks
  `window.KGApp && KGApp.files && KGApp.files() === "true"`); internal `dlBegin/dlChunk/dlEnd` (used by the app's injected script only); `setKids(json)` json = `[{name,target,via:"lan"|"relay",id,token,lv?,relay?}]` (`relay` = `{url,id,token}` of a LAN kid's relay: the proxy also allows that origin)
  (called whenever the list changes); `getNotify()` / `setNotify("true"|"false")` (default true);
  `getHistoryDays()` -> `"0"` (unlimited) | `"30"` ...; `setHistoryDays(n)`; `getLang()` -> `""` (phone setting) | `"en"` | `"vi"`,
  `setLang(code)` same codes, others ignored, same code = no-op: language of the app's own notifications (Android 13+: the
  system per-app language). The page translates itself; the WebView is not reloaded.
  App settings screen (page, APP only): `notifOn()` -> `"true"` when notifications are on and allowed by Android; `openNotifSettings()` opens Android's notification
  settings for the app; `appVersion()`; `checkUpdate()` checks GitHub now and `installUpdate(tag)` downloads `kidguard-parent-<tag>.apk` in-app (HttpURLConnection, https redirects)
  and opens the system installer (asks for "install unknown apps" first); both report by calling `window.kgUpd({st: checking|ok|new|fail|perm|dl|inst|dlfail, tag?, pct?})`;
  `takeSettings()` -> `"true"` once after the update notification was tapped (the page opens App settings; `kgAppOpen()` is called on a running page).
  `takeOpen()` -> `'{"target","id"}'` once after a notification tap (then `""`): the page opens that kid (on boot, and
  the app calls `window.kgAppOpen()` on a running page).
- **Background**: JobScheduler job every ~15 min (+ once at app start) fetches each kid's state with its token and
  notifies once per event: new entry in `requests` ("<name> xin thêm <min> phút"; the first look at a kid only records
  existing ones) and lost connection > 30 min ("Mất kết nối với máy của <name>"; LAN: `state.t` old or fetch failing
  for > 30 min, relay: `seen` old). Skipped when the parent phone itself has no network. Tapping opens the page on that kid.
- **Notification actions** (v0.9.6, no new wire format): a time request gets "+15 / +30 min" (the child's own amount
  replaces the nearer one) and "Decline". A grant does what the page's request card does: `GET state` (request gone =
  "already answered"), `PUT rules` with today's `bonus.min` raised, then `answer {id, ok: true, min}`; decline sends only
  `answer {id, ok: false}`. LAN `no such request` = already answered. SOS gets "Show location" (`geo:` at `state.location`
  when known, else the page) and a full-screen intent where Android 14 still allows it. Notifications group per kid.
  Intents carry only sha256(target|id) and the request id, never a token.

## v0.6: chores, notes, zones, events, rule-change log, templates

Rules, NEW optional fields (phone ignores bad entries; limits enforced on the phone):
```jsonc
{
  "chores": [{"id": "c1", "name": "Rửa bát", "min": 20}],   // max 20; id [\w-]{1,16}; name <= 40 chars; min 5..120
  "zones":  [{"id": "z1", "name": "Nhà", "lat": 21.03, "lng": 105.85, "r": 150}]  // max 10; r 50..2000 m
}
```
- **Chores**: the child's "Xin thêm giờ" dialog offers the parent's chores ("Rửa bát +20 phút") besides the free form.
  The request then carries `"chore": "c1"` and `min` = that chore's minutes. The parent still approves; nothing is
  granted automatically. Request shape: `{"id","t","min","note"?, "chore"?}`.
- **Note with an answer**: `answer {id, ok, min?, note?}`; `note` <= 200 chars, shown to the child in the
  "Bố mẹ đã đồng ý / chưa đồng ý" notification ("… Bố mẹ nhắn: <note>").
- **Zones / events**: the phone checks each new location fix against `zones` (inside = distance <= r; leaving needs
  distance > r + 50 m so a fix at the edge doesn't flap); fixes with accuracy > 300 m are ignored and appends events. State NEW:
  `"events": [{"id": "e1a2b3", "t": <ms>, "kind": "zoneIn|zoneOut|battery|newApp|clock|pairing|checkin|launcher|adminOpen|call|tsDown|tsUp|refused", "text": "Đã đến Nhà"}]`; a tamper event of a lasting condition also has `"key": "accoff|usageoff|home"` (see state `tamperOn`),
  newest last, at most 50, older than 7 days dropped. `text` is free text for people, in the child app's language
  (English or Vietnamese, from the phone / per-app language; the examples here are the Vietnamese ones). The same goes for
  log lines (`log[].m`; NEW `log[].k` = kind, the string id without `log_`: `pin_wrong`, `unlock`, `paired`, `tz`,
  `loc_fixed`, `usage_off`, `new_app`, `suspended`, `unsuspended`, `recover_open`, `recover_req`, `zone`, `cmd`, `cleared`,
  `totp_off`, `totp_on`, `release_fail`, `released`, `sos`, `acc_settings`, `acc_rebind`, `acc_reenabled`, `acc_paused`, `acc_resumed`, `view_start`,
  `control`, `proj_denied`, `listen_start`, `listen_done`, `listen_err`, `ring_stopped`, `dns_fallback`, `admin_open`,
  `checkin`, `rules`; absent on older lines, pages show a plain dot) and error strings (`dnsError`, `update.err`, `ts.err`). Code must switch on `kind`, never parse `text`. Kinds:
  - `zoneIn` / `zoneOut`: "Đã đến <zone>" / "Đã rời <zone>" (`zone` field = zone id). First fix after a rules change
    only records where the phone is (no event).
  - `tsDown` / `tsUp`: "Tailscale mất kết nối <n> phút (<lý do>)" once per outage after >= 3 min down while Tailscale is expected on
    (keep-alive or set up by us) and was seen connected at least once; "Tailscale đã có lại sau <n> phút" when it returns (only after a
    `tsDown`). Log only: no Telegram / FCM / parent-app notification (`Events.NO_PUSH`, worker `pushNew`, `CheckJob`).
  - `battery`: "Pin còn <n>%" once when it drops to <= 15 % while not charging (re-armed after charging to >= 30 %).
  - `newApp`: "Đã cài <label>" when a new non-system app appears (also `apps[].new`).
  - `pairing` (v0.9.1): the child held "Mở ghép lại" on a paired phone with no PIN. Pairing opens 24 h later (state
    tick), unless a PIN is set before then.
  - `clock`: "Giờ máy bị đổi" on `ACTION_TIME_CHANGED` with a jump > 2 min versus elapsed time. v0.9.3: also "Múi giờ máy bị đổi: <tz>" on `ACTION_TIMEZONE_CHANGED`.
  - `tamper` (v0.9.3): usage access was switched off (daily / app limits can no longer count).
  - `tamper` + `"shot": "<id>"`: a wrong on-device admin PIN with a photo (rule `pinShot`, see Rules).
  The child sees the zone list in "Luật của con" (transparency).
- **Rule-change log**: when rules change, the phone logs a readable diff instead of "Bố mẹ đổi luật", e.g.
  "Bố mẹ đổi luật: giới hạn 120 → 90 phút; giờ ngủ 21:30–06:30". Shown on the child screen ("Nhật ký") and in the page log.
- **Push (relay)**: events younger than 1 h are pushed like SOS, event id `e:<id>`, data
  `{"kind": "ev", "kid", "eid", "t", "ek": "<event kind>"}` (no text, no zone name, no location to Google). The parent
  app treats `ev` as "check this kid now": it runs the background check for that kid, which notifies
  "<name>: <text>" once per event id (`ev:<id>`). The background check also notifies new `events` for LAN kids.
- **Templates (page only)**: `localStorage["kg.tpl"]` (parent app: bridge `KGApp.getTpl()` / `setTpl(json)`, <= 50 KB) = `[{name, rules: {dailyMin, dailyByDay, bedtime, schedules}}]`,
  presets "Ngày đi học", "Cuối tuần", "Nghỉ hè", "Thi cử". Applying sends one rules PUT per chosen kid with only those
  fields replaced (PIN, blocked apps, allow list, DNS kept).
- **Starting levels (page only)**: right after the first open of a kid whose rules are untouched (no limits, bedtime, schedules, app lists, groups, zones, DNS, blockNew, noInstall), the page asks once for Light / Balanced / Strict / later.
  Same template path (`tplFields`) with extra fields `blockNew`, `safeDns`, `noInstall`; one normal rules PUT. Remembered as `lv: 1` in the kid entry of `kg.kids` (also in the app mirror); it is set on any answer, so it is never asked twice.
- **Weekly report (page only)**: the overview has "Báo cáo tuần": per kid total and daily average over the last 7 days
  (`history`), top 3 apps, requests count, compared with the 7 days before when available (needs 14 days of history: rare, the phone keeps 7). Requests are
  counted from the log, so approximate.

- **Evening summary (2026-11-14)**: a short daily message (and a weekly one on Sunday): screen time vs limit, top 3 apps, requests asked / answered,
  apps locked, tamper tries, bedtime kept. One builder, `android/shared/Digest.kt` (compiled into the child app and the parent app), input = the state
  (`usedMs`, `limitMin`, `apps`, `history`, `digest`). Off by default, two places: (1) parent app: settings card, KGApp `getDigest()` -> `{"on","at"}` / `setDigest("true"|"false", "HH:mm")`
  (default 20:00); `CheckJob` shows it as a notification (info channel) after that time, once a day per kid, from the same state fetch (LAN or relay). Needs a successful fetch
  that evening (a kid unreachable until the next day gets nothing for the missed day). (2) Telegram, sent by the phone (no worker cron): `/digest` = state + preview of today, `/digest on|off|HH:mm` per chat
  (`tg.chats[].digest`, `digestAt`, `digestDay` kept on the phone, not in state). Counters: asked = new request, answered = `answer` command done, blocked = log kind `suspended`, tamper = event kind `tamper` (a wrong PIN counts only when it raises that event).
  Weekly numbers = today + the 6 days of `history`. Release wipes the counters with the prefs and the chats with `tgOff`.

## v0.6 other changes
- Before the first pairing the child screen hides the code and QR; a parent reveals them with a 5 s long-press (10 min).
  `/api/hello` still says `pairing: true`. Paired with no PIN set: a 10 s long-press on the child screen opens pairing
  (10 min, logged "Mở ghép lại từ máy con") so a parent who lost every token is not locked out.
- `state.apps` also lists installed non-system apps without a launcher icon (they can be suspended too).
- Android 8–10: `DISALLOW_APPS_CONTROL` (no force stop / clear data), lifted by `release`.
- Warnings: a notification 5 min and 1 min before the daily limit, bedtime or a schedule locks the phone.
- Phone page CSP adds `connect-src 'self'`.
- Version: `android/gradle.properties` `kg.version` / `kg.code`, read by both apps (`state.version` = `BuildConfig.VERSION_NAME`).

## v0.7: basic mode (no Device Owner)

For phones that can't become Device Owner without a computer (no QR on the welcome screen, Android < 11). Install the
APK, open KidGuard and follow the checklist; no cable, no adb.
- **Mode**: `state.mode` = `"owner"` (Device Owner, everything as before) | `"basic"` (device admin + accessibility on)
  | `"setup"` (neither: rules are not enforced). `state.owner` unchanged. `state.basic` (basic/setup only):
  `{"admin": bool, "acc": bool, "usage": bool, "location": bool, "notify": bool, "battery": bool}` = what the
  checklist has (true = granted, false = still missing). `/api/hello` adds `mode`.
- **Checklist (child screen)**, one button each, opens the system screen: Quản trị thiết bị
  (`ACTION_ADD_DEVICE_ADMIN`), Trợ năng (`ACTION_ACCESSIBILITY_SETTINGS`), Truy cập dữ liệu sử dụng
  (`ACTION_USAGE_ACCESS_SETTINGS`), Vị trí (runtime fine + background location; mic only when the parent first uses
  Listen), Thông báo (API 33+ runtime), Pin (ignore battery optimizations / OEM autostart). Shown until every item is ok.
- **Blocking**: the same `decide()` result. Instead of `setPackagesSuspended`, while a blocked app is in the
  foreground (accessibility window event, plus the guard tick) KidGuard sends HOME and shows a heads-up
  "<app> đang bị khoá: <reason>" (reasons as on the block screen). `apps[].suspended` = false, `apps[].blocked` as usual.
- **Self-protection** (basic, until `release`): an active device admin can't be uninstalled until deactivated. The
  accessibility service sends HOME when a Settings / package installer / OEM security window shows KidGuard together
  with an uninstall / deactivate / force-stop / accessibility-off control (text match, Vietnamese + English).
  KidGuard's own screen is never blocked.
- **Bounce stops audio** (basic): after a bounce (rules, daily limit, lock) the guard pauses that package's playing media
  session (`MediaSessionManager`, via our notification access; skipped without it). No new state field.
- **Clock** (basic, no `DISALLOW_CONFIG_DATE_TIME`): with `WRITE_SECURE_SETTINGS` the guard tick keeps Settings.Global
  `auto_time` and `auto_time_zone` = 1; if the child turned one off (1 -> 0) the existing `clock` event "Phone clock was changed"
  is sent once. The accessibility service also sends HOME from the Date & time page (automatic switch + set date / set time
  rows). `release` stops both (`Basic.enforcing`). No new state field or rule.
- **Not available in basic** (page hides or disables them, says "cần Device Owner"): app install / USB debugging
  blocks, family DNS, airplane / location / clock / safe-mode / factory-reset locks, `reboot`, Tailscale, new-app lock
  before first launch (still blocked on launch). `lock` uses the admin's force-lock. Live screen / remote control need
  Android 11+ as before.
- **Known gaps**: Safe Mode starts the phone without KidGuard; a child who disables accessibility from the quick
  tile / OEM menu we don't recognise stops blocking (state `basic.acc: false`, the page shows a red warning, the
  parent app notifies as for lost connection). The page says so plainly.
- **release** in basic: stops blocking, removes the device admin (`removeActiveAdmin`), clears tokens -> "ok".
- **Upgrade**: a basic phone can become Device Owner later (cable / setup.sh / self-setup on Android 11+) only after
  every account is removed, like a fresh install; rules and pairing are kept.

## v0.8: "Dọn máy" (debloat)

Real gains only: hiding preinstalled OEM apps (they stop running, use no RAM, don't update) and clearing an app's
data. No "RAM cleaner" / killing background apps (they restart; costs battery) and no clearing other apps' cache
(system-only since Android 8).
- **Rules** `hidden: ["pkg", …]` (Device Owner, or the self-adb shell below): preinstalled apps the parent hid. The phone calls
  `setApplicationHidden(true)` on each (skipping the never-hide set) and un-hides what it hid before and is no longer
  listed (it remembers its own set, like suspend; shell path: `pm disable-user --user 0`, or `pm uninstall -k --user 0` when the OS refuses it (HyperOS) / `cmd package install-existing` + `pm enable --user 0`, at most 20 packages per pass, the loop continues next tick). Unknown / not installed entries are kept and ignored.
  **release** un-hides every app KidGuard hid (owner path: `setApplicationHidden(false)`; shell path: `pm enable`, see below).
- **State** `adbWhy` (basic mode, 0.9.14; absent = the self-adb shell works): why KidGuard's own adb can't run now:
  `sdk` (< Android 11), `key` (self-setup never done), `wss`, `blocked` (debugging disallowed), `wifi` (not on Wi-Fi).
- **State** `preinstalled: [{"pkg","label","hidden":bool,"suggest":bool,"mb":int|null}]` (Device Owner, or self-adb set up: `adbWhy` absent / `wifi` / `wss`, changes then wait; refreshed at
  most every 10 min): system apps the parent may hide = system / preinstalled packages with a launcher activity, plus
  any in the suggest list that are installed, plus anything currently hidden. Never lists the never-hide set.
  `suggest` = on the curated bloat list (OEM stores, browsers, music / video, game helpers, ads / analytics, preinstalled
  Facebook / Netflix stubs…). Sorted: suggest first, then label.
  `refused: true` (present only when true): the OS refused `setApplicationHidden(true)` (returned false; some ColorOS
  packages) while the package is in `rules.hidden`, so `hidden` stays false. The phone remembers it per package, retries
  every 10 min, and clears the flag on a later success, an unhide, or when the parent removes it from `rules.hidden`
  (and on release). The page should show "this phone refuses to hide it" instead of a stuck toggle.
- **State** `apps[].mb`: app + data + cache size in MB (StorageStatsManager, needs usage access; refreshed hourly), null
  if unknown. Both modes.
- **Never hide / never clear**: KidGuard, `android`, systemui, Settings, phone / dialer / telecom / contacts providers,
  the default launcher and every enabled keyboard, Google Play services / framework, Play Store, WebView, package
  installer / permission controller, `com.android.providers.*`, Bluetooth / NFC / keychain, and `Policy`'s protected apps.
- **Command** `clearData {pkg}` (deliver-once): owner on Android 9+ = `DevicePolicyManager.clearApplicationUserData`; without
  Device Owner = `pm clear --user 0 <pkg>` through the self-adb shell (any Android 11+).
  Result `"ok"` | `"not owner"` (no owner and no shell) | `"unsupported"` (owner, Android < 9) | `"protected"` (never-clear set or KidGuard) | `"failed"`.
- **Command** `clearCache {}` (short-lived, not PIN-gated: nothing is lost): every app's cache, `pm trim-caches 999G` via the
  self-adb shell in both modes; logins and data stay. Per app is impossible from shell (`pm clear --cache-only` is silently
  ignored for uid 2000 and hangs, API 34). Result `"ok"` | `"no adb"` | `"failed"`. Offered when **State** `clearCache: true` (self-adb usable: `SelfAdb.why` null / `wifi` / `wss`). **State** `apps[].mb` = appBytes + dataBytes
  (dataBytes already includes the cache; before 0.9.16 the cache was counted twice), `apps[].cache` = cache MB, only when > 0;
  **State** `cacheCleared: {at, mb}` = last clearCache since the app started (MB freed, StatFs before/after); the command
  answers only when `pm trim-caches` is done (can take minutes for many GB), the phone rebuilds its state right after.
  **Command** `rescan {}`: drops the cached app sizes (1 h) and the preinstalled list, rebuilds the state now ("ok").
  **State** `sizesAt` = when the sizes in `apps[].mb` were measured (ms, 0 = not yet).
  `apps[].noClear: true` = clearData would answer "protected" (system / KidGuard / never-clear set): the page hides its button.
- **Self-adb shell (no Device Owner)**: `setup/SelfAdb.kt`. KidGuard pairs once with the phone's own Wireless debugging
  (same flow as the self-setup, `SelfAdb.pair`: notification with the 6-digit code, pair + connect only, none of the
  Device Owner steps) and then runs `pm` as the adb shell user. `SelfAdb.ready` = a paired key exists and the local adbd
  accepts a connection now (answer cached 30-60 s). With it, `hidden`, `preinstalled` and `clearData` work in Basic mode too
  and `caps.adb` is true. KidGuard records the packages it disabled (`shellDisabled`) and only ever re-enables those.
  Limits: Android 11+; Wireless debugging must be on and the phone on Wi-Fi; after a reboot Wireless debugging is off
  again, so already disabled apps stay disabled and new hide / unhide changes wait (log line "Wireless debugging is off")
  **Adb window (2026-11-10):** with WRITE_SECURE_SETTINGS (`caps.wss`) and a paired key, `SelfAdb.ensure` turns Developer
  options + Wireless debugging on itself for a job (only when a hide / unhide / clear really has to run, on Wi-Fi, not
  under DISALLOW_DEBUGGING_FEATURES), waits up to 10 s for adbd, and puts both back 20 s after the last command. So
  Developer options can stay off for banking apps and `hidden` / `clearData` / `preinstalled` still work.
  until it is turned on again. A `pm` that does not report the new state counts as a refusal (`refused: true`, retried every 10 min);
  no shell mid-pass is not a refusal. **release** re-enables every app we disabled through the shell. Without a shell the release still completes
  (`"ok"`; the parent is never locked in): the apps still disabled stay in the phone's `shellDisabled` set, a log line says how many, and
  every later start of KidGuard (boot, opening the app) re-enables them as soon as the shell works. Until then the user can enable them in Settings > Apps.
- **Basic mode** without the shell: `hidden` is ignored and `preinstalled` is absent; the page shows the heaviest apps (`apps[].mb`) and
  says hiding preinstalled apps needs Device Owner or the self-adb shell.

## v0.9.4: app groups, Authenticator unlock code
- **Rules** `groups: [{"id","name","pkgs":[pkg…],"min":int,"hours":[{"days":[1..7],"start":"HH:MM","end":"HH:MM"}]}]`
  (max 10 groups, 100 apps each, 5 windows; a group with no packages is kept but does nothing, so the page can save it while the parent picks apps). `min` = shared daily minutes
  of all its apps (0 = none). `hours` empty = any time, else the apps work only inside a window (same crossing-midnight
  rule as `schedules`). A group of one app = "this app only 19:00-20:00". `allow` does not exempt a group (like `appMin`).
- **Rules** `unlockMin`: 15 | 30 | 60 | 120 (default 30), minutes one Authenticator code unlocks.
- New block reasons `groupHours`, `groupLimit`, between `appLimit` and `new` (see priority above).
- **Command** `totpSet {secret}`: base32 RFC 6238 secret (HMAC-SHA1, 30 s, 6 digits), made by the page
  (`crypto.getRandomValues`, 20 bytes) and shown once as an `otpauth://totp/KidGuard:%20<name>?secret=…&issuer=KidGuard`
  link + the key; `secret: ""` = off. Result `ok | bad secret`. In the worker whitelist (sits in the relay queue only until
  the phone takes it). The phone keeps it in its own prefs and never sends it back; a new secret ends any unlock.
- **Child screen** "Nhập mã mở khoá của bố mẹ" (only when a secret is set): a code within ±1 step that is newer than the
  last used one sets `unlockUntil = max(now, unlockUntil) + unlockMin`. Until then `decide()` keeps only `blocked`
  and `new`. Wrong codes: 5 free, then 5 min doubling, cap 30 min (own counter, not the PIN's; the child-side unlock and the on-device admin check each count their own wrong codes against this secret, so the two paths have separate 5-try budgets; with a PIN set, a wrong admin code counts against the PIN counter); the 5th wrong one sends a `tamper`
  event. Each use sends event `unlock` "Con đã mở khoá bằng mã của bố mẹ: N phút" and a log line.
- **State** `totpOn: bool`, `unlockUntil: ms` (0 = not unlocked). Missing `totpOn` = phone older than 0.9.4.

## v0.9.6: PIN at pairing, on-device parent page, hold-to-confirm
- **Pairing needs a PIN.** `GET /api/hello` adds `pairPin: true` (this phone takes `newPin`). `POST /api/pair
  {code, pin?, newPin?}`: with a PIN set, `pin` as before (`newPin` ignored: an old PIN can't be skipped); with no PIN,
  `newPin` (4–8 digits, the page asks twice and sends it once) is required. Errors (after the code check, so they cost
  no code tries and no PIN tries): 403 `pin required`, 400 `bad pin`. Reply `{token, id, pinSet: true}`.
- **Atomic, nothing half-done.** `/pair` stores nothing: the token (and the new PIN) wait in memory for 5 min (max 3).
  The first authenticated request with that token (the page's first `/state`) stores the token hash, the salted PIN
  (rules `rev` bumped), `everPaired`, and closes pairing in one `commit()`; other pending tokens die. A page that closes or
  crashes before that leaves the phone unpaired, the code still valid, no lockout and no wait; a phone restart drops
  pending tokens too. The page saves the kid before opening it, so a reload commits (or gets 401, forgets it and shows
  the pairing screen again). No relay change: the relay has no code pairing (relay tokens come from the `relay` command
  of an already paired page).
- **Old pages / old phones.** An old page (older parent app bundle) pairing a phone with no PIN gets `pin required`
  ("Không ghép được"): update the parent app, or pair from the page the phone serves itself (always current). Old pages
  pairing a phone that has a PIN work as before (they call `/state` right after, which commits). A new page talking to
  a phone older than 0.9.6 (no `pairPin`) pairs without the PIN fields and then shows the PIN gate below.
- **Legacy phones** (paired before this, no PIN): every page open shows a "Đặt PIN" screen with no way past it until a
  rules PUT with `pin` succeeds (LAN or relay; worker unchanged, it already hashes `pin`). The child app shows
  "Bố mẹ chưa đặt PIN" in its status card and never offers to set the PIN itself (the child holds the phone). The 24 h
  "Mở ghép lại" stays only for these phones (no PIN); with a PIN, the parent opens pairing or the on-device page with it.
- **On-device parent page.** Child app "Bố mẹ quản lý trên máy này" (shown when a PIN or an Authenticator secret is set):
  the parent PIN (same counter and lockout as pairing / "pair another device") or a current Authenticator code (±1 step,
  once each; wrong ones count on the PIN counter while a PIN is set, else on the Authenticator counter). Every wrong one
  adds a `tamper` event (pushed like the others). Success logs "Bố mẹ mở trang quản lý trên máy này" and opens
  `AdminActivity`: a WebView on `http://127.0.0.1:8770/` (the phone's own LAN server and `index.html`).
  - Session token: 24 random bytes, kept only as a hash in memory, sent as an `HttpOnly; SameSite=Strict` cookie `kgs`
    (never in a URL, never in JS, never logged). The phone accepts the cookie only as this session, never as a paired
    token. Ends (revoked server side, cookie and WebView storage wiped) when the screen turns off, the activity leaves the
    foreground, Back, the page's "Xong", or after 5 min with no touch (page polling does not count).
  - The page sees `window.KGLocal` (`end()` only): it opens the phone without saving it in `kids`, shows a sticky
    "Đang quản lý ngay trên máy con" bar, and on 401 shows "Phiên quản lý đã hết". Everything else is the normal page
    (pair a new device via "Ghép thêm máy bố mẹ", unpair all, rules, PIN, Authenticator…).
  - WebView: no file / content access, no geolocation, no new windows; navigation only to `http://127.0.0.1:8770`,
    sub-requests only there and to `tile.openstreetmap.org` (already in the CSP). No FLAG_SECURE.
- **Hold-to-confirm** (child screen: 5 s "hiện mã ghép", 10 s "Mở ghép lại"): a fill grows over the button and the label
  counts down ("Giữ tiếp… 3 giây"); an early release animates back and shows "Giữ đủ 5 giây"; a haptic tick at the end.
  TalkBack: activating the button (its click action is labelled "bắt đầu đếm N giây") runs the same countdown without a
  hold; activating again cancels. No wire change.

## v0.9.7: wind-down, lock-screen info, block note, check-in, request templates
Rules, NEW optional fields (bad values fall back to the default):
```jsonc
{
  "windDown": {"gray": false, "min": 30, "nudge": false},   // min: 0 | 15 | 30 | 45 | 60 (else 30)
  "lockInfo": {"msg": "Máy của Bin", "contact": "Gọi mẹ 09xx"}, // each <= 60 chars, one line (control chars -> space)
  "blockNote": "Học xong rồi chơi nhé"                         // <= 120 chars, one line; "" = the default text
}
```
- **Wind-down** (owner and basic): with `bedtime.on` and `windDown.gray`, the phone turns on grayscale from
  `bedtime.start - min` until `bedtime.end` (`WindDown.active`; window may cross midnight). It writes
  `Settings.Secure accessibility_display_daltonizer_enabled = 1` and `accessibility_display_daltonizer = 0` (needs
  WRITE_SECURE_SETTINGS, granted by setup; `DPM.setSecureSetting` does not allow these keys). Before the first write it
  stores the two old values (missing key remembered as missing) and puts exactly those back when the window ends, when
  `gray` is turned off, and in `release` (before the rest of release; if release fails the loop switches it on again).
  Every Guard tick re-applies only what differs (the child switching it off in Settings is undone within ~10 s).
  A missing old switch restores to `0`, a missing old mode to `12` (system default).
- `windDown.nudge`: one notification "Còn 10 phút nữa là giờ ngủ" 6..10 min before bedtime (channel `warn`, once a day;
  the 5-minute heads-up still follows). Needs `bedtime.on`, not `gray`.
- State NEW `"windDown": {"active": bool, "gray": bool, "perm": bool, "err": ""}`: `active` = inside the window with
  `gray` on, `gray` = our grayscale is on now, `perm` = the app holds WRITE_SECURE_SETTINGS (page: "cần cấp quyền"
  when `gray` is on and `perm` is false), `err` = `"no permission" | "write failed" | ""`.
- **Lock-screen info** (owner only): `DPM.setDeviceOwnerLockScreenInfo("msg · contact")` (either may be empty; both
  empty = cleared). `release` clears it.
- **Block note**: `setShortSupportMessage` = `blockNote` or the default "Bố mẹ đang tạm khoá ứng dụng này. Con có thể xin
  thêm giờ trong KidGuard nhé." (Android's "blocked by admin" dialog); `setLongSupportMessage` = a kind default
  paragraph plus "Bố mẹ nhắn: <note>". Basic mode: the block notification's text is "Bố mẹ nhắn: <note>" (or the
  default). The KidGuard screen shows "Bố mẹ nhắn: <note>" while any app is blocked. `release` clears both messages.
- **Check-in** (child buttons "Con tới nơi rồi" / "Đón con với", one tap, no alarm): an event in `events`,
  `{"id", "t", "kind": "checkin", "ck": "arrived" | "pickup", "text", "loc"?: {"lat", "lng", "acc", "t"}}`.
  `loc` = the last fix if younger than 10 min; the phone also takes a fresh fix and attaches it (to check-ins of the
  last 2 min that have none or an older one). Same transport as SOS: relay sync now and 15 s later; relay push as a
  normal `ev` (`ek: "checkin"`). Same button again within 60 s = ignored (child sees "Vừa gửi rồi"). No ack command:
  the page hides its banner after "Đã xem" (browser-local) or 3 h. Parent app: notification key `ci:<id>` on
  `kg_events` (normal importance) with "Xem vị trí" (`geo:` at `loc`, else `state.location`, else the page).
- **Request templates**: the child's "Xin thêm giờ" dialog has one-tap buttons "+15 phút làm bài" (15), "+30 phút chơi"
  (30), "Cần app học" (15) that send at once. The request adds `"tpl": "homework" | "play" | "study"` and `note` =
  the label (+ ": " + typed text). Request shape: `{"id","t","min","note"?, "chore"?, "tpl"?}`.
- **Canned replies (page only)**: chips under "Lời nhắn cho con" fill the existing `answer.note`. No wire change.
- No new commands (worker whitelist unchanged).
## Remote phone settings (after v0.9.5)
Only volume, brightness, screen timeout and Do Not Disturb; anything else goes through the Screen tab's remote control.
- **State** `sys` (absent on older children: the page hides the card). Read at most once a minute and right after a change
  (not every guard tick):
  ```jsonc
  "sys": {
    "vol": {"ring": [3, 7], "media": [5, 15], "alarm": [6, 7]},   // [current step, max step] (AudioManager)
    "bright": [120, 255] | null,       // Settings.System.SCREEN_BRIGHTNESS, max from the framework config (some OEMs > 255)
    "autoBright": false | null,        // SCREEN_BRIGHTNESS_MODE == automatic
    "timeoutMs": 30000 | null,         // SCREEN_OFF_TIMEOUT
    "dnd": true | null,                // interruption filter != ALL
    "can": {"vol": true, "bright": true, "timeout": true, "dnd": false},   // false: the page disables the control and says why
    "last": {"vol.media": {"id": "s…", "res": "ok", "t": 1793940898493}}  // per key: the last `setting` command's result
  }
  ```
- **Command** `setting {key, value, id?}`, `key` one of `vol.ring vol.media vol.alarm bright autoBright timeoutMs dnd`;
  `value` a JSON number (volume step, brightness 1..max, ms) or a boolean (`autoBright`, `dnd`). The phone clamps numbers
  (volume 0..max, brightness 1..max, timeout 15 s..10 min). Result `ok | bad key | bad value | no permission |
  no dnd access | blocked by dnd | volume fixed | not applied | failed`. The worker answers 400 `bad setting` for an
  unknown key or a value that is neither a number nor a boolean. Not deliver-once (repeats are harmless; the phone de-dups by `id`).
  The page sends its own `id` and waits for `sys.last[key].id` in a later state (relay: the command only gets `queued`).
  Each change logs one line (`log_setting_*`); a failure logs the usual `Command: setting → <result>`.
- **How, per Android version**: volume = `AudioManager.setStreamVolume` (no permission; while DND is on the ring stream can
  refuse: `blocked by dnd`). Brightness / auto / timeout = `DevicePolicyManager.setSystemSetting` on Android 9+ as Device
  Owner, else `Settings.System.putInt` (allowed by WRITE_SECURE_SETTINGS from setup, or the WRITE_SETTINGS app-op); the
  value must read back or the result is `not applied`. DND = `NotificationManager.setInterruptionFilter` (on = PRIORITY,
  off = ALL), needs policy access, which only setup can give (`cmd notification allow_dnd`); without it `can.dnd=false`.
- `release` needs nothing extra: these are plain user settings, no restriction is added.

## v0.9.3 fixes
- Relay rules carry `pinAt` (the rev of the PUT that set the PIN; kept by later PUTs). The phone takes a relay `pinHash`
  only when `pinAt` is newer than the last one it took, so a PIN changed later on LAN / on the phone is not reverted.
- Pairing (`/api/pair`) checks the code before the PIN: wrong PINs without the code no longer lock the PIN.
- `release` that does not return `ok` (e.g. `failed: N app còn bị ẩn`) keeps enforcing and logs it.
- Owner mode: an app the system refused to suspend is bounced home (like basic mode). Removing an app (not an update)
  drops it from the "known" set, so a different app reinstalled under that name counts as new.
- Safe DNS fallback (network can't validate) switches to opportunistic but keeps the Private DNS setting locked.
- Relay `cmd` ignores commands older than 24 h when checking the queue limit; the state limit counts bytes.

## Self-update (v0.9)
- **Source**: `HEAD https://github.com/HoangTran0410/kidguard/releases/latest` (no redirects followed): the `Location`
  header ends in `/tag/v<x.y.z>`. Then `releases/download/v<x.y.z>/kidguard.apk` and `kidguard.apk.sha256` (first
  token = hex SHA-256). The APK must hash to it, have package `dev.kidguard` and a higher versionCode; Android itself
  refuses an update signed with another key, so a fork's or a tampered release can't replace the app.
  A tag whose APK fails that check is remembered and skipped until a newer tag. `release` aborts (`"failed: N app còn
  bị ẩn"`, still Device Owner) if an app hidden by "Dọn máy" can't be un-hidden, and drops a waiting download.
- **When**: rule `autoUpdate` (default true) = once a day from the guard loop (after a failure: retry in 1 h), or the
  command `update` (deliver-once; result `"ok"` = started, `"busy"` = a check is already running; runs even with
  `autoUpdate` off).
- **Install**: Device Owner = silent `PackageInstaller` session (lifts `no_install_apps` / `no_install_unknown_sources`
  for the session; the new process re-applies the policy on start). Basic mode can't: the child gets a notification and
  a **Cập nhật KidGuard** button that asks for "install unknown apps" once, then shows the system confirm screen.
- **State** `update: {"latest": "0.9.1"|"" , "status": ""|"checking"|"downloading"|"installing"|"ready"|"failed",
  "err": string, "checkedAt": epoch ms|0, "pct": 0..100|-1}`. `ready` = basic mode, downloaded and verified, waiting for the child's tap.
  `pct` (v0.9.36) = download progress while `downloading`, -1 otherwise or when the size is unknown.
  Absent on children older than 0.9 (the page hides its card).
- **Parent app**: once a day the same `HEAD` from `CheckJob`; a newer tag than its own version gives one notification
  per version, the tap opens `releases/download/v<tag>/kidguard-parent-<tag>.apk` in the browser (no install permission).
- **Release assets**: `kidguard.apk`, `kidguard-parent.apk` + their `.sha256` (fixed names: QR provisioning, setup
  scripts and the child updater use `kidguard.apk`/`kidguard.apk.sha256`; never rename them) and versioned copies
  `kidguard-<v>.apk`, `kidguard-parent-<v>.apk` for people downloading by hand (website/README link these).

## Log history, the "Nhật ký" tab (after 0.9.14)
- The phone keeps every log line and event 90 days / 30,000 rows (`HistDb.kt`, SQLite `kghist.db`, table `h`, index on `t`;
  pure part `HistLogic.kt`); rows older than that go hourly. The 50 + 50 rows already in prefs are imported once when the
  file is created. Writes are queued on a background thread. A successful `release` deletes the file.
- State is unchanged: `"log"` and `"events"` still carry only the latest 50 each (older pages keep working from them).
- One day, in the phone's time zone: LAN `GET /api/logday?day=2026-11-10` -> `{"day", "days": {"yyyy-MM-dd": rows, ...last 90 days,
  only days with rows}, "ev": [event objects], "lg": [log objects], "cut": false}`; ev / lg are shaped like state `events` / `log`,
  oldest first, at most 500 rows and 80 KB (the newest kept, `cut: true`). A bad day -> 400 `{"error":"bad day"}`.
  Plus (2026-11-16) `"use": {"p": [package], "s": [[index into p, start s, end s], ...]}`: each time an app was in front that
  day, seconds from the day's local midnight, oldest first (pieces <= 10 s apart joined, < 3 s dropped, the home app left out,
  newest 600 kept). From Android's usage events, which the phone keeps ~7-10 days: an older day has `s: []`. No usage access = no `use`.
- Relay, same shape as "Device health": parent `POST /c/<id>/logday {"day"}` asks and gets `{"logday": <last stored copy of that day +
  "at"> | null, "asked": <worker ms>, "seen": ms}`; `GET /c/<id>/logday?day=` only reads. While asked (< 2 min, memory, up to 8 days)
  the sync reply has `"logday": "<day>"` (one day per sync); the phone then `PUT /c/<id>/logday` with the answer above (413 over
  100 KB, 400 bad day), which clears that ask. The worker keeps the last 4 days stored. The page shows the stored copy at once and polls
  `GET` every 3 s (max ~75 s) until `logday.at >= asked`. Not a command: nothing in the queue or the whitelist.
- Page: day strip counts come from `days`; an old phone / worker (404) falls back to the state's rows kept in localStorage.

### Phone health (the "Sức khoẻ máy" tab, `guard/Health.kt`, `HealthLogic.kt`)
- **State** `health: {score, limited, at, checks[]}`. `score` 0-100 = 100 minus the weight of every failed check (`HealthLogic`, honest: no fake
  "speed up %", no virus scan). `checks[] = {id, ok, w, fix, v}`: `w` = points lost (0 when ok), `fix` = the page can fix it with one command,
  `v` = value (count / MB / % / days). A check whose value is unknown on this phone is left out. Ids: `devOpts` (fix `devOff`), `installs` (owner only:
  DISALLOW_INSTALL_UNKNOWN_SOURCES / INSTALL_APPS not set), `admins` (device admins besides KidGuard), `sideload` (user apps not from a store;
  installer via `getInstallSourceInfo`), `patch` (security patch older than 180 days), `storage` (free < 20 %), `cache` (>= 200 MB, fix `clearCache`),
  `bg` (user apps allowed to run in the background, >= 10, fix `bgLimit`; needs the self-adb shell), `battery` (< 15 % and not charging).
  `limited` = apps whose background running we switched off (`Store.bgLimited`). `sideload` / `bg` are measured on a thread at most every 10 min, so
  they are missing from the first state after start. `at` = when (ms, 0 = not yet).
- **Command** `bgLimit {pkgs?}` (short-lived, not PIN-gated): self-adb `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` for user (non-system) apps that
  still allow it, except KidGuard, the parent app, the default launcher / keyboards / dialer / SMS, `rules.allow`, `accPause` / `vpnPause` / `devPause` /
  `tcpPause`, Tailscale. `pkgs` narrows the list (still filtered). The apps changed are remembered (`Store.bgLimited`). Result `"ok"` | `"no adb"` | `"failed"`.
  **Command** `bgRestore {pkgs?}` sets exactly those back to `allow` (`pkgs` = per-app undo, still only apps in `Store.bgLimited`; "ok" | `"no adb"` while some of the asked are left).
  `release` does the same for all (both modes).
- **State per app** (`apps[]`, added 2026-11-13, from the 10-min measure, patched at once after bgLimit / bgRestore): `bg` (true = background allowed, false = restricted; omitted
  when unknown / no adb / Android < 9), `bgMine` (true = KidGuard restricted it), `bgKeep` (true = a user app that can never be restricted: the `bgLimit` filter above),
  `bgIdle` (true = allowed, no foreground use and no notification in the last 7 days; only when usage access + notification access + >= 7 days of notification history exist, else never sent).
  `health.checks[devOpts].v` = 2: Developer options on but KidGuard's own adb is usable (it needs them): `ok` true, 0 points, `fix` still true (`devOff` kills adb features).
- **Rule** `kidClean` (see Rules JSON): the kid-side tile runs only `Policy.clearCaches` (caches, never app data, accounts or permissions), once per 10 min,
  logs `kid_clean`. The child never gets `bgLimit`.

## Device health, the "Máy" tab (after 0.9.5)
- State adds `"sdk": 34` (Build.VERSION.SDK_INT) next to `android`.
- On demand only, never in the state poll. Built by `DeviceInfo.kt` (pure part `DeviceLogic.kt`), cached 60 s on the phone.
- LAN: `GET /api/device` (token as usual) -> snapshot; `?fresh=1` accepts one at most 5 s old (the page's Refresh).
- Relay: parent `POST /c/<id>/device {}` asks and gets `{"device": <last stored snapshot + "at": worker ms> | null,
  "asked": <worker ms>, "seen": ms}`; `GET` only reads (does not ask). While asked (< 2 min, memory only) the sync reply
  has `"device": true`; the phone then `PUT /c/<id>/device` with a snapshot at most 5 s old (413 over 32 KB, 400 not an
  object), which clears the ask. The page shows the stored one at once and polls `GET` every 4 s (max 2 min) until
  `device.at >= asked`. Not a command: nothing in the queue or the whitelist.
```jsonc
{
  "t": 1793940898493, "sdk": 27, "android": "8.1.0", "model": "OPPO CPH1803", "version": "0.9.6", "uptimeMs": 123456789,
  "storage": {"total": 32000000000, "free": 3200000000,        // bytes, StorageStatsManager (matches Settings), StatFs fallback
              "apps": [{"pkg": "…", "label": "…", "app": 0, "data": 0, "cache": 0}]},   // top 15 by app+data+cache, all
                                                               // installed packages; null without usage access
  "ram": {"total": 0, "avail": 0, "threshold": 0, "low": false, // ActivityManager.MemoryInfo
          "perApp": false, "otherProcs": 0, "procStat": false}, // probes: other apps' processes visible / /proc/stat readable
  "battery": {"pct": 81, "charging": true, "plug": "ac|usb|wireless|dock|none", "tempC": 34.5,
              "health": "good|overheat|dead|overVoltage|failure|cold|unknown", "voltageMv": 4100,
              "screenOnMs": 3600000},                           // API 28+ (SCREEN_INTERACTIVE events), else null
  "net": {"type": "wifi|cell|ethernet|vpn|other|none", "rssi": -60, "bars": 3, "metered": false},   // rssi/bars Wi-Fi only
  "clearData": true                                             // Device Owner on API 28+, or a working self-adb shell: the clearData command works
}
```
- Not readable, so not shown: per-app RAM / CPU % (since Android 7/8 `getRunningAppProcesses` lists only our own
  process and `/proc` of other apps and `/proc/stat` are blocked by SELinux; the snapshot reports the probes), per-app
  battery use (BatteryStats is a system permission), clearing other apps' cache (`DELETE_CACHE_FILES` /
  `CLEAR_APP_CACHE` are `signature|privileged`, a Device Owner can't get them). The page explains each instead.
- Manifest adds `ACCESS_WIFI_STATE` (normal) for the RSSI on API 26-28 (API 29+ reads NetworkCapabilities).

## Shared files (v0.9.8): a shared folder, never a phone browser

**Rule: the parent sees and gets only what was sent through KidGuard** (parent -> kid files and the kid's outbox).
No route lists, reads or deletes anything else on the phone (privacy, and scoped storage on API 30+ forbids it anyway).
Code: `Files.kt` (Android), `FilesLogic.kt` (pure: names, caps, listing JSON; `FilesLogicTest`), `ShareInActivity.kt`.
- Entry (`GET /api/files` items and state `files`, newest first; state carries the newest 30):
  `{"id": "<16 hex>", "dir": "in|out", "name": "…", "size": bytes, "at": ms, "st": "…"}`.
  `in` = parent -> kid, `st` `delivered` (saved on the phone) -> `opened` (the kid tapped the notification) or `gone`
  (deleted on the phone, seen at download time). `out` = kid -> parent, `st` `new` -> `fetched` (a full download).
  Phone paths / URIs never leave the phone.
- LAN `PUT /api/files/<name>?id=<fid>`: optional `id` (16 hex) lets the caller choose the entry id; ignored if malformed or already taken.
- Names: rejected (400 `bad name`) with any `/` `\`, control char, `.` / `..` or blank; else `<>:"|?*` -> `_`, leading
  dots dropped, spaces collapsed, cut to 120 chars keeping the extension; a clash becomes `a (1).pdf`.
- Caps: LAN 200 MB a file (413 `too big`, checked from Content-Length before reading), relay 50 MB; 507 `no space`
  when the phone would keep < 50 MB free; index 100 entries, outbox 50 files (oldest dropped).
- parent -> kid lands in the public `Download/KidGuard/` (MediaStore.Downloads on API 29+; on 26-28 the file API
  with `WRITE_EXTERNAL_STORAGE`, `maxSdkVersion` 28, granted by the Device Owner in `Policy.apply`; basic mode without
  that grant falls back to the app's own external `Download/KidGuard`). The kid gets "Bố mẹ gửi: <name>"; the tap goes
  through `ShareInActivity` (marks `opened`) to `ACTION_VIEW` on `content://dev.kidguard.files/<id>` (`FilesProvider`,
  read-only, not exported, per-intent grant).
- kid -> parent: `ShareInActivity` is a share target ("Gửi cho bố mẹ", `ACTION_SEND` / `SEND_MULTIPLE`, `*/*`, plain
  text becomes a `.txt`) and the sheet row "Gửi file cho bố mẹ" (system picker). Each stream is copied into
  `filesDir/outbox/<id>` before the activity finishes: no URI permission is kept.

### LAN (token `X-KG-Token`, `?k=`, or the on-device admin cookie; same check as every `/api` route)
- `GET /api/files` -> `{"files": [entry…], "maxLan": 209715200, "maxRelay": 10485760}`.
- `PUT /api/files/<url-encoded name>` (body = the file, Content-Length required, streamed to storage by `Http.kt`, never
  buffered) -> `{"file": entry}`; errors `{"error": "bad name|too big|length required|incomplete|no space|failed"}`.
- `GET /api/files/<id>` -> the bytes, always `application/octet-stream` + `Content-Disposition: attachment` (a kid's
  `.html` must not render on the phone's origin). `DELETE /api/files/<id>` -> `{"result": "ok"}` (an `in` file is
  removed from Download too). Both 404 for an unknown id; ids are 16 hex, names are never used to address a file.

### Relay (worker `/c/<id>/file`, Durable Object storage, free plan OK)
- Chunks of 1 MiB (`FILE_CHUNK`), at most 50 MB a file, 8 files / 100 MB stored per kid, 1 h TTL (dropped on any file call or
  sync), deleted by the receiver right after it has the file. Parent and phone share the token.
- `POST ?fid=<16 hex>&i=<chunk>&n=<chunks>&to=kid|parent&size=<bytes>&name=<enc>` body = chunk -> `{ok, done}`
  (400 wrong chunk length / bad file, 413 > 50 MB, 507 `relay full`, 429 `{error:"quota", retryAt}` = this kid's 24 h quota is used up). `GET ?to=kid|parent` ->
  `{"files": [{fid, name, size, n, at, done}], "max", "chunk"}`. `GET ?fid&i` -> chunk bytes. `DELETE ?fid`.
- Quota (anti-spam, the default relay is one free account for everybody): per kid, counted when a file starts (first chunk), both
  directions together: 300 MB and 100 files in a window of 24 h from the first counted file (`FILE_DAY_BYTES`, `FILE_DAY_N`; one DO
  record `fq {start, bytes, n}`). Over it: `429 {error:"quota", retryAt: <ms epoch>}`. The per-IP limiter on `/c/*` (150 / 10 s) applies to
  chunks too. The phone logs a refused push (413 / 429) in its log (the parent reads it); the page shows the quota text.
- parent -> kid: the page uploads with a fresh `fid`; the sync reply then has `"files": N` (complete files for the kid);
  the phone fetches them (`Files.pull`, own thread), saves each like a LAN upload with id = `fid`, deletes it from the
  relay. The page shows "delivered" once state `files` has that id.
- kid -> parent: command `fileUp {file: <id>}` (outbox files <= 50 MB, else `too big`) makes the phone upload it with
  `fid = id, to = parent`; the page polls `GET ?to=parent` until `done`, downloads the chunks, then `DELETE`s it.
  `fileDel {file: <id>}` = the LAN `DELETE` (deliver-once). Both are in the worker whitelist.
  `fileUp` / `shotUp` "ok" = the upload started (a second one for the same file while it runs is ignored); a failed
  upload adds a log line (`state.log`). Files nobody fetched are removed by the DO alarm at the 1 h TTL (0.9.18).
  0.9.41: `fileUp` / `shotUp` are SHORT (dropped 10 min after queued: the page stops waiting after 5 min) and a new
  `fileUp` replaces a still-queued one for the same `file` (+ `thumb`), so a phone that slept uploads it once.
  The page re-sends a failed chunk (3 tries, same `fid`); `DELETE` of a file not yet whole gives its daily quota back.
  Parent app over LAN: the download is written to Downloads as it arrives (`KGApp.dlBegin/dlChunk/dlEnd`), not held in memory.
- Wrong-PIN photo: command `shotUp {shot: <id>}` (not deliver-once; `no such photo` if gone) posts that photo with `fid = id`;
  the page reads it like a `fileUp` download (one chunk), then `DELETE`s it. LAN: `GET /api/shot/<id>`.
- Parent app: the proxy streams bodies (up to 210 MB request, LAN `GET /api/files/<id>` response streamed with Content-Length) for `/api/files…` and relay `/c/<id>/file` only; other routes keep the 512 KB cap. The WebView offers a file chooser and saves downloads (bridge `KGApp.files()`).

### Release
`release` deletes the outbox and the index; files the kid received stay in `Download/KidGuard` (they are the kid's). Chat attachments (Pictures / Download KidGuard) stay too, see "Family chat" -> Attachments.
The `WRITE_EXTERNAL_STORAGE` grant (API 26-28) goes back to default with the other grants.

## v0.9.9: kid launcher

Optional home screen on the kid's phone (`HomeActivity`, category HOME). Device Owner only; off by default.

### Rule `launcher` (in `PUT /api/rules`)
`{"on": false, "settings": false, "notes": true, "lockCustom": false, "style": "", "shade": false}`. Missing = all defaults (off).
- `on`: enable the `.HomeActivity` component (manifest default: disabled) + `addPersistentPreferredActivity(HOME)` so Home
  opens it with no chooser. `on:false`, `release` (before `clearDeviceOwnerApp`) and `releaseBasic` clear the preferred
  activity and disable the component; the stock launcher comes back. Not Device Owner ⇒ the rule enables the component
  (the user picks KidGuard in Settings) and, once paired and while KidGuard is HOME, `LauncherLock` (Acc closes the "Home app"
  page / HOME chooser, bounces another launcher's HOME activity to ours; self-adb `cmd package set-home-activity` re-asserts it when
  available; `LauncherSafe.off` clears it via `clearPackagePreferredActivities`) and only once paired; before pairing the child app's local opt-in
  ("Tính năng và quyền" → launcher) decides. See "Features and permissions".
- `settings`: the kid may open system Settings: the Settings app shows, and the KidGuard control center opens settings pages (a toggle it can't flip directly, long-press on Wi-Fi / Bluetooth / airplane / data, the media card's notification-access ask); off, the Settings app stays in the grid / drawer greyed with a lock badge (tap: "bố mẹ quản lý Cài đặt" sheet; hiding it made owners think it was gone) and those say "a parent manages this", except Wi-Fi / Bluetooth / data, which the kid can still turn ON (own toggle, the Wi-Fi / internet panel, or the Bluetooth turn-on dialog) but not off (hidden by default; always shown on a phone nobody manages: no Device Owner and never paired, where the user picked the launcher themselves). `notes`: show `blockNote` in the "app tạm nghỉ" sheet.
- `lockCustom`: makes the kid's "Tuỳ chỉnh" screen read-only (style, grid, wallpaper, colours, text size) with a note
  "Bố mẹ đã khoá phần tuỳ chỉnh". Arranging (move, folders, widgets, remove) stays allowed. No PIN on the phone. `style`: `""|"be"|"teen"|"gon"`, the preset used only
  when `home.json` is first made (unknown values ⇒ `""` = teen).
- `shade` (v0.9.11): the system quick settings (control center) are closed as soon as they open, in any app (not the
  lock screen); the notification shade itself stays usable. The accessibility service (`Acc`) watches window events and,
  when a tall SystemUI window shows `quick_settings_panel` (the expanded quick settings; the shade alone shows
  `quick_qs_panel`, measured AOSP 14 + ColorOS 8.1) or a window of `miui.systemui.plugin` (HyperOS control center,
  unverified) is up, sends `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` (API 31+, else Back), re-checked 4 × 0.3 s. The
  one-row quick tiles on top of the shade stay tappable. Needs the accessibility service on; without it nothing is
  blocked. Only while the launcher is wanted (`on` + Device Owner, or the unmanaged opt-in); `on:false`,
  `release`, `releaseBasic` drop it with the launcher (no system flag is set, nothing else to undo). Not
  `setStatusBarDisabled`: that also mutes every notification's sound, vibration and heads-up (measured, API 34). The
  kid panel hides "Cài đặt nhanh của máy" while it is on.

What shows: every launcher activity passes through the Guard's `decide()`: reasons `blocked` / `new` (and parent-hidden or
suspended-by-KidGuard plumbing) are never shown, in grid, dock, drawer or search; any other reason (`daily`, `app`, `group`,
`bedtime`, `schedule`, `paused`…) shows the app greyed with a clock badge; tapping it opens a sheet with the reason,
"Mở lại …", the note, Xin thêm giờ (same request as the main screen) and Để sau. The dock slot 1 is always the dialer
(`@phone`, `ACTION_DIAL`; long press: SOS / 112 / 113 / 114 / 115), never removable.

Control center (on the phone only, no wire format): swipe down on the right half of a home page opens the kid's panel
(the left half pulls the notification shade). Wi-Fi, Bluetooth, airplane, mobile data, torch, auto-rotate, Do Not
Disturb, brightness and media volume, plus "Cài đặt nhanh của máy" (the system quick settings). Per tile
(`ControlLogic.mode`): changed directly when the app can (Device Owner: Wi-Fi, Bluetooth; WRITE_SECURE_SETTINGS / the
WRITE_SETTINGS app-op: rotate, brightness; DND policy access; torch always); otherwise it opens the system panel/page only
where `settings` would show Settings (rule, unmanaged phone, KidGuard Home), else it shows a lock ("Bố mẹ quản lý mục
này"). Airplane with `noAirplane` on a Device Owner phone is always locked. Nothing new to undo in `release`.

### State `launcher` (in `GET /api/state`, relay state too)
`{"on": bool, "home": "<pkg>", "err": ""|"not owner"|"crash"|"home", "style": "be|teen|gon", "locked": bool, "pages": n, "safeUntil": ms}`
- `on`: the component is enabled now. `home`: the package Home resolves to (`dev.kidguard` when ours). `err`: `crash` = safe mode (below), `home` = the rule is on
  but Home opens something else (re-applied every minute). Absent = older app.

### Crash fallback
Error boundary (v0.9.34, `CrashGuard`): a main-thread crash no longer kills the process. A crash in any other screen closes
the screens and the home shows; a launcher crash (launcher frames while `HomeActivity` is alive) is counted (device-protected
prefs) and the home restarts. More than 5 caught crashes in 10 s, or OutOfMemoryError, still kill the process. Worker
threads without a Looper just end; the Guard / relay Looper threads still kill it (the restart brings them back).
2nd launcher crash within 5 min: the layout is reset to the preset. 3rd within 5 min: safe mode for 24 h (`safeUntil`): the
component stays HOME and shows a plain app list (`SafeHome`, "Try the usual home" ends it) and an event
`{"kind": "launcher", "text": "…danh sách app đơn giản tối đa 24 giờ (HH:mm)"}` is sent. Before v0.9.34 safe mode turned the
launcher off, which without Device Owner cleared the default HOME (HyperOS: chooser stuck behind Recents). After 24 h the full home returns. An app update clears the
crash count and the safe mode, and a layout reset by a crash in the last 24 h comes back (`home.json.bad`, if it parses).

### `home.json` (phone only, app files dir)
```
{"v":1, "grid":{"cols":3-5,"rows":4-7,"auto":bool}, "pages":[{"items":[item…]}…], "dock":[{"pkg":"@phone"},{"pkg":pkg|""}×3],
 "style":{"preset":"be|teen|gon","icon":1-5 (default 2),"labels":bool,"shape":"square|soft|round" (default soft),"rim":"top|line|glass|none" (default top),"fill":"app|sheen" (default sheen),"dockBg":bool,"dockLabels":bool,"badge":"num|dot|off","face":"sys|light|medium|condensed|serif|easy","speak":false,"calm":false,"solid":false,"accent":"<id>"|"",
          "theme":"light|dark|auto","font":0.85|1.0|1.15|1.3,"lbl":"auto|s|m|l","wall":{"kind":"builtin|photo","id":"<wall id>","dim":0-60,"blur":0-100},"lib":true,"fx":"auto|full|lite","today":true},
 "seen":[pkg…], "notes":[note…], "todo":[todo…], "timetable":{"1".."7":{"am":[subject…],"pm":[subject…]},"bells":{"am":[[start,end]…],"pm":[…]}}, "todayHide":[card…]}
```
`custom` (optional, top level): per-app rename / icon `{"<pkg>":{"l":"<label, <=40>","i":"g:<lucide glyph>:#RRGGBB"|"e:<emoji>:#RRGGBB"}}` (keys may be `pkg/activity`); missing / unknown fields are ignored. `style.speak` = say an app's name when tapped + "Đọc to" on Hôm nay, `calm` = launcher animations off, `solid` = solid label backing.
`todayHide`: "Hôm nay" cards the kid turned off ("Tuỳ chỉnh thẻ" at the end of that page): `now` (greeting, school period
now / next, countdown to the next lock or bedtime), `time` (screen time left + top 3 apps), `sched`, `chores`, `tomorrow`
(tomorrow's subjects, from 17:00), `notes`, `files` (both ways: opens what parents sent, lists what the kid sent + "Bố mẹ đã tải", "Gửi file cho bố mẹ" = the system picker into the outbox). The SOS / call buttons can't be hidden. Empty schedule / files /
tomorrow cards are left out on their own. `style.today` false = no "Hôm nay" page at all (page 1 is the first; widgets that open it do nothing). Phone only.
KidGuard Home (`kidguard-home.apk`, package `dev.kidguard.home`, v0.9.9+) is the same launcher with no parent link: same layout file / fields, no rules (nothing is blocked), no relay / LAN / commands. It never talks to the worker or the parent page; widgets `time` `quick` `next` `counts` `usage` `lock` `when` `earn` `msg` and Today cards `time` `chores` `files` are not offered there. On a phone where KidGuard is Device Owner and the `launcher` rule is on, KidGuard hides `dev.kidguard.home` (`setApplicationHidden`, data kept) so there is one "KidGuard Home" icon / HOME choice; rule off, safe mode and `release` show it again. Before that, the first time KidGuard's launcher turns on with no `home.json` of its own, it copies KidGuard Home's layout (whole `home.json`: pages, dock, style, notes, to-dos, timetable, Today cards) and its own wallpaper photo through the provider `content://dev.kidguard.home.export` (call `layout` -> `{json}`, file `/wall`; only an app with the same signing key may read it). Other apps' widgets are dropped (bound to that app's widget host). KidGuard Home 0.9.9 has no provider: update it first. A grid that grows a row (auto rows) re-flows only an auto-filled layout (every page but the last full); a hand-made one keeps its cells.
item `{"id","kind":"app|widget|folder|shortcut","x","y","w"?,"h"?,"pkg"?,"cls"?,"type"?,"name"?,"apps"?,"names"?,"awid"?,"opt"?}` (`names` = a folder's labels of its shortcut entries (entry -> label; `apps` entries are a package or `sc:` + package + `/` + shortcut id; sent only when non-empty), `opt` = a widget's own setting from its long-press menu; clock: `"center"`). `shortcut` = an app's pinned shortcut (`pkg` = the app, `cls` = shortcut id, `name` = label), added when an app asks to pin one while the kid launcher is the default home; shown / paused / hidden like its app, dropped with it and on the KidGuard Home import.
`timetable`: ISO weekday ("1" = Monday) → morning (`am`) and afternoon (`pm`) periods, up to 8 each, subjects of ≤ 30 chars,
index = period, `""` = free period (trailing blanks dropped). An older phone's plain array day is read as the morning. `bells`: one
bell schedule for every day, `[start, end]` minutes since midnight per period, up to 8 per session (default: 7:00 / 13:30, 45 min,
5 min changes, 20 min after period 2). A gap of ≥ 15 min between two periods is "ra chơi"; morning end → afternoon start is
"nghỉ trưa". Typed by the kid on the phone (the "Thời khoá biểu" widget's editor: by day / week (Mon–Sat, Sun when used) / bell times; a break's length changes by moving the later periods). Phone only.
- Widgets (`type`, sizes w×h): `clock` 4×1 / 4×2, `analog` 2×2, `month` 4×2 / 2×2, `time` 2×1 / 4×1, `next` 4×1, `quick` 4×1,
  `todo` 2×2 / 4×2 (the kid's `todo` list, tap ticks), `note` 2×2 / 4×2 (newest of `notes`), `counts` 2×2, `usage` 4×1 / 4×2
  (today's top apps), `battery` 2×1, `lock` 1×1 / 2×1 (one tap turns the screen off, same as the system widget), `when` 4×1 / 4×2 (6:00–22:00 bar of blocked / free time, time left, per-group left),
  `earn` 2×2 / 4×2 (`rules.chores`, "Xong" sends the same request as the "Hôm nay" page), `msg` 4×1 / 2×2 (`rules.blockNote` +
  newest pending request; the phone keeps no message history), `class` 4×2 / 2×2 / 4×4 (the period now or ra chơi / nghỉ trưa, today's `timetable`, what to pack for tomorrow; 4×4 = the whole week as a grid). Older phones drop types they don't know (repair on load). One of each
  type; on a 3-column grid a 4-wide widget is 3 wide. Placed from "Tiện ích" (long-press empty space) by tap or drag. Resizing grows it in
  place: what it covers moves down by the rows it grew, else to the first free cell of the page, else onto a new page right after.
- `grid.auto` (default true when missing): the phone picks the rows so the cells are about square on its screen (4..7;
  fewer when an icon + one label line would not fit), regridding once when the screen or columns change. Since 0.9.12 the
  rows are always automatic ("Tuỳ chỉnh" offers columns only); `auto` is still written (true) and ignored when read.
- Built-in `wall.id`: `sky`, `night`, `sunset`, `aurora`, `graphite`, `ocean`, `forest`, `candy`, `mint`, `space`, `dusk`,
  `berry`, `ember` (drawn on the phone; an older phone ignores an id it does not know and keeps the preset's wallpaper).
- `style.wall.dim` / `blur` (percent) apply to any wallpaper kind: the image is blurred, then darkened, once per change.
- `style.lib` (bool, default true): the App Library as the last home page (apps grouped: suggested, recently added, then by Android app category). Folders keep `apps` in the order the kid arranged them (hold an app in an open folder to move it; hidden apps keep their slots).
- `style.fx` (default `"auto"`): launcher effects. `lite` = no page fade while swiping, no live shadows under moving cards/menus (the folder zoom stays). `auto` = lite when the system animations are off (`ValueAnimator.areAnimatorsEnabled()` false: Developer options scale 0 / Remove animations) or on a weak phone (Android < 9, low-RAM flag, or < 3 GB RAM); `full` still goes lite when the system animations are off.
- Widget `type:"app"` (another app's home-screen widget): `pkg`/`cls` = provider component, `awid` = AppWidgetHost id (host id 0x4b47), any size w×h within the grid, several allowed. Not rendered (a locked card instead) while its app is hidden or blocked; the id is deleted from the host when the item leaves the layout. Older phones drop it (repair).
- Folder: `apps` (2–20 pkgs, in order), `name` (auto from the apps' category: Trò chơi / Mạng xã hội / Học tập /
  Giải trí / Thư mục; the kid can rename). A folder left with 1 app turns back into that app (on edit and on load).
  Blocked / new / hidden apps are never shown in a folder (it is not rewritten: what shows is decided at each render).
- `wall.kind:"photo"`: the kid's own picture in `wall.jpg` (same dir), centre-cropped to the screen, ≤1080 px wide, JPEG
  ≤~300 KB; `dim` darkens it (%). Picked with `ACTION_PICK_IMAGES` (API 33+) or `ACTION_OPEN_DOCUMENT`.
- `notes`: `{"id","t":ms,"text"(≤500),"share":bool}`, max 50, newest first: the kid's notes on the "Hôm nay" page.
- `todo`: `{"id","text"(≤100),"done":bool}`, max 50: homework the kid adds there.
Written atomically (tmp + rename). An unreadable file is moved to `home.json.bad` and a fresh one made; bad items are
repaired or dropped on load. New installs go to the first free cell; apps the kid removed (`seen`) are not re-added.
The file itself is not sent to the parent.

### State `notes` (in `GET /api/state`, relay state too)
`[{"id","t":ms,"text"}]`: only the kid's notes marked "Bố mẹ xem được" (`share:true`), newest 20. Read-only for the
parent. Absent = older app; `[]` = none shared.

### "Hôm nay" page (-1, left of page 1)
SOS row (not removable; unpaired ⇒ dials 112), today's timeline (schedules, group windows, bedtime; from `today()`),
chores (`rules.chores`: "Xong" sends the same request as the main screen with the chore id) + homework (`todo`), notes,
and the Download/KidGuard folder (files from the parent, red dot = not opened yet). A KidGuard button opens the main app.

## Support report to the developer (2026-11-11, `remote/Support.kt`)

Shared with tv-remote: the census worker `https://tvr-census.99-hoangtran.workers.dev` (`dev/census/src/support.js` in the
tv-remote repo). The owner reads with `python3 ~/Desktop/Github/tv-remote/dev/census/support.py --app kidguard [CODE] [--reply "…"]`;
without `--app kidguard` tv-remote's tools leave KidGuard out (list, `said`, crashwatch's news into tv-remote sessions). The PHONE talks to it, never the page (no CSP change).
- Crashes, automatic (2026-11-12): a crash of any thread is written to `files/crash_pending.json` and the next app start
  POSTs it (15 s later) to `/v1/crash` `{id: random uuid4 per install, app:"kidguard", v: versionName (+"-home"), where:"kotlin",
  kind, msg, stack (SupportScrub), dev:{brand, model, android, sdk, hw, board, device}}`; deleted on any 2xx/4xx, kept when
  offline. Emulators are dropped by the worker. Read: `python3 ~/Desktop/Github/tv-remote/dev/census/crashes.py --app kidguard [SIG]`
  (without `--app kidguard` tv-remote's lists leave them out; crashwatch shows a macOS notice "KidGuard: N crash mới" only).
- Command `support {kind, text?, pkg?}`: `kind` one of `block time relay location launcher bank battery other`; `text` <= 1000
  chars; `pkg` = one app the parent chose (else none). Answers `ok` (sending in the background) or `busy`.
  The phone POSTs `/v1/support {id (uuid4, per install), app:"kidguard", v, model, report}` and keeps `{code, key}`.
- The child phone's KidGuard menu "Gửi lỗi cho nhà phát triển" sends too, with NO PIN but a typed random 6-letter confirm (against a child pressing it), kind `other`, from `phone`, and shows the code;
  the thread is read and answered only on the parent page.
- Command `supportSay {text}`: the parent writes on the thread (`/v1/support/say`). `no code` / `empty` / `ok`.
- State `support: {busy?, err? (limit|net|http N), code?, made?, left?, msgs?: [{who: "dev"|"you", text, at}]}`; replies are
  asked (`/v1/support/thread`) every 6 h while the code is < 14 days old (the worker's keep), and right after a send / say.
- Report = allow-list (`Support.facts`): version, flavor, device, ROM, SDK, WebView, owner/mode/basic, caps, features, adbWhy,
  acc, usage, locationOn, dev, battery, update, launcher, sys, ts/tg/net (named sub-keys only), relay default|own|off + last OK
  age, counts of apps and paused lists, names of rules that are on; plus this process's logcat (800 lines) and the last crash
  (`files/last_crash.txt`, any thread, `App.onCreate`). Everything through `SupportScrub` (keys, tokens, PINs, long hex, bot
  token, emails, MACs, SSIDs, coordinates, public IPs, non-ASCII words, non-system package / host names -> `app#hash`).
  Never: location / track, notifications, notes, photos, files, app list / usage. No live look (tv-remote has one).

## Features and permissions
What works with which permission, so the child app and the web page can show it before the heavy setup (Device Owner)
is done. Source of truth: `guard/Features.kt` (pure, `FeaturesTest`). State adds (absent = older app):
- `caps`: `{"owner","admin","acc","usage","location","notify","battery","home","adb","paired","wss","adbNet","vpn"}`, all bool. `adbNet` = adb listens on port 5555 (DevOpts.tcpNow); `vpn` = a VPN (Tailscale) is up. `wss` = KidGuard holds WRITE_SECURE_SETTINGS (granted once by `pm grant` at setup; it stays after Developer options / debugging are turned off). `home` = KidGuard
  is the default HOME app now; `adb` = the phone can run adb against itself (false until that flow exists); `paired` = `everPaired`.
- `features`: `[{"id","ok":bool,"missing":["acc","admin"]}]` in display order. `ok` = one alternative has every cap;
  otherwise `missing` = the alternative with the fewest missing caps (first on ties).

| id | alternatives (any one) | note |
|---|---|---|
| `launcher` | `home` | works without Device Owner and without pairing (local opt-in, below) |
| `launcherLock` | `owner` or `home`+`acc` | owner: persistent preferred HOME; basic (rule `launcher.on`): Acc closes the Home-app picker + bounces another launcher, self-adb `set-home-activity` optional |
| `debloat` | `owner` or `adb` | |
| `limits` | `owner` or `acc`+`usage`+`admin` | basic mode bounces with Accessibility; needs usage and device admin |
| `block` | `owner` or `acc`+`admin` | `Basic.enforcing` also needs the device admin |
| `usage` | `usage` | |
| `location` | `location` | fine + background |
| `screen` | `acc` | screen view / remote input, API 30+ |
| `remote` | `paired` | |
| `noUninstall` | `owner` or `admin` | |
| `dns` | `owner` or `wss` | private DNS; without Device Owner raw `Settings.Global` writes (Chrome/Edge DoH-off stays owner-only) |
| `lockLocation` | `owner` or `wss` | location kept on; `wss` = raw `location_mode` write (unverified on Android 10+) |
| `adbShell` | `adb` | parent page adb terminal |
| `autoGrant` | `adb` | missing permissions granted again by the phone's own adb |
| `pcAdb` | `adbNet` | `adb connect <ip>:5555` from a computer (Wi-Fi / Tailscale) |
| `tailscale` | `vpn` | parent page / adb over Tailscale |

Bank-app pauses as caps (`Features.PAUSES`): `acc` → acc; `dev` → adb + adbNet; `tcp` → adbNet; `vpn` → vpn. The kid
popup / notification before such an app lists `Features.lost(caps, pause)` = features that work now and would not
without those caps. New pause kind or feature ⇒ a row there; `FeaturesTest` fails on a missing row or name string.

Child-app screen "Tính năng và quyền" (Thêm sheet) lists these with a button per missing cap.

Local launcher opt-in (phone only, not in rules): `Store.launcherLocal`. Since 2026-11-10 not owner ⇒ `wanted` = always
true (KidGuard always listed as a HOME choice; only safe mode / release hide it), the flag is legacy; the page never gates the `launcher.on` checkbox (the
`home` cap is its result, not a prerequisite); owner ⇒ the rule, with the forced HOME. Only the owner path adds the persistent
preferred activity. The flag is not sent in state; `release` disables the component as before.

Before setup (never paired, no parent PIN, no Authenticator) "Bố mẹ quản lý trên máy này" opens the local page without a
code (`PairPin.adminOpen`); with a PIN or TOTP, or once paired, the code is required as before.
The page skips its PIN gate while `caps.paired` is false (preview) and instead asks for a new parent PIN (twice) on the
first rules save; the PUT carries it as `"pin"`. That PIN then guards the local page and is the PIN the parent enters
when pairing their own phone (`/api/pair`), so it follows the parent from preview to pairing.

## 0.9.16 phone core (rules, state, events)
- **Rules** (all allow-listed, missing = default): `passUntil` ms (0 = none; `decide()` lifts everything but blocked/new while `max(unlockUntil, passUntil) > now`);
  `bedByDay` = `[]` or exactly 7 strings Mon..Sun = the night STARTING that day, each `"HH:mm-HH:mm"` or `""` (no bedtime; early-morning minutes
  belong to the previous day's entry; any bad entry drops the whole list); `oneMore` bool (default true: the child may take +1 minute once per lock period);
  `lostOn`, `lostMsg` (<= 200), `lostPhone` (<= 20), `lostLock` / `lostRing` bool (0.9.21, default true: lock on enter + every unlock / the 15-minute alarm; locating every 5 min is always on).
- 0.9.21 rules `msgs`: the parent's saved quick messages, list of strings (<= 12, each <= 120, blanks / repeats dropped). Edited in the message sheet of the page ("Lưu làm mẫu"), listed by the watch's "Nhắn" (built-in texts when empty); sent as an ordinary `message{text}` command.
- **Grant** (`Grant.apply`): `now.why` "" or "daily" raises today's `bonusMin`; anything else sets `passUntil = max(now, passUntil) + min`.
- **State** `now: {why, until, name}` (why: lost > paused > schedule > bedtime > daily; "pass"/"code" while a pass / Authenticator unlock is in force; `until` ms, 0 = unknown);
  `done: [{id,type,res,at}]` last 10 command results (relay and LAN); `lastAnswer: {ok,min,kind,app,at}|null`; `lost: {on,applied,ringStopped}`; `oneMore` bool = the +1 minute can be taken now.
- **Requests** (`requests[]`, at most one open): `{id, at, t(=at), remindAt, kind: "time"|"app"|"web", min, note, chore?, tpl?, pkg?, app?}`. Kind "web" (0.9.24): the child shared a link to KidGuard (`ShareWebActivity`, SEND text/plain); `app` = the host. Yes = the parent side adds the host to `webAllow` and drops it from `webBlock` (page `reqApprove`, parent app `Logic.approveWebRules`, Telegram `TgLogic.approveWeb`), then `answer {req, ok}`. A new ask while one is open = remind (same id, `remindAt` = now,
  rate-limited to 5 min, raises an `request` event whose dedup key is `r:<id>:<remindAt>`). Dropped on the first tick of a new local day. `answer {req, ok, min?, note?, always?}`: for kind "app" the parent side edits `approved`/`blocked`.
- **Events**: `lost` (on/off), `sim` (state/operator/carrier changed; no ICCID: a swap inside the same carrier is not seen), `released` (before teardown, one relay sync + Telegram tick), `releaseFail`,
  `oneMore` (info), `tamper` (basic mode: uninstall/turn-off page, clock page, accessibility off, reboot; rate-limited 10 min per key, 60 min for accessibility off). `Events.important`: sos, tamper, lost, request.
- **Lost mode**: (when `lostLock`) `lockNow` on enter and on every `USER_PRESENT` (basic: message card); lock-screen info = `lostMsg · lostPhone`; fix every 5 min; alarm 5 min every 15 until `ringStop` or lost off; `release` clears it.
- **Release**: event `released`, one final relay sync, then `POST /c/<id>/forget` with the kid token, then teardown (a failed release raises `releaseFail`).
- **PIN counters**: PIN / Authenticator code typed on the child phone (on-device admin) use their own counter + lockout; remote attempts keep `pinFails`/`pinLock`.
- **Support message** (Device Owner): refreshed when `now` changes: "until HH:mm" for bedtime / schedule / pause, "ask" wording only when paired and askable; the parent's `blockNote` wins; lost mode swaps it.

## Pair over relay (after 0.9.21: "Pair from anywhere" + QR invite)
Pairs a kid phone with NO parent yet (never paired, not the Home flavor) without a shared Wi-Fi. Worker-only rendezvous in Durable Object `p:<key>`
(class `Watch`, same binding; no migration). Neither side is authenticated yet, so: secrets (only their sha256 reaches the relay), short TTL, caps, and the
phone decides (`Store.tryPair`: code, `PairPin.decide`, PIN lockout, same errors as `POST /api/pair`). Bodies <= 512 B; per-IP `RL` like `/c/*`.
- Code flow. The phone shows its LAN code on the child screen (`visiblePairCode`) and offers it: `POST /p/new {"sh": sha256hex(phone secret, 32-128 hex), "code": "123456"}`
  -> `200 {exp}` (TTL 15 min, `PAIR_TTL`); `409 taken` = another phone holds that code (the phone makes a new one); the same phone repeating it renews. Cap `PAIR_PER_DAY` (100) per network (Gate `pip:`), 429 else.
- Parent (page "Pair from anywhere", browser = the relay it was served from; app = default relay through its proxy, `Logic.isPairPath`):
  `POST /p/<code>/join {"th": sha256hex(ticket, 32 hex), "newPin": "4-8 digits", "pin": "", "name": "<= 20"}` -> `200 {ok}`; `403 wrong code` (unknown / expired / dead);
  `409 busy`; `429 {error: "too many wrong codes", wait: <ms>, retryAt}` after `PAIR_MISS_MAX` (20) join tries from one network inside a fixed `PAIR_MISS_WIN` (10 min) window that starts at the first try (Gate `pmiss:` with `win`; every join counts, right, wrong or busy, taken BEFORE the lookup so a parallel burst can't pass the cap; since 2026-11-15, was 40 wrong codes a day). Math: P(hit any live code) ~ live codes x guesses / 10^6; <= ~30 guesses per 15 min code life per network. One code takes `PAIR_JOINS` (5) joins, then it is deleted.
  Then poll `GET /p/<code>/result` header `X-KG-Ticket: <ticket>`: `{waiting:true}` | `200 {id, token, url}` once (the entry becomes `taken`; later `404`) | `{error, wait?}` = the phone refused:
  400 `bad pin` / `pin required`, 403 `wrong pin` / `closed`, 429 `pin locked` / `wait`. After an error the code can be joined again.
  `id` = 16 hex, `token` = 48 hex (a parent token of its own, see "Parents"; the page builds a `via:"relay"` kid), `url` = the relay origin. `newPin` is required (the phone has no PIN yet; `pe.pin required`).
- Phone: `GET /p/<key>` header `X-KG-Secret` every 2-3 s while the screen is on and shows the code -> `{waiting}` | `{req:{pin,newPin,name}}` | `{taken:true}` (then the entry is gone) | 403 / 404 = gone.
  It answers with `POST /p/<key>/reply {"id","token"}` (ok: token minted by `tryPair`, pending as in LAN pairing) or `{"error": "...", "wait": ms}`. On `taken` it commits (`Store.commitRelayPair`: token, `everPaired`, new PIN hash, `relayUrl`/`relayId`/`relayToken` = default relay): nothing is stored before the parent holds the link.
- Invite flow (QR provisioning). `ui/qr.html` "Ghép luôn với tôi": `POST /p/inv {"th", "newPin", "name"}` -> `200 {inv: "<16 hex>", exp}` (TTL 3 h `INVITE_TTL`, cap `INVITE_PER_DAY` 30 per network, Gate `piv:`).
  The QR payload then carries `android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE = {"kg_invite": "<inv>"}` (only the id; the PIN stays on the relay, deleted when used). After provisioning `Admin.onProfileProvisioningComplete`
  reads the extra (`PersistableBundle`, `RelayPairLogic.invite`) into `Store.setInvite` (valid 3 h, only while nobody is paired); `RelayPair` claims it with `GET /p/<inv>` (first secret wins; a second phone gets 403),
  runs `tryPair(viaInvite = true)` (no code, but the same PIN rules), replies, commits on `taken`. The qr page polls `GET /p/<inv>/result` with its ticket and saves the kid in `localStorage kg.kids`. An invite is one shot: any error deletes it.
- Expiry: lazy + alarm like Watch. Rejected: sending the PIN hash (the phone salts it itself), a second token for the relay (superseded 2026-11-17: the result now carries a parent token of its own, see "Parents"), pairing a phone that already has a parent (use `openPairing` over LAN or the relay link).

, Zepp watch app `watch/zepp/`)
Pairs a watch with a kid by a 6-digit code. Worker routes (relay only, no phone involvement; the phone needs no new version).
- `POST /w/new` body `{"sh": "<sha256 hex of the watch's secret, 64 chars>"}` (no auth, body <= 512 B) -> `200 {"code": "123456", "exp": <ms>}`.
  Code = random 6 digits (collision retried), valid 10 min (`WATCH_TTL`), stored in Durable Object `w:<code>` as `{sh, exp}`; the secret itself never reaches the relay. Errors: 400 bad json / sh, 405 method, 413, 429 (`slow down` per-IP, or `too many codes from this network, try tomorrow`: 30 codes per network per day), 503 `try again`.
- `POST /c/<id>/watch` body `{"code": "123456", "name": "<kid name, <= 20>"}` (body <= 512 B). Auth exactly like every parent route of that kid (`X-KG-Token`; missing / wrong = 401 `not paired`, unbound id = 409 `waiting for the phone`). Attaches `{url: <the origin the call came in on>, id, token: <the caller's token>, name}` to the waiting code -> `200 {"ok": true}`. Errors: 400 `bad code`, 404 `no such code` (never asked for, expired, already fetched), 409 `code already used`, 405 method.
- `GET /w/<code>` header `X-KG-Secret: <secret hex, 32-128 chars>`: `403 forbidden` (missing / wrong secret; never reveals whether the code exists with another secret), `404` (unknown / expired / already fetched), `200 {"waiting": true, "exp": ms}` until attached, then `200 {"url","id","token","name"}` ONCE: the entry is deleted with that reply.
- Expiry: lazy on every access plus a Durable Object alarm at exp + 1 s (`deleteAll`). No listing route. Credentials of an expired, never-fetched code are deleted, not handed out.
- Token choice (2026-11-17): the watch gets a parent token of its own (kind "watch", named as typed; see "Parents"), listed and revocable in the page's parents list; "renew relay link" (a new relay id) cuts it off.
- Watch side service: default relay `https://kg-relay.99-hoangtran.workers.dev` (= `DEFAULT_RELAY`), overridable in the Zepp settings page; polls ~3 s while the connect screen is open; appends `{url,id,token,name}` to settingsStorage `kids` (the shape the Zepp settings page stores).
- Parent page: Settings > "Đồng hồ" / "Watch" (`s-watch`): 6-digit input + Connect; shown only when the kid has a relay (relay kid, or LAN kid with `relay` on).
- `GET /c/<id>/tile?z=&x=&y=` (watch map, after 0.9.21): one OpenStreetMap tile (PNG) fetched by the worker and edge-cached 7 days. Auth like every parent route (`X-KG-Token`); `z` 13-18, `x`/`y` inside the zoom (else 400 `bad tile`); 502 `tile <status>` when OSM fails. Why: some networks block `tile.openstreetmap.org` (DNS -> 127.0.0.1) while the relay works; the watch side service falls back to OSM directly.
  Also accepted without `X-KG-Token` when `k=<tileKey>` is in the query (`tileKey = sha256hex("tile:" + token)`, given by `state?watch=1`): for watches whose image request cannot send headers (Garmin `makeImageRequest`). The key only fetches OSM tiles; wrong / other kid's key = 401.
- `GET /c/<id>/state?watch=1` (after 0.9.21): the same auth and keys as `/state` but small (~2-6 KB, Garmin's reply limit): `state` = every scalar field of the phone state as is, plus `now`, `location`, `lost` objects, `requests` (last 3), `done` (last 10), `sos` (last 3), `events` (last 10, `{t,kind,text<=80}`), `apps` = 15 most used `{pkg,label<=24,ms}` (no icons), `currentLabel`; `rules` whole (watches edit them with the full-replace PUT); `seen`, `pending`, `queue` (last 10), `expired` (last 10), `tileKey`.

## Calls (2026-11-15, `guard/Calls.kt`, `CallsLogic.kt`)
- **Rule** `calls`: `{"block": "off" | "unknown" | "hidden", "allow": ["+84912345678", ...]}` (max 30, 3-15 digits each; full replace like every rule; not overridable by a timed mode).
  `hidden` rejects only calls with a withheld number. `unknown` rejects withheld numbers AND numbers that are neither in the phone's contacts (PhoneLookup) nor in `allow`.
  Numbers compare by digits only; Vietnam `+84` / `0084` / `84` + 9 digits = `0` + 9 digits. Emergency numbers (112 113 114 115 911 ..., `PhoneNumberUtils.isEmergencyNumber`) are never rejected.
  Without READ_CONTACTS a shown number is let through (we cannot tell); withheld numbers are still rejected.
- **Phone side**: `CallScreen` (`CallScreeningService`, kid flavor only) answers `disallow + reject + skipNotification` (the call stays in the call log as rejected). Android 10+ and the `android.app.role.CALL_SCREENING` role.
  Role: `AutoGrant` runs `cmd role add-role-holder --user 0 android.app.role.CALL_SCREENING dev.kidguard` (+ `pm grant` READ_CONTACTS / READ_CALL_LOG) through our own adb when the rule is on; Device Owner also grants both permissions in `Policy.apply`.
  Without own adb the child's status card shows a line; one tap opens `RoleManager.createRequestRoleIntent`. Below Android 10: `callsCap.api = false`, nothing is screened (the call list still works).
- **Event** `call` (info level): "Đã chặn cuộc gọi: <number or số ẩn>", the same caller is not repeated within 10 min. It reaches the page, Telegram and the parent app like other events.
- **Release**: `Calls.release` takes the role back with `cmd role remove-role-holder` when we added it (`Store.callsRole`); a role the child accepted stays until changed in Settings -> Default apps. Release also resets `rules.calls` to off (the service then never rejects) and, for Device Owner, takes READ_CONTACTS / READ_CALL_LOG back.

## Family chat (2026-11-17, `guard/ChatLogic.kt`, `Store.chat/addChat`, `ui/ChatActivity.kt`)
One text thread per child phone, both directions. No new endpoint and no new command: it rides on the existing `message` command and on `state`.
- State `"chat": [{"id": "a1b2c3d4", "t": <ms>, "from": "p"|"k", "who": "Mẹ", "text": "..."}]`, newest last, the last 30 (the phone keeps the last 100 in prefs `chat`). `from` "p" = a parent, `who` = the parent's name (relay: the `by` the worker stamps; LAN / Telegram: ""), "k" = the child (`who` ""). Text <= 500 chars, control characters dropped.
- Parent -> child: `message{text}` (`SHORT`, 10 min on the relay). The phone now also appends it to the thread; an empty text answers `failed: empty`. A re-delivered command is deduped by its id (`markCmd`), so it is not added twice. Not in `ONCE` on purpose (not destructive).
- Child -> parent: written on the phone only (`ChatActivity`: tile "Nhắn bố mẹ" on the KidGuard screen, "Trả lời" button of a parent message). It also adds the event `{"kind": "chat", "text": <first 120 chars>}`, so the normal event paths carry it: state `events`, relay `pushNew` (FCM `{kind: "ev", ek: "chat"}` with no text), parent app "<kid>: <text>" notification. Telegram does not forward chat (the relay only sends newApp / tamper / SOS / requests).
- Clear (2026-11-17): command `chatClear` (no fields). Phone: `Store.clearChat()` (thread + unread marks), child log line `log_chat_cleared` ("Bố mẹ đã xoá lịch sử chat"), the "message" notice (id 3) cancelled; answer `ok`. Both sides then see an empty thread (state `chat: []`). Worker: `CMDS`, `ONCE` (destructive: kept until acked, 1 h window) and `SHORT` (10 min). Not PIN-gated (`CmdGate.GATED` unchanged: it cannot take the phone away from the parents, and the page asks with a red confirm first). The child has no way to clear.
- Older messages (2026-11-17): LAN `GET /chat?before=<ms>&n=` = `{"chat": [<entries, oldest first>], "more": bool}` = entries with `t < before` (0 / missing = from the newest), the newest `n` of them (default 30, max 50), `more` = still older ones; relay command `{type:"chatPull", fid:<16 hex>, before, n}` -> answer `ok` | `bad fid`, then the same JSON on the file channel (`kidguard-chat.json`) under `fid` (same as `smsPull`; worker: `CMDS`, `SHORT`, `REPLACE`, fid / number shape check `bad chatPull`). Needs no rule (the parent already reads the thread). The page asks again with `before` = the oldest `t` it has.
- Kid launcher (kid flavor only, paired): control-center tile "Nhắn bố mẹ" and the "Lời nhắn bố mẹ" widget open `ChatActivity`; the unread dot = prefs `chatLastP` (time of the newest parent message) > `chatReadAt` (set by `ChatActivity.draw`); both cleared with the thread. Not in state.
- **Attachments** (2026-11-17, the shared-files feature merged into chat). Entry gains optional `att: {"id": "<16 hex>", "name", "mime", "size", "kind": "image"|"video"|"audio"|"file", "dur"?: seconds (audio / video), "st": "ok"|"wait"|"gone"}`; `text` may be `""` when `att` is set. `id` = the Files index id (same id as `GET /api/files/<id>`, `fileUp {file}`); the chat entry `id` is that same id. `from` stays `"p"` / `"k"` (not "parent" / "kid").
  - `st`: `gone` = the file was deleted (the entry keeps its text) or is no longer in the index; `wait` = a kid file no parent has fetched yet (becomes `ok` after a LAN full download or a complete relay push); else `ok`. Derived on every read (`Files.chatView`), so state, `chatPull` / `GET /chat` and the child's screen agree.
  - Storage on the kid phone = the family's archive: no total cap, never auto-deleted, exempt from the 100-entry / 50-outbox / 1 GB-inbox limits of "Shared files" (per-file caps stay: LAN 200 MB, relay 50 MB). Images / videos -> `MediaStore` Pictures/KidGuard (a video falls back to Movies/KidGuard), others (audio, documents) -> Download/KidGuard (API 26-28: the same folders with the existing storage grant). State has `freeMb` (internal storage free, MB, -1 unknown); the page warns under ~1024.
  - Parent -> kid: the page uses the normal Files paths with a chat flag: LAN `PUT /api/files/<name>?chat=1[&dur=<s>]`; relay chunk `POST /c/<id>/file?...&chat=1` (the worker stores `chat:1` in the file meta and `GET ?to=kid` lists `"chat": 1`). When the phone saves a file with that flag it adds one chat entry from `"p"` with the `att` (no `message` command; text and file are separate entries). Without the flag the file is the old plain shared file.
  - Kid -> parent (`ChatActivity`): "+" menu = photo / video (system picker), take a photo, record a video (camera intent into `CaptureProvider`), any file (SAF); hold-to-record mic (AAC m4a mono 32 kbps, max 120 s, release = send, slide left = cancel; `RECORD_AUDIO` is already held for Listen, else asked at runtime). The share sheet "Gửi cho bố mẹ" does the same. Each file is stored in the archive, listed as an `out` Files entry (so LAN `GET /api/files/<id>` and relay `fileUp {file}` work) and added to the thread as one `"k"` entry (also the event `chat`, text = caption or file name).
  - Thumbnails: LAN `GET /api/files/<id>?thumb=1` = JPEG <= 320 px long side (image, or the first frame of a video; 404 `no thumb` for audio / documents). Relay: command `fileUp {file: <id>, thumb: 1}` (answer `ok` | `no thumb` | `no such file`) pushes the JPEG on the normal parent file channel with `fid` = the id with every hex digit complemented (d -> 15-d, `0`<->`f`; still 16 hex), name `<name>.thumb.jpg`, query `thumb=1` (a thumbnail is <= 200 KB and does NOT count in the daily quota). Cached in the app cache.
  - Bulk delete (2026-11-17, page "Chọn" mode + the child's gallery): `chatFileDel` also takes `{ids:[<16 hex>, ...]}` (1..200, duplicates dropped; the single `{id}` form still works); `chatDel {ids:[<message ids>], files?: true}` removes whole messages from the thread (`files` also deletes their attachments; deliver-once, SHORT; `bad chatDel`; log line `log_chat_del`); `fileDel` also takes `{files:[...]}` (1..200). One id-list command per 200 ids, one confirm on the page. The child can bulk-delete only gallery FILES (`ChatActivity` select mode), never messages.
  - Delete one: command `chatFileDel {id}` (worker `CMDS`, `ONCE`, `SHORT`; id shape check `bad chatFileDel`; answer `ok` | `bad id`): file removed from storage + Files index, entry keeps its text with `att.st = "gone"`. LAN `DELETE /api/files/<id>` does the same for a chat file. `chatClear` gains optional `files: true` = also delete every chat attachment (default false keeps the files, the thread is cleared; the child's gallery still lists them).
  - Kid UI: bubbles show the image / video thumbnail (play badge), an audio row (play / pause, duration) or a file row; a tap opens it with the system viewer. Top button "Ảnh & file" = gallery of everything kept (grid of photos / videos, list of voice / files, filter chips; also the files of the old shared folder). The "File với bố mẹ" tile / menu / Today link open the gallery; `FilesActivity` stays only for old notification paths.
  - Release: chat attachments in Pictures / Download are the family's files and are KEPT; the index, thumbnail cache and scratch files are wiped, the thread is cleared (`Store.clearChat`).
- Page: the "Nhắn" sheet shows `state.chat` plus the parent's own sends not echoed yet; unread = kid messages newer than `localStorage kg.chat.<kid id>`. Home flavor: no `ChatActivity`, no `message` handling.
## Monitoring: what the parent may read (2026-11-17, `guard/Monitoring.kt`, `WatchRule`)
Owner decision: reading the child's content is allowed ONLY with clear disclosure. One registry (`Monitoring.ALL`, one `Cat` per key of rule `watch`) is the source of what the child is told; nothing can switch the disclosure off.
- **Rule** `watch: {notif, ...}`: every key default `false`; full replace like the rest of the rules (send the whole object). `watch.<id>` is the registry rule path. Not in a timed `mode`.
- **Disclosure** (kid flavor only): (a) the lasting KidGuard notification gets the line "Bố mẹ xem được: <titles>" and opens the screen; (b) one heads-up notification (channel `kg_watch`, high) each time a category goes off -> on (`Guard.setRules`); (c) screen `WatchActivity` ("Bố mẹ xem được gì", from that notification, the heads-up and the main screen) lists every active category with what the parent gets, plus what is never seen.
- **Test gate**: `MonitoringTest` fails when a key of `WatchRule.toJson()` has no registry entry or no `watch_<id>_title|kid|sees` string in `values` and `values-vi`. A new monitoring feature = a key + a `Cat` + its strings + a row in `docs/support-matrix.md`.
- **Release** (`Guard.release`): `Monitoring.release` cancels the heads-up; each feature adds its own undo (rule off with the rest of the rules, stored data wiped, permissions the Device Owner granted taken back).
- Event text of a changed `watch` in the rule-change log: "những gì bố mẹ xem được trên máy này" (no values, the page shows them).

### Notifications to the parent (rule `watch.notif`, `remote/NotifSync.kt`, `guard/KeywordLogic.kt`)
- Source: the kid's own notification history (`NotifLog`, filled by the notification listener `NotifDots`). Own app, ongoing / progress / foreground-service, group summaries and media are never recorded. Needs notification access (the pull reply's `access`).
- **Automatic feed** (`watch.notif.on`): every new notification that passes the rule (`kw` = has a word of `watch.words`; `apps`/`list` = app filter) goes into a ring of the last 50 in memory (a repeated post with the same title/text is not new; lost on a phone restart, then the pull covers it). The sync body carries the newest 20 as state `notifs: {"n": [{"id","t","pkg","app"<=40,"title"<=80,"text"<=110,"hit"?}] oldest first, "more": true when older ones were left out}` (no extra relay request per notification; `id` increases, starts from the clock so it survives a restart). Absent when off or empty. The page merges by `id`, keeps 100 per kid in its own browser and shows them live. `hit` = the keyword that matched.
- **Older ones on demand**, never in state, newest first, `n` <= 50 (default 30) per page. Reply JSON (both paths):
  `{"on": bool, "access": bool, "items": [{"id","t","pkg","app","title"<=200,"text"<=300,"msgs":[{"s","t"<=160}] (last 5 of a chat)}], "more": bool, "next": <ms>}`; `next` = the `before` of the following page; `on:false` = the rule is off (empty items).
- LAN: `GET /api/notifs?before=<ms>&n=<1..50>` (token like every route; not allowed for the AI token).
- Relay: command `notifPull {fid, before, n}` (`fid` = 16 hex made by the page; short-lived: SHORT list, a newer copy replaces a queued one; not deliver-once; answers `ok` = started, `off`, `bad fid`) -> the phone puts the reply JSON on the relay file channel (`fid`, name `kidguard-notifs.json`, `to=parent`, one chunk like `shotUp`); the page polls `GET /file?to=parent` for `fid`, reads chunk 0, then `DELETE`s it.
- The first page (`before` 0 / missing) writes a log line on the phone ("Bố mẹ vừa xem thông báo của bạn", kind `watch_read`) so the child's own log shows every look.
- Disclosure text follows the mode: `notif.kw` true = "thông báo có từ khoá của bố mẹ" (`watch_notif_kw_*`), else "mọi thông báo"; going from keywords-only to everything is told again (heads-up). `watch.words` non-empty is its own entry ("cảnh báo từ khoá").
- Release: the ring is emptied (`NotifSync.clear`); the rule goes with the rest of the rules.

### Word alerts (rule `watch.words`, 2026-11-17)
- Every notification the phone records (see "Notifications to the parent"; later also an SMS) is matched against `watch.words` (ignores case and accents, substring; `KeywordLogic`). It works with `watch.notif` off, and with `watch.notif.kw` (keywords only) the same matches also fill the feed.
- A hit = one event `{kind: "word", text: "<app>, từ khoá "<word>": <title>: <short piece around the word, <= 120 chars>"}` (`Store.event`), so it reaches the parent like any event: page list, Telegram, and the parent app by FCM (`ek: "word"`, no text to Google). "Important" kind. Never one push per notification: only hits, at most 1 per app+word per 5 min and 12 per hour (`WordThrottle`).
- Registry entry `watch.words` (on = list not empty); the child is told like for every category.

### Photo on request (rule `watch.cam`, command `snap`, 2026-11-17)
- Command `{type:"snap", fid:<16 hex>, cam:"front"|"back", flash:"off"|"on"|"auto", size:"small"|"medium", delay:0..5, relay:bool}`. Answer: `ok` or one of `off`, `screen off`, `no camera permission`, `no such camera`, `busy`, `unsupported`, `bad fid`. Worker: in `CMDS` + `SHORT`, validated (`bad snap`); not deliver-once (a photo order may wait).
- Result: size small = longest side 640 px, medium 1280 px, JPEG. LAN: `GET /snap/<fid>` = `image/jpeg`, 404 `{"error":"pending"}`, 409 `{"error":"<why>"}` (why: timeout, camera, left, closed, busy). Relay (`relay:true`): file channel, same `fid`, name `kidguard-snap.jpg`, or `kidguard-snap.json` `{"error"}`.
- Registry entry `watch.cam` (told like every category; each photo also gives the child a notification and a log line `watch_snap`). History (2026-11-17): every photo stays in the phone's private storage (`filesDir/snaps/<fid>.jpg` + `<fid>.json` meta `{id, at, cam, flash, ok, err?, size}`) with NO count or age cap; a failed try is recorded too (`ok:false`, `err` = the answer above). Release and rule `watch.cam` off wipe them all. State `snaps: {n, mb}` (while `watch.cam`); the page shares the chat's low-space warning (`freeMb` < 1024). Pages newest first: LAN `GET /snaps?before=<at ms>&n=<1..60, default 30>` -> `{"snaps":[meta...],"more":bool}`; relay command `snapList {fid, before, n}` (SHORT, answers `ok` | `off` | `bad fid`; reply JSON on the file channel, name `kidguard-snaps.json`, like `notifPull`). Thumbnail <= 320 px: LAN `GET /snap/<id>?thumb=1`; relay `snapUp {id, thumb: 1}` pushes it under the complemented fid (`thumb=1`, not in the daily quota). Full photo again: LAN `GET /snap/<id>`, relay `snapUp {id}` (file channel, fid = id). Delete: `snapDel {ids:[...]}` (1..200, deliver-once, `bad snapDel`; log line `log_snap_del`). The page lists them in the Seen tab ("Ảnh đã chụp", day groups, select mode) and caches thumbs in IndexedDB `kgchat`.

### Listen history (2026-11-17)
- The recording is made ONLY in the parent page: `MediaRecorder` on the incoming audio (`<audio>.captureStream()`, LAN stream or relay WebRTC alike), switch "Ghi lại" while listening or "Luôn ghi" (`localStorage kg.ear.always`). Stored in the page's IndexedDB `kglisten` (store `s` = one row per session `{id, kid, at, dur, via, rec, mime, size}`, store `b` = the audio Blob). Nothing is recorded on the phone and nothing is uploaded.
- The phone keeps its own list of sessions (no audio): `Store.listenHist` (newest 100) and state `listen.hist` = newest 20 `[{at, dur (s), via: "lan"|"relay"}]`; the page shows the ones it has no local row for (+-90 s) as "heard from another device". Cleared on release.

### Text messages (rule `watch.sms`, command `smsPull`, 2026-11-17)
- Rule default off; registry entry `watch.sms`. Needs `READ_SMS`: Device Owner grants it in `Policy.apply` (and takes it back in release); basic mode tries `pm grant` through our own adb (at most every 10 min); otherwise the page says so and the notification feed still carries SMS notification text.
- On demand, newest first, inbox + sent, 30 a page (max 50): LAN `GET /sms?before=<ms>&n=` = `{on, access, sms:[{id,t,from,text,out}], more}`; relay command `{type:"smsPull", fid:<16 hex>, before, n}` -> answer `ok` | `off` | `bad fid`, then the same JSON on the file channel (`kidguard-sms.json`) under `fid`. Text cut at 600 chars. The child's log gets one line per first page.
- Word alerts: `Sms.tick` (Guard tick) checks new incoming SMS (after the moment the rule was seen on) against `watch.words` and raises the same `word` event as notifications (app "SMS"); throttled the same way.


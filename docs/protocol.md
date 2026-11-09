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
  "noVpn": false,                  // NEW (default false): DISALLOW_CONFIG_VPN (Device Owner); also kept while our Tailscale access needs it
  "noInstall": false, "noDebug": false,
  "accPause": ["com.VCB", "…"],    // NEW: apps that refuse to run while any accessibility service is on (banks by default; parents add any package). Absent = the phone's
                                   //      default list (AccPauseLogic.DEFAULT, ~15 VN banks / wallets); [] = feature off; max 100.
                                   //      Owner mode + WRITE_SECURE_SETTINGS only: when one opens (launcher tap or its window) the
                                   //      phone removes its own accessibility service, and puts it back once none has been in front
                                   //      for 10 min (their background processes are killed then). Nothing for release to undo
                                   //      (release forgets the pause; the service is removed by release anyway).
  "pinHash": "…",                  // the page may send "pin": "1234" instead; phone/worker store only the hash
}
```

Block reasons in `decide()` (priority): `blocked` > `appLimit` > `groupHours` > `groupLimit` (v0.9.4) > `new` > `paused` > `schedule` > `bedtime` > `daily`.

## State JSON (phone -> parent). New fields marked NEW.

Everything in v0.1 stays (`id t day model android version owner usageAccess battery charging screenOn current usedMs
leftMs apps rules location lan relay log`), plus:

```jsonc
{
  "version": "0.9.4",
  "acc": true,                     // NEW: accessibility service connected (live screen / remote control available)
  "accPaused": [],                 // NEW: packages it is turned off for right now (rules.accPause); non-empty = acc false on purpose
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
  "ts": {"state": "off|installing|on|error", "ip": "100.x.y.z", "host": "kid-an", "err": "", "noExpiry": false},  // v0.4: Tailscale (never the key); noExpiry v0.9.6: node key expiry turned off
  "requests": [                    // NEW: the child asked for more time (button on the child's KidGuard screen)
    {"id": "a1b2c3", "t": 1793940898493, "min": 30, "note": "con làm bài tập"}
  ]
}
```

## Commands (`POST /api/cmd` on LAN, queued via `POST /c/<id>/cmd` on the relay)

v0.1: `lock locate message{text} ring relay{url} openPairing unpairAll release`. NEW:
- `unpair {id}`: revoke ONE paired parent device (`id` from `state.devices[].id`). Result `ok` or `no such device`.
  Destructive: the worker whitelists it and keeps it until acked (see v0.2.1 "Commands are idempotent"; in the `ONCE` list like `unpairAll`).
  The worker keeps `id` as the command id (it is 8 hex chars, so it passes the id check); do not send other ids. Revoking
  the device you are using is allowed (it gets 401 next request and must re-pair). If that device was the relay link
  (the one named `relay`), the relay token / id are cleared too, so the phone stops syncing until the relay is set up again.
  LAN also has `POST /api/unpair {"id"}` -> `{"result":"ok"}` or 404 `{"result":"no such device"}`. `release` is not affected
  (it still clears every token).
- **State** `devices: [{"id","name","at","last","self"?:true}]`: paired parent devices, oldest pairing first. `id` = first 8 hex
  of sha256(token) (non-secret; the token never leaves the parent's browser). `name` = the page's optional `X-KG-Name`
  header (<=40 chars, taken the first time it is seen; the relay link is named `relay`; may be ""), `at` / `last` = epoch ms of
  pairing / last LAN request (`last` moves at most every 5 min; 0 for phones paired before this). `self: true` marks the
  caller's own row on LAN `/api/state` only; the relay-cached state has no `self`, the page can compute its own id as
  sha256(token).slice(0,8).
- `answer {id, ok: bool, min?}`: the parent answered a time request. The page ALSO grants the time itself by
  saving rules (`bonus`), the command only removes the request and tells the child (notification).
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
  403 `closed`. Every 3rd wrong guess makes a new code and blocks pairing for 30 s, doubling (cap 32 min).
  The PIN (child screen and /pair share one counter): 5 free tries, then locked 5 min, doubling (cap 320 min).
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
- `tailscaleOff`: undo all of it and uninstall Tailscale (also done by `release`). In the worker whitelist.
v0.9.6:
- `devOff`: turns off wireless debugging (`adb_wifi_enabled`, API 30+), USB debugging (`ADB_ENABLED`) and Developer
  options (`DEVELOPMENT_SETTINGS_ENABLED`) with `Settings.Global.putInt` (WRITE_SECURE_SETTINGS); as Device Owner it
  falls back to `DevicePolicyManager.setGlobalSetting` where Android allows it. Banking apps often refuse to open while
  these are on. Result `ok | not allowed | failed: <reason>` (`not allowed` = `dev.canOff` false). Logs `log_dev_off`.
  Not a new restriction (nothing for `release` to undo); the child can turn them on again unless `noDebug` is set.
  In the worker whitelist (repeatable, not deliver-once). Over LAN through an adb-forwarded port the reply can be lost
  (adb goes down with it); normal Wi-Fi LAN and the relay are fine.
- Saving rules that switch `noDebug` from false to true also runs `devOff` once.
v0.9.7:
- `devOn`: the way back after `devOff`: turns Developer options and USB debugging (`ADB_ENABLED`) on with
  `Settings.Global.putInt` (needs WRITE_SECURE_SETTINGS; the Device Owner fallback can't write these). Result
  `ok | not allowed | blocked | failed: <reason>` (`blocked` = the `noDebug` rule's DISALLOW_DEBUGGING_FEATURES is set).
  State `dev.canOn` (bool, v0.9.7) says whether it can work; the page shows the button only then. Logs `log_dev_on`.
  In the worker whitelist (repeatable).

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
- `/api/hello` answers on any method (no token; the pairing probe).

### Relay (worker), token header `X-KG-Token`, id = 16 hex
- Limits: `POST /c/<id>/cmd` -> 429 `queue full` with 20 commands waiting, 413 `too big` over 4 KB of JSON; `PUT /c/<id>/rules`
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
- Phone `POST /c/<id>/sync` body `{state?, acks: [cmd ids]}` (`state` omitted when unchanged, see v0.2.1) -> reply
  `{cmds: [...], rules?, live?, files?, device?}`: `rules` only when the worker's `rules.rev` is higher than the `state.rules.rev`
  it knows; the phone takes them only if `rules.rev` > its own (so a phone clock ahead of the worker's makes relay edits
  wait until the worker rev catches up; LAN saves stamp `rev = max(old+1, phone now)`); `files` = number of
  parent -> kid files waiting; `device` = the parent asked for a device-info upload.
- Parent `GET /c/<id>/state` -> `{state, rules, seen, pending}`: `state` the last synced state (`rules` inside it with `pinSet`
  instead of `pinHash`), `rules` the worker's copy, `seen` ms of the last sync, `pending` = queued command count.
- Input `ms` (swipe duration) is clamped to **50..3000** by both the worker and `Acc.kt`.
- `GET /c/<id>/state` already returns `seen` (ms of the phone's last sync): the page warns when `now - seen > 5 min`.
- The worker also serves `GET /install` -> `ui/install.html`.

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
- **Wi-Fi lock**: the service holds a `WIFI_MODE_FULL_HIGH_PERF` lock while it runs (some ROMs, e.g. ColorOS 8.1, put
  Wi-Fi to sleep with the screen off and the LAN page drops). Costs some standby battery. Released on stop / release.

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
Steps 1-6 are identical on all four paths. `SelfSetup` omits steps 7-8 (`am start`, LAN URL: it already runs inside the app) and adds an accounts pre-check and Wi-Fi-off; the other three rely on `dpm set-device-owner` failing when accounts exist.
1. `adb install -r -g kidguard.apk`
2. `adb shell dpm set-device-owner dev.kidguard/.Admin` (verify with `dumpsys device_policy`)
3. `adb shell appops set dev.kidguard GET_USAGE_STATS allow`
4. `adb shell pm grant dev.kidguard android.permission.WRITE_SECURE_SETTINGS`
   then, optional (errors ignored, for remote phone settings): `appops set dev.kidguard WRITE_SETTINGS allow` and
   `cmd notification allow_dnd dev.kidguard` (Do Not Disturb access) and
   `cmd notification allow_listener dev.kidguard/dev.kidguard.NotifDots` (launcher notification dots: counts per app
   only, never stored or sent; writing `enabled_notification_listeners` does not work since Android 8, measured on 8.1)
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
  and drops an unacked one 10 min later (the phone may have released and stopped syncing). "Now" commands (`lock locate
  message ring wake ringStop listenStop devOn devOff setting`) expire on the worker after **10 min** (others 24 h), so a
  `ring` queued overnight does not fire in the morning. The queue refuses at 20 entries (`429 queue full`).
  `answer`: the worker keeps `id` as command id only if it matches `^[\w.-]{1,64}$`, else it makes a UUID; the request id
  is always copied to `req` and the phone answers `req` (falling back to `id` on LAN).
- **State** adds `dnsError` (`""` or a short reason when the Private DNS host was rejected; the phone then falls back
  to opportunistic DNS and clears the restriction so the child keeps internet) and keeps `usageAccess` (page warns when false).
  Also `usageOff` (= `!usageAccess`; the phone raised one `tamper` event for this revoke, shows the child a notification
  to turn it back on, and logs `usage_on` when it returns) and `dnsPausedUntil` (ms, 0 when no pause is running).
  A `tamper` event is also raised once per package when a non-system app becomes the default HOME (it stays exempt from limits).
- **Relay sync**: the phone may omit `state` when nothing but `t` and sub-minute time counters (`usedMs`, `leftMs`, `apps[].ms`)
  changed; it sends a full state at least every 5 min. So on the relay `state.t` can be minutes old: "online" = `seen`, not `t`.
  The worker writes `seen` at most once per minute.
- **Live input**: the worker answers `POST /input` with `409 {"error":"not live"}` when `liveUntil < now`, and drops
  queued inputs older than 5 s when delivering. Frame `seq` starts at `Date.now()` so it stays increasing across restarts.
- **Release** puts back what the phone had before our first apply (saved once in `Store`: auto-time on/off and Private DNS
  mode + host): auto time via `setAutoTimeEnabled` (API 30+), Private DNS host re-set when it was `hostname`, `off` written
  only if WRITE_SECURE_SETTINGS is held (DPM cannot set "off"; otherwise it stays opportunistic). Nothing saved = the old
  behaviour (auto time on, opportunistic).
- **Release** also: removes our accessibility entry, clears paired tokens, unsuspends every launchable app, cancels the
  watchdog, stops Guard; returns `"failed"` if still Device Owner afterwards.
- **HTML pages** (worker `/`, `/install`, phone LAN `/`) send `X-Frame-Options: DENY` and a CSP with `frame-ancestors 'none'`.

## Location history (v0.2.1)
- Rules: `"trackMin": 10` (0 = no history points, allowed 0/5/10/15/30/60; default 10). Independently the Guard refreshes `state.location` (last fix, zone in/out events) every 15 min, also with `trackMin=0`.
- Phone (Guard): every `trackMin` minutes takes one fix (getCurrentLocation API 30+, else requestSingleUpdate,
  timeout 30 s, else lastKnown if < 2× trackMin old). Store a point only if moved ≥ 30 m from the last stored point OR
  ≥ 60 min since the last stored point. Keep 7 days, max 2000 points (drop oldest). Point = `[t, lat, lon, acc]`
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
- Command `sosAck {sos?}` (no `sos` = all; `sos` = one SOS id): removes it. v0.9.3: the SOS id moved from `id` to `sos`,
  because `id` is the command's dedupe key and the relay fills in a random one (so a relay ack never matched anything).
  Result `ok | no such sos`. In the worker whitelist.
- Page: red banner on every tab while `sos` is not empty: time, "Xem vị trí", "Nghe xung quanh" (LAN only), "Đã xử lý" (= `sosAck` without `id`).
- Parent app: an SOS id not seen before and younger than 6 h is notified on channel `kg_sos` (high importance),
  also on the first look at a kid. The background check runs ~every 15 min; relay kids also get FCM (below, seconds).
- **Listen around** (one way, LAN / Tailscale only, the relay has no audio route):
  `GET /api/listen?k=<token>` -> `200 audio/aac` (ADTS, AAC-LC 16 kHz mono 32 kbit/s, ~14 MB/h), no Content-Length,
  streamed until the client closes or 10 min pass (the page can start again). v0.9.6: a new request **replaces** a running
  session (a parent whose Wi-Fi dropped can start again at once; `busy` is no longer answered). Errors: `503 {"error": "no mic permission" | "mic blocked" | "mic failed" | "notice blocked"}` (v0.6: refused when
  the child's notifications or the `kg_listen` channel are off, so listening can never be silent). Each session is logged
  ("Bố mẹ đã nghe âm thanh xung quanh từ HH:mm (…)") and the child screen shows "Lần nghe gần nhất".
- The child is **always** told; it does not depend on `showWatching`: a beep, an ongoing high-importance notification
  "Bố mẹ đang nghe âm thanh xung quanh máy", a status line on the KidGuard screen, and log lines at start and stop.
  Android itself also shows its mic indicator.
- State adds `"listen": {"on": bool, "since": ms, "err": "<last error or empty>"}`.
- v0.9.6: the phone ends a session by itself, with `listen.err`, when the mic gives only amplitude 0 for 2.5 s
  (`"mic silenced"`: a ROM privacy layer such as ColorOS blocks it although RECORD_AUDIO is granted) or no encoded bytes
  are read for 4 s (`"mic stalled"`: also when nobody reads the stream). `on` is cleared by whichever session is current,
  so it never sticks. A partial wakelock is held while a session runs. The page sends `listenStop` when the parent stops.
- The phone gets RECORD_AUDIO through `setPermissionGrantState` (with the location ones) and adds the `microphone` type to
  its foreground service only while listening. `release` revokes RECORD_AUDIO (DENIED, then DEFAULT) and stops a session.
- Parent-app proxy: `GET /k/<t>/api/listen` is passed through as a stream (never cached).

## Instant push via FCM (v0.5, optional, relay kids only)
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

## Telegram alerts (optional, relay kids only)
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
  Sent pkgs are remembered per relay url+id (prefs `relayIcons`), so each icon goes up once.
- `GET /c/<id>/icon?pkg=<pkg>&k=<token>` -> `image/webp`, `cache-control: private, max-age=86400`; 404 if unknown.
  The `?k=` token is accepted only on this GET (an `<img>` can't send headers); every other route needs the header.
- The page uses `${base}/icon?pkg=..&k=..` in all modes (LAN, relay, parent-app proxy `/k/<target>/...`).

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
  `X-KG-Cached: <epoch ms stored>`.
- **History**: one file per kid per day (`files/history/<sha256(target+path)>/<yyyy-mm-dd>.json`, last snapshot of the
  day). Retention is a setting (default unlimited; 30 / 90 / 365). Storage guard: before writing, if free space < 200 MB
  the oldest days (all kids) are deleted until >= 200 MB or only 7 days remain.
  - `GET /app/history/days?k=<t>&p=<rest>` -> `["2026-11-06","2026-11-05",...]` newest first (`<rest>` = `api/state` or `c/<id>/state`).
  - `GET /app/history/day?k=<t>&p=<rest>&d=2026-11-05` -> `{"day","t","body"}`, 404 if none.
- **JS bridge** `window.KGApp` (strings): `version()` -> `"1"`; `files()` -> `"true"` (v0.9.8+: the WebView has a file chooser
  (multiple, `ACTION_OPEN_DOCUMENT`) and saves `<a download>` blob/data/same-origin downloads into `Download/KidGuard`; the page checks
  `window.KGApp && KGApp.files && KGApp.files() === "true"`); internal `dlBegin/dlChunk/dlEnd` (used by the app's injected script only); `setKids(json)` json = `[{name,target,via:"lan"|"relay",id,token}]`
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
  `"events": [{"id": "e1a2b3", "t": <ms>, "kind": "zoneIn|zoneOut|battery|newApp|clock|pairing|checkin|launcher", "text": "Đã đến Nhà"}]`,
  newest last, at most 50, older than 7 days dropped. `text` is free text for people, in the child app's language
  (English or Vietnamese, from the phone / per-app language; the examples here are the Vietnamese ones). The same goes for
  log lines (`log[].m`; NEW `log[].k` = kind, the string id without `log_`: `pin_wrong`, `unlock`, `paired`, `tz`,
  `loc_fixed`, `usage_off`, `new_app`, `suspended`, `unsuspended`, `recover_open`, `recover_req`, `zone`, `cmd`, `cleared`,
  `totp_off`, `totp_on`, `release_fail`, `released`, `sos`, `acc_settings`, `acc_rebind`, `acc_reenabled`, `acc_paused`, `acc_resumed`, `view_start`,
  `control`, `proj_denied`, `listen_start`, `listen_done`, `listen_err`, `ring_stopped`, `dns_fallback`, `admin_open`,
  `checkin`, `rules`; absent on older lines, pages show a plain dot) and error strings (`dnsError`, `update.err`, `ts.err`). Code must switch on `kind`, never parse `text`. Kinds:
  - `zoneIn` / `zoneOut`: "Đã đến <zone>" / "Đã rời <zone>" (`zone` field = zone id). First fix after a rules change
    only records where the phone is (no event).
  - `battery`: "Pin còn <n>%" once when it drops to <= 15 % while not charging (re-armed after charging to >= 30 %).
  - `newApp`: "Đã cài <label>" when a new non-system app appears (also `apps[].new`).
  - `pairing` (v0.9.1): the child held "Mở ghép lại" on a paired phone with no PIN. Pairing opens 24 h later (state
    tick), unless a PIN is set before then.
  - `clock`: "Giờ máy bị đổi" on `ACTION_TIME_CHANGED` with a jump > 2 min versus elapsed time. v0.9.3: also "Múi giờ máy bị đổi: <tz>" on `ACTION_TIMEZONE_CHANGED`.
  - `tamper` (v0.9.3): usage access was switched off (daily / app limits can no longer count).
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
  listed (it remembers its own set, like suspend; shell path: `pm disable-user --user 0` / `pm enable --user 0`, at most 20 packages per pass, the loop continues next tick). Unknown / not installed entries are kept and ignored.
  **release** un-hides every app KidGuard hid (owner path: `setApplicationHidden(false)`; shell path: `pm enable`, see below).
- **State** `preinstalled: [{"pkg","label","hidden":bool,"suggest":bool,"mb":int|null}]` (Device Owner, or a working self-adb shell; refreshed at
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
- **Self-adb shell (no Device Owner)**: `setup/SelfAdb.kt`. KidGuard pairs once with the phone's own Wireless debugging
  (same flow as the self-setup, `SelfAdb.pair`: notification with the 6-digit code, pair + connect only, none of the
  Device Owner steps) and then runs `pm` as the adb shell user. `SelfAdb.ready` = a paired key exists and the local adbd
  accepts a connection now (answer cached 30-60 s). With it, `hidden`, `preinstalled` and `clearData` work in Basic mode too
  and `caps.adb` is true. KidGuard records the packages it disabled (`shellDisabled`) and only ever re-enables those.
  Limits: Android 11+; Wireless debugging must be on and the phone on Wi-Fi; after a reboot Wireless debugging is off
  again, so already disabled apps stay disabled and new hide / unhide changes wait (log line "Wireless debugging is off")
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
  and `new`. Wrong codes: 5 free, then 5 min doubling (own counter, not the PIN's; the child-side unlock and the on-device admin check each count their own wrong codes against this secret, so the two paths have separate 5-try budgets; with a PIN set, a wrong admin code counts against the PIN counter); the 5th wrong one sends a `tamper`
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
  "err": string, "checkedAt": epoch ms|0}`. `ready` = basic mode, downloaded and verified, waiting for the child's tap.
  Absent on children older than 0.9 (the page hides its card).
- **Parent app**: once a day the same `HEAD` from `CheckJob`; a newer tag than its own version gives one notification
  per version, the tap opens `releases/download/v<tag>/kidguard-parent-<tag>.apk` in the browser (no install permission).
- **Release assets**: `kidguard.apk`, `kidguard-parent.apk` + their `.sha256` (fixed names: QR provisioning, setup
  scripts and the child updater use `kidguard.apk`/`kidguard.apk.sha256`; never rename them) and versioned copies
  `kidguard-<v>.apk`, `kidguard-parent-<v>.apk` for people downloading by hand (website/README link these).

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
- Caps: LAN 200 MB a file (413 `too big`, checked from Content-Length before reading), relay 10 MB; 507 `no space`
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
- Chunks of 1 MiB (`FILE_CHUNK`), at most 10 MB a file, 8 files / 32 MB per kid, 1 h TTL (dropped on any file call or
  sync), deleted by the receiver right after it has the file. Parent and phone share the token.
- `POST ?fid=<16 hex>&i=<chunk>&n=<chunks>&to=kid|parent&size=<bytes>&name=<enc>` body = chunk -> `{ok, done}`
  (400 wrong chunk length / bad file, 413 > 10 MB, 507 `relay full`). `GET ?to=kid|parent` ->
  `{"files": [{fid, name, size, n, at, done}], "max", "chunk"}`. `GET ?fid&i` -> chunk bytes. `DELETE ?fid`.
- parent -> kid: the page uploads with a fresh `fid`; the sync reply then has `"files": N` (complete files for the kid);
  the phone fetches them (`Files.pull`, own thread), saves each like a LAN upload with id = `fid`, deletes it from the
  relay. The page shows "delivered" once state `files` has that id.
- kid -> parent: command `fileUp {file: <id>}` (outbox files <= 10 MB, else `too big`) makes the phone upload it with
  `fid = id, to = parent`; the page polls `GET ?to=parent` until `done`, downloads the chunks, then `DELETE`s it.
  `fileDel {file: <id>}` = the LAN `DELETE` (deliver-once). Both are in the worker whitelist.
- Parent app: the proxy streams bodies (up to 210 MB request, LAN `GET /api/files/<id>` response streamed with Content-Length) for `/api/files…` and relay `/c/<id>/file` only; other routes keep the 512 KB cap. The WebView offers a file chooser and saves downloads (bridge `KGApp.files()`).

### Release
`release` deletes the outbox and the index; files the kid received stay in `Download/KidGuard` (they are the kid's).
The `WRITE_EXTERNAL_STORAGE` grant (API 26-28) goes back to default with the other grants.

## v0.9.9: kid launcher

Optional home screen on the kid's phone (`HomeActivity`, category HOME). Device Owner only; off by default.

### Rule `launcher` (in `PUT /api/rules`)
`{"on": false, "settings": false, "notes": true, "lockCustom": false, "style": "", "shade": false}`. Missing = all defaults (off).
- `on`: enable the `.HomeActivity` component (manifest default: disabled) + `addPersistentPreferredActivity(HOME)` so Home
  opens it with no chooser. `on:false`, `release` (before `clearDeviceOwnerApp`) and `releaseBasic` clear the preferred
  activity and disable the component; the stock launcher comes back. Not Device Owner ⇒ the rule only enables the component
  (no forced HOME: the user picks KidGuard in Settings) and only once paired; before pairing the child app's local opt-in
  ("Tính năng và quyền" → launcher) decides. See "Features and permissions".
- `settings`: show the system Settings app (hidden by default; always shown on a phone nobody manages: no Device Owner and never paired, where the user picked the launcher themselves). `notes`: show `blockNote` in the "app tạm nghỉ" sheet.
- `lockCustom`: makes the kid's "Tuỳ chỉnh" screen read-only (style, grid, wallpaper, colours, text size) with a note
  "Bố mẹ đã khoá phần tuỳ chỉnh". Arranging (move, folders, widgets, remove) stays allowed. No PIN on the phone. `style`: `""|"be"|"teen"|"gon"`, the preset used only
  when `home.json` is first made (unknown values ⇒ `""` = teen).
- `shade` (v0.9.11): the system quick settings (control center) are closed as soon as they open, in any app (not the
  lock screen); the notification shade itself stays usable. The accessibility service (`Acc`) watches window events and,
  when a tall SystemUI window shows `quick_settings_panel` (the expanded quick settings; the shade alone shows
  `quick_qs_panel`, measured AOSP 14 + ColorOS 8.1) or a window of `miui.systemui.plugin` (HyperOS control center,
  unverified) is up, sends `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` (API 31+, else Back), re-checked 4 × 0.3 s. The
  one-row quick tiles on top of the shade stay tappable. Needs the accessibility service on; without it nothing is
  blocked. Only while the launcher is wanted (`on` + Device Owner, or the unmanaged opt-in); `on:false`, safe mode,
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
Uncaught exceptions while `HomeActivity` is alive are counted (device-protected prefs). 2nd crash within 5 min: the layout is
reset to the preset. 3rd within 5 min: the launcher is turned off for 24 h (`safeUntil`), the stock launcher returns and an
event `{"kind": "launcher", "text": "…đã tắt 24 giờ (HH:mm)"}` is sent. After 24 h the rule applies again. An app update clears the
crash count and the safe mode, and a layout reset by a crash in the last 24 h comes back (`home.json.bad`, if it parses).

### `home.json` (phone only, app files dir)
```
{"v":1, "grid":{"cols":3-5,"rows":4-7,"auto":bool}, "pages":[{"items":[item…]}…], "dock":[{"pkg":"@phone"},{"pkg":pkg|""}×3],
 "style":{"preset":"be|teen|gon","icon":1-5,"labels":bool,"shape":"round|square","dockBg":bool,"dockLabels":bool,"badge":"num|dot|off","face":"sys|light|medium|condensed|serif|easy","speak":false,"calm":false,"solid":false,"accent":"<id>"|"",
          "theme":"light|dark|auto","font":0.85|1.0|1.15|1.3,"wall":{"kind":"builtin|photo","id":"<wall id>","dim":0-60,"blur":0-100},"lib":true,"fx":"auto|full|lite","today":true},
 "seen":[pkg…], "notes":[note…], "todo":[todo…], "timetable":{"1".."7":{"am":[subject…],"pm":[subject…]},"bells":{"am":[[start,end]…],"pm":[…]}}, "todayHide":[card…]}
```
`custom` (optional, top level): per-app rename / icon `{"<pkg>":{"l":"<label, <=40>","i":"g:<lucide glyph>:#RRGGBB"|"e:<emoji>:#RRGGBB"}}` (keys may be `pkg/activity`); missing / unknown fields are ignored. `style.speak` = say an app's name when tapped + "Đọc to" on Hôm nay, `calm` = launcher animations off, `solid` = solid label backing.
`todayHide`: "Hôm nay" cards the kid turned off ("Tuỳ chỉnh thẻ" at the end of that page): `now` (greeting, school period
now / next, countdown to the next lock or bedtime), `time` (screen time left + top 3 apps), `sched`, `chores`, `tomorrow`
(tomorrow's subjects, from 17:00), `notes`, `files` (both ways: opens what parents sent, lists what the kid sent + "Bố mẹ đã tải", "Gửi file cho bố mẹ" = the system picker into the outbox). The SOS / call buttons can't be hidden. Empty schedule / files /
tomorrow cards are left out on their own. `style.today` false = no "Hôm nay" page at all (page 1 is the first; widgets that open it do nothing). Phone only.
KidGuard Home (`kidguard-home.apk`, package `dev.kidguard.home`, v0.9.9+) is the same launcher with no parent link: same layout file / fields, no rules (nothing is blocked), no relay / LAN / commands. It never talks to the worker or the parent page; widgets `time` `quick` `next` `counts` `usage` `lock` `when` `earn` `msg` and Today cards `time` `chores` `files` are not offered there. On a phone where KidGuard is Device Owner and the `launcher` rule is on, KidGuard hides `dev.kidguard.home` (`setApplicationHidden`, data kept) so there is one "KidGuard Home" icon / HOME choice; rule off, safe mode and `release` show it again. Before that, the first time KidGuard's launcher turns on with no `home.json` of its own, it copies KidGuard Home's layout (whole `home.json`: pages, dock, style, notes, to-dos, timetable, Today cards) and its own wallpaper photo through the provider `content://dev.kidguard.home.export` (call `layout` -> `{json}`, file `/wall`; only an app with the same signing key may read it). Other apps' widgets are dropped (bound to that app's widget host). KidGuard Home 0.9.9 has no provider: update it first. A grid that grows a row (auto rows) re-flows only an auto-filled layout (every page but the last full); a hand-made one keeps its cells.
item `{"id","kind":"app|widget|folder|shortcut","x","y","w"?,"h"?,"pkg"?,"cls"?,"type"?,"name"?,"apps"?,"awid"?}`. `shortcut` = an app's pinned shortcut (`pkg` = the app, `cls` = shortcut id, `name` = label), added when an app asks to pin one while the kid launcher is the default home; shown / paused / hidden like its app, dropped with it and on the KidGuard Home import.
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

## Features and permissions
What works with which permission, so the child app and the web page can show it before the heavy setup (Device Owner)
is done. Source of truth: `guard/Features.kt` (pure, `FeaturesTest`). State adds (absent = older app):
- `caps`: `{"owner","admin","acc","usage","location","notify","battery","home","adb","paired","wss"}`, all bool. `wss` = KidGuard holds WRITE_SECURE_SETTINGS (granted once by `pm grant` at setup; it stays after Developer options / debugging are turned off). `home` = KidGuard
  is the default HOME app now; `adb` = the phone can run adb against itself (false until that flow exists); `paired` = `everPaired`.
- `features`: `[{"id","ok":bool,"missing":["acc","admin"]}]` in display order. `ok` = one alternative has every cap;
  otherwise `missing` = the alternative with the fewest missing caps (first on ties).

| id | alternatives (any one) | note |
|---|---|---|
| `launcher` | `home` | works without Device Owner and without pairing (local opt-in, below) |
| `launcherLock` | `owner` | persistent preferred HOME |
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

Child-app screen "Tính năng và quyền" (Thêm sheet) lists these with a button per missing cap.

Local launcher opt-in (phone only, not in rules): `Store.launcherLocal`. Not owner + never paired ⇒ `wanted` = this flag;
paired ⇒ the parent's `launcher` rule; owner ⇒ the rule, with the forced HOME. Only the owner path adds the persistent
preferred activity. The flag is not sent in state; `release` disables the component as before.

Before setup (never paired, no parent PIN, no Authenticator) "Bố mẹ quản lý trên máy này" opens the local page without a
code (`PairPin.adminOpen`); with a PIN or TOTP, or once paired, the code is required as before.
The page skips its PIN gate while `caps.paired` is false (preview) and instead asks for a new parent PIN (twice) on the
first rules save; the PUT carries it as `"pin"`. That PIN then guards the local page and is the PIN the parent enters
when pairing their own phone (`/api/pair`), so it follows the parent from preview to pairing.

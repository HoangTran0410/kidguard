# KidGuard protocol (v0.4)

The child app, the relay worker, the parent page and the installer all follow this file. If you need to change a
shape, say so in your report instead of silently diverging.

## Rules JSON (parent -> phone). New fields in v0.2 marked NEW. Unknown fields are ignored.

```jsonc
{
  "rev": 1793940898493,            // set by the phone / worker, newest wins
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
  "bonus": {"day": "2026-11-06", "min": 30},
  "blockNew": true,                // NEW: apps installed after setup stay blocked (reason "new") until approved
  "approved": ["com.new.game"],    // NEW: approved new apps
  "lockLocation": true,            // NEW (default true): location forced on, child cannot change location settings
  "noAirplane": true,              // NEW (default true): airplane mode disallowed
  "showWatching": true,            // v0.4 (default true): child sees the "watching / controlling" notification
  "safeDns": "",                   // NEW: "" = off, else a Private DNS host, e.g. "family.adguard-dns.com",
                                   //      "family.cloudflare-dns.com", "family-filter-dns.cleanbrowsing.org"
  "noInstall": false, "noDebug": false,
  "pinHash": "…",                  // the page may send "pin": "1234" instead; phone/worker store only the hash
}
```

Block reasons in `decide()` (priority): `blocked` > `appLimit` > `new` > `paused` > `schedule` > `bedtime` > `daily`.

## State JSON (phone -> parent). New fields marked NEW.

Everything in v0.1 stays (`id t day model android version owner usageAccess battery charging screenOn current usedMs
leftMs apps rules location lan relay log`), plus:

```jsonc
{
  "version": "0.2.0",
  "acc": true,                     // NEW: accessibility service connected (live screen / remote control available)
  "accReEnable": true,             // NEW: the app holds WRITE_SECURE_SETTINGS (can re-enable acc itself)
  "locationOn": true,              // NEW: system location switch
  "limitMin": 120,                 // NEW: today's limit in minutes incl. bonus (0 = none)
  "apps": [{"pkg":"…","label":"…","ms":0,"blocked":"new|…|null","suspended":true,"protected":false,
            "new": true}],         // NEW "new": installed after setup and not approved
  "history": [                     // NEW: last 7 days before today, newest first
    {"day": "2026-11-05", "ms": 5400000, "top": [{"pkg":"…","label":"…","ms":1200000}]}   // top: up to 5
  ],
  "ts": {"state": "off|installing|on|error", "ip": "100.x.y.z", "host": "kid-an", "err": ""},  // v0.4: Tailscale (never the key)
  "requests": [                    // NEW: the child asked for more time (button on the child's KidGuard screen)
    {"id": "a1b2c3", "t": 1793940898493, "min": 30, "note": "con làm bài tập"}
  ]
}
```

## Commands (`POST /api/cmd` on LAN, queued via `POST /c/<id>/cmd` on the relay)

v0.1: `lock locate message{text} ring relay{url} openPairing unpairAll release`. NEW:
- `answer {id, ok: bool, min?}`: the parent answered a time request. The page ALSO grants the time itself by
  saving rules (`bonus`), the command only removes the request and tells the child (notification).
- `reboot`: DevicePolicyManager.reboot.
`release` must also undo every v0.2 restriction (location, airplane, private DNS, accessibility allowlist).
The worker's command whitelist must include `answer` and `reboot`.
v0.4:
- While pairing is open the child screen also shows a QR of `http://<ip>:8770/#pair=<code>`; the page prefills the code
  from `#pair=` (6 digits) and drops the hash.
- `openPairing` returns `set PIN first` (and does nothing) when a parent was already paired and no PIN is set.
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
- `tailscaleOff`: undo all of it and uninstall Tailscale (also done by `release`). In the worker whitelist.

## Live screen and remote control

### Acc companion API (Kotlin, in `Acc.kt`)
```kotlin
Acc.isOn(ctx): Boolean
Acc.ensureEnabled(ctx): Boolean          // core calls this every tick
Acc.capture(maxSide = 720, quality = 50): Acc.Frame?   // Frame(jpeg, w, h) w/h = real screen px; null => Acc.lastError
Acc.input(o: JSONObject): String         // "ok" or error
Acc.lastError: String                    // "acc off" | "screen off" | "unsupported" (API < 30) | "busy" | …
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
- `GET /c/<id>/state` already returns `seen` (ms of the phone's last sync): the page warns when `now - seen > 5 min`.
- The worker also serves `GET /install` -> `web/install.html`.

## Setup steps (scripts/setup.sh, scripts/setup.ps1, the web installer and the in-app self-setup `SelfSetup.kt` do the same, in this order)
1. `adb install -r -g kidguard.apk`
2. `adb shell dpm set-device-owner dev.kidguard/.Admin` (verify with `dumpsys device_policy`)
3. `adb shell appops set dev.kidguard GET_USAGE_STATS allow`
4. `adb shell pm grant dev.kidguard android.permission.WRITE_SECURE_SETTINGS`
5. accessibility on: read `settings get secure enabled_accessibility_services`, append
   `dev.kidguard/dev.kidguard.Acc` (`:`-separated, skip if present, treat `null` as empty), write it back, then
   `settings put secure accessibility_enabled 1`
6. `adb shell dumpsys deviceidle whitelist +dev.kidguard`
7. `adb shell am start -n dev.kidguard/.MainActivity`
8. print the LAN URL (`http://<wlan0 ip>:8770`) and tell the parent to enter the 6-digit code.

## v0.2.1 changes (after review)
- **PIN hash never leaves the phone/worker to the parent.** `state.rules` and every worker response carry
  `"pinSet": true|false` instead of `pinHash`. A rules PUT with neither `pin` nor `pinHash` keeps the old hash
  (phone `Api.kt` and worker both). The child's "pair another device" button refuses while no PIN is set.
- **Commands are idempotent.** Relay commands carry `id`; the phone remembers the last 100 executed ids in Store
  (written with `commit()` before running `reboot`/`release`) and skips repeats but still acks them. Each command runs
  in its own try/catch and is acked even if it fails. The worker drops `reboot`, `release`, `unpairAll` from the queue
  as soon as it delivers them (deliver-once).
- **State** adds `dnsError` (`""` or a short reason when the Private DNS host was rejected; the phone then falls back
  to opportunistic DNS and clears the restriction so the child keeps internet) and keeps `usageAccess` (page warns when false).
- **Relay sync**: the phone may omit `state` when nothing but `t` and sub-minute time counters (`usedMs`, `leftMs`, `apps[].ms`)
  changed; it sends a full state at least every 5 min. So on the relay `state.t` can be minutes old: "online" = `seen`, not `t`.
  The worker writes `seen` at most once per minute.
- **Live input**: the worker answers `POST /input` with `409 {"error":"not live"}` when `liveUntil < now`, and drops
  queued inputs older than 5 s when delivering. Frame `seq` starts at `Date.now()` so it stays increasing across restarts.
- **Release** also: removes our accessibility entry, clears paired tokens, unsuspends every launchable app, cancels the
  watchdog, stops Guard; returns `"failed"` if still Device Owner afterwards.
- **HTML pages** (worker `/`, `/install`, phone LAN `/`) send `X-Frame-Options: DENY` and a CSP with `frame-ancestors 'none'`.

## Location history (v0.2.1)
- Rules: `"trackMin": 10` (0 = off, allowed 0/5/10/15/30/60; default 10).
- Phone (Guard): every `trackMin` minutes takes one fix (getCurrentLocation API 30+, else requestSingleUpdate,
  timeout 30 s, else lastKnown if < 2× trackMin old). Store a point only if moved ≥ 30 m from the last stored point OR
  ≥ 60 min since the last stored point. Keep 7 days, max 2000 points (drop oldest). Point = `[t, lat, lon, acc]`
  (t ms, lat/lon 5 decimals, acc m int). Permissions: manifest declares ACCESS_BACKGROUND_LOCATION; as Device Owner
  call `setPermissionGrantState(GRANTED)` for FINE/COARSE/BACKGROUND location.
- State: `"track": [[t,lat,lon,acc], …]` = points since local midnight of YESTERDAY (keeps state small).
- LAN: `GET /api/track?days=7` -> `{"track": [...]}` (all stored points, max 7 days).
- Page "Vị trí" tab: map of the chosen day (Hôm nay / Hôm qua; via LAN also older days) drawn WITHOUT external
  scripts: OSM tiles `https://tile.openstreetmap.org/{z}/{x}/{y}.png` as <img> + an SVG polyline/dots, auto-fit zoom,
  point list with times, tap a dot -> time + accuracy. CSP of HTML pages must allow `img-src 'self' data: blob:
  https://tile.openstreetmap.org`.

## SOS and "listen around" (v0.5)
- **SOS**: the child's KidGuard screen has a red "SOS" button (one confirm). The phone stores `{id, t}` (max 10;
  a second press within 30 s is ignored), logs "Con bấm SOS", takes a location fix (`locate`) and syncs the relay at
  once (and again 15 s later, for the fix). State adds `"sos": [{"id": "a1b2c3", "t": 1793940898493}]`.
- Command `sosAck {id?}` (no `id` = all; `id` = one SOS id): removes it. Do not send `id:"all"`: a command `id` is also its
  dedupe key (phone keeps the last 100), so a second `"all"` would be skipped as a duplicate. Result `ok | no such sos`. In the worker whitelist.
- Page: red banner on every tab while `sos` is not empty: time, "Xem vị trí", "Nghe xung quanh" (LAN only), "Đã xử lý" (= `sosAck` without `id`).
- Parent app: an SOS id not seen before and younger than 6 h is notified on channel `kg_sos` (high importance),
  also on the first look at a kid. The background check runs ~every 15 min; relay kids also get FCM (below, seconds).
- **Listen around** (one way, LAN / Tailscale only, the relay has no audio route):
  `GET /api/listen?k=<token>` -> `200 audio/aac` (ADTS, AAC-LC 16 kHz mono 32 kbit/s, ~14 MB/h), no Content-Length,
  streamed until the client closes or 10 min pass (the page can start again). Errors: `409 {"error":"busy"}` (one
  listener at a time), `503 {"error": "no mic permission" | "mic blocked" | "mic failed" | "notice blocked"}` (v0.6: refused when
  the child's notifications or the `kg_listen` channel are off, so listening can never be silent). Each session is logged
  ("Bố mẹ đã nghe âm thanh xung quanh từ HH:mm (…)") and the child screen shows "Lần nghe gần nhất".
- The child is **always** told; it does not depend on `showWatching`: a beep, an ongoing high-importance notification
  "Bố mẹ đang nghe âm thanh xung quanh máy", a status line on the KidGuard screen, and log lines at start and stop.
  Android itself also shows its mic indicator.
- State adds `"listen": {"on": bool, "since": ms, "err": "<last error or empty>"}`.
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
Shows the same `web/index.html` in a WebView (`scripts/build.sh` -> `build/kidguard-parent.apk`). The page only uses same-origin
URLs, so the app runs a small HTTP server on `127.0.0.1:<port>` (cleartext is allowed app-wide because the proxy calls
the child over plain http; the WebView itself may only reach `127.0.0.1:<port>` and the OSM tiles).
- **Port**: random free port chosen once and stored; reused while free. localStorage is per origin, so if the port
  changes the page loses its kid list: the app mirrors it via `KGApp.setKids` (the page should restore from it).
- **Access**: the WebView first loads `/s/<secret>` (secret random per app start) which sets cookie
  `kgs=<secret>` (HttpOnly, SameSite=Strict) and 302s to `/`. Without the cookie or with a `Host` other than
  `127.0.0.1:<port>`: 403.
- `GET /` -> index.html (same headers as the phone, CSP also `connect-src 'self'`).
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
- **JS bridge** `window.KGApp` (strings): `version()` -> `"1"`; `setKids(json)` json = `[{name,target,via:"lan"|"relay",id,token}]`
  (called whenever the list changes); `getNotify()` / `setNotify("true"|"false")` (default true);
  `getHistoryDays()` -> `"0"` (unlimited) | `"30"` ...; `setHistoryDays(n)`.
- **Background**: JobScheduler job every ~15 min (+ once at app start) fetches each kid's state with its token and
  notifies once per event: new entry in `requests` ("<name> xin thêm <min> phút"; the first look at a kid only records
  existing ones) and lost connection > 30 min ("Mất kết nối với máy của <name>"; LAN: `state.t` old or fetch failing
  for > 30 min, relay: `seen` old). Skipped when the parent phone itself has no network. Tapping opens the app.

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
  `"events": [{"id": "e1a2b3", "t": <ms>, "kind": "zoneIn|zoneOut|battery|newApp|clock", "text": "Đã đến Nhà"}]`,
  newest last, at most 50, older than 7 days dropped. Kinds:
  - `zoneIn` / `zoneOut`: "Đã đến <zone>" / "Đã rời <zone>" (`zone` field = zone id). First fix after a rules change
    only records where the phone is (no event).
  - `battery`: "Pin còn <n>%" once when it drops to <= 15 % while not charging (re-armed after charging to >= 30 %).
  - `newApp`: "Đã cài <label>" when a new non-system app appears (also `apps[].new`).
  - `clock`: "Giờ máy bị đổi" on `ACTION_TIME_CHANGED` with a jump > 2 min versus elapsed time.
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
- **Rules** `hidden: ["pkg", …]` (owner only): preinstalled apps the parent hid. The phone calls
  `setApplicationHidden(true)` on each (skipping the never-hide set) and un-hides what it hid before and is no longer
  listed (it remembers its own set, like suspend). Unknown / not installed entries are kept and ignored.
  **release** un-hides every app KidGuard hid.
- **State** `preinstalled: [{"pkg","label","hidden":bool,"suggest":bool,"mb":int|null}]` (owner only; refreshed at
  most every 10 min): system apps the parent may hide = system / preinstalled packages with a launcher activity, plus
  any in the suggest list that are installed, plus anything currently hidden. Never lists the never-hide set.
  `suggest` = on the curated bloat list (OEM stores, browsers, music / video, game helpers, ads / analytics, preinstalled
  Facebook / Netflix stubs…). Sorted: suggest first, then label.
- **State** `apps[].mb`: app + data + cache size in MB (StorageStatsManager, needs usage access; refreshed hourly), null
  if unknown. Both modes.
- **Never hide / never clear**: KidGuard, `android`, systemui, Settings, phone / dialer / telecom / contacts providers,
  the default launcher and every enabled keyboard, Google Play services / framework, Play Store, WebView, package
  installer / permission controller, `com.android.providers.*`, Bluetooth / NFC / keychain, and `Policy`'s protected apps.
- **Command** `clearData {pkg}` (owner, Android 9+, deliver-once): `DevicePolicyManager.clearApplicationUserData`.
  Result `"ok"` | `"not owner"` | `"unsupported"` (Android < 9) | `"protected"` (never-clear set or KidGuard) | `"failed"`.
- **Basic mode**: `hidden` is ignored and `preinstalled` is absent; the page shows the heaviest apps (`apps[].mb`) and
  says hiding preinstalled apps needs Device Owner.

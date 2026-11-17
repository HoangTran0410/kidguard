<!-- part of docs/flows/ (index: README.md) -->
Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. **Line numbers drift**: grep the function name.

## 2. Parent <-> phone (LAN, relay, parent app)

Paths are relative to the repo root. `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `UI/` = `ui/assets/`, `P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`.
Protocol headings below are `##`/`###` titles in `docs/protocol.md` (line numbers drift; grep the title).

### 2.0 Map of the transports

| Hop | Code |
|---|---|
| Parent page (one JS bundle, `ui/index.html` + `UI/*.js`) | `UI/core.js:129` `base(k)` picks the URL prefix; `UI/core.js:144` `call(k,path,method,body)` = every request (adds `X-KG-Token`, `X-KG-Name`) |
| LAN | phone `K/remote/Http.kt` (raw `ServerSocket`, `:8770`, 4 threads + queue 16, `Http.kt:37`) -> `K/remote/Api.kt:16 handle` -> `route` |
| Relay | worker `relay/src/index.js` (`fetch` :77 -> Durable Object `Kid` :116, one per 16-hex id); phone polls via `K/remote/Relay.kt` |
| Parent app | WebView -> `P/Proxy.kt` on `127.0.0.1:<port>` -> `/k/<b64url target>/<rest>` -> LAN phone or worker. Cookie `kgs=<secret>` + Host check (`Proxy.kt:69-76`) |
| On-device parent page | `ParentSession` cookie instead of a token (`Api.kt:62`); `LOCAL` flag in `UI/core.js:41` |

URL shapes (`UI/core.js:129`): LAN = `<origin|target>/api/<x>`; relay = `<origin>/c/<id>/<x>`; app = `/k/<b64u(target)>` + either.
Same page, same `call()`; the phone routes under `/api/*`, the worker under `/c/<id>/(sync|state|rules|cmd|live|frame|input|icons|icon|push|device|file)` (regex `index.js:96`).
Auth everywhere: header `X-KG-Token` (icon GET + LAN `/listen` + files may use `?k=`; `index.js:50`, `Api.kt:61`).

### 2.1 Pairing

#### 1a. LAN (code + PIN)
- Trigger: child screen shows 6-digit code (open while nobody paired, else 10 min after `openPairing`; `K/guard/Store.kt:91 pairCode`, `:96 openPairing`).
- Page: `UI/core.js:158 boot` -> `GET /api/hello` (`Api.kt:50`: `{id,pairing,pin,pairPin,owner,mode}`) -> `pairScreen` (`core.js:230`). QR from the child carries `#pair=<code>` (`core.js:165`).
- `POST /api/pair {code,pin?,newPin?}` (`core.js:~262`, `Api.kt:52`) -> `Store.tryPair` (`Store.kt:126`): code check -> `PairPin.decide` (no PIN set = this pairing must set `newPin`; PIN set = `checkParentPin`, shared lockout) -> returns random 24-hex token, held in `pending` only.
- Commit is lazy: first authenticated request with that token (`Api.kt:63-67` -> `Store.commitPair` `:153`) stores sha256(token), `everPaired`, the new PIN hash, closes the window, logs `log_paired`, `g.kick()`. Page's `open(k)` -> `/state` is that first request.
- Page stores `{id, token, name, via:"lan", target?}` in `localStorage kg.kids` (`core.js:61 save`; app mirrors via `KGApp.setKids`).
- Scan to add (0.9.17): parent app add-kid screen "Quét QR" -> `KGApp.scanQr()` (Google code scanner, no camera permission) -> `kgScan({text})` (`core.js`). Accepted only: relay link (`addRelayLink`, same parser as paste) or `http://host[:port]/#pair=<6 digits>` (-> `lanHello(target, code)` prefills the code). Else toast. Invite: the relay link box in Settings shows the same link as a QR (`relayLinkHtml`). Photo (0.9.20): "Chọn ảnh" -> `KGApp.scanImg()` -> system photo picker (no storage permission) -> ML Kit `BarcodeScanning` (Play services model) -> same `kgScan`; no QR in the photo -> `{st:"none"}` toast.
- Wire: protocol "Commands" (`/api/pair`, errors 403/429, backoff) and "v0.9.6: PIN at pairing...".
- Gotchas: a closed page before first `/state` leaves nothing paired (intended). Wrong-code guesses: every 3rd makes a new code + 30 s block. Another phone's kid is reached directly (`direct()` `core.js:125`) so the phone needs CORS: `Api.kt:18-35`, `Safety.corsOriginOk`, protocol "Several kids from one browser page (LAN)".
- Parent app: `/api/hello` and `/api/pair` are the only calls allowed to an unknown `http://` target (`P/Logic.kt:52 allowedRequest`).

#### 1b. Relay
- LAN-only step first: Settings tab -> `cmd({type:"relay", url})` (`UI/tab-set.js:287`) -> `POST /api/cmd` -> `Api.kt:87` -> `Guard.run "relay"` (`K/guard/Guard.kt:~441`): saves `relayUrl`, and if none yet makes `relayToken = store.addToken()` (a normal paired-device token, named `relay`) + `relayId = randomHex(8)`; reply `{result,id,token,url}`.
- Page builds link `<url>/#c=<id>&k=<token>&n=<name>` (`tab-set.js:289`). Opening it on the worker origin: `core.js:167-172` stores kid `via:"relay"`, wipes the hash. In the parent app the link is pasted in `addScreen` (`core.js:~222 rGo`, needs `https:`).
- Binding: phone `Relay.loop` (`Relay.kt:38`) posts `/c/<rid>/sync`; first sync with a non-empty `state` binds sha256(token) in the DO (`index.js:129-135`). Before that parent calls get 409 `waiting for the phone`. Later token mismatch = 401.
- Gotchas: relay needs `https://` (`Relay.kt:44`, page check `tab-set.js:286`). The token is in the URL hash (never sent to the worker as a path but lives in browser history/localStorage). `unpair` of the device named `relay` clears relayId/token (`Store.kt:69-73`) so the phone stops syncing.
- Wire: protocol "Relay (worker)..." under "Live screen and remote control" (limits) and "v0.2.1 changes" (sync omit-state). **The `/sync` body/reply itself has no section** (see section 12).

#### 1c. Pair from anywhere (0.9.21: no shared Wi-Fi; `K/remote/RelayPair.kt`, `relay/src/index.js` `pairRoute` + `Watch.pair`)
- Only a phone with no parent (`!everPaired`, not Home). `RelayPair` (thread in `Guard.start`) while the screen is on and `visiblePairCode()` shows the code: `POST /p/new {sh, code}` (the LAN code itself, so one code works both ways; 409 -> `newPairCode`), then polls `GET /p/<code>` every 2-3 s. Nudged by `MainActivity.refresh` when the code appears.
- Parent: add-kid screen (app) / relay home (browser) card "Ghép từ xa" (`core.js anyCard/anyWire`): code + name + new PIN twice -> `POST /p/<code>/join` -> polls `/result` (<= 45 s) -> `{id, token, url}` -> kid `via:"relay"` (`anyDone`, like `addRelayLink`). Needs the kid screen on: timeout text says so.
- Phone decision = `Store.tryPair` (same `PairPin.decide`, lockouts, errors as 1a), the token stays pending; on relay `taken` -> `Store.commitRelayPair` (token + `everPaired` + PIN + relay link in one commit), `g.kick()`. The relay then binds the token at the phone's first sync (2.1 1b).
- Invite (QR): `ui/qr.html` "Ghép luôn với tôi" -> `POST /p/inv` -> id in `PROVISIONING_ADMIN_EXTRAS_BUNDLE {kg_invite}` -> `Admin.onProfileProvisioningComplete` -> `Store.setInvite` -> `RelayPair` claims, same decision, no code. The qr page polls `/result` and saves the kid.
- Gotchas: needs the default relay (`Relay.DEFAULT_URL`; a self-hosted relay phone-side is not used for this first pairing); a phone with the screen off does not offer the code (invite works with screen off); code expires in 15 min on the relay, the phone renews every 10 min.
- Wire: protocol "Pair over relay".

#### 1d. Parents on the relay (2026-11-17: own token per parent; `relay/src/index.js` `Kid.join/mint/pinCheck/parentOp`)
- Worker `handle`: token hash in `par` = a parent (name -> `who`, browser lock 423 after 12 h); = the phone's hash and `par` non-empty -> phone routes only (`phoneRoute`), else 403 `join`.
- Page on 403 `join` (old saved kid / the phone's link): name + family PIN -> `POST join` with the phone's token -> replaces the kid's token. 423 `locked` -> PIN -> `POST unlock`. Invite: Connection card -> `POST invite` -> QR/link `#ri&rc&inv` -> new page asks the name -> `POST join {inv}` with no token.
- PIN check needs the phone's hash: `Relay.kt` sends sync `pv` when `pinHash/pinSalt` change; worker `hashPin` (node:crypto, `nodejs_compat`).
- `by`: worker stamps commands / rules from a parent token; phone `Guard.command` logs `log_cmd_by`, `setRules(by=)` adds ` · <name>` to the rules diff line.
- Gotchas: remote pairing and watch connect mint a parent token too, so the FIRST of them turns the phone's link phone-only; the parent app's native jobs (CheckJob, widget, push) use the token the page hands them via `setKids`, so they follow the page's migration.
- Wire: protocol "Parents".

### 2.2 Open a kid + state refresh
- `open(k)` `UI/kid.js:7` -> `refresh()` `kid.js:9` every few seconds (timer set in `open`; skipped when `document.hidden`).
- LAN: `GET /api/state` -> `Api.kt:71`: `g.currentState()` (the guard loop's cached `lastState`, `Guard.kt:408`) + `devices` list with caller's `self` row.
- Relay: `GET /c/<id>/state` -> `index.js:262`: `{state (rules PIN-stripped), rules (pending, not yet taken), seen, pending: n}`. Page: `kid.js:16-19` splits the two shapes; `lastSeen()` `kid.js:6` uses `seen` as "online" (state `t` can be minutes old).
- App mode: proxy caches 200 JSON state per target+path, writes a snapshot every >=10 s, returns it with `X-KG-Cached` on network error/5xx (`Proxy.kt:96-118`, `Logic.isStatePath`); `call()` then blocks writes (`core.js:146-147`).
- `edit` (rules object) is reloaded from the newer of `st.rules` / `pendingRules` unless mid-edit/saving (`kid.js:31-36`). Redraw only if signature `sig` changed (`kid.js:38-42`): **a new state field that should repaint must be added to `sig`**.
- Not-paired (401) -> kid forgotten, back to `boot` (`kid.js:26`).
- Wire: protocol "State JSON (phone -> parent)" + per-feature state sections.

### 2.3 Save rules
- UI edits mutate `edit`, call `changed()` (`kid.js:55`, 500 ms debounce) -> `putRules(k,e)` (`kid.js:44`): drops `pinHash/pinSet`, zone filter, `PUT <base>/rules`.
- First save on a never-paired preview phone forces a PIN prompt (`kid.js:46-53`).
- LAN: `Api.kt:77`: `Rules.fromJson(body)` (`K/guard/Rules.kt:228`) builds the WHOLE rules object (missing field = default), keeps old PIN if neither `pin` nor `pinHash` sent, `rev = max(rev+1, now)`, `g.setRules` (`Guard.kt:410`): ignores older rev, persists, `Policy.apply` on the work thread, logs `Events.ruleDiff`, `kick()`. Reply = new rules (+`pinSet`, no hash); page replaces `edit` with it (`kid.js:66`).
- Relay: `index.js:267`: stores raw body as `rules` (<=64 KB), assigns `rev`, hashes `pin` to `sha256("kg:"+pin)`, tracks `pinAt`. Page sets `e.rev = r.rev` (`kid.js:67`). Phone gets it in the next sync reply `rules` iff `rules.rev > state.rules.rev` (`index.js:258-259`); `Relay.sync` (`Relay.kt:102-110`) takes it if newer than its own rev; PIN hash only if `pinAt` newer than last taken (`store.relayPinAt`).
- Latency: relay = next poll (20 s screen on, 60 s off; 3 s right after a command; `Relay.kt:57`). Page toast says "sent" not "saved" (`kid.js:60`).
- Wire: protocol "Rules JSON" + feature sections (v0.6/v0.9.4/v0.9.7/v0.9.9 `launcher`...), "Remote phone settings" for `pinAt` (l.656).
- Gotchas: `fromJson` is the allow-list: a field the page sends but `fromJson` ignores is silently lost on LAN but still sits in the worker's stored `rules`. `norm()` (`core.js:103`) gives defaults for older phones. Templates only touch `TPL_F` fields (`kid.js:~170`).

### 2.4 Send a command
- Page: `cmd(c, okMsg, q)` `kid.js:109`: confirm via `ask()` (text keys `ask.<type>`/`askOk.<type>`; `ASK_BAD` = reboot/unpairAll/tailscaleOff), then `POST <base>/cmd`. Result handling: `"ok"|"queued"|"no such sos"` OK, any other string = toast `cr.<type>.<result>`.
- LAN: `Api.kt:85` -> `Guard.command` (`Guard.kt:424`) synchronous; reply `{result}`. `Guard.run` (`Guard.kt:~437-478`) is the command table.
- Relay: `index.js:292`: whitelist `CMDS` (:58), extra validation for `clearData` pkg and `setting` key/value, queue max 20 (`queue full` 429), <=4 KB, id = body.id if it matches `^[\w.-]{1,64}$` else UUID, `at=now`. Reply `{result:"queued"}`; page warns "phone offline" if `now - lastSeen > 120 s` (`kid.js:116`).
- Delivery: `sync` reply `cmds` = all non-acked, <24 h old. `ONCE` list (`relay/src/index.js` `ONCE`: destructive commands) is handed out until acked but only within `ONCE_WINDOW` (1 h) of the first hand-out; others stay until the phone's next sync carries `acks`. `SHORT` commands ("now" actions) go stale after `SHORT_TTL` (10 min), the rest after 24 h.
- Phone: `Relay.kt:111-117`: `Guard.command` for each (try/catch, ack always), `markCmd(id)` (`Store.kt:298`, commit(), last 100 ids) skips repeats but answers "ok". Result of a relay command is not returned to the page: it shows up in state/`log` (`Command: x -> res`).
- LAN-only on purpose: `relay`, `tailscale` (secret key must not sit in the queue). Contract test enforces whitelist parity: `relay/test/contract.test.mjs`.
- Wire: protocol "Commands" (+ "Action states", "Remote phone settings", "Self-update", "Shared files").
- Gotchas:
  - Non-`SHORT` commands live up to 24 h; only the time-critical ones (`lock ring wake message reboot pin*` ...) are `SHORT` (10 min; `setting`/`devOff` are 24 h since 2026-11-15), so they no longer fire the next day.
  - ONCE commands are re-delivered until acked (within `ONCE_WINDOW`); `Relay.sync` applies `rules` inside its own `runCatching`, so a bad rules reply cannot stop a delivered `release`.
  - `markCmd` strips `.` from ids; for `unpair`/`answer` the worker reuses the body's `id` (device/request id) as command id, so a re-sent `answer` for the same request id is deduped ("ok") even if the first failed.
  - LAN commands carry no id unless the page sets one (`setting` does, `tab-set.js:204`).

- Family actions (`UI/overview.js` `ovRun`/`ovCmd`/`ovEach`, 0.9.21): from the Family overview (>=2 kids) one confirm, then per kid in parallel over its own transport: `lock`/`ring`/`message` = `POST /cmd` (relay: own id, answer "queued" shows "sent"); `+15 min` (`grantApply`), template (`tplFields`, only the template's fields) and pause/resume = fresh state read, change, PUT the WHOLE rules (`ovEach` passes rules + state). Result card lists one line per kid (done / sent / failed: why). No receipts (`rc`) are tracked there: only the opened kid's page does that.

### 2.5 Screen view / remote input
- Tab open: `screenStart` `UI/tab-screen.js:~25-65`.
- LAN: loop `GET /api/screen?max=720&q=50` (`Api.kt:97` -> `Acc.capture` `Acc.kt:315`) ~7 fps cap; JPEG + `X-KG-W/H`; errors 503 `{error: Acc.lastError}` (`acc off|screen off|busy|timeout|need consent|denied`); page backs off on `timeout` x3 (`tab-screen.js:41`).
- Relay: page `POST /live {}` every 10 s (`tab-screen.js:49`, sets `liveUntil=now+20s` `index.js:215`) and long-polls `GET /frame?after=<seq>` (waits up to 5 s, `index.js:169-189`; also extends `liveUntil`). Phone: sync reply has `live:true` -> `Relay.startLive` (`Relay.kt:156`) -> `liveLoop` (`:164`): capture, `POST /frame` (JPEG or empty + `X-KG-Err`), reply `{inputs, live}`; runs inputs; stops when `live` false, 3 errors, or 10 min. Frames only in DO memory.
- Capture: API 30+ `AccessibilityService.takeScreenshot` (`Acc.kt:335 shoot`), API 26-29 MediaProjection (`remote/Proj.kt`, consent dialog `ProjActivity`, FGS `ProjService`); gap `Acc.frameGapMs` 333 / 1000 (`Acc.kt:312`, `LiveLogic.kt:13`). `noteViewing` shows the "watching" notice (rule `showWatching`; projection notice always).
- Input: `sendInput` `tab-screen.js:~71`: `POST <base>/input {type: tap|long|swipe|key|text, x,y 0..1}`. LAN: `Api.kt:102` -> `Acc.input` (`Acc.kt:406`) sync. Relay: worker validates (`index.js:220-235`), `409 not live` when `liveUntil<now` (page re-pings `/live` and retries `tab-screen.js:77`), queue max 50, delivered in the next `/frame` reply, dropped if >5 s old.
- "Screen off": `wake` command (`remote/Wake.kt`) via `cmd({type:"wake"})` (`tab-screen.js:157`). Relay `wake` waits for a poll (<=60 s asleep).
- Wire: protocol "Live screen and remote control" (Acc API, Input object, LAN, Relay, API levels).
- Gotchas: relay quality fixed at 720/50; screen tab is never redrawn by `refresh` (`kid.js:40`); a hidden page stops polling (`visibilitychange`).

### 2.6 Listen, ring, locate
- Listen over the relay (after 0.9.27): `earStart` (`UI/live.js`) -> `earStartRtc` (`UI/call.js`, end of file): `GET /call?ice=1`, `RTC.open({listen:true})` (recvonly audio, no mic prompt), `POST /call {op:offer, kind:"listen"}`, long-poll `earRtcWait` until `on` (answer SDP) or `end {why}` (`earRtcEnd` -> toast `ear.<why>`). Worker `Kid.callRoute` stores `kind`; sync reply `call.kind` -> `Call.fromRelay` branches to `K/remote/ListenCall.kt`: no ring/tap; `Listen.openRtc` (same checks, beep, notification, wakelock, FGS mic, log) then a headless WebView `ui/listen.html` (mic only, `sendonly`) answers via the `KG` bridge (`Call.post`). Stop: page `earStop` -> `POST end` -> sync `end` -> `ListenCall.stop`; `cmd listenStop` / release -> `Listen.stopAll` -> `ListenCall.stop`. 10-min cap on page, phone and worker. Gotchas: mic permission is asked of the WebView only while `Listen.active`; busy with a video call; E2E of the audio path `scripts/e2e-listen.mjs` (Chrome, fake mic).
- Listen (LAN/Tailscale): `earStart` `UI/live.js` (kid reached by LAN) sets `<audio>` / MediaSource to `GET /api/listen?k=<token>` -> `Api.kt:108` -> `Listen.open` (`remote/Listen.kt:107`): conflict check vs ring, RECORD_AUDIO, notice-visible check, FGS mic, beep + ongoing notification (always), MediaRecorder AAC_ADTS into a pipe; `Session.pump` streams; watchdog ends on silence/stall/10 min. Reply is a `stream` response (`Http.kt:83-88`; pool thread held for the whole session). Stop: page closes player + `cmd listenStop` (`live.js:241-248, 333`), `Listen.stopAll`. App mode: `Proxy.listen` passthrough (`Proxy.kt:156`).
- Ring: `cmd ring` (`tab-home.js:58`) -> `Ring.start` (`remote/Ring.kt:38`): 20 s alarm at max alarm volume, heads-up notice with stop; refuses `listening`. `ringStop` is idempotent (`live.js:324`). Active state is read from `state.ring` / `state.listen`, never guessed (`live.js` actPaint).
- Locate: `cmd locate` (`tab-home.js:59`, `tab-where.js:256`) -> `Guard.locate()` (`Guard.kt:~482`): last-known fixes + one fresh fix (60 s), writes `store.location`, zone events, `relay?.kick()`. Result appears in `state.location`; track via `GET /api/track?days=` (LAN, `Api.kt:105`; relay has no track route: history rides in state).
- Wire: protocol "Action states (v0.9.6)", "SOS and listen around (v0.5)", "Location history (v0.2.1)".
- Gotchas: relay `ring`/`locate` have no immediate result; relay `listen` is a WebRTC session (above), not a command.

### 2.7 Shared files (both ways)
- Phone side: `remote/Files.kt` (+`FilesLogic.kt`, `ShareInActivity.kt`); index in prefs `kgfiles`; state `files` (newest 30).
- Parent -> kid LAN: `PUT /api/files/<name>` streamed (`Http.kt:123-127` skips the 512 KB cap, `Req.input`), `Files.api` (`Files.kt:78`) -> `receive` (`:128`) -> Download/KidGuard + notification.
- Parent -> kid relay: page chunks (1 MiB) `POST /c/<id>/file?fid&i&n&to=kid&size&name` (`index.js:322 file`; 8 files/32 MB, TTL 1 h); sync reply `files:N` (`index.js:254-255`) -> `Relay.kt:118` -> `Files.pull` (`Files.kt:279`) fetches, `receive` with id=fid, then `DELETE`.
- Kid -> parent LAN: `GET /api/files/<id>` (always octet-stream attachment, `Files.kt:98`); marks `fetched` on a full download.
- Kid -> parent relay: page `cmd fileUp {file:id}` (`tab-files.js:98`) -> `Files.command` (`Files.kt:239`) spawns `push` (chunks `to=parent`); page polls `GET /file?to=parent` every 3 s (<=5 min), downloads chunks, `DELETE`s (`tab-files.js:104-120`). Delete: LAN `DELETE /api/files/<id>`, relay `cmd fileDel` (ONCE).
- Parent app: `Proxy.filesForward` (`Proxy.kt:121`) streams bodies (<=210 MB) only for `/api/files…` and `/c/<id>/file` (`Logic.isFilesPath`, `Proxy.kt:288`).
- Wire: protocol "Shared files (v0.9.8)". `release` wipes outbox + index (`Guard.kt:~512`).
- Gotchas: relay upload of a >10 MB file is blocked in the page (`FX.relay`). A LAN download/upload occupies one of the 4 HTTP pool threads.

### 2.8 Self-update
- Trigger: rule `autoUpdate` (daily, `Update.maybe` `remote/Update.kt:78` from `Guard.kt:213`) or `cmd update` (ONCE) from `tab-set.js:317` -> `Guard.run "update"` -> `Update.start(force=true)` (`Update.kt:86`).
- Steps (`Update.kt:97 run`): `HEAD github.com/HoangTran0410/kidguard/releases/latest` (no redirect) -> tag; `newer()`; download `kidguard.apk` + `.sha256`, hash check; `verifyPackage` (same package, higher versionCode; bad tag remembered); Device Owner = silent PackageInstaller session, else notify child ("ready").
- The automatic (daily) silent install waits for the screen to be off (`UpdateLogic.installNow`, re-checked every Guard minute pass, 24 h cap): it replaces the process, i.e. the kid's launcher. `cmd update` never waits.
- State `update {latest,status,err,checkedAt}` -> page card. Release assets must keep fixed names (`kidguard.apk`, `kidguard-parent.apk` + `.sha256`).
- Wire: protocol "Self-update (v0.9)". Parent app has its own: `P/CheckJob.kt` daily HEAD + `P/Updater.kt` (download + system installer), bridge `KGApp.checkUpdate/installUpdate`.
- Gotchas: `kg.code` in `android/gradle.properties` must rise every release. Home flavor has no INTERNET/Guard so it never self-updates (manifest `home/AndroidManifest.xml`); not covered by this path.

### 2.9 Page/asset serving (needed for any UI change)
- Phone: `Api.kt:39-45` serves `index.html` and `Safety.uiAsset` (`K/guard/Rules.kt:414`, regex `[a-z0-9-]+.(js|css)` -> `assets/<n>`); parent app: `P/Logic.uiAsset` (same rule) in `Proxy.handle`; worker: explicit `UI` map (`index.js:25-42`).
- New `ui/assets/x.js`: add `<script defer>` in `ui/index.html` (order matters; shared globals), import + map entry in `relay/src/index.js`; worker test `relay/test/relay.test.mjs:16` fetches every `/ui/` the page names. wrangler text rule already globs `ui/assets/*.js|css`.

### 2.9b UI pack (2026-11-17: the page updates without an app update)
- Trigger: page opened (index served, 60 s debounce; phone: rule `autoUpdate`), every 6 h (Guard tick `opt("uiPack")`, parent `CheckJob.run`), parent app `onResume`, command `uiUpdate` (`Guard.run`, `UiServe.hub().checkAsync(0)`) / `KGApp.uiUpdate()`, dev `POST /ui/push`.
- Steps (`UiPackStore.check`, `android/shared/UiPack.kt`): GET `manifest.json` (`If-None-Match`, 304 = done) -> `manifest.sig` -> verify signature + `UiPack.wanted` (seq, floor, bad list, minKid/minParent) -> GET `pack-<ver>.zip` -> `install` (verify again: signature, names, every sha256) -> temp dir -> rename `cur/` (old `cur` -> `prev/`). Served by `UiServe.res` (phone, gzip + ETag = pack ver) and `Proxy.index/ui` (parent app) via `UiPackHub.file()`; no pack = the APK's assets.
- State: `<filesDir>/uipack/{cur,prev,meta.json}` (phone: device-protected). `/ui/pack.json` (src, ver, seq, checking, result) -> page row (`uiPackHtml()` in `app-set.js`: Settings > Update card on the phone page, "App settings" in the parent app) with "Update the page" (`uiNow()`: `KGApp.uiUpdate` or cmd `uiUpdate`, polls pack.json, reloads when `ver` changed) and a "A new page is ready: Reload" bar (`uiNewBar`, never reloads by itself).
- Safety: page reports `/ui/ok?v=` after `boot()`; 3 loads without it = pack blacklisted, `prev/` back. Details and critique: `docs/decisions/page.md`, wire: protocol "UI pack".
- Gotchas: `scripts/ui-pack.json` `minKid`/`minParent` must be raised by hand when the page needs a newer native side; new `ui/assets` file = still the 3 places (index.html, relay map, and the pack picks it up by name); the phone's call screen uses bundled `call.html`/`rtc.js`; keys live in `android/app/ui-pack-key.pem` (+ backup), never in git.

### 2.10 Parent app specifics (P/)
- `MainActivity.kt` loads `/s/<secret>`; JS bridge `KGApp` (protocol "Parent app"); `Store.kt` holds kid list; `CheckJob.kt` ~15 min job fetches each kid's state, notifies on new `requests`, `sos`, lost contact; `Act.kt` notification actions replay the page's flows natively (GET state, PUT rules `bonus`, `answer`); `Push.kt`/`Notif.kt` FCM data messages from the worker (`pushNew` `index.js:366`, `POST /c/<id>/push` registers token, optional secret `FCM_SA`).
- Widget (0.9.22, `P/KidWidget.kt` + `WidgetLogic.kt`): home-screen rows per kid (max 3): name, time left / Locked / Paused / Bedtime (`now.why`, `limitMin`, `leftMs`), "seen N min ago", buttons Lock + "+15 min". Refresh: `CheckJob.runKid` -> `KidWidget.save` (snapshot per kid hash in `Store.widgetData`, no tokens) + `KidWidget.update`; with alerts off `run` calls `refreshAll` only when a widget exists. Press: `ACT_LOCK` / `ACT_PLUS` broadcast -> `goAsync` thread -> `lock` cmd, or GET state + `Logic.grantRules` + PUT the whole rules (same as notification "grant"); LAN kid falls back to its relay. Feedback in the seen line 6 s (`Store.widgetFb`), then the kid is re-fetched. Gotchas: numbers are as old as the last check (~15 min, relay kid: as old as the phone's last sync); a relay +15 is "Sent" = queued, applies when the phone syncs; kid with no `leftMs` (older phone) shows "No data yet".
- Proxy forwards only `X-KG-Token, X-KG-W, X-KG-H, Content-Type` (`Proxy.kt:100`).
- Evening summary (`android/shared/Digest.kt`, protocol "Evening summary"): trigger = `Tg.digestTick` (every Guard tick, per chat with `/digest on`, after its time, once a day) or `CheckJob.digest` (parent app setting card, `KGApp.getDigest/setDigest`, after its time, once a day per kid). Steps: state (`Guard.currentState()` on the phone, the fetched state in the parent app) -> `Digest.fromState` -> `daily` (+ `weekly` on Sunday) -> Telegram `sendMessage` (silent) / `Notif.postDigest`. State: counters `Store.dgBump` (asked in `addRequest`, answered in the `answer` command, blocked in `log("suspended")`, tamper in `addEvent`), `state.digest`. Gotchas: a missed evening is not made up next day; parent app only sees a kid when the fetch works.

### 2.11 Guided setup wizard (0.9.17, `UI/wizard.js`)
- Trigger: first load of a freshly paired kid (`wizAuto(k)` from `kid.js`); reopen from Settings "Thiết lập lại từ đầu" (`wizOpen`).
- Steps: 4 steps (level, top apps allow / normal / block, bedtime + daily limit, what the kid sees) on a COPY of the rules fields; one save at Done (`putRules`, whole object). Step kept per kid in localStorage.
- Gotchas: Escape / close before Done saves nothing; replaces the old `levelsOffer` dialog; keep `wizard.js` in `index.html` + `relay/src/index.js` map.

### 2.12 Scan QR to add a kid / invite a parent (0.9.17, parent app only)
- Trigger: "Scan QR" on add-kid (`core.js` `#scanGo`) -> bridge `KGApp.scanQr()` -> `P/MainActivity.scanQr` (Google code scanner, no CAMERA permission) -> `window.kgScan({text} | {st})`.
- Steps: text goes through `addRelayLink` (relay link) or the kid QR `http://host[:port]/#pair=<6 digits>`; anything else is ignored, never opened as a URL. Settings shows an invite QR (relay link) for the other parent.
- Gotchas: plain browsers have no scan button; no Play services = toast + manual entry.

### 2.13 Connect a Zepp watch (after 0.9.18, `watch/zepp/`, `relay/src/index.js` `watchRoute` + `Watch`)
- Trigger: watch with no kid (or "Thêm con") opens `page/pair` (`watch/zepp/page/pair.js`): big 6-digit code, time left, "Xem thử" = demo phone only when tapped.
- Steps: watch -> `PAIR_NEW` to the side service (`watch/zepp/app-side/index.js`, runs in the Zepp app) -> `POST <relay>/w/new {sh=sha256(secret)}` -> `{code, exp}` (DO `w:<code>`, 10 min). Side service polls `PAIR_POLL` ~3 s -> `GET /w/<code>` + `X-KG-Secret`. Parent page: Settings > Watch (`UI/tab-set.js` `watchCard/watchGo`) -> `POST /c/<id>/watch {code,name}` with the kid's normal relay auth (`index.js` Kid.handle `watch`; LAN kids go through `viaRelay` with `k.relay`) -> relay attaches `{url, id, token, name}` to the code. Next poll returns it once and deletes the entry; side service appends it to settingsStorage `kids`; watch shows name + "Đã kết nối" and goes home.
- Token: the relay keeps ONE token per phone (sha256 bound by the phone's first sync; only the phone can mint paired-device tokens, over LAN), so the watch gets the same token as the `/go` link. No separate "Zepp watch" device in "paired devices": removing it = renew the relay link (`relayRenew`), which also logs out the other parents.
- Gotchas: relay URL before any kid = `DEFAULT_RELAY` (or settingsStorage `relay`, settings page). The code is only a rendezvous: whoever has the kid token can attach it, whoever has the secret can fetch it. `/w/new` is capped per network (Gate `wip:`, 30/day). Expired codes: lazy check + alarm.
- Wire: protocol "Watch connect".

### 2.15 Video call (after 0.9.27, relay)
Page `UI/call.js` `vcStart` (More sheet -> "Video call"; needs a relay link and a secure page) -> `GET /call?ice=1` ->
`RTC.open` (`UI/rtc.js`: camera+mic, offer with the low-bandwidth profile, waits for ICE gathering up to 5 s) -> `POST /call
{op:offer}` -> long-poll `GET /call?id&st=ring` (`vcWait`). Worker `Kid.callRoute` stores `this.call`, kicks the phone
(socket / FCM). Phone `Relay.sync` reply `call` -> `K/remote/Call.kt` `Call.fromRelay`: grant camera+mic (DO
`setPermissionGrantState`, basic `AutoGrant.camNow` via self adb), `Wake.now`, start `CallActivity` + full-screen
notification `kg_call`, ringtone + vibration, 45 s missed timer. `CallActivity` = WebView of `ui/call.html` served from
the APK under `https://appassets.androidplatform.net` (`shouldInterceptRequest`, only `call.html` + `assets/rtc.js`);
`onPermissionRequest` grants camera/mic only for that origin and only after Answer. Answer -> bridge `KG.answerTap`
(ringing off) -> `RTC.open({offer})` -> `KG.signal("answer", sdp)` (blocking POST with the phone token) -> the page's long
poll returns `on` -> `accept`. Ends: either side's button (DataChannel `bye` + `POST end`), the parent's `end` reaching the
phone through sync (`Call.remoteEnd` -> `kgEnd(why)`), `CallActivity.onStop` (kid left the screen), page hidden 60 s,
15-min cap. Parent app: `P/MainActivity` `onPermissionRequest` asks Android for CAMERA/RECORD_AUDIO for the 127.0.0.1 page.
Gotchas: a LAN http page can't open the camera (`RTC.can()` false -> "use the relay link / parent app"); non-trickle ICE
means one round trip but up to 5 s of gathering; release already revokes CAMERA/RECORD_AUDIO.

### 2.14 Parent page components (use these, never hand-write the markup)
One helper per UI shape; ids, `data-*` attributes and class names stay the same (`shoot.mjs`, `check-search.mjs` and the click handlers rely on them).
| Need | Helper | File |
|---|---|---|
| Day / date picker (calendar popover, counts, prev/next) | `dayBtn(id, {sel, onPick, days \| min+max, counts, nav})` + `dpBind(root)`; never `<input type=date>` | `UI/tab-where.js` |
| Empty state (icon tile + text) | `emptyBox(icon, html)` | `UI/core.js` |
| Banner `.alert` (warn / bad / ok / info) | `alertBox(kind, icon, html, style?)` | `UI/core.js` |
| Status pill `.tag` | `tagEl(kind, icon, html, style?)` | `UI/core.js` |
| Toggle chips (filters, weekdays, minutes, mode) | `chipRow([chipBtn('data-x="v"', label, on), ...], {scroll, id, style, aria})` | `UI/core.js` |
| Tappable list row (icon tile, title, sub, chevron) | `navRow('id="x"' or 'data-go="x"', icon, title, sub?)` inside `<div class="list">` | `UI/core.js` |
| Bottom sheet / dialog | markup `<div id=".." class="sheet">` + panel `.dp` (one CSS block in `style.css`); open through `ask()` / `dayBtn` | `UI/requests.js` (sheet), `UI/kid.js` |
| Date + time text | `dtFull(ms)`, `hhmm(ms)`, `evAt(ms)` (clock time, "Hôm qua 16:19"), `ago(ms)`, `fmt(ms)` (minutes), `modeEnd(ts)`, `dm("YYYY-MM-DD")` | `UI/core.js`, `UI/kid.js` |
| Settings section | `sec(id, icon, title, desc, chip, body)` (+ a `QS` entry, see `check-search.mjs`) | `UI/tab-home.js` |
Not shared on purpose: `<input type=time>` (native clock is fine, 7 places), rich `.li` rows with a progress bar or inline buttons (tab-home Hôm nay lists).

### 2.15 Family chat (2026-11-17; `guard/ChatLogic.kt`, `Store.chat/addChat`, `ui/ChatActivity.kt`, page `UI/requests.js` `msgSheet`)
- Parent sends from the "Nhắn" tile (sheet = thread + input + saved quick messages): `cmd({type:"message"})` (2.4). Phone `Guard.run "message"`: `ChatLogic.clean`, `store.addChat("p", by, text)`, then the full-screen `MessageActivity` as before (now with a "Trả lời" button -> `ChatActivity`).
- Child writes in `ChatActivity` (tile "Nhắn bố mẹ", in the default tiles; needs `everPaired`): `store.addChat("k", ...)` also adds the event `chat` (so the relay `pushNew` FCM and the parent app notification work unchanged) and `Guard.running.kick()` syncs state.
- Parent reads `state.chat` (last 30; the phone keeps 100) over LAN and relay alike; the sheet polls `refresh()` every 4 s while open. Own sends show at once with "sent, waiting for the phone" until the thread echoes them (10 min cap).
- Gotchas: relay delivery of the parent's message is the 10-min `SHORT` queue, so a phone that is off for longer loses it; the page shows "sent" not "delivered"; Telegram `/say` joins the thread with no name; `release` does not clear the thread; Home flavor has no chat.
- Wire: protocol "Family chat".

<!-- part of docs/flows/ (index: README.md) -->
Path shorthands (`K/`, `L/`, `UI/`, `P/`): see `README.md`. No line numbers: grep the function name.

## 1. Enforcement (child app, `K/guard/`)

Paths relative to `android/app/src/main/kotlin/dev/kidguard/`.
Prefs: one SharedPreferences file `kg` in device-protected storage (`guard/Store.kt`). Every key below is in it.

### 1.0 Map

- `guard/Guard.kt` foreground Service `Guard`: 10 s loop `tick()` (TICK_MS), `kick()` = tick now, `pokeSoft()` = debounced 300 ms kick.
- `guard/Rules.kt` pure: `Rules` data + JSON, `decide()`, `dailyLeftMs()`, `Schedule`/`AppGroup`/`Totp`/`Track`/`Safety`.
- `guard/Policy.kt` Device Owner (DPM) calls: `apply`, `suspendExactly`, `hideExactly`, `release`, `releaseBasic`.
- `guard/Usage.kt` UsageEvents -> ms per package today. `guard/Watchdog.kt` 15 min alarm. `guard/Store.kt` all persistence + PIN/TOTP/pairing.
- `guard/Basic.kt`, `BasicLogic.kt` basic mode (no Device Owner). `Comfort.kt` (WindDown/Gray/Notes/Checkin/ReqTpl, pure), `GrayFx.kt` its Android side, `Warn.kt` heads-ups, `Sys.kt`/`SysLogic.kt` remote phone settings, `DevOpts.kt` dev options.
- Callers outside guard/: `remote/Api.kt` (LAN), `remote/Relay.kt` (relay), `remote/Acc.kt` (accessibility), `ui/MainActivity.kt` (child screen), `launcher/HomeActivity.kt` (reads `Guard.snap`).

Monitor only (`rules.monitor`): Guard.tick computes `would = decide(...)` then `want = enforced(r, would)` (Rules.kt); only `want` reaches suspendExactly (empty => unsuspends ours), Acc/bounce, `snap` (launcher) and the tile; `would` goes to `state.wouldBlock` + `logWould`. Warn.due is skipped.

### 1.1 Rules update applied

Trigger: parent saves rules (LAN `PUT /api/rules`) or relay sync returns newer rules.
- LAN: `remote/Api.kt` parse `Rules.fromJson` (Rules.kt); if body has neither `pin` nor `pinHash` copy old pinHash+salt; `rev = max(old.rev+1, now)` -> `Guard.setRules` (Guard.kt). Reply = rules minus pinHash.
- Relay: `remote/Relay.kt` parse `res.rules`; keep relay's pinHash only if `pinAt > store.relayPinAt` else blank it; apply only if `rules.rev > store.rules.rev` (strict).
- `Guard.setRules` (Guard.kt): drop if `incoming.rev < old.rev` -> blank pinHash => keep old PIN -> `store.rules = r` (Store.kt, `toJson(persist=true)`, async `apply()`) -> `work.post { Policy.apply }` -> if `noDebug` newly on: `devOff()` -> log a diff line via `Events.ruleDiff` -> zones changed => `zoneBaseline` -> `kick()`.
- `Store.rules` getter caches parsed Rules per raw string (Store.kt). Any reader (Guard, launcher, UI) sees new rules immediately.
- State: pref `rules` (JSON incl. pinHash/pinSalt). `rev` = ms of last edit.
- Gotchas:
  - `fromJson` is total replace: any field missing from the PUT resets to default (e.g. missing `hidden` => everything un-hidden next tick; missing `launcher`/`lockInfo` => cleared). Parent page must always send the full object.
  - Equal `rev` is accepted on `setRules` (`<` not `<=`), relay needs `>`.
  - Unknown/invalid values fall back silently to defaults (bed times, trackMin, unlockMin, windMin, safeDns). Lists capped silently (schedules 20, chores 20, zones 10, groups 10, 100 pkgs/group, 5 windows/group).

### 1.2 Tick (the loop) - order of work

`Guard.tick(force)` Guard.kt. Screen off and not forced => full pass at most once/min.
1. `Acc.ensureEnabled` (re-adds accessibility entry w/ WRITE_SECURE_SETTINGS), `Policy.retryDns`, `Ts.poll`.
2. Track fix every `trackMin` -> `trackFix`; `keepLocation`.
3. Usage access check: if revoked => log once + `tamper` event only.
4. App list `Policy.cached` (60 s cache; `invalidate()` on pkg add/remove via `pkgReceiver`).
5. New-app bookkeeping `knownApps/loggedNew`.
6. `dailyLeftMs` -> `Warn.due` heads-up -> wind-down gray.
7. `decide()` -> `Quick.publish` -> `Policy.suspendExactly` -> `applyHidden`.
8. Basic/owner bounce, `goHome` if front app just got suspended.
9. `locate()` every 15 min, `recoverAt` pairing reopen, hourly 7-day history, `Update.maybe`, `launcherTick`, `Guard.snap` for the launcher, build `lastState`.

### 1.3 Foreground-app decision / block-reason priority

`decide(r, usageMs, minuteOfDay, nowMs, today, launchable, protected, newApps, unlockUntil)` Rules.kt -> map pkg -> reason. Pure, unit-tested (DecideTest).
Order of writes (first writer wins, `p !in out`):
1. `blocked` - not for `protected`; ignores `allow`.
2. `appLimit` - `usageMs[p] >= appMin*60s`; ignores `allow`.
3. `groupHours` / `groupLimit` - per group: not in window => hours; else shared `min` sum => limit. Ignores `allow`.
4. `new` - only when `blockNew`, pkg in `newApps`, not allowed/approved.
5. Phone-wide reason for every launchable not `free` (`protected + allow`) and not already in out: `paused` > `schedule` > `bedtime` > `daily`.
6. `unlockUntil > now`: remove everything except `blocked` and `new`.
- Matches the priority line at the top of protocol.md.
- `protected` = Policy.protectedApps (Policy.kt): self, systemui, settings, phone/dialer/contacts, alarm clocks, gms/gsf, enabled keyboards, system HOME handlers + the user-chosen default HOME / dialer / SMS app.
- Consumers of the same function: Guard.tick, `ui/MainActivity.kt`, `launcher/HomeActivity.kt` (fallback when no snap), `Quick` tile text.
- Enforcement of the result:
  - Owner: `Policy.suspendExactly` (Policy.kt) -> `setPackagesSuspended`; only `store.suspendedByUs` (pref `susp`, commit()) is ever un-suspended by the loop. System shows its own "paused by admin" dialog (text = `blockNote`, Policy.kt).
  - Owner but OS refused suspend (OEM apps): `Acc.bounceApp` HOME + notification (Guard.kt).
  - Basic: no suspend. `Acc.blocked = want`; `Acc.basicGuard` bounces on every window change (Acc.kt, 1 s throttle `lastPoke`), tick bounces the current app. `bounceApp` once per 10 s per pkg for the notification (Acc.kt).
  - `BasicLogic.NEVER` + `prot` are never bounced.

### 1.4 Daily limit / bonus / schedules / bedtime / pause

- Used time: `Usage.today` (Usage.kt) = sum over foreground intervals since local midnight. MOVE_TO_FOREGROUND opens, MOVE_TO_BACKGROUND/screen off/keyguard/shutdown closes; a new foreground app closes all others; still-open apps closed at `now`; `current` = last open app.
- `daily`: `base = baseLimitMin` (Rules.kt: `dailyByDay[isoDow-1]` when size 7, else `dailyMin`); `limit = base + bonusMin` only if `bonusDay == today`; `used` = sum of launchable, non-`free` apps; over when `base > 0 && used >= limit`. `dailyLeftMs` mirrors it (-1 = none).
- Bonus: parent page writes `bonus {day,min}` into rules (answer cmd only removes the request: Guard.kt). `today` = device local date `Guard.day()` (Guard.kt).
- `schedule`: `Schedule.active(minute, dow)` (Rules.kt): crossing-midnight window belongs to start day; second half checks previous ISO day. `AppGroup.hours` reuse it.
- `bedtime`: `Rules.inWindow`, same every day (no day set).
- `paused`: `pausedUntil > now`.
- Timed mode (0.9.22, `rules.mode`): page "Apply until…" (templates sheet, `kid.js modeApply`) sets `{name, until, rules: template fields}` on the normal rules. `Store.rules` (what Guard, launcher, `decide()` read) = `Rules.effective(now)`: normal rules + `mode.rules` while `now < until`, else the normal rules; cached per stored string until `until`. `Store.baseRules` is the stored/edited copy (state, `GET/PUT /api/rules`, setRules, Telegram, BankList, release use it). Tick start (`Guard.tick`): expired `mode` -> `setRules(base without mode, rev+1)`. "End now" = PUT without `mode`. Release clears it (`Policy.clearLost`). Gotcha: expiry is exact for enforcement but the stored field and `Policy.apply` follow at the next tick (10 s, 60 s screen off).
- Exempt from the four phone-wide reasons: `protected + allow`.
- Heads-ups: `Warn.due` (Warn.kt) 5 min / 1 min before daily limit, 5 min before bed/schedule start, optional 10 min wind-down nudge; dedup keys `"<day>:<name>"` in pref `warned` (Store.kt); shown by `notifyWarn` (Guard.kt).
- Wind-down gray: `WindDown.active` (Comfort.kt) window `[bedStart-windMin, bedEnd)`; `GrayFx.tick` (GrayFx.kt) writes `accessibility_display_daltonizer*` (needs WRITE_SECURE_SETTINGS); old values saved in prefs `grayOwned/grayOn/grayMode` first (Store.kt) and restored when off/release.
- Request time: child `Store.addRequest` (max 5, Store.kt) -> parent answers -> rules `bonus` raised -> next tick.
- Gotchas: bonus ignored when `base == 0`; day boundary depends on child clock/timezone (owner pins auto-time, Policy.kt; basic mode does not).

### 1.5 App suspend / hide

- Suspend: loop only (Guard.kt). `want.keys` vs `store.suspendedByUs`; failures from DPM (returned array) are not recorded as suspended (Policy.kt). Logs `log_suspended/unsuspended`. `nowSusp` is persisted only when changed.
- `goHome()` when the front app was just suspended (accessibility HOME first, else startActivity).
- Hide ("Dọn máy"): `applyHidden` (Guard.kt) when `rules.hidden`/`hiddenByUs`/never-set changed or every 10 min. Owner => `setApplicationHidden`; non-owner => self-adb (falls back to `pm uninstall -k --user 0` when disable is refused, e.g. HyperOS) `pm disable-user` (`hideViaShell` Policy.kt, needs Wireless debugging). Refusals tracked in `refusedHide` (Debloat.refusedAfter). State prefs: `hid` (owner), `shellOff` (shell), `hideRefused`.
- Never-hide set = protected + self + Tailscale pkg (Policy.kt).

### 1.6 Location

- `keepLocation(r)` (Guard.kt): owner + `lockLocation` + location off => API30+ `setLocationEnabled(true)`; API<30 `setSecureSetting(LOCATION_MODE)` then WSS fallback. Runs every tick and instantly from `locObserver` (ContentObserver on LOCATION_MODE/PROVIDERS_ALLOWED).
- Policy side: `applyNet` (Policy.kt) `lockLocation` => `setLocationEnabled(true)` + `DISALLOW_CONFIG_LOCATION`, else clear.
- Two independent location timers:
  - Pace (`guard/PowerLogic.kt` `LocPace`, 0.9.18): a good `trackFix` (acc <= 100 m) also becomes pref `loc`, and the 15-min `locate()` is skipped while those are fresh (`locateDue`). 2 same-spot fixes with the screen off + `TYPE_SIGNIFICANT_MOTION` armed (`Guard.pace`) = slow pace (history >= 15 min, locate hourly); the sensor firing or screen on = normal pace and a fix at once. No sensor = never slow.
  - `locate()` (Guard.kt) every 15 min and on `locate`/SOS/check-in commands -> pref `loc` (last fix) + zone events (only if fix < 10 min old, acc <= 300 m).
  - `trackFix(trackMin)` every `trackMin` (0 = off): one fix (30 s), fallback last-known < 2x interval -> `Store.addTrack` (Store.kt; keep 7 d, <= 2000 pts, stores if moved >= 30 m or gap elapsed, `Track.gapFor`). Pref `track`.
- `fixProviders`: fused (API 31+) else network+gps together; listeners always removed (no leaked GPS).
- Zones: `Store.zoneFix` (Store.kt) in/out sets in pref `zin`; baseline after zone list change.

### 1.7 Device restrictions (Policy.apply) + release undo

`Policy.apply(ctx, r)` Policy.kt. Called: `Guard.onCreate`, every `setRules`, after self-update (Update.kt), release failure paths. NOT every tick (restrictions are persistent in DPM).
Non-owner: only `LauncherSafe.apply`.
Owner adds:
- BASE: factory reset, safe boot, add user, config date/time. INSTALL pair if `noInstall && !Update.installing` else cleared. `DISALLOW_DEBUGGING_FEATURES` iff `noDebug`.
- `setUninstallBlocked(self)`; API30+ `setUserControlDisabledPackages([self])` + `setAutoTimeEnabled(true)`; API<30 `DISALLOW_APPS_CONTROL` + `setAutoTimeRequired(true)`.
- `applyNotes`: short/long support message + lock-screen owner info.
- `applyNet`: location (see 6), `DISALLOW_AIRPLANE_MODE` (API 28+) iff `noAirplane`, Private DNS (`applyBrowsers` Chrome/Edge one managed-config bundle: `DnsOverHttpsMode=off` while safeDns + `noIncognito` + `webBlock`/`webAllow`, `applyVpnLock` = only owner of `DISALLOW_CONFIG_VPN` for `noVpn` + Tailscale; `applyDns` :105: `safeDns` host -> restriction `DISALLOW_CONFIG_PRIVATE_DNS`; rejected => opportunistic + restriction cleared + `dnsError`). `retryDns` every tick: retry after rejection; 90 s non-validated network => opportunistic fallback for 30 min (restriction kept).
- Grants: fine/coarse/background location, POST_NOTIFICATIONS, WRITE_EXTERNAL (<29), RECORD_AUDIO -> GRANTED.
- `LauncherSafe.apply` (HOME persistent preferred activity).
Also by others: Tailscale `DISALLOW_CONFIG_VPN` + uninstall block + suspend of TS pkg (remote/Ts.kt, undone Ts.kt).

#### release
Trigger: command `release` (relay: deliver-once; LAN: `POST /api/cmd`). `Guard.command` (Guard.kt) marks cmd id with `commit()` first -> `run` -> `release()`.
- `Guard.release`: on the work thread at front of queue: `stopped=true`, remove loop callbacks, stop Listen/Proj/Ring/wifi lock, `GrayFx.restore`, `Policy.release`. Result != "ok" => `stopped=false`, loop restarts, log `release_fail`. "ok" => log, `Files.wipeOutbox`, `stopSelf` after 2 s. Caller waits max 20 s.
- `Policy.release` owner path (Policy.kt), in order:
  1. `Ts.undo`; unsuspend all launchable + `suspendedByUs`; `unhideAll`; `stuck` = still hidden => re-`apply`, re-arm watchdog, return `"failed: N apps still hidden"` (res string `release_stuck`).
  2. `enableShellDisabled`; `Update.forget`.
  3. Clear restrictions: BASE + INSTALL + DEBUGGING + APPS_CONTROL; `setUninstallBlocked(false)`; `applyNet(Rules(lockLocation=false,noAirplane=false,safeDns=""))` (location, airplane, private DNS back); `setUserControlDisabledPackages([])` / `setAutoTimeRequired(false)`; support messages + lock info null.
  4. `removeAccEntry`; `NotifDots.setOn(false)`; `LauncherSafe.off(goHome)`; reset location/notif/storage grants to DEFAULT, RECORD_AUDIO DENIED then DEFAULT.
  5. `store.released = true` (commit), `Watchdog.cancel`, `clearDeviceOwnerApp`; zero `susp/hid/hideRefused`. If still owner => `released=false`, re-`apply`, re-schedule watchdog, return `"failed"`. Else `clearTokens`, `Quick.setEnabled(false)`, `"ok"`.
- Basic path `releaseBasic`: `released=true`, clear `Acc.blocked`, cancel watchdog, `removeActiveAdmin` + wait <= 3 s, then clear acc entry, dots, launcher off, `Acc.disableSelf`, tokens, Quick off. "not owner" if no admin active.
- After release nothing restarts: `Guard.start`, `Watchdog.schedule` (Watchdog.kt), `Quick`, `LauncherSafe.wanted` check `Store.released`. `Admin.onEnabled` / `onProfileProvisioningComplete` reset it (Policy.kt).
- Not undone (by design?): `setAutoTimeEnabled(true)` (API30+), Sys settings the parent changed (volume/brightness/DND), Private DNS ends as opportunistic (not the user's earlier value), `rules` pref kept (re-provisioning reapplies old rules + PIN).

### 1.8 Watchdog / boot / restart

- `Boot` receiver (Guard.kt) on BOOT_COMPLETED + MY_PACKAGE_REPLACED: `Guard.start` + `Watchdog.schedule`; update also `LauncherSafe.afterUpdate`. No LOCKED_BOOT, no directBootAware => nothing runs before first unlock (DPM suspends/restrictions persist meanwhile).
- `Guard.start` (Guard.kt): no-op in HOME flavor and when `released`; `startForegroundService`, errors only logged.
- `Watchdog` (Watchdog.kt): `setAndAllowWhileIdle` ELAPSED_REALTIME_WAKEUP every 10 min (inexact, Doze max ~1 per 9 min); `onReceive` = `Guard.running?.relayWake()` (Relay.wake: wakelock + drop a socket quiet > 2 min + sync now, ignores backoff) else `Guard.start`, + re-arm. Each relay sync holds a partial wakelock (≤ 60 s): Handler delays use uptime, which stops while the phone sleeps. Also armed in `Guard.onCreate` and `Acc.onServiceConnected` (Acc.kt).
- Service restarts: `START_STICKY`, `onTaskRemoved` -> `start`, `Acc.onServiceConnected` after OS rebind, `Acc` SCREEN_ON/USER_PRESENT receiver, accessibility events -> `Guard.poke` -> `start` if not running, Update/Admin callbacks.
- `onCreate`: foreground notif, `Policy.apply` + `Quick.setEnabled` on work thread, content observers, pkg/time receivers, wifi lock, `Http` :8770, `Relay`, post loop.
- Time receiver: clock jump / timezone change => `tamper`-style `clock` event (`Events.clockJumped`).
- Known kill mode: ColorOS Recents swipe = real force-stop (protocol.md "ColorOS"): nothing of ours runs until the app is opened.

### 1.9 PIN / admin unlock / Authenticator code (Store.kt)

- PIN: hash in rules (`pinHash`,`pinSalt`); `Rules.hashPin` (Rules.kt) salted = 10 000 x sha256; legacy `sha256("kg:"+pin)`. Compare constant-time.
- Lockout: `Store.checkParentPin` pref `pinFails/pinLock`; `Safety.pinLockMs` 5 free, then 5 min doubling (cap 64x).
- Pairing: `tryPair` (Store.kt): code (6 digits, open 10 min or while never paired) -> PIN check/set (`PairPin.decide`) -> token pending 10 min (`PendingPairs`) -> `commitPair` on first authenticated request: store token hash, `everPaired`, new PIN into rules, one `commit()`. 3rd wrong guess => new code + 30 s doubling block (`Safety.pairBlockMs`).
- Child "Bố mẹ quản lý trên máy này": `ui/MainActivity.kt` -> `Store.checkAdmin`: TOTP first (6 digits), else PIN; wrong => `tamper` event; returns 1/0/-1; then `ui/AdminActivity` session.
- Child unlock code (Authenticator): `tryUnlock` `Totp.check` (+-1 step, step > `totpLast`); ok => `unlockUntil = max(now, old) + unlockMin` (stacks), `unlock` event; 5 free tries then 5 min doubling (`unlockFails/unlockLock`), 5th wrong => `tamper`. Effect = decide() step 6.
- `totpSet` command (Guard.kt) stores secret in pref `totpSecret`, resets `unlockUntil`; secret never sent to parent.
- "Lost the link": child screen sets `recoverAt`; tick opens pairing after 24 h unless a PIN exists (Guard.kt).
- Recovery when the PIN is forgotten (verified in code): (a) a still-paired browser/app: "Quên PIN?" = `pinReset`, applies after 24 h with an alert to every parent, the old PIN cancels it (since 0.9.13 a rules PUT no longer replaces a set PIN; `CmdGate`, protocol "PIN-gated commands"); (b) Authenticator set: `checkAdmin` accepts its TOTP -> on-device admin page -> Settings -> new PIN (`MainActivity.askAdmin`); (c) no PIN ever set: "lost the link" (24 h, above). No PIN + no paired device + no Authenticator = no in-app recovery (only factory reset / adb); deliberately no new mechanism, it would also be a way in for the child.
- `openPairing` command refuses with "set PIN first" if ever paired and no PIN (Guard.kt).

### 1.10 Other commands in `Guard.run` (Guard.kt)

`fixPerms` (2026-11-14, `guard/FixPerms.kt`): `missing(ctx)` = state `missing` (admin/acc basic only, usage, listener, location, notify, battery). `fix`: thread -> DO `setPermissionGrantState` (location, notifications) + `Acc.ensureEnabled` (WSS) -> `AutoGrant.missing` cmds over `SelfAdb.run` if the link is up -> what is left: `ask` starts the first Settings screen (only if `AccPause.canReopen`) + notification id 8 (channel `fix`). Result `ok|working|none`, details in state `fixPerms`. Page: live band `#fixPermsBtn` in `live.js`. Not covered: OEM auto-start (undetectable).

Dedup by id (`markCmd`, last 100, Store.kt). `lock` (DPM lockNow), `reboot`, `clearData`, `setting` -> `Sys.set`, `devOff/devOn` -> DevOpts, `relay` (relay URL/token), `unpair/unpairAll`, `answer`, `sosAck`, `update`, tailscale*, file*. `Sys`: volume via AudioManager; brightness/timeout via DPM.setSystemSetting (owner) else Settings.System; DND via NotificationManager (needs policy access); state cached 60 s (Sys.kt) and must read back or "not applied".

### 1.11 Basic mode (no Device Owner)

- `Basic.mode` (Basic.kt): owner | basic (admin active + accessibility on) | setup. `enforcing` = !owner && admin && !released.
- Blocking only by bounce (see 3). Self-protect: `BasicLogic.selfProtect` (BasicLogic.kt) on Settings-like packages scanned at 0.2/0.8/1.8 s (Acc.kt); needs both a "KidGuard" mention and a destructive control.
- Hide/clear only through self-adb shell; location lock, restrictions, suspend: not available. Checklist UI: the "Việc cần làm" card `SetupSteps` (`ui/SetupSteps.kt`, both modes).

### 1.12 Bank apps vs accessibility (`guard/AccPause.kt`)

- Why: Vietnamese bank / wallet apps refuse to run while ANY accessibility service is on. Rule `accPause` = package list (default `AccPauseLogic.DEFAULT`).
- Trigger: the bank comes to the front (`AccPause.tick(use.current)` every tick, or `pauseLate` from the Acc window event).
- Steps: Home (global action) -> our service really turns off (`disableSelf`) -> kill the bank's background process -> reopen it from the background (its notification's own intent if it was opened from one, `NotifLog.recentClick`, else the launch intent).
- Debugging too (2026-11-11, My MobiFone error 50052): the same pause snapshots Developer options / USB / wireless debugging (`Store.devPaused` mask, `DevOpts.bankPause`), turns them off, and the resume (or release via `AccPause.clear`) puts back exactly what was on, unless `noDebug` / DISALLOW_DEBUGGING_FEATURES came meanwhile (`DevOpts.bankResume`). Device Owner without WRITE_SECURE_SETTINGS can only restore USB debugging. adb drops while a bank is open (wireless `adb tcpip` port survives the adbd restart until reboot; Wireless debugging gets a new port).
- Refusal hint (2026-11-17, owner: a user who only sees the bank say "remove KidGuard" tries to uninstall and gets stuck): `RefuseHint` (from `Acc.onAccessibilityEvent`, also toasts via `typeNotificationStateChanged`) reads the text an app shows in its first 12 s (dialog / toast; FLAG_SECURE does not hide it from accessibility, `accessibilityDataSensitive` views would) -> `RefuseLogic.kinds` (no accents/case: accessibility / debugging / VPN words + a "something is wrong" word, text <= 600 chars) -> kinds not covered by the rules -> notification with the fix ("don't uninstall anything", toasts are capped at 2 lines) + log + event `refused` (parent app gets an FCM push at the next relay sync) + state `refused` -> page band "Add to the list". Never pauses by itself (a kid could make an app show those words). Once per app + kind per 6 h. Page also has Export / Import list (website `bank-apps.json` shape; also takes `{accPause:[..]}` or bare package names).
- List from the website (2026-11-11, owner): `BankList.kt` reads `https://kidguard.asia/data/bank-apps.json` (`website/data/bank-apps.json`) once a day + the bundled `AccPauseLogic.DEFAULT` at start; adds only packages never offered before (`Store.bankSeen`, seed = 0.9.12 list) to `rules.accPause`, so a parent removal sticks; `drop` removes only list-offered ones. A blocked app never pauses accessibility (AccPause.pause), so the list cannot open a bypass. New bank app = edit the JSON + Pages deploy, no release. Per entry `acc` / `vpn` / `dev` / `tcp` (2026-11-20, `tcp` = rules.tcpPause "Cổng adb", seen set `Store.tcpSeen`).
- VPN too, per app (2026-11-11, Cake "thiết bị có dấu hiệu đang bật VPN"): rule `vpnPause` is its own list (page: one row per app, Trợ năng / VPN boxes). `VpnPause.kt`: on open, owner mode snapshots + lifts always-on VPN, Tailscale gets DISCONNECT_VPN; `Ts.keep` and the Ts policy re-assert skip while paused; launcher starts the app 0.9 s later after a fresh pause, Acc restarts it (AccPause.relaunch) when it came up another way. Resume on the same timer as acc: always-on back + CONNECT_VPN. Release: CONNECT_VPN only (always-on was ours, Ts.undo drops it). Basic mode + always-on set in Settings, or a non-Tailscale VPN: cannot, state `vpnStuck` -> page alert.
- Resume: with WRITE_SECURE_SETTINGS the service comes back once no bank was in front for 10 min (Device Owner) / 1 min (basic, since basic mode blocks through the service).
- Gotchas: basic mode needs "display over other apps" for the reopen (`canReopen`; `grantOverlay` tries every 6 h). Release clears the pause (`AccPause.clear`).
- Without WRITE_SECURE_SETTINGS (2026-11-20, Xiaomi basic mode): `AccPause.selfOff` / `selfOffLate` turn the service off with `disableSelf()` only; `Store.accSelf` = "expected, kid must turn it on". `AccPause.tick` -> `selfTick`: 15 s after the bank left -> notification "Bật lại bảo vệ" (button: Accessibility settings, every 2 min); service back (`Acc.isOn`) -> cleared; 5 min after leaving still off -> state cleared, the normal `accoff` tamper alarm fires. Parent sees `accPaused` ("paused for <app>"), not tampering, until then. While off: no app blocking, nav gestures, screen/input features. Device Owner / granted phones keep the normal path.

### 1.13 Tailscale keep-alive (`remote/Ts.kt` `Ts.keep`, both modes)

- Why: parents reach the phone through Tailscale; HyperOS "clean all" / a Recents swipe / battery saver kill it (seen on Poco 2026-11-10: `am_kill ... OneKeyClean`), and in basic mode there is no always-on VPN.
- Applies when Tailscale is installed AND (rule `tsKeep`, default OFF, page Settings > Tailscale, OR KidGuard manages it, `tsWanted`); not in the Home flavor, not after release. Default off on purpose: a Tailscale installed for work must not be forced on.
- Steps (`TsLogic.reviveStep`, unit-tested): no `100.64/10` address for 20 s -> explicit broadcast `com.tailscale.ipn.CONNECT_VPN` to `IPNReceiver` (silent, restarts a killed process) every 60 s -> still down after 90 s and our launcher in front with the screen on -> open Tailscale once and go Home (max every 10 min). Logs `ts_log_down` / `ts_log_back` to the parent.
- Device Owner also has always-on VPN (`Ts.applyPolicy`); a hidden (suspended) Tailscale is never opened, only broadcast to.
- Gotchas: a force-stopped app gets no broadcasts until opened (hence the open step); release stops it (`s.released`).

### 1.14 Factory reset protection (0.9.17, `guard/Frp.kt`, `Policy.applyFrp`)
- Trigger: rule `frpIds` (numeric Google account ids; `Frp.ids` sanitises) saved from the page (Settings: field, how-to, warning).
- Steps: `Policy.apply` -> `applyFrp` -> `setFactoryResetProtectionPolicy` (Device Owner, Android 11+); empty list = `null` policy. State `frp` says applied / unsupported.
- Undo: `Policy.release` clears it.
- Gotchas: the API takes the numeric id, not the email (no in-app way to read it); a wrong id can brick the phone after a reset; not testable without a reset.

### 1.16 Monitoring disclosure (2026-11-17, `guard/Monitoring.kt`, `ui/WatchActivity.kt`)
- Trigger: rule `watch.*` changes (page / parent app / relay `PUT rules` -> `Guard.setRules`).
- Steps: `Monitoring.turnedOn(old, new)` -> one heads-up notification (`kg_watch`); `fgNotification` appends `Monitoring.line` (the lasting notification; tick `relabel` keeps it in the phone language) and opens `WatchActivity`; the main screen has a row "Bố mẹ xem được gì".
- State: only the rule. Release: `Monitoring.release` + each feature's own undo.
- Gotchas: the registry is the only list: add a `Cat` (and strings in both languages) before the feature, `MonitoringTest` enforces it. The lasting notification is on a low channel the child can turn off in Settings (Android lets any user hide a foreground notice); the heads-up and the screen cannot be hidden by us. Not in the Home flavor (no Guard, no parent).

### 1.17 Notifications and word alerts to the parent (2026-11-17, `remote/NotifSync.kt`, `guard/KeywordLogic.kt`, page `tab-seen.js`)
- Trigger: a notification is posted (`NotifDots` -> `NotifLog.record` -> `NotifSync.offer`), rule `watch.notif` / `watch.words`.
- Steps: `offer` dedupes (same key + text = not new) and matches `KeywordLogic.hit` (accent/case-insensitive, any text of the notification) for the word alert. State builder adds `notifs`: `NotifSync.stateJson` reads the newest 100 rows of the notification log (sqlite), `NotifFeedLogic.send` applies on / keywords-only / app filter, newest 20; cached on `NotifLog.version` (main thread: cached copy only). Telegram `/notif` reads the same log with a (time, row id) cursor. Older rows: `GET /api/notifs` or relay `notifPull` -> file channel. First page logs "parent looked".
- Keyword hit: `NotifSync.alert` -> `Store.event("word", ...)` (throttled) -> FCM like any event.
- Parent-app mirror (0.9.74): after a row is written, `NotifSync.nudge` (no keyword hit, feed sends it) -> `Guard.relayNudge` (screen off or socket up) -> relay `kick()`, coalesced by `NudgeGate` to 1 per 30 s with a trailing kick. Worker `nfNew` (in `pushNew`): newest non-hit row id > `nfPushed` -> FCM `{kind:"nf", kid}` (no text, collapse `nf-<kid>`, ttl 600 s, >= 15 s apart) to tokens registered `nf: true`. Parent app `Push.handle` -> `KidNotif.fromRelay` (`GET state?nf=<cursor>`) -> `Logic.nfPlan` -> one quiet InboxStyle notification per kid (channel `kg_kidnotif`); `CheckJob` does the same from the whole state every ~15 min (LAN-only kids). Latency: screen off ~3-8 s, screen on <= 20 s. Gaps: held (focus) rows keep their old time and are never mirrored; a kick during relay backoff is skipped.
- State: ring in memory only (`NotifSync.clear` on rule off and release). The page keeps its own merged list per kid in localStorage.
- Gotchas: no notification access = nothing arrives (the page says so after a pull). A notification the phone never recorded (ongoing, own app) is never sent. Narrowing to keywords-only is silent; widening to everything is told to the child again.

### 1.18 Photo on request (2026-11-17, `remote/Snap.kt`, `ui/SnapActivity.kt`, `Shot.take`, page `tab-seen.js` card `seenCam`)
- Trigger: page command `snap {fid, cam, flash, size, delay, relay}` (rule `watch.cam` on). Refusals (answer string): off, screen off, no camera permission, no such camera, busy, unsupported (Home).
- Steps: tell the child (notification id 13 + log `watch_snap`), start `SnapActivity` (translucent, `FLAG_NOT_TOUCHABLE`: Android 11+ gives the camera only to a visible app), wait `delay`, flash (front = white window at brightness 1 for <= 2 s, back = torch; "auto" = light sensor < 10 lux), `Shot.take` to `snaps/<fid>.jpg`. LAN: page polls `GET /snap/<fid>` (404 pending, 409 + reason). Relay: the phone pushes the jpg (or a json `{error}`) on the file channel under `fid`.
- Safety: the white is removed by a posted callback (<= 2 s), onStop and onDestroy; the whole Activity ends in 25 s; if the window does not start in 4 s the photo is taken from the service without flash.
- State: every photo stays in `filesDir/snaps` (`<fid>.jpg` + `<fid>.json` meta, failed tries too, NO cap, 2026-11-17 history), deleted on release / rule off (`RemoteSnap.clear`). Page: Seen tab "Ảnh đã chụp" (`hist.js`) pages them with `GET /snaps` / relay `snapList`, thumbs `?thumb=1` / `snapUp {thumb}`, delete = `snapDel {ids}` (select mode, `select.js`). Camera permission is granted by Device Owner (`Policy.apply` when `watch.cam`) or `AutoGrant.camNow`. Also reachable from Telegram `/snap` (1.20); live camera = flows 2.16.

### 1.15 Lost mode card escape (0.9.16, `Guard.lockForLost`, `MessageActivity`)
- Trigger: rule `lostOn` (+ `lostMsg`, `lostPhone`) from page / parent app / Telegram `/lost`.
- Steps: on `USER_PRESENT` (not SCREEN_ON) show the card (message, call-back number, "Parent: turn off"); Device Owner `lockNow` again after `LOST_CARD_MS` (20 s). A right PIN / Authenticator code on the card sets `lostGraceUntil` (10 min): no relock, so a parent holding an offline phone can end lost mode on the page. `lostTick` locates every 5 min and loops the ring; a SIM change raises an event.
- Gotchas: Basic mode shows the card only (no `lockNow`); `lostOn` wins over `monitor` in `nowWhy`.

### 1.19 Text messages for the parent (2026-11-17, `remote/Sms.kt`, page `tab-seen.js` card `seenSms`)
- Trigger: rule `watch.sms` on (page switch, confirmed: the child is told). Policy grants READ_SMS (Device Owner) / our adb (basic, every 10 min at most); release takes it back.
- Steps: the page loads a page on demand (LAN `/sms`, relay `smsPull` + file channel); `Sms.tick` on each Guard tick turns a new incoming SMS with a `watch.words` word into the `word` event (`NotifSync.alert`).
- Gotchas: no permission = the page says so; the Notifications card still shows SMS notification text. Chat apps are not SMS. Call audio is not recorded (out of scope).


### 1.20 Telegram bot on the phone (`remote/Tg.kt`, pure `TgLogic.kt`, `CamClip.kt`; protocol "Telegram bot on the child phone")
- Trigger: the family's own bot token (`tgSet`), `Tg` long-polls `getUpdates`; a chat is let in with the page's 6-digit code (max 5). Alerts are scanned from the store (and `HistDb` with a row cursor) on every Guard tick (`Tg.tick`); evening summary `digestTick` (2.10). Destructive commands are not offered. Slow commands (rec, locate, screen) run on a bounded 4-thread pool.
- `/snap [front|back]`: `Tg.snap` -> the same `RemoteSnap.command` as 1.18 (rule `watch.cam`, child notice, log, photo history), sent with `sendPhoto`; refused while a live camera runs. `/clip [secs] [front|back]` (5-30 s): `Tg.clip` -> `CamClip` (Camera2 + MediaRecorder H.264/AAC ~480p) reuses `CamService`/`CamActivity` (extra `clip`) for the while-in-use camera rule, ongoing notice + log, `sendVideo`, temp file in `cacheDir/tgclip` deleted after sending / on release / rule off; no mic permission = video without sound (caption says so). Commands older than 10 min are dropped (`TgLogic.tooOld`).
- One-tap turn-on: `/snap` `/clip` with `watch.cam` off, or `/notif` with `watch.notif` off, send an inline button (`askWatch`, data `w:<command line>`, `TgLogic.watchData/fromWatchData`, only `WATCH_CMDS`). The tap runs `TgLogic.watchOn` through `editRules` -> `Guard.setRules` (so child notice + log + page state follow like any rule change), removes the button, runs the command, and raises event `watchTg` (in `Events.IMPORTANT`) so every parent hears who turned it on. Page twin: Take photo / Watch live with `watch.cam` off = one confirm (`snapGo.ensure` in `tab-seen.js`: child is told, rules saved, then the action goes on).
- `/notif on|off`: forwards the notification feed (rule `watch.notif`) to that chat, silent, batched per tick, from the notification-log sqlite with a (time, row id) cursor (nothing lost while offline; see 1.17).
- Gotchas: texts vi + en in `TgLogic`; a new command needs the menu list (`setMyCommands`, refreshed once per process via `cmdsSet`), help text and `TgLogicTest`.

### 1.21 No re-alerting notifications (gate `NotifGateTest`, app + parent)
- Rule (INCIDENT 2026-11-20: the usage-access notice was re-posted with sound every tick, ColorOS popped it every few seconds): anything posted from a tick / receiver / retry loop posts once per state change (remember what was posted: `Store.degraded`, `Store.refused`, ...) AND sets `setOnlyAlertOnce`.
- Gate: `android/app/src/test/.../NotifGateTest.kt` (and the parent twin) scans every `Notification.Builder(` chain: `.setOngoing(true)` always needs `setOnlyAlertOnce(...)` (not `false`); any other must set it too or be listed in `ONE_SHOT` ("File.function" + why it cannot repeat). New notification = fix the code first, an `ONE_SHOT` row only for a one-event notice.
- Related: `Degraded.report` once per new key, NavGest rescue notice on change, `applyHidden` does not re-open Developer options / Wireless debugging every tick (10 min), `log_acc_rebind` hourly, `log_dns_fallback` every 6 h.

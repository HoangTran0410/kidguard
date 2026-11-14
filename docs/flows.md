# KidGuard flows (how things actually work)

Traced from the code on 2026-11-09 (main @ 0c95d99). For each flow: trigger -> steps -> where state lives -> gotchas.
**Line numbers drift**: treat `file:123` as a hint and grep the function name. Wire formats are in `docs/protocol.md`
(the contract); decisions, rejected approaches and known open issues are in `docs/decisions/` (one file per area, index in `README.md`).

Sections: 0 Privileges -> flows · 1 Enforcement (child app) · 2 Parent <-> phone (LAN, relay, parent app) · 3 Kid launcher · 4 Setup, build,
release · 5 Add-a-feature checklists (new command / rule field / state field / worker route).

Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. Guard section paths are relative to `K/`.

## 0. What KidGuard can do (privileges -> the flows that use them)

Read this before saying "we can't" or building a new mechanism: most needs are already covered by a privilege some
flow holds. Add a row when a flow starts using a privilege.

| Privilege (how it is granted) | What it allows | Used by |
|---|---|---|
| Device Owner (QR / setup / SelfSetup) | suspend / hide apps, user restrictions, silent APK install, always-on VPN, lockNow, reboot, system settings, start activities from the background | `Policy.apply`, `Ts.setup` (installs + logs in Tailscale), `Update`, `AccPause.canReopen` |
| Device admin (basic mode) | marks basic mode (`Basic.mode`), lockNow | `Basic.kt` |
| Accessibility service `Acc` | the window in front, global actions (Home, Recents, close quick settings), remote taps, read Settings screens | basic-mode bounce (§1.11), self-protect, remote input (§2.5), `AccPause` bank flow (§1.12) |
| `WRITE_SECURE_SETTINGS` (adb grant) | turn our accessibility back on, Private DNS, location mode, auto time | `Acc.ensureEnabled`, `Policy.syncDns`, `keepLocation`, `AccPause` resume |
| Background activity start (Device Owner, or `SYSTEM_ALERT_WINDOW` in basic mode, `AccPause.grantOverlay`) | open another app's screen while KidGuard is in the background | `AccPause.pauseLate` (reopen the bank), `Ts.keep` (open Tailscale), `Ts.setup` |
| Explicit broadcast to another app's receiver | wake a killed app without UI | `Ts.keep` (Tailscale `CONNECT_VPN`, §1.13) |
| `KILL_BACKGROUND_PROCESSES` | kill another app's background process | `AccPause` (fresh bank start) |
| Usage access | foreground app + time per app | `Usage.today` (`use.current` in the tick, also = our launcher when home is in front) |
| Notification listener | notification history + stats, dots, re-send a notification's tap intent, hold an app's notifications (snoozeNotification until 8/12/18 h or a mute end) | `NotifLog`, `NotifStats`, `NotifHold`, `NotifDots`, `AccPause` deep link |
| DND policy access | Do Not Disturb on/off | study mode (`FocusStore`), `Sys` |
| Mic / camera / media projection / location (runtime) | listen, Telegram /rec, wrong-PIN photo, screen view, locate / track | `Listen`, `Tg`, `Shot`, `Proj`, `Guard.trackFix` |
| Self adb (Wireless debugging against itself) | shell grants, hide / clear in basic mode | `SelfSetup*` |
| Foreground service + 15 min `Watchdog` alarm + boot receiver | stay alive, restart after kill / reboot | `Guard`, `Watchdog` |

## 1. Enforcement (child app, `K/guard/`)

Paths relative to `android/app/src/main/kotlin/dev/kidguard/`. Line numbers as of main @ 0c95d99.
Prefs: one SharedPreferences file `kg` in device-protected storage (`guard/Store.kt:14`). Every key below is in it.

### 1.0 Map

- `guard/Guard.kt` foreground Service `Guard`: 10 s loop `tick()` (TICK_MS, :672), `kick()` = tick now (:138), `pokeSoft()` = debounced 300 ms kick (:141).
- `guard/Rules.kt` pure: `Rules` data + JSON, `decide()` (:331), `dailyLeftMs()` (:367), `Schedule`/`AppGroup`/`Totp`/`Track`/`Safety`.
- `guard/Policy.kt` Device Owner (DPM) calls: `apply` (:45), `suspendExactly` (:232), `hideExactly` (:256), `release` (:410), `releaseBasic` (:469).
- `guard/Usage.kt` UsageEvents -> ms per package today. `guard/Watchdog.kt` 15 min alarm. `guard/Store.kt` all persistence + PIN/TOTP/pairing.
- `guard/Basic.kt`, `BasicLogic.kt` basic mode (no Device Owner). `Comfort.kt` (WindDown/Gray/Notes/Checkin/ReqTpl, pure), `GrayFx.kt` its Android side, `Warn.kt` heads-ups, `Sys.kt`/`SysLogic.kt` remote phone settings, `DevOpts.kt` dev options.
- Callers outside guard/: `remote/Api.kt` (LAN), `remote/Relay.kt` (relay), `remote/Acc.kt` (accessibility), `ui/MainActivity.kt` (child screen), `launcher/HomeActivity.kt` (reads `Guard.snap`).

Monitor only (`rules.monitor`): Guard.tick computes `would = decide(...)` then `want = enforced(r, would)` (Rules.kt); only `want` reaches suspendExactly (empty => unsuspends ours), Acc/bounce, `snap` (launcher) and the tile; `would` goes to `state.wouldBlock` + `logWould`. Warn.due is skipped.

### 1.1 Rules update applied

Trigger: parent saves rules (LAN `PUT /api/rules`) or relay sync returns newer rules.
- LAN: `remote/Api.kt:77` parse `Rules.fromJson` (Rules.kt:228); if body has neither `pin` nor `pinHash` copy old pinHash+salt (:80); `rev = max(old.rev+1, now)` (:81) -> `Guard.setRules` (Guard.kt:410). Reply = rules minus pinHash.
- Relay: `remote/Relay.kt:102-109` parse `res.rules`; keep relay's pinHash only if `pinAt > store.relayPinAt` else blank it (:107); apply only if `rules.rev > store.rules.rev` (strict, :109).
- `Guard.setRules` (Guard.kt:410-421): drop if `incoming.rev < old.rev` (:412) -> blank pinHash => keep old PIN (:414) -> `store.rules = r` (Store.kt:32, `toJson(persist=true)`, async `apply()`) -> `work.post { Policy.apply }` (:416) -> if `noDebug` newly on: `devOff()` (:417) -> log a diff line via `Events.ruleDiff` (:418) -> zones changed => `zoneBaseline` (:419) -> `kick()` (:420).
- `Store.rules` getter caches parsed Rules per raw string (Store.kt:24-31). Any reader (Guard, launcher, UI) sees new rules immediately.
- State: pref `rules` (JSON incl. pinHash/pinSalt). `rev` = ms of last edit.
- Gotchas:
  - `fromJson` is total replace: any field missing from the PUT resets to default (e.g. missing `hidden` => everything un-hidden next tick; missing `launcher`/`lockInfo` => cleared). Parent page must always send the full object.
  - Equal `rev` is accepted on `setRules` (`<` not `<=`), relay needs `>`.
  - Unknown/invalid values fall back silently to defaults (bed times, trackMin, unlockMin, windMin, safeDns). Lists capped silently (schedules 20, chores 20, zones 10, groups 10, 100 pkgs/group, 5 windows/group).

### 1.2 Tick (the loop) - order of work

`Guard.tick(force)` Guard.kt:143-218. Screen off and not forced => full pass at most once/min (:149).
1. `Acc.ensureEnabled` (re-adds accessibility entry w/ WRITE_SECURE_SETTINGS), `Policy.retryDns`, `Ts.poll`.
2. Track fix every `trackMin` (:160) -> `trackFix` (:574); `keepLocation` (:161).
3. Usage access check (:162): if revoked => log once + `tamper` event only.
4. App list `Policy.cached` (60 s cache, :152; `invalidate()` on pkg add/remove via `pkgReceiver` :80).
5. New-app bookkeeping `knownApps/loggedNew` (:169-178).
6. `dailyLeftMs` -> `Warn.due` heads-up (:181-184) -> wind-down gray (:186-187).
7. `decide()` (:188) -> `Quick.publish` -> `Policy.suspendExactly` (:192) -> `applyHidden` (:198).
8. Basic/owner bounce (:200-204), `goHome` if front app just got suspended (:206).
9. `locate()` every 15 min (:207), `recoverAt` pairing reopen (:208), hourly 7-day history (:212), `Update.maybe`, `launcherTick`, `Guard.snap` for the launcher (:215), build `lastState` (:217).

### 1.3 Foreground-app decision / block-reason priority

`decide(r, usageMs, minuteOfDay, nowMs, today, launchable, protected, newApps, unlockUntil)` Rules.kt:331 -> map pkg -> reason. Pure, unit-tested (DecideTest).
Order of writes (first writer wins, `p !in out`):
1. `blocked` (:337) - not for `protected`; ignores `allow`.
2. `appLimit` (:338) - `usageMs[p] >= appMin*60s`; ignores `allow`.
3. `groupHours` / `groupLimit` (:340-347) - per group: not in window => hours; else shared `min` sum => limit. Ignores `allow`.
4. `new` (:348) - only when `blockNew`, pkg in `newApps`, not allowed/approved.
5. Phone-wide reason for every launchable not `free` (`protected + allow`) and not already in out (:350-361): `paused` > `schedule` > `bedtime` > `daily`.
6. `unlockUntil > now` (:362): remove everything except `blocked` and `new`.
- Matches protocol.md:35 priority line.
- `protected` = Policy.protectedApps (Policy.kt:198): self, systemui, settings, phone/dialer/contacts, alarm clocks, gms/gsf, enabled keyboards, system HOME handlers + the user-chosen default HOME / dialer / SMS app.
- Consumers of the same function: Guard.tick (:188), `ui/MainActivity.kt:465`, `launcher/HomeActivity.kt:345` (fallback when no snap), `Quick` tile text.
- Enforcement of the result:
  - Owner: `Policy.suspendExactly` (Policy.kt:232) -> `setPackagesSuspended`; only `store.suspendedByUs` (pref `susp`, commit()) is ever un-suspended by the loop. System shows its own "paused by admin" dialog (text = `blockNote`, Policy.kt:78-85).
  - Owner but OS refused suspend (OEM apps): `Acc.bounceApp` HOME + notification (Guard.kt:204).
  - Basic: no suspend. `Acc.blocked = want` (:201); `Acc.basicGuard` bounces on every window change (Acc.kt:119-123, 1 s throttle `lastPoke`), tick bounces the current app (:202). `bounceApp` once per 10 s per pkg for the notification (Acc.kt:207).
  - `BasicLogic.NEVER` + `prot` are never bounced.

### 1.4 Daily limit / bonus / schedules / bedtime / pause

- Used time: `Usage.today` (Usage.kt:27) = sum over foreground intervals since local midnight. MOVE_TO_FOREGROUND opens, MOVE_TO_BACKGROUND/screen off/keyguard/shutdown closes (:46-55); a new foreground app closes all others (:49); still-open apps closed at `now` (:57); `current` = last open app.
- `daily`: `base = baseLimitMin` (Rules.kt:323: `dailyByDay[isoDow-1]` when size 7, else `dailyMin`); `limit = base + bonusMin` only if `bonusDay == today` (:357); `used` = sum of launchable, non-`free` apps (:358); over when `base > 0 && used >= limit` (:359). `dailyLeftMs` (:367) mirrors it (-1 = none).
- Bonus: parent page writes `bonus {day,min}` into rules (answer cmd only removes the request: Guard.kt:455-459). `today` = device local date `Guard.day()` (Guard.kt:700).
- `schedule`: `Schedule.active(minute, dow)` (Rules.kt:12): crossing-midnight window belongs to start day; second half checks previous ISO day. `AppGroup.hours` reuse it.
- `bedtime`: `Rules.inWindow` (:314), same every day (no day set).
- `paused`: `pausedUntil > now`.
- Exempt from the four phone-wide reasons: `protected + allow`.
- Heads-ups: `Warn.due` (Warn.kt:16) 5 min / 1 min before daily limit, 5 min before bed/schedule start, optional 10 min wind-down nudge; dedup keys `"<day>:<name>"` in pref `warned` (Store.kt:265); shown by `notifyWarn` (Guard.kt:400).
- Wind-down gray: `WindDown.active` (Comfort.kt:16) window `[bedStart-windMin, bedEnd)`; `GrayFx.tick` (GrayFx.kt:17) writes `accessibility_display_daltonizer*` (needs WRITE_SECURE_SETTINGS); old values saved in prefs `grayOwned/grayOn/grayMode` first (Store.kt:454-461) and restored when off/release.
- Request time: child `Store.addRequest` (max 5, Store.kt:370) -> parent answers -> rules `bonus` raised -> next tick.
- Gotchas: bonus ignored when `base == 0`; day boundary depends on child clock/timezone (owner pins auto-time, Policy.kt:56-63; basic mode does not).

### 1.5 App suspend / hide

- Suspend: loop only (Guard.kt:191-197). `want.keys` vs `store.suspendedByUs`; failures from DPM (returned array) are not recorded as suspended (Policy.kt:239-245). Logs `log_suspended/unsuspended`. `nowSusp` is persisted only when changed.
- `goHome()` (:592) when the front app was just suspended (accessibility HOME first, else startActivity).
- Hide ("Dọn máy"): `applyHidden` (Guard.kt:238) when `rules.hidden`/`hiddenByUs`/never-set changed or every 10 min. Owner => `setApplicationHidden`; non-owner => self-adb (falls back to `pm uninstall -k --user 0` when disable is refused, e.g. HyperOS) `pm disable-user` (`hideViaShell` Policy.kt:271, needs Wireless debugging). Refusals tracked in `refusedHide` (Debloat.refusedAfter). State prefs: `hid` (owner), `shellOff` (shell), `hideRefused`.
- Never-hide set = protected + self + Tailscale pkg (Policy.kt:250).

### 1.6 Location

- `keepLocation(r)` (Guard.kt:353): owner + `lockLocation` + location off => API30+ `setLocationEnabled(true)`; API<30 `setSecureSetting(LOCATION_MODE)` then WSS fallback. Runs every tick and instantly from `locObserver` (:367, ContentObserver on LOCATION_MODE/PROVIDERS_ALLOWED).
- Policy side: `applyNet` (Policy.kt:92) `lockLocation` => `setLocationEnabled(true)` + `DISALLOW_CONFIG_LOCATION`, else clear.
- Two independent location timers:
  - Pace (`guard/PowerLogic.kt` `LocPace`, 0.9.18): a good `trackFix` (acc <= 100 m) also becomes pref `loc`, and the 15-min `locate()` is skipped while those are fresh (`locateDue`). 2 same-spot fixes with the screen off + `TYPE_SIGNIFICANT_MOTION` armed (`Guard.pace`) = slow pace (history >= 15 min, locate hourly); the sensor firing or screen on = normal pace and a fix at once. No sensor = never slow.
  - `locate()` (Guard.kt:508) every 15 min and on `locate`/SOS/check-in commands -> pref `loc` (last fix) + zone events (only if fix < 10 min old, acc <= 300 m, :517, :293).
  - `trackFix(trackMin)` (:574) every `trackMin` (0 = off): one fix (30 s), fallback last-known < 2x interval -> `Store.addTrack` (Store.kt:310; keep 7 d, <= 2000 pts, stores if moved >= 30 m or gap elapsed, `Track.gapFor`). Pref `track`.
- `fixProviders` (:529): fused (API 31+) else network+gps together; listeners always removed (no leaked GPS).
- Zones: `Store.zoneFix` (Store.kt:440) in/out sets in pref `zin`; baseline after zone list change.

### 1.7 Device restrictions (Policy.apply) + release undo

`Policy.apply(ctx, r)` Policy.kt:45. Called: `Guard.onCreate` (:97), every `setRules` (:416), after self-update (Update.kt:182), release failure paths. NOT every tick (restrictions are persistent in DPM).
Non-owner: only `LauncherSafe.apply` (:46).
Owner adds:
- BASE: factory reset, safe boot, add user, config date/time (:38). INSTALL pair if `noInstall && !Update.installing` else cleared (:51). `DISALLOW_DEBUGGING_FEATURES` iff `noDebug` (:52).
- `setUninstallBlocked(self)`; API30+ `setUserControlDisabledPackages([self])` + `setAutoTimeEnabled(true)`; API<30 `DISALLOW_APPS_CONTROL` + `setAutoTimeRequired(true)` (:55-63).
- `applyNotes`: short/long support message + lock-screen owner info (:78).
- `applyNet`: location (see 6), `DISALLOW_AIRPLANE_MODE` (API 28+) iff `noAirplane`, Private DNS (`applyDoh` Chrome/Edge `DnsOverHttpsMode=off` while safeDns, `applyVpnLock` = only owner of `DISALLOW_CONFIG_VPN` for `noVpn` + Tailscale; `applyDns` :105: `safeDns` host -> restriction `DISALLOW_CONFIG_PRIVATE_DNS`; rejected => opportunistic + restriction cleared + `dnsError`). `retryDns` (:134) every tick: retry after rejection; 90 s non-validated network => opportunistic fallback for 30 min (restriction kept).
- Grants (:66-73): fine/coarse/background location, POST_NOTIFICATIONS, WRITE_EXTERNAL (<29), RECORD_AUDIO -> GRANTED.
- `LauncherSafe.apply` (HOME persistent preferred activity).
Also by others: Tailscale `DISALLOW_CONFIG_VPN` + uninstall block + suspend of TS pkg (remote/Ts.kt:306-308, undone Ts.kt:345-347).

#### release
Trigger: command `release` (relay: deliver-once; LAN: `POST /api/cmd`). `Guard.command` (Guard.kt:424) marks cmd id with `commit()` first (:428) -> `run` -> `release()` (:486).
- `Guard.release` (:486-505): on the work thread at front of queue: `stopped=true`, remove loop callbacks, stop Listen/Proj/Ring/wifi lock, `GrayFx.restore`, `Policy.release`. Result != "ok" => `stopped=false`, loop restarts, log `release_fail`. "ok" => log, `Files.wipeOutbox`, `stopSelf` after 2 s. Caller waits max 20 s (:501).
- `Policy.release` owner path (Policy.kt:417-465), in order:
  1. `Ts.undo`; unsuspend all launchable + `suspendedByUs`; `unhideAll`; `stuck` = still hidden => re-`apply`, re-arm watchdog, return `"failed: N apps still hidden"` (res string `release_stuck`).
  2. `enableShellDisabled`; `Update.forget`.
  3. Clear restrictions: BASE + INSTALL + DEBUGGING + APPS_CONTROL (:433); `setUninstallBlocked(false)`; `applyNet(Rules(lockLocation=false,noAirplane=false,safeDns=""))` (location, airplane, private DNS back); `setUserControlDisabledPackages([])` / `setAutoTimeRequired(false)`; support messages + lock info null.
  4. `removeAccEntry`; `NotifDots.setOn(false)`; `LauncherSafe.off(goHome)`; reset location/notif/storage grants to DEFAULT, RECORD_AUDIO DENIED then DEFAULT.
  5. `store.released = true` (commit), `Watchdog.cancel`, `clearDeviceOwnerApp`; zero `susp/hid/hideRefused`. If still owner => `released=false`, re-`apply`, re-schedule watchdog, return `"failed"`. Else `clearTokens`, `Quick.setEnabled(false)`, `"ok"`.
- Basic path `releaseBasic` (:469): `released=true`, clear `Acc.blocked`, cancel watchdog, `removeActiveAdmin` + wait <= 3 s, then clear acc entry, dots, launcher off, `Acc.disableSelf`, tokens, Quick off. "not owner" if no admin active.
- After release nothing restarts: `Guard.start` (:693), `Watchdog.schedule` (Watchdog.kt:25), `Quick` (:44), `LauncherSafe.wanted` (:32) check `Store.released`. `Admin.onEnabled` / `onProfileProvisioningComplete` reset it (Policy.kt:20,24).
- Not undone (by design?): `setAutoTimeEnabled(true)` (API30+), Sys settings the parent changed (volume/brightness/DND), Private DNS ends as opportunistic (not the user's earlier value), `rules` pref kept (re-provisioning reapplies old rules + PIN).

### 1.8 Watchdog / boot / restart

- `Boot` receiver (Guard.kt:40) on BOOT_COMPLETED + MY_PACKAGE_REPLACED: `Guard.start` + `Watchdog.schedule`; update also `LauncherSafe.afterUpdate`. No LOCKED_BOOT, no directBootAware => nothing runs before first unlock (DPM suspends/restrictions persist meanwhile).
- `Guard.start` (Guard.kt:691): no-op in HOME flavor and when `released`; `startForegroundService`, errors only logged.
- `Watchdog` (Watchdog.kt): `setAndAllowWhileIdle` ELAPSED_REALTIME_WAKEUP every 15 min (inexact, Doze max ~1 per 9 min); `onReceive` = `Guard.start` + re-arm. Also armed in `Guard.onCreate` and `Acc.onServiceConnected` (Acc.kt:42).
- Service restarts: `START_STICKY` (:114), `onTaskRemoved` -> `start` (:117), `Acc.onServiceConnected` after OS rebind, `Acc` SCREEN_ON/USER_PRESENT receiver, accessibility events -> `Guard.poke` -> `start` if not running (:698), Update/Admin callbacks.
- `onCreate` (:90): foreground notif, `Policy.apply` + `Quick.setEnabled` on work thread, content observers, pkg/time receivers, wifi lock, `Http` :8770, `Relay`, post loop.
- Time receiver: clock jump / timezone change => `tamper`-style `clock` event (:69-79, `Events.clockJumped`).
- Known kill mode: ColorOS Recents swipe = real force-stop (protocol.md:207-213): nothing of ours runs until the app is opened.

### 1.9 PIN / admin unlock / Authenticator code (Store.kt)

- PIN: hash in rules (`pinHash`,`pinSalt`); `Rules.hashPin` (Rules.kt:213) salted = 10 000 x sha256; legacy `sha256("kg:"+pin)`. Compare constant-time (:199).
- Lockout: `Store.checkParentPin` (:111) pref `pinFails/pinLock`; `Safety.pinLockMs` 5 free, then 5 min doubling (cap 64x).
- Pairing: `tryPair` (Store.kt:126): code (6 digits, open 10 min or while never paired) -> PIN check/set (`PairPin.decide`) -> token pending 10 min (`PendingPairs`) -> `commitPair` (:153) on first authenticated request: store token hash, `everPaired`, new PIN into rules, one `commit()`. 3rd wrong guess => new code + 30 s doubling block (`Safety.pairBlockMs`).
- Child "Bố mẹ quản lý trên máy này": `ui/MainActivity.kt:254` -> `Store.checkAdmin` (:171): TOTP first (6 digits), else PIN; wrong => `tamper` event (:189); returns 1/0/-1; then `ui/AdminActivity` session.
- Child unlock code (Authenticator): `tryUnlock` (:229) `Totp.check` (+-1 step, step > `totpLast`); ok => `unlockUntil = max(now, old) + unlockMin` (stacks), `unlock` event; 5 free tries then 5 min doubling (`unlockFails/unlockLock`), 5th wrong => `tamper`. Effect = decide() step 6.
- `totpSet` command (Guard.kt:474) stores secret in pref `totpSecret`, resets `unlockUntil`; secret never sent to parent.
- "Lost the link": child screen sets `recoverAt`; tick opens pairing after 24 h unless a PIN exists (Guard.kt:208-211).
- Recovery when the PIN is forgotten (verified in code): (a) a still-paired browser/app: "Quên PIN?" = `pinReset`, applies after 24 h with an alert to every parent, the old PIN cancels it (since 0.9.13 a rules PUT no longer replaces a set PIN; `CmdGate`, protocol "PIN-gated commands"); (b) Authenticator set: `checkAdmin` accepts its TOTP -> on-device admin page -> Settings -> new PIN (`MainActivity.askAdmin`); (c) no PIN ever set: "lost the link" (24 h, above). No PIN + no paired device + no Authenticator = no in-app recovery (only factory reset / adb); deliberately no new mechanism, it would also be a way in for the child.
- `openPairing` command refuses with "set PIN first" if ever paired and no PIN (Guard.kt:452).

### 1.10 Other commands in `Guard.run` (Guard.kt:436)

Dedup by id (`markCmd`, last 100, Store.kt:298). `lock` (DPM lockNow), `reboot`, `clearData`, `setting` -> `Sys.set`, `devOff/devOn` -> DevOpts, `relay` (relay URL/token), `unpair/unpairAll`, `answer`, `sosAck`, `update`, tailscale*, file*. `Sys`: volume via AudioManager; brightness/timeout via DPM.setSystemSetting (owner) else Settings.System; DND via NotificationManager (needs policy access); state cached 60 s (Sys.kt:36) and must read back or "not applied".

### 1.11 Basic mode (no Device Owner)

- `Basic.mode` (Basic.kt:25): owner | basic (admin active + accessibility on) | setup. `enforcing` = !owner && admin && !released.
- Blocking only by bounce (see 3). Self-protect: `BasicLogic.selfProtect` (BasicLogic.kt:72) on Settings-like packages scanned at 0.2/0.8/1.8 s (Acc.kt:128-135); needs both a "KidGuard" mention and a destructive control.
- Hide/clear only through self-adb shell; location lock, restrictions, suspend: not available. Checklist UI: the "Việc cần làm" card `SetupSteps` (`ui/SetupSteps.kt`, both modes).

### 1.12 Bank apps vs accessibility (`guard/AccPause.kt`)

- Why: Vietnamese bank / wallet apps refuse to run while ANY accessibility service is on. Rule `accPause` = package list (default `AccPauseLogic.DEFAULT`).
- Trigger: the bank comes to the front (`AccPause.tick(use.current)` every tick, or `pauseLate` from the Acc window event).
- Steps: Home (global action) -> our service really turns off (`disableSelf`) -> kill the bank's background process -> reopen it from the background (its notification's own intent if it was opened from one, `NotifLog.recentClick`, else the launch intent).
- Debugging too (2026-11-11, My MobiFone error 50052): the same pause snapshots Developer options / USB / wireless debugging (`Store.devPaused` mask, `DevOpts.bankPause`), turns them off, and the resume (or release via `AccPause.clear`) puts back exactly what was on, unless `noDebug` / DISALLOW_DEBUGGING_FEATURES came meanwhile (`DevOpts.bankResume`). Device Owner without WRITE_SECURE_SETTINGS can only restore USB debugging. adb drops while a bank is open (wireless `adb tcpip` port survives the adbd restart until reboot; Wireless debugging gets a new port).
- List from the website (2026-11-11, owner): `BankList.kt` reads `https://kid-guard.pages.dev/data/bank-apps.json` (`website/data/bank-apps.json`) once a day + the bundled `AccPauseLogic.DEFAULT` at start; adds only packages never offered before (`Store.bankSeen`, seed = 0.9.12 list) to `rules.accPause`, so a parent removal sticks; `drop` removes only list-offered ones. A blocked app never pauses accessibility (AccPause.pause), so the list cannot open a bypass. New bank app = edit the JSON + Pages deploy, no release.
- VPN too, per app (2026-11-11, Cake "thiết bị có dấu hiệu đang bật VPN"): rule `vpnPause` is its own list (page: one row per app, Trợ năng / VPN boxes). `VpnPause.kt`: on open, owner mode snapshots + lifts always-on VPN, Tailscale gets DISCONNECT_VPN; `Ts.keep` and the Ts policy re-assert skip while paused; launcher starts the app 0.9 s later after a fresh pause, Acc restarts it (AccPause.relaunch) when it came up another way. Resume on the same timer as acc: always-on back + CONNECT_VPN. Release: CONNECT_VPN only (always-on was ours, Ts.undo drops it). Basic mode + always-on set in Settings, or a non-Tailscale VPN: cannot, state `vpnStuck` -> page alert.
- Resume: with WRITE_SECURE_SETTINGS the service comes back once no bank was in front for 10 min (Device Owner) / 1 min (basic, since basic mode blocks through the service).
- Gotchas: basic mode needs "display over other apps" for the reopen (`canReopen`; `grantOverlay` tries every 6 h). Release clears the pause (`AccPause.clear`).

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

### 1.15 Lost mode card escape (0.9.16, `Guard.lockForLost`, `MessageActivity`)
- Trigger: rule `lostOn` (+ `lostMsg`, `lostPhone`) from page / parent app / Telegram `/lost`.
- Steps: on `USER_PRESENT` (not SCREEN_ON) show the card (message, call-back number, "Parent: turn off"); Device Owner `lockNow` again after `LOST_CARD_MS` (20 s). A right PIN / Authenticator code on the card sets `lostGraceUntil` (10 min): no relock, so a parent holding an offline phone can end lost mode on the page. `lostTick` locates every 5 min and loops the ring; a SIM change raises an event.
- Gotchas: Basic mode shows the card only (no `lockNow`); `lostOn` wins over `monitor` in `nowWhy`.

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
- Delivery: `sync` reply `cmds` = all non-acked, <24 h old. `ONCE` list (`relay/src/index.js` `ONCE`: destructive commands) is handed out until acked but only within `ONCE_WINDOW` (10 min) of the first hand-out; others stay until the phone's next sync carries `acks`. `SHORT` commands ("now" actions) go stale after `SHORT_TTL` (10 min), the rest after 24 h.
- Phone: `Relay.kt:111-117`: `Guard.command` for each (try/catch, ack always), `markCmd(id)` (`Store.kt:298`, commit(), last 100 ids) skips repeats but answers "ok". Result of a relay command is not returned to the page: it shows up in state/`log` (`Command: x -> res`).
- LAN-only on purpose: `relay`, `tailscale` (secret key must not sit in the queue). Contract test enforces whitelist parity: `relay/test/contract.test.mjs`.
- Wire: protocol "Commands" (+ "Action states", "Remote phone settings", "Self-update", "Shared files").
- Gotchas:
  - Non-`SHORT` commands live up to 24 h; `lock`/`ring`/`wake`/`message`/`setting`/`devOff` are `SHORT` (10 min), so they no longer fire the next day.
  - ONCE commands are re-delivered until acked (within `ONCE_WINDOW`); `Relay.sync` applies `rules` inside its own `runCatching`, so a bad rules reply cannot stop a delivered `release`.
  - `markCmd` strips `.` from ids; for `unpair`/`answer` the worker reuses the body's `id` (device/request id) as command id, so a re-sent `answer` for the same request id is deduped ("ok") even if the first failed.
  - LAN commands carry no id unless the page sets one (`setting` does, `tab-set.js:204`).

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
- Listen (LAN/Tailscale only): `earStart` `UI/shell.js:262` (refuses relay: `ear.lanOnly`) sets `<audio>` / MediaSource to `GET /api/listen?k=<token>` -> `Api.kt:108` -> `Listen.open` (`remote/Listen.kt:107`): conflict check vs ring, RECORD_AUDIO, notice-visible check, FGS mic, beep + ongoing notification (always), MediaRecorder AAC_ADTS into a pipe; `Session.pump` streams; watchdog ends on silence/stall/10 min. Reply is a `stream` response (`Http.kt:83-88`; pool thread held for the whole session). Stop: page closes player + `cmd listenStop` (`shell.js:317-321, 408`), `Listen.stopAll`. App mode: `Proxy.listen` passthrough (`Proxy.kt:156`).
- Ring: `cmd ring` (`tab-home.js:58`) -> `Ring.start` (`remote/Ring.kt:38`): 20 s alarm at max alarm volume, heads-up notice with stop; refuses `listening`. `ringStop` is idempotent (`shell.js:398`). Active state is read from `state.ring` / `state.listen`, never guessed (`shell.js` actPaint).
- Locate: `cmd locate` (`tab-home.js:59`, `tab-where.js:256`) -> `Guard.locate()` (`Guard.kt:~482`): last-known fixes + one fresh fix (60 s), writes `store.location`, zone events, `relay?.kick()`. Result appears in `state.location`; track via `GET /api/track?days=` (LAN, `Api.kt:105`; relay has no track route: history rides in state).
- Wire: protocol "Action states (v0.9.6)", "SOS and listen around (v0.5)", "Location history (v0.2.1)".
- Gotchas: relay `ring`/`locate` have no immediate result; `listen` has no relay route at all (docs say so; code enforces in the page only).

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

### 2.10 Parent app specifics (P/)
- `MainActivity.kt` loads `/s/<secret>`; JS bridge `KGApp` (protocol "Parent app"); `Store.kt` holds kid list; `CheckJob.kt` ~15 min job fetches each kid's state, notifies on new `requests`, `sos`, lost contact; `Act.kt` notification actions replay the page's flows natively (GET state, PUT rules `bonus`, `answer`); `Push.kt`/`Notif.kt` FCM data messages from the worker (`pushNew` `index.js:366`, `POST /c/<id>/push` registers token, optional secret `FCM_SA`).
- Proxy forwards only `X-KG-Token, X-KG-W, X-KG-H, Content-Type` (`Proxy.kt:100`).

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

## 3. Kid launcher

Base: `android/app/src/main/kotlin/dev/kidguard/launcher/` (abbrev `L/`). All files `package dev.kidguard`. Line numbers are
as read on 2026-11-09 (HomeActivity.kt = `HA`). Not read in detail: HomeWidgetsLive/Lists/Plan/Kid bodies, HomeFolder body,
HomeLibrary/HomeControl bodies (only signatures), Wallpaper draw(), PageDots, KidNotes, TimetableLogic, WidgetDraw.

### 3.0 Where is X

| Feature | Where |
|---|---|
| Activity state, lifecycle | `L/HomeActivity.kt` onCreate 162, onResume 241, onNewIntent 288, onBackPressed 300 |
| Installed app list | `HA.launcherApps()` 311 (queryIntentActivities LAUNCHER, skip HomeEntry, `labelCache`) |
| Show / pause / hide decision | `HA.decision()` 338 + `LauncherLogic.show()` (L/LauncherLogic.kt) |
| Render pipeline | `HA.render` 432, `render0` 445-516, `buildPages` 632-724 |
| Wallpaper bitmap | `HA.ensureWall` 518, `Wallpaper.draw/photo/blur/dim` (L/custom/Wallpaper.kt), `WallSurface` HA:1446 |
| Palette / presets / accents / walls | `L/custom/HomeStyle.kt` (PRESETS 121, ACCENTS 83, WALLS 128, `palette` 100, `ink` 138) |
| Icon bitmaps (mask, grey) | `L/IconCache.kt` `get` 36 (LRU = heap/5, 8-96 MB), `greyPaint` |
| Launch app | `L/HomeIcons.kt` `launch` (~165), `debounce`, `reopenShort/When` |
| Pure layout model | `L/HomeLayout.kt` (fresh, parse, place, drop*, group, moveTo, removeIds, removePage, resizeWidget, regrid, fitRows, placeAppWidget*) |
| Persistence | `L/HomeFile.kt` (filesDir/home.json, tmp+rename, `.bad`) |
| Pager (pages, swipe) | `HA.Pager` 1139-1239; `CellLayout` 1242+ |
| Drag and drop | `L/HomeDrag.kt` startDrag, hover, drop 321, landNow 356, endDrag 397, dockTarget, folderPlate |
| Long-press menus | `L/HomeMenus.kt` menu, appMenu, emptyMenu, pausedSheet, update 135, homeSettings |
| Edit mode | `L/HomeEdit.kt` enterEdit, exitEdit, editBars, removeOne |
| Dock, SOS, bars | `L/HomeDock.kt` buildDock, sosMenu 87, inkPass |
| Drawer + A-Z rail | `HA.Drawer` 804-1065 (refill 930, rail touch 903, scrubTo 1013, follow 1030); open/close in HomeDock.kt |
| Swipe up/down on home | `HA.SwipeUp` 1073; `expandShade` 1129; `openControl` L/control/HomeControl.kt 206 |
| Folders | `L/HomeMenus.kt` openFolder/folderSheet/renameFolder/addToFolderSheet; `L/HomeFolder.kt` (FolderGrid, folderDragBegin/Evt, folderZoom) |
| App Library page | `L/HomeLibrary.kt` (`LibraryLogic.groups`, libraryOut/libraryBack/libraryFill) |
| Today page | `L/today/TodayPage.kt` |
| Own widgets | `L/widgets/HomeWidgets.kt` (design system at top, SIZES), `HomeWidgetsLive/Lists/Plan/Kid` |
| Other apps' widgets | `L/widgets/HomeAppWidgets.kt` (awAdd, awConfigure, awPlace, awSync, appWidget) |
| Widget picker | `HomeWidgets.kt` openPicker, `awPickerSection` |
| Notification dots | `L/NotifDots.kt` (listener), `Dots.refresh`, `DotHost` interface in icons |
| Customization screen | `L/custom/CustomActivity.kt` build 71-176, set 188, regrid 197, applyPreset 208, pick/savePhoto 420+ |
| Recent apps row | `L/Recent.kt` (usage stats, else local list of 12) |
| Control center | `L/control/HomeControl.kt` (`CcSys`, `ControlLogic.mode`, toggles) |
| Safety / crash fallback | `L/LauncherSafe.kt` wanted, apply/on/off, uncaught handler, safe mode |
| Flavor switch | `L/Flavor.kt` (HOME = BuildConfig.HOME), `L/HomeEntry.kt`, `src/home/AndroidManifest.xml` |
| Kid-to-Home layout import | `L/custom/HomeExport.kt` (provider "layout"/"wall", same-signer check) |

### 3.1 Start / render / page structure

Trigger: HOME press, boot, `onNewIntent`, rule change, package change, minute tick.
1. `onCreate` HA:162: loads `HomeFile`, builds root (WallSurface, Pager, dock, hint, drawer prebuilt), `pager.jump(1)`.
2. `onResume` HA:241: registers `timeRx` (minute) and `pkgRx` (package add/remove); `NotifDots.setOn(style.badge != "off")`;
   applies `LauncherSafe`.
3. `render(force)` HA:432 -> `render0` 445: builds key string `k` (model JSON, labels, show map, want map, block, banner,
   timeKey, rules...). `!force && k==key` -> only `tickClocks`. `busy()` (drag/anim) -> retry in 1500 ms (HA:492).
4. `buildPages` 632: `PageBuilt` signature per page; `pageGlobal` + `reusePages` reuse built pages on layout-only edits.
5. Today page filled on idle (`addIdleHandler`, HA:505-510) unless it is the current page; drawer prebuilt at HA:514.
Page structure (pager children): [0] TodayPage, 1..n `CellLayout` grids, last = `LibraryPage` if `style.lib`.
`pager.minPage` = 0, or 1 when `style.today` is off (child 0 still exists so numbers do not move).
State/persistence: `home.json` (see docs/protocol.md "home.json"). Atomic write, in-memory cache, bad parse -> `.bad` + fresh.
Gotchas: banners cached 20 s (`bannerNow` HA:366); `render` must not run while `busy()`; auto rows (`fitRows`->`autoRows`->`update(regrid)`).
Paging: Pager.settle fling or 30 % width; rubber band 1/3 past ends; long press on empty space -> `emptyMenu` (HA:1151).

### 3.2 App list refresh (install / uninstall / blocked)

Trigger: package broadcast, rule push, time tick, `Policy.invalidate()`.
1. `pkgRx` (HA onResume): `apps = null; Policy.invalidate()`, debounced `pkgLater` 400 ms -> `render(true)`.
2. `launcherApps()` HA:311 re-queries PackageManager; label cache; category + system flags (for the Library).
3. `decision(pkg)` HA:338: uses `Guard.snap` when < 30 s old, else runs `decide()` locally.
4. `LauncherLogic.show()`: HIDE for plumbing, `r.hidden`, `r.blocked`, reason in HIDE_REASONS (blocked/new), or settings app while `!settingsOk`.
   PAUSED for any other `want[pkg]` reason (grey + clock badge, tap -> `pausedSheet`). `pkg==self` always OK.
5. `HomeLayout.appendNew` places genuinely new apps on the last page; `prune` drops uninstalled ones; `seen` = placed or
   removed apps, never auto re-added (kid removing an app from home keeps it in drawer only).
Gotchas: `settingsOk = r.launcher.settings || (!owner && !paired)`; new app is "new" (hidden) until parent allows (rule `blocked/new`).

### 3.3 Launching an app

Trigger: tap on icon (grid, dock, drawer, folder, library).
1. `HomeIcons.launch` (~165): `show()` != OK handled before (PAUSED -> `pausedSheet`).
2. `debounce` 600 ms; `Recent.note(pkg)`.
3. Explicit `ComponentName` intent, flags `NEW_TASK|RESET_TASK_IF_NEEDED`, `sourceBounds`.
4. Animation: `ActivityOptions.makeClipRevealAnimation` from the icon view (system zoom). Fallback `getLaunchIntentForPackage`, else toast.
5. HyperOS needs the home window to show wallpaper (SHOW_WALLPAPER in both theme files) or it slides (memory file).
Gotchas: `reopenShort/When` handles apps that re-open the launcher; Home flavor's system apps open the same way.

### 3.4 Drag and drop / page flip / resize widget

Trigger: long press on item (`arm()`), then move 8 dp; `immediate` for folder panel and picker.
1. `arm()` long press -> `menu`; moving past 8 dp -> `startDrag` -> `startDragAndDrop(DragSt)`.
2. `onDrag` -> `hover` (HomeDrag.kt) -> `drop` 321 -> `endDrag` 397 -> render -> `landNow` 356 springs item into its cell.
3. Edge flip: 48 dp zone, 400 ms dwell, creates `tempPage` past the last page (removed if nothing dropped).
4. Folder-on-drop: `FOLDER_MS`=350 and `nearCentre` (<0.36 width); swap preview after `SWAP_MS`=220.
5. Remove: magnet circle (pull 170 dp, hot zone 64 dp) -> `HomeLayout.dropRemove`.
6. Dock: `dockTarget`/`gapAt`; `dockRoom` decides insert vs replace; slot 0 fixed `@phone`; DOCK=4.
7. Pure rules: `dropCell`, `dropMove`, `dropOnto`, `dropDock`, `insertDock`; limits cols 3..5, rows 4..7, <=20 pages, <=60 items/page, FOLDER_MAX 20.
8. Resize widget: `HomeMenus` widget menu -> `HomeLayout.resizeWidget` (grows in place + reflow, test added; see goal.md). Allowed sizes from `HomeWidgets.SIZES`.
State: only `home.json` on drop. Gotchas: `busy()` blocks render during drag; `ACTION_DRAG_ENDED` must run `endDrag` once.

### 3.5 Edit mode (multi-select)

Trigger: menu "Sắp xếp" / long press empty space.
1. `enterEdit(pre)` (HomeEdit.kt): pager scaled 0.86, dock/hint hidden, `picked` set, `editBars` top "Đã chọn N / Xong".
2. `CellLayout.onInterceptTouchEvent` HA:1261: while `editing`, a quick tap picks the item under it; the minus badge -> `removeOne(id)` (confirm modal).
3. Nothing picked: select-all, delete page (`removePage`), widget picker. Picked: none, group (>=2 apps -> `group`), move (`moveTo`), remove (`removeIds`).
4. `exitEdit` on Back, Home, onPause.
Gotchas: the Library page is taken out of the pager during edit (`libraryOut`), put back by `libraryBack`.

### 3.6 Folders

Trigger: drop app on app (dwell 350 ms centre), edit-mode group, folder tap.
1. `HomeLayout.dropOnto` / `group` create `HItem(kind=folder, apps)` (FOLDER_MAX 20; `folderKind`).
2. `openFolder` (HomeMenus) needs `lastView`; zoom via `folderZoom`; `folderSheet` shows a `FolderGrid`; drag out via `folderDragBegin/Evt`.
3. `renameFolder`, `autoName` (name from app categories), `addToFolderSheet`.
4. Icon: `HomeIcons.folderIcon` (mini preview, dot sum via `DotHost`).
State: inside `home.json` item `apps`. Gotchas: `openFolder` returns silently if `lastView == null`.

### 3.7 Drawer search and A-Z rail

Trigger: swipe up on a home page (`SwipeUp` HA:1073), dock handle, Home button when on page 1.
1. Drawer inner class HA:804-1065 is kept (`drawerCache`) and prebuilt at HA:514.
2. Open/close follows finger: `drawerAt`/`drawerRelease`, opens at 40 %, closes at 60 % (`drawerOpens`).
3. `refill` HA:930: builds rows lazily per frame; no query -> "Dùng gần đây" row (`Recent.load`: usage stats 3 days, else last 12 opened here).
4. Search: accent-folded (`LauncherLogic.fold`/`matchesFolded`), Vietnamese Đ separate letter.
5. Rail: letter from `LauncherLogic.letter` (digits -> "#", other -> "..." bucket); touch HA:903; `scrubTo` 1013 lights one letter, dims others; `follow` 1030 keeps the list in sync.
6. Pull down closes; keyboard via InputMethodManager.
Swipe down on home: x0 > width/2 -> `openControl()` (kid control center), else `expandShade(false)` (reflection on StatusBarManager, HA:1129).
Gotchas: `Recent.load` is a binder call; must run off main thread (note in Recent.kt) - verify callers.

- ⋮ next to the search field -> menu "Hidden apps" -> `hiddenScreen()` (`L/HomeHidden.kt`): an overlay grid of every app the drawer leaves out with its reason (`LauncherLogic.hiddenReason`, unit-tested, same order as `show()` + study mode): parent-hidden / blocked / waiting for OK / study time / KidGuard-managed Tailscale. Plumbing (Play services & co.) is one count line. A tap only toasts the reason: nothing launches from there (it would bypass the rules). Our own `HomeEntry` is skipped by package, not class name (the Home flavor shares the class).

### 3.8 Today page ("Hôm nay")

`L/today/TodayPage.kt`; rebuilt on idle (HA:505-510). Cards (hidden ones in `HomeModel.todayHide`, "Tuỳ chọn thẻ" modal):
- header: call + SOS (never hideable; absent in Home flavor)
- `now`: greeting, current/next school period, countdown to next lock/bedtime
- `time`: screen time left + top 3 apps; empty: "no apps used, no limit" text (no action: limits are the parent's)
- `sched`: from `LauncherLogic.today` (parent schedules, group hours, bedtime); empty: text + link to the kid's timetable (`openTimetable`)
- `chores`: parent chores + kid's own homework todos
- `tomorrow`: only in the evening (`TimetableLogic.eveningFirst`); empty: text + "Tạo thời khoá biểu" -> `openTimetable(tomorrow)`
- `notes`: kid notes (share-with-parent flag; hidden switch in Home)
- `files`: parent->kid files + kid's outbox; hidden when empty AND never paired (no one to share with)
Rule (0c95d99): cards show an empty state with a way to fill them, never vanish; data the parent owns gets explanatory text, not a create button.
Home flavor: only now, sched, tomorrow, notes (HOME_CARDS).

#### 3.8b Kid-side asks (0.9.16, `K/ui/KidActions.kt`, `KidNow.kt`)
- One open request (`Store.openRequest`): the block sheet (`HomeMenus.pausedSheet`) and "Xin thêm giờ" show "Đã xin lúc HH:mm, đang chờ bố mẹ" + "Nhắc lại" (`addRequest` -> new / remind / wait toast); the parent's last answer (`Store.lastAnswer`) stays on the sheet. Blocked / new app: "Xin mở <app>" = kind `app` with pkg + label.
- "+1 phút" on the sheet: `OneMore.allowed(rules, nowWhy, store.oneMoreKey)` -> `Guard.oneMore()`. No Ask when `nowWhy` is lost / paused.
- SOS: unpaired -> modal "calls 112"; paired + offline -> "Chưa có mạng" + "Gọi bố mẹ" (`KidNow.parentNumber`: `lostPhone`, else a number in the lock-screen contact); online -> "Đã gửi".
- Basic mode: `K/ui/BlockCard.kt` is the block card (reason / reopen time / Ask / OK). `Acc.bounceApp` should call `BlockCard.show(service, label, pkg, code)` before posting its notification.
- Study mode: 10 s "Hoàn tác" under the chip after a hand start (no PIN); `gate()` takes title + button.

#### 3.8c Kid "Tuần này của bạn" (0.9.17, `L/today/KidWins.kt`)
- Trigger: Today rebuild. Steps: days within the daily limit + current run from `Usage.range` over 7 days (off the UI thread), list-finished days from `StreakStore`; card is hidden when there is nothing to celebrate and in the Home flavor.
- Gotchas: no stored history (OEM usage retention varies); chores done and bedtime kept are not counted (no record).

### 3.9 Widgets

Own: `Flavor.widget(type)` filters; types clock, analog, month, time, next, quick, todo, note, counts, usage, battery, lock, when,
earn, msg, class, timer, streak (+ "app" for host widgets). Picker `openPicker`: one "KidGuard" card, 2-col tiles + size pills, drag via `Src.NewWidget`,
lazy "other apps" section (`awPickerSection`). Home flavor allows clock, analog, month, battery, todo, note, class.
Other apps': `HomeAppWidgets.kt`: `awAdd` allocates id (host id `AW_HOST_ID` 0x4b47) -> `bindAppWidgetIdIfAllowed`, else ACTION_APPWIDGET_BIND
(REQ_BIND 4701) -> `awConfigure` (REQ_CONF 4702) -> `awPlace` (`HomeLayout.placeAppWidget`). `awSync` deletes ids that left the model.
`appWidget()` shows a locked card if the host app is PAUSED/HIDE, a "gone" card if the provider vanished. Sizes from provider min/resize.
Gotchas: pending bind id lives only in memory (`aw.pendingId`).

### 3.10 Notification dots

`L/NotifDots.kt`: `NotificationListenerService` counting per package non-ongoing, non-group-summary, non-FGS notifications whose ranking
allows badges; counts only, nothing stored. `onChange` -> `Dots.refresh` -> icons (apps; folders = sum). `setOn` on resume from
`style.badge` ("off"/"dot"/"num"). Access is granted at setup (`cmd notification allow_listener dev.kidguard/dev.kidguard.NotifDots`,
docs/protocol.md:255); `CustomActivity` HA shows a "need access" note + opens system listener settings when missing (CustomActivity.kt:128).

### 3.11 Customization screen (CustomActivity)

Trigger: menu "Tuỳ chỉnh". Same task, not in recents; reloads home on resume if `HomeFile.load` differs.
1. `build()` CustomActivity.kt:71 rebuilds only ScrollView content (no scroll jump).
2. Sections: live preview, saved layouts (`L/custom/HomeSaved.kt`: up to 5 named home copies in filesDir/layouts/, tap = switch via `HomeBackup.apply`, one undo; presets removed 2026-11-11), grid cols stepper, icon size, labels, font face, shape, dock bg/labels,
   Today on/off, Library on/off, fx (full/lite/auto), dots (off/dot/num), wallpaper (photo + 13 built-ins), blur + dim sliders,
   accent, theme, text size, reset.
3. `set(f)` 188 -> `guard()` -> `save()` = `HomeFile.save` + `IconCache.clear`; `regrid` 197 offers Undo for a smaller grid; `applyPreset` 208.
4. Photo: `pick()` (API 33 PICK_IMAGES else OPEN_DOCUMENT) -> `savePhoto` on a thread: EXIF rotate, downscale to <=1080 wide, centre-crop,
   JPEG <=300 KB to filesDir/wall.jpg (tmp+rename) -> `style.wallKind="photo"`.
5. Locked (`rules.launcher.lockCustom`): read-only with note; `guard()` toasts. Lock hint hidden in Home flavor.

### 3.12 Parent `launcher.*` rules

| Rule | Effect | Where |
|---|---|---|
| `on` | enables HomeActivity component + persistent preferred HOME (owner) | LauncherSafe.wanted/apply 34-45 |
| `settings` | shows Settings app (+ control center tiles open system pages) | HA `settingsOk` 390, LauncherLogic.show |
| `notes` | blockNote shown in pausedSheet | HomeMenus.kt:39 |
| `lockCustom` | CustomActivity read-only | CustomActivity.kt:47 |
| `style` | preset only for first `home.json` | HA `firstModel` 397-405 |
| `shade` | `LauncherSafe.shadeBlocked`; Acc closes quick settings | Acc.kt:50,76; HomeControl.kt:300,350 |
State to parent: `LauncherSafe.state()` (`on`, `home`, `err`, `style`, `locked`, `pages`, `safeUntil`) LauncherSafe.kt:208-211.
Crash fallback: uncaught handler counts while `HomeActivity.alive>0`; 2 in 5 min = layout reset (`HomeFile.reset`, `.bad` kept); 3 = safe mode 24 h + off.
`afterUpdate`/`freshStart` clear counters and restore `.bad`.

### 3.13 Kid vs Home flavor

| Aspect | kid (`dev.kidguard`) | home (`dev.kidguard.home`) |
|---|---|---|
| Rules | parent `Rules` | none; `settingsOk` always true; nothing blocked |
| `LauncherSafe.wanted` | rule/local opt-in | always true; `HomeEntry` re-enables + starts |
| Header | call + SOS | none |
| Banner | owner > usage > location > acc > guard | only "home" banner (`homeIsOurs`) |
| Widgets | all | clock, analog, month, battery, todo, note, class, app |
| Today cards | all | now, sched, tomorrow, notes |
| Fresh layout | preset by rule | 2-row clock only (`fresh(parent=false)`) |
| Control center | shade rule | `shadeOff()` false (HomeControl.kt:350) |
| Extras | | hides itself when kid launcher is on (`setApplicationHidden`); imports layout/wall via `HomeExport` |

## 4. Setup, build, release

### 4.0 Facts
- Version: `android/gradle.properties` `kg.version` + `kg.code` (one place, both apps; `app/build.gradle.kts:15-16`, `parent/build.gradle.kts:32-33`).
- Package `dev.kidguard`; Device Owner admin `dev.kidguard/.Admin`; accessibility `dev.kidguard/dev.kidguard.Acc`; LAN port 8770.
- Flavors (`app/build.gradle.kts:21-25`): `kid` (`dev.kidguard`, kidguard.apk), `home` (`dev.kidguard.home`, kidguard-home.apk, launcher only, no libadb native libs `:64-69`). Parent app = `:parent` (`dev.kidguard.parent`, kidguard-parent.apk).
- One signing key `android/app/signing.jks` (git-ignored, pw hardcoded in both build.gradle files, `app/build.gradle.kts:26-33`, `parent/build.gradle.kts:38`). Same key = self-update works, QR checksum valid, HomeExport provider trusts Home.
- Repo `HoangTran0410/kidguard` (origin). Site `kid-guard.pages.dev`. APK URLs always `releases/latest/download/<fixed name>`.

### 4.1 SETUP flows (all end in: KidGuard is Device Owner + 4 grants + accessibility on)
Contract: `docs/protocol.md:248-265` "Setup steps" (9 items). AGENTS.md says setup.sh, setup.ps1, install.html, SelfSetup.kt must stay in sync.

First run on the child app (`SetupSteps.chooser`): "Đầy đủ (khuyên dùng)" -> SelfSetupActivity, "Cơ bản" -> the checklist (flag `setup_ask/firstChoice`). Setup end (SelfSetup done, or the checklist complete = last row "Ghép máy bố mẹ") opens the pairing code + QR for 10 min (`Store.codeRevealUntil`). After release (`Store.released`) MainActivity shows one card (`rel_title`).

#### 1a. QR provisioning (factory-reset phone, setup wizard)
- Trigger: parent opens `/qr` (worker `relay/src/index.js:81` serves `ui/qr.html`; also static in repo `ui/qr.html`), fills Wi-Fi + APK URL, child taps 6x on the welcome screen and scans.
- `ui/qr.html:84` `DEF = /*KG*/{component, checksum, apkUrl}/*KG*/` (inlined by build.sh); `:92-93` builds `PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION` + `..._SIGNATURE_CHECKSUM`; `:126-130` validates https + checksum present.
- Phone side: `setup/Provisioning.kt:20-28` `ProvisioningModeActivity` answers FULLY_MANAGED_DEVICE; `:31-38` `PolicyComplianceActivity` returns OK. Manifest `AndroidManifest.xml:204-216` (GET_PROVISIONING_MODE, ADMIN_POLICY_COMPLIANCE).
- Wizard installs APK from URL, verifies cert SHA-256 (url-safe base64), sets Device Owner. NO adb steps run, so no usage-stats / WRITE_SECURE_SETTINGS / accessibility grants.
- After that: the "Việc cần làm" card (`ui/SetupSteps.kt`) on MainActivity walks the child through "restricted settings" (API 33+), Accessibility, Usage access, battery, OEM auto-start. "Secure settings" yes/no is a row in "Bảo vệ và quyền" (MainActivity.refresh).
- Gotchas: checksum is tied to signing.jks (AGENTS.md); `ui/provision.json` is rewritten by build.sh; QR needs Android 10+ wizard flow ("QR 6 taps"); apkUrl must be https and fetched by the wizard (GitHub latest redirect).

#### 1b. scripts/setup.sh (macOS/Linux) and scripts/setup.ps1 (Windows), USB adb
- Trigger: user runs `./setup.sh [-y] [apk]` with USB debugging on, phone accounts removed (app accounts stay: step 1b below). Several devices: `ANDROID_SERIAL=<serial>`.
- setup.sh steps: APK pick `:18-34` (local `kidguard.apk`, `../build/kidguard.apk`, else download latest + sha256 verify); adb or offer to download platform-tools `:37-55`; exactly 1 device `:57-58`; confirm model `:60-67`; `chk()` treats output matching Error|Exception|denied|Failure as failure `:70-81`.
  [1] `adb install -r -g` `:84`; [2] `dpm set-device-owner` + verify via dumpsys `:97-98`; [3] GET_USAGE_STATS `:112`; [4] WRITE_SECURE_SETTINGS `:115` + optional WRITE_SETTINGS, allow_dnd, allow_listener NotifDots `:117-119`; [5] accessibility merge `:122-131`; [6] deviceidle whitelist `:134`; [7] `am start MainActivity` `:137`; [8] print LAN URL `:141-144`; ColorOS hint `:147-148`.
- setup.ps1 same order: install `:93`, owner `:105`, grants `:120-127`, a11y `:130-138`, idle `:141`, start `:144`, ColorOS `:155`.
- Step 1b (both scripts, after install): `dumpsys account` -> keepable app authenticators (`pm list packages -3`) -> `pm disable-user`, wait 14 s, `sync`, `adb reboot`, wait boot, `set-device-owner` retried up to 2 min ("accounts" lingers ~30 s after boot), then ALWAYS `pm enable` (EXIT trap / `Fail` + `apps-off.txt` for a crash). Phone (system-authenticator) accounts: stop + message. Tested on an Android 14 AVD (setup.sh); setup.ps1 not run.
- Gotchas: needs phone accounts (system authenticators) removed (DO restriction); the reboot sooner than ~10 s after `pm disable-user` loses the state; Xiaomi needs "Install via USB" + "USB debugging (Security settings)"; ColorOS needs "Disable permission monitoring" (hint printed on error); INSTALL_FAILED_UPDATE_INCOMPATIBLE = different signature (setup.sh:87).

#### 1c. WebUSB installer `ui/install.html` (Chrome/Edge, no tools)
- Trigger: `/install` on the worker (`relay/src/index.js:80`) or open file. Tango ADB bundled inline (`install.html:132-136`; rebuild `ui/vendor/build.sh`).
- `:162-190` connect; `:199-226` `loadApk`: tries `fetch("kidguard.apk")` same-origin, else file picker (`isApk` PK check). SHA-256 checked against `kidguard.apk.sha256` (fail closed) and package `dev.kidguard` checked in the picked file (`isKidGuardApk`); the worker has no `/kidguard.apk` route yet, so on the relay it always falls to the picker (GitHub release downloads send no CORS headers).
- `:233-` `sh`/`must` same error regex; steps at ~`:262-310`: `pm install -r -g /data/local/tmp/kidguard.apk` (push via sync, then rm), `dpm set-device-owner`, usage stats, secure settings + optional trio, accessibility merge, deviceidle, `am start`, LAN IP via wlan0 / route, ColorOS check.
- Step 1b in JS: `planAccounts` (same as `SelfSetupLogic.planAccounts`), list in `localStorage["kg.appsOff"]`, `adb.power.reboot()` then `reconnectAfterReboot` (`manager.getDevices()`, same serial, 3 min), `reenableApps()` in the `finally` of the install click + banner `#appsoff` (button reconnects first). Not run in a real browser/WebUSB.
- Gotcha: another adb server holding the USB interface -> message at `:190` ("adb kill-server").

#### 1d. SelfSetup on-phone (no PC): Wireless debugging against itself
- Trigger: MainActivity list row `ui/MainActivity.kt:174` (`main_self`) -> `setup/SelfSetupActivity.kt`.
- `SelfAdb.kt` (adb key/cert in filesDir, libadb-android + Conscrypt), `SelfSetup.kt`: mDNS port finders `:86-125`; `start()` `:185`; `job()` `:202-278`: pair `:204-210` -> connect `:212-217` -> accounts via `dumpsys account`: system-authenticator accounts abort; app accounts -> `restartForAccounts` (grant WSS, `Store.ownerPending`, `pm disable-user`, 14 s, `sync`, `reboot`) and the job ends; after boot `SelfSetup.resume` (from `Boot` and `Guard.onCreate`) reconnects self-adb (`SelfAdb.ensure`), retries `set-device-owner` while Android says "accounts", does the grants, `pm enable` the apps, notification (`finishApps`). Stale > 10 min = apps only. Needs the first unlock after boot. Not run on a real phone -> owner `:256` -> usage `:257` -> secure `:258` + optional trio `:260-262` -> a11y `:263-267` -> idle `:267` -> ColorOS probe -> optional `adb_wifi_enabled 0` `:271` -> `Guard.start`. Pair code entered from notification reply box (`:290-300`) because the pairing dialog closes when leaving Settings.
- `pairOnly` mode (`:169,:218-222`) = pair only, keeps shell for later (used by SelfAdb.pair for features without full setup).
- Requires an APK already installed (it is the installer itself) and Wi-Fi; Home flavor strips its native libs, so Home cannot self-setup.
- Pure helpers (unit-tested `SelfSetupLogicTest`): `ok`, `permMonitoring`, `accounts`, `withAccessibility` (`SelfSetup.kt:130-145`).

#### 1e. "Setup steps" sync rule
| step | proto | setup.sh | setup.ps1 | install.html | SelfSetup |
|---|---|---|---|---|---|
| install | 1 | yes | yes | yes (pm install) | n/a (already installed) |
| device owner | 2 | yes | yes | yes | yes |
| usage stats | 3 | yes | yes | yes | yes |
| secure settings + optional trio | 4 | yes | yes | yes | yes |
| accessibility | 5 | yes | yes | yes | yes |
| deviceidle | 6 | yes | yes | yes | yes |
| am start | 7 | yes | yes | yes | NO (app already in foreground) |
| LAN URL | 8 | yes | yes | yes | NO (app shows it) |
| ColorOS hint | 9 | yes | yes | yes | yes |
Extra only in SelfSetup: optional Wi-Fi-debugging off; its step 1b runs across a reboot (`resume`). Rule for edits: change protocol.md "Setup steps" + all 4 in one commit.

### 4.2 BUILD flow
- Trigger: `scripts/build.sh` (JDK 17, SDK 35, `ANDROID_HOME` default `~/Library/Android/sdk`; `TRUSTSTORE=` for TLS proxy `:15-19`).
- Steps (`scripts/build.sh`): write `android/local.properties` `:8`; create `signing.jks` if missing (keytool, pw `kidguard`), refuses on CI `:9-14`; one gradle call `:20`: `testKidDebugUnitTest assembleKidRelease assembleHomeRelease :parent:testDebugUnitTest :parent:assembleRelease`; copy to `build/kidguard.apk`, `kidguard-home.apk`, `kidguard-parent.apk` `:21-24`; `.sha256` for each `:26` (Update.kt needs kidguard.apk.sha256); versioned copies `kidguard-<v>.apk` etc. `:30`; perl-stamp `<span class="ver">v..</span>` in 4 site pages `:31-32`; `scripts/stamp-site.sh` (css/img `?v=<sha10>` cache-bust) `:33`; QR checksum from apksigner cert digest -> `ui/provision.json` + inline `/*KG*/` in `ui/qr.html` `:36-52`.
- Release build: R8 on (`app/build.gradle.kts:36`), ABIs arm64-v8a + armeabi-v7a only (`:40`); debug adds x86_64.
- Debug install: `:app:installKidDebug` / `:app:installHomeDebug` (AGENTS.md). Home + kid must both be installed for launcher changes (memory: poco-home-is-home-flavor).
- Unit tests: `cd android && ./gradlew testKidDebugUnitTest :parent:testDebugUnitTest` (DecideTest, PinTrackTest, SelfSetupLogicTest, HomeEditTest, LauncherLogicTest...). Relay: `cd relay && npm ci && npm test` (`package.json:test` -> `test/run.mjs`, wrangler dev :8793).
- CI `.github/workflows/ci.yml`: on push main + PR; jobs `android` (kid + parent unit tests, `node --check` of every ui/assets script named by index.html), `relay` (npm test). No signing key, no APK build, no release, no Home build, no website deploy.
- Gotchas: output APKs only local (`build/` git-ignored); `:parent` optional `google-services.json` => FCM resources (missing = FCM off); `ignoreAssetsPatterns` keeps install.html/qr.html/vendor out of the APKs `app/build.gradle.kts:51`.

### 4.3 RELEASE flow (derived from git log; no release script, no CI release)
- Evidence: commits titled `vX.Y.Z: ...` bump gradle.properties + site chip + docs/protocol.md (e.g. ce7a9de touches gradle.properties, 5 site pages, protocol.md). Tags `v0.9.5..v0.9.10` exist (v0.9.10 -> ce7a9de, a plain commit-title match). Update.kt reads `github.com/HoangTran0410/kidguard/releases/latest` redirect -> tag `vX` -> downloads `releases/download/v<tag>/kidguard.apk` + `.sha256` (`remote/Update.kt:25-41,119-141`). Hence GitHub Releases must carry assets named exactly `kidguard.apk`, `kidguard.apk.sha256` (+ `kidguard-parent.apk`, `kidguard-home.apk`, `.sha256`s, versioned copies per protocol.md:685).
- The GitHub release creation itself (gh release create / manual upload) is not scripted or documented anywhere in repo: inferred.
- Self-update: kid app checks daily (rule `autoUpdate`) or parent `update` command; Device Owner installs silently; refuses equal/lower versionCode (AGENTS.md).
- Relay deploy (users' own account): `scripts/relay.sh` / `relay.ps1` -> `cd relay; npm ci; wrangler whoami/login; wrangler deploy` (`relay.sh:11-25`), prints `https://kidguard.<you>.workers.dev`; `--delete` / `-Delete` removes. Dev maintainer deploy = same script or `npx wrangler deploy`; `wrangler.toml` name `kidguard`, DO class `Kid` migration v1, ratelimit RL. FCM secret: `npx wrangler secret put FCM_SA` (README:166). Worker bundles `ui/*.html` + `ui/assets/*` at deploy, so relay MUST be redeployed after any ui/ change for "away" parents.
- Website deploy: Cloudflare Pages `kid-guard.pages.dev`, dir `website/` (`_headers` present). No deploy script/config/workflow in repo (`scripts/site.sh` was added in 7acef5e then reverted in 9d75f23). Presumably `wrangler pages deploy website` or git-connected; unverified.

#### Release checklist (derived)
1. Land feature commits; run `cd android && ./gradlew testKidDebugUnitTest :parent:testDebugUnitTest` and `cd relay && npm ci && npm test`; update `docs/protocol.md` for any wire/setup change.
2. Bump `kg.version` and `kg.code` (+1, must grow) in `android/gradle.properties`.
3. `scripts/build.sh` (needs the real `android/app/signing.jks`; never regenerate). Check printed "QR checksum" unchanged vs `ui/provision.json` in git.
4. Review diff: 4 site pages version chip, css/img `?v=`, `ui/provision.json`, `ui/qr.html` (should be unchanged if key same).
5. Update website/guide/README text for new features (done in release commits), keep `llms.txt`/sitemap if relevant.
6. Commit `vX.Y.Z: <summary>` (with Co-Authored-By), tag `vX.Y.Z`, push main + tag.
7. Create GitHub Release `vX.Y.Z` (manual/gh) and upload from `build/`: `kidguard.apk`, `kidguard-home.apk`, `kidguard-parent.apk`, their `.sha256` files, and `kidguard-<v>.apk`, `kidguard-home-<v>.apk`, `kidguard-parent-<v>.apk`. Mark as latest (updater + QR + site use `/latest/`).
8. Deploy relay (`scripts/relay.sh`) so /install, /qr, parent page match; deploy website to Pages (owner's OK first).
   Website: static dir `website/` -> Cloudflare Pages project `kid-guard` (`kid-guard.pages.dev`): `npx wrangler pages deploy website --project-name kid-guard` (the exact command is the maintainer's choice; nothing in the repo runs it). Run `scripts/stamp-site.sh` first if css/img changed (build.sh already does).
9. Verify the release: `scripts/release-check.sh [vX.Y.Z]` (read-only HEAD requests) checks the 9 contract asset names (`kidguard{,-parent,-home}.apk` + `.sha256`, three `-<v>.apk` copies) exist and the tag is `latest`. Asset names come from `Update.kt` (`kidguard.apk`, `.sha256`), README/website links and `build.sh`.
10. Real-phone checks are the user's (nothing tests ui/*.html or on-device).
- Signing key backup: `android/app/signing.jks` (gitignored, store/key/alias password `kidguard`, alias `kidguard`) is the only copy; lose it and every enrolled phone needs uninstall + redo setup (self-update and QR checksum both pin it). Keep an offline copy (password manager / encrypted drive) and the SHA-256 printed by build.sh ("QR checksum") with it.
- build.sh side effects (tracked files it rewrites): site version chips, `?v=` cache-busters (stamp-site.sh), `ui/provision.json`, `ui/qr.html` `/*KG*/` block. With the same key and unchanged assets only the chips change; always review `git diff` after a build. CI never runs build.sh (no key): it compiles kid/home/parent debug instead.

## 5. Add-a-feature checklists

### New command `foo`
1. Phone: add `"foo" -> ...` to `Guard.run` (`K/guard/Guard.kt` command table) returning a short result string. Impl in its own file under `K/guard|remote/`. Parsing note: for relay the worker may replace `id` (see 4 gotchas); don't use `id` as a payload field unless it matches `^[\w.-]{1,64}$` (existing precedent: `sosAck` uses `sos`).
2. If it undoes/adds a restriction: also handle in `Guard.release()`/`Policy.release` (AGENTS rule).
3. Worker: add to `CMDS` (`relay/src/index.js:58`); destructive/secret-bearing/non-repeatable -> also `ONCE` (`:57`); payload validation next to `clearData`/`setting` (`:295-296`); keep body <= 4 KB. LAN-only -> add to `LAN_ONLY` in `relay/test/contract.test.mjs:17` instead.
4. Page: a control whose effect is big or can't be undone from the page (incl. checkboxes) gets one row in `CONFIRM` (`ui/assets/kid.js`), no handler change. Button/handler calling `cmd({type:"foo",...}, okMsg, askArr|false)` (`UI/kid.js:109`); confirm texts `ask.foo`/`askOk.foo` in `i18n-vi.js` + `i18n-en.js`; result texts `cr.foo.<result>`; for async state (spinner) use the `act()/expect()` machinery in `shell.js` and add the state field to `sig` (`kid.js:38`).
5. State feedback: if the page needs the outcome, add a state field in `Guard` state builder (`Guard.kt:~308-330`) + document it; log line key in `res/values*/strings` (vi + en) and `LOG_IC` (`kid.js`).
6. Docs: `docs/protocol.md` "Commands" (the contract test fails if `foo` isn't mentioned there as a word).
7. Parent app: nothing, unless a notification action needs it (`P/Act.kt`, `P/Logic.kt`).
8. Tests: `cd relay && npm test` (contract + relay), Kotlin unit tests for any pure logic (`*Logic.kt` pattern).

### New rule field `bar`
1. `K/guard/Rules.kt`: field in `data class Rules` (:133), `toJson()` (:173), `fromJson()` (:228) with default + clamp. Both are the allow-list. Persisted via `toJson(persist=true)`.
2. Enforcement: `decide()` in Rules.kt (pure, `DecideTest`) or `Guard.kt` tick / `Policy.apply` (`Policy.kt`); undo in `release` if it adds a restriction.
3. `Events.ruleDiff` (`K/guard/Events.kt`) so the change log shows it.
4. Page: default in `norm()` (`UI/core.js:103`, for older phones), control in the right `UI/tab-*.js` (mutate `edit`, call `changed()`), `FEAT_SEC/FEAT_TAB` + `state.features` if gated (`core.js:76-78`, `K/guard/Features.kt`), i18n vi+en.
5. Worker: nothing needed (stores any JSON <= 64 KB); only PIN fields are special.
6. Docs: `docs/protocol.md` "Rules JSON" (or the feature section). Old-phone behavior: unknown field ignored.
7. Launcher fields live under `rules.launcher` (protocol "Rule `launcher`") and have a normalizer in `norm()` (`core.js:114`).

### New state field
`Guard.kt` state builder -> `docs/protocol.md` -> page reads `st.x` (guard for undefined on older phones) -> add to `sig` if it must repaint -> if relay state size matters: worker rejects state >120 KB (`index.js:239`), relay reply cap 256 KB (`Relay.kt:203`).

### New worker route
Add the action name to regex `index.js:96`, handle in `Kid.fetch`, add `limitFor` if body > 256 KB; parent app proxy needs nothing (generic `/k/...`), except streaming bodies (`Logic.isFilesPath`). Phone side calls it from `Relay.kt`.


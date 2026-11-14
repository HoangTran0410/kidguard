<!-- part of docs/flows/ (index: README.md) -->
Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. **Line numbers drift**: grep the function name.

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
- Timed mode (0.9.22, `rules.mode`): page "Apply until…" (templates sheet, `kid.js modeApply`) sets `{name, until, rules: template fields}` on the normal rules. `Store.rules` (what Guard, launcher, `decide()` read) = `Rules.effective(now)`: normal rules + `mode.rules` while `now < until`, else the normal rules; cached per stored string until `until`. `Store.baseRules` is the stored/edited copy (state, `GET/PUT /api/rules`, setRules, Telegram, BankList, release use it). Tick start (`Guard.tick`): expired `mode` -> `setRules(base without mode, rev+1)`. "End now" = PUT without `mode`. Release clears it (`Policy.clearLost`). Gotcha: expiry is exact for enforcement but the stored field and `Policy.apply` follow at the next tick (10 s, 60 s screen off).
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

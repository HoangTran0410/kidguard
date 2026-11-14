# KidGuard flows (how things actually work)

Traced from the code on 2026-11-09 (main @ 0c95d99). For each flow: trigger -> steps -> where state lives -> gotchas.
**Line numbers drift**: treat `file:123` as a hint and grep the function name. Wire formats are in `docs/protocol.md`
(the contract); decisions, rejected approaches and known open issues are in `docs/decisions/` (one file per area, index in `README.md`).

One file per area; section numbers (§1.6, §4.3, ...) are kept across files. Read only the file you need.

| § | File | What |
|---|---|---|
| 0 | this file (below) | Privileges -> the flows that use them: check before saying "can't" |
| 1 | `enforcement.md` | Rules, tick, block decision, limits, suspend, location, Policy + release, watchdog, PIN, basic mode, FRP, lost mode |
| 2 | `remote.md` | Pairing, state refresh, save rules, commands, screen/listen/ring, files, self-update, page serving, parent app, wizard, QR, watch connect |
| 3 | `launcher.md` | Kid launcher: where is X, render, app list, drag, edit, folders, drawer, Today, widgets, dots, customization, `launcher.*` rules, flavors |
| 4 | `setup-build.md` | Setup flows (4 ways), build, release steps (§4.3) |
| 5 | `checklists.md` | Add a command / rule field / state field / worker route |

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

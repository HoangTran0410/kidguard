# Permissions: who grants each one, per setup

One row per permission the child app (`kid` flavor) declares that needs a grant. For each setup it says where the
grant comes from. `node scripts/check-perms.mjs` (CI + `build.sh`) fails when:
- a `uses-permission` in `android/app/src/main/AndroidManifest.xml` is in neither the table nor the install-time list;
- a cited file (`` `File.kt` ``, or `` `File.kt#word` `` to look for another word) does not contain the row's token (or that word);
- a row leaves a setup cell empty. "—" plus a reason is allowed, and it means the setup really has no way to get it.

Columns:
- **Device Owner**: what the app does alone.
- **Basic + own adb**: the 4 setups (`setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt`) and `AutoGrant` (every 30 min).
- **Basic, no adb**: what the child taps. `FixPerms` = the page's "Fix" button, which opens the one-tap system dialog or the Settings page.

New permission ⇒ add a row here in the same commit. If "Basic, no adb" has no path, say what the parent sees.

Install-time (no grant needed): ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, BLUETOOTH, BLUETOOTH_ADMIN, CHANGE_WIFI_STATE, EXPAND_STATUS_BAR, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, FOREGROUND_SERVICE_MEDIA_PROJECTION, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_SPECIAL_USE, INTERNET, KILL_BACKGROUND_PROCESSES, MODIFY_AUDIO_SETTINGS, QUERY_ALL_PACKAGES, RECEIVE_BOOT_COMPLETED, REQUEST_DELETE_PACKAGES, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, UPDATE_PACKAGES_WITHOUT_USER_ACTION, WAKE_LOCK

| Permission | Token | Used for | Device Owner | Basic + own adb | Basic, no adb |
|---|---|---|---|---|---|
| ACCESS_FINE_LOCATION | ACCESS_FINE_LOCATION | location, geofence, weather | `Policy.kt` | `AutoGrant.kt` | `SetupSteps.kt`, `FixPerms.kt` |
| ACCESS_COARSE_LOCATION | ACCESS_COARSE_LOCATION | same | `Policy.kt` | `AutoGrant.kt` | `SetupSteps.kt`, `FeaturesSheet.kt` |
| ACCESS_BACKGROUND_LOCATION | ACCESS_BACKGROUND_LOCATION | location while the screen is off | `Policy.kt` | `AutoGrant.kt` | `SetupSteps.kt`, `FixPerms.kt` |
| POST_NOTIFICATIONS | POST_NOTIFICATIONS | every notice to the child (13+) | `Policy.kt` | `AutoGrant.kt` | `FixPerms.kt` |
| RECORD_AUDIO | RECORD_AUDIO | listen around, Telegram voice, video call, chat voice | `Policy.kt` | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"mic"`, checklist `SetupSteps.kt` |
| READ_CALL_LOG | READ_CALL_LOG | Calls card: recent calls | `Policy.kt` | `Calls.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"calls"`, checklist `SetupSteps.kt` |
| READ_CONTACTS | READ_CONTACTS | Calls card: "unknown caller" | `Policy.kt` | `Calls.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"calls"`, checklist `SetupSteps.kt` |
| CAMERA | CAMERA | photo on request + live camera (`watch.cam`), wrong-PIN photo, video call, chat camera | `Policy.kt`, `Snap.kt` | `AutoGrant.kt` (`pinShot` / `watch.cam` on; the call and live camera grant at start, `CamCall.grant`) | `FixPerms.kt#"cam"` (while `watch.cam` is on); wrong-PIN photo: `MainActivity.kt`; call + chat: asked when used |
| READ_SMS | READ_SMS | reading SMS (`watch.sms`) | `Policy.kt` | `AutoGrant.kt`, `Sms.kt` | `FixPerms.kt#"sms"` (while `watch.sms` is on). Unverified: Android 10+ may refuse the dialog for a sideloaded app (restricted permission) |
| BLUETOOTH_CONNECT | BLUETOOTH_CONNECT | control-center Bluetooth toggle (12+) | `HomeControl.kt` | — not granted: the tile opens Bluetooth settings | — same (`HomeControl.kt#ACTION_BLUETOOTH_SETTINGS`) |
| WRITE_EXTERNAL_STORAGE | WRITE_EXTERNAL_STORAGE | parent files into Download (Android 9 and older) | `Policy.kt` | — not granted: `Files.kt` falls back to the app folder | — same (`Files.kt`) |
| PACKAGE_USAGE_STATS | GET_USAGE_STATS | screen time, app limits | — the child taps it in the checklist (`FixPerms.kt#"usage"`) | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"usage"` |
| WRITE_SECURE_SETTINGS | WRITE_SECURE_SETTINGS | accessibility back on, DNS / location lock (basic) | `Policy.kt` | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | — needs adb (shown on the page as feature "wss") |
| SYSTEM_ALERT_WINDOW | SYSTEM_ALERT_WINDOW | reopen a bank app after a pause (basic) | — not needed (a Device Owner starts activities itself) | `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FeaturesSheet.kt#ACTION_MANAGE_OVERLAY_PERMISSION` |
| WRITE_SETTINGS | WRITE_SETTINGS | brightness / screen timeout from the page | `Sys.kt#ownerP` | `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `HomeControl.kt#ACTION_MANAGE_WRITE_SETTINGS` (the child, from the control center); the page action fails until then |
| ACCESS_NOTIFICATION_POLICY | allow_dnd | Do Not Disturb from the page / control center | — GAP: a Device Owner cannot grant it; QR-provisioned phones without adb need the child's tap | `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `HomeControl.kt#ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` (the child, from the control center) |
| REQUEST_INSTALL_PACKAGES | REQUEST_INSTALL_PACKAGES | self-update | — not needed (a Device Owner installs silently) | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | — the system asks at the first update |
| USE_FULL_SCREEN_INTENT | USE_FULL_SCREEN_INTENT | ringing video call, SOS screen | — granted at install for sideloaded apps (Android 14 revokes it only for Play installs); unverified on HyperOS | — same | — same |
| (battery optimisation off) | deviceidle | stays alive in Doze | — the child taps it (`FixPerms.kt#"battery"`) | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"battery"` |
| (notification access) | allow_listener | notification dots, keyword alerts | `Policy.kt#NotifDots` | `AutoGrant.kt`, `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"listener"` |
| (accessibility) | enabled_accessibility_services | basic-mode blocking, screen view, refusal detector | `Policy.kt` | `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` | `FixPerms.kt#"acc"` |
| (device admin) | set-active-admin | basic mode: no uninstall, lock | — is the owner | `AutoGrant.kt` | `FixPerms.kt#"admin"` |

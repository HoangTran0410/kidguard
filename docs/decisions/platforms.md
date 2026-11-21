# KidGuard decisions: platforms

Index and the rule for adding lines: `README.md`.

### Oppo / ColorOS 5 (Android 8.1, CPH1912 / A5s; WebView = Chrome 101)
- adb `appops`/`pm grant`/`settings put` fail (UPDATE_APP_OPS_STATS / GRANT_RUNTIME_PERMISSIONS / WRITE_SECURE_SETTINGS) until developer option "Disable permission monitoring" is on; setup.sh/ps1/install.html/SelfSetup print the fix (4ed5ee2) Also `appops set` -> "uid 2000 does not have MANAGE_APP_OPS_MODES" (2026-11-20, new Oppo in self setup): matched by `SelfSetupLogic.permMonitoring`, the failure text is then only the fix; the self-setup screen says it before the run on oppo/realme/oneplus. Steps 3-6 of self setup (usage, secure settings, accessibility, battery) no longer stop at the first failure: each runs, failures are listed once at the end (owner 2026-11-20 "các bước tiếp có phụ thuộc ko": they do not). Usage-off note (id 7) is posted once per off period with setOnlyAlertOnce, not every tick (ColorOS re-alerted every few seconds; owner 2026-11-20), and not before the first app scan. The setup card shows "Tự cài qua Wi-Fi" while a non-pairing step is left (Android 11+, basic).
- No 6-tap QR provisioning on ColorOS Android 8-9: use USB (65c971d)
- Recents-swipe is a real force-stop (alarms gone, Accessibility off): MainActivity excludeFromRecents; AdminActivity finishes on onStop (not onPause: Android 8 shade pull fires onPause) (831bf98)
- ColorOS 5 reports stable inset = 0 (HomeActivity.kt:208); draws full-window dialogs under the nav bar (Sheet.kt:67)
- Wi-Fi sleeps with screen off on 8.1: WifiLock held while service runs (Guard.kt:628). LAN dropped on screen-off before that
- Mic via privacy layer can report amplitude 0: Listen watchdog ends such sessions (Listen.kt:48)
- Android 8 switches show "TẮT" text which counted as a button in Basic mode -> `checkable` (BasicLogic.kt:52)
- `isLocationEnabled` is API 28+; basic location status uses the secure setting on 8-9 (831bf98)
- After our own update Android 8 finishes HomeActivity but leaves its starting window: screen stuck black. MY_PACKAGE_REPLACED restarts HOME (67d9b09)
- `cmd package compile` fails (JIT only): measure with gfxinfo/framestats
- Android 8 PopupWindow.showAsDropDown doesn't flip near the bottom: place menus manually (3fce88c)
- API<28: DISALLOW_CONFIG_DATE_TIME doesn't exist: owner-mode timezone change on 8.x is a bypass (open)

### Play Protect
- Blocks sideloaded APKs that use accessibility ("App blocked to protect your device"): turn off scan during setup; durable fix needs Play Console appeal ($25 account, user's call)
- Android 13+: "Restricted setting" for sideloaded accessibility: App info -> menu -> Allow restricted settings (8b24a98)

### Poco / HyperOS 3 (SDK 36)
- Third-party home's app-open zoom is only played if the home window has SHOW_WALLPAPER; else apps slide in from the side. Keep `windowShowWallpaper=true` in values AND values-night themes (night file overrode the first experiment). Check `dumpsys window windows` (11-09)
- Another home turns gesture navigation into 3 buttons; nothing restores gestures (HomeMenus.kt:139)
  - 2026-11-15 (Poco, HyperOS OS3.0, SDK 36): `settings put global force_fsg_nav_bar 1` (WRITE_SECURE_SETTINGS) hides the 3-button bar (navigation_mode 0 -> 2) but swipes do NOTHING with KidGuard as home: HyperOS gestures live in POCO Launcher's TouchInteractionService (ignored when it is not the default home) and SystemUI EdgeBackGestureHandler stays disabled. With the bar hidden, key events still work: HOME -> KidGuard home, APP_SWITCH -> POCO Launcher RecentsActivity (recents OK), BACK OK. So own gestures (accessibility edge strips -> performGlobalAction) + fsg=1 is viable; fsg must go back to 0 whenever our accessibility is off/paused (bank apps) or the phone is stuck with no navigation.
  - 2026-11-15 owner test, correction: with fsg=1 and KidGuard as home, HyperOS's own edge Back WORKS (top and bottom half); only swipe-up Home and swipe-up-hold Recents do nothing. NavGest now draws only the bottom strip.
- Back-gesture strip ~25 dp eats drag events at the edge
- HyperOS control-center window ids (`miui.systemui.plugin`) unmeasured; Acc matching is "unverified"
- Poco default HOME = dev.kidguard.home (flavor home)

### Samsung One UI (Galaxy A51)
- `loadLabel` is a binder call routed through Samsung EDM: cache labels by apk path + activity + locale (~27 -> ~9 ms per return home) (54ae2dc)
- Some icons come pre-shaped with transparent margin: measure the visible pixels when normalising (IconCache.kt:113)
- Icon LRU must be heap-based (1/8 heap, 8-64 MB; later 1/5): 4 MB meant every rebuild re-rendered everything (~700 ms after a drop) (df13c13, 435a991)

### Old WebView (Chromium < 111, e.g. Oppo = 101)
- color-mix() only inside `@supports (color:color-mix(in srgb,red,blue))`; inline JS styles must not use color-mix. 46 declarations moved (b05190c)
- inset/min() expanded for old WebViews; flex gap issues on Android 8 Chrome on the site (4ca10dd, 831bf98)

### Build
- Gradle file-system watching (macOS) missed script-rewritten Kotlin files twice: "up-to-date" + old APK. Build with `--no-watch-fs`, check APK time (11-09)

### Android generic
- `enabled_notification_listeners` writes ignored since Android 8
- Android 12+: no Settings.Global writes; use DPM setGlobalSetting whitelist (ADB_ENABLED, AUTO_TIME...). `adb_wifi_enabled` is @hide (DevOpts.kt)
- Banking apps refuse to run with Developer options / USB debugging on
- (2026-11-20, owner: "make it a setup step, open Developer options and highlight the row") ColorOS "Disable permission monitoring" is its own self-setup step (before pairing, ColorOS = opporom/oplusrom prop or oppo/realme/oneplus). The run probes right after pairing with `appops set dev.kidguard GET_USAGE_STATS allow`; blocked -> opens Developer options and waits with the adb link up (re-probe every 3 s / on SelfSetupActivity resume, 10 min max), no re-pairing. `persist.sys.permission.enable` is read as a hint only (test Oppo 5.2.1 = false, meaning unverified). `setup/PermMon.kt`: open + `:settings:fragment_args_key` guess; with Acc running it scrolls the list to the row (vi/en/zh text) and draws a row-sized NOT_TOUCHABLE outline for 8 s (unverified on a real ColorOS: key name, row text, overlay position). setup.sh/ps1/install.html hints now also match MANAGE_APP_OPS_MODES.
- 2026-11-21 (flow review MED) Self-setup shows the ColorOS "Disable permission monitoring" text only when `PermMon.colorOs()` (`SelfSetupLogic.permMonMessage`): MIUI prints GRANT_RUNTIME_PERMISSIONS / WRITE_SECURE_SETTINGS too when "USB debugging (Security settings)" is off and keeps its own hint.
- 2026-11-21 (owner, Poco HyperOS photo) Developer options: Xiaomi/Poco/Redmi turn on by tapping "OS version" ("MIUI version" on MIUI), not Build number; Samsung keeps Build number under Software information. Every setup text says both (app ssa_s1_d / ssa_dev_first, install.html, guide).

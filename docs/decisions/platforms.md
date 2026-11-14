# KidGuard decisions: platforms

Index and the rule for adding lines: `README.md`.

### Oppo / ColorOS 5 (Android 8.1, CPH1912 / A5s; WebView = Chrome 101)
- adb `appops`/`pm grant`/`settings put` fail (UPDATE_APP_OPS_STATS / GRANT_RUNTIME_PERMISSIONS / WRITE_SECURE_SETTINGS) until developer option "Disable permission monitoring" is on; setup.sh/ps1/install.html/SelfSetup print the fix (4ed5ee2)
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

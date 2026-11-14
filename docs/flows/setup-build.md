<!-- part of docs/flows/ (index: README.md) -->
Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. **Line numbers drift**: grep the function name.

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

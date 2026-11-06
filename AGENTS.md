# KidGuard: notes for agents (AGENTS.md)

Android parental control. Child phone runs the app as **Device Owner**; parents use a browser page (LAN direct, or via a
Cloudflare Worker relay). User-facing docs: `README.md` (English). Wire formats: `docs/protocol.md`. **That file is
the contract**: change it in the same commit as any JSON / endpoint / command shape you change.

## Layout
- `app/src/main/kotlin/dev/kidguard/` (package `dev.kidguard`, minSdk 26, target 34)
  - `Rules.kt` pure rules + `decide()` (block-reason priority, see protocol). Unit-tested, keep it Android-free.
  - `Guard.kt` foreground loop: applies rules, suspends apps, location track. `Policy.kt` DevicePolicyManager restrictions.
  - `Store.kt` persistence, `Usage.kt` usage stats, `Watchdog.kt` restart alarm, `Provisioning.kt` QR/Device Owner.
  - `Http.kt` LAN server on `:8770` (serves `web/index.html` from assets), `Api.kt` its routes (token `X-KG-Token`).
  - `Relay.kt` phone side of the worker relay. `Acc.kt` accessibility: screen capture + remote input (API 30+).
  - `SelfSetup*.kt` phone runs adb against itself over Wireless debugging (libadb-android + Conscrypt).
  - `MainActivity.kt` child UI, `Ic.kt` Lucide vector icons (`res/drawable/li_*.xml`).
- `web/` is **one UI for both sides**: it is the APK's assets dir (`sourceSets.assets.srcDirs("../web")`) and the worker
  imports the `.html` files as text. Single-file pages, inline JS/CSS, no build step.
  - `index.html` parent page. `install.html` WebUSB installer (Tango ADB bundled inline; rebuild: `web/vendor/build.sh`).
  - `qr.html` QR provisioning page; `build.sh` rewrites the JSON between `/*KG*/` markers and `web/provision.json`.
- `worker/src/index.js` relay (Durable Object `Kid`, SQLite, free plan). Command whitelist lives here.
- `setup.sh`, `setup.ps1`, `install.html`, `SelfSetup.kt` all do the same setup steps: keep them in sync (protocol "Setup steps").

## Build / test
- `./build.sh` → `build/kidguard.apk`; runs `testDebugUnitTest` + `assembleRelease`. JDK 17, Android SDK 35.
  Behind a TLS proxy: `TRUSTSTORE=/path.jks ./build.sh`.
- Kotlin unit tests only: `./gradlew testDebugUnitTest` (`DecideTest`, `PinTrackTest`, `SelfSetupLogicTest`).
- Worker: `cd worker && npm ci && npm test` (starts `wrangler dev` on :8793, runs `test/relay.test.mjs`, stops it).
- Nothing tests `web/*.html` or on-device behaviour: say so in reports; real-phone checks are the user's.

## Gotchas
- Don't read `web/install.html` / `web/qr.html` whole: they contain huge inline bundles (lines of 13–25k chars). Use grep.
- `app/signing.jks` is git-ignored and auto-made by `build.sh` (password `kidguard`). The QR checksum is tied to it:
  a different key ⇒ provisioning fails. Never regenerate/delete it without asking.
- App version lives in 2 places, bump both: `versionName`/`versionCode` in `app/build.gradle.kts` and `Guard.kt` `BuildConfigVersion` (sent in state).
- `release` command must undo every restriction we add. A new restriction ⇒ also handle it in release.
- New relay command ⇒ add to the worker whitelist + protocol. Destructive ones (`reboot`, `release`, `unpairAll`) are deliver-once.
- HTML responses need `X-Frame-Options: DENY` + CSP; new external origins (e.g. tiles) must be added to CSP on both phone and worker.
- UI text is Vietnamese; code/comments English.

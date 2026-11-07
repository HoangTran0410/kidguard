# KidGuard: notes for agents (AGENTS.md)

Android parental control. Child phone runs the app as **Device Owner**; parents use a browser page (LAN direct, or via a
Cloudflare Worker relay). User-facing docs: `README.md` (English). Wire formats: `docs/protocol.md`. **That file is
the contract**: change it in the same commit as any JSON / endpoint / command shape you change.

## Layout
Root: `android/` (Gradle project: `app/` child app, `parent/` parent app), `ui/`, `relay/`, `website/` (kid-guard.pages.dev),
`docs/`, `scripts/` (`build.sh`, `setup.sh`, `setup.ps1`, `relay.sh`/`relay.ps1` = deploy the worker to the user's own Cloudflare account). Build outputs land in `build/` at the root.
- `android/app/src/main/kotlin/dev/kidguard/` (package `dev.kidguard` for **every** file, minSdk 26, target 34). Folders are for
  reading only: never give a file a sub-package (manifest components + the Device Owner admin are bound to `dev.kidguard.*`).
  - `guard/` enforcement: `Rules.kt` pure rules + `decide()` (block-reason priority, see protocol; unit-tested, keep it
    Android-free), `Guard.kt` foreground loop (applies rules, suspends apps, location), `Policy.kt` DevicePolicyManager
    restrictions + `Admin`, `Store.kt` persistence, `Usage.kt` usage stats, `Watchdog.kt` restart alarm, Basic mode, comfort, sys settings.
  - `remote/` parent link + remote commands: `Http.kt` LAN server on `:8770` (serves `ui/index.html` + `ui/assets/*`),
    `Api.kt` its routes (token `X-KG-Token`), `Relay.kt` phone side of the worker relay, `Acc.kt` accessibility (screen
    capture + remote input, API 30+), `Proj.kt`, `Listen.kt`, `Ring.kt`, `Wake.kt`, shared files, `Update.kt` self-update.
  - `launcher/` kid launcher: `HomeActivity.kt`, `HomeLayout.kt` (pure model, unit-tested), `LauncherSafe.kt`, style, wallpapers, "Hôm nay" page.
  - `setup/` `Provisioning.kt` QR/Device Owner, `SelfSetup*.kt` phone runs adb against itself (libadb-android + Conscrypt).
  - `ui/` child screens: `MainActivity.kt`, `AdminActivity.kt`, `Modal`/`Sheet`/`Ui` helpers, `Ic.kt` Lucide icons
    (`res/drawable/li_*.xml`), kid actions, shortcuts, language.
- `ui/` is **one UI for both sides**: it is the APK's assets dir (`sourceSets.assets.srcDirs("../../ui")`, for both apps) and the worker
  imports the `.html` files (and `ui/assets/*`) as text. No build step.
  - `index.html` parent page: markup shell + icon sprite; its CSS/JS live in `ui/assets/` (`style.css`, then classic `defer` scripts
    sharing globals, in the `<script>` order of index.html: `qrcode.js`, `i18n-vi.js`, `i18n-en.js` (dict `L`), `core.js` (i18n fns,
    storage, `call()`/`base()`, helpers, boot, add/pair/list screens), `overview.js` (all-kids view, map engine, back nav), `kid.js`
    (open/refresh, rules save, `ask`, `cmd`, templates), `tab-files.js`, `shell.js` (nav, `render()`, banners, actions, sheets),
    `tab-home|apps|rules|screen|where|set.js`, `start.js` (boot call, last)). Served at `/ui/<name>` by `Api.kt`, the parent
    app's `Proxy.kt` (both: whitelist `/ui/[a-z0-9-]+.(js|css)`) and the worker (explicit import map `UI`).
    **New `ui/assets` file** ⇒ add its `<script defer>` tag in index.html AND its import in `relay/src/index.js` (the worker test
    fetches every `/ui/` file the page names). Top-level `let`/`const` are shared across files: never redeclare a name. `install.html` WebUSB installer (Tango ADB bundled inline; rebuild: `ui/vendor/build.sh`).
  - `qr.html` QR provisioning page (QR library `ui/assets/qrcode.js`, served at `/ui/qrcode.js`, shared with the parent page); `scripts/build.sh` rewrites the JSON between `/*KG*/` markers and `ui/provision.json`.
- `relay/src/index.js` relay (Durable Object `Kid`, SQLite, free plan). Command whitelist lives here.
- `scripts/setup.sh`, `scripts/setup.ps1`, `install.html`, `SelfSetup.kt` all do the same setup steps: keep them in sync (protocol "Setup steps").

## Build / test
- `scripts/build.sh` → `build/kidguard.apk` + `build/kidguard-parent.apk`; runs both apps' unit tests + `assembleRelease`. JDK 17, Android SDK 35.
  Behind a TLS proxy: `TRUSTSTORE=/path.jks scripts/build.sh`.
- Kotlin unit tests only: `cd android && ./gradlew testDebugUnitTest :parent:testDebugUnitTest` (`DecideTest`, `PinTrackTest`, `SelfSetupLogicTest`).
- Worker: `cd worker && npm ci && npm test` (starts `wrangler dev` on :8793, runs `test/relay.test.mjs`, stops it).
- Nothing tests `ui/*.html` or on-device behaviour: say so in reports; real-phone checks are the user's.

## Gotchas
- Don't read `ui/install.html` / `ui/qr.html` whole: they contain huge inline bundles (lines of 13–25k chars). Use grep.
- `android/app/signing.jks` (shared by both apps) is git-ignored and auto-made by `scripts/build.sh` (password `kidguard`). The QR checksum is tied to it:
  a different key ⇒ provisioning fails. Never regenerate/delete it without asking.
- App version: `kg.version` / `kg.code` in `android/gradle.properties` (both apps; state sends `BuildConfig.VERSION_NAME`). `kg.code` must go up every release: the self-updater refuses an equal versionCode.
- `release` command must undo every restriction we add. A new restriction ⇒ also handle it in release.
- New relay command ⇒ add to the worker whitelist + protocol. Destructive ones (`reboot`, `release`, `unpairAll`) are deliver-once.
- HTML responses need `X-Frame-Options: DENY` + CSP; new external origins (e.g. tiles) must be added to CSP on both phone and worker.
- UI text is Vietnamese; code/comments English.

# KidGuard: notes for agents (AGENTS.md)

Android parental control. The child phone runs the app as **Device Owner** (or "basic mode": admin + accessibility);
parents use one web page (LAN direct, Tailscale, or a Cloudflare Worker relay), also wrapped by a parent Android app.
The child app is also a kid launcher, shipped alone as the "KidGuard Home" flavor.

## Read first (in this order, only what you need)
| Need | File |
|---|---|
| What KidGuard is allowed to do (Device Owner, accessibility, background start, broadcasts, ...) and which flow uses each: check before saying "can't" or building a new mechanism | `docs/flows.md` §0 |
| How a feature works end to end (trigger -> steps -> state -> gotchas, with file pointers) | `docs/flows.md` |
| What was already decided / tried and rejected / OEM quirks / past bugs / open issues | `docs/decisions.md` |
| Wire formats: rules, state, commands, endpoints, setup steps (**the contract**) | `docs/protocol.md` (900 lines: grep the heading) |
| Which feature works with which setup (Device Owner / basic / +WSS / +adb / Home) and over which link (LAN / Tailscale / relay) | `docs/support-matrix.md` (update the row in the same commit) |
| Launcher perf budgets, how to measure, rules (**any launcher UI change**) | `docs/launcher-perf.md`, `scripts/perf.sh` |
| User-facing docs | `README.md`, `website/guide.html` |

Before designing or "fixing" something, grep `docs/decisions.md` for it: many ideas were already measured and dropped.
When you settle something durable (a decision, a rejected approach, a platform quirk), add one line there in the same commit.

## Hard rules
- `docs/protocol.md` is the contract: change it in the same commit as any JSON / endpoint / command / setup-step change
  (the relay contract test checks commands are named there).
- `release` must undo every restriction we add. New restriction ⇒ also undo it in `Policy.release` / `Guard.release`.
- Every Kotlin file is `package dev.kidguard` (folders are for reading only; manifest + Device Owner admin bind to it).
- UI text: follow `docs/copy.md` (outcome first, one idea per sentence, short, glossary words). - UI text Vietnamese + English, always both (`res/values` + `res/values-vi`, `ui/assets/i18n-en.js` + `i18n-vi.js`); code and comments English.
- Rules JSON is a full replace on the phone (`Rules.fromJson` is the allow-list): the page must always send the whole object.
- New relay command ⇒ worker whitelist `CMDS` + protocol "Commands" + (destructive) `ONCE` list. Checklist: `docs/flows.md` §5.
- New page section (`sec("id", ...)`) ⇒ an entry in the feature search `QS` (`ui/assets/shell.js`), keys in both i18n files;
  `node scripts/check-search.mjs` fails otherwise (CI + `build.sh`).
- New `ui/assets` file ⇒ `<script defer>` tag in `ui/index.html` (order matters, shared globals: never redeclare a
  top-level name) AND import + map entry in `relay/src/index.js`.
- HTML responses: `X-Frame-Options: DENY` + CSP; a new external origin goes into CSP on both phone (`Http.kt`) and worker (`relay/src/index.js` `CSP`).
- `kg.code` (`android/gradle.properties`) goes up every release: the self-updater refuses an equal versionCode.
  Release asset names (`kidguard.apk`, `.sha256`, ...) are a contract with the updater, QR page and website.
- Setup steps are done 4 ways and must stay in sync: `scripts/setup.sh`, `scripts/setup.ps1`, `ui/install.html`, `K/setup/SelfSetup.kt`.
- Launcher changes: build and install BOTH flavors (`kid` and `home`); the phone's default HOME may be either.
- Launcher UI changes are measured on a real phone (release build, `docs/launcher-perf.md`): before/after numbers in the
  commit, within the budgets there or a written reason.
- Never regenerate/delete `android/app/signing.jks` (QR checksum + self-update + Home layout import depend on it).
- Don't read `ui/install.html` / `ui/qr.html` whole (inline bundles, lines of 13-25k chars): grep them.
- Don't search inside `.claude/worktrees/` (stale copies of the tree from old agent runs).

## Layout (where is X)
Root: `android/` (Gradle: `app/` child app with flavors `kid` + `home`, `parent/` parent app), `ui/` (parent page +
installers, also the APK assets dir and imported by the worker), `relay/` (Cloudflare Worker + Durable Object), `website/`
(static site kid-guard.pages.dev), `watch/` (Zepp OS parent app for Amazfit Bip Max: `app-side/` runs in the Zepp phone app and talks to the relay like the page, `page/` = watch screens; build `cd watch && npm ci && zeus build`, install `tools/install.exp` = the owner's watch, ask first), `docs/`, `scripts/` (build, setup, relay deploy). Build outputs: `build/`.

`K/` = `android/app/src/main/kotlin/dev/kidguard/`:
| Area | Files |
|---|---|
| Rules + block decision (pure, unit-tested) | `K/guard/Rules.kt` (`Rules`, `fromJson/toJson`, `decide()`) |
| Enforcement loop, commands, state JSON | `K/guard/Guard.kt` (`tick`, `setRules`, `command`/`run`, `release`, state builder) |
| Device Owner policy + release undo | `K/guard/Policy.kt` (`apply`, `suspendExactly`, `release`, `releaseBasic`) |
| Persistence, PIN, pairing, TOTP | `K/guard/Store.kt` (one prefs file `kg`, device-protected) |
| Basic mode, usage, watchdog, comfort, phone settings | `K/guard/Basic*.kt`, `Usage.kt`, `Watchdog.kt`, `Comfort.kt`, `Sys*.kt`, `DevOpts.kt` |
| LAN server + routes | `K/remote/Http.kt` (`:8770`), `K/remote/Api.kt` (token `X-KG-Token`) |
| Relay (phone side) | `K/remote/Relay.kt`; worker `relay/src/index.js` (`CMDS`, `ONCE`, DO `Kid`) |
| Screen / input / listen / ring / wake / files / update / Tailscale | `K/remote/Acc.kt`, `Proj.kt`, `Listen.kt`, `Ring.kt`, `Wake.kt`, `Files.kt`, `Update.kt`, `Ts.kt` |
| Kid launcher | `K/launcher/HomeActivity.kt` (state, render, pager, drawer) + extension files `HomeDrag/HomeMenus/HomeEdit/HomeIcons/HomeDock/HomeFolder/HomeLibrary`; pure model `HomeLayout.kt` (unit-tested); `LauncherSafe.kt`; `Flavor.kt`. Subfolders: `widgets/` (`HomeWidgets*`, `HomeAppWidgets`, `HomeMedia`, `*Logic`), `custom/` (`CustomActivity` = Tuỳ chỉnh, `AppLook*` = Sửa app, colour picker, backup, wallpaper, style), `control/` (control center), `focus/`, `today/` (`TodayPage`, `KidNotes`) |
| Setup / provisioning | `K/setup/Provisioning.kt`, `SelfSetup*.kt` |
| Child screens | `K/ui/MainActivity.kt`, `AdminActivity.kt`, `Modal`/`Sheet`/`Ui`, `Ic.kt` (Lucide icons `res/drawable/li_*.xml`) |
| Parent page | `ui/index.html` + `ui/assets/`: `core.js` (`call()`, `base()`, boot, pairing), `kid.js` (refresh, `putRules`, `cmd`), `shell.js` (nav, render, actions), `tab-*.js`, `i18n-*.js` |
| Parent app | `android/parent/.../parent/` (`Proxy.kt` localhost proxy, `CheckJob.kt`, `Push.kt`) |

Flavor `home` (`dev.kidguard.home`): launcher only, `src/home/AndroidManifest.xml` removes the parental parts; code checks `Flavor.HOME`.

## Build / test
- Everything: `scripts/build.sh` → `build/kidguard.apk`, `kidguard-home.apk`, `kidguard-parent.apk` (+ `.sha256`, versioned
  copies). Runs unit tests + release builds. Side effects: stamps version into site pages and the QR checksum into
  `ui/qr.html` / `ui/provision.json` (review the diff). JDK 17, SDK 35. Behind a TLS proxy: `TRUSTSTORE=/path.jks scripts/build.sh`.
- Quick: `cd android && ./gradlew assembleKidRelease assembleHomeRelease testKidDebugUnitTest :parent:testDebugUnitTest`.
  Debug install: `:app:installKidDebug` / `:app:installHomeDebug`.
- Worker: `cd relay && npm ci && npm test` (starts `wrangler dev` on :8793; one run at a time).
- After editing `ui/assets/*.js`: `node --check` each file AND `scripts/check-js.sh` (esbuild: catches a reassigned `const` that blanks a whole tab; CI + build.sh run both).
- Parent-page UI change ⇒ `node scripts/shots/shoot.mjs --audit --width 360 --out <tmp>` (and again with
  `--patch basic.json` = `{"mode":"basic","owner":false}`): every section open, full-page PNGs, prints what sticks out of
  the screen / is cut by a card. LOOK at the PNGs of the tab you changed before handing it to the owner.
- Nothing tests `ui/*.html` or on-device behaviour: say so in reports; real-phone checks are the user's.
- Release steps (bump, build, tag, GitHub release assets, relay + site deploy): `docs/flows.md` §4.3.
  Deploys (`wrangler deploy`, Pages) need the owner's OK.

## Common gotchas (more in `docs/decisions.md` §3)
- Android 8.1 / ColorOS: adb grants need "Disable permission monitoring"; Recents swipe = force-stop; stable inset 0.
- Old WebView (Chrome < 111, e.g. Oppo = 101): `color-mix()` only inside `@supports`; no fallback-line trick.
- HyperOS: the home window must show the wallpaper (`windowShowWallpaper` in BOTH `values` and `values-night` themes)
  or app-open animations slide instead of zoom.
- `DevicePolicyManager.setStatusBarDisabled` silences all notifications: don't use it (quick settings are closed via Acc).
- Relay commands that aren't deliver-once stay queued 24 h; the parent page shows "sent", not "done".

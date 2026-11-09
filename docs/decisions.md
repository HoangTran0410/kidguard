# KidGuard: decisions, rejected approaches, gotchas, past bugs, open issues

Read this before proposing a design or "fix": most ideas below were already tried, measured or decided. Final state only
("reversed in X" marks a change of mind). Dates are 2026-11-xx; `(abc1234)` = commit with the details (`git show`).
"unverified" = not re-checked against current code. When you decide/learn something durable, add one line here in the
same commit. Flows: `docs/flows.md`. Wire contract: `docs/protocol.md`.

## 1. DECIDED

### Process / agents
- Design briefs must name 3-4 reference products (Pixel, Nova, iOS, Niagara) and give a "references -> what we took" table; each action has exactly one entry point; no status chip unless something is wrong; no stray section titles on canvas screens — owner rejected launcher mock v2 (11-07)
- UI/UX reviews must walk every action through idle/pending/active/stop/fail/screen-off/double-press/conflict on a running phone, not static screenshots; phone-reported state is source of truth (11-07, ux-review-must-walk-states)
- Launcher changes: build + install BOTH kid and home APKs; check resolved HOME (`cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`) first; Poco's default home is dev.kidguard.home (11-09)
- Telegram alerts go through the relay worker only (secret TG_BOT, own bot per family, webhook path = hash of the token); LAN-only families cannot use them. Text only, same events as FCM, no inline buttons (answering stays in the page)
- Agents in parallel work in separate git worktrees; one npm test at a time in relay/ (two at once = fake "Headers Timeout") (11-07)
- After merging ui/index.html or ui/assets/*.js: run `node --check` ("keep both" conflict resolution re-added stale markup -> JS syntax error) (11-07)
- Index.html was split into ui/ + ui/assets/ (17 files, classic defer scripts, no build step) because a 6.4k-line file caused merge conflicts between parallel agents (0fed7a0). Repo folders renamed web->ui, worker->relay, site->website (c792aac)
- Kotlin source in feature folders (guard/ remote/ launcher/ setup/ ui/) but ONE package `dev.kidguard`: manifest components + Device Owner admin are bound to it (e08ce30). HomeActivity split into extension-function files, state stays in the activity (e397c07)
- docs/protocol.md is the contract; contract test checks Guard.kt / worker CMDS / protocol agree (1747923). A guard.kt folder move broke that test once (d1c75da)
- `kg.code` must go up every release (self-updater refuses equal versionCode); build.sh writes `.sha256` assets, self-update needs them (042d72a; v0.9.0 shipped stale hand-made ones)

### Enforcement (Device Owner / basic mode)
- Release (`release` cmd) must undo every restriction; a failed release re-applies policy + watchdog, keeps parent tokens and keeps enforcing (1747923, f032b5d). Release aborts with "failed: N app còn bị ẩn" if a Dọn máy-hidden app can't be un-hidden
- Basic mode (no Device Owner) exists for phones that can't be DO without a PC (no QR setup, Android <11): accessibility bounces blocked apps HOME, bounces Settings/installer pages that would uninstall/deactivate/force-stop us; self-protection only after first pairing (43b298f, 831bf98)
- Features without full setup: feature->capability table (Features.kt, state.caps/features); launcher works without DO via local opt-in before pairing, parent rule after; only owner forces home (d1c75da)
- Dọn máy without DO: through the phone's own Wireless-debugging adb (`pm disable-user`/`enable`/`clear`); only re-enables packages recorded in Store.shellDisabled; max 20 per pass (d1c75da, agent C). The parent page gated it owner-only until 2026-11-09; now basic mode gets Dọn máy + Xoá dữ liệu whenever state.preinstalled is sent (= SelfAdb.ready)
- Pairing: PIN required once set; 5 free tries then 5 min lock, doubling; every 3rd wrong pairing guess rotates the code; code checked BEFORE PIN so wrong PINs alone can't lock pairing (66d8d5d, f032b5d)
- "Reopen pairing" when paired with no PIN opens only after 24 h (Store.recoverAt) and notifies parents; a PIN cancels it. Reason: otherwise the child pairs its own browser and releases (7fc1506). A "Chưa đặt PIN" banner on every tab pushes parents to set one (d39f9dc)
- Listen (mic): one-way, beep + ongoing notification + log, max 10 min, one listener, refused if its notice channel is silenced, child always told (d7fed7a, 747bc65). Legal-risk item per CEO review
- Safe DNS fallback: after ~90 s unvalidated falls back to opportunistic; retries host after 30 min; keeps DISALLOW_CONFIG_PRIVATE_DNS (60811e2, f032b5d)
- Time-change / timezone change / usage-access-off are parent events (clock / tamper) (f032b5d)
- Location lock works on Android 8 via LOCATION_MODE + content observer (5f03cbe). Location history asks network+gps together on Android <12 (gps alone never answers indoors) and removes every one-shot listener (41cf1f9)
- Device-health, chores, zones (arrive/leave), alerts as state.events, FCM push carries no text (747bc65, f508a30)
- App groups (shared limit + hours), Google-Authenticator-style offline unlock code (RFC 6238) (ceab74c)
- devOff (Developer options/USB debugging off, because banking apps refuse to open with them on); noDebug off->on also runs devOff once (0998bdb). Never press devOff on the dev Oppo (kills adb)
- Remote settings: volume/brightness/timeout/DND via `setting` cmd, state `sys` read at most once a minute (318b4ca)
- Quick-settings blocking: rule `launcher.shade` (parent toggle, default off) closes ONLY quick settings via Acc; notification shade stays usable (5b81268, user: "tuong dong control center thoi")
- Tailscale: auth key only typed on LAN, never queued on relay (`tailscaleOff` whitelisted only); API access token mints the kid's own key and disables its 180-day expiry; token memory-only (882cd92, 678c8ba)
- showWatching rule (default true); notice switches to "Bố mẹ đang điều khiển máy" after first input; log always records (882cd92)
- Self-update from GitHub releases: daily check (rule autoUpdate default on) or `update` cmd (deliver-once); checks sha256 + package + versionCode; DO installs silently, basic shows notification + button (e461efe)
- Large-font support: modals scroll with pinned buttons, widgets scale to cell, sheets keep clear of ColorOS nav bar (d1c75da)

- Pairing code reveal before the first pairing (`MainActivity.kt` `everPaired` gate, hold 5 s) is accepted: at that point the parent holds the phone for setup. Forgotten-PIN recovery paths are listed in `docs/flows.md` section 1.9; no extra recovery mechanism on purpose.
- Guard `tick()`: optional steps (usage, DNS retry, Tailscale poll, location, battery, warn, history, update, launcher) run through `opt{}`, so one throwing never skips suspend/hide; failures are logged once a minute per step.
- Release moves `NotifDots.setOn(false)` after Device Owner is really gone and restores `tsWanted/tsKey/tsNoExpiry` when release fails; `Debloat.canClear` refuses `dev.kidguard` and `dev.kidguard.home`; LAN file upload is capped at 10 min total; `Store.ringVol` lets the Guard restore the alarm volume after a process death mid-ring.

- Bank apps (VCB & co.) refuse to run while ANY accessibility service is on, Play Store ones too (Niagara Launcher, owner report), and re-check on every resume. Rule `accPause` (default ~15 VN banks/wallets): owner mode really removes our service when one opens (launcher tap first, else its window event), back on after 10 min with none in front + killBackgroundProcesses on them. Not "hiding" from the check. Basic mode: same when the app holds WRITE_SECURE_SETTINGS (adb / Wireless-debugging setup), back on after 1 min instead of 10 (the service is the enforcement); before 11-09 evening `able()` required Device Owner so the Poco (basic) never paused. Recents re-open after resume may still lose the race (unverified) (11-09)

- Launcher drawer jank after using an app: the "recent" row changed so the first swipe frame rebuilt the whole grid (94-103 ms build, 183-200 ms frame, Oppo A5s). Now `drawerPrep()` refills the hidden drawer on idle after resume, and the hidden drawer is INVISIBLE not GONE (GONE re-measured the grid on show): 3-5 ms build, 16-50 ms first frame. "Dùng gần đây" title dropped (owner: the row speaks for itself) (11-09)

- Kid Home takes apps' pinned shortcuts (CONFIRM_PIN_SHORTCUT -> `PinShortcutActivity`, accepted at once, first free cell; item kind `shortcut`). It follows its source app's show/pause/hide rule (never a way around a block); Guard still enforces whatever it opens. Pinned widgets (requestPinAppWidget) are declined. Our own "add Lock to home" still puts the 1x1 lock widget when Kid Home (kid flavor) is the default home (11-09)
- Drawer after a cold start: icons rendered on a background thread first (`drawerWarm`), then the drawer built. POCO measured: build 254 -> 33 ms, rows added while swiping 35-83 -> 4-6 ms each, p90 frame 200 -> 44-69 ms. A Chrome PWA "Install" is a real WebAPK (`org.chromium.webapk.*`), placed on the home as a normal app; only "Tạo lối tắt" gives a pinned shortcut (11-09)
- Home first render after a cold start (release builds): app labels + "has activity" kept on disk per apk path (`Labels`, `labels.json`), launcher keeps its own decision 15 s until the Guard ticks, home icons not rendered yet load on a background thread (`IconCache.async`, shown when ready). POCO 483-522 -> 184-189 ms, Oppo A5s 1165-1173 -> 509-513 ms. A blurred-wallpaper file cache was tried and dropped: the 315 ms blur was a debug build (not JIT-compiled), 45 ms in release (11-09)
- DND tile: the manifest needs ACCESS_NOTIFICATION_POLICY or KidGuard is not listed on the system DND access page (only the adb `allow_dnd` setup step granted it) (11-09)
- Tuỳ chỉnh first open: sections below the fold are built after the first frame is drawn (a View.post before it still ran ahead of that frame: no gain, measured). Oppo A5s first draw 146-218 -> 87-142 ms (11-09)

### Remote / relay
- Command whitelist lives in the relay worker; new command = whitelist + protocol + test. Destructive (`reboot`, `release`, `unpairAll`, `update`, `unpair`) are deliver-once (AGENTS.md)
- Worker caps: cmd <=4 KB, rules <=64 KB, full queue -> 429 (never drop oldest), per-IP 150 req/10 s on /c/*; state size counted in bytes; expired cmds don't count (1747923, 66d8d5d, f032b5d)
- Worker gives every command a random id, so SOS ack uses field `sos`, not `id` (f032b5d)
- Rules carry `pinAt`; phone takes a relay PIN hash only once (the relay re-sent an old hash and reverted a LAN-changed PIN) (f032b5d)
- LAN server rejects non-IP / non-.ts.net Host headers (DNS rebinding); CORS on /api only for other KidGuard phones (private IP/LAN name/*.ts.net), never Allow-Credentials (66d8d5d, 4a2a75a)
- Tailscale first, relay under Advanced; per-family self-host relay (scripts/relay.sh|ps1 deploy to the user's own Cloudflare account) because the shared free relay supports only ~20-50 families (546465e, CEO review)
- Relay state is hashed per minute (usage counters) -> ~3x fewer full states (1747923)
- Live screen: Android 11+ takeScreenshot via Acc; Android 8-10 via MediaProjection with one child consent (Proj.kt). LAN frame loop up to ~7 fps (3dcd7a0)
- Listen player uses MediaSource: first sound ~0.3 s instead of ~8 s (ffcebd0)
- HTML needs X-Frame-Options DENY + CSP; new external origins (tiles) must be added to CSP on phone AND worker

### Parent page / parent app
- Every action that changes the child's phone asks via one custom `ask()` modal (Esc/backdrop = cancel, typed word for release), never native confirm. The confirm is asked BEFORE the 20 s answer timer starts (time on the confirm was counted as "phone did not answer") (0900627, 4ca10dd)
- Page layout: sidebar/bottom nav, contextual hero, stateful actions; ring/listen wait for phone-confirmed state; only 1 banner shows plus "N more" line, SOS always on top (43de0ec)
- Parent page is flat (no backdrop blur, no drop shadows; focus rings kept) for old WebViews (4ca10dd)
- Parent app = WebView + localhost proxy serving ui/; only our own origin plus OSM tiles reachable; caches last state (X-KG-Cached banner); 8 s state timeout so a stalled phone falls back fast (625bbc2)
- Update notification opens the in-app Settings screen instead of a github.com link (the GitHub app hijacked those) (4445879)
- i18n: English default + Vietnamese; `L` dict; wire format unchanged; phone-written text follows the child phone's language; Langs.ALL single list; no AppCompat (74e9e99)
- Map: one gesture helper (mapCtl) for location map, overview and place picker; tiles z clamped 3..18, .de tile fallback (b1110b8)
- Parent unlock speed: PIN hash hex via table (~11x faster), WebView warmed while typing (d1c75da)
- Icons: Lucide SVG/vector everywhere, no emoji in app, page or scripts (5d1853c, b604eb6)
- One QR library (ui/assets/qrcode.js) shared by parent page and qr.html (8f05f0c). TOTP QR drawn locally (4ce13f2)
- Parent page commands/dialogs are bound to the kid that was current when the dialog opened (`cmd()` aborts if `cur` changed; `open/goList/overview` close any open `ask`); destructive confirms name the kid; unpairAll and forget-kid need the typed word like release. Pause/resume-all builds rules from relay pending `j.rules` and refuses when no rules are known (full replace)
- Parent app self-update verifies `kidguard-parent.apk.sha256` (release asset from build.sh) before the installer opens; CheckJob posts one "kid unpaired" notification on 401/403 (flag in Store, cleared when the kid answers again)

### Launcher (kid home)
- Kid launcher is the persistent HOME behind rule `launcher`, with crash fallback (LauncherSafe); an app update clears the crash count and restores a layout reset in the last 24 h (56c95f8, 704c7b8)
- Ships also as a separate "KidGuard Home" APK (flavor `home`, dev.kidguard.home, ~0.9 MB, no admin/acc/relay/internet). Its layout hands over to KidGuard the first time KidGuard's launcher turns on (same signing key only); KidGuard-as-DO hides Home while it runs the launcher; release shows it again (868c40d, ce7a9de)
- Opening an app uses the SYSTEM clip-reveal zoom; home window sets `windowShowWallpaper=true` in BOTH theme files (9cf1ada series, 5b81268). The self-drawn fake zoom was removed
- iOS-style home: dim layer long-press menu, edit mode with minus badge, multi-select (pick rings; bar with select/delete page, group, move, remove), folders (3x3 pages), App Library page, Today page (can be turned off via style.today) (9cf1ada, 435a991, 6f8e6fa)
- Drag: hold-and-drag re-arranges anywhere; folder only after 0.5 s rest on icon centre (dark plate); swap waits 220 ms; remove target is a small circle top-centre; the grid does NOT shrink while dragging (drop bar floats over it) (5973dfc, 8d14c2c, 9cf1ada). "Sắp xếp màn chính" arrange mode REMOVED (81131b4)
- Edge page-flip zone 48 dp (back-gesture strip eats ~25 dp on Poco) (435a991)
- One text ink per wallpaper (picked once like Pixel/iOS), no per-label scrim pills (250a473)
- Labels: one style everywhere; size follows icon; up to 2 lines only if no word splits; dock has own switch `style.dockLabels` (704c7b8)
- Notification dots via NotificationListenerService (counts per package, nothing stored/sent); access via `cmd notification allow_listener` in all 4 setup paths; release unbinds (86c66de)
- KidGuard itself shows in the drawer (was hidden since v1; users couldn't find it) (53553b7). Home entry hidden from its own grid/drawer (ab9c761)
- Today page cards show empty states with a way to fill them (0c95d99) — this REVERSES 9cf1ada's "empty cards hidden"
- Home gestures (GestLogic.kt, HomeGestures.kt; prefs `hm_gest`, not in home.json) + Tuỳ chỉnh backup (HomeBackup.kt, SAF JSON `kidguard-home-backup` v1, layout + gestures + wall.jpg if <= 400 KB, one undo = home.undo.json): swipes default to "default" = old drawer / shade+control behaviour; lock only in kid flavor via Quick.lock (Acc, else admin); gesture "notifications" = expandNotificationsPanel, else Acc global action (quick-settings blocking by rule `launcher.shade` still applies); restore + gesture edits obey `lockCustom`. Not verified on a device
- Kid control center (HomeControl.kt + ControlLogic.kt): Wi-Fi, Bluetooth, torch, airplane, data, sound tiles; left-half swipe down = notifications, right-half = quick settings (5b81268). Unfeasible directly (per feasibility report, unverified on device): mobile data, airplane write, hotspot, cast -> open Settings panel / show locked
- Widgets: 4 kid widgets + 6 more on one shared widget design system (wCard/wHead/wMain/wSub/wEmpty/wOpen), plus other apps' widgets via AppWidgetHost (host id deleted when the item leaves the layout) (4c266ec, ee1a075)
- Wallpaper blur 0-100 % / dim 0-60 % sliders for every kind; wallpaper drawn on its own SurfaceView (86c66de, 435a991)
- Built-in wallpapers: sky/night/sunset/aurora (the preset defaults) re-rendered as mesh gradients (radial blobs + 12-14/255 tiled grain, no blur filter, same ids so saved layouts get the new look); new ids bloom/lagoon (light) and nebula (dark); light ones stay pastel so HomeStyle.ink keeps dark text (11-09)
- Icons: all in chosen shape (circle/squircle); legacy icons fill or sit at 74 % on plate; normalised visual area (86c66de, 704c7b8)

- Launcher safety nets (decided): `HomeFile` keeps an older good `home.json.bad` as `.bad.old` on a second reset and sets its cache only after the rename; crash counting needs a launcher UI frame in the stack (`LauncherLogic.isLauncherCrash`), not just `HomeActivity.alive`; Tuỳ chỉnh Undo covers presets/Reset and a new wallpaper photo (`wall.jpg.prev`); dropping a folder or another app's widget on the remove circle asks first; `closeOverlay` hides the IME.
- Grid items next to app icons draw `IconCache.plate()` (same squircle/circle + glass rim): folder background (dark: white 22 % fill, pal.card matched navy wallpapers so the folder had no edge) and the 1x1 lock widget (laid out like appIcon, with label). Folder open/close 280/220 ms, close to a page swipe (180-320 ms); 420/340 felt slow (11-09)

- Child screen (MainActivity) uses Kid Home's palette (`HomeStyle.palette`, recreated when accent/theme changes). One "Việc cần làm · x/y" card (`ui/SetupSteps.kt`) holds every setup step for both modes, battery + OEM auto-start included (auto-start can't be read back: child taps "Đã bật" -> `Store.autostartOk`); only steps left are listed. "Trạng thái bảo vệ" + "Tính năng và quyền" merged into one sheet "Bảo vệ và quyền" (`FeaturesSheet.show(..., status, battery)`). Kid Home card on the main screen while it isn't HOME. Old `BasicChecklist` / owner `Checklist` deleted (11-09)

- (2026-11-09) Long-press menus stay short (Pixel / One UI): header = name + (i) app info; one "Sửa app" sheet holds rename + icon + restore (live preview, saves only on Lưu); "Chọn" merged into "Sửa màn hình chính" (starts with the item picked); widget sizes = chips in the header; no "Đọc tên" row (speak setting reads on tap).

- (2026-11-09) Sửa app sheet: icon grid = plain views (oval drawable + glyph / white emoji), recoloured in place, built after the first draw in 2 lumps. Oppo A5s release warm: build 71-83 -> 25-29 ms, first draw 133-149 -> 94-108 ms, colour tap p90 10-11 ms. Perf rules + budgets: `docs/launcher-perf.md`
- (2026-11-09) Bank opened without our launcher (Recents, notification, link) already saw the service on: `AccPause.pauseLate` = Home (global action, while still bound) -> pause -> kill bank -> reopen it fresh after 1 s. Deep links are lost; with another launcher on a basic-mode phone Android may block the reopen
- (2026-11-09) Restored backup / undo changed renames but the launcher kept its cached app labels until a package event: `AppLook.version`, `launcherApps()` rereads when it moves

- (2026-11-09) Widget layout check = a generated home.json with every widget type x size (emulator, `run-as` copy + kill the process: `am force-stop` does not stop a Device Owner launcher), screenshot every page, vi locale, 4 and 5 columns. Found: DayBar height `34 * d.toInt()` (2.75 -> 2, hour labels clipped; Oppo 2.0 hid it), lock 2x1 label not centred, usage 4x1 times ellipsized to "1 …" (now short "1g 20p" + DropRow drops trailing icons so the total always fits); battery 2x1 content left-packed (weight-1 text column) -> centred group. Customize select rows (`segRow`) = StackRow: label above value when both don't fit (the long gesture values squeezed "Vuốt lên" to one letter per line). Missed at coding time because only build + tests ran, no screenshot with long vi strings: layout check added to `docs/launcher-perf.md` §4
- (2026-11-10) Launcher polish (owner screenshots): rows are always automatic (square cells; Customize offers columns only, `grid.auto` ignored on read); icon size = stepper 1..5 (a slider took a block), other sliders = label above + full-width bar; swipe-up chevron under the dots removed (TalkBack custom action `kg_all_apps` on the dots instead); edit-mode "−" badge removed (the pick circle + "Bỏ khỏi màn chính" in the bar cover it); high-contrast labels = one tight `backLabel` pill via `addLabel` for apps, folders, shortcuts, dock phone; `kit.pill` centres icon + text as a group.
- (2026-11-10) Widgets on 3-5 columns: SIZES width 4 = the whole row (`HomeLayout.span`); growing the columns stretches widgets that filled the old row; old 5-column layouts with a 4-wide card at x=0 and a free 5th cell are stretched on parse. Clock + date are `FitText` (one line, shrink to the cell; the date falls back to "Th 3, 10/11"): a 3-of-5 clock at 2 rows wrapped to "00:2 / 0" on the owner's POCO.
- (2026-11-10) "Sửa app": ~100 Lucide glyphs + ~100 emoji in horizontal 6x3 pages built when swiped to (`PagedGrid`); "Emoji khác…" = the keyboard's own emoji panel / search (no bundled 3,600-emoji table), first cluster split by hand (`AppLook.firstEmoji`: BreakIterator splits ZWJ on old ICU / JVM < 20), spec length up to 16; colour = one row opening `colorPick` (24 presets 44 dp, HSV sliders, hex); glyph ink turns dark on light plates (`AppLook.ink`). Study-app picker = `appPickSheet` (drawer search + icons + check). Another app's icon on an app: not built (a game could wear a study app's icon) - owner to decide.

### Website / docs
- Site is static HTML+CSS, no JS (CSP script-src 'none' on most pages; only the llms "Hỏi AI" button allows 'self' script), deployed by `npx wrangler pages deploy website --project-name kid-guard --branch main` (direct upload); not tied to git
- Guide pages (website/guide.html + en/guide.html) replace GitHub README anchor links — user: links to nonexistent anchors not OK; stable section ids listed in website-guide report: install, install-phone/qr/usb/cli/basic, pair, parent-app, away, tailscale, relay, shared-relay, features, iphone, remove, faq (26e4309)
- Download links use `releases/latest/download/kidguard(-parent).apk` (no edit per release); version only as a hero chip (5ee5aae). Versioned names kidguard-<v>.apk also attached because Chrome saved "kidguard (1).apk" with no version (36f479a)
- APKs stay on GitHub releases: same-origin /dl/ hosting was tried and reverted (open-source provenance, one source to maintain) (9d75f23, reverted 7acef5e)
- English README only (README.vi.md dropped) (bf164b5). Site CSS cache-busted with a content hash (fb3d5ec)
- Rule `monitor` ("Chỉ theo dõi"): `decide()` stays pure and always runs; `enforced(r, would)` empties it in monitor mode, so suspend / bounce / launcher / Quick tile see nothing while `state.wouldBlock` + a log line report what strict mode would do. Device-level `Policy.apply` restrictions and all reporting are unchanged; heads-up warnings are off.
- Release APK 7.4 -> 3.8 MB by dropping x86_64 and BouncyCastle PQC (747bc65)
- Widgets "timer" (Hẹn giờ) + "streak" (Chuỗi ngày), both flavors: timer state in device-protected prefs `kg_widgets2`, alarm = `setExactAndAllowWhileIdle` on ELAPSED_REALTIME (no exact-alarm permission: falls back to inexact on 31+), keyed by endAt so a stale alarm is ignored, lost on reboot (no boot receiver); streak day = list goes "not all done" -> "all done" (homework never resets daily, so "all ticked" alone would count every day), logic in `TimerLogic.kt` / `StreakLogic.kt` (unit-tested)

- Study mode (Học bài, 2026-11-09, `FocusLogic/Store/Ui`): launcher-local (prefs `kg_focus`, not in rules JSON). Hides non-study apps by forcing `Show.HIDE` in `render0` (never un-hides a parent block); DND set only if access granted and filter was ALL, restored by render, Guard tick, end-alarm `FocusEndReceiver` and `Policy.release`. No education category in `LibraryLogic` (none exists in ApplicationInfo), so study apps start empty + picker; `TimetableLogic` is school bells, not home study hours, so own weekly slots.
## 2. REJECTED / DON'T RETRY
- `DevicePolicyManager.setStatusBarDisabled` for shade blocking: also sets mDisableNotificationEffects, silencing ALL notification sound/vibration/heads-up (measured API 34). Also don't use lock-task status bar flags. Use Acc quick-settings closing (11-09)
- Self-drawn app-open zoom animation: unnecessary once the window shows the wallpaper (HyperOS then plays the system zoom) (11-09)
- Plain fallback line before `color-mix(... var() ...)`: does nothing (value parses, fails at computed time -> transparent). Reversed 555b36f by b05190c: use @supports blocks
- Same-origin APK hosting on the website (/dl/): reverted, back to GitHub release links (9d75f23)
- Sharing GitHub URLs to open updates: the GitHub app hijacks them and the download hung (4445879, 7acef5e)
- "Sắp xếp màn chính" arrange mode: only added a top bar that pushed the grid down (81131b4)
- Per-label scrim pills / sampling background behind each label (250a473)
- ImageView wallpaper redrawn per swipe frame (SurfaceView instead) (86c66de)
- withLayer on sheet slide / pager scale in edit mode, refill(2) first tiles, removing sheet elevation: no measurable gain on Oppo A5s (11-08 oppo-perf)
- Building widget-picker tiles one per frame before the slide: ~20 ms each, 17 janky frames (71-86 % jank). Use two lumps after slide-up (33 %) (HomeWidgets.kt:393)
- Showing the pairing code on the child screen with no PIN gate: child pairs own browser (7fc1506, S7)
- Writing `Settings.Secure enabled_notification_listeners` for notif dots: ignored since Android 8; use `cmd notification allow_listener` (86c66de)
- Generic "uiautomator dump" on the dev phone while testing accessibility: it suppresses other a11y services (Acc looks off). Use screencap (v0.7.0 notes)
- Self-assigned system-overlay/true-control-center style approach for first control center phase: stay in-launcher (no SYSTEM_ALERT_WINDOW); Phase 2 overlay not built (11-08 feasibility)
- Defaults of 1.6x font and fixed heights in child UI: use min-height + wrapping (d1c75da)
- Hiding the Poco-detected "gesture nav becomes 3 buttons" side effect: can't fix; the launcher warns before the user picks (HomeMenus.kt:139)
- Keystroke / keyword monitoring (FamShield does it on Windows): rejected, conflicts with "the child always knows"; see docs/research-famshield.md

## 3. PLATFORM GOTCHAS
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
- Protected apps (`Policy.protectedApps`, 2026-11-09): exempt from every limit = our package + system apps (HOME/dialer/alarm handlers, system keyboards, SMS/dialer default if system). A non-system default dialer or SMS app is limited like any app (emergency calls go through system `com.android.phone`/telecom, still protected). Dead-end exceptions kept exempt: a non-system default HOME (suspending the only home screen leaves no way to open anything) and the current default keyboard (no way to type the PIN); the HOME case raises a `tamper` event once per package (`Policy.foreignHome`, Guard tick), so the parent can remove the app or `hidden` it. Other enabled non-system keyboards are no longer exempt.
- Safe DNS timed pause (`safeDnsPauseUntil`, 2026-11-09): an absolute end time in the rules, not a command, so it works over LAN and relay alike and survives phone reboots; `Policy.syncDns` (Guard tick, 10 s) re-applies when the effective host (`Rules.dnsHost`) changes, so expiry needs no alarm. Compared with the phone clock: a parent clock far off shortens/lengthens the pause (same caveat as `pausedUntil`).
- Browser DoH vs safe DNS: with `safeDns` on, `Policy.applyDoh` pushes managed config `DnsOverHttpsMode=off` to Chrome (stable/beta/dev/canary) and Edge (policy exists on Android from Edge 147); cleared when safeDns is off and in release. Firefox Android has no managed-config/AppConfig support (Mozilla only turns its DoH off itself when enterprise policy/parental control is detected), Brave/Opera unverified: documented limitation, not blocked. Unverified on a real phone.
- Safe DNS presets (page `DNS()`): CleanBrowsing Family `family-filter-dns.cleanbrowsing.org` and AdGuard Family `family.adguard-dns.com` force SafeSearch (+ YouTube restricted, "where possible" for AdGuard; CleanBrowsing's YouTube level is Moderate/Strict per differing pages; the YouTube app may bypass); Cloudflare Family `family.cloudflare-dns.com` only blocks adult/malware (no SafeSearch). Plus "custom host".
- `noVpn` rule (default false): `DISALLOW_CONFIG_VPN` via `Policy.applyVpnLock`, the single owner (set if `noVpn` OR `Store.tsWanted`; Ts.kt and release go through it). It blocks the VPN settings UI and adding/enabling VPN profiles by the user; it does not uninstall VPN apps and an app the user already consented to may keep running, which we cannot stop without removing the app (use blocked/hidden apps). Our Tailscale always-on VPN is set by the DO and unaffected. Unverified on a real phone.
- macOS bash 3.2: empty array with `set -u` broke build.sh (882cd92)
- Behind a TLS proxy: TRUSTSTORE=/path.jks scripts/build.sh
- CI flakes: wrangler dev gives 503 on a fresh Durable Object and sometimes hangs: rerun; per-IP rate limit trips fast CI runners -> tests retry 429 (9e6042a)

## 4. PAST BUGS (cause -> fix -> avoid)
- LAN `rules PUT` kept pinHash without pinSalt -> PIN never matched again. Fix: copy salt with hash. Avoid: treat PIN hash+salt as one unit (1747923)
- Failed `release` left phone owner but unenforced (loop stopped on anything but "failed"). Fix: any non-ok result resumes enforcing (f032b5d)
- sosAck over relay never cleared SOS (random id) -> separate `sos` field (f032b5d)
- Relay re-sent old PIN hash and reverted a PIN set via LAN -> pinAt (f032b5d)
- Relay page lost edits: older poll overwrote PUT result -> take `rev` from the PUT reply (f032b5d)
- Kid-switch race / not-paired reload loop / stale rules save on the parent page -> gen token, bound to kid + object identity (1747923)
- Child could self-pair (code visible with no parent / no PIN) -> PIN-gated pairing, 24 h recovery (7fc1506)
- Self-update: `at` written before download (24 h wait after failure), only Exception caught, session leak, Policy.apply re-added DISALLOW_INSTALL during update, rejected tag redownloaded -> all fixed 7fc1506
- Un-hiding an app logged a false "Cài mới" -> fixed 7fc1506
- Parent app WebView render process gone killed the app -> recreate activity (7fc1506)
- Relay "queued" toasted as done; unpaired kid raised "Mất kết nối" (401/403) -> fixed (7fc1506, f032b5d)
- Merge "keep both" re-added stale settings markup -> JS syntax error -> node --check after merging
- Basic mode loops: accessibility detail page bounced forever (selfProtect before first pairing), Settings opened in own task, FallbackHome counted as home app (opening About phone dropped child to home) -> gate on everPaired, NEW_TASK (831bf98, 92f58e8)
- Broken pipe on LAN server killed the whole app (Oppo dropbox) -> catch (5f03cbe, Http.kt:61)
- Location track sparse on Oppo -> network+gps together, listener cleanup; gps was left on for hours (41cf1f9)
- Bottom tabs/admin bar background vanished on old WebView -> color-mix in @supports (b05190c)
- Duplicate Truecaller in dock (dialer that is also SMS app) -> de-dup (ce7a9de)
- Fresh grid growing 5 -> 6 rows left last row empty -> auto-filled layouts re-flow (ce7a9de)
- Widget resize 4x1 -> 4x2 jumped to last free page -> grows in place and reflows (435a991)
- Launcher menus cut off at bottom -> placed manually and flip above (3fce88c)
- Drawer pull-down velocity read ~0 px/s because the drawer moves with the finger -> measure in screen coordinates (b521b05)
- Keyboard close shrank the home grid -> pages use bars inset only (86c66de)
- Home render 340 ms on return -> sort key folded once, regex compiled once (73c2ae9); then Rules JSON parse cache, dialer cached per resume, banner checks 20 s cache, toJson cache -> return home 13-18 ms -> 2-5 ms (Oppo)
- Page reuse in buildPages: drop render 87 -> 25-32 ms (Oppo)
- folderZoom closed before first frame left stuck scrim; drawer rebuilt every minute reset scroll; renderRetry pile-up; pager.dragging stuck if paused mid-gesture; 400 ms debounce for package broadcasts (11-09 perf review)
- Drag: Poco flash/ghost on drop; library folder shrink-then-jump (5b81268)
- First "color-mix fallback" fix looked right but changed nothing (555b36f) -> verify on a real old Chrome (adb reverse + test page)
- Installed only kid APK after launcher change; user pressed Home and saw old build -> install both flavors
- Hold-button short tap gave no feedback -> toast "Giữ nút N giây" (7fc1506)
- Ring had no stop; mic banner stuck without stop button; ring pressable while listening; LAN dropped on screen-off (user complaint 11-07) -> stateful actions, WifiLock

## 5. OPEN BACKLOG (older reviews; most "unverified" against current code)
- Usage-access off = limits dead (event + banner only; DO can't lock the appop); Usage.kt misses an app already in front at midnight (unverified)
- Owner mode Android 8.x timezone bypass (no DISALLOW_CONFIG_DATE_TIME <28); basic mode wall-clock tricks (event only); no trusted time (unverified)
- `noDebug` default false: Wireless debugging + Termux/shell can switch off private DNS / usage appop; retryDns doesn't check drift (unverified)
- Firefox/Opera/Brave DoH still bypasses safe DNS (Chrome/Edge handled, see above)
- Basic bounce leaves audio/PiP running; guest user in basic mode; PiP/split usage undercount
- getKids bridge exposes tokens; shared phone/parent token (S4); deliver-once loss on destructive cmds (S8, fixed 11-09)
- Http pool 4+16 idle-socket lockout (fixed 11-09); SelfSetup set-device-owner retry after partial setup; save-fail bar lost on kid switch
- Zone circles only drawn when a track exists; weekly compare needs 14 days but phone keeps 7
- Release on-device un-hide path untested (covered only by DebloatTest); 24 h recovery untested on device; real-GitHub update path only tested with a fake GitHub
- Launcher perf open (fixed 2026-11-09: Recent.load generation guard, NotifDots recount debounced 250 ms, LauncherSafe main-thread only, pendingId leak; SKIPPED as risky or not a real cost: ensureWall/IconCache sync render (needed before the first frame, runs only on change), shareLabels/editBars (tap-rate, tiny), update()/applyEdit save off main (ordering vs crash safety), drag allocations, pageFade): ensureWall decodes/blurs on main; Recent.load thread per resume without generation guard; IconCache misses render on main; shareLabels allocs per measure; editBars rebuilt per pick; NotifDots recount not debounced; update()/applyEdit save JSON + full render(true) on main (HomeMenus.kt update, HomeEdit.kt applyEdit); pageFade offscreen layers on Today/Library pages; drag allocations (taken() copies per event); LauncherSafe counts any-thread crashes; pendingId widget leak
- Dock slot background reallocation per drag event: fixed 11-09; extra wallpaper layer cost (windowShowWallpaper) on Oppo A5s unmeasured
- Play Console appeal for Play Protect block (user's call)
- Convenience ideas (ranked in 2026-11-07-convenience-ideas): study schedule, evening summary notification, chores gating an app, kid focus mode, DNS per time slot, expected-in-zone alerts
- Everything on-device (real Oppo/Poco/Samsung) is the user's to verify; nothing tests ui/*.html or on-device behaviour automatically

## 6. OPEN ISSUES found by the 2026-11-09 flow trace (not fixed; verify before fixing)
Each was read from code only, nothing run on a device. Numbers restart per area.

### Enforcement
1. (fixed 2026-11-09: state `usageOff`, tamper event once per revoke (persisted flag `usageOffRaised`), child notification id 7 opens the usage-access screen, page banner; nothing is blocked) Usage access revoked => limits silently disabled. Guard.kt:162-167 only logs once + `tamper` event; `Usage.range` then returns empty (Usage.kt:34-35) so `daily`, `appLimit`, `groupLimit` never fire; owner mode does not re-grant the app-op (Settings is in `protected`, Policy.kt:201). Bedtime/schedule/blocked still work. Gap: no enforcement or auto-fix.
2. (fixed 2026-11-09, see decision below) User-chosen default HOME / dialer / SMS app and every enabled keyboard are `protected` (Policy.kt:209-227): never suspended/blocked, and excluded from `new`/daily counts (Guard.kt:171, Rules.kt:358). Any installed app that declares a HOME filter, set as default launcher, escapes all limits (comment at :212 says intended; with `noInstall=false` it is a bypass).
3. Midnight rollover undercount: `Usage.range(from=midnight)` (Usage.kt:33-57) never sees a MOVE_TO_FOREGROUND before midnight, so an app open across midnight gets 0 ms and `current=null` until its next event. Same for split-screen/PiP: "one app at a time" rule (:49) closes the other window. (fixed 2026-11-09) `Usage.range` queries 6 h before `from` and counts only time after `from`, so an app open across midnight is counted and `current` is set. Split-screen/PiP single-foreground rule unchanged (not fixed).
4. `Guard.release` timeout path (Guard.kt:501): after 20 s returns "failed" but the work-thread job may later finish "ok" - then no `stopSelf` is scheduled (:503 uses stale `res`) and Http/Relay keep running with `stopped=true`; parent got "failed". (fixed 2026-11-09) `release()` uses an atomic state: a job finishing "ok" after the 20 s timeout still schedules `stopSelf`.
5. `Guard.onCreate`/`onStartCommand` do not check `Store.released` (only `Guard.start` does, :693). A START_STICKY restart in the 2 s before `stopSelf`, or any system rebind, runs `Policy.apply` (non-owner: `LauncherSafe.apply`) and the loop after release. Low. (fixed 2026-11-09) `onCreate` stops itself when `Store.released`; `onStartCommand` returns NOT_STICKY and stops.
6. Duplicate-command answer: `command()` returns "ok" for a repeated id (Guard.kt:428) even if the first run failed (e.g. `release` -> `failed: N apps still hidden`); re-sending the same id looks successful. Parent page must use fresh ids. (fixed 2026-11-09) a command whose run returned `failed...` is forgotten (`Store.unmarkCmd`), so re-sending the id runs it again.
7. Clock skew vs relay rules: relay rules apply only if `rules.rev > store.rules.rev` (Relay.kt:109) while LAN saves stamp `rev = max(old+1, phone now)` (Api.kt:81). If the phone clock is ahead of the parent/worker clock, later relay edits are ignored until the worker's rev catches up. protocol.md:10 says "newest wins" without this caveat. (doc-only, fixed 2026-11-09) caveat added to protocol.md rules `rev`. Code unchanged; a monotonic rev from the worker is the real fix (relay area).
8. `fromJson` full replace (Rules.kt:228) + LAN PUT (Api.kt:77): a partial or older-client PUT wipes `hidden`, `groups`, `launcher`, `lockInfo`, `zones`, `chores` etc. (silently, only a rule-diff log line). Not in protocol.md ("Unknown fields are ignored" but missing ones reset). (doc-only, fixed 2026-11-09) protocol.md now says missing fields reset.
9. `suspendedByUs` persisted after the DPM call (Guard.kt:192-196): crash in between leaves apps suspended but untracked; the loop never un-suspends them even after the rule is removed (only `release` clears: Policy.kt:420). Rare. (fixed 2026-11-09) the loop persists `had + want` before the DPM call and the real result after.
10. Two location cadences: `locate()` every 15 min hard-coded (Guard.kt:207) in addition to `trackMin`; protocol.md:288-291 describes only `trackMin`. With `trackMin=0` the phone still fixes every 15 min (battery/privacy vs doc "0 = off"). (doc-only, fixed 2026-11-09) protocol.md Location history documents the 15 min refresh; kept in code (last fix and zone events rely on it).
11. Bonus is silently ignored when the day has no limit (`base == 0`, Rules.kt:359) - correct but a child-visible "limitMin" of 0 hides the bonus (Guard.kt:316). Doc `bonus` line (protocol.md:21) does not say. (doc-only, fixed 2026-11-09) `bonus` line in protocol.md says it is ignored without a base limit.
12. `Watchdog` start from a background alarm (Watchdog.kt:13 -> `startForegroundService`) is allowed for Device Owner, but in Basic mode on Android 12+ it may throw ForegroundServiceStartNotAllowedException; error is only logged (Guard.kt:695). Unverified on a real basic-mode phone. Cosmetic: odd indentation at Watchdog.kt:27. **NOT FIXED:** needs a real basic-mode Android 12+ phone to confirm; indentation at Watchdog.kt fixed (2026-11-09).
13. (fixed 2026-11-09: `Policy.savePrior`/`restorePrior`; "off" restore unverified on device) `Policy.release` leaves `setAutoTimeEnabled(true)` (Policy.kt:58) and rewrites Private DNS to opportunistic (Policy.kt:110) - AGENTS.md says release must undo every restriction; these are settings, not restrictions, but not reverted to prior values (protocol.md:82 silent).
14. `Totp.check` order (Rules.kt:127): tries t, t-1, t+1 and a hit on t+1 marks `totpLast=t+1`, so the genuine current code for the next 30-60 s is rejected as "used". Minor UX. (checked 2026-11-09, not a bug) a t+1 hit stores `totpLast=t+1` and the next code (t+2) is still accepted; only a replay is refused. Test `totpEarlyCodeDoesNotLockOutTheNextOne` added.
15. Admin unlock via TOTP when a PIN is also set: a wrong 6-digit code hits only the PIN counter (Store.kt:181-183), the TOTP counter (`unlockFails`) is untouched, so brute-force limits are shared with PIN (5 free tries, then 5 min) - fine, but `tryUnlock` (child unlock) and `checkAdmin` use different counters, giving 2 independent 5-try budgets on the same secret. protocol.md:543 says "own counter" only for unlock. (doc-only, fixed 2026-11-09) protocol.md TOTP line spells out the separate counters.
16. Doc/code OK checks (no mismatch found): priority list (protocol.md:35 vs Rules.kt:337-361), `allow` not exempting groups/appMin (protocol.md:534 vs :338-346), release steps (protocol.md:283 vs Policy.kt:417-465), release-failed string `failed: N app còn bị ẩn` (strings.xml:217), wind-down window (protocol.md:596 vs Comfort.kt:16-23), cmd dedup (protocol.md:272).

### Parent <-> phone
1. **App-mode device name is always blank.** Page sends `X-KG-Name: "KidGuard app / Android"` (`UI/core.js:140-149`) but `Proxy.forward` only copies `X-KG-Token/W/H/Content-Type` (`P/Proxy.kt:100`), so `Store.touchDevice` (`Api.kt:68`) never sees a name for parent-app pairings; the devices list shows "no name". Protocol l.74 promises the header. Also the CORS list for direct browsers is fine; only the proxy drops it. (fixed 2026-11-09): Proxy.forward now forwards `X-KG-Name`.
2. **`Relay.sync` can lose a delivered ONCE command.** `rules` handled first with no `runCatching` (`Relay.kt:102-110`); an exception (bad rules JSON, `setRules`) aborts before `cmds` loop (`:111`), `acks` already cleared (`:101`). The worker already deleted ONCE cmds (`index.js:245`), so a `release`/`reboot`/`unpair` is silently dropped. Same loss if the HTTP reply is cut after the worker commits. Protocol l.272-276 describes "idempotent" but not this at-most-once gap. (fixed 2026-11-09): rules run in runCatching; worker keeps ONCE cmds until acked (10 min window, phone dedups by id); protocol.md v0.2.1.
4. **Protocol gaps:** no section for `POST /c/<id>/sync` (body `{state?,acks}`, reply `{cmds, rules?, live?, files?, device?}`), nor `GET /c/<id>/state` shape `{state,rules,seen,pending}`. Protocol l.272-273 lists deliver-once as only `reboot, release, unpairAll`; code `ONCE` has 8 (`index.js:57`: + `unpair clearData update totpSet fileDel`; some documented piecemeal, `totpSet`/`clearData` not at all). The contract test only checks `ONCE`⊂`CMDS` and three names. (fixed 2026-11-09): protocol.md now has sync/state shapes, full ONCE list, clamp.
5. **Undocumented LAN bits:** `PUT /api/files/<name>?id=<fid>` (`Files.kt:84 r.query["id"]`: caller picks the entry id; purpose unclear) not in protocol "Shared files"; `Api.kt:50` `/hello` accepts any method. (fixed 2026-11-09): documented in protocol.md (LAN + Shared files).
6. **Input `ms` clamp differs:** worker 50..2000 (`index.js:232`), phone 50..3000 (`Acc.kt` swipe), protocol silent. (fixed 2026-11-09): both 50..3000.
7. **Stale commands fire late:** non-ONCE relay commands (`lock ring wake message locate setting devOn devOff`) stay 24 h (`index.js:242,298`); a `ring` or `lock` queued overnight runs when the phone reconnects. Page only warns "offline" (`kid.js:116`). Worth a short TTL for these types. (fixed 2026-11-09): worker TTL 10 min for ring/lock/wake/message/locate/setting/dev*.
8. **LAN thread pool starvation:** pool is 4 threads + 16 queue (`Http.kt:37`); `/api/listen` (<=10 min) and large file transfers each hold a thread, plus long `/screen` loops. 4 concurrent ones starve `/state` for other pages; overflow closes the socket silently (`Http.kt:48`). (fixed 2026-11-09): 16 threads + 32 queue, 6 long-route slots, 503 instead of silent close.
9. **Relay id on worker `answer`:** worker keeps `body.id` as command id only if it matches `^[\w.-]{1,64}$` (`index.js:301`); otherwise it becomes a UUID and the phone's `answer` handler reads `c.optString("id")` (`Guard.kt:455`) = wrong id -> "no such request". Works today only because request ids are short alnum. Same fragility noted in the `sosAck` comment (`Guard.kt:460`). (fixed 2026-11-09): worker copies request id to `req`; Guard answer reads `req` first.
10. **Repo noise:** `.claude/worktrees/agent-*/` hold full stale copies of the tree (old `web/ui/core.js`, `Api.kt`); greps for `X-KG-Name`, `Api.kt`, etc. hit them. Exclude when searching.

### Launcher
1. `L/HomeDock.kt:87` `sosMenu` still offers SOS (dials 112 unpaired) in Home flavor, while Flavor.kt says SOS is left out there. (fixed 2026-11-09) Home flavor: sosMenu drops the SOS row, keeps 112-115 dial rows.
2. (SKIPPED: product decision; recommend keeping Home parent-free, kid todos stay in the todo widget) `L/TodayPage.kt` chores card excluded from HOME_CARDS but it is the only place for kid homework todos; Home user reaches them only via the todo widget.
3. `HomeLayout.hasWidget(m,"next")` hides the clock's next-event line even when "next" is not renderable in Home (imported layouts). (fixed 2026-11-09) HomeActivity gates `hasNext` with `Flavor.widget("next")`.
4. `L/HomeLayout.kt` ~540: orphan doc comment "Ids of the other apps' widgets..." sits above `imported()`. (fixed 2026-11-09) KDoc moved to `appWidgetIds`.
5. `L/TodayPage.kt` ~488 `counts()` uses literals "in"/"gone"/"opened" instead of `FilesLogic` constants; `pad()` `padBottom` only stored. (fixed 2026-11-09) `counts()` uses FilesLogic constants; `padBottom` untouched.
7. `L/LauncherSafe.kt` uncaught handler counts any crash (e.g. Guard thread) as a launcher crash while `HomeActivity.alive>0` -> can reset kid layout or trip 24 h safe mode. (fixed 2026-11-09) handler counts only main-thread crashes.
8. `L/HomeAppWidgets.kt` `aw.pendingId` lost if process dies mid-bind (leaked host ids); `awSync` does not clean orphans after `HomeFile.reset`. (fixed 2026-11-09) pending id persisted (`awPending`), deleted at first awSync if not in the model; orphans after HomeFile.reset NOT cleaned (the host id list is a hidden API).
9. `L/HomeExport.kt` `pull`: wallpaper `WALL_MAX` checked after the full copy. (fixed 2026-11-09) copy stops at WALL_MAX.
10. (partly fixed: NotifDots.granted cached; rest skipped: render0 key needs Files.all and launcherApps binder cost unmeasured) Perf: `HA.render0` ~445-516 runs `Files.all()` (disk) and builds a long key on every minute tick; `launcherApps()` HA:311 is binder-heavy; `NotifDots.granted()` binder call per resume and in `CustomActivity.build` (:128).
11. (skipped: 30 s snapshot is the Guard tick cadence, the lag is by design) `HA.decision` HA:338 uses a snapshot up to 30 s old: rule change lags on screen.
12. `HomeMenus.openFolder` silent return when `lastView == null` (tap does nothing). (fixed 2026-11-09) renders first when lastView is null.
13. `Recent.load` (Recent.kt:21) is a binder call documented "run off main"; confirm every caller (drawer `refill`) honours it. (fixed 2026-11-09) only caller is the onResume thread; generation guard added.
14. `CustomActivity.kt:128` recomputes `NotifDots.granted` on every `build()` (each tap) = binder call per tap. (fixed 2026-11-09) `NotifDots.granted` cached 5 s (`fresh=true` on return from settings).
15. `Wallpaper.kt` `dim()` has a stray orphan KDoc ("Darkens...") above the `blur` KDoc (lines ~160-163). (fixed 2026-11-09) KDoc moved above `dim`.

### Setup / build / release
2. `README.md:208` test command `testDebugUnitTest` is ambiguous with flavors (app has kid/home); AGENTS.md uses `testKidDebugUnitTest`. **(fixed 2026-11-09)** README already used testKidDebugUnitTest; no other bare testDebugUnitTest left.
3. Website deploy and GitHub release creation are undocumented and unscripted; `scripts/site.sh` mentioned in git (7acef5e) was reverted; `stamp-site.sh` header comment is the only trace. Release asset names are a hard contract with Update.kt / QR / site but nothing checks them. Tag v0.9.10 exists but no tool verifies that a release with assets exists. **(fixed 2026-11-09)** added scripts/release-check.sh (HEAD-checks the 9 asset names + latest) and documented Pages deploy/release steps in flows.md 4.3.
4. build.sh stamps/commits `ui/qr.html` and `ui/provision.json` and site pages as side effects of building: any local build (even debug-intent) dirties tracked files; CI cannot run build.sh (signing). **(fixed 2026-11-09)** documented in flows.md 4.3 (side effects listed, review the diff); behaviour unchanged; CI now compiles home+parent debug instead.
5. CI never builds `assembleHomeRelease`, parent release, or checks `home` flavor compile; only `testKidDebugUnitTest`. Home flavor breakage would only show at release time. **(fixed 2026-11-09)** CI runs assembleHomeDebug :parent:assembleDebug.
6. (fixed 2026-11-09: protocol.md Setup steps now states the SelfSetup differences) SelfSetup omits protocol steps 7-8 (am start, LAN URL) while `protocol.md:248` says all do "the same, in this order"; it also adds accounts pre-check and Wi-Fi-off that protocol does not list. Others (setup.sh/ps1/install.html) have no accounts pre-check (rely on dpm failing).
7. install.html does not verify APK integrity (no SHA-256) while setup.sh does; setup.ps1 not checked for sha parity (unverified, grep showed no sha in the install lines). **(fixed 2026-11-09)** install.html verifies kidguard.apk.sha256 via WebCrypto when the server serves it (skipped if absent); setup.ps1 already had SHA-256 parity (Get-FileHash).
8. Signing/keystore passwords hardcoded `kidguard` in `app/build.gradle.kts:27-31` and `parent/build.gradle.kts` and build.sh:12; key backup not documented. Lost key = all phones need uninstall + redo setup. **(fixed 2026-11-09)** password/key untouched; key backup documented in flows.md 4.3.
9. `build.sh` default `URL` fallback hardcodes upstream repo `HoangTran0410/kidguard` (`:44`); forks get QR pointing at upstream APK, and upstream checksum would mismatch. **(fixed 2026-11-09)** build.sh fallback URL derived from the origin remote (upstream only if not a github remote).
10. `cd` path `ls website` shows no `dl/` or `.gitignore` entry check; `.gitignore` had 3 lines added in 7acef5e for `/dl` (possibly stale after revert; unverified). **(fixed 2026-11-09)** no /dl lines exist in .gitignore anymore; nothing to do.
- (2026-11-09) Per-app rename/icon = `HomeModel.custom` keyed by package (`AppLook.kt`; IconCache key includes the spec; search folds custom + real label); reading aids = `HStyle.speak/calm/solid` + font "easy" (system sans-serif-medium + 0.06 em spacing, no bundled font); reduce motion = ValueAnimator.setDurationScale(0) by reflection (best effort) + lite fx; TTS needs the `TTS_SERVICE` `<queries>` entry on Android 11+.

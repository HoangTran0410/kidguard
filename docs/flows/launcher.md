<!-- part of docs/flows/ (index: README.md) -->
Path shorthands (`K/`, `L/`, `UI/`, `P/`): see `README.md`. No line numbers: grep the function name.

## 3. Kid launcher

Base: `android/app/src/main/kotlin/dev/kidguard/launcher/` (abbrev `L/`). All files `package dev.kidguard`. `HA` = HomeActivity.kt. Not read in detail: HomeWidgetsLive/Lists/Plan/Kid bodies, HomeFolder body,
HomeLibrary/HomeControl bodies (only signatures), Wallpaper draw(), PageDots, KidNotes, TimetableLogic, WidgetDraw.

### 3.0 Where is X

| Feature | Where |
|---|---|
| Activity state, lifecycle | `L/HomeActivity.kt` onCreate, onResume, onNewIntent, onBackPressed |
| Installed app list | `HA.launcherApps()` (queryIntentActivities LAUNCHER, skip HomeEntry, label cache) |
| Show / pause / hide decision | `HA.decision()` + `LauncherLogic.show()` (L/LauncherLogic.kt) |
| Render pipeline | `HA.render`, `render0`, `buildPages` |
| Wallpaper bitmap | `HA.ensureWall`, `Wallpaper.draw/photo/blur/dim` (L/custom/Wallpaper.kt), `WallSurface` |
| Palette / presets / accents / walls | `L/custom/HomeStyle.kt` (PRESETS, ACCENTS 83, WALLS, `palette` 100, `ink` 138) |
| Icon bitmaps (mask, grey) | `L/IconCache.kt` `get` 36 (LRU = heap/5, 8-96 MB), `greyPaint` |
| Launch app | `L/HomeIcons.kt` `launch` (~165), `debounce`, `reopenShort/When` |
| Pure layout model | `L/HomeLayout.kt` (fresh, parse, place, drop*, group, moveTo, removeIds, removePage, resizeWidget, regrid, fitRows, placeAppWidget*) |
| Persistence | `L/HomeFile.kt` (filesDir/home.json, tmp+rename, `.bad`) |
| Pager (pages, swipe) | `HA.Pager`; `HA.CellLayout` |
| Drag and drop | `L/HomeDrag.kt` startDrag, hover, drop, landNow, endDrag, dockTarget, folderPlate |
| Long-press menus | `L/HomeMenus.kt` menu, appMenu, emptyMenu, pausedSheet, update, homeSettings |
| Edit mode | `L/HomeEdit.kt` enterEdit, exitEdit, editBars (remove = minus badge) |
| Dock, SOS, bars | `L/HomeDock.kt` buildDock, sosMenu 87, inkPass |
| Drawer + A-Z rail | `HA.Drawer` (refill, rail touch, scrubTo, follow); open/close in HomeDock.kt |
| Swipe up/down on home | `HA.SwipeUp`; shade pull `Shade.kt` / `HomeGestures.kt`; `openControl` L/control/HomeControl.kt |
| Folders | `L/HomeMenus.kt` openFolder/folderSheet/renameFolder/addToFolderSheet; `L/HomeFolder.kt` (FolderGrid, folderDragBegin/Evt, folderZoom) |
| App Library page | `L/HomeLibrary.kt` (`LibraryLogic.groups`, libraryOut/libraryBack/libraryFill) |
| Today page | `L/today/TodayPage.kt` |
| Own widgets | `L/widgets/HomeWidgets.kt` (design system at top, SIZES), `HomeWidgetsLive/Lists/Plan/Kid` |
| Other apps' widgets | `L/widgets/HomeAppWidgets.kt` (awAdd, awConfigure, awPlace, awSync, appWidget) |
| Widget picker | `HomeWidgets.kt` openPicker, `awPickerSection` |
| Notification dots | `L/NotifDots.kt` (listener), `Dots.refresh`, `DotHost` interface in icons |
| Customization screen | `L/custom/CustomActivity.kt` build, set, regrid, presets, pick/savePhoto |
| Recent apps row | `L/Recent.kt` (usage stats, else local list of 12) |
| Control center | `L/control/HomeControl.kt` (`CcSys`, `ControlLogic.mode`, toggles) |
| Safety / crash fallback | `L/LauncherSafe.kt` wanted, apply/on/off, uncaught handler, safe mode |
| Flavor switch | `L/Flavor.kt` (HOME = BuildConfig.HOME), `L/HomeEntry.kt`, `src/home/AndroidManifest.xml` |
| Kid-to-Home layout import | `L/custom/HomeExport.kt` (provider "layout"/"wall", same-signer check) |

### 3.1 Start / render / page structure

Trigger: HOME press, boot, `onNewIntent`, rule change, package change, minute tick.
1. `onCreate`: loads `HomeFile`, builds root (WallSurface, Pager, dock, hint, drawer prebuilt), `pager.jump(1)`.
2. `onResume`: registers `timeRx` (minute) and `pkgRx` (package add/remove); `NotifDots.setOn(style.badge != "off")`;
   applies `LauncherSafe`.
3. `render(force)` -> `render0` 445: builds key string `k` (model JSON, labels, show map, want map, block, banner,
   timeKey, rules...). `!force && k==key` -> only `tickClocks`. `busy()` (drag/anim) -> retry in 1500 ms.
4. `buildPages` 632: `PageBuilt` signature per page; `pageGlobal` + `reusePages` reuse built pages on layout-only edits.
5. Today page filled on idle (`addIdleHandler`,) unless it is the current page; drawer prebuilt at.
Page structure (pager children): [0] TodayPage, 1..n `CellLayout` grids, last = `LibraryPage` if `style.lib`.
`pager.minPage` = 0, or 1 when `style.today` is off (child 0 still exists so numbers do not move).
State/persistence: `home.json` (see docs/protocol.md "home.json"). Atomic write, in-memory cache, bad parse -> `.bad` + fresh.
Gotchas: banners cached 20 s (`bannerNow`); `render` must not run while `busy()`; auto rows (`fitRows`->`autoRows`->`update(regrid)`).
Paging: Pager.settle fling or 30 % width; rubber band 1/3 past ends; long press on empty space -> `emptyMenu`.

### 3.2 App list refresh (install / uninstall / blocked)

Trigger: package broadcast, rule push, time tick, `Policy.invalidate()`.
1. `pkgRx` (HA onResume): `apps = null; Policy.invalidate()`, debounced `pkgLater` 400 ms -> `render(true)`.
2. `launcherApps()` re-queries PackageManager; label cache; category + system flags (for the Library).
3. `decision(pkg)`: uses `Guard.snap` when < 30 s old, else runs `decide()` locally.
4. `LauncherLogic.show()`: HIDE for plumbing, `r.hidden`, `r.blocked`, reason in HIDE_REASONS (blocked/new), or settings app while `!settingsOk`.
   PAUSED for any other `want[pkg]` reason (grey + clock badge, tap -> `pausedSheet`). `pkg==self` always OK.
5. `HomeLayout.appendNew` places genuinely new apps on the last page; `prune` drops uninstalled ones; `seen` = placed or
   removed apps, never auto re-added (kid removing an app from home keeps it in drawer only).
Gotchas: `settingsOk = r.launcher.settings || (!owner && !paired)`; new app is "new" (hidden) until parent allows (rule `blocked/new`).

### 3.3 Launching an app

Trigger: tap on icon (grid, dock, drawer, folder, library).
1. `HomeIcons.launch`: `show()` != OK handled before (PAUSED -> `pausedSheet`).
2. `debounce` 600 ms; `Recent.note(pkg)`.
3. Explicit `ComponentName` intent, flags `NEW_TASK|RESET_TASK_IF_NEEDED`, `sourceBounds`.
4. Animation: `ActivityOptions.makeClipRevealAnimation` from the icon view (system zoom). Fallback `getLaunchIntentForPackage`, else toast.
5. HyperOS needs the home window to show wallpaper (SHOW_WALLPAPER in both theme files) or it slides (memory file).
Gotchas: `reopenShort/When` handles apps that re-open the launcher; Home flavor's system apps open the same way.

### 3.4 Drag and drop / page flip / resize widget

Trigger: long press on item (`arm()`), then move 8 dp; `immediate` for folder panel and picker.
1. `arm()` long press -> `menu`; moving past 8 dp -> `startDrag` -> `startDragAndDrop(DragSt)`.
2. `onDrag` -> `hover` (HomeDrag.kt) -> `drop` 321 -> `endDrag` 397 -> render -> `landNow` 356 springs item into its cell.
3. Edge flip: 48 dp zone, 400 ms dwell, creates `tempPage` past the last page (removed if nothing dropped).
4. Folder-on-drop: `FOLDER_MS`=350 and `nearCentre` (<0.36 width); swap preview after `SWAP_MS`=220.
5. Remove: magnet circle (pull 170 dp, hot zone 64 dp) -> `HomeLayout.dropRemove`.
6. Dock: `dockTarget`/`gapAt`; `dockRoom` decides insert vs replace; slot 0 fixed `@phone`; DOCK=4.
7. Pure rules: `dropCell`, `dropMove`, `dropOnto`, `dropDock`, `insertDock`; limits cols 3..5, rows 4..7, <=20 pages, <=60 items/page, FOLDER_MAX 20.
8. Resize widget: `HomeMenus` widget menu -> `HomeLayout.resizeWidget` (grows in place + reflow, test added; see goal.md). Allowed sizes from `HomeWidgets.SIZES`.
State: only `home.json` on drop. Gotchas: `busy()` blocks render during drag; `ACTION_DRAG_ENDED` must run `endDrag` once.

### 3.5 Edit mode (multi-select)

Trigger: menu "Sắp xếp" / long press empty space.
1. `enterEdit(pre)` (HomeEdit.kt): pager scaled 0.86, dock/hint hidden, `picked` set, `editBars` top "Đã chọn N / Xong".
2. `CellLayout.onInterceptTouchEvent`: while `editing`, a quick tap picks the item under it; the minus badge -> remove (confirm modal).
3. Nothing picked: select-all, delete page (`removePage`), widget picker. Picked: none, group (>=2 apps -> `group`), move (`moveTo`), remove (`removeIds`).
4. `exitEdit` on Back, Home, onPause.
Gotchas: the Library page is taken out of the pager during edit (`libraryOut`), put back by `libraryBack`.

### 3.6 Folders

Trigger: drop app on app (dwell 350 ms centre), edit-mode group, folder tap.
1. `HomeLayout.dropOnto` / `group` create `HItem(kind=folder, apps)` (FOLDER_MAX 20; `folderKind`).
2. `openFolder` (HomeMenus) needs `lastView`; zoom via `folderZoom`; `folderSheet` shows a `FolderGrid`; drag out via `folderDragBegin/Evt`.
3. `renameFolder`, `autoName` (name from app categories), `addToFolderSheet`.
4. Icon: `HomeIcons.folderIcon` (3×3 mini preview, max 9, dot sum via `DotHost`).
5. Open over the home: the dim (`WallDim`) paints the wallpaper bitmap + a light dim and fades in, so the home disappears behind it; `content` goes INVISIBLE once open, `homeBack()` restores it (close, drag-out). No wallpaper bitmap / drawer behind: plain 200 dim (+ the home fades as a layer if it is the home).
State: inside `home.json` item `apps`. Gotchas: `openFolder` returns silently if `lastView == null`.

### 3.7 Drawer search and A-Z rail

Trigger: swipe up on a home page (`SwipeUp`), dock handle, Home button when on page 1.
1. Drawer inner class is kept (`drawerCache`) and prebuilt at.
2. Open/close follows finger: `drawerAt`/`drawerRelease`, opens at 40 %, closes at 60 % (`drawerOpens`).
3. `refill`: builds rows lazily per frame; no query -> "Dùng gần đây" row (`Recent.load`: usage stats 3 days, else last 12 opened here).
4. Search: accent-folded (`LauncherLogic.fold`/`matchesFolded`), Vietnamese Đ separate letter.
5. Rail: letter from `LauncherLogic.letter` (digits -> "#", other -> "..." bucket); touch; `scrubTo` 1013 lights one letter, dims others; `follow` 1030 keeps the list in sync.
6. Pull down closes; keyboard via InputMethodManager.
Swipe down on home: x0 > width/2 -> `openControl()` (kid control center), else the notification shade (`Shade.kt` / `HomeGestures.kt`: reflection on StatusBarManager).
Gotchas: `Recent.load` is a binder call; must run off main thread (note in Recent.kt) - verify callers.

- ⋮ next to the search field -> menu "Hidden apps" -> `hiddenScreen()` (`L/HomeHidden.kt`): an overlay grid of every app the drawer leaves out with its reason (`LauncherLogic.hiddenReason`, unit-tested, same order as `show()` + study mode): parent-hidden / blocked / waiting for OK / study time / KidGuard-managed Tailscale. Plumbing (Play services & co.) is one count line. A tap only toasts the reason: nothing launches from there (it would bypass the rules). Our own `HomeEntry` is skipped by package, not class name (the Home flavor shares the class).

### 3.8 Today page ("Hôm nay")

`L/today/TodayPage.kt`; rebuilt on idle. Cards (hidden ones in `HomeModel.todayHide`, "Tuỳ chọn thẻ" modal):
- header: call + SOS (never hideable; absent in Home flavor)
- `now`: greeting, current/next school period, countdown to next lock/bedtime
- `time`: screen time left + top 3 apps; empty: "no apps used, no limit" text (no action: limits are the parent's)
- `sched`: from `LauncherLogic.today` (parent schedules, group hours, bedtime); empty: text + link to the kid's timetable (`openTimetable`)
- `chores`: parent chores + kid's own homework todos
- `tomorrow`: only in the evening (`TimetableLogic.eveningFirst`); empty: text + "Tạo thời khoá biểu" -> `openTimetable(tomorrow)`
- Timetable sheet "⋯" (`openTimetable` head): "Gửi thời khoá biểu" = ACTION_SEND plain text (`TimetableText.write`, vi/en labels by locale); "Nhập từ chữ" = paste box (pre-filled from the clipboard when it parses) -> `TimetableText.read` = full replace of subjects, bells only when the text has them. Test `TimetableTextTest`.
- `notes`: kid notes (share-with-parent flag; hidden switch in Home)
- `files`: parent->kid files + kid's outbox; hidden when empty AND never paired (no one to share with)
Rule (0c95d99): cards show an empty state with a way to fill them, never vanish; data the parent owns gets explanatory text, not a create button.
Home flavor: only now, sched, tomorrow, notes (HOME_CARDS).

#### 3.8b Kid-side asks (0.9.16, `K/ui/KidActions.kt`, `KidNow.kt`)
- One open request (`Store.openRequest`): the block sheet (`HomeMenus.pausedSheet`) and "Xin thêm giờ" show "Đã xin lúc HH:mm, đang chờ bố mẹ" + "Nhắc lại" (`addRequest` -> new / remind / wait toast); the parent's last answer (`Store.lastAnswer`) stays on the sheet. Blocked / new app: "Xin mở <app>" = kind `app` with pkg + label.
- "+1 phút" on the sheet: `OneMore.allowed(rules, nowWhy, store.oneMoreKey)` -> `Guard.oneMore()`. No Ask when `nowWhy` is lost / paused.
- SOS: unpaired -> modal "calls 112"; paired + offline -> "Chưa có mạng" + "Gọi bố mẹ" (`KidNow.parentNumber`: `lostPhone`, else a number in the lock-screen contact); online -> "Đã gửi".
- Basic mode: `K/ui/BlockCard.kt` is the block card (reason / reopen time / Ask / OK). `Acc.bounceApp` should call `BlockCard.show(service, label, pkg, code)` before posting its notification.
- Study mode: 10 s "Hoàn tác" under the chip after a hand start (no PIN); `gate()` takes title + button.

#### 3.8c Kid "Tuần này của bạn" (0.9.17, `L/today/KidWins.kt`)
- Trigger: Today rebuild. Steps: days within the daily limit + current run from `Usage.range` over 7 days (off the UI thread), list-finished days from `StreakStore`; card is hidden when there is nothing to celebrate and in the Home flavor.
- Gotchas: no stored history (OEM usage retention varies); chores done and bedtime kept are not counted (no record).

### 3.9 Widgets

Own: `Flavor.widget(type)` filters; types clock, analog, month, time, next, quick, todo, note, counts, usage, battery, lock, when,
earn, msg, class, timer, streak, media, weather, clean (+ "app" for host widgets). `clean` (2x1, 2x2, kid flavor only; `cleanWidget` in HomeWidgetsPlan.kt): offered in the picker only while rule kidClean is on; tap = `KidClean.run` (same as the KidGuard-screen tile, one shared 10-min cooldown in prefs `quick`/`cleanAt`); rule turned off later = the placed widget says "Bố mẹ chưa cho dọn". Render reads only the rule + the stamp. Picker `openPicker`: one "KidGuard" card, 2-col tiles + size pills, drag via `Src.NewWidget`,
lazy "other apps" section (`awPickerSection`). Home flavor allows clock, analog, month, battery, todo, note, class, timer, streak, media, weather.
`weather` (2x1, 2x2; `HomeWeather.kt`, pure `WeatherLogic.kt`): renders from the prefs cache `kg_weather`, a background thread (last known location -> Open-Meteo -> Geocoder place) redraws it; refresh when > 30 min old on render / minute tick (only while the launcher is in front). No location permission = the card says so; a tap asks (kid flavor only, the Home manifest removes location). Tap = the phone's weather app (`openRelated`).
Other apps': `HomeAppWidgets.kt`: `awAdd` allocates id (host id `AW_HOST_ID` 0x4b47) -> `bindAppWidgetIdIfAllowed`, else ACTION_APPWIDGET_BIND
(REQ_BIND) -> `awConfigure` (REQ_CONF) -> `awPlace` (`HomeLayout.placeAppWidget`). `awSync` deletes ids that left the model.
`appWidget()` shows a locked card if the host app is PAUSED/HIDE, a "gone" card if the provider vanished. Sizes from provider min/resize.
Gotchas: pending bind id lives only in memory (`aw.pendingId`).

### 3.10 Notification dots

`L/NotifDots.kt`: `NotificationListenerService` counting per package non-ongoing, non-group-summary, non-FGS notifications whose ranking
allows badges; counts only, nothing stored. `onChange` -> `Dots.refresh` -> icons (apps; folders = sum). `setOn` on resume from
`style.badge` ("off"/"dot"/"num"). Access is granted at setup (`cmd notification allow_listener dev.kidguard/dev.kidguard.NotifDots`,
docs/protocol.md "Setup steps"); `CustomActivity` shows a "need access" note + opens system listener settings when missing (CustomActivity.kt).

### 3.11 Customization screen (CustomActivity)

Trigger: menu "Tuỳ chỉnh". Same task, not in recents; reloads home on resume if `HomeFile.load` differs.
1. `build()` CustomActivity.kt rebuilds only ScrollView content (no scroll jump).
2. Sections: live preview, saved layouts (`L/custom/HomeSaved.kt`: up to 5 named home copies in filesDir/layouts/, tap = switch via `HomeBackup.apply`, one undo; presets removed 2026-11-11), grid cols stepper, icon size, labels, font face, shape, dock bg/labels,
   Today on/off, Library on/off, fx (full/lite/auto), dots (off/dot/num), wallpaper (photo + 13 built-ins), blur + dim sliders,
   accent, theme, text size, reset.
3. `set(f)` -> `guard()` -> `save()` = `HomeFile.save` + `IconCache.clear`; `regrid` offers Undo for a smaller grid; presets apply in one `set`.
4. Photo: `pick()` (API 33 PICK_IMAGES else OPEN_DOCUMENT) -> `savePhoto` on a thread: EXIF rotate, downscale to <=1080 wide, centre-crop,
   JPEG <=300 KB to filesDir/wall.jpg (tmp+rename) -> `style.wallKind="photo"`.
5. Locked (`rules.launcher.lockCustom`): read-only with note; `guard()` toasts. Lock hint hidden in Home flavor.

Xiaomi / Poco only: switch "Vuốt thay cho 3 nút" (`Store.navGest`, `NavGest`): hides the 3-button bar and HyperOS keeps its edge Back; an Acc overlay strip at the bottom gives Home / Recents; bar returns whenever Acc is off / paused / a bank app is in front (details: docs/decisions/launcher.md 2026-11-15).

### 3.12 Parent `launcher.*` rules

| Rule | Effect | Where |
|---|---|---|
| `on` | enables HomeActivity component + persistent preferred HOME (owner) | LauncherSafe.wanted/apply 34-45 |
| `settings` | shows Settings app (+ control center tiles open system pages) | HA `settingsOk`, LauncherLogic.show |
| `notes` | blockNote shown in pausedSheet | HomeMenus.kt |
| `lockCustom` | CustomActivity read-only | CustomActivity.kt |
| `style` | preset only for first `home.json` | HA `firstModel` |
| `shade` | `LauncherSafe.shadeBlocked`; Acc closes quick settings | Acc.kt; HomeControl.kt |
State to parent: `LauncherSafe.state()` (`on`, `home`, `err`, `style`, `locked`, `pages`, `safeUntil`) LauncherSafe.kt.
Crash fallback: `CrashGuard` (guard/CrashGuard.kt) keeps the process on main-thread crashes (other screens closed; launcher crash = counted + home restarted); 2 launcher crashes in 5 min = layout reset (`HomeFile.reset`, `.bad` kept); 3 = safe mode 24 h = `SafeHome` plain app list, component stays HOME.
`afterUpdate`/`freshStart` clear counters and restore `.bad`.

### 3.13 Kid vs Home flavor

| Aspect | kid (`dev.kidguard`) | home (`dev.kidguard.home`) |
|---|---|---|
| Rules | parent `Rules` | none; `settingsOk` always true; nothing blocked |
| `LauncherSafe.wanted` | rule/local opt-in | always true; `HomeEntry` re-enables + starts |
| Header | call + SOS | none |
| Banner | owner > usage > location > acc > guard | only "home" banner (`homeIsOurs`) |
| Widgets | all | clock, analog, month, battery, todo, note, class, timer, streak, media, weather, app |
| Today cards | all | now, sched, tomorrow, notes |
| Fresh layout | preset by rule | 2-row clock only (`fresh(parent=false)`) |
| Control center | shade rule | `shadeOff()` false (HomeControl.kt) |
| Extras | | hides itself when kid launcher is on (`setApplicationHidden`); imports layout/wall via `HomeExport` |

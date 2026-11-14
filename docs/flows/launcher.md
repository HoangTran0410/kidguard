<!-- part of docs/flows/ (index: README.md) -->
Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. **Line numbers drift**: grep the function name.

## 3. Kid launcher

Base: `android/app/src/main/kotlin/dev/kidguard/launcher/` (abbrev `L/`). All files `package dev.kidguard`. Line numbers are
as read on 2026-11-09 (HomeActivity.kt = `HA`). Not read in detail: HomeWidgetsLive/Lists/Plan/Kid bodies, HomeFolder body,
HomeLibrary/HomeControl bodies (only signatures), Wallpaper draw(), PageDots, KidNotes, TimetableLogic, WidgetDraw.

### 3.0 Where is X

| Feature | Where |
|---|---|
| Activity state, lifecycle | `L/HomeActivity.kt` onCreate 162, onResume 241, onNewIntent 288, onBackPressed 300 |
| Installed app list | `HA.launcherApps()` 311 (queryIntentActivities LAUNCHER, skip HomeEntry, `labelCache`) |
| Show / pause / hide decision | `HA.decision()` 338 + `LauncherLogic.show()` (L/LauncherLogic.kt) |
| Render pipeline | `HA.render` 432, `render0` 445-516, `buildPages` 632-724 |
| Wallpaper bitmap | `HA.ensureWall` 518, `Wallpaper.draw/photo/blur/dim` (L/custom/Wallpaper.kt), `WallSurface` HA:1446 |
| Palette / presets / accents / walls | `L/custom/HomeStyle.kt` (PRESETS 121, ACCENTS 83, WALLS 128, `palette` 100, `ink` 138) |
| Icon bitmaps (mask, grey) | `L/IconCache.kt` `get` 36 (LRU = heap/5, 8-96 MB), `greyPaint` |
| Launch app | `L/HomeIcons.kt` `launch` (~165), `debounce`, `reopenShort/When` |
| Pure layout model | `L/HomeLayout.kt` (fresh, parse, place, drop*, group, moveTo, removeIds, removePage, resizeWidget, regrid, fitRows, placeAppWidget*) |
| Persistence | `L/HomeFile.kt` (filesDir/home.json, tmp+rename, `.bad`) |
| Pager (pages, swipe) | `HA.Pager` 1139-1239; `CellLayout` 1242+ |
| Drag and drop | `L/HomeDrag.kt` startDrag, hover, drop 321, landNow 356, endDrag 397, dockTarget, folderPlate |
| Long-press menus | `L/HomeMenus.kt` menu, appMenu, emptyMenu, pausedSheet, update 135, homeSettings |
| Edit mode | `L/HomeEdit.kt` enterEdit, exitEdit, editBars, removeOne |
| Dock, SOS, bars | `L/HomeDock.kt` buildDock, sosMenu 87, inkPass |
| Drawer + A-Z rail | `HA.Drawer` 804-1065 (refill 930, rail touch 903, scrubTo 1013, follow 1030); open/close in HomeDock.kt |
| Swipe up/down on home | `HA.SwipeUp` 1073; `expandShade` 1129; `openControl` L/control/HomeControl.kt 206 |
| Folders | `L/HomeMenus.kt` openFolder/folderSheet/renameFolder/addToFolderSheet; `L/HomeFolder.kt` (FolderGrid, folderDragBegin/Evt, folderZoom) |
| App Library page | `L/HomeLibrary.kt` (`LibraryLogic.groups`, libraryOut/libraryBack/libraryFill) |
| Today page | `L/today/TodayPage.kt` |
| Own widgets | `L/widgets/HomeWidgets.kt` (design system at top, SIZES), `HomeWidgetsLive/Lists/Plan/Kid` |
| Other apps' widgets | `L/widgets/HomeAppWidgets.kt` (awAdd, awConfigure, awPlace, awSync, appWidget) |
| Widget picker | `HomeWidgets.kt` openPicker, `awPickerSection` |
| Notification dots | `L/NotifDots.kt` (listener), `Dots.refresh`, `DotHost` interface in icons |
| Customization screen | `L/custom/CustomActivity.kt` build 71-176, set 188, regrid 197, applyPreset 208, pick/savePhoto 420+ |
| Recent apps row | `L/Recent.kt` (usage stats, else local list of 12) |
| Control center | `L/control/HomeControl.kt` (`CcSys`, `ControlLogic.mode`, toggles) |
| Safety / crash fallback | `L/LauncherSafe.kt` wanted, apply/on/off, uncaught handler, safe mode |
| Flavor switch | `L/Flavor.kt` (HOME = BuildConfig.HOME), `L/HomeEntry.kt`, `src/home/AndroidManifest.xml` |
| Kid-to-Home layout import | `L/custom/HomeExport.kt` (provider "layout"/"wall", same-signer check) |

### 3.1 Start / render / page structure

Trigger: HOME press, boot, `onNewIntent`, rule change, package change, minute tick.
1. `onCreate` HA:162: loads `HomeFile`, builds root (WallSurface, Pager, dock, hint, drawer prebuilt), `pager.jump(1)`.
2. `onResume` HA:241: registers `timeRx` (minute) and `pkgRx` (package add/remove); `NotifDots.setOn(style.badge != "off")`;
   applies `LauncherSafe`.
3. `render(force)` HA:432 -> `render0` 445: builds key string `k` (model JSON, labels, show map, want map, block, banner,
   timeKey, rules...). `!force && k==key` -> only `tickClocks`. `busy()` (drag/anim) -> retry in 1500 ms (HA:492).
4. `buildPages` 632: `PageBuilt` signature per page; `pageGlobal` + `reusePages` reuse built pages on layout-only edits.
5. Today page filled on idle (`addIdleHandler`, HA:505-510) unless it is the current page; drawer prebuilt at HA:514.
Page structure (pager children): [0] TodayPage, 1..n `CellLayout` grids, last = `LibraryPage` if `style.lib`.
`pager.minPage` = 0, or 1 when `style.today` is off (child 0 still exists so numbers do not move).
State/persistence: `home.json` (see docs/protocol.md "home.json"). Atomic write, in-memory cache, bad parse -> `.bad` + fresh.
Gotchas: banners cached 20 s (`bannerNow` HA:366); `render` must not run while `busy()`; auto rows (`fitRows`->`autoRows`->`update(regrid)`).
Paging: Pager.settle fling or 30 % width; rubber band 1/3 past ends; long press on empty space -> `emptyMenu` (HA:1151).

### 3.2 App list refresh (install / uninstall / blocked)

Trigger: package broadcast, rule push, time tick, `Policy.invalidate()`.
1. `pkgRx` (HA onResume): `apps = null; Policy.invalidate()`, debounced `pkgLater` 400 ms -> `render(true)`.
2. `launcherApps()` HA:311 re-queries PackageManager; label cache; category + system flags (for the Library).
3. `decision(pkg)` HA:338: uses `Guard.snap` when < 30 s old, else runs `decide()` locally.
4. `LauncherLogic.show()`: HIDE for plumbing, `r.hidden`, `r.blocked`, reason in HIDE_REASONS (blocked/new), or settings app while `!settingsOk`.
   PAUSED for any other `want[pkg]` reason (grey + clock badge, tap -> `pausedSheet`). `pkg==self` always OK.
5. `HomeLayout.appendNew` places genuinely new apps on the last page; `prune` drops uninstalled ones; `seen` = placed or
   removed apps, never auto re-added (kid removing an app from home keeps it in drawer only).
Gotchas: `settingsOk = r.launcher.settings || (!owner && !paired)`; new app is "new" (hidden) until parent allows (rule `blocked/new`).

### 3.3 Launching an app

Trigger: tap on icon (grid, dock, drawer, folder, library).
1. `HomeIcons.launch` (~165): `show()` != OK handled before (PAUSED -> `pausedSheet`).
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
2. `CellLayout.onInterceptTouchEvent` HA:1261: while `editing`, a quick tap picks the item under it; the minus badge -> `removeOne(id)` (confirm modal).
3. Nothing picked: select-all, delete page (`removePage`), widget picker. Picked: none, group (>=2 apps -> `group`), move (`moveTo`), remove (`removeIds`).
4. `exitEdit` on Back, Home, onPause.
Gotchas: the Library page is taken out of the pager during edit (`libraryOut`), put back by `libraryBack`.

### 3.6 Folders

Trigger: drop app on app (dwell 350 ms centre), edit-mode group, folder tap.
1. `HomeLayout.dropOnto` / `group` create `HItem(kind=folder, apps)` (FOLDER_MAX 20; `folderKind`).
2. `openFolder` (HomeMenus) needs `lastView`; zoom via `folderZoom`; `folderSheet` shows a `FolderGrid`; drag out via `folderDragBegin/Evt`.
3. `renameFolder`, `autoName` (name from app categories), `addToFolderSheet`.
4. Icon: `HomeIcons.folderIcon` (mini preview, dot sum via `DotHost`).
State: inside `home.json` item `apps`. Gotchas: `openFolder` returns silently if `lastView == null`.

### 3.7 Drawer search and A-Z rail

Trigger: swipe up on a home page (`SwipeUp` HA:1073), dock handle, Home button when on page 1.
1. Drawer inner class HA:804-1065 is kept (`drawerCache`) and prebuilt at HA:514.
2. Open/close follows finger: `drawerAt`/`drawerRelease`, opens at 40 %, closes at 60 % (`drawerOpens`).
3. `refill` HA:930: builds rows lazily per frame; no query -> "Dùng gần đây" row (`Recent.load`: usage stats 3 days, else last 12 opened here).
4. Search: accent-folded (`LauncherLogic.fold`/`matchesFolded`), Vietnamese Đ separate letter.
5. Rail: letter from `LauncherLogic.letter` (digits -> "#", other -> "..." bucket); touch HA:903; `scrubTo` 1013 lights one letter, dims others; `follow` 1030 keeps the list in sync.
6. Pull down closes; keyboard via InputMethodManager.
Swipe down on home: x0 > width/2 -> `openControl()` (kid control center), else `expandShade(false)` (reflection on StatusBarManager, HA:1129).
Gotchas: `Recent.load` is a binder call; must run off main thread (note in Recent.kt) - verify callers.

- ⋮ next to the search field -> menu "Hidden apps" -> `hiddenScreen()` (`L/HomeHidden.kt`): an overlay grid of every app the drawer leaves out with its reason (`LauncherLogic.hiddenReason`, unit-tested, same order as `show()` + study mode): parent-hidden / blocked / waiting for OK / study time / KidGuard-managed Tailscale. Plumbing (Play services & co.) is one count line. A tap only toasts the reason: nothing launches from there (it would bypass the rules). Our own `HomeEntry` is skipped by package, not class name (the Home flavor shares the class).

### 3.8 Today page ("Hôm nay")

`L/today/TodayPage.kt`; rebuilt on idle (HA:505-510). Cards (hidden ones in `HomeModel.todayHide`, "Tuỳ chọn thẻ" modal):
- header: call + SOS (never hideable; absent in Home flavor)
- `now`: greeting, current/next school period, countdown to next lock/bedtime
- `time`: screen time left + top 3 apps; empty: "no apps used, no limit" text (no action: limits are the parent's)
- `sched`: from `LauncherLogic.today` (parent schedules, group hours, bedtime); empty: text + link to the kid's timetable (`openTimetable`)
- `chores`: parent chores + kid's own homework todos
- `tomorrow`: only in the evening (`TimetableLogic.eveningFirst`); empty: text + "Tạo thời khoá biểu" -> `openTimetable(tomorrow)`
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
earn, msg, class, timer, streak (+ "app" for host widgets). Picker `openPicker`: one "KidGuard" card, 2-col tiles + size pills, drag via `Src.NewWidget`,
lazy "other apps" section (`awPickerSection`). Home flavor allows clock, analog, month, battery, todo, note, class.
Other apps': `HomeAppWidgets.kt`: `awAdd` allocates id (host id `AW_HOST_ID` 0x4b47) -> `bindAppWidgetIdIfAllowed`, else ACTION_APPWIDGET_BIND
(REQ_BIND 4701) -> `awConfigure` (REQ_CONF 4702) -> `awPlace` (`HomeLayout.placeAppWidget`). `awSync` deletes ids that left the model.
`appWidget()` shows a locked card if the host app is PAUSED/HIDE, a "gone" card if the provider vanished. Sizes from provider min/resize.
Gotchas: pending bind id lives only in memory (`aw.pendingId`).

### 3.10 Notification dots

`L/NotifDots.kt`: `NotificationListenerService` counting per package non-ongoing, non-group-summary, non-FGS notifications whose ranking
allows badges; counts only, nothing stored. `onChange` -> `Dots.refresh` -> icons (apps; folders = sum). `setOn` on resume from
`style.badge` ("off"/"dot"/"num"). Access is granted at setup (`cmd notification allow_listener dev.kidguard/dev.kidguard.NotifDots`,
docs/protocol.md:255); `CustomActivity` HA shows a "need access" note + opens system listener settings when missing (CustomActivity.kt:128).

### 3.11 Customization screen (CustomActivity)

Trigger: menu "Tuỳ chỉnh". Same task, not in recents; reloads home on resume if `HomeFile.load` differs.
1. `build()` CustomActivity.kt:71 rebuilds only ScrollView content (no scroll jump).
2. Sections: live preview, saved layouts (`L/custom/HomeSaved.kt`: up to 5 named home copies in filesDir/layouts/, tap = switch via `HomeBackup.apply`, one undo; presets removed 2026-11-11), grid cols stepper, icon size, labels, font face, shape, dock bg/labels,
   Today on/off, Library on/off, fx (full/lite/auto), dots (off/dot/num), wallpaper (photo + 13 built-ins), blur + dim sliders,
   accent, theme, text size, reset.
3. `set(f)` 188 -> `guard()` -> `save()` = `HomeFile.save` + `IconCache.clear`; `regrid` 197 offers Undo for a smaller grid; `applyPreset` 208.
4. Photo: `pick()` (API 33 PICK_IMAGES else OPEN_DOCUMENT) -> `savePhoto` on a thread: EXIF rotate, downscale to <=1080 wide, centre-crop,
   JPEG <=300 KB to filesDir/wall.jpg (tmp+rename) -> `style.wallKind="photo"`.
5. Locked (`rules.launcher.lockCustom`): read-only with note; `guard()` toasts. Lock hint hidden in Home flavor.

### 3.12 Parent `launcher.*` rules

| Rule | Effect | Where |
|---|---|---|
| `on` | enables HomeActivity component + persistent preferred HOME (owner) | LauncherSafe.wanted/apply 34-45 |
| `settings` | shows Settings app (+ control center tiles open system pages) | HA `settingsOk` 390, LauncherLogic.show |
| `notes` | blockNote shown in pausedSheet | HomeMenus.kt:39 |
| `lockCustom` | CustomActivity read-only | CustomActivity.kt:47 |
| `style` | preset only for first `home.json` | HA `firstModel` 397-405 |
| `shade` | `LauncherSafe.shadeBlocked`; Acc closes quick settings | Acc.kt:50,76; HomeControl.kt:300,350 |
State to parent: `LauncherSafe.state()` (`on`, `home`, `err`, `style`, `locked`, `pages`, `safeUntil`) LauncherSafe.kt:208-211.
Crash fallback: uncaught handler counts while `HomeActivity.alive>0`; 2 in 5 min = layout reset (`HomeFile.reset`, `.bad` kept); 3 = safe mode 24 h + off.
`afterUpdate`/`freshStart` clear counters and restore `.bad`.

### 3.13 Kid vs Home flavor

| Aspect | kid (`dev.kidguard`) | home (`dev.kidguard.home`) |
|---|---|---|
| Rules | parent `Rules` | none; `settingsOk` always true; nothing blocked |
| `LauncherSafe.wanted` | rule/local opt-in | always true; `HomeEntry` re-enables + starts |
| Header | call + SOS | none |
| Banner | owner > usage > location > acc > guard | only "home" banner (`homeIsOurs`) |
| Widgets | all | clock, analog, month, battery, todo, note, class, app |
| Today cards | all | now, sched, tomorrow, notes |
| Fresh layout | preset by rule | 2-row clock only (`fresh(parent=false)`) |
| Control center | shade rule | `shadeOff()` false (HomeControl.kt:350) |
| Extras | | hides itself when kid launcher is on (`setApplicationHidden`); imports layout/wall via `HomeExport` |

# Launcher performance: rules + how to measure

The kid launcher is the phone's home screen: it is on screen more than any app, and the slowest test phone (Oppo A5s,
Android 8.1, JIT only, 60 Hz) is a real kid's phone. **Every launcher change that adds or changes UI is measured on a
real phone before it is committed**, and the numbers go in the commit message (and `docs/decisions/launcher.md` if they teach
something). "Builds and looks fine" is not a perf check.

## 1. Budgets (Oppo A5s, release build, warm = 2nd open onward)

| What | Budget | Today (2026-11-09) |
|---|---|---|
| Any frame while scrolling / swiping / dragging | p90 ≤ 16 ms, janky ≤ 5 % | drawer, pager, sheets: p90 9-14 ms |
| Main-thread work in one frame (a build lump, a tap handler) | ≤ 16 ms; split larger work over frames | |
| Sheet / menu open: build | ≤ 30 ms warm | Sửa app 25-29 ms |
| Sheet / menu open: first draw (tap → pixels) | ≤ 100 ms warm, ≤ 150 ms cold | Sửa app 94-108 / 143 ms |
| Return home from an app | ≤ 5 ms render (nothing changed = no rebuild) | 2-5 ms |
| Home edit action (drop, resize, remove) | ≤ 35 ms | 25-32 ms |
| Home first render after cold start | ≤ 500 ms Oppo, ≤ 200 ms POCO | 509 / 184-189 ms |
| Idle (screen on, nothing touched) | no render unless state changed | 5-9 ms per Guard tick |

A change that makes a row worse than "Today" needs a reason in the commit; a change over budget is not done.

## 2. How to measure (10 minutes)

Device rules first: memory note *test-devices-rules* (Oppo: restore layouts, wake with `KEYCODE_WAKEUP` + swipe, never
type the PIN; POCO = owner's daily phone, install only).

```sh
cd android && ./gradlew --no-watch-fs assembleKidRelease        # release: debug numbers are 2-7x off (docs/decisions/launcher.md)
adb -s $S install -r app/build/outputs/apk/kid/release/app-kid-release.apk   # debug + release share the key
scripts/perf.sh $S on            # kgperf probe on (off by default in every build)
scripts/perf.sh $S reset; <do the action 3x with adb input>; scripts/perf.sh $S frames; scripts/perf.sh $S log
```

- **Frames**: `dumpsys gfxinfo` (via `perf.sh frames`) after a `reset` right before the action. Report total, janky %,
  p90. Do the action 3 times; the 1st is cold (class loading, JIT): report cold and warm separately.
- **Main-thread time**: `Perf.time("what") { ... }` around a suspect block, `Perf.firstDraw(view, "what", t0)` for
  "tap → first pixels" (see `AppLookUi.kt` `appEditSheet`). `HomeActivity.Lap` already logs any render > 16 ms as
  `KG.perf`. Leave `Perf` probes in: they cost one cached boolean check when off.
- **Gradle gotcha**: after files are rewritten by a script, incremental builds have reported "up-to-date" and kept the
  old APK (twice on 2026-11-09). Build with `--no-watch-fs` and check the APK time (`stat`) or a log line you just added.
- Window animations (sheet slide, app open) run in the system compositor: a slow app frame delays the content, it does
  not stutter the slide itself. Still count it: the kid sees the empty sheet.

## 3. Rules (each one came from a measured problem; see docs/decisions/launcher.md for the numbers)

**Build less, later**
1. First frame = only what is visible. Below the fold / grids are built after the first frame is DRAWN: an
   `OnDrawListener` then `post` (a plain `View.post` before the first frame still runs ahead of it: measured, no gain).
   Reserve the height they will take so nothing jumps.
2. More than ~15 ms of building = split into lumps on separate frames (`postOnAnimation`), never one view per frame
   (widget picker: 1 tile/frame = 71-86 % jank; 2 lumps = 33 %).
3. Re-use, don't rebuild: a state change updates the views it affects (recolour a `GradientDrawable`, change a stroke)
   instead of `removeAllViews` + re-add. Pages whose items did not change are kept (`buildPages`).
4. Render only when the key changed (`render(false)`); nothing on return-home or idle tick unless state moved.

**No heavy work on the main thread**
5. No bitmap per list item: plain views with a drawable background (Sửa app grid: 50 bitmaps ≈ 70 ms → views ≈ 20 ms).
   Bitmaps only for what must be one (app icons via `IconCache`, async on miss).
6. Disk, JSON parse of big blobs, package-manager queries, binder calls: cached (per resume / per raw string) or on a
   background thread at background priority, with a generation guard so an old result never lands.
7. Debounce bursty sources (package broadcasts 400 ms, notification dots 250 ms).

**Cheap frames**
8. No per-frame allocation in draw / touch / animator callbacks (drag, plates, highlights): allocate once, reuse.
9. Animate `translation` / `alpha` / `scale` only; no layout passes in an animation; time steps from frame time, not
   per-frame constants (they ran 2x at 120 Hz).
10. Hidden-but-ready views are `INVISIBLE`, not `GONE`, when they come back often (drawer: GONE re-measured the grid,
    94-103 ms).
11. Old WebView / Android 8.1 limits still apply (no `color-mix()` outside `@supports`, etc.).

**Don't optimise blind**
12. Measure first, then fix the biggest cost, then measure again with the same steps. A "fix" without a before/after
    number is not a fix (several were tried and dropped: docs/decisions/launcher.md "Rejected").

## 4. Checklist for a launcher PR / commit

- [ ] Release build on Oppo A5s (and POCO if it is about the home first render): numbers for every new or changed
      screen / action, cold + warm, in the commit message.
- [ ] Within the budgets of §1 or a written reason.
- [ ] Both flavors build (`kid` + `home`); behaviour checked on the flavor the phone uses as HOME.
- [ ] New probe points left in as `Perf.*` (off by default); no `Log.d` spam.
- [ ] Layout: screenshot every new / changed screen, row and widget size with the LONGEST vi string, at font scale
      1.3 and on a 4- and a 5-column grid (emulator: `settings put system font_scale 1.3`). A label beside a value
      must not be squeezed to 0 (a weight-1 label next to a wrap-content value: "Vuốt lên" went 1 letter per line),
      a number must never be ellipsized ("1 …"). Build + unit tests say nothing about this.
- [ ] A durable lesson (a trick that worked or didn't) = one line in `docs/decisions/launcher.md`.

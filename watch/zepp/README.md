# KidGuard for Zepp OS (Amazfit)

Parent watch app for Zepp OS 3+ (built and used on the owner's Amazfit Bip Max, 0.7.x). Shared contract with the
other watches: `../README.md`; wire formats: `docs/protocol.md` "Watch connect". **Line numbers drift**: grep the name.

Two halves:
- `page/*.js` runs **on the watch** (UI, GPS, compass). It has no internet: it asks the side service with
  `this.request({method, params})` (zml `BasePage`).
- `app-side/index.js` runs **inside the Zepp app on the parent's phone**: talks to the relay (`fetch`), keeps the
  kids list (settingsStorage `kids`), downloads + converts map tiles and sends them to the watch over Bluetooth.
- `setting/index.js` = the settings page in the Zepp app (relay URL, paste a link). `utils/ui.js` = column layout,
  toast, two-tap confirm, command receipts; `utils/map.js` = tile maths + tile cache on the watch.

## Build / install / test
- `npm ci && npx zeus build` -> `dist/*.zab`. Bump `app.json` version code + name AND `APP_VER` in `page/index.js`
  (Bip Max returns nothing usable from getPackageInfo, the constant is what the home screen shows).
- Owner's watch: Zepp app > Developer mode > Bridge on; `pkill -f "zeus bridge"; LOGSEC=120 expect tools/log.exp`
  installs + prints watch/side logs (wait for `KG-INSTALLED`; "Client already bound" = an old bridge still runs:
  pkill + retry; "INSTALL TIMEOUT" = retry once). Install only with the owner's OK.
- Simulator: Zepp OS Simulator (macOS 14+, needs the tun/tap system extension via Tunnelblick + `sudo`), then `zeus dev`.
  UI only: Bluetooth file transfer and `image.convert` behave differently on a real watch (see Map gotchas).

## Flows

### Connect a kid (`page/pair.js`, side `pairNew` / `pairPoll`)
- Trigger: no kid yet, or "Thêm con" in `page/kids.js`.
- Steps: `PAIR_NEW` -> side makes a secret, `POST /w/new {sh}` -> code shown big with time left; `onTick` sends
  `PAIR_POLL` every ~3 s -> `GET /w/<code>` + `X-KG-Secret`; when the parent typed the code (page Settings > Watch)
  the reply has `{url,id,token,name}` once -> appended to `kids` -> "Đã kết nối" -> home.
- State: settingsStorage `kids` (Zepp app on the phone). "Xem thử" = demo kid, only when tapped (`demoKid`, `demoState`).
- Gotchas: code lives 10 min, the page asks a new one; 30 codes per network per day (relay 429).

### Home (`page/index.js`, side `STATE` -> `summary()`)
- Trigger: app open, resume, after any command (`onResume` reloads).
- Steps: `STATE` -> side `GET /c/<id>/state` -> `summary()` trims it to what the watch needs (time left, `now.why`,
  battery, last seen, requests, pending queue, loc, zone, msgs) -> `render`.
- Kid switch: `page/kids.js` list; pending commands row opens the queue list.

### Commands and receipts (`act()` in `page/index.js`, `utils/ui.js` `rc*`)
- Steps: `CMD {type}` -> `POST /c/<id>/cmd` -> id saved (`rcSave`) -> "Đã gửi"; `rcPoll` asks `RECEIPT` until
  `state.done[]` / `expired[]` has the id -> toast with the result.
- Types used: `lock`, `ring`, `ringStop`, `locate`, `message {text}` (presets = `rules.msgs`), `answer`, `sosAck`.
- Rules edits (more time, pause, bedtime, limit, block): `RULES {op}` -> `changeRules` = GET state, `editRules()`,
  PUT the whole rules (full replace).

### Where + map (`page/info.js` kind "loc", side `TILES`, `utils/map.js`)
- Steps: `render` -> `map()`: 2x2 OSM tiles around the kid (`mosaic`), missing ones asked once per zoom/fix with
  `TILES` -> side `tiles()`: per tile `getTile` (relay `/c/<id>/tile`, fallback OSM) -> `image.convert` to the
  watch format -> `transferFile` outbox -> the page gets each file in `onReceivedFile` -> `remember` -> redraw.
  Kid dot + accuracy circle + zone drawn with CIRCLE widgets; −/+ zoom right under the map (z 13-18, stored `mz`).
- State: watch LocalStorage `tiles2` (z/x/y -> path, keeps 20); side storage `tc2` (converted-at time, 7 days).
- Gotchas (all measured on the Bip Max, 2026-11-14):
  - a page only gets files through `onReceivedFile(fo)`; zml BasePage owns the inbox (`inbox.on("NEWFILE")` never fires);
  - side `downloadFile` WITHOUT `filePath`, use the event's `tempFilePath` (with a filePath, convert said "not found");
  - download one tile at a time (temp names come from the URL, all relay URLs end in `/tile`);
  - convert target must end in `.png`, else IMG draws black;
  - IMG `src` = the `data://download/...` path; `../data/...` draws nothing;
  - tiles are drawn at 256 px and cropped (`pos_x/pos_y`), `auto_scale` untested since the fixes above;
  - changing the tile path format = rename both cache keys (`tiles2`, `tc2`) or old entries stop new downloads.

### Find mode (`page/find.js`)
- Trigger: "Tìm con" on the Where page.
- Steps: watch GPS (`readGeo`) + compass (`readCmp`) -> bearing/distance to the kid's last fix -> arrow rotated,
  N/E/S/W ring turns with the heading, `±acc m`, "hướng <8-way>", near hint under 20 m (GPS error dominates).
- Gotchas: compass needs calibration (hint shown); the arrow keeps turning when near.

# KidGuard for Garmin (Connect IQ parent watch app)

Monkey C **watch-app** (not a watch face): shows the kid's time left / why, battery, last seen, open requests, sends
lock / ring / locate / message, shows where the kid is. Same contract as `watch/zepp/` (see `watch/README.md` and
`docs/protocol.md` "Watch connect"). The watch talks to the **relay only**; every HTTP call goes through the Garmin
Connect app on the parent's phone (`Communications.makeWebRequest`), so the watch needs a phone link, no Wi-Fi code.

**Status: first version, NEVER COMPILED, never run** (no device files, see "Not verified"). Code was written against the
Connect IQ 9.3.0 API docs (SDK docs read locally); expect a round of compiler errors on the first `monkeyc`.

Paths below: `S/` = `watch/garmin/source/`. Line numbers as of this commit, grep the name if they drift.

## Build / run
- SDK: Connect IQ SDK (9.3.0 tested for the API docs) + JDK 17. Install with the SDK Manager (needs a free Garmin
  account: the **device files** for `fenix7`, `venu3`, `fr265`, `vivoactive5` are only served to a signed-in manager).
- Developer key (never commit; `.gitignore` covers `*.der`, `*.pem`, `developer_key*`):
  `openssl genrsa -out key.pem 4096 && openssl pkcs8 -topk8 -inform PEM -outform DER -in key.pem -out developer_key.der -nocrypt`
- Build: `cd watch/garmin && monkeyc -d fenix7 -f monkey.jungle -o bin/kidguard.prg -y /path/developer_key.der -w`
  (change `-d` for the other devices; add `-r` for release).
- Simulator: `connectiq` then `monkeydo bin/kidguard.prg fenix7`.
- Store build: `monkeyc -e -r ... -o kidguard.iq` (needs the store account: out of scope).
- Setting (Garmin Connect app, optional): "Relay URL", empty = `https://kidguard.99-hoangtran.workers.dev` (`S/Store.mc:31`).
- Devices in `manifest.xml`: fenix7, venu3, fr265, vivoactive5 (min API 4.2.0, permission `Communications`).
  Languages: `eng` (`resources/strings`) + `vie` (`resources-vie/strings`): every string exists in both.

## Files
| File | What |
|---|---|
| `S/KgApp.mc` | entry: no kid -> `ConnectView`, else the kids menu; `Nav.showKids()` swaps the root list |
| `S/Store.mc` | `Application.Storage`: `kids`, pending connect, `zoom`; relay URL from the app setting |
| `S/Api.mc` | relay client: `connectNew/Poll`, `state`, `cmd`, `putRules` (all `makeWebRequest`, JSON in/out) |
| `S/Util.mc` | sha256 hex, secure random secret, Long-safe numbers, `newestRules`, time text, slippy-tile math |
| `S/ConnectView.mc` | connect by code |
| `S/KidsMenu.mc`, `S/KidView.mc` | kids list, kid view + its menu, remove kid |
| `S/ActionsMenu.mc` | request answer menu, actions menu, message presets |
| `S/Grant.mc` | approve / deny a request (rules edit + `answer`) |
| `S/WhereView.mc` | location, one OSM tile, zoom |
| `S/Toast.mc` | "Sending... / Sent / error" screen that closes itself |

## Flows

### 1. Connect by code
- **Trigger**: first launch (no kid) or "+ Connect a kid" in the kids list (`S/ConnectView.mc`).
- **Steps**: `newCode` (`:45`) makes `secret` = 32 random bytes as 64 hex (`Util.randomSecret`, `S/Util.mc:44`,
  `Cryptography.randomBytes`) -> `POST <relay>/w/new {"sh": sha256hex(secret)}` (`Util.sha256hex :32`,
  `Cryptography.Hash` SHA256 over the hex text, same as the Zepp app) -> `{code, exp}`; the 6 digits are shown big with a
  mm:ss countdown. `tick` (`:71`, 1 s timer) polls `GET /w/<code>` with `X-KG-Secret` every 3rd second (`Api.connectPoll`,
  `S/Api.mc:28`). `{waiting}` = keep going; `{url,id,token,name}` (once) -> `finish` (`:107`) checks https origin, 16-hex id,
  48-hex token, then `Store.addKid` (`S/Store.mc:65`, same id replaces). 404 / 403 / expiry = new code.
  The parent types the code on the KidGuard page: Settings > Watch.
- **State**: `Application.Storage` key `kids` = `[{url,id,token,name}]`. The secret lives only in memory (a code
  that survives an app restart is not worth the extra storage; a new code takes 1 request).
- **Gotchas**: epoch ms (`exp`, `seen`, `loc.t`) overflow a 32-bit Number: read through `Util.lng`. START asks a fresh
  code. The token equals the `/go` link's: "renew relay link" on the page cuts the watch off (401 -> "Link expired").

### 2. Kid view
- **Trigger**: pick a kid in the list (`S/KidsMenu.mc:22`) -> `KidView` (`S/KidView.mc`).
- **Steps**: `onShow` and a 60 s timer call `refresh` (`:35`) -> `GET <url>/c/<id>/state` (`X-KG-Token`,
  `X-KG-Name: Garmin watch`) -> `parse` (`:57`) keeps only `leftMs`, `now.why`, `battery`, `charging`, `seen`,
  `location`, last `requests[]`, `rules.msgs` and drops the body at once. Screen: big time left (`left < 0` = "No limit"),
  why text (`Util.whyText`: lost / paused / schedule / bedtime / daily / pass / code), battery, "Seen N min ago",
  open request in orange. START / tap / long UP opens the kid menu (Answer request if one is open, Actions, Where,
  Refresh, Remove).
- **State**: `KidView.info` (small Dictionary), nothing persisted.
- **Gotchas**: **Response size.** The full `/state` (every app with its icon, all events) is tens of KB; Garmin returns
  `-402` (`NETWORK_RESPONSE_TOO_LARGE`) or `-403` (out of memory) when it does not fit. So `Api.state` asks
  `/state?watch=1` (relay, after 0.9.21: scalars + top 15 apps + last events, ~2-6 KB; rules stay whole because
  approve PUTs them back). "Too much data for this watch" (`Util.failText`) can still show with a very large rules
  object on a small-memory device (vivoactive5 / fr265): measure on a device.
### 5. Where
- **Trigger**: kid menu > Where (`S/WhereView.mc`), using `info.loc` from the last refresh.
- **Steps**: shows accuracy `±N m` and age. `fetch` (`:37`) computes the slippy tile (`Util.tileOf`, `S/Util.mc:146`, Doubles)
  and calls `Communications.makeImageRequest(<relay>/c/<id>/tile?z&x&y&k=<tileKey>, maxWidth/Height = min(screen, 256))`
  (`tileKey` from `state?watch=1`, kept in `k["tk"]`: image requests cannot send `X-KG-Token`); before the first
  refresh, straight from tile.openstreetmap.org.
  The bitmap is drawn so the kid's point is at the screen centre; a red dot + a blue accuracy circle
  (`acc / metersPerPixel * scale`) go on top. UP / DOWN / swipe / tap top and bottom third = zoom in / out (13..18,
  stored in `Storage.zoom`); START reloads. A zoom during a running request is queued (`dirty`).
- **State**: `Storage.zoom`; the bitmap lives in memory only.
- **Gotchas**: `makeImageRequest` has **no headers option**, so the relay's `/c/<id>/tile` (needs `X-KG-Token`) cannot be
  used; tiles come from OSM directly (Garmin Connect's User-Agent, 1 tile per view: fine for OSM's policy but not
  edge-cached, and blocked on networks that block OSM). A later option is a relay route that accepts a short-lived
  query token. One tile only: the area outside it stays dark. "(c) OpenStreetMap" is drawn in the info band (licence).
  Only "Locate" on the Actions menu makes the phone send a fresh fix; the view shows whatever `state.location` has.

## Not verified
- **Never compiled.** The SDK (9.3.0) was downloaded and `monkeyc` runs, but it stops with
  `Invalid device id specified: 'fenix7'`: the device files need a Garmin login (SDK Manager), which was not available.
  Expect syntax / type errors. Things most likely to need a fix: `Menu2` item ids that are Dictionaries (RequestMenu),
  `data has :get` on the image callback, `Dc` type names, `import Toybox.Timer` / `Math` use, and the app id in the manifest.
- Never run in the simulator or on a watch: layout on round screens (text positions are percentages of the screen),
  heap use (`-402/-403` on `/state`, a 256 px tile bitmap on small-memory devices), the 3 s poll, Menu2 stack handling
  (`Nav.showKids`, `RemoveConfirm.finish`, `Toast.extra` pops).
- Vietnamese diacritics depend on the device's system font; some devices may show boxes (names / messages come from
  the phone). Not checked per device.
- The launcher icon (`resources/drawables/launcher.png`, 60 px) does not match every device's launcher size: the
  compiler may warn.
- Not done in v1: SOS display, receipts (`state.done`), the other rules edits of the Zepp app (more time,
  pause, bedtime, block app), several tiles, auto-refresh of the location.
- Relay behaviour is as documented (`docs/protocol.md` "Watch connect"); nothing was called against the live relay.

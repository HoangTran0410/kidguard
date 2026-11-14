# KidGuard Wear OS parent watch app (`watch/wearos/`)

Kotlin + Compose for Wear OS (minSdk 30, Wear OS 3+). Standalone: the watch uses its own Wi-Fi / LTE, no phone companion.
It talks to the relay only, like the page and the Zepp app (shared contract: `../README.md`, wire formats: `docs/protocol.md`
"Watch connect"). v0.1.0, debug signing only (never touches `android/app/signing.jks`).

Path shorthand: `W/` = `app/src/main/kotlin/dev/kidguard/wear/`. **Line numbers drift**: grep the function name.
Behaviour reference: `watch/zepp/app-side/index.js` (`summary()`, `editRules()`, `pairNew/pairPoll`, `http()`), `watch/zepp/page/*.js`.

## Build / run
- `cd watch/wearos && ./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`. JDK 17, SDK 35, `local.properties` (`sdk.dir`, git-ignored).
- Emulator: `sdkmanager "system-images;android-34;android-wear;arm64-v8a"`, `avdmanager create avd -n wear_round -k <image> -d wearos_small_round`.
  Install ONLY with `adb -s emulator-XXXX install -r ...` (real phones / TVs share adb: never run `install*` Gradle tasks here).
  Emulator quirks: `adb shell svc power stayon true` (else the ambient clock overlay hides the app); swipe from the middle, not the bottom edge.
- Versions: `gradle.properties` `wear.version` / `wear.code` (independent of the phone apps).

## Files
| Area | File |
|---|---|
| Relay calls, state parsing, commands, rules edit, tiles | `W/Net.kt` (`Net`, `Summary`, demo state `Summary.demo`) |
| Kids + zoom persistence (SharedPreferences `kg`, app-private) | `W/Store.kt` |
| 2x2 tile mosaic + Mercator maths + tile cache | `W/Map.kt` |
| Screens (connect, kids, kid home, message, where) + command runner | `W/Ui.kt` |
| Navigation (swipe to go back) | `W/MainActivity.kt` (`App`) |
| Strings vi + en | `app/src/main/res/values/strings.xml`, `values-vi/strings.xml` |

## 1. Connect by code
Trigger: app start with no kid, or "Add a kid". Routes `connect`.
1. `ConnectScreen` (`W/Ui.kt:130`) loop: `Net.pairNew` (`W/Net.kt:75`) = random 32-byte secret, `POST <relay>/w/new {sh: sha256(secret)}` -> `{code, exp}`. Relay = `Net.DEFAULT_RELAY`.
2. Shows the 6 digits big + "m:ss left"; polls `Net.pairPoll` (`:88`) `GET <relay>/w/<code>` with `X-KG-Secret` every 3 s (the 1 s ticker only updates the countdown).
3. Parent types the code at Settings > Watch -> relay returns `{url,id,token,name}` once -> validated (https base, 16-hex id, 48-hex token) -> `Store.addKid` (`W/Store.kt:28`, same id = replace) -> navigates to that kid.
4. 404 / 403 or local expiry -> a new code is requested automatically. Network error -> "retrying", next try after 5 s.
State: kids JSON in prefs `kg` (with token); the secret lives only in memory of the loop (leaving the screen drops it).
Gotchas: the relay clock may differ from the watch's, so `exp` is trusted only inside a 12 min window, else now + 10 min. Limit 30 codes / network / day: each app start on the connect screen costs one. "Renew relay link" on the parent page cuts the watch off (state calls then fail with 401: re-add the kid).

## 2. Kids list -> kid home
Trigger: start with kids, or after connect. `KidsScreen` (`W/Ui.kt:174`) -> `KidScreen` (`:188`).
1. `Net.state` (`W/Net.kt:106`) `GET <url>/c/<id>/state` with `X-KG-Token`, `X-KG-Name: Wear OS watch`, refreshed every 15 s and after every action.
2. Shows: time left (`state.leftMs`) or why blocked (`state.now.why`, `whyText` `W/Ui.kt:62`: daily/paused/schedule/bedtime/pass/code, same words as Zepp), current app, battery, last seen (`seen`).
3. Open requests (last 3 of `state.requests`): time = "+15 / +30 min" + Deny, app = Allow + Deny -> `Net.answer` (`W/Net.kt:166`): grant first (`grant`: bonus or timed pass by `now.why`, or `appOk`), via GET state -> edit newest rules -> PUT the WHOLE rules (pin fields stripped), then `cmd answer {req, ok, min | always}` (the same order as the page).
4. "Remove this kid" deletes the kid from the watch only (the parent's other devices stay paired).
Demo: a built-in "Try demo" chip (connect and kids screens) opens `Kid.DEMO` (`W/Store.kt:10`): canned `Summary.demo()`, every command is a no-op ("Done"), never stored.

## 3. Actions: lock, ring / stop, locate, message
Trigger: chips on the kid home; `MessageScreen` (`W/Ui.kt:252`) for messages.
1. `Net.cmd` (`W/Net.kt:147`) `POST <url>/c/<id>/cmd {type, id, ...}` with our own id; `queued`/`ok` = sent. Types: `lock`, `ring`, `ringStop`, `locate`, `message {text}`, `answer`.
2. `Runner.run` (`W/Ui.kt:98`): "Sending..." -> "Sent" -> polls `Net.receipt` (`:158`) every 3 s for ~60 s: `state.done[id].res == "ok"` -> "Done", other res -> "Failed: res", `expired[]` -> "Phone did not get it". No answer in 60 s stays "Sent" (relay queues non-once commands 24 h).
3. Message presets = `rules.msgs` (max 12); empty or unreachable -> built-in list (`msg1..msg5`, same as Zepp).
Gotchas: "sent" is not "done" (phone may be off); `locate` also reloads state afterwards; the status line sits under the header so it is visible without scrolling.

## 4. Where
Trigger: "Where" chip. `WhereScreen` (`W/Ui.kt:274`).
1. Last fix from `state.location {lat,lng,acc,t}`: "N min ago, ±acc m", lat/lng, "Find now" (= `locate`, state reloads after).
2. Map: `Tiles.mosaic` (`W/Map.kt:42`) = 2x2 tiles of 256 px with the kid in the centre half (same maths as `zepp/utils/map.js`), drawn as a 256 px window scaled to 70 % of the screen width. Tile source `Net.tile` (`W/Net.kt:207`): `<url>/c/<id>/tile?z&x&y` with the token first, `tile.openstreetmap.org` with a real User-Agent as fallback. Memory (24 tiles) + disk cache (`cacheDir`, 7 days) per OSM tile policy.
3. Overlay: red kid dot, blue accuracy circle (`acc` metres), green rings for `rules.zones`; "-" / "+" buttons right under the map, zoom 13-18 saved in prefs (`mz`).
4. "Directions" (`openMaps`, `W/Ui.kt:352`): `https://www.google.com/maps/dir/?api=1&destination=lat,lng`, first with the Google Maps package, then any viewer, else a toast "No Maps app".
Gotchas: some networks block tile.openstreetmap.org (DNS answers 127.0.0.1): the relay route is tried first for that reason. Missing tiles show a grey mosaic + "Map not available" (dot still drawn).

## Not done in v1
Rules edits other than granting time (pause, bedtime, block app), SOS ack, events list, tile complication/tile (Wear Tiles API), relay override UI (prefs key `relay` is read but nothing sets it), release signing, Play listing.

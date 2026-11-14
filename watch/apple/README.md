# KidGuard Apple Watch (watchOS parent app)

First version, written 2026-11-14 without Xcode: the UI-free core is tested on macOS, the SwiftUI part has NEVER been
built for watchOS (see "Not verified"). Shared contract for all watches: `watch/README.md`; wire formats: `docs/protocol.md`
("Watch connect", state, commands). Port of the Zepp app (`watch/zepp/app-side/index.js`), minus the app list / zones / events.

Standalone watch-only app (`WKApplication`, watchOS 10+, Swift 5 mode): URLSession straight to the relay, no iPhone app,
no WatchConnectivity. Layout: `Core/` = Swift package `KidGuardCore` (no UI, builds + tests on macOS), `App/` = SwiftUI +
Keychain, `project.yml` = XcodeGen spec (the `.xcodeproj` is generated, not committed).

## Build / run
- Core on macOS (works with only the Command Line Tools): `watch/apple/test.sh` (wraps `swift test`; CLT has Swift Testing
  but not XCTest and SwiftPM needs the extra framework paths). With full Xcode: `cd Core && swift test`.
- App: install Xcode 15+ (watchOS 10 SDK), `brew install xcodegen`, `cd watch/apple && xcodegen generate`, open
  `KidGuardWatch.xcodeproj`, pick the watch scheme, set Signing > Team to the owner's Apple ID (free account: runs on the own
  watch for 7 days, then re-install), Run on a watch / simulator.
- App Store / TestFlight needs the paid Apple Developer Program ($99/yr). Bundle id `dev.kidguard.watch` (change if taken).
- Never commit `DEVELOPMENT_TEAM`; never put a token in logs (nothing here prints one).

## Flows (trigger -> steps -> state -> gotchas). Line numbers drift: grep the name.

### 1. Connect (first launch, or "Add a kid")
- Trigger: `KidGuardApp.swift` shows `ConnectView` when `store.kids` is empty; `KidsView` "Add a kid" opens it again.
- Steps (`App/Views/ConnectView.swift` `ConnectModel.run`): `Pairing.requestCode` (`Core/.../Pairing.swift:33`) makes a 32-byte
  random secret (`makeSecret:14`), `POST <relay>/w/new {sh: sha256(secret)}` -> `{code, exp}`; shows `123 456` + countdown;
  every 3rd second `GET /w/<code>` with `X-KG-Secret` (`poll:42`): waiting / 404 = expired (-> new code) / `{url,id,token,name}` once
  (`parseKid:50` validates https, 16-hex id, 48-hex token like the Zepp side). Code lifetime 10 min, renewed automatically.
- State: token -> Keychain (`App/KidStore.swift`, service `dev.kidguard.watch`, account = kid id, AfterFirstUnlock);
  url / id / name -> UserDefaults `kids.meta`. Relay = UserDefaults `relay` or `defaultRelay` (`Api.swift:3`); no settings UI in v1.
- Gotchas: the secret lives only in memory, so leaving the screen or killing the app loses the code (a new one is asked on return).
  A 403 on poll (wrong secret) is retried silently like a missed poll. Poll stops on `onDisappear`: nothing runs in the background.
  Meta without token (Keychain wiped, e.g. watch restored) is dropped at load. "Renew relay link" on the page cuts the watch off: delete the kid
  (swipe in the list) and connect again.

### 2. Kids list -> kid home (state)
- Trigger: `KidsView` row -> `KidHomeView` (`App/Views/KidHomeView.swift`); `KidHomeModel.start` refreshes every 15 s while visible.
- Steps: `KidClient.state()` = `GET <url>/c/<id>/state` with `X-KG-Token` + `X-KG-Name: Apple Watch` (`Api.swift:47`) -> `Summary(reply)`
  (`Models.swift:57`, port of Zepp `summary()`): `leftMs`, `now.why/until/name` (`whyLine` same wording as Zepp `nowLine`), battery, `seen`,
  current app (empty when screen off), `loc`, `requests[]`, `rules.msgs`. Newest rules = higher `rev` of `reply.rules` / `state.rules` (`Rules.newest:18`).
- Gotchas: `leftMs` nil = no limit shown ("No limit running"); `seen` is the relay's last contact, not the phone's clock.

### 3. Requests: approve +15 / +30 / deny
- Trigger: a `state.requests[]` entry shows a section in the kid home (time: +15, +30, No; app: Allow, No).
- Steps: `KidClient.answer` (`Api.swift:104`) like the page: when approving, edit the rules FIRST (`changeRules`: GET state again ->
  `Rules.apply` -> PUT the WHOLE rules object, `pinHash/pinSet/pin` stripped), THEN `POST /cmd {type:"answer", req, ok, min | always, id}`
  (the phone removes the request). `Rules.grant` (`Rules.swift:30`): `now.why` "" / "daily" -> today's `bonus.min` (+, max 600), any other
  lock -> `passUntil` = max(now, passUntil) + min. App request -> `approved` += pkg, removed from `blocked`.
- Gotchas: rules are a full replace and kept as loose JSON (`JSON.swift`) so fields this app does not know survive; a failed PUT means the
  answer cmd is not sent (request stays). Deny sends only the cmd.

### 4. Actions: lock, ring, ringStop, locate, message
- Trigger: buttons in the kid home (lock asks to confirm); `MessagesView` = presets `rules.msgs`, fallback `m1..m5` (localized).
- Steps: `KidClient.command` (`Api.swift:79`) makes our own id `w<time36><rand>`, `POST /cmd`, status "<action>: sent" (only queued on the
  relay). `KidHomeModel.follow` then polls `state` every 2.5 s, 6 times: `state.done[id].res == "ok"` -> done, other -> failed + text,
  `expired[]` -> not delivered, else "waiting for the phone" (`Summary.receipt`, `Models.swift:93`).
- Gotchas: non-once commands stay queued 24 h, so "waiting" is normal when the phone is off; buttons are disabled while one is in flight
  (`busy`). `message` text is cut to 120 chars (Zepp does the same).

### 5. Where
- Trigger: kid home -> Where (`App/Views/WhereView.swift`).
- Steps: `state.location` -> MapKit `Map` (watchOS 10 own tiles, nothing to download; the relay `/tile` route is not used) with
  `MapCircle(radius: acc)` + `Marker`, "±N m, <age>", last zone event; Find now = `locate` command; Directions = `MKMapItem.openInMaps`
  (driving) because watchOS has no browser for an http maps link.
- Gotchas: needs a `locate` + a refresh to get a fresh fix (the screen shows the last one); accuracy radius floor 5 m, map span >= 400 m.

### 6. Strings
`App/en.lproj/Localizable.strings` + `App/vi.lproj/Localizable.strings` (same keys, always both); `Text("key")` or `String(localized:)`.

## Not verified (read before trusting it)
- The SwiftUI app (`App/`) has NEVER been built for watchOS and never run: no Xcode on the dev Mac. It was only type-checked with
  `swiftc -typecheck` against the macOS SDK + Core (0 errors; one macOS 26 deprecation warning for `MKPlacemark`, fine for watchOS 10).
  Expect small watchOS-only compile errors on the first Xcode build.
- `project.yml` was never run through XcodeGen (not installed); the Info.plist keys (`WKApplication`, no companion id) are from memory.
- Keychain on a real watch, MapKit `Map` / `MapCircle` look, `openInMaps` on watch, vi layout on a 41/45 mm screen, polling battery cost: unchecked.
- Real relay: the request shapes are unit-tested against the documented protocol with a fake transport, never against the live worker.
- Core tests (9, `Core/Tests`): JSON decode of a sample state, receipts, rules edits (grant / pass / appOk / unknown fields / integer output),
  pairing requests + flow + validation, answer order (GET, GET, PUT, POST) and headers, formatters.
- Not in v1: pause / bedtime / limit edits (the ops exist in `RuleOp`, no screens), app list + block, zones, events, SOS ack, lost mode,
  relay setting, complications, notifications for new requests (needs the background refresh + a push or a relay change).

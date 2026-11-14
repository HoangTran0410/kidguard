# KidGuard watch apps (parent side)

A parent watch app shows the kid's state (time left, where, requests) and sends the same commands as the parent page.
Every watch talks to the **relay only** (Cloudflare Worker), never to the child phone directly. One folder per platform:

| Folder | Platform | Language | Build | Status |
|---|---|---|---|---|
| `zepp/` | Zepp OS 3+ (Amazfit Bip Max, …) | JS (device page + side service in the Zepp phone app) | `cd zepp && npm ci && npx zeus build` | used daily, 0.7.x |
| `wearos/` | Wear OS 3+ (Galaxy Watch, Pixel Watch, …) | Kotlin + Compose for Wear | `cd wearos && ./gradlew assembleDebug` | first version |
| `garmin/` | Garmin Connect IQ | Monkey C | `monkeyc` from the Connect IQ SDK | first version |
| `apple/` | watchOS 10+ | Swift + SwiftUI | Xcode | first version |

Each folder has its own `README.md` in the style of `docs/flows/`: trigger -> steps -> state -> gotchas, with file pointers.

## Shared contract (all watches do the same thing)
Wire formats live in `docs/protocol.md` (the contract); this is the short list a watch needs.

1. **Connect** (`docs/protocol.md` "Watch connect"): make a random secret (32 bytes hex), `POST <relay>/w/new {"sh": sha256hex(secret)}`
   -> `{code, exp}`; show the 6 digits big; poll `GET <relay>/w/<code>` with header `X-KG-Secret: <secret>` every ~3 s
   until it returns `{url, id, token, name}` (once). Store that as one kid. Parent types the code in the page:
   Settings > Watch. Code lasts 10 min: ask a new one when it expires. Default relay: `https://kidguard.99-hoangtran.workers.dev`.
2. **Read**: `GET <url>/c/<id>/state?watch=1` (small, ~2-6 KB; drop `?watch=1` only if you need the full state) header `X-KG-Token: <token>` (+ `X-KG-Name: <Platform> watch`) ->
   `{state, rules, seen, pending, queue, done, expired}`. What the watch shows comes from `state` (time left, `now.why`,
   `loc {lat,lng,acc,t}`, `zone`, `requests[]`, battery) and `seen` (last contact). Reference summary: `zepp/app-side/index.js` `summary()`.
3. **Commands**: `POST <url>/c/<id>/cmd {type, ...}` -> `{id}`; it is only queued ("sent"), a later `state.done[]`
   has the result (`{id, type, res}`), `expired[]` if it never ran. Used by the watches: `lock`, `ring`, `ringStop`,
   `locate`, `message {text}`, `answer {req, ok, min}`, `sosAck`.
4. **Rules edits** (more time, pause, bedtime, block): `GET state` -> change `rules` -> `PUT <url>/c/<id>/rules` with the
   **whole** rules object (full replace on the phone). Reference: `zepp/app-side/index.js` `editRules()` + the `case` list.
5. **Map**: `GET <url>/c/<id>/tile?z=&x=&y=` (256 px OSM PNG, z 13-18, same auth; or `&k=<tileKey>` from `state?watch=1` when the platform cannot send headers, e.g. Garmin). Fall back to tile.openstreetmap.org
   with a real User-Agent. Draw the kid dot + accuracy circle (`loc.acc` metres) yourself.
6. **Quick messages**: `rules.msgs` (list of strings) = the parent's presets; fall back to the built-in list.

Rules for every watch: UI text Vietnamese + English; never log the token; the token is the same one the parent's
`/go` link uses ("renew relay link" on the page cuts every watch off).

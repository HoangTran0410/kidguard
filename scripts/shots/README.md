# Parent-page screenshots (sample data)
1. `node shoot.mjs --out /some/dir` writes `<dir>/{vi,en}/{kids,today,apps,rules,where,screen,files,settings-away}.png` (400x860 CSS px, 2x, dark theme).
2. It starts `server.mjs` (3 fake kids on 127.0.0.1:8771-8773, serving `ui/` + fixtures) and drives headless Chrome over CDP; needs Node >= 20.10 and Chrome (`CHROME=...` to override).
3. Flags: `--patch state.json` (server only: merged over /state, e.g. basic mode), `--lang vi,en`, `--only today,where`, `--theme dark|light`. The clock is fixed at today 16:20 in both the server and the page.
4. Change the story in `fixtures/*.json` (`@-5m`, `@07:30`, `@day:-1` = relative times; `{"vi":..,"en":..}` = per-language text). The map needs network (OpenStreetMap tiles).
5. `--audit` (+ `--width 360`): opens every section, full-page PNG, prints elements that stick out of the screen or are cut off by a clipping parent (cards), and suspicious text with context. `--patch` is passed to the server (basic mode: `{"mode":"basic","owner":false}`). Desktop Chrome does not reproduce every Android WebView quirk.
   The audit also runs a UX lint (i18n keys shown as text, two controls with nearly the same label, tap targets < 24 px, controls without a name) and, on the Rules tab, flips every switch / picks every option, then checks each rules PUT carries the whole object (the phone replaces its rules with it).
6. All data is invented (kids Bin/Mia/Tom, generic central-Hanoi area); never put a real name or address in fixtures. Nothing here touches `ui/` or `android/`.

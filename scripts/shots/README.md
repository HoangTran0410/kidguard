# Parent-page screenshots (sample data)
1. `node shoot.mjs --out /some/dir` writes `<dir>/{vi,en}/{kids,today,apps,rules,where,screen,files,settings-away}.png` (400x860 CSS px, 2x, dark theme).
2. It starts `server.mjs` (3 fake kids on 127.0.0.1:8771-8773, serving `ui/` + fixtures) and drives headless Chrome over CDP; needs Node >= 20.10 and Chrome (`CHROME=...` to override).
3. Flags: `--lang vi,en`, `--only today,where`, `--theme dark|light`. The clock is fixed at today 16:20 in both the server and the page.
4. Change the story in `fixtures/*.json` (`@-5m`, `@07:30`, `@day:-1` = relative times; `{"vi":..,"en":..}` = per-language text). The map needs network (OpenStreetMap tiles).
5. All data is invented (kids Bin/Mia/Tom, generic central-Hanoi area); never put a real name or address in fixtures. Nothing here touches `ui/` or `android/`.

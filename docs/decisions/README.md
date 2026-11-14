# KidGuard decisions: index

Read the file of the area you touch before proposing a design or "fix": most ideas in these files were already tried,
measured or rejected ("reversed in X" marks a change of mind). Dates are 2026-11-xx; `(abc1234)` = commit with the
details; "unverified" = not re-checked against current code. Flows: `docs/flows/` (index `README.md`). Wire contract: `docs/protocol.md`.

**RULE:** when you decide or learn something durable, add ONE line under the right heading (`## Decided`,
`## Rejected / don't retry`, `## Past bugs (cause -> fix -> avoid)`) of the right area file, in the same commit.
Never append at the end of a file blindly. Open items (not fixed, not verified) go to `open.md`.
A line touching two areas goes to the area of the code it changes; do not duplicate it.

| File | What is in it |
|---|---|
| `enforcement.md` | Device Owner, basic mode, blocking and pauses, safe DNS, lost mode, PIN gate, Guard / Policy, phone core, adb helper |
| `remote.md` | Relay worker, LAN server, Telegram, parent Android app, pairing, tokens, self-update, Tailscale |
| `page.md` | Parent web page (`ui/assets`): layout, dialogs, i18n, maps, page-side bugs |
| `launcher.md` | Kid launcher, widgets, control center, drawer, kid UI screens, launcher perf |
| `watch.md` | Watch apps (`watch/zepp/`): connect, map, find mode, Zepp OS quirks |
| `setup-build.md` | Setup scripts, provisioning, build, release, website and docs, process / agents |
| `platforms.md` | OEM and OS gotchas: Oppo / ColorOS, Play Protect, Poco / HyperOS, Samsung, old WebView, Build, Android generic |
| `open.md` | Open backlog and open issues (older reviews, 2026-11-09 flow trace), mostly unverified |

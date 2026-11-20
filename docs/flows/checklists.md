<!-- part of docs/flows/ (index: README.md) -->
Path shorthands (`K/`, `L/`, `UI/`, `P/`): see `README.md`. No line numbers: grep the function name.

## 5. Add-a-feature checklists

### New command `foo`
1. Phone: add `"foo" -> ...` to `Guard.run` (`K/guard/Guard.kt` command table) returning a short result string. Impl in its own file under `K/guard|remote/`. Parsing note: for relay the worker may replace `id` (see 4 gotchas); don't use `id` as a payload field unless it matches `^[\w.-]{1,64}$` (existing precedent: `sosAck` uses `sos`).
2. If it undoes/adds a restriction: also handle in `Guard.release()`/`Policy.release` (AGENTS rule).
3. Worker: add to `CMDS` (`relay/src/index.js`); destructive/secret-bearing/non-repeatable -> also `ONCE`; payload validation next to `clearData`/`setting`; keep body <= 4 KB. LAN-only -> add to `LAN_ONLY` in `relay/test/contract.test.mjs` instead.
4. Page: a control whose effect is big or can't be undone from the page (incl. checkboxes) gets one row in `CONFIRM` (`ui/assets/kid.js`), no handler change. Button/handler calling `cmd({type:"foo",...}, okMsg, askArr|false)` (`UI/kid.js`); confirm texts `ask.foo`/`askOk.foo` in `i18n-vi.js` + `i18n-en.js`; result texts `cr.foo.<result>`; for async state (spinner) use the `act()/expect()` machinery in `live.js` and add the state field to `sig` (`kid.js`).
5. State feedback: if the page needs the outcome, add a state field in `Guard` state builder (`Guard.kt`) + document it; log line key in `res/values*/strings` (vi + en) and `LOG_IC` (`kid.js`).
6. Docs: `docs/protocol.md` "Commands" (the contract test fails if `foo` isn't mentioned there as a word).
6b. Older kid apps: add `foo` to `NEED` (`ui/assets/kid.js`) under the first kid version that has it (`node scripts/check-need.mjs` fails otherwise); old phones then get "update the kid's phone" with a button instead of a silent no-op. Its page controls go in that entry's `ui` ids.
7. Parent app: nothing, unless a notification action needs it (`P/Act.kt`, `P/Logic.kt`).
8. Tests: `cd relay && npm test` (contract + relay), Kotlin unit tests for any pure logic (`*Logic.kt` pattern).

### New rule field `bar`
1. `K/guard/Rules.kt`: field in `data class Rules`, `toJson()`, `fromJson()` with default + clamp. Both are the allow-list. Persisted via `toJson(persist=true)`.
2. Enforcement: `decide()` in Rules.kt (pure, `DecideTest`) or `Guard.kt` tick / `Policy.apply` (`Policy.kt`); undo in `release` if it adds a restriction.
3. `Events.ruleDiff` (`K/guard/Events.kt`) so the change log shows it.
4. Page: default in `norm()` (`UI/core.js`, for older phones), control in the right `UI/tab-*.js` (mutate `edit`, call `changed()`), `FEAT_SEC/FEAT_TAB` + `state.features` if gated (`core.js`, `K/guard/Features.kt`), i18n vi+en.
5. Worker: nothing needed (stores any JSON <= 64 KB); only PIN fields are special.
6. Docs: `docs/protocol.md` "Rules JSON" (or the feature section). Old-phone behavior: unknown field ignored.
6b. A new top-level key: add it to `NEED.rule` (`ui/assets/kid.js`) with the first kid version that has it, and its card / control ids to `NEED.ui` (`check-need.mjs`). An old phone drops unknown keys, so the switch would look like it turns itself off.
7. Launcher fields live under `rules.launcher` (protocol "Rule `launcher`") and have a normalizer in `norm()` (`core.js`).

### New state field
`Guard.kt` state builder -> `docs/protocol.md` -> page reads `st.x` (guard for undefined on older phones) -> add to `sig` if it must repaint -> if relay state size matters: worker rejects state >120 KB (`index.js`), relay reply cap 256 KB (`Relay.kt`).

### New worker route
Add the action name to regex `index.js`, handle in `Kid.fetch`, add `limitFor` if body > 256 KB; parent app proxy needs nothing (generic `/k/...`), except streaming bodies (`Logic.isFilesPath`). Phone side calls it from `Relay.kt`.

### New notification (child phone)
Posted from a tick / receiver / retry loop: remember what was posted (once per state change) and set `setOnlyAlertOnce`; a real one-event notice goes into `ONE_SHOT` of `NotifGateTest` (app + parent copies) with the reason. See enforcement 1.21.

### New Telegram command
`TgLogic` (parse, texts vi + en, `TgLogicTest`) -> `Tg.runCmd` -> menu list `setMyCommands` + help text -> if it needs a `watch.*` rule, add it to `WATCH_CMDS` so the one-tap turn-on button works (enforcement 1.20) -> protocol "Telegram bot on the child phone".

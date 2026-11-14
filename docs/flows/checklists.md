<!-- part of docs/flows/ (index: README.md) -->
Path shorthands: `K/` = `android/app/src/main/kotlin/dev/kidguard/`, `L/` = `K/launcher/`, `UI/` = `ui/assets/`,
`P/` = `android/parent/src/main/kotlin/dev/kidguard/parent/`. **Line numbers drift**: grep the function name.

## 5. Add-a-feature checklists

### New command `foo`
1. Phone: add `"foo" -> ...` to `Guard.run` (`K/guard/Guard.kt` command table) returning a short result string. Impl in its own file under `K/guard|remote/`. Parsing note: for relay the worker may replace `id` (see 4 gotchas); don't use `id` as a payload field unless it matches `^[\w.-]{1,64}$` (existing precedent: `sosAck` uses `sos`).
2. If it undoes/adds a restriction: also handle in `Guard.release()`/`Policy.release` (AGENTS rule).
3. Worker: add to `CMDS` (`relay/src/index.js:58`); destructive/secret-bearing/non-repeatable -> also `ONCE` (`:57`); payload validation next to `clearData`/`setting` (`:295-296`); keep body <= 4 KB. LAN-only -> add to `LAN_ONLY` in `relay/test/contract.test.mjs:17` instead.
4. Page: a control whose effect is big or can't be undone from the page (incl. checkboxes) gets one row in `CONFIRM` (`ui/assets/kid.js`), no handler change. Button/handler calling `cmd({type:"foo",...}, okMsg, askArr|false)` (`UI/kid.js:109`); confirm texts `ask.foo`/`askOk.foo` in `i18n-vi.js` + `i18n-en.js`; result texts `cr.foo.<result>`; for async state (spinner) use the `act()/expect()` machinery in `shell.js` and add the state field to `sig` (`kid.js:38`).
5. State feedback: if the page needs the outcome, add a state field in `Guard` state builder (`Guard.kt:~308-330`) + document it; log line key in `res/values*/strings` (vi + en) and `LOG_IC` (`kid.js`).
6. Docs: `docs/protocol.md` "Commands" (the contract test fails if `foo` isn't mentioned there as a word).
7. Parent app: nothing, unless a notification action needs it (`P/Act.kt`, `P/Logic.kt`).
8. Tests: `cd relay && npm test` (contract + relay), Kotlin unit tests for any pure logic (`*Logic.kt` pattern).

### New rule field `bar`
1. `K/guard/Rules.kt`: field in `data class Rules` (:133), `toJson()` (:173), `fromJson()` (:228) with default + clamp. Both are the allow-list. Persisted via `toJson(persist=true)`.
2. Enforcement: `decide()` in Rules.kt (pure, `DecideTest`) or `Guard.kt` tick / `Policy.apply` (`Policy.kt`); undo in `release` if it adds a restriction.
3. `Events.ruleDiff` (`K/guard/Events.kt`) so the change log shows it.
4. Page: default in `norm()` (`UI/core.js:103`, for older phones), control in the right `UI/tab-*.js` (mutate `edit`, call `changed()`), `FEAT_SEC/FEAT_TAB` + `state.features` if gated (`core.js:76-78`, `K/guard/Features.kt`), i18n vi+en.
5. Worker: nothing needed (stores any JSON <= 64 KB); only PIN fields are special.
6. Docs: `docs/protocol.md` "Rules JSON" (or the feature section). Old-phone behavior: unknown field ignored.
7. Launcher fields live under `rules.launcher` (protocol "Rule `launcher`") and have a normalizer in `norm()` (`core.js:114`).

### New state field
`Guard.kt` state builder -> `docs/protocol.md` -> page reads `st.x` (guard for undefined on older phones) -> add to `sig` if it must repaint -> if relay state size matters: worker rejects state >120 KB (`index.js:239`), relay reply cap 256 KB (`Relay.kt:203`).

### New worker route
Add the action name to regex `index.js:96`, handle in `Kid.fetch`, add `limitFor` if body > 256 KB; parent app proxy needs nothing (generic `/k/...`), except streaming bodies (`Logic.isFilesPath`). Phone side calls it from `Relay.kt`.

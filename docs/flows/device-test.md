# Device test checklist (run on every new phone / brand)

Nothing in CI covers on-device behaviour, so this list is the gate before a brand is called "supported"
(`docs/support-matrix.md`). Do not run it on the owner's enrolled phones: see the test-devices memory note; never
release / unpair / clear data on an enrolled phone. Brand lessons go in `docs/decisions/platforms.md` (one line).

Where to look: **page** = parent page (Log tab, "Chức năng & quyền", phone card); **state** = `GET /state` JSON
(`docs/protocol.md` "State"); **dumpsys** = `adb shell dumpsys ...`. Paths: `K/` = `android/app/src/main/kotlin/dev/kidguard/`.
Record per phone: model, Android + skin version, mode, date, pass/fail per row, one line per surprise.

## 0. Before: preflight per brand
Do these before step 1; each is a known blocker (`docs/decisions/platforms.md`, `docs/flows/setup-build.md` 1b gotchas).
| Brand | Preflight | Pass when |
|---|---|---|
| Any | Remove system accounts (Google / brand) for DO; Android 13+: App info > "Allow restricted settings" before enabling accessibility | `adb shell dpm set-device-owner` not "accounts" error |
| Samsung One UI 6.1+ | Settings > Security & privacy > Auto Blocker: off (or at least "Maximum restrictions" off and "block commands by USB cable" off); Sleeping apps: KidGuard removed from the list | adb install works; device admin activates (else Safe-Surfer-style message) |
| Xiaomi / HyperOS | Developer options: "Install via USB" + "USB debugging (Security settings)" on (needs Mi account + SIM) | `pm grant ... WRITE_SECURE_SETTINGS` no SecurityException |
| Oppo / Realme / OnePlus | Developer options: "Disable permission monitoring" on; startup manager allow; lock in Recents | same |
| Vivo | Developer options: "USB debugging (Security settings)" + "Install via USB" (may want account/SIM: untested); Autostart + Background power consumption "allow"; lock in Recents | same |
| Tecno / Infinix (HiOS / XOS) | Phone Master / Battery Lab: "Power saving management for apps" off for KidGuard; Auto-start allow; lock in Recents | KidGuard not frozen after 10 min screen off |
| Android Go (2-3 GB) | note RAM; Device Owner ok, profile owner not | see step 9 |

## 1. Install in 3 modes (reset or uninstall between modes)
Use a spare/test phone only. Check `state.mode`, `state.caps`, page "Chức năng & quyền" (`guard/Features.kt` is the source).
| # | Mode | How (`setup-build.md` §4.1) | Expected |
|---|---|---|---|
| 1a | Device Owner via QR | factory reset, QR 6 taps (1a) | `mode:"owner"`, `caps.owner` true, `caps.wss` true, accessibility on, no setup-wizard error |
| 1b | Device Owner via adb | `scripts/setup.sh` (1b) | same; script ends with LAN URL; `dumpsys device_policy` lists dev.kidguard as Device Owner |
| 1c | Basic (no adb) | install APK, "Việc cần làm" card (admin + accessibility + usage + overlay) | `mode:"basic"`, `caps.admin`, `caps.acc`, `caps.usage` true; `caps.wss`/`caps.adb` false |
| 1d | Basic + self-adb | "Tự cài qua Wi-Fi" (1d, Android 11+) or `pm grant WRITE_SECURE_SETTINGS` | `caps.wss` true, `caps.adb` true; `state.fixPerms` ok; Wireless debugging only briefly on |

Per mode, also: page pairs (LAN, then relay), the "Việc cần làm" card reaches 0 items, OEM auto-start row done.

## 2. Behaviour checklist (run in every mode that applies)
| # | Test | Do | Expected | Look |
|---|---|---|---|---|
| 2 | Block an app | page: block app X; open X on the kid | X does not open (DO: suspended dialog; basic: bounced Home within ~1 s by `Acc`) | page Log `block` event; state `blocked`; `Rules.decide()` (`K/guard/Rules.kt`) |
| 3 | Screen-time limit | set `dailyMin` = 2 min, use any app 2 min | apps lock, kid sees limit screen; parent gets event | Log; state `use` / limit fields |
| 4a | Bank pause `accPause` | install a listed bank app (or a stub from `bank-apps.json`), open it | accessibility off while bank is front, bank opens without "remove accessibility"; service back <= 10 min (DO) / 1 min (basic) after leaving | state `caps.acc`; Log; `flows/enforcement.md` §1.12 |
| 4b | `devPause` (Developer options / USB / wireless debugging off while bank open) | same with a `dev` bank | debugging off during, restored after exactly as before (DO without WSS: only USB debugging) | `adb` drops (expected); `Store.devPaused` cleared after |
| 4c | `tcpPause` (adb TCP port 5555) | bank with `tcp` | `caps.adbNet` false while open, back after | state `caps.adbNet` |
| 4d | VPN pause | app in `vpnPause` while Tailscale/always-on on | VPN lifted, resumes after | state `ts`, `vpn` |
| 5 | Reboot | `adb reboot` (or `cmd reboot` from page) | within ~2 min after unlock: Guard running, accessibility on, rules still applied, relay online, blocked app still blocked | page phone card "online"; state `uptimeMs` small; Watchdog/boot receiver (`K/guard/Watchdog.kt`) |
| 6 | App update (self-update) | install build N, publish/serve N+1 with higher `kg.code`; page "Update" or wait for `autoUpdate` | N+1 installed silently (DO) / system installer prompt (basic); settings, pairing, rules survive; screen-off wait respected for auto path | state `update {latest,status,err}`; `version` in state; `flows/remote.md` §2.8 |
| 7 | 24 h background survival | screen off, charger off, on Wi-Fi, nothing opened; rule active | after 24 h: page shows online, last check-in < 15 min old, accessibility on (basic), no `tamper` for acc-off, battery drop noted (Oppo baseline 0.4 %/day) | Log gaps; state `t`/`uptimeMs` (no reboot); `dumpsys deviceidle`, `dumpsys activity processes | grep kidguard` |
| 8 | Release / unpair notifies parents | page "Release" (never on an enrolled phone) | event `released` reaches page (+ Telegram if set) BEFORE teardown; all restrictions undone (apps unsuspended, restrictions cleared, admin/DO removed, settings restored); a failure shows `releaseFail` | Log; `dumpsys device_policy`; `flows/enforcement.md` Policy.release; protocol "Release" |
| 9 | Uninstall attempt | Settings > Apps > KidGuard > Uninstall; also deactivate admin; force stop; turn off accessibility | DO: uninstall disabled. Basic: Uninstall / Force stop / accessibility-off pages bounced; parent gets `tamper` (rate-limited 10 min / 60 min for acc off) | Log `tamper`; `BasicLogic.selfProtect` |

## 3. Brand extras (only when the brand is new)
- Recents swipe: does it force-stop and kill our service (Oppo yes)? Expect watchdog/boot path to restore; note in decisions.
- Battery/background list: after step 7 is the app in "Sleeping/Deep sleeping" (Samsung), frozen (Tecno), or killed (Vivo/Oppo)? Record which toggle fixed it.
- Launcher: if the phone's default HOME is ours, install the RELEASE of that flavor only (`docs/launcher-perf.md`); window shows wallpaper (HyperOS).
- Sideload gate: first install of the APK from browser/file manager: which prompts appear (unknown sources, store-only block, Play Protect)? One line each.

## 4. Done when
Every row passed or has a written reason; the support-matrix row for the brand is updated; durable findings are one line each in `docs/decisions/platforms.md`; failures that cost the owner time become an INCIDENT line + test (see memory "Incident = line + test").
Remote device labs (Samsung RTL, Android Device Streaming partner labs) can cover rows 1b/1d, 2-3 and 9 only via adb; QR (1a), reboot-survival and 24 h tests need a real phone in hand.

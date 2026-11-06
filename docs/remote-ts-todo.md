# WIP: remote access over Tailscale (branch v4-ts)

Goal: the parent reaches the child's phone from anywhere without touching it, like tv-remote,
but fully automatic because KidGuard is Device Owner.

## Idea
1. Parent pastes a Tailscale auth key in the parent page (at home, once).
2. Phone downloads the official APK from pkgs.tailscale.com/stable (`?mode=json` → universal APK + `.sha256`), checks SHA-256, installs silently with PackageInstaller (Device Owner = no prompt).
3. Managed config via `setApplicationRestrictions` (Tailscale ≥ 1.74): `AuthKey`, `Hostname`, `ForceEnabled`, `OnboardingFlow=hide`, `UseTailscaleDNSSettings=never` (keep KidGuard's Private DNS filter), `AllowIncomingConnections=always`, `ManagedByOrganizationName`.
4. `setAlwaysOnVpnPackage` (no lockdown, so internet works if the VPN is down), `setUninstallBlocked`, `DISALLOW_CONFIG_VPN`.
5. Once a 100.64.0.0/10 address shows up: suspend the app (hidden from the child); if the tunnel drops after suspending, unsuspend and keep it visible.
6. Release / "tailscaleOff": undo all of it and uninstall.
Parent then opens `http://<tailnet IP or hostname>:8770` from a phone with Tailscale on the same tailnet.

Refs: https://tailscale.com/kb/1315/mdm-keys · https://tailscale.com/kb/1384/android-mdm

## Done (code only, NOT built or run)
- `app/src/main/kotlin/dev/kidguard/Ts.kt`: TsLogic (pure) + Ts (download, install, policy, poll, hide, undo).
- `TsLogicTest.kt` unit tests for TsLogic.
- Guard: `Ts.poll` each tick, `ts` in state JSON (never the key), commands `tailscale {authKey, hostname}` / `tailscaleOff`.
- Policy: Tailscale hidden from the app list; `release` calls `Ts.undo`.
- Store: tsWanted / tsKey / tsHost / tsHide.
- Manifest: `<queries><package com.tailscale.ipn/>`.

## TODO
- [x] `./build.sh` passes (unit tests incl. TsLogicTest). Needs the real `app/signing.jks` copied in (gitignored); a missing key makes build.sh create a new one and change web/provision.json + web/qr.html — never commit that.
- [x] Parent page `web/index.html`: Cài đặt → card "Điều khiển từ xa qua Tailscale": key input (LAN only), hostname, On/Off, state/ip/err from `state.ts`.
- [x] Worker whitelist: `tailscaleOff` only (`tailscale` carries the key: LAN only). Protocol updated.
- [x] Emulator test (AVD Android 34, Tailscale 1.104.5, 2026-11-06): silent install ~15 s, managed config read
      (`Start: setting opts.AuthKey by syspolicy`), logs in with no tap, tailnet IP after ~20 s, always-on VPN,
      DISALLOW_CONFIG_VPN, uninstall blocked (`DELETE_FAILED_OWNER_BLOCKED`). Bad key → `error` after ~2 min.
- [x] Tunnel survives `setPackagesSuspended` (emulator): app suspended, IP kept, parent page + API reachable from
      another tailnet device at `http://<ip>:8770`.
- [x] Private DNS filter works with the VPN up: tun network also uses the Private DNS host (family filter → 127.0.0.1).
- [x] `tailscaleOff` undo (emulator): VPN off, restriction cleared, app uninstalled, internet still fine.
- [x] No Tailscale UI in front of the child: setup no longer opens the app. The always-on VPN bind alone reads the managed
      config and logs in (emulator: tailnet IP 3.5 s after the policy, app never in front, sampled every 1 s). Fallback if
      no address in 30 s: open the app once, HOME after 3 s.
- [x] After uninstall the node stays in the admin console (no logout): documented in README + the card ("delete it in
      Machines"). Ephemeral keys rejected on purpose: the device is deleted after being offline and its IP changes.
- [ ] Real phone test (OEM ROMs: Samsung/Xiaomi may differ on always-on VPN + suspended VPN app).
- [x] README.md "Away from home": Tailscale vs Worker table + steps.
- [x] Docs: VPN key icon in the status bar can't be hidden (README table + card).

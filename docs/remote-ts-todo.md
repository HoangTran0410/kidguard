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
- [ ] Emulator/real phone test: download + silent install, managed config picked up (does it log in without opening the UI?), always-on VPN takes, tailnet IP appears.
- [ ] Does the tunnel survive `setPackagesSuspended`? (unknown; code falls back to visible).
- [ ] Does Private DNS filter still work with the VPN up?
- [ ] Release undo: VPN off, restrictions cleared, app uninstalled.
- [ ] README.md "Away from home": add this option.
- [ ] Docs: VPN key icon in the status bar can't be hidden (Android shows it always).

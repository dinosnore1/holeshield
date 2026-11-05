# Hole Shield 2.0.0 — Android phones and TV

Hole Shield is the self-contained DNS filter for Android TV and phones. This
release introduced the current app package.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.0.0-SHA256SUMS.txt`** — checksum for the APK.

The APK is signed with the direct-install key.

## New package

The package is `com.dino.holeshield`. Android treats it as a new app:

1. Uninstall any older app package.
2. Install `holeshield.apk`.
3. Turn on protection and approve Android's VPN prompt again.
4. Run **Update gravity now**.

Settings, rules and statistics do not migrate from older packages.

## What's new

- Rebranded UI, dashboard, notifications, launcher label and TV banner as
  **Hole Shield** / **Hole Shield TV**.
- Targets **Android 16 (API 36)**.
- In-app **VPN disclosure** before Android's VPN consent prompt.
- Notification permission handling on Android 13+.
- Edge-to-edge layout on Android 15+.
- Foreground service declared as `specialUse` for local DNS filtering.
- Fresh launcher icon and TV banner.
- Fixed a target-SDK 34+ receiver registration crash.
- The disclosure is shown again whenever Android's VPN consent is requested.

## Filtering

- Local, DNS-only `VpnService`; no remote VPN tunnel.
- Deduplicated gravity database with a 600k-domain cap.
- Exact, wildcard and regex allow/deny rules; allow rules win.
- CNAME inspection, local cache, DoH upstream and plain-DNS fallback.
- Pause/resume, query log, statistics, web dashboard and LAN DNS on 5354.
- D-pad TV UI and touch phone UI with dark Material 3 theming.

## Verified

- 70 desktop checks.
- Fresh installs on Android 15 (API 35) and Android 16 (API 36) emulators.
- In-app disclosure → Android VPN prompt → protected.
- Cancel leaves protection off and starts no VPN service.
- `specialUse` foreground service and start-at-boot work on API 35/36.
- TV tested on a Hisense VU Android 11 set: D-pad UI, dashboard, LAN DNS,
  filtering and the maximum preset.

## Notes

- Android 8.0 (API 26) or newer.
- No root. LAN DNS uses port 5354 because Android cannot bind port 53 without
  root.
- Set Android **Private DNS** to **Off** if a strict DoT hostname bypasses the
  filter.
- Privacy policy:
  https://dinosnore1.github.io/holeshield-privacy/privacy.html

## SHA-256

```
5d5afc5e6be1c2c1692ce1f31438694cc63b7e4331b6a2545fef4e995ef8d9d1  holeshield.apk
```

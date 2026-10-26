# Hole Shield 2.0.0 — Android phones and TV

Hole Shield is the new name for the self-contained DNS filter previously
released as **Pi-hole for Android TV and phones**. This release is a new app
package, not an in-place upgrade.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.0.0-SHA256SUMS.txt`** — checksums for both files.

The APK is signed with the direct-install key. The AAB is signed with the Play
upload key; Play App Signing will re-sign the delivered app. The two files are
not interchangeable signing-wise.

## Important: new package

The package changed from `com.dino.pihole` to `com.dino.holeshield`. Android
treats this as a new app:

1. Uninstall the old **Pi-hole** app.
2. Install `holeshield.apk` or the Play build.
3. Turn on protection and approve Android's VPN prompt again.
4. Run **Update gravity now**.

Settings, rules and statistics do not migrate from the old package.

## What's new

- Rebranded UI, dashboard, notifications, launcher label and TV banner as
  **Hole Shield** / **Hole Shield TV**.
- Targets **Android 16 (API 36)** for current Play requirements.
- In-app **VPN disclosure** before Android's VPN consent prompt.
- Notification permission handling on Android 13+.
- Edge-to-edge layout on Android 15+.
- Foreground service declared as `specialUse` for local DNS filtering.
- Fresh launcher icon and TV banner.
- Fixed a target-SDK 34+ receiver registration crash.
- The disclosure is shown again whenever Android's VPN consent is requested.

## Filtering

- Local, DNS-only `VpnService`; no remote VPN tunnel.
- 23-list maximum preset, deduplicated to roughly 500k domains, 600k cap.
- Exact, wildcard and regex allow/deny rules; allow rules win.
- CNAME inspection, local cache, DoH upstream and plain-DNS fallback.
- Pause/resume, query log, statistics, web dashboard and LAN DNS on 5354.
- D-pad TV UI and touch phone UI with dark Material 3 theming.

## Verified

- 70 desktop checks.
- Signed AAB validates and is signed with the upload key.
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
- The Play listing is not public yet; the AAB is provided for upload/testing.
- Privacy policy:
  https://dinosnore1.github.io/holeshield-privacy/privacy.html

## SHA-256

```
0a41d54c98c485a59718a7158edacd138d7e0f6be965084769b63c39de400c3f  holeshield.aab
5d5afc5e6be1c2c1692ce1f31438694cc63b7e4331b6a2545fef4e995ef8d9d1  holeshield.apk
```

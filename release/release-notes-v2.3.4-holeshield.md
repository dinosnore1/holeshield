# Hole Shield 2.3.4 — Category levels and expanded blocklists

Stable release for Android and the native desktop app. The Android package keeps
the same `com.dino.holeshield` identity and signing key as 2.3.1–2.3.3, so it
installs over existing builds and keeps settings, rules, blocklists and
statistics.

## Download

### Android

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.4-release-SHA256SUMS.txt`** — APK checksum.

### Desktop

- **macOS (universal):** `Hole-Shield-2.3.4-universal.pkg`
- **Windows:** `Hole-Shield-2.3.4.msi`, `Hole-Shield-2.3.4.exe`
- **Linux:** `holeshield_2.3.4-1_amd64.deb`, `holeshield-2.3.4-1.x86_64.rpm`
- **`holeshield-desktop-2.3.4-SHA256SUMS.txt`** — desktop checksums.

The desktop installers bundle a Java runtime. The universal macOS package
contains both Intel and Apple Silicon builds and selects the native one during
installation. The installers are unsigned, so macOS Gatekeeper and Windows
SmartScreen will warn on first launch. Signing steps are documented in
`desktop/packaging/SIGNING.md`.

## Changes

- Every category now has a **Lite / Medium / Maximum** blocking level.
- Added expanded, validated blocklists across Ads, Trackers, Malware, Adult,
  Piracy, Gambling, Dating and Social — 97 list URLs in total.
- Removed the fixed per-category domain budgets. The selected level now decides
  which lists are used.
- Added a fair-share **1.51M-domain memory guard** so one large category cannot
  starve the others.
- Combined the category enable/disable toggle and level selector into one row on
  Android and in the dashboard.
- Android category rows now use the chevron / divider / toggle layout.
- **Add maximum blocklists** now enables Ads, Trackers and Malware and sets all
  three to Maximum before updating.
- Fixed the desktop gravity category flag order.
- Android is now **2.3.4** (`versionCode 11`); desktop is **2.3.4**.

## Verified

- 298 Android JVM checks pass.
- 168 desktop checks pass.
- Android CI build and signing pass on a clean runner.
- Desktop VM smoke tests pass for macOS universal PKG, Linux DEB and Windows MSI.
- STB: 2.3.4 installed over the upload-signed build and the expanded lists
  loaded with the fair-share guard.
- `doubleclick.net`, `tinder.com`, `facebook.com`, `1337x.to`, `betway.com`,
  `bet365.com`, `draftkings.com`, `pornhub.com`, `redtube.com` and
  `xvideos.com` are blocked; `example.com` resolves normally.
- Category level changes were verified on the STB (Adult Lite/Medium/Maximum).
- The combined category rows were verified with D-pad focus on the STB.

## SHA-256

```
c3c4f2a43d9c5ae98e25356949b8988c691977c4b6c786e19b8f6623fa1241b5  holeshield.apk
```

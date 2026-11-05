# Hole Shield 2.3.3 — Android and desktop cleanup release

Stable release for Android and the native desktop app. The Android package keeps
the same `com.dino.holeshield` identity and signing key as 2.3.1 and 2.3.2, so it
installs over existing builds and keeps settings, rules, blocklists and
statistics.

## Download

### Android

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.3-release-SHA256SUMS.txt`** — APK checksum.

### Desktop

- **macOS:** `Hole Shield-2.3.3.dmg`, `Hole Shield-2.3.3.pkg`
- **Windows:** `Hole Shield-2.3.3.msi`, `Hole Shield-2.3.3.exe`
- **Linux:** `holeshield_2.3.3-1_amd64.deb`, `holeshield-2.3.3-1.x86_64.rpm`
- **Portable:** `holeshield-desktop-2.3.3.jar` (Java 17),
  `holeshield-linux-portable-2.3.3.tar.gz`,
  `holeshield-windows-portable-2.3.3.zip`
- **`holeshield-desktop-2.3.3-SHA256SUMS.txt`** — desktop checksums.

The desktop installers bundle a Java runtime. They are unsigned, so macOS
Gatekeeper and Windows SmartScreen will warn on first launch.

## Changes

- Repository renamed to **`holeshield`**.
- Android ships as a signed APK; store listing, bundle packaging and store-only
  assets were removed.
- Removed the inactive legacy external DNS-server integration and its discovery,
  settings and upstream code paths.
- Removed legacy branding from themes, resources, comments, dashboard storage
  keys and build paths.
- Removed two legacy blocklist sources; the preset now has 23 maintained lists.
- The Android sideload keystore moved to
  `~/.android/holeshield-sideload.keystore`. The key itself is unchanged.
- Dashboard session storage now uses the `holeshield_sid` key. Existing browser
  sessions sign in once more after upgrading.
- Desktop version is now 2.3.3 with the same DNS engine, dashboard and rules.

## Verified

- 131 Android JVM checks pass.
- 117 desktop checks pass.
- Android CI build and signing pass on a clean runner.
- Desktop VM smoke tests pass for macOS PKG/DMG/JAR, Linux DEB/portable/JAR and
  Windows MSI/portable/JAR.
- `doubleclick.net` is blocked; `example.com` resolves through DoH.
- No store, bundle or legacy-brand references remain in the repository.

## SHA-256

```
95f77c17cb5259f868f7c769817a89de85213cb5692d231c72f900b203a3c072  holeshield.apk
```

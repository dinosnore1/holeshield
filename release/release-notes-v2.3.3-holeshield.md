# Hole Shield 2.3.3 — standalone cleanup release

Stable release. Same `com.dino.holeshield` package and the same signing key as
2.3.1 and 2.3.2, so it installs over existing builds and keeps settings, rules,
blocklists and statistics.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.3-release-SHA256SUMS.txt`** — checksum for the APK.

## Changes

- Repository renamed to **`holeshield`**.
- APK-only distribution; store listing, bundle packaging and store-only assets
  were removed.
- Removed the inactive legacy external DNS-server integration and its discovery,
  settings and upstream code paths.
- Removed legacy branding from themes, resources, comments, dashboard storage
  keys and build paths.
- Removed two legacy blocklist sources; the preset now has 23 maintained lists.
- The sideload keystore moved to `~/.android/holeshield-sideload.keystore`.
  The key itself is unchanged.
- Dashboard session storage now uses the `holeshield_sid` key. Existing browser
  sessions sign in once more after upgrading.

## Verified

- 131 JVM checks pass.
- Android CI build and signing pass on a clean runner.
- Desktop builds and VM installer smoke tests remain green.
- `doubleclick.net` is blocked; `example.com` resolves through DoH.
- No store, bundle or legacy-brand references remain in the repository.

## SHA-256

```
95f77c17cb5259f868f7c769817a89de85213cb5692d231c72f900b203a3c072  holeshield.apk
```

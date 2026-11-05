# Hole Shield 2.3.2 beta — dashboard redesign and update channel

Beta build for the update-channel test. Same package and signing key as 2.3.1;
settings, rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.2-release-SHA256SUMS.txt`** — checksums for the APK.

## Highlights

- Web dashboard redesign for desktop and phone: sticky desktop section sidebar
  with scroll spy, fixed icon bottom bar on phones.
- Phone app redesign with persistent icon bottom navigation.
- Native System, Dark and Light appearance modes.
- Stable/Beta/Off update channel with a manual Check for updates action.
- TV navigation rail, dropdown settings, popup polish and theme-aware switches.
- Fixed dashboard section IDs so query/rules panels render correctly.

## Beta update test

1. Open **Settings → Updates**.
2. Set **Update channel** to **Beta**.
3. Choose **Check for updates**.
4. The installed 2.3.1 build should report 2.3.2 available. Its button opens the
   direct beta APK URL; the 2.3.2 build itself uses the in-app downloader.

## Verified

- 133 desktop checks pass.
- Web dashboard desktop and phone layouts checked in dark and light themes.
- Dashboard DOM test passes.
- Not installed over ADB; this release is for the in-app beta channel test.
- The beta APK is hosted at
  https://dinosnore1.github.io/holeshield-privacy/holeshield-2.3.2-beta.apk
  for direct download.

## SHA-256

```
65171fc809cdab6a2c4b3f5efa4b91c2e4bbfc3873798f0a83cee2c2917e18ca  holeshield.apk
```

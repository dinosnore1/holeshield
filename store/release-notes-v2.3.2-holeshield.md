# Hole Shield 2.3.2 beta — dashboard redesign and update channel

Beta build for the update-channel test. Same package and signing key as 2.3.1;
settings, rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.3.2-release-SHA256SUMS.txt`** — checksums for both files.

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
4. The installed 2.3.1 build should report 2.3.2 available and offer **Open release**.

## Verified

- 133 desktop checks pass.
- Web dashboard desktop and phone layouts checked in dark and light themes.
- Dashboard DOM test passes.
- Not installed over ADB; this release is for the in-app beta channel test.

## SHA-256

```
825a6b9b6a0d01b3947ab29ae11ff0f5c76e160ecc146288e9c0a8bcfb79298e  holeshield.aab
74430b99c6168fb8f81a822d3704ee43083b8a950d4cd747c710a38ecb6e9674  holeshield.apk
```

# Hole Shield 2.3.1 — web dashboard visual refresh

An in-place update for 2.3.0: same package, same signing key, your settings,
rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.3.1-release-SHA256SUMS.txt`** — checksums for both files.

## Refreshed web dashboard

- Branded header with a shield mark, sticky translucent bar and live status pill.
- Hero summary with upstream and blocking-hours chips.
- Icon cards for totals, blocking, lists and clients, with clearer hierarchy.
- Tonal panels, accent headings, hover states and consistent spacing.
- Cleaner query table with status dots, category tags and row actions.
- Polished forms, list health, server details, footer and login screen.
- Responsive header, hero actions, cards and panels for phones.
- Light and dark Material You palettes, subtle background grid, reduced-motion
  support and themed scrollbars.
- Settings keeps its scroll position and focused row when an in-place option
  such as Fallback DNS is changed.

## Also included from 2.3.0

- Opt-in dashboard/LAN DNS with first-run setup.
- Notification actions, Quick Settings tile and home-screen widget.
- Query log quick allow/block, category column and CSV/JSON export.
- Blocking hours, per-client LAN rules and runtime category switches.
- PBKDF2 web-password storage, login lockout and the merged Log out menu.
- Backup/restore, list health and live/24-hour/week/month charts.
- DNS-over-TLS, selectable blocked response and parental filtering.
- Compact 64-bit gravity storage, launcher shortcuts and CI.

## Verified

- 133 desktop checks pass.
- Dashboard structure, range switching, tooltips, query actions, category
  switches, list health and the merged logout menu were exercised with jsdom.
- Dark, light and 500px mobile layouts were visually checked in headless Chrome.
- 2.3.1 installed on the Android TV/STB (versionCode 8 / versionName 2.3.1).

## SHA-256

```
b304378623ed5ff387e49f516882002b70ae9823cb506e3c56cbfff62be69ded  holeshield.aab
d228c3ef7326968ebb0320dea0d260c59b3dfa4c665209945560d50335e3e5f2  holeshield.apk
```

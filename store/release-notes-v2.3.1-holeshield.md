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
- Settings now exposes SafeSearch directly, with an accurate active/inactive
  description.
- Network details open at the header; only address rows have Copy buttons.
- Blocklist health uses readable filenames, shows an OK summary and switches the
  action to "Update lists" when nothing failed.
- Server details no longer repeat the dashboard/LAN URLs, top clients no longer
  duplicate bare IPs, and query rows show their category.
- Stat cards are tinted and use accent values; section headings have accent
  bars; the query log has a TV focus target.
- Added an adjustable TV safe area in Settings → System. It is **off by
  default**; options are Off, 5%, 10%, 12% and 15%. Enabling it adds insets and
  a bottom scrim; Off keeps the original full-width layout.
- Boolean settings now use Material-style toggle switches; action rows keep
  chevrons. Row spacing was tightened for a denser settings list.
- Focus no longer scales rows; the focused row uses an outline, tinted surface,
  left accent bar and soft lift instead.
- TV now uses a persistent left navigation rail for Home, Server, Gravity & rules,
  Query log and Settings. Brand/status live in the rail, TV subheaders drop the
  redundant Back button, and long query domains ellipsize cleanly.
- Native app now supports System, Dark and Light appearance modes under
  Settings → Appearance → Theme, with a full light palette, light dialogs and
  theme-aware switches. The choice is persisted and backed up.
- Phone layout now uses a compact header and a persistent bottom navigation bar
  (Home, Server, Gravity, Log, Settings), with redundant Back buttons removed.
- Multi-option settings use single-choice dropdown dialogs: Fallback DNS,
  DoH/DoT provider presets, Blocked response and TV safe area.
- Dropdown popups use a custom padded layout so options and buttons stay clear
  of the rounded corners, with 34dp TV / 26dp phone inner padding. They now have
  a shield header, subtitle, close button, divider and clearer selected rows.

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
8aa8b72919ccfb329bc44d0aad7bd6f8e0068f3953685f0bf3ada9b207a5dcd4  holeshield.aab
ca41d35d9fe1feb328bf3dfe13b7cfd34deeed05c5682627e0c80a3e112daa82  holeshield.apk
```

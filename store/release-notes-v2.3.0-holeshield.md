# Hole Shield 2.3.0 — opt-in sharing, controls and hardening

An in-place update for 2.2.x: same package, same signing key, your settings,
rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.3.0-release-SHA256SUMS.txt`** — checksums for both files.

## First-run privacy

The web dashboard and the LAN DNS listener are now **off by default**. A
first-run setup screen explains the local VPN, the query log and the optional
LAN exposure, with separate opt-in checkboxes. Turning either listener on from
Settings shows a confirmation before it is exposed.

## Everyday controls

- **Notification actions:** Pause 30 min, Resume and Open dashboard.
- **Quick Settings tile:** shows protection state and toggles blocking.
- **Home-screen widget:** status, blocked count, Pause/Resume and Open.
- **Launcher shortcuts:** Pause 30 min and Query log.
- **Network details:** copyable dashboard and LAN DNS addresses.

## Query log

- Tap a row on the TV/phone app for quick exact/wildcard allow or block.
- The dashboard adds a Category column and Allow/Block buttons per row.
- Export the query log as CSV or JSON from the dashboard or the app.

## Blocking hours and categories

- Optional daily blocking window; overnight ranges are supported and blocking
  resumes automatically.
- Runtime switches for **Ads**, **Trackers** and **Malware** apply immediately
  without a gravity reload.
- Per-client LAN rules: `192.168.1.50 ads.example.com` blocks,
  `192.168.1.50 !example.com` allows, and `*.example.com` matches subdomains.

## Login hardening

- Web passwords are stored as salted **PBKDF2-HMAC-SHA256** records, not
  plaintext. Existing plaintext passwords migrate on first launch.
- Five failed attempts trigger an increasing lockout (30 seconds up to 15
  minutes).
- Login sets an HttpOnly/SameSite session cookie in addition to the SID.
- The merged **Log out** menu can end this browser or every session.

## Backup, health and charts

- **Backup/restore** settings, custom rules, blocklist sources and the query log
  as one JSON file through the system document picker.
- **Blocklist health** shows per-list success/failure, domain count, error and a
  retry action.
- The dashboard adds a **live, 24-hour, week and month queries/blocked chart** and a
  blocked-by-category breakdown.

## Storage and DNS

- Gravity is stored as a sorted **64-bit hash array** with category flags. A
  600k-domain list costs a few megabytes instead of tens of megabytes of strings.
- **DNS-over-TLS** upstream in addition to DoH and plain DNS.
- Select the blocked response: **0.0.0.0**, **NXDOMAIN** or **NODATA**.
- Optional **SafeSearch/parental filter** adds an adult-content list to gravity.

## Platform

- Log rows are focusable with a TV remote and expose quick actions.
- Accessibility labels on the shield and launcher logo.
- GitHub Actions runs the desktop checks and builds the APK on push/PR.

## Verified

- 133 desktop checks pass, including PBKDF2 login, compact gravity merging and
  blocked-response modes.
- 2.3.0 installed on the Android TV/STB (versionCode 7 / versionName 2.3.0).
- The ongoing notification exposes Pause 30 min and Resume; the widget
  pause/resume action was exercised with an ADB broadcast.
- The dashboard serves the new chart, category switches, health panel, export
  actions and query-log columns; the UI was exercised with jsdom.
- Dashboard and LAN DNS defaults are off for new installs; existing installs
  keep their saved choice.

## SHA-256

```
9e3a8dcc389613d38ab9aaad47c1f906cf60719a1fd972f7b60f1a842602d2f6  holeshield.aab
2b2db2f379278aab6f6e1c9ed90303c9a976e327b18d0c582e6f15d033fb8c0c  holeshield.apk
```

# Hole Shield 2.2.0 — tracker and malware list categories

An in-place update for 2.1.x: same package, same signing key, your settings,
rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.2.0-release-SHA256SUMS.txt`** — checksums for both files.

## New: tracker and malware categories

The maximum preset now contains **25 lists** with explicit categories:

- **Ads and trackers** — Hagezi Ultimate, OISD, 1Hosts Xtra, AdGuard CNAME ads,
  popup ads and classic hosts lists.
- **Tracker and telemetry** — Perflyst SmartTV and Android tracking, plus
  Hagezi native lists for Amazon, Apple, Huawei, LG webOS, Oppo/Realme, Roku,
  Samsung, TikTok, Vivo, Winoffice and Xiaomi.
- **Malware and phishing** — URLhaus malicious-download hosts and Phishing
  Army domains.

In **Gravity & rules** you can add or remove the tracker/telemetry and
malware/phishing categories independently. The rows show **Active** when all
lists in that category are configured. Tap **Update gravity now** after changing
a category to download and apply it.

The 600k-domain gravity cap is unchanged. Security lists are placed before the
classic hosts lists so they are included when the cap is reached.

## Also included

- 2.1.0 optional web username + password login.
- 2.0.1 background-CPU fix and automatic protection restart after update/reboot.
- Version strings now come from the installed package.

## Verified

- 113 desktop checks pass, including category merge/remove/hasAll and web login.
- URLhaus hosts parsed to 1,906 domains; Phishing Army parsed to 151,707
  domains.
- 2.2.0 installed on the Android TV/STB (versionCode 6 / versionName 2.2.0).
- Tracker and malware categories show Active; the 25-list update produced
  592,615 unique domains (ads 526,615, trackers 3,896, malware 153,613).
- The web dashboard now shows a **LIST COVERAGE** row with Ads, Trackers and
  Malware domain counts.
- The web dashboard has icon-only **sun/moon** theme and **refresh** buttons;
  the theme choice is remembered in the browser.
- The dashboard tab has a shield favicon, and a **Log out** button appears when
  a web login is configured.
- The login screen is a simple centered form with a title, Authorization
  Required divider, compact pill fields and an arrow submit button.
- A single compact **Pause** pill (pause icon, dropdown, chevron) pauses
  immediately for 5 minutes through 24 hours, or until resumed. It then shows
  the remaining time and counts down in place; the pill width follows the label,
  the native select text is hidden, the paused label stays neutral, and the
  focus ring/shadow is removed while paused.
- URLhaus (`02teste.cc`), Phishing Army (`0-ilxrc-w285.p9bckp.sbs`) and tracker
  `app-measurement.com` resolve to 0.0.0.0; `example.com` resolves normally.

## SHA-256

```
d8fcb2cdb0693ae5a81b18d04fad717464eb6715bd978cc7096dd0cf7268575f  holeshield.aab
a36288e370832cd1b2a652e6c8ee62558ba955d99d4b8d43e683a11f9948155b  holeshield.apk
```

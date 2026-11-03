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
- The web dashboard has a **Light/Dark** theme toggle in the header and on the
  login screen; the choice is remembered in the browser.
- URLhaus (`02teste.cc`), Phishing Army (`0-ilxrc-w285.p9bckp.sbs`) and tracker
  `app-measurement.com` resolve to 0.0.0.0; `example.com` resolves normally.

## SHA-256

```
60e218a7ed1ea7f97cd403cd4e23c9e14cda29523e2fe5004b0a3067ba92245c  holeshield.aab
d8b5dc3c813602e52c48fc2511476598ed5b92749a065087fb52eb3a5981941b  holeshield.apk
```

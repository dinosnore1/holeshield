# Hole Shield 2.3.5 — Fair gravity budget and a live dashboard

Android release. The package keeps the same `com.dino.holeshield` identity and
signing key as 2.3.1–2.3.4, so it installs over existing builds and keeps
settings, rules, blocklists and statistics.

The desktop app is unchanged at 2.3.4; no desktop installers are part of this
release.

## Download

### Android

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.5-release-SHA256SUMS.txt`** — APK checksum.

## Changes

- Gravity lists are **merged and deduplicated before the memory guard runs**, so
  a domain that appears in several lists is charged once instead of once per
  list. The kept entry keeps every category flag it was listed under.
- Each domain is charged to the first matching category in priority order:
  **Ads → Malware → Trackers → Adult → Piracy → Gambling → Dating → Social →
  Other**, so Ads coverage is no longer starved by larger lists.
- The gravity budget is split across the **eight user-facing categories** and
  only counts categories that are switched on. A disabled category hands its
  share to the enabled ones; space a small list cannot fill flows to Ads first,
  then the rest in priority order.
- Category counts are reported from the **loaded index** and add up exactly to
  the total shown on the Gravity screen.
- **SafeSearch is now part of the Adult category.** The separate switch is gone
  from Lists and Settings; existing installs migrate automatically so adult
  filtering is never lost.
- The web dashboard chart opens on **Live** instead of 24h and refreshes **every
  second** while the Live range is selected.
- With **Limit gravity size** off, the full merged index loads (~3.5M domains on
  the test box): Ads holds its complete list, malware loads in full, and only
  the tracker tail is trimmed by the remaining heap guard.

## Verified

- 306 Android JVM checks pass.
- 179 desktop checks pass.
- Built and installed incrementally on the STB at 192.168.2.133
  (JMSB200A, Android TV), settings and lists preserved.
- Cap off: 3,495,253 domains loaded — ads 1,252,516, malware 1,429,865,
  trackers 560,962, adult 133,807, gambling 86,578, dating 31,525. The category
  cards sum to the total.
- Memory at full load: ~179 MB PSS, Dalvik heap ~61 MB of a 256 MB limit, system
  MemAvailable ~994 MB — no OOM or restart.
- `example.com` resolves normally; `doubleclick.net` returns `0.0.0.0`.
- Dashboard page served by the device reports the Live default and the
  one-second chart refresh.
- Cap on (six categories enabled): 1,510,000 domains — ads 748,337, malware
  251,667, trackers 251,667, adult 135,909, gambling 90,778, dating 31,642.

## SHA-256

```
52aee79c85106144d094f810d1e013d80e80424ee81a136df4fb755ce393c741  holeshield.apk
```

# Hole Shield 2.3.5 — Fair gravity budget, live dashboard and desktop companion mode

Android and desktop release. The Android package keeps the same
`com.dino.holeshield` identity and signing key as 2.3.1–2.3.4, so it installs
over existing builds and keeps settings, rules, blocklists and statistics. The
desktop build is now 2.3.5 and adds companion mode for logging in to a Hole
Shield system already running on the LAN.

## Download

### Android

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.3.5-release-SHA256SUMS.txt`** — APK checksum.

### Desktop

- **`Hole-Shield-2.3.5-universal.pkg`** — one macOS installer for Intel and
  Apple Silicon.
- **`Hole-Shield-2.3.5.msi`** — Windows installer.
- **`Hole-Shield-2.3.5.exe`** — Windows installer with a friendlier setup
  wizard.
- **`holeshield_2.3.5-1_amd64.deb`** — Debian/Ubuntu package.
- **`holeshield-2.3.5-1.x86_64.rpm`** — Fedora/RHEL package.
- **`holeshield-desktop-2.3.5-SHA256SUMS.txt`** — checksums for every desktop
  file above.

The desktop installers bundle a Java runtime, so Java does not need to be
installed separately.

## Android changes

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

## Desktop changes

### Companion mode

Companion mode turns the desktop app into a console for another Hole Shield
system on the LAN — an Android TV, a phone or another desktop running the same
dashboard.

- **Settings → Companion mode**: enter the other system's address and port
  (for example `192.168.2.133` and `8080`), plus the dashboard username and
  password when that system has a login.
- **Test connection** reports the remote version and blocked-domain count
  before you apply the settings.
- Companion mode does **not** start a local DNS resolver or a local web
  dashboard, so it needs no administrator rights and never binds port 53.
- The **Companion** page mirrors the remote summary: queries, blocked count,
  blocked rate, gravity size, clients and blocked-by-category counts. It
  refreshes every two seconds.
- Remote controls pause or resume filtering, flush the remote DNS cache and
  start a remote blocklist update from the Companion page or the tray menu.
- **Open dashboard** opens the other system's web dashboard in your browser.
  It is available in the header, the Companion page, Settings and the tray.
- A rejected password is not retried automatically, so it cannot lock the
  remote account. Press **Reconnect** or apply corrected settings.
- Turn companion mode off and apply to return to running a local resolver.

Companion mode uses only JDK HTTP and JSON support, with no new runtime
libraries. It works against the desktop dashboard and the Android dashboard,
including Android's `401` login probe.

### Other desktop changes

- The desktop build is version **2.3.5**, matching the Android app.
- `--companion HOST` starts the app in companion mode from a terminal or
  script.
- Headless output now reports the companion connection and remote dashboard
  address.
- The tray menu, header and status text follow the active mode.

## Verified

### Android

- 306 Android JVM checks pass.
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

### Desktop

- 199 desktop checks pass, including remote login, the Android `401` probe,
  summary parsing, pause/resume and wrong-password rejection.
- Companion mode was tested against a password-protected desktop dashboard,
  both headless and with the Swing window.
- The real Android TV at `192.168.2.133:8080` answered the `401` login probe
  and rejected a wrong password with the expected message.
- Local mode still resolves `example.com` and blocks `doubleclick.net` with
  `0.0.0.0` after switching companion mode off.
- GitHub Actions installs and smoke-tests the macOS universal PKG, the Linux
  DEB and the Windows MSI.

## Notes

- The desktop installers are unsigned unless signing certificates are
  configured in the repository. See `desktop/packaging/SIGNING.md`.
- macOS may ask you to confirm opening an app from an unidentified developer
  on first launch.

## SHA-256

Android:

```
52aee79c85106144d094f810d1e013d80e80424ee81a136df4fb755ce393c741  holeshield.apk
```

Desktop:

```
d0bae5057e27c0aeb4504a25ad564d06763a7e3c8fb6346d500b272789d78cde  Hole-Shield-2.3.5-universal.pkg
8f586fe3f327e206461781ed07b9653321e72a29da3af0a7807173fd7dad2fcd  Hole-Shield-2.3.5.msi
16580537007c2ccc42ac7c2960bb8bc803f12f3f13d60d0aeb21b112f41a8259  Hole-Shield-2.3.5.exe
791030a2c3fe68499364ebd786d404ea5dbfbf83b81650d37be34a5825b7bdc1  holeshield_2.3.5-1_amd64.deb
b4f18614f1574d409d0fdb07a77229e161e86ff7e4e16fdc6845a671ad2631db  holeshield-2.3.5-1.x86_64.rpm
```

The same desktop checksums are attached as
`holeshield-desktop-2.3.5-SHA256SUMS.txt`.

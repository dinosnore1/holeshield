# Hole Shield 2.3.5.1 — In-app updates and a dedicated About page

Hotfix release for Android and desktop. The Android package keeps the same
`com.dino.holeshield` identity and signing key as 2.3.1–2.3.5, so it installs
over existing builds and keeps settings, rules, blocklists and statistics. The
desktop build keeps version 2.3.5; the installers attached here are rebuilt
with the same updater.

## Download

### Android

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV
  (version 2.3.5.1, versionCode 13).
- **`holeshield-2.3.5.1-release-SHA256SUMS.txt`** — APK checksum.

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

The live update manifest at
`https://dinosnore1.github.io/holeshield/latest.json` lists every file with its
SHA-256 and size, so the 2.3.5 installers already in the field can find and
verify this build.

## Android changes

- **Updates moved to their own About page.** The About screen sits below
  Settings in the navigation rail on TV and as a sixth tab on phones. It holds
  the app icon, version, release notes and privacy links, the copyright line,
  the update card and the Stable/Beta/Off channel. The old update dialog and
  the About block at the end of Settings are gone.
- **The update button carries the state.** One button in the Updates card
  reports **Check now**, **Checking…** with a rotating ring, **Up to date** with
  a green tick, **Download and install**, **Downloading…**, **Verifying…**,
  **Installing…**, **Try again**, **Retry check** and the channel-specific
  **Allow installs** / **Updates are off**. There is no separate status box.
- **Updates are verified before they run.** The app downloads the APK from the
  release manifest, checks its SHA-256 and size, and hands it to the Android
  package installer. Nothing opens a browser; Android shows one confirmation
  prompt and protection resumes afterwards.
- **Progress and notes stay in context.** The card shows the download progress
  bar, the release notes, the last-checked time and any error inline, and the
  update channel lives in its own card underneath.
- **TV focus is kept.** The check button stays focusable while it works, so
  remote focus does not fall back to the navigation rail during a check.

## Desktop changes

- **About page below Settings.** The desktop app gains an **About** entry under
  **Settings**, also available from the View menu as **⌘6**. It owns the app
  hero and the updates card that used to sit at the end of Settings.
- **In-app updates.** The card checks the same release manifest, compares
  versions, downloads the installer for this platform, verifies its SHA-256 and
  runs it from a small helper that waits for the app to exit, installs the
  update and starts the app again. macOS uses the universal package, Windows
  uses the MSI (falling back to the EXE) and Linux uses DEB or RPM depending on
  the package manager it finds.
- **The button reports every state.** **Check now**, **Checking…**,
  **Up to date**, **Check again**, **Downloading…**, **Verifying…**,
  **Installing…**, **Retry check** and the unsupported-platform case all show in
  the button itself, with a rotating ring while working and a green tick when
  current.
- **Checks run daily.** The app checks once a day on startup, records the last
  check and folds that time into the Updates header instead of a separate
  footer line.
- **Unsupported systems are explained.** If the platform has no installer or
  the manifest has no asset for it, the card says so and leaves the rest of the
  About page intact.

## Verified

### Android

- 306 Android JVM checks and 12 update-verifier checks pass.
- Built and installed incrementally on the STB (Android TV, `192.168.2.133`)
  over the previous 2.3.5 build; settings, rules, blocklists and statistics
  were kept.
- Checked on the TV: the **About** rail entry, the hero card, the Updates card,
  the channel card, the spinner while checking, the green **Up to date** tick
  with **Last checked just now**, and focus staying on the check button after
  the state changed.
- Phone presentation was exercised on the same device at roughly 427 dp: the
  six-tab bottom bar, the stacked hero and the wrapped Updates subtitle.

### Desktop

- 262 desktop checks pass.
- Built and run from `/Applications/Hole Shield.app` on macOS, in both light
  and dark themes: the About page below Settings, the updates card states, the
  daily check and the SHA-256 verification path.
- GitHub Actions builds the macOS universal package, Windows MSI/EXE and Linux
  DEB/RPM from this tag.

## Notes

- Android version 2.3.5.1 (`versionCode 13`); desktop version 2.3.5.
- Android needs **Install unknown apps** permission for Hole Shield the first
  time it installs an update. The About page offers to open that setting.
- The app never uploads queries or browsing history, and the updater only
  fetches the public release manifest and the installer listed in it.

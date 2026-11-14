# Hole Shield Desktop 2.3.5 — Companion mode

Desktop release for Windows, Linux and macOS. This build adds **companion
mode**, so the desktop app can log in to a Hole Shield system that is already
running on your network instead of starting a resolver of its own.

The Android app is unchanged at 2.3.5 and keeps its separate
[`v2.3.5-holeshield`](https://github.com/dinosnore1/holeshield/releases/tag/v2.3.5-holeshield)
release and APK.

## Download

- **`Hole-Shield-2.3.5-universal.pkg`** — one macOS installer for Intel and
  Apple Silicon.
- **`Hole-Shield-2.3.5.msi`** — Windows installer.
- **`Hole-Shield-2.3.5.exe`** — Windows installer with a friendlier setup
  wizard.
- **`holeshield_2.3.5-1_amd64.deb`** — Debian/Ubuntu package.
- **`holeshield-2.3.5-1.x86_64.rpm`** — Fedora/RHEL package.
- **`holeshield-desktop-2.3.5-SHA256SUMS.txt`** — checksums for every file
  above.

Linux packages include a bundled Java runtime. macOS and Windows installers
also bundle their runtime, so Java does not need to be installed separately.

## Companion mode

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

## Other changes

- The desktop build is version **2.3.5**, matching the Android app.
- `--companion HOST` starts the app in companion mode from a terminal or
  script.
- Headless output now reports the companion connection and remote dashboard
  address.
- The tray menu, header and status text follow the active mode.

## Verified

- 199 desktop checks pass, including remote login, the Android `401` probe,
  summary parsing, pause/resume and wrong-password rejection.
- 306 shared JVM checks pass.
- Companion mode was tested against a password-protected desktop dashboard,
  both headless and with the Swing window.
- The real Android TV at `192.168.2.133:8080` answered the `401` login probe
  and rejected a wrong password with the expected message.
- Local mode still resolves `example.com` and blocks `doubleclick.net` with
  `0.0.0.0` after switching companion mode off.
- GitHub Actions installs and smoke-tests the macOS universal PKG, the Linux
  DEB and the Windows MSI.

## Notes

- The installers are unsigned unless signing certificates are configured in
  the repository. See `desktop/packaging/SIGNING.md`.
- macOS may ask you to confirm opening an app from an unidentified developer
  on first launch.

## SHA-256

Checksums are attached as `holeshield-desktop-2.3.5-SHA256SUMS.txt`.

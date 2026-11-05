# Hole Shield Desktop

Hole Shield for Windows, Linux and macOS. It is a native desktop port of the
Android resolver: the same gravity hash format, the same hosts/adblock parser,
the same allow/deny rules and the same web dashboard.

## What it does

- Local DNS resolver on UDP and TCP, with a TTL cache.
- Encrypted DNS over HTTPS (Cloudflare by default) or a plain upstream.
- Gravity blocklists with Ads, Trackers, Malware, Adult and Other categories.
- Exact, wildcard and regular-expression allow/deny rules.
- CNAME target inspection.
- Query log, live/24h/week/month charts, top domains, clients and query types.
- Embedded web dashboard with optional username/password login.
- Native Swing control panel with System, Dark and Light themes.
- Tray / menu-bar controls, pause timers and launch-at-login.
- Portable JAR plus native installers that bundle a Java runtime.

## Run the portable JAR

Requires Java 17.

```bash
./desktop/tools/build.sh
./desktop/tools/run.sh
```

On Windows:

```powershell
.\desktop\tools\build.ps1
.\desktop\tools\run.bat
```

Options:

```text
--port N            DNS port (default 53; falls back to 5354)
--web-port N        Dashboard port (default 8080)
--data-dir DIR      Settings and gravity directory
--headless          DNS and dashboard only, no window
--minimized         Start hidden in the tray
--open-dashboard    Open the web dashboard on launch
--page NAME         Start on Overview, Query log, Blocklists, Rules or Settings
--replace           Start even if another instance is running
--version           Print the version
```

## First run

1. The app tries port **53**. If it does not have permission, it falls back to
   **5354** and shows the reason in the control panel.
2. Open `http://127.0.0.1:8080/admin/`.
3. Add or remove blocklist sources on **Blocklists**, then choose **Update now**.
   The bundled seed list works immediately, before the first download.
4. On **Settings**, choose the upstream and the blocked response style.

### Point this computer at Hole Shield

Port 53 is required for normal system DNS. On macOS and Linux, start the app
with administrator rights (the **Restart as administrator** button on Settings)
or run it from a terminal:

```bash
sudo java -jar desktop/build/holeshield-desktop.jar --port 53
```

Then configure DNS:

- macOS: `sudo networksetup -setdnsservers "Wi-Fi" 127.0.0.1`
- Linux: `sudo resolvectl dns <interface> 127.0.0.1`
- Windows (admin PowerShell): `netsh interface ip set dns name="Wi-Fi" static 127.0.0.1`

Restore automatic DNS with `Empty` (macOS), `resolvectl revert` (Linux) or
`dhcp` (Windows). The Settings page has a **Copy DNS setup commands** button.

To filter other devices, leave the bind address at `0.0.0.0`, allow the DNS
port through the firewall, and set the router or each device to the computer's
LAN address. Routers can only forward to port 53, so use port 53 for a
router-wide setup.

## Build, test and package

```bash
./desktop/tools/test.sh       # headless core checks
./desktop/tools/package.sh    # app image + installers for this OS
```

Windows:

```powershell
.\desktop\tools\test.ps1
.\desktop\tools\package.ps1
```

Artifacts are written to `desktop/build/dist/`:

- macOS: `Hole Shield.app`, `.dmg`, `.pkg`
- Linux: app image, `.deb`, `.rpm` (when `rpmbuild` is available), portable
  `.tar.gz`
- Windows: app image, `.zip`, `.msi` and `.exe` when WiX is installed
- Every platform: `holeshield-desktop-2.3.2.jar`

The GitHub Actions workflow in `.github/workflows/desktop.yml` builds and
uploads all three platforms. It also installs the PKG/DMG, DEB, MSI and portable
ZIP on macOS, Ubuntu and Windows VMs, starts the installed app headless, resolves
a blocked and an allowed domain, verifies the DoH upstream and checks the
dashboard API. The installers are unsigned by default; see
`desktop/packaging/README.md` for signing notes.

## Data directory

- macOS: `~/Library/Application Support/Hole Shield`
- Windows: `%APPDATA%\Hole Shield`
- Linux: `$XDG_CONFIG_HOME/holeshield` or `~/.config/holeshield`

The directory contains `settings.properties`, `gravity.bin`,
`gravity.properties`, `stats.txt` and `query_log.tsv`. Set
`HOLESH_DESKTOP_HOME` to override it.

## Source layout

| File | Purpose |
| --- | --- |
| `DesktopApp.java` | Entry point, service lifecycle, arguments |
| `DesktopPrefs.java` | Persistent settings |
| `FilterEngine.java` | Gravity, custom rules, blocklist downloads |
| `DnsService.java` | UDP/TCP listener, filtering and logging |
| `Upstream.java` | DoH and plain DNS with TTL cache |
| `StatsStore.java` | Query log, counters and chart history |
| `WebDashboard.java` | Dashboard and Pi-hole-compatible JSON API |
| `ControlPanel.java` | Native Swing UI |
| `TrayController.java` | Tray / menu-bar controls |
| `Theme.java`, `Widgets.java`, `Icons.java` | UI kit |
| `SystemIntegration.java` | Autostart, DNS commands, elevation |
| `DesktopTest.java` | Headless checks |

Shared with the Android app: `DnsPacket`, `Gravity`, `HostsParser`,
`Blocklists` and `WebAuth`. The desktop build compiles those files directly
from `src/com/dino/holeshield/`; there are no copies to keep in sync.

## Current limitations

- The app does not install a privileged system service. Port 53 requires
  administrator/root, or the app runs on 5354.
- Automatic desktop self-update is not implemented yet; use the Releases page.
- Installers are unsigned until signing certificates are configured.
- The dashboard is served over plain HTTP on the LAN. Set a password if other
  devices can reach it.

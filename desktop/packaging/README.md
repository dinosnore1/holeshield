# Desktop packaging notes

`tools/package.sh` and `tools/package.ps1` use the JDK `jpackage` tool. Each
installer includes a trimmed Java runtime, so the user does not need Java.

## macOS

- `Hole Shield.app` — portable application bundle.
- `Hole Shield-2.3.2.dmg` — drag-to-Applications disk image.
- `Hole Shield-2.3.2.pkg` — installer package.

The build is unsigned. To ship it:

1. Sign the app and nested runtime with a Developer ID Application certificate.
2. Sign the DMG/PKG with a Developer ID Installer certificate.
3. Notarize with `notarytool` and staple the ticket.

To start the resolver automatically at login without prompting for a password,
install a launch daemon that runs the app as root on port 53. Keep the
LaunchAgent in `SystemIntegration` for per-user tray startup only.

## Linux

- `Hole Shield/` app image — portable directory.
- `holeshield_2.3.2_amd64.deb` — Debian/Ubuntu package.
- `holeshield-2.3.2-1.x86_64.rpm` — Fedora/RHEL package when `rpmbuild` exists.
- `holeshield-linux-portable-2.3.2.tar.gz` — portable archive.

For system-wide filtering on port 53 without running as root:

```bash
sudo setcap 'cap_net_bind_service=+ep' "/opt/hole shield/bin/Hole Shield"
```

or run the app as a systemd service:

```ini
[Unit]
Description=Hole Shield DNS filter
After=network-online.target

[Service]
ExecStart=/opt/hole shield/bin/Hole Shield --headless --port 53
Restart=on-failure
User=holeshield
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

## Windows

- `Hole Shield/` app image.
- `holeshield-windows-portable-2.3.2.zip`.
- `Hole Shield-2.3.2.msi` and `.exe` when WiX 3 is installed and `candle.exe`
  is on `PATH`.

Windows does not require administrator rights to bind port 53. The app still
needs permission from the firewall to accept LAN DNS traffic. Add an inbound
rule for UDP/TCP 53.

## Icons

`IconGenerator` writes a 1024px PNG. The packaging scripts convert it to
`.icns` with `iconutil` on macOS and to a PNG-backed `.ico` on Windows.

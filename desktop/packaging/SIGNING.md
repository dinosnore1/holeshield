# Signing desktop installers

The release installers are currently unsigned. Signing requires certificates you
own; none are stored in this repository.

## macOS

### Prerequisites

- Apple Developer Program membership.
- **Developer ID Application** certificate, for the app and DMG.
- **Developer ID Installer** certificate, for the PKG.
- An app-specific password from <https://appleid.apple.com>, or a `notarytool`
  keychain profile.

Check the identities:

```bash
security find-identity -v -p codesigning
```

Store notarization credentials once:

```bash
xcrun notarytool store-credentials "holeshield-notary" \
  --apple-id "you@example.com" \
  --team-id "TEAMID" \
  --password "app-specific-password"
```

### Sign and notarize

Build first:

```bash
./desktop/tools/package.sh
```

Then sign, recreate the DMG/PKG, notarize and staple:

```bash
MAC_APP_SIGN_ID="Developer ID Application: Your Name (TEAMID)" \
MAC_INSTALLER_SIGN_ID="Developer ID Installer: Your Name (TEAMID)" \
MAC_NOTARY_PROFILE="holeshield-notary" \
./desktop/tools/sign-macos.sh
```

The script:

- signs the app bundle with the hardened runtime and the Java entitlements in
  `desktop/packaging/macos/entitlements.plist`;
- rebuilds and signs the DMG;
- rebuilds and signs the PKG with the installer identity;
- submits both to Apple, waits, staples the tickets and checks Gatekeeper.

Without `MAC_NOTARY_PROFILE`, set `APPLE_ID`, `APPLE_TEAM_ID` and
`APPLE_APP_PASSWORD` instead. If neither is set, the script signs but skips
notarization.

### Verify

```bash
codesign --verify --deep --strict --verbose=2 "desktop/build/dist/Hole Shield.app"
spctl --assess --type execute --verbose=2 "desktop/build/dist/Hole Shield.app"
spctl --assess --type install --verbose=2 "desktop/build/dist/Hole-Shield-2.3.3.pkg"
xcrun stapler validate "desktop/build/dist/Hole-Shield-2.3.3.dmg"
xcrun stapler validate "desktop/build/dist/Hole-Shield-2.3.3.pkg"
```

## Windows

### Prerequisites

Choose one:

- **OV/EV code-signing certificate** as a `.pfx` file;
- certificate installed in the Windows certificate store, referenced by SHA-1
  thumbprint (required for many EV hardware tokens);
- **Azure Trusted Signing** or another cloud signing service.

Install the Windows SDK so `signtool.exe` is available. SmartScreen reputation
is separate from a valid signature; EV certificates and consistent signing help,
but new certificates can still warn until reputation builds.

### Sign the MSI and EXE

```powershell
$env:WINDOWS_CERT_PFX = "C:\certs\holeshield.pfx"
$env:WINDOWS_CERT_PASSWORD = "pfx-password"
.\desktop\tools\sign-windows.ps1
```

For a certificate in the store:

```powershell
$env:WINDOWS_CERT_SHA1 = "THUMBPRINT"
.\desktop\tools\sign-windows.ps1
```

Optional:

```powershell
$env:WINDOWS_TIMESTAMP_URL = "http://timestamp.digicert.com"
.\desktop\tools\sign-windows.ps1 -AppExe "C:\Program Files\Hole Shield\Hole Shield.exe"
```

The script signs `Hole-Shield-2.3.3.msi` and `Hole-Shield-2.3.3.exe`, timestamps
them with SHA-256, and runs `signtool verify /pa`.

### Verify

```powershell
signtool verify /pa /v "desktop\build\dist\Hole-Shield-2.3.3.msi"
signtool verify /pa /v "desktop\build\dist\Hole-Shield-2.3.3.exe"
```

## Linux

Linux does not show Gatekeeper or SmartScreen warnings. Package signatures are
optional:

```bash
# Debian/Ubuntu
dpkg-sig --sign builder -k "$GPG_KEY" holeshield_2.3.3-1_amd64.deb

# Fedora/RHEL
rpm --addsign --define "_gpg_name $GPG_KEY" holeshield-2.3.3-1.x86_64.rpm
```

For repository delivery, sign the repository `Release`/`InRelease` metadata with
GPG instead of signing each download manually.

## CI secrets

To sign in GitHub Actions, add these repository secrets and run the scripts
after the packaging steps:

| Secret | Purpose |
| --- | --- |
| `MAC_APP_SIGN_ID` | Developer ID Application identity |
| `MAC_INSTALLER_SIGN_ID` | Developer ID Installer identity |
| `MAC_NOTARY_PROFILE` | `notarytool` keychain profile, or use Apple ID secrets |
| `APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` | Alternative notarization credentials |
| `WINDOWS_CERT_PFX_BASE64` | Base64-encoded PFX |
| `WINDOWS_CERT_PASSWORD` | PFX password |

Never commit certificates, passwords or notarization credentials. Decode the PFX
into a temporary file inside the Windows job and delete it afterwards.

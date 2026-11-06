#!/usr/bin/env bash
# Signs, packages and notarizes the macOS build.
#
# Required:
#   MAC_APP_SIGN_ID        "Developer ID Application: Name (TEAMID)"
#   MAC_INSTALLER_SIGN_ID  "Developer ID Installer: Name (TEAMID)"
#
# Notarization, one of:
#   MAC_NOTARY_PROFILE     keychain profile from `notarytool store-credentials`
#   APPLE_ID + APPLE_TEAM_ID + APPLE_APP_PASSWORD
#
# Optional:
#   MAC_VERSION            default 2.3.3
#   MAC_DIST               default desktop/build/dist
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DIST="${MAC_DIST:-$ROOT/desktop/build/dist}"
VERSION="${MAC_VERSION:-2.3.3}"
APP="$DIST/Hole Shield.app"
PKG="$DIST/Hole-Shield-$VERSION.pkg"
ENTITLEMENTS="$ROOT/desktop/packaging/macos/entitlements.plist"

: "${MAC_APP_SIGN_ID:?Set MAC_APP_SIGN_ID to a Developer ID Application identity}"
: "${MAC_INSTALLER_SIGN_ID:?Set MAC_INSTALLER_SIGN_ID to a Developer ID Installer identity}"

if [ ! -d "$APP" ]; then
  echo "missing app bundle: $APP" >&2
  echo "run ./desktop/tools/package.sh first" >&2
  exit 1
fi

echo "Signing app bundle…"
codesign --force --deep --options runtime --timestamp \
  --entitlements "$ENTITLEMENTS" --sign "$MAC_APP_SIGN_ID" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

echo "Creating signed PKG…"
TMP_PKG="$(mktemp -d)/Hole-Shield-$VERSION-unsigned.pkg"
pkgbuild --component "$APP" --install-location /Applications \
  --identifier com.dino.holeshield --version "$VERSION" "$TMP_PKG"
productsign --sign "$MAC_INSTALLER_SIGN_ID" "$TMP_PKG" "$PKG"
rm -f "$TMP_PKG"

notarize() {
  local file="$1"
  if [ -n "${MAC_NOTARY_PROFILE:-}" ]; then
    xcrun notarytool submit "$file" --keychain-profile "$MAC_NOTARY_PROFILE" --wait
  elif [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ] \
      && [ -n "${APPLE_APP_PASSWORD:-}" ]; then
    xcrun notarytool submit "$file" --apple-id "$APPLE_ID" \
      --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
  else
    echo "Skipping notarization: set MAC_NOTARY_PROFILE or Apple ID credentials." >&2
    return 0
  fi
  xcrun stapler staple "$file"
  xcrun stapler validate "$file"
}

notarize "$PKG"

echo "Verifying Gatekeeper…"
spctl --assess --type execute --verbose=2 "$APP" || true
spctl --assess --type install --verbose=2 "$PKG" || true

echo "Signed artifacts:"
ls -lh "$APP" "$PKG"

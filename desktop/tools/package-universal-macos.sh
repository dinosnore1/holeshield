#!/usr/bin/env bash
# Builds one universal macOS PKG containing both Apple Silicon and Intel apps.
# The package installs the matching app into /Applications.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION="${MAC_VERSION:-2.3.6}"
OUT="${MAC_OUT:-$ROOT/desktop/build/dist}"
ARM_APP=""
INTEL_APP=""

while [ $# -gt 0 ]; do
  case "$1" in
    --arm) ARM_APP="${2:-}"; shift 2 ;;
    --intel) INTEL_APP="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$ARM_APP" ] || [ ! -d "$ARM_APP" ]; then
  echo "missing Apple Silicon app bundle (--arm)" >&2; exit 1
fi
if [ -z "$INTEL_APP" ] || [ ! -d "$INTEL_APP" ]; then
  echo "missing Intel app bundle (--intel)" >&2; exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/payload/Applications" "$TMP/scripts" "$OUT"

if [ -n "${MAC_APP_SIGN_ID:-}" ]; then
  echo "Signing both app bundles…"
  ENTITLEMENTS="$ROOT/desktop/packaging/macos/entitlements.plist"
  for app in "$ARM_APP" "$INTEL_APP"; do
    codesign --force --deep --options runtime --timestamp \
      --entitlements "$ENTITLEMENTS" --sign "$MAC_APP_SIGN_ID" "$app"
    codesign --verify --deep --strict --verbose=2 "$app"
  done
fi

cp -R "$ARM_APP" "$TMP/payload/Applications/Hole Shield-arm64.app"
cp -R "$INTEL_APP" "$TMP/payload/Applications/Hole Shield-x64.app"

cat > "$TMP/scripts/postinstall" <<'SH'
#!/bin/sh
set -eu
osascript -e 'tell application "Hole Shield" to quit' >/dev/null 2>&1 || true
sleep 1
ARCH="$(uname -m)"
if [ "$ARCH" = "arm64" ]; then
  SRC="/Applications/Hole Shield-arm64.app"
  OTHER="/Applications/Hole Shield-x64.app"
else
  SRC="/Applications/Hole Shield-x64.app"
  OTHER="/Applications/Hole Shield-arm64.app"
fi
DEST="/Applications/Hole Shield.app"
if [ ! -d "$SRC" ]; then
  echo "postinstall: expected $SRC to exist" >&2
  exit 1
fi
rm -rf "$DEST"
mv "$SRC" "$DEST"
chmod +x "$DEST/Contents/MacOS/Hole Shield"
rm -rf "$OTHER"
exit 0
SH
chmod +x "$TMP/scripts/postinstall"

# The package carries both architecture-specific apps with the same bundle
# identifier. Without this, Installer can relocate one of them to any copy of
# Hole Shield.app elsewhere on disk (for example a local build), leaving
# /Applications without the app the postinstall script expects.
pkgbuild --analyze --root "$TMP/payload" "$TMP/components.plist" >/dev/null
for index in 0 1; do
  if /usr/libexec/PlistBuddy -c "Print :$index:BundleIsRelocatable" \
      "$TMP/components.plist" >/dev/null 2>&1; then
    /usr/libexec/PlistBuddy -c "Set :$index:BundleIsRelocatable false" \
      "$TMP/components.plist"
  else
    /usr/libexec/PlistBuddy -c "Add :$index:BundleIsRelocatable bool false" \
      "$TMP/components.plist"
  fi
done

PKG="$OUT/Hole-Shield-$VERSION-universal.pkg"
rm -f "$PKG"

echo "Building universal PKG…"
pkgbuild --root "$TMP/payload" --scripts "$TMP/scripts" \
  --component-plist "$TMP/components.plist" \
  --identifier com.dino.holeshield --version "$VERSION" \
  --install-location / "$PKG"

if [ -n "${MAC_INSTALLER_SIGN_ID:-}" ]; then
  echo "Signing universal PKG…"
  SIGNED="$TMP/Hole-Shield-$VERSION-universal-signed.pkg"
  productsign --sign "$MAC_INSTALLER_SIGN_ID" "$PKG" "$SIGNED"
  mv "$SIGNED" "$PKG"
fi

if [ -n "${MAC_NOTARY_PROFILE:-}" ] || \
   { [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ] \
     && [ -n "${APPLE_APP_PASSWORD:-}" ]; }; then
  if [ -n "${MAC_NOTARY_PROFILE:-}" ]; then
    xcrun notarytool submit "$PKG" --keychain-profile "$MAC_NOTARY_PROFILE" --wait
  else
    xcrun notarytool submit "$PKG" --apple-id "$APPLE_ID" \
      --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" --wait
  fi
  xcrun stapler staple "$PKG"
  xcrun stapler validate "$PKG"
fi

echo "Architectures:"
lipo -info "$ARM_APP/Contents/MacOS/Hole Shield" 2>/dev/null || true
lipo -info "$INTEL_APP/Contents/MacOS/Hole Shield" 2>/dev/null || true
echo "Built:"
ls -lh "$PKG"

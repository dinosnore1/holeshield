#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ]; then
  JDK="$JAVA_HOME"
elif [ -x /opt/homebrew/opt/openjdk@17/bin/jpackage ]; then
  JDK=/opt/homebrew/opt/openjdk@17
elif [ -x /usr/local/opt/openjdk@17/bin/jpackage ]; then
  JDK=/usr/local/opt/openjdk@17
else
  JDK="$(dirname "$(dirname "$(command -v jpackage)")")"
fi

./desktop/tools/build.sh

NAME="Hole Shield"
VERSION="2.3.3"
BUILD="$ROOT/desktop/build"
DEST="$BUILD/dist"
ICONS="$BUILD/icons"
rm -rf "$DEST" "$ICONS"
mkdir -p "$DEST" "$ICONS"

"$JDK/bin/java" -cp "$BUILD/classes" com.dino.holeshield.IconGenerator \
  "$ICONS/icon.png" >/dev/null

OS="$(uname -s)"
ICON=""
if [ "$OS" = "Darwin" ]; then
  ICONSET="$ICONS/HoleShield.iconset"
  mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$ICONS/icon.png" \
      --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$ICONS/icon.png" \
      --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET" -o "$ICONS/icon.icns"
  ICON="$ICONS/icon.icns"
elif [ "$OS" = "Linux" ]; then
  sips -z 512 512 "$ICONS/icon.png" --out "$ICONS/icon-512.png" >/dev/null 2>&1 \
    || cp "$ICONS/icon.png" "$ICONS/icon-512.png"
  ICON="$ICONS/icon-512.png"
else
  ICON="$ICONS/icon.png"
fi

STAGE="$BUILD/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$BUILD/holeshield-desktop.jar" "$STAGE/"

BASE=(
  --name "$NAME"
  --app-version "$VERSION"
  --input "$STAGE"
  --main-jar holeshield-desktop.jar
  --main-class com.dino.holeshield.DesktopApp
  --dest "$DEST"
  --vendor "Dino"
  --description "Local DNS filtering for Windows, Linux and macOS"
  --java-options "-Xmx256m"
  --add-modules java.base,java.desktop,java.net.http,jdk.httpserver,java.logging,jdk.crypto.ec
)
if [ -n "$ICON" ]; then BASE+=(--icon "$ICON"); fi

echo "Building app image…"
"$JDK/bin/jpackage" --type app-image "${BASE[@]}"

if [ "${MAC_APP_ONLY:-0}" = "1" ] && [ "$OS" = "Darwin" ]; then
  echo "macOS app image only (universal package consumes this)."
  exit 0
fi

if [ "$OS" = "Darwin" ]; then
  echo "Building PKG…"
  "$JDK/bin/jpackage" --type pkg "${BASE[@]}" \
    --mac-package-name "$NAME" --mac-package-identifier com.dino.holeshield
elif [ "$OS" = "Linux" ]; then
  echo "Building DEB…"
  "$JDK/bin/jpackage" --type deb "${BASE[@]}" \
    --linux-shortcut --linux-menu-group Network \
    --linux-package-name holeshield \
    --linux-deb-maintainer "Hole Shield <noreply@example.com>"
  if command -v rpmbuild >/dev/null 2>&1; then
    echo "Building RPM…"
    "$JDK/bin/jpackage" --type rpm "${BASE[@]}" \
      --linux-shortcut --linux-menu-group Network \
      --linux-package-name holeshield \
      --linux-rpm-license-type MIT
  else
    echo "rpmbuild not found; skipping RPM."
  fi
else
  echo "Windows installers are built by package.ps1."
fi

echo "Artifacts in $DEST:"
ls -1 "$DEST"

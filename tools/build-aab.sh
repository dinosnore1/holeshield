#!/bin/bash
# Builds and signs the Play upload bundle (AAB) from the same sources as
# build.sh. Requires bundletool and the upload keystore.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

JAR="$SDK/platforms/android-36/android.jar"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
BUNDLETOOL="${BUNDLETOOL:-$HOME/Library/Android/bundletool.jar}"
KS="${UPLOAD_KEYSTORE:-$HOME/.android/holeshield-upload.jks}"
PWFILE="${UPLOAD_PASSWORD_FILE:-$HOME/.android/holeshield-upload-password.txt}"
OUT=build
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)"

for f in "$JAR" "$BUNDLETOOL" "$KS" "$PWFILE" "$JAVA_HOME/bin/jarsigner"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

./build.sh

# AAB modules use the protobuf resource format, not a binary APK.
"$BT/aapt2" link -o "$OUT/proto.apk" -I "$JAR" --manifest AndroidManifest.xml \
  -A assets -R "$OUT"/compiled/*.flat --proto-format --auto-add-overlay

rm -rf "$OUT/base" "$OUT/proto"
mkdir -p "$OUT/base/manifest" "$OUT/base/dex" "$OUT/proto"
unzip -q "$OUT/proto.apk" -d "$OUT/proto"
mv "$OUT/proto/AndroidManifest.xml" "$OUT/base/manifest/"
cp -r "$OUT/proto/res" "$OUT/proto/assets" "$OUT/proto/resources.pb" "$OUT/base/"
cp "$OUT/dex/classes.dex" "$OUT/base/dex/"
(cd "$OUT/base" && zip -qr ../base.zip .)

"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" build-bundle \
  --modules="$OUT/base.zip" --output="$OUT/holeshield.aab"
"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" validate --bundle="$OUT/holeshield.aab" >/dev/null

PASS="$(cat "$PWFILE")"
"$JAVA_HOME/bin/jarsigner" -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
  "$OUT/holeshield.aab" upload >/dev/null
"$JAVA_HOME/bin/jarsigner" -verify "$OUT/holeshield.aab" >/dev/null
cp "$OUT/holeshield.aab" "$OUT/holeshield-$VERSION.aab"

# Produce a universal APK for sideload testing of the exact bundle contents.
"$JAVA_HOME/bin/java" -jar "$BUNDLETOOL" build-apks \
  --bundle="$OUT/holeshield.aab" --output="$OUT/holeshield-test.apks" \
  --mode=universal --ks="$KS" --ks-key-alias=upload \
  --ks-pass="pass:$PASS" --key-pass="pass:$PASS"
rm -rf "$OUT/universal"
mkdir -p "$OUT/universal"
unzip -q "$OUT/holeshield-test.apks" -d "$OUT/universal"

echo "aab:  $(pwd)/$OUT/holeshield.aab"
echo "test: $(pwd)/$OUT/universal/universal.apk"
shasum -a 256 "$OUT/holeshield.aab" "$OUT/universal/universal.apk"

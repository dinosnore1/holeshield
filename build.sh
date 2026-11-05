#!/bin/bash
# Builds holeshield.apk with the Android SDK command-line tools (no Gradle,
# no AndroidX, no third-party libraries).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
JAR="$SDK/platforms/android-36/android.jar"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
LAMBDA="$BT/core-lambda-stubs.jar"

for f in "$JAR" "$BT/aapt2" "$JAVA_HOME/bin/javac"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

python3 make_art.py res/drawable

"$BT/aapt2" compile --dir res -o "$OUT/compiled"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
  --manifest AndroidManifest.xml --java "$OUT/gen" -A assets \
  -R "$OUT"/compiled/*.flat --auto-add-overlay

find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$JAR:$LAMBDA" -d "$OUT/classes" @"$OUT/sources.txt"
[ -f "$OUT/classes/com/dino/holeshield/MainActivity.class" ] \
  || { echo "compile failed: MainActivity.class missing" >&2; exit 1; }
[ -f "$OUT/classes/com/dino/holeshield/DnsVpnService.class" ] \
  || { echo "compile failed: DnsVpnService.class missing" >&2; exit 1; }

find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"

cp "$OUT/unsigned.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/aligned.apk"

KEYSTORE="$HOME/.android/holeshield-sideload.keystore"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias holeshield \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Hole Shield, O=Local"
fi

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/holeshield.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/holeshield.apk"
cp "$OUT/holeshield.apk" "$OUT/holeshield-2.0.0.apk"
echo "built: $(pwd)/$OUT/holeshield.apk ($(du -h "$OUT/holeshield.apk" | cut -f1))"

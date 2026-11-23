#!/bin/bash
# Run after ./build.sh. Pass adb options (e.g. -s SERIAL) as arguments.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$SDK/platforms/android-36/android.jar"
ADB="${ADB:-$SDK/platform-tools/adb}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/holeshield-db-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
PKG=com.dino.holeshield.historytest
mkdir -p "$OUT/classes" "$OUT/dex"
cat > "$OUT/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
    <application android:label="Hole Shield History Test"/>
    <instrumentation android:name="com.dino.holeshield.TestQueryDb" android:targetPackage="$PKG"/>
</manifest>
EOF
"$JDK/bin/javac" --release 8 -cp "$JAR:build/classes" -d "$OUT/classes" tools/TestQueryDb.java
find build/classes "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"
"$BT/aapt2" link -I "$JAR" --manifest "$OUT/AndroidManifest.xml" -o "$OUT/test.apk"
(cd "$OUT/dex" && zip -q ../test.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/test.apk" "$OUT/aligned.apk"
"$JDK/bin/keytool" -genkeypair -keystore "$OUT/test.keystore" -alias test \
    -keyalg RSA -keysize 2048 -validity 30 -storepass android -keypass android \
    -dname "CN=Hole Shield History Test"
"$BT/apksigner" sign --ks "$OUT/test.keystore" --ks-pass pass:android \
    --out "$OUT/signed.apk" "$OUT/aligned.apk"
"$ADB" "$@" install "$OUT/signed.apk"
cleanup() {
    "$ADB" "$@" uninstall "$PKG" >/dev/null
    rm -rf "$OUT"
}
trap 'cleanup "$@"' EXIT
for phase in seed restart overload overload-restart; do
    result="$("$ADB" "$@" shell am instrument -w -e phase "$phase" "$PKG/com.dino.holeshield.TestQueryDb")"
    printf '%s\n' "$result"
    grep -Fq "QueryDb $phase: PASS" <<< "$result"
    "$ADB" "$@" shell am force-stop "$PKG"
done

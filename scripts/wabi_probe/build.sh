#!/usr/bin/env bash
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:?}"; TOOLS="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d -printf '%f\n' | sort -V | tail -1)"; TOOLS="$SDK/build-tools/$TOOLS"
PLATFORM="$(find "$SDK/platforms" -name android.jar | sort -V | tail -1)"
D="$(mktemp -d)"; mkdir -p "$D/classes" "$D/dex"
"$TOOLS/aapt" package -f -M "$SRC/AndroidManifest.xml" -I "$PLATFORM" -F "$D/unsigned.apk"
javac -source 8 -target 8 -bootclasspath "$PLATFORM" -d "$D/classes" "$SRC/ProbeActivity.java"
"$TOOLS/d8" --lib "$PLATFORM" --min-api 23 --output "$D/dex" $(find "$D/classes" -name "*.class")
(cd "$D/dex" && "$TOOLS/aapt" add "$D/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$D/unsigned.apk" "$D/aligned.apk"
keytool -genkeypair -keystore "$D/test.keystore" -storepass android -alias debug -keypass android -dname 'CN=Test only' -keyalg RSA -keysize 2048 -validity 1 -noprompt >/dev/null 2>&1
"$TOOLS/apksigner" sign --ks "$D/test.keystore" --ks-pass pass:android --key-pass pass:android --out /tmp/wabi-vpn-probe.apk "$D/aligned.apk"
"$TOOLS/apksigner" verify /tmp/wabi-vpn-probe.apk
adb install -r /tmp/wabi-vpn-probe.apk

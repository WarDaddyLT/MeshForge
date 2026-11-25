#!/usr/bin/env bash
set -euo pipefail
app="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
out="$app/out"
keystore="$out/debug.keystore"

for input in "$platform" "$tools/aapt2" "$tools/d8" "$tools/zipalign" "$tools/apksigner" "$jdk/bin/javac" "$jdk/bin/jar" "$keystore"; do
  if [ ! -e "$input" ]; then
    printf 'Missing test build input %s; build the app with ./build.sh first.\n' "$input" >&2
    exit 1
  fi
done

work="$(mktemp -d "$out/usb-e2e-build.XXXXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir "$work/classes" "$work/dex"
"$tools/aapt2" link -I "$platform" --manifest "$app/tests/usb-e2e/AndroidManifest.xml" -o "$work/base.apk"
"$jdk/bin/javac" --release 17 -classpath "$platform" -d "$work/classes" \
  "$app/tests/usb-e2e/UsbHostE2EInstrumentation.java"
"$jdk/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
JAVA_HOME="$jdk" "$tools/d8" --lib "$platform" --min-api 33 --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q -j "$work/base.apk" ./*.dex)
"$tools/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
JAVA_HOME="$jdk" "$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android \
  --out "$out/meshforge-usb-e2e-test.apk" "$work/aligned.apk"
JAVA_HOME="$jdk" "$tools/apksigner" verify "$out/meshforge-usb-e2e-test.apk"
printf '%s\n' "$out/meshforge-usb-e2e-test.apk"
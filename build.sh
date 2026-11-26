#!/usr/bin/env bash
set -euo pipefail
app="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
out="$app/out"
usb_commit=a8b9ecc7d32ce6df749c44a2b9e8cb208ac30609
mkdir -p "$out"
usb_source="${USB_SERIAL_SOURCE:-$out/usb-serial}"
if [ ! -d "$usb_source/.git" ]; then
  git clone --quiet --depth 1 --branch v3.10.0 https://github.com/mik3y/usb-serial-for-android.git "$usb_source"
fi
test "$(git -C "$usb_source" rev-parse HEAD)" = "$usb_commit"
if [ -z "${ANDROID_NODE_MODULES:-}" ]; then
  (cd "$app" && npm ci --no-audit --no-fund)
fi
modules="${ANDROID_NODE_MODULES:-$app/node_modules}"
foreach_input() {
  for input in "$platform" "$tools/aapt2" "$jdk/bin/javac"; do
    if [ ! -f "$input" ]; then
      echo "Missing build input $input" >&2
      exit 1
    fi
  done
}
foreach_input
work="$(mktemp -d "$out/build.XXXXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir "$work/assets" "$work/classes" "$work/dex" "$work/usb"
cp "$app"/assets/* "$work/assets/"
cp "$usb_source/LICENSE.txt" "$work/assets/usb-serial-LICENSE.txt"
cp "$modules/esptool-js/LICENSE" "$work/assets/esptool-js-LICENSE.txt"
cp "$app/reference-LICENSE.txt" "$work/assets/esp-flash-android-LICENSE.txt"
cp "$app/catalogue-LICENSE.txt" "$work/assets/meshcore-catalogue-LICENSE.txt"
cp -R "$usb_source/usbSerialForAndroid/src/main/java/." "$work/usb"
# The only AndroidX dependency in these Java sources is a source-retention type annotation.
sed '/^import androidx.annotation.IntDef;/d; /^[[:space:]]*@IntDef(/d' \
  "$work/usb/com/hoho/android/usbserial/driver/UsbSerialPort.java" > "$work/UsbSerialPort.java"
mv "$work/UsbSerialPort.java" "$work/usb/com/hoho/android/usbserial/driver/UsbSerialPort.java"
cat > "$work/usb/com/hoho/android/usbserial/BuildConfig.java" <<'JAVA'
package com.hoho.android.usbserial;
public final class BuildConfig {
    public static final boolean DEBUG = false;
}
JAVA
"$modules/.bin/esbuild" "$modules/esptool-js/lib/index.js" --bundle --format=iife --global-name=esptool \
  --platform=browser --target=es2020 --outfile="$work/assets/esptool.bundle.js"
"$tools/aapt2" compile --dir "$app/res" -o "$work/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$app/AndroidManifest.xml" -A "$work/assets" \
  -o "$work/base.apk" "$work/resources.zip"
find "$app/src" "$work/usb" -name '*.java' > "$work/java-sources"
"$jdk/bin/javac" --release 17 -classpath "$platform" -d "$work/classes" @"$work/java-sources"
"$jdk/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
JAVA_HOME="$jdk" "$tools/d8" --lib "$platform" --min-api 33 --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q -j "$work/base.apk" ./*.dex)
"$tools/zipalign" -f 4 "$work/base.apk" "$work/aligned.apk"
keystore="$out/debug.keystore"
if [ ! -f "$keystore" ]; then
  "$jdk/bin/keytool" -genkeypair -keystore "$keystore" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=MeshForge Android Debug" -keyalg RSA -validity 10000
fi
JAVA_HOME="$jdk" "$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android \
  --out "$out/meshforge-field-flasher-debug.apk" "$work/aligned.apk"
JAVA_HOME="$jdk" "$tools/apksigner" verify "$out/meshforge-field-flasher-debug.apk"
echo "$out/meshforge-field-flasher-debug.apk"

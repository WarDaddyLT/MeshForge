#!/usr/bin/env bash
set -euo pipefail
app="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="${ANDROID_ADB:-$sdk/platform-tools/adb}"
mode="${1:-}"
if [ "$#" -gt 0 ]; then shift; fi
board="${1:-}"
if [ "$#" -gt 0 ]; then shift; fi
uf2="${1:-}"

case "$mode" in
  serial|dfu|esp32-probe) ;;
  *)
    printf 'Usage:\n  tests/run-usb-e2e.sh serial <board-name>\n  tests/run-usb-e2e.sh esp32-probe "Elecrow ThinkNode M5"\n  tests/run-usb-e2e.sh dfu <board-name> <verified-board-matched.uf2>\n' >&2
    exit 2
    ;;
esac
if [ "$mode" = "esp32-probe" ] && [ "$board" != "Elecrow ThinkNode M5" ]; then
  printf 'The non-writing ESP32 ROM/layout probe is currently verified only for Elecrow ThinkNode M5.\n' >&2
  exit 2
fi
if [ -z "$board" ]; then
  printf 'A board name is required for hardware identity checks.\n' >&2
  exit 2
fi
if [ "$mode" = "dfu" ] && { [ -z "$uf2" ] || [ ! -f "$uf2" ]; }; then
  printf 'DFU mode requires a local, verified UF2 file for the selected board.\n' >&2
  exit 2
fi
if [ ! -x "$adb" ]; then
  printf 'ADB not found: %s\n' "$adb" >&2
  exit 2
fi

serial="${ANDROID_SERIAL:-}"
if [ -z "$serial" ]; then
  devices="$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  count="$(printf '%s\n' "$devices" | awk 'NF { n++ } END { print n + 0 }')"
  if [ "$count" -ne 1 ]; then
    printf 'Expected exactly one ADB device, found %s. Set ANDROID_SERIAL when more than one is connected.\n' "$count" >&2
    exit 1
  fi
  serial="$devices"
fi
adb_device() { "$adb" -s "$serial" "$@"; }

"$app/build.sh"
"$app/tests/build-usb-e2e.sh"
adb_device install -r "$app/out/meshforge-field-flasher-debug.apk"
adb_device install -r "$app/out/meshforge-usb-e2e-test.apk"

remote_name=""
cleanup() {
  if [ -n "$remote_name" ]; then
    adb_device shell rm -f "/sdcard/Android/data/dev.meshforge.fieldflasher/files/$remote_name" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [ "$mode" = "dfu" ]; then
  remote_name="meshforge-usb-e2e-$$.uf2"
  adb_device shell mkdir -p /sdcard/Android/data/dev.meshforge.fieldflasher/files
  adb_device push "$uf2" "/sdcard/Android/data/dev.meshforge.fieldflasher/files/$remote_name"
  printf 'Put the radio into UF2/DFU mode and connect it to the Android phone by USB-C/OTG. Approve MeshForge USB access if prompted.\n'
elif [ "$mode" = "esp32-probe" ]; then
  printf 'Connect one Elecrow ThinkNode M5 in normal MeshCore mode by USB-C/OTG. The test will request confirmation for a non-writing ROM/layout probe; tap Check layout on the phone.\n'
else
  printf 'Connect one MeshCore radio in normal operating mode to the Android phone by USB-C/OTG. Approve MeshForge USB access if prompted.\n'
fi

args=(-w -e mode "$mode" -e board "$board")
if [ -n "$remote_name" ]; then args+=(-e imageFile "$remote_name"); fi
adb_device shell am instrument "${args[@]}" \
  dev.meshforge.fieldflasher.usbtest/.UsbHostE2EInstrumentation
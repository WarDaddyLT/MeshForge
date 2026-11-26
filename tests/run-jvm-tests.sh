#!/usr/bin/env bash
set -euo pipefail

project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
jdk="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
platform="$sdk/platforms/android-37.0/android.jar"
suites=(EspApplicationPlan FirmwareRuntime Uf2Volume DiagnosticLog)

cd "$project"
mkdir -p out
classes="$(mktemp -d out/jvm-tests.XXXXXXXX)"
trap 'rm -rf "$classes"' EXIT
sources=()
for suite in "${suites[@]}"; do sources+=("tests/${suite}Test.java"); done
"$jdk/bin/javac" --release 17 -classpath "$platform" -sourcepath src -d "$classes" "${sources[@]}"
for suite in "${suites[@]}"; do
  "$jdk/bin/java" -cp "$classes" "dev.meshforge.fieldflasher.${suite}Test"
done

#!/bin/bash
# Compila el APK desktop-style.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export PATH=$JAVA_HOME/bin:$PATH
cd "$ROOT"
./gradlew :app:assembleRelease
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
cp "$APK" "$ROOT/$(basename "$APK" .apk).apk"
ls -la "$ROOT"/*.apk

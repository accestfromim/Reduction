#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_HOME="${JAVA_HOME:-/home/user/tools/jdk-17.0.2}"
ANDROID_HOME="${ANDROID_HOME:-/home/user/android-sdk}"
GRADLE_BIN="${GRADLE_BIN:-/home/user/tools/gradle-8.9/bin/gradle}"
VERSION_CODE="${VERSION_CODE:-30}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

export JAVA_HOME
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT_DIR"
"$GRADLE_BIN" --no-daemon -PappVersionCode="$VERSION_CODE" -PappVersionName="$VERSION_NAME" assembleRelease

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/DSAssist-v${VERSION_NAME}.apk"
echo "APK: $ROOT_DIR/dist/DSAssist-v${VERSION_NAME}.apk"

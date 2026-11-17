#!/usr/bin/env bash
# Builds the phone app: dist/Reduction-v<version>.apk
set -euo pipefail
. "$(dirname "$0")/tools/env.sh"
VERSION_CODE="${VERSION_CODE:-91}"
VERSION_NAME="${VERSION_NAME:-1.0.61}"

cd "$ROOT"
$GRADLE --no-daemon -PappVersionCode="$VERSION_CODE" -PappVersionName="$VERSION_NAME" \
  -PupdateManifestUrl="$REDUCTION_UPDATE_MANIFEST" assembleRelease

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/Reduction-v${VERSION_NAME}.apk"
echo "APK: $ROOT/dist/Reduction-v${VERSION_NAME}.apk"

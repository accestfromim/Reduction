#!/usr/bin/env bash
set -euo pipefail

SOURCE="$SRCROOT/../app/src/main/assets"
DEST="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
mkdir -p "$DEST/WebAssets" "$DEST/Harness"
for name in index.html css js vendor article; do
  /usr/bin/ditto "$SOURCE/$name" "$DEST/WebAssets/$name"
done
/usr/bin/ditto "$SOURCE/harness" "$DEST/Harness"
cp "$SRCROOT/Resources/ios.js" "$SRCROOT/Resources/ios.css" "$DEST/WebAssets/"

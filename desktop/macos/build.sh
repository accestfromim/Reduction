#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../../tools/env.sh"
cd "$ROOT"
[ "$(uname -s)" = Darwin ] || { echo "请在 macOS 上构建 Reduction.app。" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -F -v 17)"
fi
export JAVA_HOME
for program in java jdeps jlink; do
  [ -x "$JAVA_HOME/bin/$program" ] || { echo "构建需要完整的 JDK 17，请在 local.env 中设置 JAVA_HOME。" >&2; exit 1; }
done

WORK="$ROOT/build/macos"
mkdir -p "$WORK" "$ROOT/dist"
STAGE="$(mktemp -d "$WORK/package.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$WORK/scratch" "$WORK/swift-cache" "$STAGE/Reduction.iconset"
export TMPDIR="$WORK/scratch"

"$GRADLE" --configure-on-demand :desktop:installDist
LIBS="$ROOT/desktop/build/install/reduction/lib"
APP="$STAGE/Reduction.app"
CONTENTS="$APP/Contents"
RESOURCES="$CONTENTS/Resources"
mkdir -p "$CONTENTS/MacOS" "$RESOURCES/service"
cp "$HERE/Info.plist" "$CONTENTS/Info.plist"
cp "$ROOT/LICENSE" "$RESOURCES/LICENSE"
cp "$LIBS/"*.jar "$RESOURCES/service/"

MODULES="$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --multi-release 17 --print-module-deps "$LIBS/"*.jar)"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES,jdk.crypto.ec" --strip-debug --no-header-files --no-man-pages \
  --compress=2 --output "$RESOURCES/runtime"

ARCH="$(uname -m)"
xcrun swiftc -swift-version 5 -O -target "$ARCH-apple-macos14.4" -module-cache-path "$WORK/swift-cache" \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker "$CONTENTS/Info.plist" \
  "$HERE/"*.swift -o "$CONTENTS/MacOS/Reduction"

ICON="$ROOT/desktop/src/main/resources/icon.png"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$ICON" --out "$STAGE/Reduction.iconset/icon_${size}x${size}.png" >/dev/null
  sips -z "$((size * 2))" "$((size * 2))" "$ICON" --out "$STAGE/Reduction.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$STAGE/Reduction.iconset" -o "$RESOURCES/Reduction.icns"
codesign --force --sign - "$APP"
codesign --verify --strict "$APP"

OUTPUT="$ROOT/dist/Reduction.app"
if [ -e "$OUTPUT" ]; then mv "$OUTPUT" "$STAGE/Previous.app"; fi
if ! mv "$APP" "$OUTPUT"; then
  if [ -e "$STAGE/Previous.app" ]; then mv "$STAGE/Previous.app" "$OUTPUT"; fi
  exit 1
fi
printf 'macOS 应用：%s\n' "$OUTPUT"

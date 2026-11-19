#!/usr/bin/env bash
# Package an Android update. Raw Gradle builds use the recorded version without advancing it.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/tools/env.sh"

lock_android_package() {
    cd "$ROOT"
    mkdir -p dist
    exec 9>dist/.apk-build.lock
    flock 9
}

# Also used by publish.sh, which holds the same lock through its upload.
build_android_package() {
    local variant=$1 step=$2 current code major minor patch suffix="" candidate
    shift 2
    local semver='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
    current=$(sed -n 's/^appVersionName=//p' version.properties)
    code=$(sed -n 's/^appVersionCode=//p' version.properties)
    [[ $current =~ $semver && $code =~ ^[1-9][0-9]*$ ]] || { echo "Invalid version.properties" >&2; return 1; }
    IFS=. read -r major minor patch <<< "$current"
    case "$step" in
        patch) candidate="$major.$minor.$((patch + 1))" ;;
        minor) candidate="$major.$((minor + 1)).0" ;;
        major) candidate="$((major + 1)).0.0" ;;
        *) candidate="$step" ;;
    esac
    APK_VERSION_NAME=${VERSION_NAME:-$candidate}
    APK_VERSION_CODE=${VERSION_CODE:-$((code + 1))}
    if ! [[ $APK_VERSION_NAME =~ $semver && $APK_VERSION_CODE =~ ^[1-9][0-9]*$ ]] ||
        [[ $APK_VERSION_NAME == "$current" ]] || ((APK_VERSION_CODE <= code)) ||
        ! printf '%s\n' "$current" "$APK_VERSION_NAME" | sort -V -C; then
        echo "The APK version must advance from $current ($code)" >&2
        return 1
    fi

    [[ $variant == debug ]] && suffix=-debug
    APK_FILE="dist/Reduction-v$APK_VERSION_NAME$suffix.apk"
    [[ ! -e $APK_FILE ]] || { echo "$APK_FILE already exists" >&2; return 1; }
    $GRADLE --no-daemon -PappVersionCode="$APK_VERSION_CODE" -PappVersionName="$APK_VERSION_NAME" \
        -PupdateManifestUrl="$REDUCTION_UPDATE_MANIFEST" "$@" ":app:assemble${variant^}"
    local output="app/build/outputs/apk/$variant"
    local built="$output/app-$variant.apk"
    [[ -s $built ]] || { echo "Missing signed APK at $built" >&2; return 1; }
    python3 - "$output/output-metadata.json" "${built##*/}" "$APK_VERSION_NAME" "$APK_VERSION_CODE" <<'PY'
import json, sys
with open(sys.argv[1]) as metadata:
    (apk,) = json.load(metadata)["elements"]
if (apk["outputFile"], apk["versionName"], apk["versionCode"]) != (sys.argv[2], sys.argv[3], int(sys.argv[4])):
    sys.exit("Gradle produced a different APK or version than requested")
PY
    (
        set -e
        local artifact version_file
        artifact=$(mktemp dist/.package-XXXXXXXX)
        version_file=$(mktemp ./version.properties.XXXXXXXX)
        trap 'rm -f -- "$artifact" "$version_file"' EXIT
        install -m 644 "$built" "$artifact"
        printf 'appVersionName=%s\nappVersionCode=%s\n' "$APK_VERSION_NAME" "$APK_VERSION_CODE" > "$version_file"
        chmod 644 "$version_file"
        # Reserve the completed build number before exposing the APK in dist/.
        mv "$version_file" version.properties
        mv "$artifact" "$APK_FILE"
    )
    echo "APK: $ROOT/$APK_FILE"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    variant=release
    if [[ ${1:-} == --debug ]]; then variant=debug; shift; fi
    [[ $# -le 1 ]] || { echo "usage: build.sh [--debug] [patch|minor|major|X.Y.Z]" >&2; exit 2; }
    lock_android_package
    build_android_package "$variant" "${1:-patch}"
fi

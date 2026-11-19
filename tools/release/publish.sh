#!/usr/bin/env bash
# Publishes a new version of the phone app, start to finish:
#   tools/release/publish.sh            the next patch version:  1.0.62 -> 1.0.63
#   tools/release/publish.sh minor      the next minor version:  1.0.62 -> 1.1.0
#   tools/release/publish.sh major      the next major version:  1.0.62 -> 2.0.0
#   tools/release/publish.sh 1.4.0      this very version
# Runs the tests and lint, packages one new version, makes the
# patch from the version now live, uploads, and checks the live files with the app's own
# update code. The manifest is swapped last, so a failure before that publishes nothing.
set -euo pipefail
STEP="${1:-patch}"
. "$(dirname "$0")/../../build.sh"
SERVER="${REDUCTION_UPDATE_HOST:?set REDUCTION_UPDATE_HOST in local.env: the ssh host updates are served from}"
REMOTE="${REDUCTION_UPDATE_DIR:?set REDUCTION_UPDATE_DIR in local.env: the directory on that host}"
MANIFEST_URL="${REDUCTION_UPDATE_MANIFEST:?set REDUCTION_UPDATE_MANIFEST in local.env: the address the app asks}"
lock_android_package

# Debug APKs also advance the local version. Make the patch from the APK phones actually have.
live=$(mktemp)
trap 'rm -f "$live"' EXIT
status=$(curl -sSL --output "$live" --write-out '%{http_code}' "$MANIFEST_URL")
case "$status" in
  200)
    metadata=$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); print(m["versionName"], m["versionCode"], m["sha256"])' "$live")
    read -r OLD OLD_CODE old_hash <<< "$metadata"
    [[ $OLD =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && $OLD_CODE =~ ^[1-9][0-9]*$ && $old_hash =~ ^[0-9a-fA-F]{64}$ ]]
    printf '%s  %s\n' "$old_hash" "dist/Reduction-v$OLD.apk" | sha256sum --check ;;
  404)
    # Before the first manifest, use the latest local release APK as the initial patch base.
    OLD=$(printf '%s\n' dist/Reduction-v*.apk | sed -nE 's|^dist/Reduction-v([0-9]+\.[0-9]+\.[0-9]+)\.apk$|\1|p' | sort -V | tail -1)
    [[ -n $OLD ]] || { echo "Missing previous release APK in dist/" >&2; exit 1; }
    OLD_CODE=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "dist/Reduction-v$OLD.apk" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
    [[ $OLD_CODE =~ ^[1-9][0-9]*$ ]] ;;
  *) echo "Could not read the published update manifest (HTTP $status)" >&2; exit 1 ;;
esac

echo "== tests, lint and package"
build_android_package release "$STEP" :app:testReleaseUnitTest :app:lintRelease
python3 - <<'PY'
import glob, re, sys
tests = failures = 0
for path in glob.glob('app/build/test-results/testReleaseUnitTest/*.xml'):
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(path, encoding='utf-8').read(600))
    tests += int(m[1]); failures += int(m[2]) + int(m[3])
lint = len(re.findall(r'severity="(Error|Fatal)"', open('app/build/reports/lint-results-release.xml', encoding='utf-8').read()))
print(f'{tests} tests, {failures} failed; {lint} lint errors')
sys.exit(1 if failures or lint else 0)
PY

NEW=$APK_VERSION_NAME
CODE=$APK_VERSION_CODE
APK=${APK_FILE##*/}; PATCH="patch-$OLD_CODE-$CODE.bin"
python3 tools/release/make_update.py --from "dist/Reduction-v$OLD.apk" --from-code "$OLD_CODE" --to "dist/$APK" --to-code "$CODE" --to-name "$NEW"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "dist/$APK"

echo "== upload"
( cd dist
  scp -q -o BatchMode=yes -o LogLevel=ERROR "$APK" "$PATCH" "$SERVER:$REMOTE/"
  scp -q -o BatchMode=yes -o LogLevel=ERROR manifest.json "$SERVER:$REMOTE/manifest.json.new"
  ssh -o BatchMode=yes -o LogLevel=ERROR "$SERVER" "cd $REMOTE && sha256sum $APK $PATCH" | cut -c1-64 > /tmp/reduction-remote.sha
  diff /tmp/reduction-remote.sha <(cut -c1-64 SHA256SUMS.txt) || { echo "the uploaded files differ from the built ones; nothing was published"; exit 1; }
  ssh -o BatchMode=yes -o LogLevel=ERROR "$SERVER" "cd $REMOTE && mv manifest.json.new manifest.json && chmod 644 manifest.json $APK $PATCH" )

echo "== live check"
for attempt in 1 2 3; do
  REDUCTION_UPDATE_MANIFEST="$MANIFEST_URL" REDUCTION_INSTALLED_APK="$PWD/dist/Reduction-v$OLD.apk" \
    $GRADLE -q :app:testReleaseUnitTest --tests '*UpdateFlowTest*' >/dev/null 2>&1 || true
  if grep -q 'tests="1" skipped="0" failures="0" errors="0"' app/build/test-results/testReleaseUnitTest/TEST-*UpdateFlowTest.xml; then
    echo "published $NEW: a phone on $OLD updates to it through the patch"
    exit 0
  fi
done
echo "$NEW is uploaded, but the live update check did not pass"
exit 1

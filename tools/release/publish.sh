#!/usr/bin/env bash
# Publishes a new version of the phone app, start to finish:
#   tools/release/publish.sh 1.0.57
# Bumps the version everywhere it is written, runs the tests and lint, builds, makes the
# patch from the version now live, uploads, and checks the live files with the app's own
# update code. The manifest is swapped last, so a failure before that publishes nothing.
set -euo pipefail
NEW="${1:?usage: publish.sh <new version name, e.g. 1.0.57>}"
. "$(dirname "$0")/../env.sh"
cd "$ROOT"
SERVER="${REDUCTION_UPDATE_HOST:?set REDUCTION_UPDATE_HOST in local.env: the ssh host updates are served from}"
REMOTE="${REDUCTION_UPDATE_DIR:?set REDUCTION_UPDATE_DIR in local.env: the directory on that host}"
MANIFEST_URL="${REDUCTION_UPDATE_MANIFEST:?set REDUCTION_UPDATE_MANIFEST in local.env: the address the app asks}"

OLD="$(sed -n 's/.*appVersionName.*?: "\(.*\)"/\1/p' app/build.gradle.kts)"
OLD_CODE="$(sed -n 's/.*appVersionCode.*?: \([0-9]*\)$/\1/p' app/build.gradle.kts)"
CODE=$((OLD_CODE + 1))
[ -n "$OLD" ] && [ -n "$OLD_CODE" ] || { echo "cannot read the current version"; exit 1; }
[ -f "dist/Reduction-v$OLD.apk" ] || { echo "dist/Reduction-v$OLD.apk is missing: the patch is made from it"; exit 1; }
echo "== $OLD ($OLD_CODE) -> $NEW ($CODE)"

sed -i "s/?: $OLD_CODE\$/?: $CODE/; s/?: \"$OLD\"/?: \"$NEW\"/" app/build.gradle.kts
sed -i "s/VERSION_CODE:-$OLD_CODE/VERSION_CODE:-$CODE/; s/VERSION_NAME:-$OLD/VERSION_NAME:-$NEW/" build.sh
sed -i "s/BRIDGE_VERSION_CODE = $OLD_CODE/BRIDGE_VERSION_CODE = $CODE/" app/src/main/java/com/mindtravel/reduction/hardware/update/AssetUpdater.kt
sed -i "s/v$OLD/v$NEW/g; s/必须不低于 $OLD_CODE（/必须不低于 $CODE（/" docs/DEVELOPMENT.md

echo "== tests and lint"
$GRADLE -q :app:testReleaseUnitTest :app:lintRelease
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

echo "== build"
./build.sh | tail -1
APK="Reduction-v$NEW.apk"; PATCH="patch-$OLD_CODE-$CODE.bin"
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

#!/usr/bin/env bash
# Starts Reduction on this computer. Open the address it prints in a browser.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/home/user/tools/jdk-17.0.2}"
GRADLE="${GRADLE:-/home/user/tools/gradle-8.9/bin/gradle}"
"$GRADLE" --offline -q :desktop:installDist
exec desktop/build/install/reduction/bin/reduction "$@"

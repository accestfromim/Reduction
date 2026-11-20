#!/usr/bin/env bash
# Starts Reduction on this computer. Open the address it prints in a browser.
set -euo pipefail
. "$(dirname "$0")/tools/env.sh"
cd "$ROOT"
$GRADLE -q :desktop:installDist
export REDUCTION_ROOT="$ROOT"
exec desktop/build/install/reduction/bin/reduction "$@"

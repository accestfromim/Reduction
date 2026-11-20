#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/../tools/env.sh"
cd "$ROOT"

if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -F -v 17)"
    export JAVA_HOME
fi

if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-}" = "YES" ]; then
    exit 0
fi

"$GRADLE" --configure-on-demand :shared:embedAndSignAppleFrameworkForXcode

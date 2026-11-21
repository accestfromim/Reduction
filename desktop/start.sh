#!/usr/bin/env bash
# Runs Reduction's service on this computer, unless it is already running.
# It stays in the foreground of this session: that is what keeps it alive.
# The window is opened by whoever calls this — on Windows, the launcher install.sh sets up.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=8780
URL="http://localhost:$PORT"
DATA="$HOME/.local/share/reduction"
mkdir -p "$DATA"
. "$HERE/../tools/env.sh"
export PULSE_SERVER="${PULSE_SERVER:-unix:/mnt/wslg/PulseServer}"

running() { curl -s -m 2 -o /dev/null "$URL/icon.png"; }

running && exit 0
# Right after login the sound system may not be up yet; the service looks for it once, at its start.
for _ in $(seq 1 60); do [ -S /mnt/wslg/PulseServer ] && break; sleep 0.5; done
export REDUCTION_ROOT="$ROOT"
# installDist may replace ZIP entries in place. Give this JVM its own immutable classpath.
runtime=$(mktemp -d) || exit 1
trap 'rm -rf -- "$runtime"' EXIT
cp -R --reflink=auto "$HERE/build/install/reduction/." "$runtime/" || exit 1
"$runtime/bin/reduction" "$PORT" >> "$DATA/desktop.log" 2>&1

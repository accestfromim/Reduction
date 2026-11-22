#!/usr/bin/env bash
# All launchers start the same user service. Only its ExecStart runs the JVM.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=8780
unit=reduction-desktop.service
if [ "${1:-}" != --serve ]; then
  case "${1:-start}" in start|restart) action=${1:-start} ;; *) exit 2 ;; esac
  unitdir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  mkdir -p "$unitdir"
  tmp=$(mktemp "$unitdir/.reduction-unit-XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  cat > "$tmp" <<UNIT
[Unit]
Description=Reduction Linux desktop backend
[Service]
ExecStart="$HERE/start.sh" --serve
Restart=on-failure
RestartSec=3
[Install]
WantedBy=default.target
UNIT
  if ! cmp -s "$tmp" "$unitdir/$unit"; then
    mv "$tmp" "$unitdir/$unit"
    systemctl --user daemon-reload
  fi
  systemctl --user enable "$unit" --quiet
  systemctl --user "$action" "$unit"
  exit
fi
DATA="$HOME/.local/share/reduction"
mkdir -p "$DATA"
. "$HERE/../tools/env.sh"
export PULSE_SERVER="${PULSE_SERVER:-unix:/mnt/wslg/PulseServer}"

# Right after login the sound system may not be up yet; the service looks for it once, at its start.
for _ in $(seq 1 60); do [ -S /mnt/wslg/PulseServer ] && break; sleep 0.5; done
export REDUCTION_AGENT_SYSTEMD=1
export REDUCTION_ROOT="$ROOT"
# installDist may replace ZIP entries in place. Give this JVM its own immutable classpath.
runtime=$(mktemp -d) || exit 1
trap 'rm -rf -- "$runtime"' EXIT
cp -R --reflink=auto "$HERE/build/install/reduction/." "$runtime/" || exit 1
"$runtime/bin/reduction" "$PORT" >> "$DATA/desktop.log" 2>&1

#!/usr/bin/env bash
set -eu
here=$(cd "$(dirname "$0")" && pwd)
"$here/start.sh"
url=http://127.0.0.1:8780
for attempt in {1..60}; do
  if curl -fsS --max-time 1 "$url/index.html" -o /dev/null; then
    for browser in google-chrome chromium chromium-browser; do
      if command -v "$browser" >/dev/null; then
        exec "$browser" --app="$url"
      fi
    done
    echo "Install Chrome or Chromium in Linux to open the Reduction panel." >&2
    exit 1
  fi
  sleep 0.5
done
echo "Reduction did not respond at $url" >&2
exit 1

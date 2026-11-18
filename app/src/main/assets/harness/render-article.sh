#!/bin/sh
# Draws a finished article page as the long picture it is published as.
#
#   render-article.sh <name>      the page's HTML on standard input
#
# The page is laid out 540 CSS pixels wide and drawn at twice that: a picture 1080 pixels wide and as
# long as the page. Prints "PATH <file>" and "SIZE <width> <height>" for the picture, which is kept
# under ~/reduction-agents/articles/<name>.png beside <name>.html, the page it was drawn from; then,
# for whoever is to look at it, a JPEG of it under a line "=====IMAGE article.jpeg image/jpeg=====" in
# base64 — the whole of a short picture, the top of a long one. Anything else printed is what went wrong.
set -eu
name=$(printf '%s' "${1:-article}" | LC_ALL=C tr -c 'A-Za-z0-9._\200-\377-' '_' | cut -c1-80)
[ -n "$name" ] || name=article
out=$HOME/reduction-agents/articles
mkdir -p "$out"
page=$out/$name.html
picture=$out/$name.png
work=$(mktemp -d "$out/.render.XXXXXX")
trap 'rm -rf "$work"' EXIT
cat > "$work/article.html"
[ -s "$work/article.html" ] || { echo "出图失败：没有收到页面"; exit 0; }

look=$work/look.jpg
valid_height() {
  case "$height" in ''|*[!0-9]*) echo "出图失败：页面没有排出来"; exit 0 ;; esac
  [ "$height" -gt 0 ] && [ "$height" -le 60000 ] || { echo "出图失败：页面高度不对（$height）"; exit 0; }
}
run_chrome() {
  timeout 90 "$chrome" --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --user-data-dir="$work/profile" --run-all-compositor-stages-before-draw --virtual-time-budget=8000 "$@" 2>/dev/null
}
if [ "$(uname -s)" = Darwin ]; then
  renderer=${REDUCTION_MAC_ARTICLE:-}
  if [ -z "$renderer" ] && [ -f "$HOME/.local/share/reduction/macos-renderer" ]; then
    renderer=$(cat "$HOME/.local/share/reduction/macos-renderer")
  fi
  [ -n "$renderer" ] && [ -x "$renderer" ] || { echo "出图失败：请构建并打开 macOS 版 Reduction"; exit 0; }
  height=$("$renderer" --render-article "$work/article.html" "$work/article.png" "$look" 2>"$work/render.log") || {
    echo "出图失败：WebKit 没有画出图片"
    cat "$work/render.log"
    exit 0
  }
  valid_height
  shown=$((height < 1800 ? height : 1800))
else
  chrome=${CHROME_PATH:-}
  for candidate in google-chrome google-chrome-stable chromium chromium-browser; do
    [ -n "$chrome" ] && break
    chrome=$(command -v "$candidate" 2>/dev/null || true)
  done
  [ -n "$chrome" ] || { echo "出图失败：这台电脑上没有 Chrome 或 Chromium"; exit 0; }
  height=$(run_chrome --window-size=540,800 --dump-dom "file://$work/article.html" | sed -n 's/.*data-article-height="\([0-9]*\)".*/\1/p' | head -1)
  valid_height
  run_chrome --window-size=540,"$height" --screenshot="$work/article.png" "file://$work/article.html" >/dev/null || { echo "出图失败：Chrome 没有画出图片"; exit 0; }
  shown=$((height < 1800 ? height : 1800))
  run_chrome --window-size=540,"$shown" --force-device-scale-factor=1.5 --screenshot="$work/look.png" "file://$work/article.html" >/dev/null || true
  if [ -s "$work/look.png" ] && command -v convert >/dev/null 2>&1; then
    convert "$work/look.png" -quality 82 "$look" 2>/dev/null || true
  fi
fi
[ -s "$work/article.png" ] || { echo "出图失败：没有生成长图"; exit 0; }
mv "$work/article.html" "$page"
mv "$work/article.png" "$picture"
printf 'PATH %s\nSIZE %s %s\n' "$picture" 1080 $((height * 2))

if [ -s "$look" ]; then kind=image/jpeg; shot=$look; else kind=image/png; shot=$work/look.png; fi
if [ -s "$shot" ] && [ "$(wc -c < "$shot")" -le 3145728 ]; then
  [ "$shown" -lt "$height" ] && printf 'SHOWN top %s of %s\n' $((shown * 2)) $((height * 2))
  printf '\n=====IMAGE %s %s=====\n' "article.${kind#image/}" "$kind"
  base64 < "$shot" | tr -d '\r\n'
fi

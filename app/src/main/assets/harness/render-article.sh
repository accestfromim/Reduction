#!/bin/sh
# Draws a finished article page as the long picture it is published as, with this computer's Chrome.
#
#   render-article.sh <name>      the page's HTML on standard input
#
# The page is laid out 540 CSS pixels wide and drawn at twice that: a picture 1080 pixels wide and as
# long as the page. Prints "PATH <file>" and "SIZE <width> <height>" for the picture, which is kept
# under ~/reduction-agents/articles/<name>.png beside <name>.html, the page it was drawn from; then,
# for whoever is to look at it, a JPEG of it under a line "=====IMAGE article.jpeg image/jpeg=====" in
# base64 — the whole of a short picture, the top of a long one. Anything else printed is what went wrong.
set -eu
name=$(printf '%s' "${1:-article}" | tr -c 'A-Za-z0-9._\200-\377-' '_' | cut -c1-80)
[ -n "$name" ] || name=article
out=$HOME/reduction-agents/articles
mkdir -p "$out"
page=$out/$name.html
picture=$out/$name.png
cat > "$page"
[ -s "$page" ] || { echo "出图失败：没有收到页面"; exit 0; }

chrome=${CHROME_PATH:-}
for candidate in google-chrome google-chrome-stable chromium chromium-browser; do
  [ -n "$chrome" ] && break
  chrome=$(command -v "$candidate" 2>/dev/null || true)
done
[ -n "$chrome" ] || { echo "出图失败：这台电脑上没有 Chrome 或 Chromium"; exit 0; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
run() {
  timeout 90 "$chrome" --headless=new --no-sandbox --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --user-data-dir="$work/profile" --run-all-compositor-stages-before-draw --virtual-time-budget=8000 "$@" 2>/dev/null
}
# How long the page is, once its pictures and fonts are in: the page says so itself, and is asked first.
height=$(run --window-size=540,800 --dump-dom "file://$page" | sed -n 's/.*data-article-height="\([0-9]*\)".*/\1/p' | head -1)
case "$height" in ''|*[!0-9]*) echo "出图失败：页面没有排出来"; exit 0 ;; esac
[ "$height" -gt 0 ] && [ "$height" -le 60000 ] || { echo "出图失败：页面高度不对（$height）"; exit 0; }
run --window-size=540,"$height" --screenshot="$picture" "file://$page" > /dev/null || true
[ -s "$picture" ] || { echo "出图失败：Chrome 没有画出图片"; exit 0; }
printf 'PATH %s\nSIZE %s %s\n' "$picture" 1080 $((height * 2))

# Something to look at: the top of the picture as a JPEG, small enough to send.
look=$work/look.jpg
shown=$height
[ "$shown" -gt 1800 ] && shown=1800
run --window-size=540,"$shown" --force-device-scale-factor=1.5 --screenshot="$work/look.png" "file://$page" > /dev/null || true
if [ -s "$work/look.png" ]; then
  if command -v convert > /dev/null 2>&1; then convert "$work/look.png" -quality 82 "$look" 2>/dev/null || true; fi
  if [ -s "$look" ]; then kind=image/jpeg; shot=$look; else kind=image/png; shot=$work/look.png; fi
  if [ "$(wc -c < "$shot")" -le 3145728 ]; then
    [ "$shown" -lt "$height" ] && printf 'SHOWN top %s of %s\n' $((shown * 2)) $((height * 2))
    # Named plainly: the line that announces a picture takes only letters, digits, dots, dashes and underscores.
    printf '\n=====IMAGE %s %s=====\n' "article.${kind#image/}" "$kind"
    base64 -w 0 "$shot"
  fi
fi

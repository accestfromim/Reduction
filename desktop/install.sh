#!/usr/bin/env bash
# Sets Reduction up as an application on this computer:
#   - builds it
#   - fetches the audio tools it uses for the loudspeakers and the microphone, into its own folder
#   - puts an icon on the Windows desktop, and starts it at login
# Run again after updating the code. `install.sh --remove` takes the icon and the login start away.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$HOME/.local/share/reduction"
PS=/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe
# The library the pet draws its panel with: Microsoft's WebView2 SDK, from NuGet. Its three files go beside
# the pet; the engine itself is part of Windows.
WEBVIEW2_VERSION=1.0.4314.35
WEBVIEW2_SHA256=90778deb1b77e36292828b4b5833c9255c27533f40e0ddd55891ae718944edc4
win() { "$PS" -NoProfile -NonInteractive -Command "$1" | tr -d '\r'; }

if [ "${1:-}" = "--remove" ]; then
  win 'Remove-Item -ErrorAction SilentlyContinue "$([Environment]::GetFolderPath("Startup"))\Reduction.lnk","$([Environment]::GetFolderPath("Desktop"))\Reduction.lnk"; Remove-Item -Recurse -ErrorAction SilentlyContinue "$env:LOCALAPPDATA\Reduction"'
  echo "已移除桌面图标和开机自启。数据仍在 $DATA"
  exit 0
fi

echo "== 构建"
. "$HERE/../tools/env.sh"
( cd "$ROOT" && $GRADLE -q :desktop:installDist )
# A service still running from before the build would go on with half of the old program and half of
# the new one, and fail without a word; it is stopped here and started again at the end.
pkill -f 'com.mindtravel.reduction.desktop.MainK[t]' || true

echo "== 音频工具"
AUDIO="$DATA/audio"
if command -v pacat >/dev/null 2>&1; then
  echo "系统里已有 pacat"
elif [ -x "$AUDIO/usr/bin/pacat" ] && ! LD_LIBRARY_PATH="$AUDIO/usr/lib/x86_64-linux-gnu:$AUDIO/usr/lib/x86_64-linux-gnu/pulseaudio" ldd "$AUDIO/usr/bin/pacat" | grep -q "not found"; then
  echo "已在 $AUDIO"
else
  # From the system's own package source, unpacked into this folder: nothing is installed system-wide.
  mkdir -p "$AUDIO/debs" && cd "$AUDIO/debs"
  apt-get download pulseaudio-utils libpulse0 libsndfile1 libasyncns0 libflac14 libmp3lame0 libmpg123-0t64 >/dev/null
  for deb in *.deb; do dpkg -x "$deb" "$AUDIO"; done
  missing="$(LD_LIBRARY_PATH="$AUDIO/usr/lib/x86_64-linux-gnu:$AUDIO/usr/lib/x86_64-linux-gnu/pulseaudio" ldd "$AUDIO/usr/bin/pacat" | grep "not found" || true)"
  [ -z "$missing" ] && echo "已取到 $AUDIO" || { echo "音频工具还缺这些库，声音会由浏览器窗口代劳："; echo "$missing"; }
fi

echo "== Windows 图标与开机自启"
[ -x "$PS" ] || { echo "这不是带 Windows 的环境，跳过。用 $HERE/start.sh 启动。"; exit 0; }
APPDIR_WIN="$(win '$d="$env:LOCALAPPDATA\Reduction"; New-Item -ItemType Directory -Force $d | Out-Null; $d')"
APPDIR="$(wslpath "$APPDIR_WIN")"
cp "$HERE/reduction.ico" "$APPDIR/reduction.ico"
SDK="$DATA/webview2/$WEBVIEW2_VERSION.nupkg"
if ! echo "$WEBVIEW2_SHA256  $SDK" | sha256sum -c --status 2>/dev/null; then
  mkdir -p "$DATA/webview2"
  curl -fsSL -m 300 -o "$SDK" "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$WEBVIEW2_VERSION/microsoft.web.webview2.$WEBVIEW2_VERSION.nupkg"
  echo "$WEBVIEW2_SHA256  $SDK" | sha256sum -c --status || { echo "WebView2 SDK 下载不完整或校验不符：$SDK"; exit 1; }
fi
# The pet is a small program of its own, built here with the compiler that comes with Windows.
"$PS" -NoProfile -NonInteractive -Command 'Get-Process ReductionPet -ErrorAction SilentlyContinue | Stop-Process -Force' || true
rm -f "$APPDIR/pet.ps1" "$APPDIR/pet-fps.txt" "$APPDIR/pet.json" || true
rm -rf "$APPDIR/window" || true
for part in lib/net462/Microsoft.Web.WebView2.Core.dll lib/net462/Microsoft.Web.WebView2.Wpf.dll runtimes/win-x64/native/WebView2Loader.dll; do
  unzip -p "$SDK" "$part" > "$APPDIR/$(basename "$part")"
done
cp "$HERE/pet.cs" "$APPDIR/pet.cs"
FRAMEWORK='C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
( cd "$APPDIR" && "$(wslpath "$FRAMEWORK")/csc.exe" /nologo /target:winexe /platform:x64 /optimize+ /out:ReductionPet.exe /win32icon:reduction.ico \
    "/r:$FRAMEWORK\\WPF\\PresentationCore.dll" "/r:$FRAMEWORK\\WPF\\PresentationFramework.dll" "/r:$FRAMEWORK\\WPF\\WindowsBase.dll" \
    "/r:$FRAMEWORK\\System.Xaml.dll" "/r:$FRAMEWORK\\System.Web.Extensions.dll" "/r:$FRAMEWORK\\System.Drawing.dll" \
    /r:Microsoft.Web.WebView2.Core.dll /r:Microsoft.Web.WebView2.Wpf.dll pet.cs ) | iconv -f GBK -t UTF-8 -c | tr -d '\r' || true
# A pet that did not compile must not pass for the one already there.
if [ "$APPDIR/ReductionPet.exe" -nt "$APPDIR/pet.cs" ]; then rm -f "$APPDIR/pet.cs"; else echo "桌宠没有编译出来，仍是上一次的：见上面的输出"; exit 1; fi
# The launcher: starts the service inside this Linux system with no console window and puts the pet on the
# desktop. Asked for the panel, it has the pet open it.
cat > "$APPDIR/Reduction.vbs" <<VBS
Set shell = CreateObject("WScript.Shell")
shell.Run "wsl.exe -d ${WSL_DISTRO_NAME:-Ubuntu} -- $HERE/start.sh", 0, False
panel = ""
If WScript.Arguments.Count > 0 Then panel = " --panel 1"
shell.Run """$APPDIR_WIN\\ReductionPet.exe"" --url http://localhost:8780" & panel, 1, False
VBS
rm -f "$HOME/.config/reduction/window.env"
win '
$app = "$env:LOCALAPPDATA\Reduction"
$shell = New-Object -ComObject WScript.Shell
foreach ($place in @(@("Desktop", "panel"), @("Startup", ""))) {
  $link = $shell.CreateShortcut("$([Environment]::GetFolderPath($place[0]))\Reduction.lnk")
  $link.TargetPath = "$env:WINDIR\System32\wscript.exe"
  $link.Arguments = "`"$app\Reduction.vbs`" " + $place[1]
  $link.IconLocation = "$app\reduction.ico"
  $link.WorkingDirectory = $app
  $link.Description = "Reduction"
  $link.Save()
}
'
# Started now, service and pet, so that what runs is what was just built.
( cd "$APPDIR" && /mnt/c/Windows/System32/wscript.exe Reduction.vbs ) || true
echo "桌面图标：$(win '[Environment]::GetFolderPath("Desktop")')\\Reduction.lnk"
echo "开机自启：$(win '[Environment]::GetFolderPath("Startup")')\\Reduction.lnk"
echo "装好了，桌宠已经启动。以后登录 Windows 它会自己出现；双击桌面上的 Reduction 会同时打开面板。"

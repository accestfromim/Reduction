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
win() { "$PS" -NoProfile -NonInteractive -Command "$1" | tr -d '\r'; }

if [ "${1:-}" = "--remove" ]; then
  win 'Remove-Item -ErrorAction SilentlyContinue "$([Environment]::GetFolderPath("Startup"))\Reduction.lnk","$([Environment]::GetFolderPath("Desktop"))\Reduction.lnk"; Remove-Item -Recurse -ErrorAction SilentlyContinue "$env:LOCALAPPDATA\Reduction"'
  echo "已移除桌面图标和开机自启。数据仍在 $DATA"
  exit 0
fi

echo "== 构建"
. "$HERE/../tools/env.sh"
( cd "$ROOT" && $GRADLE -q :desktop:installDist )

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
BROWSER=""
for candidate in "/mnt/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" "/mnt/c/Program Files/Microsoft/Edge/Application/msedge.exe" "/mnt/c/Program Files/Google/Chrome/Application/chrome.exe"; do
  [ -x "$candidate" ] && { BROWSER="$(wslpath -w "$candidate")"; break; }
done
[ -n "$BROWSER" ] || echo "没有找到 Edge 或 Chrome：窗口开不了，可以在任意浏览器里打开 http://localhost:8780"
# The pet is a small program of its own, built here with the compiler that comes with Windows.
"$PS" -NoProfile -NonInteractive -Command 'Get-Process ReductionPet -ErrorAction SilentlyContinue | Stop-Process -Force' || true
rm -f "$APPDIR/pet.ps1" "$APPDIR/pet-fps.txt" "$APPDIR/pet.json" || true
cp "$HERE/pet.cs" "$APPDIR/pet.cs"
FRAMEWORK='C:\Windows\Microsoft.NET\Framework64\v4.0.30319'
( cd "$APPDIR" && "$(wslpath "$FRAMEWORK")/csc.exe" /nologo /target:winexe /platform:x64 /optimize+ /out:ReductionPet.exe /win32icon:reduction.ico \
    "/r:$FRAMEWORK\\WPF\\PresentationCore.dll" "/r:$FRAMEWORK\\WPF\\PresentationFramework.dll" "/r:$FRAMEWORK\\WPF\\WindowsBase.dll" \
    "/r:$FRAMEWORK\\System.Xaml.dll" "/r:$FRAMEWORK\\System.Web.Extensions.dll" pet.cs ) | tr -d '\r' || true
[ -f "$APPDIR/ReductionPet.exe" ] && rm -f "$APPDIR/pet.cs" || echo "桌宠没有编译出来：见上面的输出"
# The launcher: starts the service inside this Linux system with no console window, puts the pet on the
# desktop, and, when asked for the panel, opens it once the service answers. The panel is a window with
# no address bar or tabs and a profile of its own, so it is its own entry on the taskbar.
cat > "$APPDIR/Reduction.vbs" <<VBS
Set shell = CreateObject("WScript.Shell")
url = "http://localhost:8780"
browser = "$BROWSER"
profile = "$APPDIR_WIN\\window"
shell.Run "wsl.exe -d ${WSL_DISTRO_NAME:-Ubuntu} -- $HERE/start.sh", 0, False
shell.Run """$APPDIR_WIN\\ReductionPet.exe"" --url " & url & " --browser """ & browser & """ --profile """ & profile & """", 1, False
If WScript.Arguments.Count = 0 Then WScript.Quit
For attempt = 1 To 80
  If shell.Run("curl.exe -s -m 2 --noproxy * -o NUL " & url & "/icon.png", 0, True) = 0 Then Exit For
  WScript.Sleep 500
Next
shell.Run """" & browser & """ --app=" & url & " ""--proxy-bypass-list=localhost;127.0.0.1"" ""--user-data-dir=" & profile & """ --window-size=420,780 --no-first-run", 1, False
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
echo "桌面图标：$(win '[Environment]::GetFolderPath("Desktop")')\\Reduction.lnk"
echo "开机自启：$(win '[Environment]::GetFolderPath("Startup")')\\Reduction.lnk"
echo "装好了。登录 Windows 后桌宠会自己出现；双击桌面上的 Reduction 会同时打开面板。"

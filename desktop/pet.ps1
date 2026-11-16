# Reduction's desktop pet: a small figure that lives on the desktop, always on top.
#
#   it breathes while idle, opens its arms while listening, circles while thinking, pulses while speaking
#   hold it and speak, let go to send          (the computer's own microphone)
#   click it to open or close the full panel   (cards, history, settings)
#   drag it anywhere; right-click for the menu
#
# It only shows what the service at $Url is doing and passes on what is done to it.
#
# -Snapshot <png> draws the pet in the pose named by -Pose into a picture and leaves, showing nothing:
# that is how its looks are checked without looking at anybody's screen.
param([string]$Url = "http://localhost:8780", [string]$Browser = "", [string]$Profile = "", [string]$Snapshot = "", [string]$Pose = "idle", [double]$At = 3.0)

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Windows.Forms

$created = $false
$mutex = New-Object System.Threading.Mutex($true, $(if ($Snapshot) { "ReductionPetSnapshot" } else { "ReductionPet" }), [ref]$created)
if (-not $created) { exit }

# Asked by number: under the name "localhost" Windows tries an address the service does not listen on, and waits.
$Api = $Url -replace "localhost", "127.0.0.1"
$home_ = Join-Path $env:LOCALAPPDATA "Reduction"
$placeFile = Join-Path $home_ "pet.json"

[xml]$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        Title="Reduction Pet" Width="320" Height="310" WindowStyle="None" AllowsTransparency="True"
        Background="Transparent" Topmost="True" ShowInTaskbar="False" ResizeMode="NoResize">
  <Grid>
    <Border Name="Bubble" Visibility="Collapsed" VerticalAlignment="Top" HorizontalAlignment="Center" Margin="6,0,6,0"
            Background="#F0101010" BorderBrush="#40FFFFFF" BorderThickness="1" CornerRadius="14" Padding="12,8" MaxWidth="288">
      <TextBlock Name="BubbleText" Foreground="#F2F2F2" FontSize="13" TextWrapping="Wrap" MaxHeight="96" TextTrimming="CharacterEllipsis"
                 FontFamily="Microsoft YaHei UI, Segoe UI"/>
    </Border>
    <Canvas Name="Pet" Width="200" Height="200" VerticalAlignment="Bottom" HorizontalAlignment="Center" Cursor="Hand">
      <!-- What falls in: a disc of dust seen almost edge-on, turning; the far half is behind the body. -->
      <Canvas Name="DiscBack" Width="200" Height="200"/>
      <Ellipse Name="Glow" Canvas.Left="40" Canvas.Top="40" Width="120" Height="120" Opacity="0">
        <Ellipse.Fill><RadialGradientBrush><GradientStop Color="#77FFFFFF" Offset="0.5"/><GradientStop Color="#00FFFFFF" Offset="1"/></RadialGradientBrush></Ellipse.Fill>
      </Ellipse>
      <!-- Light bent right round the edge. -->
      <Ellipse Name="Halo" Canvas.Left="49" Canvas.Top="49" Width="102" Height="102" Stroke="#55FFFFFF" StrokeThickness="1.2"/>
      <Ellipse Name="Body" Canvas.Left="52" Canvas.Top="52" Width="96" Height="96" Fill="#FF080808" Stroke="#38FFFFFF" StrokeThickness="1"/>
      <Canvas Name="Face" Canvas.Left="52" Canvas.Top="52" Width="96" Height="96">
        <Canvas.Clip><EllipseGeometry Center="48,48" RadiusX="47" RadiusY="47"/></Canvas.Clip>
        <Ellipse Name="SideL" Width="36" Height="36" Fill="#F2F2F2"/>
        <Ellipse Name="SideR" Width="36" Height="36" Fill="#F2F2F2"/>
        <Ellipse Name="Gap" Width="46.6" Height="46.6" Fill="#FF080808"/>
        <Ellipse Name="Core" Width="36" Height="36" Fill="#F2F2F2"/>
      </Canvas>
      <!-- The near half of the disc passes in front. -->
      <Canvas Name="DiscFront" Width="200" Height="200"/>
    </Canvas>
  </Grid>
</Window>
"@
$window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $xaml))
$names = "Bubble","BubbleText","Pet","Glow","Halo","Body","Face","SideL","SideR","Gap","Core","DiscBack","DiscFront"
$ui = @{}
foreach ($name in $names) { $ui[$name] = $window.FindName($name) }

# Where it sat last time; otherwise the bottom right corner, above the taskbar.
$area = [System.Windows.SystemParameters]::WorkArea
$window.Left = $area.Right - 330
$window.Top = $area.Bottom - 320
if (Test-Path $placeFile) {
  try {
    $place = Get-Content $placeFile -Raw | ConvertFrom-Json
    if ($place.left -ge $area.Left - 200 -and $place.left -le $area.Right - 100 -and $place.top -ge $area.Top - 100 -and $place.top -le $area.Bottom - 100) {
      $window.Left = $place.left; $window.Top = $place.top
    }
  } catch {}
}

# ── talking to the service ───────────────────────────────────────────────
function Ask([string]$path, [string]$method = "GET") {
  try {
    $request = [System.Net.HttpWebRequest]::Create($Api + $path)
    $request.Proxy = $null
    $request.Timeout = 600
    $request.Method = $method
    if ($method -eq "POST") { $request.Headers.Add("X-Reduction", "1"); $request.ContentLength = 0 }
    $response = $request.GetResponse()
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $text = $reader.ReadToEnd()
    $response.Close()
    return $text | ConvertFrom-Json
  } catch { return $null }
}

function PanelWindow { Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -eq "Reduction" -and $_.ProcessName -match "msedge|chrome" } | Select-Object -First 1 }

function TogglePanel {
  $open = PanelWindow
  if ($open) { [void]$open.CloseMainWindow(); return }
  if (-not $Browser) { [void][System.Windows.MessageBox]::Show("没有找到可以开面板的程序。可以在浏览器里打开 $Url"); return }
  # Beside the pet, on whichever side there is room.
  $x = [int]($window.Left - 410); if ($x -lt $area.Left) { $x = [int]($window.Left + 330) }
  $y = [int][Math]::Max($area.Top, [Math]::Min($window.Top - 560, $area.Bottom - 800))
  Start-Process $Browser -ArgumentList "--app=$Url", "--proxy-bypass-list=localhost;127.0.0.1", "`"--user-data-dir=$Profile`"", "--window-size=420,780", "--window-position=$x,$y", "--no-first-run"
}

# ── how it looks ─────────────────────────────────────────────────────────
$script:state = "asleep"
$script:shown = "asleep"
$script:lineId = -1
$script:bubbleUntil = [DateTime]::MinValue
$script:t = 0.0
$script:blend = 0.0          # 0..1 as it settles into a new state

function Place($shape, [double]$cx, [double]$cy, [double]$scaleX = 1.0, [double]$scaleY = 1.0) {
  $w = $shape.Width * $scaleX; $h = $shape.Height * $scaleY
  [System.Windows.Controls.Canvas]::SetLeft($shape, $cx - $w / 2)
  [System.Windows.Controls.Canvas]::SetTop($shape, $cy - $h / 2)
  $shape.RenderTransform = New-Object System.Windows.Media.ScaleTransform($scaleX, $scaleY)
}

function Say([string]$text, [double]$seconds = 0) {
  if (-not $text) { return }
  if ($text.Length -gt 90) { $text = $text.Substring(0, 88) + "…" }
  $ui.BubbleText.Text = $text
  $ui.Bubble.Visibility = "Visible"
  if ($seconds -le 0) { $seconds = [Math]::Min(12, [Math]::Max(3.5, $text.Length * 0.16)) }
  $script:bubbleUntil = [DateTime]::Now.AddSeconds($seconds)
}

# The disc is made of grains, each on its own orbit: the nearer the centre, the faster. Seen almost edge-on,
# an orbit is a flat ellipse; a grain is drawn behind the body on the far side and in front on the near side.
# Grains slowly spiral inward and are born again at the rim, which is what makes the disc look like it is feeding.
$rand = New-Object System.Random
$grains = @()
for ($i = 0; $i -lt 72; $i++) {
  $dot = New-Object System.Windows.Shapes.Ellipse
  $dot.Fill = [System.Windows.Media.Brushes]::White
  $grains += [pscustomobject]@{ Dot = $dot; R = 52 + $rand.NextDouble() * 44; A = $rand.NextDouble() * 6.283; Size = 1.2 + $rand.NextDouble() * 2.2; Front = $null }
}
$script:spinFrom = -10.0     # when the wings last went round once, for fun

$frame = New-Object System.Windows.Threading.DispatcherTimer
$frame.Interval = [TimeSpan]::FromMilliseconds(33)
$step = {
  $script:t += 0.033
  $t = $script:t
  if ($script:shown -ne $script:state) { $script:shown = $script:state; $script:blend = 0.0 }
  $script:blend = [Math]::Min(1.0, $script:blend + 0.08)
  $k = $script:blend

  # The figure is the logo, to scale: three circles of radius 82, the outer two centred 59 to either side and
  # hidden within 106 of the middle. Here the radius is 18, so the offset is 12.95 and the ring 23.3.
  $spread = 12.95; $lift = 0.0; $core = 1.0; $squash = 1.0; $glow = 0.0; $bob = [Math]::Sin($t * 1.6) * 2.0; $dim = 1.0
  $lx = -1.0; $ly = 0.0; $rx = 1.0; $ry = 0.0
  switch ($script:shown) {
    "idle" {
      $spread = 12.95 + [Math]::Sin($t * 1.6) * 0.6
      # A blink now and then: the core flattens for a moment.
      $phase = $t % 5.2
      if ($phase -lt 0.16) { $squash = 1.0 - [Math]::Sin($phase / 0.16 * [Math]::PI) * 0.85 }
      # And now and then the two wings swing once right round the core.
      if ($t - $script:spinFrom -gt 9.0) { $script:spinFrom = $t }
      $turn = ($t - $script:spinFrom) / 1.3
      if ($turn -lt 1.0) {
        $a = (1 - [Math]::Cos($turn * [Math]::PI)) * [Math]::PI      # eases in and out over one full turn
        $lx = -[Math]::Cos($a); $ly = -[Math]::Sin($a) * 0.6; $rx = [Math]::Cos($a); $ry = [Math]::Sin($a) * 0.6
        $spread = 12.95 + [Math]::Sin($turn * [Math]::PI) * 2.0
      }
    }
    "listening" {
      $spread = 12.95 + $k * (4.0 + [Math]::Sin($t * 7.0) * 1.0)
      $core = 1.0 + $k * 0.06
      $glow = $k * (0.55 + 0.3 * [Math]::Sin($t * 7.0))
      $bob = 0.0
    }
    "thinking" {
      # The two arcs go round the core.
      $a = $t * 3.2
      $lx = -[Math]::Cos($a); $ly = -[Math]::Sin($a) * 0.55; $rx = [Math]::Cos($a); $ry = [Math]::Sin($a) * 0.55
      $spread = 14.0; $core = 0.94
    }
    "speaking" {
      $voice = [Math]::Abs([Math]::Sin($t * 9.0)) * 0.6 + [Math]::Abs([Math]::Sin($t * 5.3 + 1.0)) * 0.4
      $core = 1.0 + $k * 0.10 * $voice
      $spread = 12.95 + $k * 2.0 * $voice
      $glow = $k * 0.25 * $voice
    }
    "asleep" { $dim = 0.45; $squash = 0.18; $bob = [Math]::Sin($t * 0.8) * 1.2; $spread = 12.0 }
  }
  $cx = 48.0; $cy = 48.0
  Place $ui.SideL ($cx + $lx * $spread) ($cy + $ly * $spread)
  Place $ui.SideR ($cx + $rx * $spread) ($cy + $ry * $spread)
  Place $ui.Gap $cx $cy $core $core
  Place $ui.Core $cx $cy $core ($core * $squash)
  $ui.Face.Opacity = $dim
  $ui.Glow.Opacity = $glow

  # The disc: quicker and brighter the busier it is, barely there when asleep.
  $speed = 1.0; $bright = 0.55; $pull = 3.0
  switch ($script:shown) {
    "listening" { $speed = 0.5; $bright = 0.9; $pull = -6.0 }     # held back, widening: it is taking in, not eating
    "thinking" { $speed = 2.6; $bright = 0.85; $pull = 9.0 }
    "speaking" { $speed = 1.5; $bright = 0.75; $pull = 4.0 }
    "asleep" { $speed = 0.25; $bright = 0.18; $pull = 1.0 }
  }
  $tilt = 0.30 + 0.05 * [Math]::Sin($t * 0.5)                      # the disc rocks a little
  $lean = 0.22 * [Math]::Sin($t * 0.31)                            # and its axis sways
  $cosL = [Math]::Cos($lean); $sinL = [Math]::Sin($lean)
  foreach ($g in $grains) {
    $g.A += 0.033 * $speed * 190.0 / [Math]::Pow($g.R, 1.35)
    $g.R -= 0.033 * $pull
    if ($g.R -lt 50) { $g.R = 92 + $rand.NextDouble() * 6; $g.A = $rand.NextDouble() * 6.283 }
    if ($g.R -gt 99) { $g.R = 52 + $rand.NextDouble() * 6 }
    $x = [Math]::Cos($g.A) * $g.R; $y = [Math]::Sin($g.A) * $g.R * $tilt
    # Near the body the path is pulled round it, as light is: the closer, the more it bulges away from the centre line.
    $bend = 1.0 + 0.55 * [Math]::Exp(-($g.R - 50) / 14.0)
    $y *= $bend
    $px = 100 + $x * $cosL - $y * $sinL; $py = 100 + $bob + $x * $sinL + $y * $cosL
    $front = [Math]::Sin($g.A) -gt 0
    if ($g.Front -ne $front) {
      if ($g.Front -ne $null) { if ($g.Front) { $ui.DiscFront.Children.Remove($g.Dot) } else { $ui.DiscBack.Children.Remove($g.Dot) } }
      if ($front) { [void]$ui.DiscFront.Children.Add($g.Dot) } else { [void]$ui.DiscBack.Children.Add($g.Dot) }
      $g.Front = $front
    }
    $near = ($g.R - 50) / 46.0                                      # 0 at the body, 1 at the rim
    $size = $g.Size * (0.7 + 0.5 * (1 - $near)) * $(if ($front) { 1.15 } else { 0.85 })
    $g.Dot.Width = $size * (1.0 + 1.6 * (1 - $near)); $g.Dot.Height = $size    # stretched along its path as it speeds up
    $g.Dot.Opacity = $bright * (0.25 + 0.75 * (1 - $near)) * $(if ($front) { 1.0 } else { 0.6 })
    [System.Windows.Controls.Canvas]::SetLeft($g.Dot, $px - $g.Dot.Width / 2)
    [System.Windows.Controls.Canvas]::SetTop($g.Dot, $py - $g.Dot.Height / 2)
  }
  $ui.Halo.Opacity = 0.35 + 0.45 * $bright + 0.15 * [Math]::Sin($t * 2.3)
  [System.Windows.Controls.Canvas]::SetTop($ui.Halo, 49 + $bob)
  [System.Windows.Controls.Canvas]::SetTop($ui.Body, 52 + $bob)
  [System.Windows.Controls.Canvas]::SetTop($ui.Face, 52 + $bob)
  if ($ui.Bubble.Visibility -eq "Visible" -and [DateTime]::Now -gt $script:bubbleUntil -and $script:state -ne "listening") { $ui.Bubble.Visibility = "Collapsed" }
}
$frame.Add_Tick($step)

$poll = New-Object System.Windows.Threading.DispatcherTimer
$poll.Interval = [TimeSpan]::FromMilliseconds(350)
$poll.Add_Tick({
  if ($script:holding) { return }
  $now = Ask "/pet"
  if (-not $now) { $script:state = "asleep"; return }
  $script:state = $now.state
  if ($script:lineId -lt 0) { $script:lineId = $now.lineId }       # nothing old is repeated when the pet starts
  elseif ($now.lineId -ne $script:lineId) { $script:lineId = $now.lineId; Say $now.line }
})

# ── what is done to it ───────────────────────────────────────────────────
$script:down = $false; $script:moved = $false; $script:holding = $false
$script:downAt = [DateTime]::MinValue; $script:grab = $null

$hold = New-Object System.Windows.Threading.DispatcherTimer
$hold.Interval = [TimeSpan]::FromMilliseconds(320)
$hold.Add_Tick({
  $hold.Stop()
  if ($script:down -and -not $script:moved) {
    $answer = Ask "/pet/talk?action=start" "POST"
    if ($answer -and $answer.state -eq "listening") { $script:holding = $true; $script:state = "listening"; Say "在听…松开发送" 60 }
    elseif ($answer -and -not $answer.ready) { Say "先点我一下，在面板的设置里填 API Key" }
    else { Say "用不了麦克风" }
  }
})

$ui.Pet.Add_MouseLeftButtonDown({
  param($sender, $e)
  $script:down = $true; $script:moved = $false; $script:holding = $false
  $script:grab = $e.GetPosition($window)
  [void]$ui.Pet.CaptureMouse()
  $hold.Start()
})
$ui.Pet.Add_MouseMove({
  param($sender, $e)
  if (-not $script:down -or $script:holding) { return }
  $at = $e.GetPosition($window)
  if (-not $script:moved -and ([Math]::Abs($at.X - $script:grab.X) + [Math]::Abs($at.Y - $script:grab.Y)) -lt 5) { return }
  $script:moved = $true
  $hold.Stop()
  $window.Left += $at.X - $script:grab.X
  $window.Top += $at.Y - $script:grab.Y
})
$ui.Pet.Add_MouseLeftButtonUp({
  $hold.Stop()
  $ui.Pet.ReleaseMouseCapture()
  if (-not $script:down) { return }
  $script:down = $false
  if ($script:holding) {
    $script:holding = $false
    $ui.Bubble.Visibility = "Collapsed"
    [void](Ask "/pet/talk?action=stop" "POST")
  } elseif ($script:moved) {
    @{ left = $window.Left; top = $window.Top } | ConvertTo-Json | Set-Content -Encoding UTF8 $placeFile
  } else {
    TogglePanel
  }
})

$menu = New-Object System.Windows.Controls.ContextMenu
foreach ($entry in @(@("打开或关闭面板", { TogglePanel }), @("让它安静", { [void](Ask "/bridge/stopSpeaking" "POST") }), @("收起桌宠", { $window.Close() }))) {
  $item = New-Object System.Windows.Controls.MenuItem
  $item.Header = $entry[0]
  $item.Add_Click($entry[1])
  [void]$menu.Items.Add($item)
}
$ui.Pet.ContextMenu = $menu

if ($Snapshot) {
  $script:state = $Pose
  for ($i = 0; $i -lt [int]($At / 0.033); $i++) { & $step }
  $ui.Pet.Background = New-Object System.Windows.Media.SolidColorBrush ([System.Windows.Media.Color]::FromRgb(58, 74, 92))
  $size = New-Object System.Windows.Size 200, 200
  $ui.Pet.Measure($size); $ui.Pet.Arrange((New-Object System.Windows.Rect $size)); $ui.Pet.UpdateLayout()
  $picture = New-Object System.Windows.Media.Imaging.RenderTargetBitmap 600, 600, 288, 288, ([System.Windows.Media.PixelFormats]::Pbgra32)
  $picture.Render($ui.Pet)
  $encoder = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
  $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($picture))
  $file = [System.IO.File]::Create($Snapshot); $encoder.Save($file); $file.Close()
  exit
}

$window.Add_Closed({ $frame.Stop(); $poll.Stop() })
$frame.Start(); $poll.Start()
[void]$window.ShowDialog()
$mutex.ReleaseMutex()

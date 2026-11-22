[CmdletBinding()]
param(
    [string]$InstallDir = "$env:LOCALAPPDATA\Reduction",
    [ValidateRange(1,65535)][int]$Port = 8780,
    [switch]$Panel,
    [switch]$BackendOnly,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath (Join-Path $InstallDir 'windows-runtime.json') -Raw | ConvertFrom-Json
if ($config.ProjectRoot) { $env:REDUCTION_ROOT = $config.ProjectRoot }
$java = (Get-Item -LiteralPath $config.Java).FullName
$lib = (Get-Item -LiteralPath $config.Library).FullName
$dataHome = (Get-Item -LiteralPath $config.DataHome).FullName
$class = 'com.mindtravel.reduction.desktop.MainKt'
$stateDir = Join-Path $InstallDir 'backend-logs'
[IO.Directory]::CreateDirectory($stateDir) | Out-Null
function Find-Backend {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Where-Object LocalAddress -in @('127.0.0.1','0.0.0.0','::','::1') | Select-Object -First 1
    if (!$listener) { return }
    $server = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (!$server -or $server.ExecutablePath -ne $java -or
        !$server.CommandLine.Contains("-Duser.home=$dataHome") -or
        !$server.CommandLine.Contains("$class $Port") -or !$server.CommandLine.Contains("$lib\*")) {
        throw "Port $Port is already in use by a different backend (PID $($listener.OwningProcess))."
    }
    $server
}
$lock = New-Object Threading.Mutex($false, "Local\ReductionNativeBackend-$Port")
$held = $false
try {
    try { $held = $lock.WaitOne(60000) } catch [Threading.AbandonedMutexException] { $held = $true }
    if (!$held) { throw 'Timed out waiting for the desktop launcher.' }
    $server = Find-Backend
    if ($Stop) {
        if ($server) {
            $process = Get-Process -Id $server.ProcessId
            $process.Kill()
            if (!$process.WaitForExit(15000)) { throw 'The backend did not stop.' }
        }
        return
    }
    if (!$server) {
        $arguments = @(('"-Duser.home=' + $dataHome + '"'), '-cp', ('"' + $lib + '\*"'), $class, "$Port")
        $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
        $errFile = Join-Path $stateDir "$stamp.err.log"
        $process = Start-Process -FilePath $java -ArgumentList ($arguments -join ' ') -WorkingDirectory $InstallDir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $stateDir "$stamp.out.log") -RedirectStandardError $errFile
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            if ($process.HasExited) { throw "Windows backend exited ($($process.ExitCode)). See $errFile" }
            $server = Find-Backend
            if ($server) { break }
            Start-Sleep -Milliseconds 250
        }
        if (!$server) { $process.Kill(); throw "Backend startup timed out. See $errFile" }
    }
    $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/index.html" -TimeoutSec 10
    if ($page.StatusCode -ne 200 -or !$page.Content.Contains('settingsSheet')) { throw 'Reduction page did not load.' }
    if (!$BackendOnly) {
        $argsForPet = @('--url', "http://localhost:$Port")
        if ($Panel) { $argsForPet += '--panel','1' }
        Start-Process -FilePath (Join-Path $InstallDir 'ReductionPet.exe') -ArgumentList $argsForPet -WorkingDirectory $InstallDir
    }
    Write-Output "Windows backend PID $($server.ProcessId) ready on $Port"
} finally {
    if ($held) { $lock.ReleaseMutex() }
    $lock.Dispose()
}

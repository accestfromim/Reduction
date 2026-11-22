[CmdletBinding()]
param(
    [string]$InstallDir = "$env:LOCALAPPDATA\Reduction",
    [string]$Library,
    [string]$ProjectRoot,
    [string]$Java,
    [string]$DataHome = $env:USERPROFILE,
    [switch]$Rollback,
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$launcher = Join-Path $InstallDir 'Reduction.vbs'
$backup = Join-Path $InstallDir 'Reduction.before-windows.vbs'
if ($Rollback) {
    if (!(Test-Path -LiteralPath $backup -PathType Leaf)) { throw "Missing original launcher $backup" }
    $temp = Join-Path $InstallDir ("rollback-" + [guid]::NewGuid() + '.vbs')
    Copy-Item -LiteralPath $backup -Destination $temp
    [IO.File]::Replace($temp, $launcher, "$temp.previous")
    Remove-Item -LiteralPath "$temp.previous"
    return
}
function Native-Path([string]$Path, [bool]$Directory) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'Supply a Windows filesystem path.' }
    $item = Get-Item -LiteralPath $Path
    if ($item.PSIsContainer -ne $Directory -or $item.FullName -notmatch '^[a-zA-Z]:\\') {
        throw "Expected an existing local Windows path: $Path"
    }
    $item.FullName
}
$lib = Native-Path $Library $true
$runtime = Native-Path $Java $false
$profileDir = Native-Path $DataHome $true
if (!(Test-Path -LiteralPath (Join-Path $lib 'desktop.jar'))) { throw "Missing desktop.jar in $lib" }
if (!(Test-Path -LiteralPath (Join-Path $InstallDir 'ReductionPet.exe'))) { throw 'Install the Windows desktop pet first.' }
$sourceScript = Join-Path $PSScriptRoot 'start-windows.ps1'
$startScript = Join-Path $InstallDir 'start-windows.ps1'
if ([IO.Path]::GetFullPath($sourceScript) -ne [IO.Path]::GetFullPath($startScript)) {
    Copy-Item -LiteralPath $sourceScript -Destination $startScript -Force
}
$config = @{ Java = $runtime; Library = $lib; DataHome = $profileDir }
if ($ProjectRoot) { $config.ProjectRoot = Native-Path $ProjectRoot $true }
$config |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $InstallDir 'windows-runtime.json') -Encoding UTF8
if ($PrepareOnly) { return }
$proposal = Join-Path $InstallDir ("launcher-" + [guid]::NewGuid() + '.vbs')
@(
    'Set shell = CreateObject("WScript.Shell")'
    'Set fs = CreateObject("Scripting.FileSystemObject")'
    'app = fs.GetParentFolderName(WScript.ScriptFullName)'
    'cmd = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -File """ & app & "\start-windows.ps1"" -InstallDir """ & app & """"'
    'If WScript.Arguments.Count > 0 Then cmd = cmd & " -Panel"'
    'shell.Run cmd, 0, False'
) | Set-Content -LiteralPath $proposal -Encoding ascii
if (Test-Path -LiteralPath $launcher) {
    if (!(Test-Path -LiteralPath $backup)) { Copy-Item -LiteralPath $launcher -Destination $backup }
    [IO.File]::Replace($proposal, $launcher, "$proposal.previous")
    Remove-Item -LiteralPath "$proposal.previous"
} else {
    [IO.File]::Move($proposal, $launcher)
}

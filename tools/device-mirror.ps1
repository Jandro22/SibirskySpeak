param(
    [string]$Package = "com.sibirskyspeak",
    [switch]$NoAlwaysOnTop
)

$ErrorActionPreference = "Stop"

$scrcpyCommand = Get-Command scrcpy -ErrorAction SilentlyContinue
$scrcpy = if ($scrcpyCommand) { $scrcpyCommand.Source } else { $null }
if (-not $scrcpy) {
    $scrcpy = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Genymobile.scrcpy_*" `
        -Filter scrcpy.exe -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $scrcpy) { throw "scrcpy is not installed. Run: winget install --exact Genymobile.scrcpy" }

$adb = Join-Path $PSScriptRoot "../.tools/android-sdk/platform-tools/adb.exe"
if (-not (Test-Path -LiteralPath $adb)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    $adb = if ($adbCommand) { $adbCommand.Source } else { $null }
}
if (-not $adb) { throw "ADB was not found." }

$devices = & $adb devices | Select-String "\tdevice$"
if ($devices.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($devices.Count)." }
$serial = ($devices[0].Line -split "\s+")[0]

& $adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

$arguments = @(
    "--serial", $serial,
    "--window-title", "SibirskySpeak Device",
    "--keep-active",
    "--no-audio"
)
if (-not $NoAlwaysOnTop) { $arguments += "--always-on-top" }

Start-Process -FilePath $scrcpy -ArgumentList $arguments

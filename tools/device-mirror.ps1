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

$deviceList = & $adb devices -l | Out-String
if ($deviceList -match "\soffline\s") {
    & $adb reconnect offline | Out-Null
    $deadline = (Get-Date).AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 500
        $deviceList = & $adb devices -l | Out-String
    } while ($deviceList -notmatch "\sdevice\s" -and (Get-Date) -lt $deadline)
}
$devices = $deviceList -split "`r?`n" | Where-Object { $_ -match "\sdevice\s" }
if ($devices.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($devices.Count)." }
$serial = ($devices[0] -split "\s+")[0]

& $adb -s $serial shell settings put global stay_on_while_plugged_in 7 | Out-Null
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null

& $adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

$arguments = @(
    "--serial", $serial,
    "--window-title", "SibirskySpeak Device",
    "--keep-active",
    "--turn-screen-on",
    "--no-audio"
)
if (-not $NoAlwaysOnTop) { $arguments += "--always-on-top" }

Start-Process -FilePath $scrcpy -ArgumentList $arguments

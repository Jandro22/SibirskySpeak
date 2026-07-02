$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$AndroidHome = Join-Path $Root ".tools\android-sdk"
$Adb = Join-Path $AndroidHome "platform-tools\adb.exe"
$Apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"

if (!(Test-Path $Adb)) {
    & (Join-Path $PSScriptRoot "setup-android.ps1")
}

if (!(Test-Path $Apk)) {
    & (Join-Path $PSScriptRoot "build-debug.ps1")
}

$DeviceList = & $Adb devices -l | Out-String
if ($DeviceList -match "\soffline\s") {
    & $Adb reconnect offline | Out-Null
    $Deadline = (Get-Date).AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 500
        $DeviceList = & $Adb devices -l | Out-String
    } while ($DeviceList -notmatch "\sdevice\s" -and (Get-Date) -lt $Deadline)
}
$Devices = @($DeviceList -split "`r?`n" | Where-Object { $_ -match "\sdevice\s" })
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Devices.Count -ne 1) {
    throw "Expected exactly one authorized Android device; found $($Devices.Count)."
}

$Serial = ($Devices[0] -split "\s+")[0]
& $Adb -s $Serial shell settings put global stay_on_while_plugged_in 7 | Out-Null
& $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null

& $Adb -s $Serial install -r $Apk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

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

& $Adb devices
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

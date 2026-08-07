param(
    [string]$Serial,
    [switch]$KeepQaState,
    [switch]$SkipBuild,
    [switch]$NoDrive,
    [switch]$LearnerApp,
    [switch]$InstallLearner,
    [switch]$AllowLearnerStateChanges,
    [int]$UnlockTimeoutSeconds = 120,
    [int]$MaxActions = 80,
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$AndroidHome = Join-Path $Root ".tools\android-sdk"
$Adb = Join-Path $AndroidHome "platform-tools\adb.exe"
$QaApk = Join-Path $Root "app\build\outputs\apk\qa\app-qa.apk"
$Driver = Join-Path $PSScriptRoot "review_phone_episode.py"
$Package = if ($LearnerApp) { "com.sibirskyspeak" } else { "com.sibirskyspeak.qa" }

if (!(Test-Path $Adb)) {
    & (Join-Path $PSScriptRoot "setup-android.ps1")
}

if (-not $LearnerApp -and -not $SkipBuild) {
    Push-Location $Root
    try {
        & (Join-Path $Root "gradlew.bat") assembleQa
        if ($LASTEXITCODE -ne 0) { throw "assembleQa failed." }
    } finally {
        Pop-Location
    }
}

if (-not $LearnerApp -and !(Test-Path $QaApk)) {
    throw "QA APK not found at '$QaApk'. Run without -SkipBuild first."
}

function Get-AuthorizedPhysicalDevices {
    $rows = (& $Adb devices -l 2>&1 | Out-String) -split "`r?`n"
    return @(
        $rows |
            Where-Object { $_ -match "^\S+\s+device(?:\s|$)" } |
            ForEach-Object { ($_ -split "\s+")[0] } |
            Where-Object { $_ -notmatch "^emulator-" }
    )
}

& $Adb start-server 2>&1 | Out-Null
$Devices = @(Get-AuthorizedPhysicalDevices)
if ($Serial) {
    if ($Serial -notin $Devices) {
        throw "Requested physical device '$Serial' is not authorized and online. Connected physical devices: $($Devices -join ', ')."
    }
} elseif ($Devices.Count -eq 1) {
    $Serial = $Devices[0]
} elseif ($Devices.Count -eq 0) {
    throw "No authorized physical Android phone is connected."
} else {
    throw "More than one physical phone is connected; pass -Serial."
}

if (-not $OutputDirectory) {
    $Stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
    $OutputDirectory = Join-Path $Root "build\phone-review\$Stamp"
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

# Driving an episode records real learning evidence. Learner-package review is
# therefore capture-only unless the caller explicitly accepts that mutation.
$EffectiveNoDrive = $NoDrive -or ($LearnerApp -and -not $AllowLearnerStateChanges)
if ($LearnerApp -and -not $NoDrive -and -not $AllowLearnerStateChanges) {
    Write-Warning "Learner-package review is capture-only by default. Pass -AllowLearnerStateChanges to drive its current episode."
}

$PreviousStayOn = (& $Adb -s $Serial shell settings get global stay_on_while_plugged_in 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($PreviousStayOn) -or $PreviousStayOn -eq "null") { $PreviousStayOn = "0" }
$ExitCode = 1

try {
    & $Adb -s $Serial shell settings put global stay_on_while_plugged_in 7 | Out-Null
    & $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null

    if ($LearnerApp) {
        if ($InstallLearner) {
            $InstallArgs = @{ Serial = $Serial }
            & (Join-Path $PSScriptRoot "install-debug.ps1") @InstallArgs
        }
        $InstalledPath = (& $Adb -s $Serial shell pm path $Package 2>$null | Out-String).Trim()
        if (-not $InstalledPath) { throw "The learner app is not installed. Pass -InstallLearner to install it safely." }
    } else {
        & $Adb -s $Serial install -r -t $QaApk
        if ($LASTEXITCODE -ne 0) { throw "Installing the isolated QA APK failed." }
        if (-not $KeepQaState) {
            & $Adb -s $Serial shell pm clear $Package | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Resetting isolated QA state failed." }
        }
        & $Adb -s $Serial shell pm grant $Package android.permission.RECORD_AUDIO 2>$null | Out-Null
        & $Adb -s $Serial shell pm grant $Package android.permission.POST_NOTIFICATIONS 2>$null | Out-Null
    }

    $DriverArgs = @(
        $Driver,
        "--adb", $Adb,
        "--serial", $Serial,
        "--package", $Package,
        "--output", $OutputDirectory,
        "--unlock-timeout", $UnlockTimeoutSeconds,
        "--max-actions", $MaxActions
    )
    if ($EffectiveNoDrive) { $DriverArgs += "--no-drive" }

    & python @DriverArgs
    $ExitCode = $LASTEXITCODE
} finally {
    & $Adb -s $Serial shell settings put global stay_on_while_plugged_in $PreviousStayOn 2>$null | Out-Null
}

Write-Host "Phone review artifacts: $OutputDirectory"
if (Test-Path (Join-Path $OutputDirectory "report.html")) {
    Write-Host "Open: $(Join-Path $OutputDirectory 'report.html')"
}
exit $ExitCode

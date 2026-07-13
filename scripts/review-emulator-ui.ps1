param(
    [string]$AvdName = "Sibirsky_Pixel4a_API35",
    [string]$Serial = "",
    [switch]$ResetApp,
    [switch]$Install,
    [switch]$Headless
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Adb = Join-Path $Root ".tools\android-sdk\platform-tools\adb.exe"
$Apk = Join-Path $Root "app\build\outputs\apk\debug\app-debug.apk"
$Package = "com.sibirskyspeak"
$Output = Join-Path $Root ("build\emulator-review\" + (Get-Date -Format "yyyyMMdd-HHmmss"))

$serial = if ($Serial) { $Serial } else { (& (Join-Path $PSScriptRoot "start-emulator.ps1") -AvdName $AvdName -Visible:(-not $Headless) | Select-Object -Last 1).Trim() }
$packageInstalled = ((& $Adb -s $serial shell pm path $Package 2>$null | Out-String) -match '^package:')
if ($Install -or !$packageInstalled) {
    # An explicit install request is a verification request: never reinstall a
    # stale APK left by an earlier build.
    if ($Install -or !(Test-Path $Apk)) { & (Join-Path $PSScriptRoot "build-debug.ps1") }
    & $Adb -s $serial install -r $Apk | Out-Host
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
if ($ResetApp) { & $Adb -s $serial shell pm clear $Package | Out-Null }

# Keep system permission dialogs out of repeatable review captures. Production
# permission flows remain unchanged; these grants apply only to the emulator.
foreach ($permission in @("android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO")) {
    & $Adb -s $serial shell pm grant $Package $permission 2>$null | Out-Null
}

$launchStarted = Get-Date
& $Adb -s $serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null

# First launch can spend several seconds importing the bundled curriculum. Wait for
# real app semantics instead of taking a screenshot of the splash screen.
# Include both first-run onboarding and post-onboarding surfaces. Matching only
# dashboard/study copy made clean-install reviews falsely time out while the
# rendered onboarding screen was already visible.
$bodyPattern = "Start with a useful first session|Your first session|I know some Russian|Today's Focus|Curriculum mastery|Practice actions completed|Daily goal|Study settings|Reader library|New vocabulary|Make the adjective agree|Choose the right Russian form|Translate this Russian word|onboarding_beginner"
$deadline = (Get-Date).AddSeconds(45)
do {
    Start-Sleep -Seconds 2
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb -s $serial shell uiautomator dump /sdcard/sibirsky-ready.xml 2>$null | Out-Null
        $hierarchy = (& $Adb -s $serial shell cat /sdcard/sibirsky-ready.xml 2>$null | Out-String)
    } finally {
        $ErrorActionPreference = $priorPreference
    }
} while ($hierarchy -notmatch $bodyPattern -and (Get-Date) -lt $deadline)
if ($hierarchy -notmatch $bodyPattern) {
    throw "The app body did not finish rendering within 45 seconds."
}

# Semantics may become available one frame before an entrance animation finishes.
# Require two consecutive identical trees before capturing the rendered surface.
$stableHierarchy = $null
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 500
    & $Adb -s $serial shell uiautomator dump /sdcard/sibirsky-ready.xml 2>$null | Out-Null
    $nextHierarchy = (& $Adb -s $serial shell cat /sdcard/sibirsky-ready.xml 2>$null | Out-String)
    if ($nextHierarchy -eq $stableHierarchy) { break }
    $stableHierarchy = $nextHierarchy
}
# The semantics tree can settle before Compose's entrance animation is painted.
# Keep this explicit buffer so cold-start captures represent the screen a person sees.
Start-Sleep -Seconds 2

New-Item -ItemType Directory -Force -Path $Output | Out-Null
$screenPath = Join-Path $Output "screen.png"
# Capture the settled device framebuffer. Start-Process preserves PNG bytes on
# Windows PowerShell, unlike piping native binary stdout through the object stream.
$candidates = @()
for ($captureIndex = 1; $captureIndex -le 3; $captureIndex++) {
    $candidate = Join-Path $Output ("screen-{0}.png" -f $captureIndex)
    $capture = Start-Process -FilePath $Adb `
        -ArgumentList @("-s", $serial, "exec-out", "screencap", "-p") `
        -RedirectStandardOutput $candidate -Wait -PassThru -NoNewWindow
    if ($capture.ExitCode -eq 0 -and (Test-Path $candidate) -and (Get-Item $candidate).Length -ge 1000) {
        $candidates += Get-Item $candidate
    }
    Start-Sleep -Milliseconds 700
}
if ($candidates.Count -gt 0) {
    # Missing/black surfaces compress dramatically; the largest settled PNG is the
    # most complete framebuffer when the emulator renderer races a capture request.
    $best = $candidates | Sort-Object Length -Descending | Select-Object -First 1
    Move-Item -LiteralPath $best.FullName -Destination $screenPath -Force
    $candidates | Where-Object FullName -ne $best.FullName | Remove-Item -Force
} else {
    & $Adb -s $serial emu screenrecord screenshot $Output | Out-Null
    $captured = Get-ChildItem $Output -Filter "Screenshot_*.png" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$captured) { throw "The emulator did not produce a screenshot." }
    Move-Item -LiteralPath $captured.FullName -Destination $screenPath -Force
}
& $Adb -s $serial shell uiautomator dump /sdcard/sibirsky-review.xml | Out-Null
& $Adb -s $serial pull /sdcard/sibirsky-review.xml (Join-Path $Output "ui.xml") | Out-Null
& $Adb -s $serial shell rm -f /sdcard/sibirsky-ready.xml /sdcard/sibirsky-review.xml | Out-Null

$startupMs = [int]((Get-Date) - $launchStarted).TotalMilliseconds
@("serial=$serial", "package=$Package", "avd=$AvdName", "startup_ms=$startupMs") | Set-Content -LiteralPath (Join-Path $Output "review.txt")
& $Adb -s $serial shell dumpsys meminfo $Package | Out-File -Encoding utf8 (Join-Path $Output "meminfo.txt")
Write-Output $Output

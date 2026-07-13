param(
    [string]$Serial = "emulator-5554",
    [switch]$Headless
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot "..\.tools\android-sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }

& $adb -s $Serial wait-for-device
try {
    # Exercise the most failure-prone accessibility layout: 200% system text,
    # no transition animation, and the same clean-install review flow used by CI.
    & $adb -s $Serial shell settings put system font_scale 2.0
    & $adb -s $Serial shell settings put global animator_duration_scale 0
    & $adb -s $Serial shell settings put global transition_animation_scale 0
    $reviewParams = @{
        Install = $true
        ResetApp = $true
        Serial = $Serial
        Headless = $Headless.IsPresent
    }
    & (Join-Path $PSScriptRoot "review-emulator-ui.ps1") @reviewParams
}
finally {
    & $adb -s $Serial shell settings put system font_scale 1.0
    & $adb -s $Serial shell settings put global animator_duration_scale 1
    & $adb -s $Serial shell settings put global transition_animation_scale 1
}

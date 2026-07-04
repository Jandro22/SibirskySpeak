param(
    [string]$AvdName = "Sibirsky_Pixel4a_API35",
    [switch]$Visible,
    [switch]$ColdBoot
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Sdk = Join-Path $Root ".tools\android-sdk"
$AvdHome = Join-Path $Root ".tools\android-avd"
$Emulator = Join-Path $Sdk "emulator\emulator.exe"
$Adb = Join-Path $Sdk "platform-tools\adb.exe"

$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_AVD_HOME = $AvdHome

if (!(Test-Path (Join-Path $AvdHome "$AvdName.ini"))) {
    & (Join-Path $PSScriptRoot "setup-emulator.ps1") -AvdName $AvdName
}

function Find-RunningAvd {
    $serials = & $Adb devices | Select-String '^emulator-\d+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] }
    foreach ($serial in $serials) {
        $nameLine = & $Adb -s $serial emu avd name 2>$null | Select-Object -First 1
        $name = if ($null -ne $nameLine) { $nameLine.Trim() } else { "" }
        if ($name -eq $AvdName) { return $serial }
    }
    return $null
}

$serial = Find-RunningAvd
if (!$serial) {
    # Software rendering is slightly slower than host GPU rendering but makes
    # headless screencap deterministic on Windows (host+no-window can return black).
    $gpuMode = if ($Visible) { "auto" } else { "swiftshader_indirect" }
    $arguments = @("@$AvdName", "-no-boot-anim", "-camera-back", "none", "-camera-front", "none", "-gpu", $gpuMode, "-memory", "2048", "-cores", "2")
    if (!$Visible) { $arguments += "-no-window" }
    if ($ColdBoot) { $arguments += "-no-snapshot-load" }
    Start-Process -FilePath $Emulator -ArgumentList $arguments -WorkingDirectory (Split-Path $Emulator) -WindowStyle Hidden | Out-Null

    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 2
        $serial = Find-RunningAvd
    } while (!$serial -and (Get-Date) -lt $deadline)
    if (!$serial) { throw "Emulator did not register with ADB within four minutes." }
}

$deadline = (Get-Date).AddMinutes(4)
do {
    Start-Sleep -Seconds 2
    $booted = (& $Adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
} while ($booted -ne "1" -and (Get-Date) -lt $deadline)
if ($booted -ne "1") { throw "Emulator $serial did not finish booting within four minutes." }

# Keep Android/Compose animations enabled. Setting animator_duration_scale to zero
# can freeze AnimatedContent at its initial transparent frame and invalidate review
# screenshots even though the accessibility hierarchy is present.
& $Adb -s $serial shell settings put global window_animation_scale 1 | Out-Null
& $Adb -s $serial shell settings put global transition_animation_scale 1 | Out-Null
& $Adb -s $serial shell settings put global animator_duration_scale 1 | Out-Null
& $Adb -s $serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $Adb -s $serial shell wm dismiss-keyguard | Out-Null

Write-Output $serial

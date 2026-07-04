param(
    [string]$AvdName = "Sibirsky_Pixel4a_API35"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Sdk = Join-Path $Root ".tools\android-sdk"
$Jdk = Join-Path $Root ".tools\jdk"
$AvdHome = Join-Path $Root ".tools\android-avd"
$SdkManager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$AvdManager = Join-Path $Sdk "cmdline-tools\latest\bin\avdmanager.bat"
$Image = "system-images;android-35;google_apis;x86_64"

if (!(Test-Path $SdkManager)) {
    & (Join-Path $PSScriptRoot "setup-android.ps1")
}

$env:JAVA_HOME = $Jdk
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_AVD_HOME = $AvdHome
New-Item -ItemType Directory -Force -Path $AvdHome | Out-Null

$installed = & $SdkManager --list_installed | Out-String
if ($installed -notmatch [regex]::Escape($Image)) {
    Write-Host "Installing the lightweight Google APIs ATD image (one-time download)..."
    "y" | & $SdkManager "emulator" $Image
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$existing = & $AvdManager list avd | Out-String
$config = Join-Path $AvdHome "$AvdName.avd\config.ini"
$hasCorrectImage = (Test-Path $config) -and ((Get-Content $config | Out-String) -match 'image\.sysdir\.1=.*google_apis')
if (!$hasCorrectImage -and $existing -match "Name:\s+$([regex]::Escape($AvdName))(\s|$)") {
    & $AvdManager delete avd --name $AvdName | Out-Null
    $existing = & $AvdManager list avd | Out-String
}
if ($existing -notmatch "Name:\s+$([regex]::Escape($AvdName))(\s|$)") {
    Write-Host "Creating $AvdName"
    "no" | & $AvdManager create avd --force --name $AvdName --package $Image --device "pixel_4a"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$settings = [ordered]@{
    "hw.cpu.ncore" = "2"
    "hw.ramSize" = "2048"
    "vm.heapSize" = "256"
    "disk.dataPartition.size" = "2G"
    "hw.gpu.enabled" = "yes"
    "hw.gpu.mode" = "auto"
    "hw.camera.back" = "none"
    "hw.camera.front" = "none"
    "showDeviceFrame" = "no"
    "skin.dynamic" = "yes"
}
$lines = [Collections.Generic.List[string]]::new()
if (Test-Path $config) {
    foreach ($line in Get-Content $config) { $lines.Add($line) }
}
foreach ($entry in $settings.GetEnumerator()) {
    $prefix = "$($entry.Key)="
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { $index = $i; break }
    }
    if ($index -ge 0) { $lines[$index] = "$prefix$($entry.Value)" } else { $lines.Add("$prefix$($entry.Value)") }
}
$lines | Set-Content -LiteralPath $config -Encoding ASCII

Write-Host "Emulator ready: $AvdName"
Write-Host "AVD home: $AvdHome"

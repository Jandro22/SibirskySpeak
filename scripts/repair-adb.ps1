param(
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$AndroidHome = Join-Path $Root ".tools\android-sdk"
$Adb = Join-Path $AndroidHome "platform-tools\adb.exe"
$SdkManager = Join-Path $AndroidHome "cmdline-tools\latest\bin\sdkmanager.bat"
$DriverInf = Join-Path $AndroidHome "extras\google\usb_driver\android_winusb.inf"

if (!(Test-Path -LiteralPath $Adb) -or !(Test-Path -LiteralPath $SdkManager)) {
    & (Join-Path $PSScriptRoot "setup-android.ps1")
}
if (!(Test-Path -LiteralPath $DriverInf)) {
    & $SdkManager "platform-tools" "extras;google;usb_driver"
    if ($LASTEXITCODE -ne 0) { throw "Could not download Android platform-tools and the Google USB driver." }
}

$pixel = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -match "^USB\\VID_18D1" } |
    Where-Object { [string]::IsNullOrWhiteSpace($Serial) -or $_.InstanceId -match [regex]::Escape($Serial) } |
    Select-Object -First 1
if (-not $pixel) { throw "Windows does not currently see a connected Google USB device." }

$driver = Get-CimInstance Win32_PnPSignedDriver |
    Where-Object { $_.DeviceID -eq $pixel.InstanceId } |
    Select-Object -First 1
if ($driver.DriverProviderName -ne "Google, Inc.") {
    Write-Host "Installing the signed Google ADB driver for $($pixel.FriendlyName)..."
    $arguments = "/add-driver `"$DriverInf`" /install"
    $process = Start-Process -FilePath "$env:SystemRoot\System32\pnputil.exe" `
        -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Google ADB driver installation failed with exit code $($process.ExitCode)." }
}

function Restart-AdbServer {
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb kill-server 2>&1 | Out-Null
        Get-Process adb -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -eq (Resolve-Path -LiteralPath $Adb).Path } |
            Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 500
        & $Adb start-server 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "ADB server failed to start." }
    } finally {
        $ErrorActionPreference = $priorPreference
    }
}

function Find-PhysicalSerial([int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $lines = @((& $Adb devices -l 2>$null | Out-String) -split "`r?`n" |
            Where-Object { $_ -match "^\S+\s+device(?:\s|$)" })
        $physical = @($lines | Where-Object { ($_ -split "\s+")[0] -notmatch "^emulator-" })
        if ($Serial) {
            $match = $physical | Where-Object { ($_ -split "\s+")[0] -eq $Serial } | Select-Object -First 1
            if ($match) { return ($match -split "\s+")[0] }
        } elseif ($physical.Count -eq 1) {
            return ($physical[0] -split "\s+")[0]
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $null
}

Restart-AdbServer
$online = Find-PhysicalSerial 10
if (-not $online) {
    # The signed driver can remain healthy while the Windows USB transport handle is
    # stale after sleep or a cable renegotiation. Restart just this device node once.
    Write-Host "Restarting the Pixel USB device node..."
    $arguments = "/restart-device `"$($pixel.InstanceId)`""
    $process = Start-Process -FilePath "$env:SystemRoot\System32\pnputil.exe" `
        -ArgumentList $arguments -Verb RunAs -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Pixel USB restart failed with exit code $($process.ExitCode)." }
    Start-Sleep -Seconds 2
    Restart-AdbServer
    $online = Find-PhysicalSerial 15
}

if (-not $online) {
    throw "Windows and the Google driver are healthy, but the Pixel did not authorize ADB. Unlock it and confirm the USB debugging authorization prompt."
}

Write-Host "ADB repaired: $online is online through the signed Google driver."
Write-Output $online

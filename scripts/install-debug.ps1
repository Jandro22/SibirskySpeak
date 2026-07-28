param(
    # Pass -Serial when a phone and emulator are connected at the same time.
    # The previous script silently assumed exactly one device, which made the
    # normal "push to phone" workflow fail as soon as an emulator was running.
    [string]$Serial,
    # Use only for a known-empty/new device. Existing learner data is still
    # protected by the default fail-closed snapshot check.
    [switch]$AllowWithoutBackup
)

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

function Get-AuthorizedDevices {
    $output = (& $Adb devices -l 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { return @() }
    return @($output -split "`r?`n" | Where-Object { $_ -match "^\S+\s+device(?:\s|$)" })
}

function Select-DeviceSerial([string[]]$Devices, [string]$RequestedSerial) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        $match = $Devices | Where-Object { ($_ -split "\s+")[0] -eq $RequestedSerial } | Select-Object -First 1
        if ($match) { return ($match -split "\s+")[0] }
        return $null
    }
    # A running emulator must never make the normal "push to phone" workflow
    # ambiguous. Prefer the sole physical transport; fall back to a sole emulator.
    $physical = @($Devices | Where-Object { ($_ -split "\s+")[0] -notmatch "^emulator-" })
    if ($physical.Count -eq 1) { return ($physical[0] -split "\s+")[0] }
    if ($Devices.Count -eq 1) { return ($Devices[0] -split "\s+")[0] }
    return $null
}

function Wait-ForDevice([string]$RequestedSerial, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $devices = @(Get-AuthorizedDevices)
        $selected = Select-DeviceSerial $devices $RequestedSerial
        if ($selected) { return $selected }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Restart-AdbServer {
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Adb reconnect offline 2>&1 | Out-Null
        & $Adb kill-server 2>&1 | Out-Null
        Start-Sleep -Milliseconds 500
        & $Adb start-server 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $priorPreference
    }
}

$priorPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try { & $Adb start-server 2>&1 | Out-Null } finally { $ErrorActionPreference = $priorPreference }
$RequestedSerial = $Serial
# If Windows has a physical Google USB device attached, pin that transport before
# considering an emulator fallback. An offline Pixel is deliberately absent from
# Get-AuthorizedDevices; without this pin the old selector mistook the emulator for
# the sole target, then failed its backup check against the wrong installation.
if ([string]::IsNullOrWhiteSpace($RequestedSerial)) {
    $attachedPixel = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -match "^USB\\VID_18D1" } |
        Select-Object -First 1
    if ($attachedPixel) {
        $usbSerial = ($attachedPixel.InstanceId -split "\\")[-1]
        if (-not [string]::IsNullOrWhiteSpace($usbSerial)) { $RequestedSerial = $usbSerial }
    }
}
$Serial = Wait-ForDevice $RequestedSerial 5
if (-not $Serial) {
    Restart-AdbServer
    $Serial = Wait-ForDevice $RequestedSerial 15
}
if (-not $Serial) {
    $usbPixel = Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue |
        Where-Object { $_.InstanceId -match "^USB\\VID_18D1" } |
        Select-Object -First 1
    if ($usbPixel) {
        # A healthy Google WinUSB node can retain a stale handle after sleep or USB
        # renegotiation. ADB then sees only the emulator until the device node is
        # rebound. Repair that condition automatically instead of making every
        # install fail and asking the learner to run a separate command by hand.
        Write-Warning "Windows sees '$($usbPixel.FriendlyName)' but ADB does not; rebinding the Pixel USB transport."
        $repairArgs = @{}
        if ($RequestedSerial) { $repairArgs.Serial = $RequestedSerial }
        & (Join-Path $PSScriptRoot "repair-adb.ps1") @repairArgs | Out-Null
        $Serial = Wait-ForDevice $RequestedSerial 15
    }
}
if (-not $Serial) {
    if ($RequestedSerial) {
        throw "Requested device '$RequestedSerial' did not become authorized and online after restarting ADB."
    }
    throw "No unambiguous authorized Android target became online after restarting ADB."
}
# "Stay awake while charging" is a real, persistent device setting (Settings >
# Developer options), not something scoped to this script or this app — leaving
# it changed after the script exits silently overrides the phone's own sleep
# behavior indefinitely, which is not this script's call to make. Caffeinate
# only for the duration of the install (so a QA session doesn't lock mid-way
# through observing a change), then always restore whatever the setting was
# before, even if the install itself fails.
$PreviousStayOn = (& $Adb -s $Serial shell settings get global stay_on_while_plugged_in 2>$null | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($PreviousStayOn) -or $PreviousStayOn -eq "null") { $PreviousStayOn = "0" }
& $Adb -s $Serial shell settings put global stay_on_while_plugged_in 7 | Out-Null
& $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null

try {
    # A replacement install normally preserves app data, but capture the app's latest
    # validated full-state snapshot on the host before touching an existing package.
    # If an existing install has data but no snapshot, stop: continuing would leave no
    # recovery path if signing/package tooling unexpectedly falls back to uninstall.
    $Installed = (& $Adb -s $Serial shell pm path com.sibirskyspeak 2>$null | Out-String).Trim()
    if ($Installed) {
        $BackupRoot = Join-Path $Root ".device-backups\$Serial"
        New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
        $Stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
        $BackupPath = Join-Path $BackupRoot "full_state_$Stamp.jsonl"
        $BackupContent = & $Adb -s $Serial exec-out run-as com.sibirskyspeak cat files/backups/full_state_latest.jsonl 2>$null | Out-String
        if ([string]::IsNullOrWhiteSpace($BackupContent) -or $BackupContent -notmatch '"russian"\s*:') {
            if (-not $AllowWithoutBackup) {
                throw "Existing SibirskySpeak install has no readable full-state snapshot. Open the app and use Full Backup before installing, or pass -AllowWithoutBackup only for a known-empty device."
            }
            Write-Warning "No readable learner snapshot found; continuing because -AllowWithoutBackup was explicitly supplied."
        } else {
            $HasNote = $false
            foreach ($Line in ($BackupContent -split "`r?`n")) {
                if ([string]::IsNullOrWhiteSpace($Line)) { continue }
                try { $Row = $Line | ConvertFrom-Json -ErrorAction Stop }
                catch { throw "The device snapshot is truncated or invalid JSONL; refusing to install." }
                if ($null -ne $Row.russian -and $null -ne $Row.lemma) { $HasNote = $true }
            }
            if (!$HasNote) { throw "The device snapshot contains no learner notes; refusing to install." }
            [System.IO.File]::WriteAllText($BackupPath, $BackupContent, [System.Text.UTF8Encoding]::new($false))
            Write-Host "Saved device recovery snapshot: $BackupPath"
        }
    }

    $installedSuccessfully = $false
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        & $Adb -s $Serial install -r $Apk
        if ($LASTEXITCODE -eq 0) {
            $installedSuccessfully = $true
            break
        }
        if ($attempt -eq 1) {
            Write-Warning "ADB transport dropped during install; restarting it and retrying once."
            Restart-AdbServer
            if (-not (Wait-ForDevice $Serial 15)) {
                & (Join-Path $PSScriptRoot "repair-adb.ps1") -Serial $Serial | Out-Null
                if (-not (Wait-ForDevice $Serial 15)) { break }
            }
        }
    }
    if (-not $installedSuccessfully) { throw "APK installation failed after ADB recovery and one retry." }
}
finally {
    & $Adb -s $Serial shell settings put global stay_on_while_plugged_in $PreviousStayOn | Out-Null
}

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
            throw "Existing SibirskySpeak install has no readable full-state snapshot. Open the app and use Full Backup before installing; refusing to risk learner data."
        }
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

    & $Adb -s $Serial install -r $Apk
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    & $Adb -s $Serial shell settings put global stay_on_while_plugged_in $PreviousStayOn | Out-Null
}

param(
    [string]$Package = "com.sibirskyspeak",
    [string]$OutputRoot = "build/device-debug",
    [switch]$PullDatabase,
    [switch]$NoKeepAwake
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @(
        (Join-Path $PSScriptRoot "../.tools/android-sdk/platform-tools/adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android/Sdk/platform-tools/adb.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    }

    $running = Get-CimInstance Win32_Process -Filter "name = 'adb.exe'" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($running -and $running.ExecutablePath -and (Test-Path -LiteralPath $running.ExecutablePath)) { return $running.ExecutablePath }
    throw "ADB was not found. Install Android platform-tools or place them in .tools/android-sdk/platform-tools."
}

function Invoke-AdbText([string[]]$Arguments) {
    $priorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $result = (& $script:Adb @Arguments 2>&1 | Out-String).TrimEnd()
        if ($LASTEXITCODE -ne 0) { throw "ADB failed ($LASTEXITCODE): $result" }
        return $result
    } finally {
        $ErrorActionPreference = $priorPreference
    }
}

function Save-AdbBinary([string[]]$Arguments, [string]$Destination) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:Adb
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Windows PowerShell 5.1 targets .NET Framework, where ProcessStartInfo.ArgumentList
    # is absent. Quote each fixed argument explicitly so binary capture works there too.
    $start.Arguments = ($Arguments | ForEach-Object {
        '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { throw "Failed to start ADB binary capture." }
    try {
        $stream = [IO.File]::Create($Destination)
        try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $exitCode = $process.ExitCode
    } finally {
        $process.Dispose()
    }
    if ($exitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "ADB binary capture failed: $errorText"
    }
}

$script:Adb = Find-Adb
if ((Invoke-AdbText @("devices", "-l")) -match "\soffline\s") {
    # Wireless ADB occasionally leaves a stale offline transport after sleep.
    # Reconnect it before asking the user to touch the phone.
    try { Invoke-AdbText @("reconnect", "offline") | Out-Null } catch { }
    $deadline = (Get-Date).AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 500
        $deviceList = Invoke-AdbText @("devices", "-l")
    } while ($deviceList -notmatch "\sdevice\s" -and (Get-Date) -lt $deadline)
} else {
    $deviceList = Invoke-AdbText @("devices", "-l")
}
$deviceLines = $deviceList -split "`r?`n" | Where-Object { $_ -match "\sdevice\s" }
if ($deviceLines.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($deviceLines.Count)." }

if (-not $NoKeepAwake) {
    # Keep the display awake whenever USB or wireless debugging is powered. This
    # avoids repeated lock-screen interruptions during a development session.
    Invoke-AdbText @("shell", "settings", "put", "global", "stay_on_while_plugged_in", "7") | Out-Null
    Invoke-AdbText @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$output = Join-Path $OutputRoot $stamp
New-Item -ItemType Directory -Force -Path $output | Out-Null
$output = (Resolve-Path -LiteralPath $output).Path

$deviceLines | Set-Content -LiteralPath (Join-Path $output "device.txt")
Invoke-AdbText @("shell", "dumpsys", "window") | Select-String "mCurrentFocus|mFocusedApp" | Set-Content -LiteralPath (Join-Path $output "focus.txt")
Invoke-AdbText @("shell", "dumpsys", "package", $Package) | Set-Content -LiteralPath (Join-Path $output "package.txt")

$remoteScreen = "/sdcard/${Package}_debug_screen.png"
$remoteUi = "/sdcard/${Package}_debug_ui.xml"
try {
    Invoke-AdbText @("shell", "screencap", "-p", $remoteScreen) | Out-Null
    Invoke-AdbText @("pull", $remoteScreen, (Join-Path $output "screen.png")) | Out-Null
    Invoke-AdbText @("shell", "uiautomator", "dump", $remoteUi) | Out-Null
    Invoke-AdbText @("pull", $remoteUi, (Join-Path $output "ui.xml")) | Out-Null
} finally {
    Invoke-AdbText @("shell", "rm", "-f", $remoteScreen, $remoteUi) | Out-Null
}

$appPid = try { Invoke-AdbText @("shell", "pidof", $Package) } catch { $null }
if ($appPid) {
    Invoke-AdbText @("logcat", "-d", "--pid=$appPid", "-t", "2000") | Set-Content -LiteralPath (Join-Path $output "logcat.txt")
} else {
    "Package process is not running." | Set-Content -LiteralPath (Join-Path $output "logcat.txt")
}

# run-as confines this inventory to the app's private data directory. It records
# filenames only and never reads shared storage or another application's sandbox.
Invoke-AdbText @("shell", "run-as $Package sh -c 'pwd; find . -maxdepth 3 -type f | sort'") |
    Set-Content -LiteralPath (Join-Path $output "sandbox-files.txt")

if ($PullDatabase) {
    # Stop Room briefly so its WAL is checkpointed and the three files cannot be
    # copied from different moments. The next app launch is otherwise unaffected.
    Invoke-AdbText @("shell", "am", "force-stop", $Package) | Out-Null
    Start-Sleep -Milliseconds 500
    $databaseOutput = Join-Path $output "databases"
    New-Item -ItemType Directory -Force -Path $databaseOutput | Out-Null
    foreach ($name in @("sibirsky_speak.db", "sibirsky_speak.db-wal", "sibirsky_speak.db-shm")) {
        try {
            Save-AdbBinary @("exec-out", "run-as", $Package, "cat", "databases/$name") (Join-Path $databaseOutput $name)
        } catch {
            Write-Warning $_.Exception.Message
        }
    }
    $mainDatabase = Join-Path $databaseOutput "sibirsky_speak.db"
    if (-not (Test-Path -LiteralPath $mainDatabase) -or (Get-Item -LiteralPath $mainDatabase).Length -lt 4096) {
        throw "Database capture is missing or truncated; telemetry was not captured."
    }
}

Write-Output $output

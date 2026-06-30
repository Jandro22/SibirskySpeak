param(
    [string]$Package = "com.sibirskyspeak",
    [string]$OutputRoot = "build/device-debug",
    [switch]$PullDatabase
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
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    $stream = [IO.File]::Create($Destination)
    try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
    $errorText = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue
        throw "ADB binary capture failed: $errorText"
    }
}

$script:Adb = Find-Adb
$deviceLines = Invoke-AdbText @("devices", "-l") -split "`r?`n" | Where-Object { $_ -match "\sdevice\s" }
if ($deviceLines.Count -ne 1) { throw "Expected exactly one authorized Android device; found $($deviceLines.Count)." }

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

$appPid = Invoke-AdbText @("shell", "pidof", $Package)
if ($appPid) {
    Invoke-AdbText @("logcat", "-d", "--pid=$appPid", "-t", "2000") | Set-Content -LiteralPath (Join-Path $output "logcat.txt")
} else {
    "Package process is not running." | Set-Content -LiteralPath (Join-Path $output "logcat.txt")
}

# run-as confines this inventory to the app's private data directory. It records
# filenames only and never reads shared storage or another application's sandbox.
Invoke-AdbText @("shell", "run-as", $Package, "sh", "-c", "pwd; find . -maxdepth 3 -type f | sort") |
    Set-Content -LiteralPath (Join-Path $output "sandbox-files.txt")

if ($PullDatabase) {
    $databaseOutput = Join-Path $output "databases"
    New-Item -ItemType Directory -Force -Path $databaseOutput | Out-Null
    foreach ($name in @("sibirsky_speak.db", "sibirsky_speak.db-wal", "sibirsky_speak.db-shm")) {
        try {
            Save-AdbBinary @("exec-out", "run-as", $Package, "cat", "databases/$name") (Join-Path $databaseOutput $name)
        } catch {
            Write-Warning $_.Exception.Message
        }
    }
}

Write-Output $output

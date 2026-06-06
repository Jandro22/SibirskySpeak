param(
    [switch] $SkipSdkInstall
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root ".tools"
$Downloads = Join-Path $Tools "downloads"
$JdkRoot = Join-Path $Tools "jdk"
$GradleRoot = Join-Path $Tools "gradle"
$AndroidHome = Join-Path $Tools "android-sdk"
$CmdlineLatest = Join-Path $AndroidHome "cmdline-tools\latest"

$JdkUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
$GradleUrl = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
$CmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip"

New-Item -ItemType Directory -Force -Path $Downloads, $Tools | Out-Null

function Download-IfMissing($Url, $Destination) {
    if (Test-Path $Destination) {
        return
    }
    Write-Host "Downloading $Url"
    curl.exe -L --fail --retry 3 --output $Destination $Url
}

if (!(Test-Path $JdkRoot)) {
    $JdkZip = Join-Path $Downloads "temurin-jdk-17.zip"
    $JdkExtract = Join-Path $Tools "jdk-extract"
    Download-IfMissing $JdkUrl $JdkZip
    Remove-Item -Recurse -Force $JdkExtract -ErrorAction SilentlyContinue
    Expand-Archive -Path $JdkZip -DestinationPath $JdkExtract -Force
    $ExtractedJdk = Get-ChildItem $JdkExtract -Directory | Select-Object -First 1
    Move-Item -Path $ExtractedJdk.FullName -Destination $JdkRoot
    Remove-Item -Recurse -Force $JdkExtract
}

if (!(Test-Path $GradleRoot)) {
    $GradleZip = Join-Path $Downloads "gradle-8.9-bin.zip"
    $GradleExtract = Join-Path $Tools "gradle-extract"
    Download-IfMissing $GradleUrl $GradleZip
    Remove-Item -Recurse -Force $GradleExtract -ErrorAction SilentlyContinue
    Expand-Archive -Path $GradleZip -DestinationPath $GradleExtract -Force
    $ExtractedGradle = Get-ChildItem $GradleExtract -Directory | Select-Object -First 1
    Move-Item -Path $ExtractedGradle.FullName -Destination $GradleRoot
    Remove-Item -Recurse -Force $GradleExtract
}

if (!(Test-Path $CmdlineLatest)) {
    $CmdlineZip = Join-Path $Downloads "android-commandline-tools.zip"
    $CmdlineExtract = Join-Path $Tools "cmdline-tools-extract"
    Download-IfMissing $CmdlineToolsUrl $CmdlineZip
    Remove-Item -Recurse -Force $CmdlineExtract -ErrorAction SilentlyContinue
    Expand-Archive -Path $CmdlineZip -DestinationPath $CmdlineExtract -Force
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CmdlineLatest) | Out-Null
    Move-Item -Path (Join-Path $CmdlineExtract "cmdline-tools") -Destination $CmdlineLatest
    Remove-Item -Recurse -Force $CmdlineExtract
}

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:PATH = "$JdkRoot\bin;$GradleRoot\bin;$CmdlineLatest\bin;$AndroidHome\platform-tools;$env:PATH"

$SdkDir = ($AndroidHome -replace "\\", "/")
Set-Content -Path (Join-Path $Root "local.properties") -Value "sdk.dir=$SdkDir`n" -Encoding ASCII

if (!$SkipSdkInstall) {
    $SdkManager = Join-Path $CmdlineLatest "bin\sdkmanager.bat"
    Write-Host "Accepting Android SDK licenses"
    "y`ny`ny`ny`ny`ny`ny`ny`ny`ny`n" | & $SdkManager --licenses
    Write-Host "Installing Android SDK packages"
    & $SdkManager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
}

Write-Host "Android build tools are ready."
Write-Host "JAVA_HOME=$JdkRoot"
Write-Host "ANDROID_HOME=$AndroidHome"

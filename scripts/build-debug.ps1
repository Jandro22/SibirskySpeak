$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root ".tools"
$JdkRoot = Join-Path $Tools "jdk"
$GradleRoot = Join-Path $Tools "gradle"
$AndroidHome = Join-Path $Tools "android-sdk"
$CmdlineLatest = Join-Path $AndroidHome "cmdline-tools\latest"

if (!(Test-Path $JdkRoot) -or !(Test-Path $GradleRoot) -or !(Test-Path $CmdlineLatest)) {
    & (Join-Path $PSScriptRoot "setup-android.ps1")
}

$env:JAVA_HOME = $JdkRoot
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:PATH = "$JdkRoot\bin;$GradleRoot\bin;$CmdlineLatest\bin;$AndroidHome\platform-tools;$env:PATH"

Push-Location $Root
try {
    if (Test-Path ".\gradlew.bat") {
        .\gradlew.bat assembleDebug
    } else {
        & (Join-Path $GradleRoot "bin\gradle.bat") assembleDebug
    }
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

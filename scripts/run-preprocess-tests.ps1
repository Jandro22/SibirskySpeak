$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Python = Get-Command python -ErrorAction SilentlyContinue
if ($null -eq $Python) {
    throw "python is required for preprocessing tests"
}

Push-Location (Join-Path $Root "tools\preprocess")
try {
    python -m pytest -q
} finally {
    Pop-Location
}

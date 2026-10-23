# Build (and optionally install) the debug app. Requires JDK 17 and the Android SDK.
# Usage: .\build_app.ps1 [-Install] [-Release]
param([switch]$Install, [switch]$Release)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME) { Write-Warning 'JAVA_HOME is not set; using java from PATH' }
if (-not $env:ANDROID_HOME -and -not (Test-Path 'local.properties')) {
    Write-Warning 'Set ANDROID_HOME or create local.properties with sdk.dir=<path to Android SDK>'
}

$task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
& .\gradlew.bat $task
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Get-ChildItem -Recurse -Filter '*.apk' app\build\outputs\apk | Select-Object -First 1
Write-Host "Built: $($apk.FullName)"

if ($Install) {
    # uses the single connected device; pass -s <serial> yourself via adb if several are attached
    adb install -r $apk.FullName
}
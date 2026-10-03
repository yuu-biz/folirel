[CmdletBinding()]
param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed: $LASTEXITCODE" }
    if ($DeviceTests) {
        & .\gradlew.bat :app:connectedDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Device tests failed: $LASTEXITCODE" }
    }
}
finally {
    Pop-Location
}

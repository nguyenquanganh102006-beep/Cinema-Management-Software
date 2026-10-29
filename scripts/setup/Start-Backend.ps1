$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Import-LocalEnvironment.ps1')
# Existing IDE terminals may still have the old environment after a JDK install.
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $savedJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if ($savedJavaHome -and (Test-Path -LiteralPath (Join-Path $savedJavaHome 'bin\javac.exe'))) {
        $env:JAVA_HOME = $savedJavaHome
    } else {
        throw 'JDK 21 is required. Set user JAVA_HOME to the JDK folder containing bin\javac.exe, then retry.'
    }
}
$env:DEBUG = 'false'
Push-Location (Join-Path $PSScriptRoot '..\..\backend')
try {
    & .\mvnw.cmd spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Backend exited unsuccessfully.' }
} finally { Pop-Location }

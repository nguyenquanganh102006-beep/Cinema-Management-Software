$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\setup\Import-LocalEnvironment.ps1')
Push-Location (Join-Path $PSScriptRoot '..\..\frontend')
try {
    & npm.cmd run test:e2e
    if ($LASTEXITCODE -ne 0) { throw 'Frontend E2E failed.' }
} finally { Pop-Location }

[CmdletBinding()]
param(
    [ValidatePattern('^[a-z][a-z0-9_]{0,62}$')]
    [string]$DatabaseName = 'cinema_demo'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envPath = Join-Path $repoRoot '.env'
$dumpPath = Join-Path $repoRoot 'backend\data\cinema-demo.dump'

if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) {
    throw 'Missing .env. Run scripts/setup/Initialize-LocalEnvironment.ps1 first.'
}
if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) {
    throw 'Missing backend/data/cinema-demo.dump from the repository.'
}

$dbUsername = $null
foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
    if ($line -match '^\s*DB_USERNAME\s*=\s*(.*?)\s*$') {
        $dbUsername = $Matches[1].Trim()
        if ($dbUsername.Length -ge 2 -and (($dbUsername.StartsWith('"') -and $dbUsername.EndsWith('"')) -or ($dbUsername.StartsWith("'") -and $dbUsername.EndsWith("'")))) {
            $dbUsername = $dbUsername.Substring(1, $dbUsername.Length - 2)
        }
        break
    }
}
if (-not $dbUsername -or $dbUsername -notmatch '^[a-zA-Z_][a-zA-Z0-9_]*$') {
    throw 'DB_USERNAME is missing or invalid in .env.'
}

$containerId = (& docker compose -f (Join-Path $repoRoot 'compose.yaml') ps -q postgres).Trim()
if ($LASTEXITCODE -ne 0 -or -not $containerId) {
    throw 'The PostgreSQL container is not running. Start it with docker compose up -d postgres.'
}

$existingDatabase = & docker exec $containerId psql -U $dbUsername -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DatabaseName';"
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect PostgreSQL databases.' }
if (($existingDatabase | Out-String).Trim() -eq '1') {
    throw "Database '$DatabaseName' already exists. Choose another name; this script never overwrites a database."
}

$containerDumpPath = '/tmp/cinema-demo-restore.dump'
$databaseCreated = $false
try {
    & docker exec $containerId createdb -U $dbUsername $DatabaseName
    if ($LASTEXITCODE -ne 0) { throw "Could not create database '$DatabaseName'." }
    $databaseCreated = $true

    & docker cp $dumpPath "${containerId}:$containerDumpPath" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the demo snapshot into PostgreSQL.' }

    & docker exec $containerId pg_restore -U $dbUsername -d $DatabaseName --no-owner --no-acl $containerDumpPath
    if ($LASTEXITCODE -ne 0) { throw "Could not restore the demo snapshot into '$DatabaseName'." }
} catch {
    $restoreFailure = $_
    if ($databaseCreated) {
        & docker exec $containerId dropdb -U $dbUsername $DatabaseName | Out-Null
    }
    throw $restoreFailure
} finally {
    & docker exec $containerId rm -f $containerDumpPath | Out-Null
}

Write-Host "Restored the sanitized demo database as '$DatabaseName'."
Write-Host "Set DB_URL=jdbc:postgresql://localhost:<POSTGRES_PORT>/$DatabaseName in .env before starting the backend."

param([string]$Path = (Join-Path $PSScriptRoot '..\..\.env'))
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Path)) { throw 'Missing .env. Run scripts/setup/Initialize-LocalEnvironment.ps1 first.' }
foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
    if ($line -match '^\s*(#|$)') { continue }
    if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid environment line. Use KEY=value, without shell commands.' }
    $key = $Matches[1]
    $value = $Matches[2].Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}

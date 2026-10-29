$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$destination = Join-Path $root '.env'
if (Test-Path -LiteralPath $destination) { throw '.env already exists; edit it directly rather than overwriting credentials.' }
function New-RandomValue([int]$Length) {
    $bytes = New-Object byte[] $Length
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
$template = [IO.File]::ReadAllText((Join-Path $root '.env.example'))
$template = $template.Replace('replace-with-local-password', (New-RandomValue 16))
$template = $template.Replace('replace-with-at-least-32-random-bytes', (New-RandomValue 32))
$template = $template.Replace('replace-with-demo-password', (New-RandomValue 8))
$template = $template.Replace('replace-with-demo-pin', (Get-Random -Minimum 100000 -Maximum 1000000).ToString())
[IO.File]::WriteAllText($destination, $template, (New-Object Text.UTF8Encoding $false))
Write-Output 'Created ignored .env with random local credentials. Read DEMO_PASSWORD there to log in.'

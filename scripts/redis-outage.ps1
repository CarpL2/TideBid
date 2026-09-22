[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Status', 'Suspend', 'Resume')]
    [string]$Action,

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $repositoryRoot '.env' }
elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $repositoryRoot $EnvFile }
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf) -or
    -not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw 'TideBid .env or infra/compose.yaml is missing.'
}
if ($Action -eq 'Suspend' -and -not $AcknowledgeImpact) {
    throw 'Suspend intentionally interrupts Redis. Retry with -AcknowledgeImpact.'
}
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) { throw 'Docker CLI was not found.' }
& $docker.Source version --format '{{.Server.Version}}' *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop engine is not reachable.' }
$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)

function Get-RedisStatus {
    $rows = @(& $script:docker.Source @($script:compose + @(
        'ps', '--all', '--format', '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}', 'redis')))
    if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1) { throw 'Could not inspect the TideBid Redis container.' }
    $parts = ([string]$rows[0]).Split('|', 4)
    if ($parts.Count -ne 4) { throw 'Docker returned an invalid Redis status row.' }
    return [pscustomobject]@{ Service = $parts[0]; State = $parts[1]; Health = $parts[2]; ExitCode = [int]$parts[3] }
}

if ($Action -eq 'Status') {
    $status = Get-RedisStatus
    Write-Host "redis: $($status.State)/$($status.Health) exit-$($status.ExitCode)"
    return
}

if ($Action -eq 'Suspend') {
    & $docker.Source @($compose + @('stop', 'redis'))
    if ($LASTEXITCODE -ne 0) { throw 'Redis could not be suspended.' }
    $status = Get-RedisStatus
    if ($status.State -eq 'running') { throw 'Redis is still running after suspend.' }
    Write-Host '[PASS] TideBid Redis is suspended. Containers and named volumes were preserved.'
    Write-Host 'Always run: .\scripts\redis-outage.ps1 -Action Resume'
    return
}

& (Join-Path $PSScriptRoot 'infra-up.ps1') -EnvFile $EnvFile
$status = Get-RedisStatus
if ($status.State -ne 'running' -or $status.Health -ne 'healthy') {
    throw 'Redis did not become healthy after resume.'
}
Write-Host '[PASS] TideBid Redis is healthy again; existing data volumes were preserved.'

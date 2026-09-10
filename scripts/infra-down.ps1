[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(0, 300)]
    [int]$TimeoutSeconds = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) {
    throw 'Docker CLI was not found. Install Docker Desktop and retry.'
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file does not exist: $EnvFile"
}
if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "Compose file does not exist: $composeFile"
}

& $dockerCommand.Source version --format '{{.Server.Version}}' *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop engine is not reachable. Start Docker Desktop and wait until the engine is running.'
}

$composeArguments = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
& $dockerCommand.Source @($composeArguments + @('config', '--quiet'))
if ($LASTEXITCODE -ne 0) {
    throw "TideBid Compose configuration is invalid. Docker exited with code $LASTEXITCODE."
}

$containerIds = @(& $dockerCommand.Source @($composeArguments + @('ps', '--all', '--quiet')))
if ($LASTEXITCODE -ne 0) {
    throw "Could not inspect TideBid containers. Docker exited with code $LASTEXITCODE."
}
if ($containerIds.Count -eq 0) {
    Write-Host 'No TideBid middleware containers exist. Nothing was stopped.'
    return
}

Write-Host 'Stopping TideBid middleware without removing containers, networks, or named volumes...'
$stopArguments = $composeArguments + @('stop')
if ($TimeoutSeconds -gt 0) {
    $stopArguments += @('--timeout', [string]$TimeoutSeconds)
}
& $dockerCommand.Source @stopArguments
if ($LASTEXITCODE -ne 0) {
    throw "TideBid middleware could not stop cleanly. Docker exited with code $LASTEXITCODE."
}

$runningIds = @(& $dockerCommand.Source @($composeArguments + @('ps', '--quiet', '--status', 'running')))
if ($LASTEXITCODE -ne 0) {
    throw "Could not verify stopped TideBid containers. Docker exited with code $LASTEXITCODE."
}
if ($runningIds.Count -gt 0) {
    throw 'Some TideBid middleware containers are still running after docker compose stop.'
}

Write-Host 'TideBid middleware is stopped. Containers and named volumes remain available for the next start.'

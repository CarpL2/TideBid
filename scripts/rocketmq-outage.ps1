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
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

foreach ($path in @($EnvFile, $composeFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file does not exist: $path"
    }
}
if ($Action -eq 'Suspend' -and -not $AcknowledgeImpact) {
    throw 'Suspend intentionally interrupts TideBid messaging. Retry with -AcknowledgeImpact.'
}
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}
& $docker.Source version --format '{{.Server.Version}}' *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop engine is not reachable.'
}
$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)

function Invoke-Compose {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & $script:docker.Source @($script:compose + $Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose command failed with exit code $LASTEXITCODE."
    }
}

if ($Action -eq 'Status') {
    Invoke-Compose -Arguments @(
        'ps', '--all', 'rocketmq-nameserver', 'rocketmq-broker', 'rocketmq-proxy', 'rocketmq-dashboard'
    )
    return
}

if ($Action -eq 'Suspend') {
    Write-Host 'Stopping only the TideBid RocketMQ Broker; containers and volumes are preserved...'
    Invoke-Compose -Arguments @('stop', 'rocketmq-broker')
    $runningBroker = @(& $docker.Source @($compose + @('ps', '--quiet', '--status', 'running', 'rocketmq-broker')))
    if ($LASTEXITCODE -ne 0 -or @($runningBroker | Where-Object { $_ }).Count -ne 0) {
        throw 'RocketMQ Broker is still running after the suspend request.'
    }
    Write-Host '[PASS] TideBid RocketMQ Broker is suspended. MySQL, applications and named volumes were not stopped.'
    Write-Host 'Always run: .\scripts\rocketmq-outage.ps1 -Action Resume'
    return
}

Write-Host 'Restoring TideBid middleware and validating the persisted RocketMQ topology...'
& (Join-Path $PSScriptRoot 'infra-up.ps1') -EnvFile $EnvFile
& (Join-Path $PSScriptRoot 'check-rocketmq-topology.ps1') -EnvFile $EnvFile
Write-Host '[PASS] RocketMQ Broker, Proxy and topology are ready; existing volumes were preserved.'

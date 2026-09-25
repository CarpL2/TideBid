[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [string]$GatewayBaseUri = 'http://127.0.0.1:9000',

    [Parameter()]
    [switch]$AssertHealthy
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

function Test-HttpHealth {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $body = Invoke-RestMethod -Uri $Uri -TimeoutSec 2
        return [pscustomobject]@{ State = if ([string]$body.status -eq 'UP') { 'UP' } else { 'DOWN' }; Detail = [string]$body.status }
    } catch {
        return [pscustomobject]@{ State = 'DOWN'; Detail = 'unreachable' }
    }
}

function Invoke-ComposeJson {
    param([Parameter(Mandatory = $true)][string[]]$Services)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) { throw 'Docker CLI was not found.' }
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw "Environment file does not exist: $EnvFile" }
    $output = @(& $docker.Source compose --env-file $EnvFile -f $composeFile ps --format json @Services)
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose status query failed.' }
    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $output) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        try {
            $parsed = [string]$line | ConvertFrom-Json
            foreach ($item in @($parsed)) { $items.Add($item) }
        } catch { }
    }
    return $items
}

function Read-DotEnvValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { return '' }
    foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        if ($line.Substring(0, $separator).Trim() -cne $Name) { continue }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        return $value
    }
    return ''
}

$gateway = $GatewayBaseUri.TrimEnd('/')
$applications = [ordered]@{
    gateway = "$gateway/actuator/health"
    account = 'http://127.0.0.1:9101/actuator/health'
    auction = 'http://127.0.0.1:9102/actuator/health'
    trade = 'http://127.0.0.1:9103/actuator/health'
    realtime = 'http://127.0.0.1:9104/actuator/health'
    ai = 'http://127.0.0.1:9105/actuator/health'
}
$failed = [System.Collections.Generic.List[string]]::new()
Write-Host 'TideBid application status:'
foreach ($entry in $applications.GetEnumerator()) {
    $health = Test-HttpHealth -Uri $entry.Value
    Write-Host ('  {0,-9} {1,-4} {2}' -f $entry.Key, $health.State, $entry.Value)
    if ($health.State -ne 'UP') { $failed.Add("application:$($entry.Key)") }
}

Write-Host 'TideBid middleware status:'
foreach ($item in @(Invoke-ComposeJson -Services @('redis', 'rocketmq-broker', 'rocketmq-proxy'))) {
    $service = [string]$item.Service
    $state = [string]$item.State
    $health = [string]$item.Health
    $display = if ([string]::IsNullOrWhiteSpace($health)) { $state } else { "$state/$health" }
    Write-Host ('  {0,-16} {1}' -f $service, $display)
    if ($state -ne 'running' -or ($health -and $health -ne 'healthy')) { $failed.Add("middleware:$service") }
}

$internalToken = Read-DotEnvValue -Name 'TIDEBID_INTERNAL_SERVICE_TOKEN'
if ([string]::IsNullOrWhiteSpace($internalToken)) {
    Write-Host '  realtime-runtime unavailable (TIDEBID_INTERNAL_SERVICE_TOKEN is not configured)'
    if ($AssertHealthy) { $failed.Add('realtime-runtime:token') }
} else {
    try {
        $runtime = Invoke-RestMethod -Uri 'http://127.0.0.1:9104/internal/realtime/status' `
            -Headers @{ 'X-TideBid-Internal-Token' = $internalToken } -TimeoutSec 2
        $runtimeValues = @(
            [int]$runtime.connections,
            [int]$runtime.subscriptions,
            [int]$runtime.syncingSubscriptions
        )
        if ($runtimeValues | Where-Object { $_ -lt 0 }) { throw 'runtime status contained a negative count.' }
        $redisState = if ([bool]$runtime.redisListenerRunning) { 'UP' } else { 'DOWN' }
        $rocketState = if ([bool]$runtime.rocketMqConsumerRunning) { 'UP' } else { 'DOWN' }
        Write-Host ('  {0,-16} status={1} connections={2} subscriptions={3} syncing={4} redis={5} rocketmq={6}' -f `
                'realtime-runtime', [string]$runtime.status, [int]$runtime.connections,
                [int]$runtime.subscriptions, [int]$runtime.syncingSubscriptions, $redisState, $rocketState)
        if ([string]$runtime.status -ne 'UP' -or $redisState -ne 'UP' -or $rocketState -ne 'UP') {
            $failed.Add('realtime-runtime:degraded')
        }
    } catch {
        Write-Host '  realtime-runtime DOWN/unreachable'
        $failed.Add('realtime-runtime')
    }
}

if ($AssertHealthy) {
    & (Join-Path $PSScriptRoot 'check-rocketmq-topology.ps1') -EnvFile $EnvFile
    if ($failed.Count -gt 0) { throw "TideBid status check failed: $($failed -join ', ')" }
    Write-Host '[PASS] TideBid applications and Realtime middleware are healthy.'
} elseif ($failed.Count -gt 0) {
    Write-Host "[WARN] Unhealthy or unreachable components: $($failed -join ', ')"
}

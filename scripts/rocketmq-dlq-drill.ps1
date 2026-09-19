[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 180,

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $AcknowledgeImpact) {
    throw 'This drill publishes one invalid local message and temporarily shortens one retry policy. Re-run with -AcknowledgeImpact.'
}

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

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}
$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile, 'exec', '-T',
    'rocketmq-broker', 'sh', 'mqadmin')
$nameserver = 'rocketmq-nameserver:9876'
$cluster = 'TideBidLocalCluster'
$topic = 'tidebid-trade-events'
$tag = 'seller.credit-requested'
$group = 'tidebid-account-credit-v1'
$dlqTopic = "%DLQ%$group"
$shortRetryPolicy = '{"type":"CUSTOMIZED","customizedRetryPolicy":{"next":[1000,2000]}}'
$eventId = [Guid]::NewGuid().ToString()

function Invoke-MqAdmin {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter()][switch]$AllowFailure
    )

    $output = @(& $script:docker.Source @($script:compose + $Arguments) 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "RocketMQ mqadmin command failed with exit code $exitCode."
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
}

$drillFailure = $null
try {
    $before = Invoke-MqAdmin -Arguments @('getConsumerConfig', '-n', $nameserver, '-g', $group)
    if (-not (($before.Output -join "`n") -match 'retryMaxTimes\s*=\s*16')) {
        throw "Consumer group $group is not at the required retryMaxTimes=16 baseline."
    }

    Invoke-MqAdmin -Arguments @(
        'updateSubGroup', '-n', $nameserver, '-c', $cluster, '-g', $group,
        '-s', 'true', '-d', 'false', '-m', 'false', '-o', 'false', '-q', '1',
        '-r', '2', '-a', 'true', '-p', $shortRetryPolicy
    ) | Out-Null
    $temporary = Invoke-MqAdmin -Arguments @('getConsumerConfig', '-n', $nameserver, '-g', $group)
    $temporaryText = $temporary.Output -join "`n"
    if ($temporaryText -notmatch 'retryMaxTimes\s*=\s*2' -or
        $temporaryText -notmatch 'next=\[1000, 2000\]') {
        throw 'Temporary retry policy was not applied exactly as requested.'
    }

    $sent = Invoke-MqAdmin -Arguments @(
        'sendMessage', '-n', $nameserver, '-t', $topic, '-c', $tag,
        '-k', $eventId, '-p', '{invalid-json'
    )
    $messageLine = @($sent.Output | Where-Object { $_ -match '^tidebid-broker\s+\d+\s+SEND_OK\s+([A-Fa-f0-9]+)\s*$' })
    if ($messageLine.Count -ne 1 -or $messageLine[0] -notmatch '([A-Fa-f0-9]+)\s*$') {
        throw 'RocketMQ did not return exactly one SEND_OK message ID.'
    }
    $messageId = $Matches[1]
    Write-Host "Poison message accepted: eventId=$eventId messageId=$messageId"

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $dlqRecord = $null
    do {
        Start-Sleep -Seconds 2
        $query = Invoke-MqAdmin -Arguments @(
            'queryMsgByKey', '-n', $nameserver, '-t', $dlqTopic, '-k', $eventId, '-m', '8'
        ) -AllowFailure
        if ($query.ExitCode -eq 0) {
            $records = @($query.Output | Where-Object {
                $_ -match '^[A-Fa-f0-9]+\s+\d+\s+\d+\s*$'
            })
            if ($records.Count -eq 1 -and $records[0] -match "^$([regex]::Escape($messageId))\s+") {
                $dlqRecord = $records[0]
                break
            }
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($null -eq $dlqRecord) {
        throw "Poison event $eventId did not appear in $dlqTopic within $TimeoutSeconds seconds."
    }

    $manifestPath = Join-Path $repositoryRoot '.runtime\apps\processes.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw 'Application PID manifest is missing; cannot verify Account retry logs.'
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $account = @($manifest.processes | Where-Object { [string]$_.name -eq 'account' })
    if ($account.Count -ne 1 -or -not (Test-Path -LiteralPath ([string]$account[0].stdoutLog) -PathType Leaf)) {
        throw 'Account runtime log is unavailable; cannot verify retry attempts.'
    }
    $retryLines = @(Select-String -LiteralPath ([string]$account[0].stdoutLog) -SimpleMatch $messageId |
        Where-Object { $_.Line.Contains('RocketMQ consumption will retry') })
    if ($retryLines.Count -lt 3) {
        throw "Expected at least three rejected deliveries for messageId=$messageId, found $($retryLines.Count)."
    }

    Write-Host "[PASS] Poison event reached $dlqTopic after $($retryLines.Count) rejected deliveries. eventId=$eventId messageId=$messageId"
} catch {
    $drillFailure = $_
} finally {
    try {
        Invoke-MqAdmin -Arguments @(
            'updateSubGroup', '-n', $nameserver, '-c', $cluster, '-g', $group,
            '-s', 'true', '-d', 'false', '-m', 'false', '-o', 'false', '-q', '1',
            '-r', '16', '-a', 'true'
        ) | Out-Null
        & (Join-Path $PSScriptRoot 'check-rocketmq-topology.ps1') -EnvFile $EnvFile
        if ($LASTEXITCODE -ne 0) {
            throw 'RocketMQ topology verification failed after restoring the consumer group.'
        }
        Write-Host '[PASS] Consumer group retry policy was restored to the project baseline.'
    } catch {
        throw "CRITICAL: could not restore RocketMQ consumer group $group. $($_.Exception.Message)"
    }
}

if ($null -ne $drillFailure) {
    throw $drillFailure
}

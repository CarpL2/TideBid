[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,18}$')]
    [string]$OrderId,

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(60, 300)]
    [int]$TimeoutSeconds = 180,

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $AcknowledgeImpact) {
    throw 'This drill temporarily changes local MySQL lock timeout, locks one wallet row and publishes one duplicate-intent event. Re-run with -AcknowledgeImpact.'
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
$composePrefix = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
$mysqlIds = @(& $docker.Source @($composePrefix + @('ps', '--quiet', 'mysql')))
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect the TideBid MySQL container.'
}
$mysqlIds = @($mysqlIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($mysqlIds.Count -ne 1) {
    throw "Expected one running TideBid MySQL container, found $($mysqlIds.Count)."
}
$mysqlId = [string]$mysqlIds[0]
$newEventId = [Guid]::NewGuid().ToString()
$newOutboxId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() * 100000L +
    (Get-Random -Minimum 1000 -Maximum 9999)
$lockMarker = 'rollback_drill_' + $newEventId.Replace('-', '').Substring(0, 12)
$lockJob = $null
$lockConnectionId = $null
$lockReleased = $false
$originalLockWait = $null
$drillFailure = $null

function Invoke-Sql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = @($Sql | & $script:docker.Source exec --interactive $script:mysqlId sh -c `
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --batch --raw --skip-column-names --user=root' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Consumer rollback drill SQL failed with exit code $LASTEXITCODE."
    }
    return $output
}

function Restart-Account {
    & (Join-Path $PSScriptRoot 'app-outage.ps1') `
        -Service account -Action Suspend -AcknowledgeImpact -EnvFile $EnvFile
    & (Join-Path $PSScriptRoot 'app-outage.ps1') `
        -Service account -Action Resume -EnvFile $EnvFile -StartupTimeoutSeconds 180
}

function Read-BusinessState {
    param([Parameter(Mandatory = $true)][string]$EventId)

    $sql = @"
SELECT
    t.seller_id,
    t.seller_credit_no,
    w.available_balance,
    w.version,
    (SELECT COUNT(*) FROM tidebid_account.account_inbox i
        WHERE i.consumer_name = 'tidebid-account-credit-v1' AND i.event_id = '$EventId'),
    (SELECT COUNT(*) FROM tidebid_account.wallet_credit c
        WHERE c.credit_no = t.seller_credit_no),
    (SELECT COUNT(*) FROM tidebid_account.wallet_ledger l
        WHERE l.business_no = t.seller_credit_no),
    (SELECT COUNT(*) FROM tidebid_account.account_outbox a
        WHERE a.aggregate_id = t.seller_credit_no AND a.event_type = 'seller.credited'),
    (SELECT status FROM tidebid_trade.trade_outbox o WHERE o.event_id = '$EventId')
FROM tidebid_trade.trade_order t
JOIN tidebid_account.wallet_account w ON w.user_id = t.seller_id
WHERE t.id = '$OrderId'
  AND t.status = 'PAID'
  AND t.seller_settlement_status = 'COMPLETED';
"@
    $lines = @(Invoke-Sql -Sql $sql)
    if ($lines.Count -ne 1) {
        throw "Expected one completed paid order for orderId=$OrderId, found $($lines.Count)."
    }
    $columns = $lines[0] -split "`t"
    if ($columns.Count -ne 9) {
        throw 'Consumer rollback state query returned an unexpected shape.'
    }
    return [pscustomobject]@{
        SellerId = $columns[0]
        CreditNo = $columns[1]
        Available = [decimal]::Parse($columns[2], [System.Globalization.CultureInfo]::InvariantCulture)
        WalletVersion = [long]$columns[3]
        InboxRows = [long]$columns[4]
        CreditRows = [long]$columns[5]
        LedgerRows = [long]$columns[6]
        ResultOutboxRows = [long]$columns[7]
        SourceOutboxStatus = $columns[8]
    }
}

function Assert-BusinessUnchanged {
    param(
        [Parameter(Mandatory = $true)]$Baseline,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][long]$ExpectedInboxRows
    )

    if ($Actual.SellerId -cne $Baseline.SellerId -or $Actual.CreditNo -cne $Baseline.CreditNo -or
        $Actual.Available -ne $Baseline.Available -or $Actual.WalletVersion -ne $Baseline.WalletVersion -or
        $Actual.CreditRows -ne $Baseline.CreditRows -or $Actual.LedgerRows -ne $Baseline.LedgerRows -or
        $Actual.ResultOutboxRows -ne $Baseline.ResultOutboxRows -or
        $Actual.InboxRows -ne $ExpectedInboxRows) {
        throw 'Consumer retry changed seller credit business state or produced an unexpected Inbox count.'
    }
}

try {
    $originalLines = @(Invoke-Sql -Sql 'SELECT @@GLOBAL.innodb_lock_wait_timeout;')
    if ($originalLines.Count -ne 1) {
        throw 'Could not read the original MySQL lock wait timeout.'
    }
    $originalLockWait = [int]$originalLines[0]
    if ($originalLockWait -lt 5) {
        throw "MySQL lock wait timeout is already unexpectedly low: $originalLockWait."
    }

    $sourceSql = @"
SELECT event_id
FROM tidebid_trade.trade_outbox
WHERE aggregate_id = '$OrderId'
  AND event_type = 'seller.credit-requested'
  AND status = 'PUBLISHED';
"@
    $sourceLines = @(Invoke-Sql -Sql $sourceSql)
    if ($sourceLines.Count -ne 1 -or
        $sourceLines[0] -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$') {
        throw 'Expected exactly one published seller-credit source event.'
    }
    $sourceEventId = $sourceLines[0]

    $baseline = Read-BusinessState -EventId $sourceEventId
    if ($baseline.InboxRows -ne 1 -or $baseline.CreditRows -ne 1 -or
        $baseline.LedgerRows -ne 1 -or $baseline.ResultOutboxRows -ne 1) {
        throw 'The selected order is not an exactly-once completed seller-credit baseline.'
    }

    Invoke-Sql -Sql 'SET GLOBAL innodb_lock_wait_timeout = 3;' | Out-Null
    Restart-Account

    $lockSql = @"
START TRANSACTION;
SELECT id FROM tidebid_account.wallet_account WHERE user_id = $($baseline.SellerId) FOR UPDATE;
SELECT SLEEP(180) AS $lockMarker;
COMMIT;
"@
    $lockJob = Start-Job -ScriptBlock {
        param($DockerPath, $ContainerId, $Sql)
        $Sql | & $DockerPath exec --interactive $ContainerId sh -c `
            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --batch --raw --skip-column-names --user=root'
        if ($LASTEXITCODE -ne 0) {
            throw "Wallet lock session exited with code $LASTEXITCODE."
        }
    } -ArgumentList $docker.Source, $mysqlId, $lockSql

    $lockDeadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        $lockRows = @(Invoke-Sql -Sql @"
SELECT trx_mysql_thread_id
FROM information_schema.innodb_trx
WHERE trx_state = 'RUNNING' AND trx_query LIKE '%$lockMarker%';
"@)
        if ($lockRows.Count -eq 1) {
            $lockConnectionId = [long]$lockRows[0]
            break
        }
        if ($lockJob.State -in @('Failed', 'Completed', 'Stopped')) {
            throw "Wallet lock job ended before acquiring the row lock: $($lockJob.State)."
        }
    } while ([DateTimeOffset]::UtcNow -lt $lockDeadline)
    if ($null -eq $lockConnectionId) {
        throw 'Wallet row lock was not acquired within 20 seconds.'
    }

    $cloneSql = @"
INSERT INTO tidebid_trade.trade_outbox (
    id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
    topic, tag, message_key, payload, payload_hash, deliver_at, status,
    attempt_count, next_attempt_at, lease_owner, lease_token, lease_until,
    published_at, last_error_code, created_at, updated_at
)
SELECT
    $newOutboxId, '$newEventId', aggregate_type, aggregate_id, event_type, schema_version,
    topic, tag, '$newEventId', JSON_SET(payload, '$.eventId', '$newEventId'),
    REPEAT('0', 64), UTC_TIMESTAMP(6), 'PENDING', 0,
    UTC_TIMESTAMP(6) + INTERVAL 10 MINUTE, NULL, NULL, NULL, NULL, NULL,
    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM tidebid_trade.trade_outbox
WHERE event_id = '$sourceEventId' AND status = 'PUBLISHED';
SELECT ROW_COUNT();
"@
    $cloneResult = @(Invoke-Sql -Sql $cloneSql)
    if ($cloneResult.Count -ne 1 -or [int]$cloneResult[0] -ne 1) {
        throw 'Could not clone exactly one seller-credit Outbox event.'
    }
    $payloadLines = @(Invoke-Sql -Sql "SELECT payload FROM tidebid_trade.trade_outbox WHERE event_id = '$newEventId';")
    if ($payloadLines.Count -ne 1) {
        throw 'Could not read the cloned event payload for hashing.'
    }
    $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($payloadLines[0])
    $payloadHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($payloadBytes)).ToLowerInvariant()
    $activateResult = @(Invoke-Sql -Sql @"
UPDATE tidebid_trade.trade_outbox
SET payload_hash = '$payloadHash', next_attempt_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
WHERE event_id = '$newEventId' AND status = 'PENDING' AND attempt_count = 0;
SELECT ROW_COUNT();
"@)
    if ($activateResult.Count -ne 1 -or [int]$activateResult[0] -ne 1) {
        throw 'Could not activate the cloned Outbox event after hashing.'
    }
    Write-Host "Wallet locked and duplicate-intent event activated: orderId=$OrderId eventId=$newEventId"

    $messageId = $null
    $failureDeadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
    do {
        Start-Sleep -Seconds 1
        $queryOutput = @(& $docker.Source @($composePrefix + @(
            'exec', '-T', 'rocketmq-broker', 'sh', 'mqadmin', 'queryMsgByKey',
            '-n', 'rocketmq-nameserver:9876', '-t', 'tidebid-trade-events', '-k', $newEventId, '-m', '8'
        )) 2>&1)
        if ($LASTEXITCODE -eq 0) {
            $records = @($queryOutput | Where-Object { $_ -match '^([A-Fa-f0-9]+)\s+\d+\s+\d+\s*$' })
            if ($records.Count -eq 1 -and $records[0] -match '^([A-Fa-f0-9]+)\s+') {
                $messageId = $Matches[1]
            }
        }
        if ($null -ne $messageId) {
            $manifest = Get-Content -LiteralPath (Join-Path $repositoryRoot '.runtime\apps\processes.json') -Raw |
                ConvertFrom-Json
            $account = @($manifest.processes | Where-Object { [string]$_.name -eq 'account' })
            if ($account.Count -eq 1 -and
                (Test-Path -LiteralPath ([string]$account[0].stdoutLog) -PathType Leaf)) {
                $failures = @(Select-String -LiteralPath ([string]$account[0].stdoutLog) -SimpleMatch $messageId |
                    Where-Object { $_.Line.Contains('RocketMQ consumption will retry') })
                if ($failures.Count -ge 1) {
                    break
                }
            }
        }
    } while ([DateTimeOffset]::UtcNow -lt $failureDeadline)
    if ($null -eq $messageId -or $failures.Count -lt 1) {
        throw 'The locked consumer did not return a retryable failure within 45 seconds.'
    }

    $rolledBack = Read-BusinessState -EventId $newEventId
    Assert-BusinessUnchanged -Baseline $baseline -Actual $rolledBack -ExpectedInboxRows 0
    Write-Host "[PASS] First delivery rolled back completely. eventId=$newEventId messageId=$messageId inbox=0"

    Invoke-Sql -Sql "KILL $lockConnectionId;" | Out-Null
    $lockReleased = $true
    Wait-Job -Job $lockJob -Timeout 10 | Out-Null

    $successDeadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $recovered = $null
    do {
        Start-Sleep -Seconds 1
        $recovered = Read-BusinessState -EventId $newEventId
        if ($recovered.InboxRows -eq 1 -and $recovered.SourceOutboxStatus -ceq 'PUBLISHED') {
            break
        }
    } while ([DateTimeOffset]::UtcNow -lt $successDeadline)
    if ($null -eq $recovered -or $recovered.InboxRows -ne 1 -or
        $recovered.SourceOutboxStatus -cne 'PUBLISHED') {
        throw 'Broker retry did not commit the recovered consumer transaction within the timeout.'
    }
    Assert-BusinessUnchanged -Baseline $baseline -Actual $recovered -ExpectedInboxRows 1
    Start-Sleep -Seconds 3
    $stable = Read-BusinessState -EventId $newEventId
    Assert-BusinessUnchanged -Baseline $baseline -Actual $stable -ExpectedInboxRows 1
    Write-Host "[PASS] Broker retry committed once. eventId=$newEventId messageId=$messageId inbox=1 credit=1 ledger=1"
} catch {
    $drillFailure = $_
} finally {
    if (-not $lockReleased) {
        try {
            $remainingLockIds = @(Invoke-Sql -Sql @"
SELECT trx_mysql_thread_id
FROM information_schema.innodb_trx
WHERE trx_query LIKE '%$lockMarker%';
"@)
            foreach ($remainingLockId in $remainingLockIds) {
                if ([string]$remainingLockId -match '^[0-9]+$') {
                    Invoke-Sql -Sql "KILL $remainingLockId;" | Out-Null
                }
            }
            $lockReleased = $remainingLockIds.Count -gt 0
        } catch {
            Write-Warning 'Could not explicitly kill the wallet lock connection; removing the job next.'
        }
    }
    if ($null -ne $lockJob) {
        Stop-Job -Job $lockJob -ErrorAction SilentlyContinue
        Remove-Job -Job $lockJob -Force -ErrorAction SilentlyContinue
    }
    if ($null -ne $originalLockWait) {
        try {
            Invoke-Sql -Sql "SET GLOBAL innodb_lock_wait_timeout = $originalLockWait;" | Out-Null
            Restart-Account
            $restoredLines = @(Invoke-Sql -Sql 'SELECT @@GLOBAL.innodb_lock_wait_timeout;')
            if ($restoredLines.Count -ne 1 -or [int]$restoredLines[0] -ne $originalLockWait) {
                throw 'MySQL lock wait timeout did not return to its original value.'
            }
            Write-Host "[PASS] MySQL lock wait timeout restored to $originalLockWait and Account restarted."
        } catch {
            throw "CRITICAL: could not restore MySQL/Account after the rollback drill. $($_.Exception.Message)"
        }
    }
}

if ($null -ne $drillFailure) {
    throw $drillFailure
}

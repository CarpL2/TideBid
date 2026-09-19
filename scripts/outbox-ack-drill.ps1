[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,18}$')]
    [string]$OrderId,

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(30, 300)]
    [int]$TimeoutSeconds = 120,

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $AcknowledgeImpact) {
    throw 'This drill reopens one published local Outbox lease for a controlled duplicate delivery. Re-run with -AcknowledgeImpact.'
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

function Invoke-Sql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = @($Sql | & $script:docker.Source exec --interactive $script:mysqlId sh -c `
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --batch --raw --skip-column-names --user=root' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Outbox ACK drill SQL failed with exit code $LASTEXITCODE."
    }
    return $output
}

function Read-State {
    $sql = @"
SELECT
    o.event_id,
    o.status,
    o.attempt_count,
    t.seller_credit_no,
    w.available_balance,
    w.version,
    (SELECT COUNT(*) FROM tidebid_account.account_inbox i
        WHERE i.consumer_name = 'tidebid-account-credit-v1' AND i.event_id = o.event_id),
    (SELECT COUNT(*) FROM tidebid_account.wallet_credit c
        WHERE c.credit_no = t.seller_credit_no),
    (SELECT COUNT(*) FROM tidebid_account.wallet_ledger l
        WHERE l.business_no = t.seller_credit_no)
FROM tidebid_trade.trade_outbox o
JOIN tidebid_trade.trade_order t ON t.id = CAST(o.aggregate_id AS UNSIGNED)
JOIN tidebid_account.wallet_account w ON w.user_id = t.seller_id
WHERE o.aggregate_id = '$OrderId'
  AND o.event_type = 'seller.credit-requested';
"@
    $lines = @(Invoke-Sql -Sql $sql)
    if ($lines.Count -ne 1) {
        throw "Expected one seller-credit Outbox event for orderId=$OrderId, found $($lines.Count)."
    }
    $columns = $lines[0] -split "`t"
    if ($columns.Count -ne 9) {
        throw 'Outbox ACK drill state query returned an unexpected shape.'
    }
    return [pscustomobject]@{
        EventId = $columns[0]
        Status = $columns[1]
        Attempts = [int]$columns[2]
        CreditNo = $columns[3]
        Available = [decimal]::Parse($columns[4], [System.Globalization.CultureInfo]::InvariantCulture)
        WalletVersion = [long]$columns[5]
        InboxRows = [long]$columns[6]
        CreditRows = [long]$columns[7]
        LedgerRows = [long]$columns[8]
    }
}

function Count-TopicMessagesByKey {
    param([Parameter(Mandatory = $true)][string]$EventId)

    $arguments = @($script:composePrefix + @(
        'exec', '-T', 'rocketmq-broker', 'sh', 'mqadmin', 'queryMsgByKey',
        '-n', 'rocketmq-nameserver:9876', '-t', 'tidebid-trade-events', '-k', $EventId, '-m', '64'
    ))
    $output = @(& $script:docker.Source @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not query the seller-credit event by key in RocketMQ.'
    }
    return @($output | Where-Object { $_ -match '^[A-Fa-f0-9]+\s+\d+\s+\d+\s*$' }).Count
}

$before = Read-State
if ($before.EventId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' -or
    $before.Status -cne 'PUBLISHED' -or $before.InboxRows -ne 1 -or
    $before.CreditRows -ne 1 -or $before.LedgerRows -ne 1) {
    throw 'The selected order is not a completed, once-consumed seller-credit baseline.'
}
$messagesBefore = Count-TopicMessagesByKey -EventId $before.EventId
if ($messagesBefore -lt 1) {
    throw 'The original published seller-credit message cannot be found by eventId.'
}

$reopenSql = @"
UPDATE tidebid_trade.trade_outbox
SET status = 'PUBLISHING',
    published_at = NULL,
    lease_owner = 'fault-drill',
    lease_token = UUID(),
    lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND,
    updated_at = UTC_TIMESTAMP(6)
WHERE event_id = '$($before.EventId)'
  AND status = 'PUBLISHED'
  AND attempt_count = $($before.Attempts);
SELECT ROW_COUNT();
"@
$changed = @(Invoke-Sql -Sql $reopenSql)
if ($changed.Count -ne 1 -or [int]$changed[0] -ne 1) {
    throw 'Could not reopen exactly one published Outbox lease.'
}
Write-Host "Expired published lease created: orderId=$OrderId eventId=$($before.EventId) attempts=$($before.Attempts)"

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
$after = $null
$messagesAfter = $messagesBefore
do {
    Start-Sleep -Seconds 1
    $after = Read-State
    if ($after.Status -ceq 'PUBLISHED' -and $after.Attempts -eq ($before.Attempts + 1)) {
        $messagesAfter = Count-TopicMessagesByKey -EventId $before.EventId
        if ($messagesAfter -gt $messagesBefore) {
            break
        }
    }
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if ($null -eq $after -or $after.Status -cne 'PUBLISHED' -or
    $after.Attempts -ne ($before.Attempts + 1) -or $messagesAfter -le $messagesBefore) {
    throw 'Trade did not republish and mark the expired Outbox lease within the timeout.'
}
Start-Sleep -Seconds 3
$settled = Read-State
if ($settled.Status -cne 'PUBLISHED' -or $settled.Attempts -ne ($before.Attempts + 1) -or
    $settled.EventId -cne $before.EventId -or $settled.CreditNo -cne $before.CreditNo -or
    $settled.InboxRows -ne $before.InboxRows -or $settled.CreditRows -ne $before.CreditRows -or
    $settled.LedgerRows -ne $before.LedgerRows -or $settled.Available -ne $before.Available -or
    $settled.WalletVersion -ne $before.WalletVersion) {
    throw 'Duplicate delivery changed Inbox cardinality or seller wallet state.'
}

Write-Host "[PASS] ACK/mark-loss replay was absorbed. eventId=$($before.EventId) topicCopies=$messagesBefore->$messagesAfter outboxAttempts=$($before.Attempts)->$($settled.Attempts) inbox=$($settled.InboxRows) credit=$($settled.CreditRows) ledger=$($settled.LedgerRows)"

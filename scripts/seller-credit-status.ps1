[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,18}$')]
    [string]$OrderId,

    [Parameter()]
    [ValidateSet('Observe', 'Pending', 'Completed')]
    [string]$ExpectedState = 'Observe',

    [Parameter()]
    [string]$EnvFile = ''
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

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}
$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
$mysqlIds = @(& $docker.Source @($compose + @('ps', '--quiet', 'mysql')))
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect the TideBid MySQL container.'
}
$mysqlIds = @($mysqlIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($mysqlIds.Count -ne 1) {
    throw "Expected one running TideBid MySQL container, found $($mysqlIds.Count)."
}

$sql = @"
SELECT
    o.id,
    o.status,
    o.seller_settlement_status,
    o.seller_credit_no,
    o.seller_receivable_amount,
    COALESCE(DATE_FORMAT(o.seller_credited_at, '%Y-%m-%dT%H:%i:%s.%fZ'), 'NULL'),
    (SELECT COUNT(*) FROM tidebid_account.wallet_credit c
        WHERE c.credit_no = o.seller_credit_no),
    COALESCE((SELECT SUM(c.amount) FROM tidebid_account.wallet_credit c
        WHERE c.credit_no = o.seller_credit_no), 0.00),
    (SELECT COUNT(*) FROM tidebid_account.wallet_ledger l
        WHERE l.business_no = o.seller_credit_no),
    COALESCE((SELECT SUM(l.available_delta) FROM tidebid_account.wallet_ledger l
        WHERE l.business_no = o.seller_credit_no), 0.00)
FROM tidebid_trade.trade_order o
WHERE o.id = $OrderId;
"@
$mysqlId = [string]$mysqlIds[0]
$lines = @($sql | & $docker.Source exec --interactive $mysqlId sh -c `
    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --batch --raw --skip-column-names --user=root' 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Seller credit diagnostics failed. Docker/MySQL exited with code $LASTEXITCODE."
}
if ($lines.Count -ne 1) {
    throw "Expected one trade order for orderId=$OrderId, found $($lines.Count)."
}
$columns = $lines[0] -split "`t"
if ($columns.Count -ne 10) {
    throw 'Seller credit diagnostics returned an unexpected shape.'
}

$status = $columns[1]
$settlement = $columns[2]
$creditNo = $columns[3]
$receivable = [decimal]::Parse($columns[4], [System.Globalization.CultureInfo]::InvariantCulture)
$creditedAt = $columns[5]
$creditRows = [long]$columns[6]
$creditAmount = [decimal]::Parse($columns[7], [System.Globalization.CultureInfo]::InvariantCulture)
$ledgerRows = [long]$columns[8]
$ledgerDelta = [decimal]::Parse($columns[9], [System.Globalization.CultureInfo]::InvariantCulture)

Write-Host "orderId=$OrderId status=$status settlement=$settlement creditNo=$creditNo receivable=$($receivable.ToString('F2', [System.Globalization.CultureInfo]::InvariantCulture)) creditRows=$creditRows creditAmount=$($creditAmount.ToString('F2', [System.Globalization.CultureInfo]::InvariantCulture)) ledgerRows=$ledgerRows ledgerDelta=$($ledgerDelta.ToString('F2', [System.Globalization.CultureInfo]::InvariantCulture)) creditedAt=$creditedAt"

if ($ExpectedState -eq 'Pending') {
    if ($status -cne 'PAID' -or $settlement -cne 'PENDING' -or
        [string]::IsNullOrWhiteSpace($creditNo) -or $creditedAt -cne 'NULL' -or
        $creditRows -ne 0 -or $creditAmount -ne 0 -or $ledgerRows -ne 0 -or $ledgerDelta -ne 0) {
        throw 'Seller credit is not in the expected PAID/PENDING state without durable Account credit.'
    }
    Write-Host '[PASS] Seller settlement is pending and Account has not credited the seller.'
} elseif ($ExpectedState -eq 'Completed') {
    if ($status -cne 'PAID' -or $settlement -cne 'COMPLETED' -or
        [string]::IsNullOrWhiteSpace($creditNo) -or $creditedAt -ceq 'NULL' -or
        $creditRows -ne 1 -or $creditAmount -ne $receivable -or
        $ledgerRows -ne 1 -or $ledgerDelta -ne $receivable) {
        throw 'Seller credit is not in the expected exactly-once completed state.'
    }
    Write-Host '[PASS] Seller settlement completed with one matching credit and one matching ledger entry.'
}

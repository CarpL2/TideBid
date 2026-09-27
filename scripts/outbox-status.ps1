[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [switch]$AssertHealthy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'infra\compose.yaml'
$diagnosticFile = Join-Path $PSScriptRoot 'sql\outbox-diagnostics.sql'
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

foreach ($path in @($EnvFile, $composeFile, $diagnosticFile)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required file does not exist: $path"
    }
}
$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $docker) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}

$compose = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
function Read-DotEnvEntries {
    $entries = [ordered]@{}
    foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw 'Invalid .env entry; expected NAME=value.' }
        $name = $line.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $entries.Contains($name)) {
            throw 'Invalid or duplicate environment variable name in .env.'
        }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $entries[$name] = $value
    }
    return $entries
}

$originalEnvironment = @{}
foreach ($entry in (Read-DotEnvEntries).GetEnumerator()) {
    $originalEnvironment[$entry.Key] = [pscustomobject]@{
        Existed = Test-Path -LiteralPath "Env:$($entry.Key)"
        Value = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    }
    [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
}
try {
    $mysqlIds = @(& $docker.Source @($compose + @('ps', '--quiet', 'mysql')))
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect the TideBid MySQL container.'
    }
} finally {
    foreach ($entry in $originalEnvironment.GetEnumerator()) {
        $value = if ($entry.Value.Existed) { $entry.Value.Value } else { $null }
        [Environment]::SetEnvironmentVariable($entry.Key, $value, 'Process')
    }
}
$mysqlIds = @($mysqlIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($mysqlIds.Count -ne 1) {
    throw "Expected one running TideBid MySQL container, found $($mysqlIds.Count)."
}
$mysqlId = [string]$mysqlIds[0]

function Invoke-ReadOnlySql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = @($Sql | & $script:docker.Source exec --interactive $mysqlId sh -c `
        'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --batch --raw --user=root' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Read-only MySQL diagnostics failed. Docker/MySQL exited with code $LASTEXITCODE."
    }
    return $output
}

Write-Host 'TideBid messaging and trade diagnostics (UTC; payloads and secrets are never selected):'
$diagnostics = [System.IO.File]::ReadAllText($diagnosticFile)
Invoke-ReadOnlySql -Sql $diagnostics | ForEach-Object { Write-Host $_ }

if ($AssertHealthy) {
    $healthSql = @'
SELECT
  (SELECT COUNT(*) FROM tidebid_auction.auction_outbox
    WHERE status IN ('PENDING','PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6))
  + (SELECT COUNT(*) FROM tidebid_account.account_outbox
    WHERE status IN ('PENDING','PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6))
  + (SELECT COUNT(*) FROM tidebid_trade.trade_outbox
    WHERE status IN ('PENDING','PUBLISHING') AND deliver_at <= UTC_TIMESTAMP(6)) AS due_backlog,
  (SELECT COUNT(*) FROM tidebid_auction.auction_outbox WHERE status = 'DEAD')
  + (SELECT COUNT(*) FROM tidebid_account.account_outbox WHERE status = 'DEAD')
  + (SELECT COUNT(*) FROM tidebid_trade.trade_outbox WHERE status = 'DEAD') AS dead_messages;
'@
    $healthLines = @(Invoke-ReadOnlySql -Sql $healthSql)
    if ($healthLines.Count -lt 2) {
        throw 'Outbox health query returned no data.'
    }
    $columns = $healthLines[-1] -split "`t"
    if ($columns.Count -ne 2) {
        throw 'Outbox health query returned an unexpected shape.'
    }
    $dueBacklog = [long]$columns[0]
    $deadMessages = [long]$columns[1]
    if ($dueBacklog -ne 0 -or $deadMessages -ne 0) {
        throw "Messaging health assertion failed: dueBacklog=$dueBacklog deadMessages=$deadMessages."
    }
    Write-Host '[PASS] No due Outbox backlog and no DEAD messages.'
}

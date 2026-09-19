[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9:_-]{0,63}$')]
    [string]$PaymentNo,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,18}$')]
    [string]$UserId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,18}$')]
    [string]$OrderId,

    [Parameter(Mandatory = $true)]
    [decimal]$Amount,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{8,48}$')]
    [string]$RequestId,

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $AcknowledgeImpact) {
    throw 'This drill can debit a local virtual wallet. Re-run with -AcknowledgeImpact after checking every printed smoke identifier.'
}
if ($Amount -le 0 -or [decimal]::Round($Amount, 2) -ne $Amount) {
    throw 'Amount must be positive and contain at most two decimal places.'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file does not exist: $EnvFile"
}

$values = [System.Collections.Generic.Dictionary[string, string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
        continue
    }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) {
        throw 'Invalid .env entry. Expected NAME=value without printing the sensitive value.'
    }
    $name = $line.Substring(0, $separator).Trim()
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $values.ContainsKey($name)) {
        throw 'Invalid or duplicate environment variable name in .env.'
    }
    $value = $line.Substring($separator + 1).Trim()
    if ($value.Length -ge 2 -and
        (($value.StartsWith('"') -and $value.EndsWith('"')) -or
         ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $values.Add($name, $value)
}
if (-not $values.ContainsKey('TIDEBID_INTERNAL_SERVICE_TOKEN')) {
    throw 'TIDEBID_INTERNAL_SERVICE_TOKEN is missing from .env.'
}
$token = $values['TIDEBID_INTERNAL_SERVICE_TOKEN']
if ([string]::IsNullOrWhiteSpace($token) -or $token.Length -lt 32 -or $token.Length -gt 512) {
    throw 'TIDEBID_INTERNAL_SERVICE_TOKEN must contain 32 to 512 characters; its value was not printed.'
}

$headers = @{
    'X-TideBid-Internal-Token' = $token
    'X-Request-Id' = $RequestId
}
$body = [ordered]@{
    paymentNo = $PaymentNo
    userId = $UserId
    orderId = $OrderId
    amount = $Amount
} | ConvertTo-Json
$response = Invoke-RestMethod `
    -Method Post `
    -Uri 'http://127.0.0.1:9101/internal/wallet-debits' `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $body `
    -TimeoutSec 15

if ([string]$response.code -cne 'SUCCESS' -or $null -eq $response.data) {
    throw 'Account did not return a successful debit response.'
}
$actualAmount = [decimal]::Parse(
    [string]$response.data.amount,
    [System.Globalization.NumberStyles]::Number,
    [System.Globalization.CultureInfo]::InvariantCulture
)
if ([string]$response.data.paymentNo -cne $PaymentNo -or
    [string]$response.data.userId -cne $UserId -or
    [string]$response.data.orderId -cne $OrderId -or
    $actualAmount -ne $Amount) {
    throw 'Account debit response does not match the requested payment identity or amount.'
}
if ([string]$response.data.status -cne 'SUCCEEDED') {
    throw "Account stored a definite non-success result: $([string]$response.data.status)."
}

Write-Host "[PASS] Account stored one idempotent debit. paymentNo=$PaymentNo status=SUCCEEDED amount=$($Amount.ToString('F2', [System.Globalization.CultureInfo]::InvariantCulture))"
Write-Host 'The internal token was read from .env and was not printed.'

[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(5, 120)]
    [int]$TimeoutSeconds = 30,

    [Parameter()]
    [ValidateSet(1, 2)]
    [int]$ExpectedRealtimeInstances = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

foreach ($required in @('TIDEBID_NACOS_USERNAME', 'TIDEBID_NACOS_PASSWORD')) {
    if (-not $values.ContainsKey($required) -or [string]::IsNullOrWhiteSpace($values[$required]) -or
        $values[$required].StartsWith('change-me')) {
        throw "$required must be set to a non-placeholder value; its value was not printed."
    }
}
$serverAddress = if ($values.ContainsKey('TIDEBID_NACOS_SERVER_ADDR')) {
    $values['TIDEBID_NACOS_SERVER_ADDR']
} else {
    '127.0.0.1:8848'
}
$serverAddress = $serverAddress.Trim().TrimEnd('/')
if ($serverAddress -notmatch '^https?://') {
    $serverAddress = 'http://' + $serverAddress
}
$serverUri = [Uri]$serverAddress
if (-not $serverUri.IsAbsoluteUri -or $serverUri.Scheme -notin @('http', 'https') -or
    -not [string]::IsNullOrEmpty($serverUri.Query) -or
    -not [string]::IsNullOrEmpty($serverUri.Fragment)) {
    throw 'TIDEBID_NACOS_SERVER_ADDR must be an HTTP(S) host and port.'
}
$path = $serverUri.AbsolutePath.TrimEnd('/')
if ([string]::IsNullOrEmpty($path)) {
    $path = '/nacos'
} elseif ($path -cne '/nacos') {
    throw 'TIDEBID_NACOS_SERVER_ADDR may contain no path or only /nacos.'
}
$baseUri = $serverUri.GetLeftPart([UriPartial]::Authority) + $path

$login = Invoke-RestMethod -Method Post `
    -Uri ($baseUri + '/v3/auth/user/login') `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{ username = $values['TIDEBID_NACOS_USERNAME']; password = $values['TIDEBID_NACOS_PASSWORD'] } `
    -TimeoutSec $TimeoutSeconds
if ([string]::IsNullOrWhiteSpace([string]$login.accessToken)) {
    throw 'Nacos login did not return an access token.'
}
$headers = @{ Authorization = 'Bearer ' + [string]$login.accessToken }
$expected = [ordered]@{
    'tidebid-gateway' = 9000
    'tidebid-account' = 9101
    'tidebid-auction' = 9102
    'tidebid-trade' = 9103
    'tidebid-realtime' = 9104
    'tidebid-ai' = 9105
}

foreach ($entry in $expected.GetEnumerator()) {
    $query = [System.Web.HttpUtility]::ParseQueryString('')
    $query['serviceName'] = $entry.Key
    $query['groupName'] = 'TIDEBID_GROUP'
    $query['namespaceId'] = 'tidebid-dev'
    $query['healthyOnly'] = 'false'
    $response = Invoke-RestMethod -Method Get `
        -Uri ($baseUri + '/v3/client/ns/instance/list?' + $query.ToString()) `
        -Headers $headers `
        -TimeoutSec $TimeoutSeconds
    if ([int]$response.code -ne 0) {
        throw "Nacos rejected the instance query for $($entry.Key)."
    }
    $instances = @($response.data)
    $expectedCount = if ($entry.Key -eq 'tidebid-realtime') { $ExpectedRealtimeInstances } else { 1 }
    if ($instances.Count -ne $expectedCount) {
        throw "Expected $expectedCount Nacos instance(s) for $($entry.Key), found $($instances.Count)."
    }
    foreach ($instance in $instances) {
        $allowedPorts = if ($entry.Key -eq 'tidebid-realtime' -and $ExpectedRealtimeInstances -eq 2) { @(9104, 9204) } else { @([int]$entry.Value) }
        if ([string]$instance.ip -cne '127.0.0.1' -or [int]$instance.port -notin $allowedPorts -or
            -not [bool]$instance.healthy -or -not [bool]$instance.enabled -or
            -not [bool]$instance.ephemeral -or [string]$instance.clusterName -cne 'DEFAULT' -or
            [string]$instance.metadata.'preserved.register.source' -cne 'SPRING_CLOUD') {
            throw "Nacos instance metadata for $($entry.Key) does not match the local TideBid baseline."
        }
    }
    $ports = (($instances | Sort-Object {[int]$_.port} | ForEach-Object { "127.0.0.1:$([int]$_.port)" }) -join ', ')
    Write-Host "  $($entry.Key): $ports / healthy / ephemeral"
}

Write-Host "[PASS] TideBid Java service registrations match the expected baseline (Realtime instances: $ExpectedRealtimeInstances)."

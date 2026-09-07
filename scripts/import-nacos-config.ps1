[CmdletBinding()]
param(
    [string]$EnvFile,
    [string]$ConfigDirectory,
    [ValidateRange(5, 600)]
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
}
if ([string]::IsNullOrWhiteSpace($ConfigDirectory)) {
    $ConfigDirectory = Join-Path $repositoryRoot 'infra/nacos/configs'
}

function Import-DotEnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return
    }

    foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid .env entry. Expected NAME=value."
        }

        $name = $line.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "Invalid environment variable name in .env."
        }

        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2) {
            $doubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
            $singleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
            if ($doubleQuoted -or $singleQuoted) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }

        $existing = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrEmpty($existing)) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Get-RequiredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('change-me')) {
        throw "Set $Name to a non-placeholder value in .env or the current process environment."
    }
    return $value
}

function Get-EnvironmentValueOrDefault {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$DefaultValue
    )

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value
}

function Get-NacosBaseUri {
    param([Parameter(Mandatory = $true)][string]$ServerAddress)

    $address = $ServerAddress.Trim().TrimEnd('/')
    if ($address.Contains(',')) {
        throw 'The import script requires one Nacos server address.'
    }
    if ($address -notmatch '^https?://') {
        $address = 'http://' + $address
    }

    $uri = [Uri]$address
    if (-not $uri.IsAbsoluteUri -or ($uri.Scheme -ne 'http' -and $uri.Scheme -ne 'https')) {
        throw 'TIDEBID_NACOS_SERVER_ADDR must be an HTTP(S) host and port.'
    }

    $path = $uri.AbsolutePath.TrimEnd('/')
    if ([string]::IsNullOrEmpty($path)) {
        $path = '/nacos'
    }
    elseif ($path -ne '/nacos') {
        throw 'TIDEBID_NACOS_SERVER_ADDR may contain no path or only /nacos.'
    }

    return $uri.GetLeftPart([UriPartial]::Authority) + $path
}

function Wait-NacosReady {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][int]$Timeout
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    do {
        try {
            $response = Invoke-RestMethod -Method Get `
                -Uri ($BaseUri + '/v3/admin/core/state/readiness') -TimeoutSec 3
            if ([int]$response.code -eq 0 -and [string]$response.data -eq 'ok') {
                return
            }
        }
        catch {
            # Nacos may still be starting; retry until the explicit deadline.
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "Nacos did not become ready within $Timeout seconds."
}

function Try-NacosLogin {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password
    )

    try {
        $response = Invoke-RestMethod -Method Post `
            -Uri ($BaseUri + '/v3/auth/user/login') `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ username = $Username; password = $Password } `
            -TimeoutSec 10
        if (-not [string]::IsNullOrWhiteSpace([string]$response.accessToken)) {
            return [string]$response.accessToken
        }
    }
    catch {
        return $null
    }
    return $null
}

function Invoke-NacosAdminRequest {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUri,
        [Parameter(Mandatory = $true)][string]$AccessToken,
        [Parameter(Mandatory = $true)][ValidateSet('Get', 'Post', 'Put', 'Delete')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Body
    )

    $request = @{
        Method     = $Method
        Uri        = $BaseUri + $Path
        Headers    = @{ Authorization = 'Bearer ' + $AccessToken }
        TimeoutSec = 15
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/x-www-form-urlencoded'
        $request.Body = $Body
    }

    $response = Invoke-RestMethod @request
    if ($null -eq $response -or $response.PSObject.Properties.Name -notcontains 'code' -or
        [int]$response.code -ne 0) {
        throw "Nacos Admin API rejected $Method $Path."
    }
    return $response
}

function Normalize-ConfigContent {
    param([AllowEmptyString()][string]$Content)

    if ($null -eq $Content) {
        return ''
    }
    return ($Content -replace "`r`n", "`n").TrimEnd([char[]]"`r`n")
}

Import-DotEnvFile -Path $EnvFile

$serverAddress = Get-EnvironmentValueOrDefault -Name 'TIDEBID_NACOS_SERVER_ADDR' -DefaultValue '127.0.0.1:8848'
$namespaceId = Get-EnvironmentValueOrDefault -Name 'TIDEBID_NACOS_NAMESPACE' -DefaultValue 'tidebid-dev'
$groupName = Get-EnvironmentValueOrDefault -Name 'TIDEBID_NACOS_GROUP' -DefaultValue 'TIDEBID_GROUP'
$username = Get-RequiredEnvironmentValue -Name 'TIDEBID_NACOS_USERNAME'
$password = Get-RequiredEnvironmentValue -Name 'TIDEBID_NACOS_PASSWORD'
$baseUri = Get-NacosBaseUri -ServerAddress $serverAddress

if (-not (Test-Path -LiteralPath $ConfigDirectory -PathType Container)) {
    throw "Nacos config directory does not exist: $ConfigDirectory"
}

$expectedDataIds = @(
    'tidebid-common.yml',
    'tidebid-gateway.yml',
    'tidebid-account.yml',
    'tidebid-auction.yml',
    'tidebid-trade.yml',
    'tidebid-realtime.yml',
    'tidebid-ai.yml'
)
$actualDataIds = @(Get-ChildItem -LiteralPath $ConfigDirectory -File -Filter '*.yml' |
        Select-Object -ExpandProperty Name)
$missingDataIds = @($expectedDataIds | Where-Object { $_ -notin $actualDataIds })
$unexpectedDataIds = @($actualDataIds | Where-Object { $_ -notin $expectedDataIds })
if ($missingDataIds.Count -gt 0 -or $unexpectedDataIds.Count -gt 0) {
    throw "Expected exactly seven managed Nacos .yml files. Missing=$($missingDataIds.Count), unexpected=$($unexpectedDataIds.Count)."
}

Write-Host "Waiting for Nacos at $baseUri ..."
Wait-NacosReady -BaseUri $baseUri -Timeout $TimeoutSeconds
Write-Host 'Nacos is ready.'

$accessToken = Try-NacosLogin -BaseUri $baseUri -Username $username -Password $password
if ([string]::IsNullOrWhiteSpace($accessToken)) {
    if ($username -ne 'nacos') {
        throw 'Nacos login failed. First-time administrator initialization requires TIDEBID_NACOS_USERNAME=nacos.'
    }
    try {
        $null = Invoke-RestMethod -Method Post `
            -Uri ($baseUri + '/v3/auth/user/admin') `
            -ContentType 'application/x-www-form-urlencoded' `
            -Body @{ password = $password } `
            -TimeoutSec 10
        Write-Host 'Initialized the Nacos administrator from TIDEBID_NACOS_PASSWORD.'
    }
    catch {
        throw 'Nacos login failed and first-time administrator initialization was rejected. Verify TIDEBID_NACOS_PASSWORD.'
    }
    $accessToken = Try-NacosLogin -BaseUri $baseUri -Username $username -Password $password
    if ([string]::IsNullOrWhiteSpace($accessToken)) {
        throw 'Nacos administrator initialization returned, but login still failed.'
    }
}
Write-Host "Authenticated to Nacos as $username."

$encodedNamespace = [Uri]::EscapeDataString($namespaceId)
$namespaceCheck = Invoke-NacosAdminRequest -BaseUri $baseUri -AccessToken $accessToken `
    -Method Get -Path ('/v3/admin/core/namespace/check?namespaceId=' + $encodedNamespace)
if (-not [bool]$namespaceCheck.data) {
    $namespaceCreate = Invoke-NacosAdminRequest -BaseUri $baseUri -AccessToken $accessToken `
        -Method Post -Path '/v3/admin/core/namespace' -Body @{
            namespaceId   = $namespaceId
            namespaceName = 'TideBid Development'
            namespaceDesc = 'Local development configuration for TideBid'
        }
    if (-not [bool]$namespaceCreate.data) {
        throw "Nacos did not create namespace $namespaceId."
    }
    Write-Host "Created namespace $namespaceId."
}
else {
    Write-Host "Namespace $namespaceId already exists."
}

$secretEnvironmentNames = @(
    'TIDEBID_NACOS_PASSWORD',
    'TIDEBID_NACOS_AUTH_TOKEN',
    'TIDEBID_NACOS_AUTH_IDENTITY_VALUE',
    'TIDEBID_MYSQL_ROOT_PASSWORD',
    'TIDEBID_ACCOUNT_DB_PASSWORD',
    'TIDEBID_AUCTION_DB_PASSWORD',
    'TIDEBID_TRADE_DB_PASSWORD',
    'TIDEBID_AI_DB_PASSWORD',
    'TIDEBID_REDIS_PASSWORD',
    'ALIBABA_CLOUD_ACCESS_KEY_SECRET',
    'DASHSCOPE_API_KEY'
)

foreach ($dataId in $expectedDataIds) {
    $filePath = Join-Path $ConfigDirectory $dataId
    $content = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $filePath).Path)
    if ([string]::IsNullOrWhiteSpace($content)) {
        throw "Nacos config is empty: $dataId"
    }
    if ($content.Contains('change-me') -or $content.Contains('BEGIN PRIVATE KEY')) {
        throw "Nacos config contains forbidden secret material or a placeholder: $dataId"
    }
    foreach ($secretName in $secretEnvironmentNames) {
        $secretValue = [Environment]::GetEnvironmentVariable($secretName, 'Process')
        if (-not [string]::IsNullOrEmpty($secretValue) -and $secretValue.Length -ge 8 -and
            $content.Contains($secretValue)) {
            throw "Nacos config contains the expanded value of ${secretName}: $dataId"
        }
    }

    $publish = Invoke-NacosAdminRequest -BaseUri $baseUri -AccessToken $accessToken `
        -Method Post -Path '/v3/admin/cs/config' -Body @{
            namespaceId = $namespaceId
            groupName   = $groupName
            dataId      = $dataId
            content     = $content
            type        = 'yaml'
            desc        = 'Managed from the TideBid repository'
        }
    if (-not [bool]$publish.data) {
        throw "Nacos did not publish $dataId."
    }

    $configQuery = '?namespaceId={0}&groupName={1}&dataId={2}' -f `
        [Uri]::EscapeDataString($namespaceId), `
        [Uri]::EscapeDataString($groupName), `
        [Uri]::EscapeDataString($dataId)
    $stored = Invoke-NacosAdminRequest -BaseUri $baseUri -AccessToken $accessToken `
        -Method Get -Path ('/v3/admin/cs/config' + $configQuery)
    if ((Normalize-ConfigContent -Content ([string]$stored.data.content)) -ne
        (Normalize-ConfigContent -Content $content)) {
        throw "Nacos read-back content differs for $dataId."
    }
    Write-Host "Published and verified $dataId."
}

$namespaceDetails = Invoke-NacosAdminRequest -BaseUri $baseUri -AccessToken $accessToken `
    -Method Get -Path ('/v3/admin/core/namespace?namespaceId=' + $encodedNamespace)
$configCount = [int]$namespaceDetails.data.configCount
Write-Host "Imported and verified $($expectedDataIds.Count) Data IDs in $namespaceId/$groupName (namespace config count: $configCount)."

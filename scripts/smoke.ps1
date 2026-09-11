[CmdletBinding()]
param(
    [Parameter()]
    [string]$GatewayBaseUri = 'http://127.0.0.1:9000',

    [Parameter()]
    [ValidateRange(1, 60)]
    [int]$RequestTimeoutSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:currentStep = 'initialization'
$script:currentTraceId = 'unavailable'
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

function Resolve-GatewayBaseUri {
    param([Parameter(Mandatory = $true)][string]$Value)

    $parsed = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$parsed) -or
        ($parsed.Scheme -ne 'http' -and $parsed.Scheme -ne 'https')) {
        throw 'GatewayBaseUri must be an absolute HTTP or HTTPS URI.'
    }
    if (-not [string]::IsNullOrEmpty($parsed.Query) -or -not [string]::IsNullOrEmpty($parsed.Fragment)) {
        throw 'GatewayBaseUri must not contain a query string or fragment.'
    }
    return $Value.TrimEnd('/')
}

function New-StepTraceId {
    param([Parameter(Mandatory = $true)][string]$Step)

    return "smoke-$Step-$([Guid]::NewGuid().ToString('N').Substring(0, 16))"
}

function Convert-ResponseContentToText {
    param([Parameter(Mandatory = $true)]$Content)

    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Get-HttpFailureDetails {
    param([Parameter(Mandatory = $true)]$Failure)

    $status = 'unavailable'
    if ($null -ne $Failure.Exception.Response -and $null -ne $Failure.Exception.Response.StatusCode) {
        $status = [string][int]($Failure.Exception.Response.StatusCode)
    }

    $code = 'unavailable'
    $message = $Failure.Exception.Message
    $errorBody = ''
    if ($null -ne $Failure.ErrorDetails) {
        $errorBody = [string]($Failure.ErrorDetails.Message)
    }
    if (-not [string]::IsNullOrWhiteSpace($errorBody)) {
        try {
            $parsedBody = $errorBody | ConvertFrom-Json
            if ($null -ne $parsedBody.PSObject.Properties['traceId'] -and
                -not [string]::IsNullOrWhiteSpace([string]($parsedBody.traceId))) {
                $script:currentTraceId = [string]($parsedBody.traceId)
            }
            if ($null -ne $parsedBody.PSObject.Properties['code']) {
                $code = [string]($parsedBody.code)
            }
            if ($null -ne $parsedBody.PSObject.Properties['message'] -and
                -not [string]::IsNullOrWhiteSpace([string]($parsedBody.message))) {
                $message = [string]($parsedBody.message)
            }
        } catch {
            # Keep the transport exception; never print an arbitrary response body.
        }
    }
    return "HTTP status=$status code=$code message=$message"
}

function Invoke-SmokeRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Step,
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$ExpectedStatus,
        [Parameter()][hashtable]$Headers = @{},
        [Parameter()]$Body,
        [Parameter()][switch]$ApiEnvelope
    )

    $script:currentStep = $Step
    $script:currentTraceId = New-StepTraceId -Step $Step
    $requestHeaders = @{}
    foreach ($entry in $Headers.GetEnumerator()) {
        $requestHeaders[$entry.Key] = $entry.Value
    }
    $requestHeaders['X-Trace-Id'] = $script:currentTraceId

    $request = @{
        UseBasicParsing = $true
        Method = $Method
        Uri = "$GatewayBaseUri$Path"
        Headers = $requestHeaders
        TimeoutSec = $RequestTimeoutSeconds
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json -Compress
    }

    try {
        $response = Invoke-WebRequest @request
    } catch {
        $details = Get-HttpFailureDetails -Failure $_
        throw "request failed: $details"
    }

    if ([int]($response.StatusCode) -ne $ExpectedStatus) {
        throw "expected HTTP $ExpectedStatus but received $([int]($response.StatusCode))"
    }
    try {
        $responseText = Convert-ResponseContentToText -Content $response.Content
        $responseBody = $responseText | ConvertFrom-Json
    } catch {
        throw 'response body is not valid JSON'
    }

    $responseTraceId = [string]($response.Headers['X-Trace-Id'])
    if ($responseTraceId -ne $script:currentTraceId) {
        throw "response X-Trace-Id did not preserve the request trace ID (actual=$responseTraceId)"
    }
    if ($ApiEnvelope) {
        foreach ($property in @('code', 'message', 'data', 'traceId')) {
            if ($null -eq $responseBody.PSObject.Properties[$property]) {
                throw "API response is missing property '$property'"
            }
        }
        if ([string]($responseBody.code) -ne 'SUCCESS') {
            throw "expected API code SUCCESS but received $([string]($responseBody.code))"
        }
        if ([string]($responseBody.traceId) -ne $script:currentTraceId) {
            throw "response body traceId did not preserve the request trace ID (actual=$([string]($responseBody.traceId)))"
        }
        if ($null -eq $responseBody.data) {
            throw 'successful API response has no data'
        }
    }

    Write-Host "[PASS] $Step traceId=$script:currentTraceId"
    return $responseBody
}

function Assert-Value {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function ConvertTo-InvariantDecimal {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$FieldName
    )

    try {
        $text = [Convert]::ToString($Value, $invariantCulture)
        return [decimal]::Parse($text, [System.Globalization.NumberStyles]::Number, $invariantCulture)
    } catch {
        throw "$FieldName is not a valid decimal amount"
    }
}

try {
    $GatewayBaseUri = Resolve-GatewayBaseUri -Value $GatewayBaseUri
    $suffix = [Guid]::NewGuid().ToString('N').Substring(0, 16)
    $canonicalUsername = "smoke_$suffix"
    $submittedUsername = $canonicalUsername.ToUpperInvariant()
    $nickname = "Smoke $($suffix.Substring(0, 8))"
    $password = "Smoke-$([Guid]::NewGuid().ToString('N').Substring(0, 20))!"

    $health = Invoke-SmokeRequest `
        -Step 'gateway-health' `
        -Method GET `
        -Path '/actuator/health' `
        -ExpectedStatus 200
    Assert-Value -Condition ([string]($health.status) -eq 'UP') -Message 'gateway health status is not UP'

    $register = Invoke-SmokeRequest `
        -Step 'register' `
        -Method POST `
        -Path '/api/auth/register' `
        -ExpectedStatus 201 `
        -Headers @{ 'X-Request-Id' = "smoke-register-$($suffix.Substring(0, 16))" } `
        -Body @{ username = $submittedUsername; password = $password; nickname = $nickname } `
        -ApiEnvelope
    Assert-Value -Condition ($register.data.userId -is [string]) -Message 'registration userId must be a JSON string to preserve 64-bit precision'
    Assert-Value -Condition ([long]($register.data.userId) -gt 0) -Message 'registration returned an invalid userId'
    Assert-Value -Condition ([string]($register.data.username) -ceq $canonicalUsername) -Message 'registration did not normalize the username to lowercase'
    Assert-Value -Condition ([string]($register.data.nickname) -ceq $nickname) -Message 'registration nickname does not match the submitted nickname'
    $registeredRoles = @($register.data.roles | ForEach-Object { [string]$_ } | Sort-Object)
    Assert-Value -Condition ($registeredRoles.Count -eq 1 -and $registeredRoles[0] -ceq 'USER') -Message 'registration did not return exactly the USER role'
    $userId = [string]($register.data.userId)

    $login = Invoke-SmokeRequest `
        -Step 'login' `
        -Method POST `
        -Path '/api/auth/login' `
        -ExpectedStatus 200 `
        -Headers @{ 'X-Request-Id' = "smoke-login-$($suffix.Substring(0, 16))" } `
        -Body @{ username = $submittedUsername; password = $password } `
        -ApiEnvelope
    Assert-Value -Condition ([string]($login.data.tokenType) -ceq 'Bearer') -Message 'login tokenType is not Bearer'
    Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]($login.data.accessToken))) -Message 'login returned an empty access token'
    Assert-Value -Condition ([long]($login.data.expiresIn) -gt 0) -Message 'login returned a non-positive token lifetime'
    $authorization = "$([string]($login.data.tokenType)) $([string]($login.data.accessToken))"

    $profile = Invoke-SmokeRequest `
        -Step 'current-user' `
        -Method GET `
        -Path '/api/users/me' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $authorization } `
        -ApiEnvelope
    Assert-Value -Condition ($profile.data.userId -is [string]) -Message 'profile userId must be a JSON string to preserve 64-bit precision'
    Assert-Value -Condition ([string]($profile.data.userId) -ceq $userId) -Message 'profile userId does not match the registered user'
    Assert-Value -Condition ([string]($profile.data.username) -ceq $canonicalUsername) -Message 'profile username does not match the registered user'
    Assert-Value -Condition ([string]($profile.data.nickname) -ceq $nickname) -Message 'profile nickname does not match the registered user'
    $profileRoles = @($profile.data.roles | ForEach-Object { [string]$_ } | Sort-Object)
    Assert-Value -Condition (($profileRoles -join ',') -ceq ($registeredRoles -join ',')) -Message 'profile roles do not match the registered roles'

    $wallet = Invoke-SmokeRequest `
        -Step 'current-wallet' `
        -Method GET `
        -Path '/api/wallets/me' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $authorization } `
        -ApiEnvelope
    Assert-Value -Condition ($wallet.data.userId -is [string]) -Message 'wallet userId must be a JSON string to preserve 64-bit precision'
    Assert-Value -Condition ([string]($wallet.data.userId) -ceq $userId) -Message 'wallet userId does not match the registered user'
    $availableBalance = ConvertTo-InvariantDecimal -Value $wallet.data.availableBalance -FieldName 'availableBalance'
    $frozenBalance = ConvertTo-InvariantDecimal -Value $wallet.data.frozenBalance -FieldName 'frozenBalance'
    Assert-Value -Condition ($availableBalance -eq [decimal]10000.00) -Message "availableBalance must be 10000.00 (actual=$availableBalance)"
    Assert-Value -Condition ($frozenBalance -eq [decimal]0.00) -Message "frozenBalance must be 0.00 (actual=$frozenBalance)"

    Write-Host "TideBid smoke test passed for $canonicalUsername. availableBalance=$($availableBalance.ToString('F2', $invariantCulture)) frozenBalance=$($frozenBalance.ToString('F2', $invariantCulture))."
} catch {
    throw "TideBid smoke test failed at step '$script:currentStep' traceId=$script:currentTraceId. $($_.Exception.Message)"
}

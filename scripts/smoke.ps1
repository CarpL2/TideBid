[CmdletBinding()]
param(
    [Parameter()]
    [string]$GatewayBaseUri = 'http://127.0.0.1:9000',

    [Parameter()]
    [ValidateRange(1, 60)]
    [int]$RequestTimeoutSeconds = 10,

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [switch]$AuctionCore,

    [Parameter()]
    [ValidateRange(60, 600)]
    [int]$AuctionOpenTimeoutSeconds = 240
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:currentStep = 'initialization'
$script:currentTraceId = 'unavailable'
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

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
        [Parameter()][switch]$ApiEnvelope,
        [Parameter()][switch]$Quiet
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

    if (-not $Quiet) {
        Write-Host "[PASS] $Step traceId=$script:currentTraceId"
    }
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

function Read-SmokeEnvironment {
    if ([string]::IsNullOrWhiteSpace($script:EnvFile)) {
        $script:EnvFile = Join-Path $repositoryRoot '.env'
    } elseif (-not [System.IO.Path]::IsPathRooted($script:EnvFile)) {
        $script:EnvFile = Join-Path $repositoryRoot $script:EnvFile
    }
    $script:EnvFile = [System.IO.Path]::GetFullPath($script:EnvFile)
    if (-not (Test-Path -LiteralPath $script:EnvFile -PathType Leaf)) {
        throw 'AuctionCore smoke test requires an environment file; copy .env.example to .env first.'
    }

    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $script:EnvFile).Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw 'Invalid .env entry. Expected NAME=value without printing the sensitive value.'
        }
        $name = $line.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw 'Invalid environment variable name in .env.'
        }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    return $values
}

function Get-SmokeEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$Values
    )

    $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if (-not [string]::IsNullOrEmpty($processValue)) {
        return $processValue
    }
    if ($Values.ContainsKey($Name)) {
        return [string]$Values[$Name]
    }
    return ''
}

function Get-AuctionCoreSettings {
    $values = Read-SmokeEnvironment
    $ossEnabled = Get-SmokeEnvironmentValue -Name 'TIDEBID_OSS_ENABLED' -Values $values
    if ($ossEnabled -ine 'true') {
        throw 'AuctionCore smoke test requires TIDEBID_OSS_ENABLED=true before the applications are started.'
    }
    $adminEnabled = Get-SmokeEnvironmentValue -Name 'TIDEBID_DEV_ADMIN_ENABLED' -Values $values
    if ($adminEnabled -ine 'true') {
        throw 'AuctionCore smoke test requires TIDEBID_DEV_ADMIN_ENABLED=true before the applications are started.'
    }
    $adminUsername = (Get-SmokeEnvironmentValue -Name 'TIDEBID_DEV_ADMIN_USERNAME' -Values $values).Trim()
    $adminPassword = Get-SmokeEnvironmentValue -Name 'TIDEBID_DEV_ADMIN_PASSWORD' -Values $values
    if ([string]::IsNullOrWhiteSpace($adminUsername) -or [string]::IsNullOrWhiteSpace($adminPassword) -or
        $adminPassword.StartsWith('change-me')) {
        throw 'AuctionCore smoke test requires non-placeholder development administrator credentials; values were not printed.'
    }
    return [pscustomobject]@{ AdminUsername = $adminUsername; AdminPassword = $adminPassword }
}

function Register-SmokeActor {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Suffix
    )

    $username = "smoke_${Label}_$Suffix"
    $password = "Smoke-$([Guid]::NewGuid().ToString('N').Substring(0, 20))!"
    $response = Invoke-SmokeRequest `
        -Step "register-$Label" `
        -Method POST `
        -Path '/api/auth/register' `
        -ExpectedStatus 201 `
        -Headers @{ 'X-Request-Id' = "smoke-register-$Label-$($Suffix.Substring(0, 8))" } `
        -Body @{ username = $username; password = $password; nickname = "Smoke $Label" } `
        -ApiEnvelope
    Assert-Value -Condition ($response.data.userId -is [string]) -Message "$Label userId must be a JSON string"
    return [pscustomobject]@{
        UserId = [string]$response.data.userId
        Username = $username
        Password = $password
    }
}

function Login-SmokeActor {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password
    )

    $response = Invoke-SmokeRequest `
        -Step "login-$Label" `
        -Method POST `
        -Path '/api/auth/login' `
        -ExpectedStatus 200 `
        -Headers @{ 'X-Request-Id' = "smoke-login-$Label-$([Guid]::NewGuid().ToString('N').Substring(0, 8))" } `
        -Body @{ username = $Username; password = $Password } `
        -ApiEnvelope
    Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]$response.data.accessToken)) `
        -Message "$Label login returned an empty access token"
    return "$([string]$response.data.tokenType) $([string]$response.data.accessToken)"
}

function Send-SmokeImageToOss {
    param(
        [Parameter(Mandatory = $true)][string]$UploadUrl,
        [Parameter(Mandatory = $true)]$RequiredHeaders,
        [Parameter(Mandatory = $true)][byte[]]$Content
    )

    $script:currentStep = 'oss-put'
    $headers = @{}
    $contentType = 'image/png'
    foreach ($property in $RequiredHeaders.PSObject.Properties) {
        $normalizedName = $property.Name.ToLowerInvariant()
        if ($normalizedName -eq 'content-length' -or $normalizedName -eq 'host') {
            continue
        }
        if ($normalizedName -eq 'content-type') {
            $contentType = [string]$property.Value
            continue
        }
        $headers[$property.Name] = [string]$property.Value
    }
    try {
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Method Put `
            -Uri $UploadUrl `
            -Headers $headers `
            -ContentType $contentType `
            -Body $Content `
            -TimeoutSec $RequestTimeoutSeconds
    } catch {
        throw 'OSS PUT failed; the signed URL and response body were suppressed.'
    }
    if ([int]$response.StatusCode -notin @(200, 201)) {
        throw "OSS PUT returned unexpected HTTP status $([int]$response.StatusCode); the signed URL was suppressed."
    }
    Write-Host "[PASS] oss-put traceId=$script:currentTraceId"
}

function Wait-RegistrationFinal {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$RegistrationId,
        [Parameter(Mandatory = $true)][string]$Authorization
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
    do {
        $result = Invoke-SmokeRequest `
            -Step "registration-$Label-status" `
            -Method GET `
            -Path "/api/registrations/$RegistrationId" `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $Authorization } `
            -ApiEnvelope `
            -Quiet
        $status = [string]$result.data.status
        if ($status -eq 'REGISTERED' -or $status -eq 'FAILED') {
            Write-Host "[PASS] registration-$Label-final traceId=$script:currentTraceId status=$status"
            return $result.data
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "$Label registration did not reach a final state within 30 seconds"
}

function Wait-AuctionOpen {
    param(
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][string]$Authorization
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($AuctionOpenTimeoutSeconds)
    do {
        $result = Invoke-SmokeRequest `
            -Step 'auction-open-status' `
            -Method GET `
            -Path "/api/auctions/$AuctionId" `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $Authorization } `
            -ApiEnvelope `
            -Quiet
        $status = [string]$result.data.sessionStatus
        if ($status -eq 'OPEN') {
            Write-Host "[PASS] auction-open traceId=$script:currentTraceId"
            return $result.data
        }
        if ($status -eq 'AWAITING_CLOSE') {
            throw 'auction reached AWAITING_CLOSE before the smoke bids were submitted'
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "auction did not open within $AuctionOpenTimeoutSeconds seconds"
}

try {
    $GatewayBaseUri = Resolve-GatewayBaseUri -Value $GatewayBaseUri
    $auctionCoreSettings = if ($AuctionCore) { Get-AuctionCoreSettings } else { $null }
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

    if (-not $AuctionCore) {
        Write-Host "TideBid foundation smoke test passed for $canonicalUsername. availableBalance=$($availableBalance.ToString('F2', $invariantCulture)) frozenBalance=$($frozenBalance.ToString('F2', $invariantCulture))."
        return
    }

    $sellerAuthorization = $authorization
    $buyerOne = Register-SmokeActor -Label 'buyer1' -Suffix $suffix
    $buyerTwo = Register-SmokeActor -Label 'buyer2' -Suffix $suffix
    $buyerOneAuthorization = Login-SmokeActor `
        -Label 'buyer1' -Username $buyerOne.Username -Password $buyerOne.Password
    $buyerTwoAuthorization = Login-SmokeActor `
        -Label 'buyer2' -Username $buyerTwo.Username -Password $buyerTwo.Password
    $adminAuthorization = Login-SmokeActor `
        -Label 'admin' `
        -Username $auctionCoreSettings.AdminUsername `
        -Password $auctionCoreSettings.AdminPassword
    $adminProfile = Invoke-SmokeRequest `
        -Step 'admin-profile' `
        -Method GET `
        -Path '/api/users/me' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $adminAuthorization } `
        -ApiEnvelope
    $adminRoles = @($adminProfile.data.roles | ForEach-Object { [string]$_ })
    Assert-Value -Condition ($adminRoles -contains 'ADMIN') -Message 'configured development administrator does not have ADMIN role'

    $imageBytes = [Convert]::FromBase64String(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='
    )
    $checksum = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($imageBytes)).ToLowerInvariant()
    $uploadIntent = Invoke-SmokeRequest `
        -Step 'create-upload-intent' `
        -Method POST `
        -Path '/api/assets/upload-intents' `
        -ExpectedStatus 201 `
        -Headers @{
            Authorization = $sellerAuthorization
            'X-Request-Id' = "smoke-upload-$($suffix.Substring(0, 12))"
        } `
        -Body @{
            originalFilename = "smoke-$suffix.png"
            contentType = 'image/png'
            contentLength = $imageBytes.Length
            checksumSha256 = $checksum
        } `
        -ApiEnvelope
    Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]$uploadIntent.data.objectKey)) `
        -Message 'upload intent returned an empty Object Key'
    Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]$uploadIntent.data.uploadUrl)) `
        -Message 'upload intent returned an empty signed URL'
    Send-SmokeImageToOss `
        -UploadUrl ([string]$uploadIntent.data.uploadUrl) `
        -RequiredHeaders $uploadIntent.data.requiredHeaders `
        -Content $imageBytes

    $startAt = [DateTimeOffset]::UtcNow.AddMinutes(2)
    $endAt = $startAt.AddMinutes(10)
    $draft = Invoke-SmokeRequest `
        -Step 'create-auction-draft' `
        -Method POST `
        -Path '/api/assets' `
        -ExpectedStatus 201 `
        -Headers @{
            Authorization = $sellerAuthorization
            'X-Request-Id' = "smoke-draft-$($suffix.Substring(0, 12))"
        } `
        -Body @{
            title = "Smoke auction $($suffix.Substring(0, 8))"
            description = 'Stage 02 end-to-end smoke auction with two registered bidders.'
            category = 'COLLECTIBLES'
            itemCondition = 'GOOD'
            startPrice = '100.00'
            bidIncrement = '10.00'
            depositAmount = '50.00'
            startAt = $startAt.ToString('o')
            endAt = $endAt.ToString('o')
            imageObjectKeys = @([string]$uploadIntent.data.objectKey)
        } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$draft.data.reviewStatus -eq 'DRAFT') -Message 'new item is not DRAFT'
    Assert-Value -Condition ([string]$draft.data.sessionStatus -eq 'DRAFT') -Message 'new auction is not DRAFT'
    $itemId = [string]$draft.data.itemId
    $auctionId = [string]$draft.data.auctionId

    $submission = Invoke-SmokeRequest `
        -Step 'submit-auction' `
        -Method POST `
        -Path "/api/assets/$itemId/submit" `
        -ExpectedStatus 200 `
        -Headers @{
            Authorization = $sellerAuthorization
            'X-Request-Id' = "smoke-submit-$($suffix.Substring(0, 12))"
        } `
        -Body @{
            itemVersion = [long]$draft.data.itemVersion
            sessionVersion = [long]$draft.data.sessionVersion
        } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$submission.data.reviewStatus -eq 'PENDING_REVIEW') `
        -Message 'submitted item is not PENDING_REVIEW'

    $review = Invoke-SmokeRequest `
        -Step 'approve-auction' `
        -Method POST `
        -Path "/api/admin/assets/$itemId/reviews" `
        -ExpectedStatus 200 `
        -Headers @{
            Authorization = $adminAuthorization
            'X-Request-Id' = "smoke-review-$($suffix.Substring(0, 12))"
        } `
        -Body @{
            decision = 'APPROVE'
            submissionVersion = [int]$submission.data.submissionVersion
            comment = 'Approved by stage 02 smoke verification.'
        } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$review.data.itemStatus -eq 'APPROVED') -Message 'review did not approve the item'
    Assert-Value -Condition ([string]$review.data.sessionStatus -eq 'SCHEDULED') -Message 'approved auction is not SCHEDULED'

    $registrationRequestOne = "smoke-reg1-$($suffix.Substring(0, 12))"
    $registrationOne = Invoke-SmokeRequest `
        -Step 'register-buyer1-auction' `
        -Method POST `
        -Path '/api/registrations' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $registrationRequestOne } `
        -Body @{ auctionId = $auctionId } `
        -ApiEnvelope
    $registrationOneFinal = Wait-RegistrationFinal `
        -Label 'buyer1' `
        -RegistrationId ([string]$registrationOne.data.registrationId) `
        -Authorization $buyerOneAuthorization
    Assert-Value -Condition ([string]$registrationOneFinal.status -eq 'REGISTERED') `
        -Message "buyer1 registration failed with code $([string]$registrationOneFinal.failureCode)"

    $registrationOneReplay = Invoke-SmokeRequest `
        -Step 'replay-buyer1-registration' `
        -Method POST `
        -Path '/api/registrations' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $registrationRequestOne } `
        -Body @{ auctionId = $auctionId } `
        -ApiEnvelope
    Assert-Value `
        -Condition ([string]$registrationOneReplay.data.registrationId -ceq [string]$registrationOne.data.registrationId) `
        -Message 'buyer1 registration replay returned a different registration ID'

    $registrationTwo = Invoke-SmokeRequest `
        -Step 'register-buyer2-auction' `
        -Method POST `
        -Path '/api/registrations' `
        -ExpectedStatus 200 `
        -Headers @{
            Authorization = $buyerTwoAuthorization
            'X-Request-Id' = "smoke-reg2-$($suffix.Substring(0, 12))"
        } `
        -Body @{ auctionId = $auctionId } `
        -ApiEnvelope
    $registrationTwoFinal = Wait-RegistrationFinal `
        -Label 'buyer2' `
        -RegistrationId ([string]$registrationTwo.data.registrationId) `
        -Authorization $buyerTwoAuthorization
    Assert-Value -Condition ([string]$registrationTwoFinal.status -eq 'REGISTERED') `
        -Message "buyer2 registration failed with code $([string]$registrationTwoFinal.failureCode)"

    foreach ($buyer in @(
        [pscustomobject]@{ Label = 'buyer1'; UserId = $buyerOne.UserId; Authorization = $buyerOneAuthorization },
        [pscustomobject]@{ Label = 'buyer2'; UserId = $buyerTwo.UserId; Authorization = $buyerTwoAuthorization }
    )) {
        $buyerWallet = Invoke-SmokeRequest `
            -Step "$($buyer.Label)-wallet-after-hold" `
            -Method GET `
            -Path '/api/wallets/me' `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $buyer.Authorization } `
            -ApiEnvelope
        Assert-Value -Condition ([string]$buyerWallet.data.userId -ceq [string]$buyer.UserId) `
            -Message "$($buyer.Label) wallet identity mismatch"
        $buyerAvailable = ConvertTo-InvariantDecimal $buyerWallet.data.availableBalance 'availableBalance'
        $buyerFrozen = ConvertTo-InvariantDecimal $buyerWallet.data.frozenBalance 'frozenBalance'
        Assert-Value -Condition ($buyerAvailable -eq [decimal]9950.00) `
            -Message "$($buyer.Label) available balance does not reflect exactly one deposit hold"
        Assert-Value -Condition ($buyerFrozen -eq [decimal]50.00) `
            -Message "$($buyer.Label) frozen balance does not reflect exactly one deposit hold"
    }

    $opened = Wait-AuctionOpen -AuctionId $auctionId -Authorization $buyerOneAuthorization
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $opened.minimumNextBid 'minimumNextBid') -eq [decimal]100.00) `
        -Message 'first minimum bid is not the start price'

    $buyerOneBidRequest = "smoke-bid1-$($suffix.Substring(0, 12))"
    $buyerOneBid = Invoke-SmokeRequest `
        -Step 'buyer1-bid' `
        -Method POST `
        -Path '/api/bids' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $buyerOneBidRequest } `
        -Body @{ auctionId = $auctionId; amount = '100.00' } `
        -ApiEnvelope
    Assert-Value -Condition ([long]$buyerOneBid.data.sequenceNo -eq 1) -Message 'buyer1 bid sequence is not 1'

    $buyerOneBidReplay = Invoke-SmokeRequest `
        -Step 'replay-buyer1-bid' `
        -Method POST `
        -Path '/api/bids' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $buyerOneBidRequest } `
        -Body @{ auctionId = $auctionId; amount = '100.00' } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$buyerOneBidReplay.data.bidId -ceq [string]$buyerOneBid.data.bidId) `
        -Message 'bid replay returned a different bid ID'

    $buyerTwoBid = Invoke-SmokeRequest `
        -Step 'buyer2-bid' `
        -Method POST `
        -Path '/api/bids' `
        -ExpectedStatus 200 `
        -Headers @{
            Authorization = $buyerTwoAuthorization
            'X-Request-Id' = "smoke-bid2-$($suffix.Substring(0, 12))"
        } `
        -Body @{ auctionId = $auctionId; amount = '110.00' } `
        -ApiEnvelope
    Assert-Value -Condition ([long]$buyerTwoBid.data.sequenceNo -eq 2) -Message 'buyer2 bid sequence is not 2'

    $finalDetail = Invoke-SmokeRequest `
        -Step 'verify-final-auction' `
        -Method GET `
        -Path "/api/auctions/$auctionId" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization } `
        -ApiEnvelope
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $finalDetail.data.currentPrice 'currentPrice') -eq [decimal]110.00) `
        -Message 'final current price is not 110.00'
    Assert-Value -Condition ([long]$finalDetail.data.bidCount -eq 2) -Message 'final bid count is not 2'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $finalDetail.data.minimumNextBid 'minimumNextBid') -eq [decimal]120.00) `
        -Message 'final minimum next bid is not 120.00'

    $history = Invoke-SmokeRequest `
        -Step 'verify-bid-history' `
        -Method GET `
        -Path "/api/auctions/$auctionId/bids?page=1&size=20" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization } `
        -ApiEnvelope
    Assert-Value -Condition ([long]$history.data.total -eq 2) `
        -Message 'bid replay produced a duplicate record or an expected bid is missing'
    $historyItems = @($history.data.items)
    Assert-Value -Condition ($historyItems.Count -eq 2) -Message 'bid history does not contain exactly two records'
    Assert-Value -Condition ([long]$historyItems[0].sequenceNo -eq 2 -and [bool]$historyItems[0].mine) `
        -Message 'buyer2 is not the final leading bidder in bid history'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $historyItems[0].amount 'latest bid amount') -eq [decimal]110.00) `
        -Message 'latest bid amount is not 110.00'
    Assert-Value -Condition ([long]$historyItems[1].sequenceNo -eq 1 -and -not [bool]$historyItems[1].mine) `
        -Message 'buyer1 bid history entry is missing or incorrectly exposed'

    Write-Host "TideBid auction-core smoke test passed. itemId=$itemId auctionId=$auctionId sellerId=$userId buyer1Id=$($buyerOne.UserId) buyer2Id=$($buyerTwo.UserId)."
} catch {
    throw "TideBid smoke test failed at step '$script:currentStep' traceId=$script:currentTraceId. $($_.Exception.Message)"
}

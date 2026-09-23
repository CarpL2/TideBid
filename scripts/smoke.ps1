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
    [switch]$RealtimeProxy,

    [Parameter()]
    [switch]$RealtimeMultiInstance,

    [Parameter()]
    [switch]$RealtimeRestartRecovery,

    [Parameter()]
    [switch]$RealtimeBrokerRecovery,

    [Parameter()]
    [switch]$ReliableTrade,

    [Parameter()]
    [ValidateSet('Sold', 'SoldAndUnsold', 'All')]
    [string]$ReliableTradeCoverage = 'All',

    [Parameter()]
    [ValidateRange(60, 600)]
    [int]$AuctionOpenTimeoutSeconds = 240,

    [Parameter()]
    [ValidateRange(30, 600)]
    [int]$ReliableTradeTimeoutSeconds = 180,

    [Parameter()]
    [ValidateRange(45, 600)]
    [int]$ReliableTradeAuctionDurationSeconds = 45,

    [Parameter()]
    [switch]$PauseBeforeFirstBid,

    [Parameter()]
    [switch]$PauseAfterSecondBid,

    [Parameter()]
    [switch]$PauseAfterTimeoutPending,

    [Parameter()]
    [switch]$PauseBeforeSoldPayment,

    [Parameter()]
    [switch]$PauseAfterSoldPaymentUnknown,

    [Parameter()]
    [switch]$PauseAfterSoldPaymentPendingSettlement
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:currentStep = 'initialization'
$script:currentTraceId = 'unavailable'
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function ConvertTo-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $sha256.ComputeHash($Bytes)
    } finally {
        $sha256.Dispose()
    }
    return ([BitConverter]::ToString($digest).Replace('-', '')).ToLowerInvariant()
}

if ($ReliableTrade) {
    $AuctionCore = $true
}
if ($RealtimeProxy -or $RealtimeMultiInstance -or $RealtimeRestartRecovery -or $RealtimeBrokerRecovery) {
    $AuctionCore = $true
}
if ($PauseAfterTimeoutPending -and (-not $ReliableTrade -or $ReliableTradeCoverage -ne 'All')) {
    throw 'PauseAfterTimeoutPending requires -ReliableTrade -ReliableTradeCoverage All.'
}
if (($PauseBeforeSoldPayment -or $PauseAfterSoldPaymentUnknown -or $PauseAfterSoldPaymentPendingSettlement) -and
    -not $ReliableTrade) {
    throw 'Sold-payment fault checkpoints require -ReliableTrade.'
}
if ($PauseAfterSoldPaymentUnknown -and -not $PauseBeforeSoldPayment) {
    throw 'PauseAfterSoldPaymentUnknown requires PauseBeforeSoldPayment so Account can be suspended first.'
}
if ($PauseAfterSoldPaymentPendingSettlement -and -not $PauseBeforeSoldPayment) {
    throw 'PauseAfterSoldPaymentPendingSettlement requires PauseBeforeSoldPayment so Broker can be suspended first.'
}
if ($PauseAfterSoldPaymentUnknown -and $PauseAfterSoldPaymentPendingSettlement) {
    throw 'Unknown-payment and pending-settlement fault checkpoints cannot run in the same smoke flow.'
}

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

function Wait-SmokeCheckpoint {
    param([Parameter(Mandatory = $true)][string]$Prompt)

    $confirmation = Read-Host "$Prompt Type CONTINUE to proceed"
    if ($confirmation -cne 'CONTINUE') {
        throw 'Fault-drill checkpoint was not explicitly confirmed with CONTINUE.'
    }
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
    $responseProperty = $Failure.Exception.PSObject.Properties['Response']
    if ($null -ne $responseProperty -and $null -ne $responseProperty.Value) {
        $statusProperty = $responseProperty.Value.PSObject.Properties['StatusCode']
        if ($null -ne $statusProperty -and $null -ne $statusProperty.Value) {
            $status = [string][int]($statusProperty.Value)
        }
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

function Invoke-RealtimeWebSocketCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][long]$LastSequenceNo
    )

    $ticketResponse = Invoke-SmokeRequest `
        -Step 'realtime-ticket' `
        -Method POST `
        -Path '/api/realtime/tickets' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $Authorization } `
        -ApiEnvelope
    $ticket = [string]$ticketResponse.data.ticket
    Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace($ticket)) -Message 'realtime ticket is empty'

    $gatewayUri = [Uri]$GatewayBaseUri
    $webSocketScheme = if ($gatewayUri.Scheme -eq 'https') { 'wss' } else { 'ws' }
    $webSocketUri = [Uri]::new(
        "${webSocketScheme}://$($gatewayUri.Authority)/ws/auctions?ticket=$([Uri]::EscapeDataString($ticket))"
    )
    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $socket.Options.SetRequestHeader('Origin', 'http://127.0.0.1:5173')
    $connectCancellation = [Threading.CancellationTokenSource]::new($RequestTimeoutSeconds * 1000)
    $receiveCancellation = [Threading.CancellationTokenSource]::new($RequestTimeoutSeconds * 1000)
    try {
        $socket.ConnectAsync($webSocketUri, $connectCancellation.Token).GetAwaiter().GetResult()
        Assert-Value -Condition ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) `
            -Message 'realtime WebSocket did not reach OPEN state'

        $requestId = New-StepTraceId -Step 'realtime-subscribe'
        $subscribe = @{
            type = 'SUBSCRIBE'
            protocolVersion = 1
            requestId = $requestId
            payload = @{ auctionId = $AuctionId; lastSequenceNo = $LastSequenceNo }
        } | ConvertTo-Json -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($subscribe)
        $socket.SendAsync(
            [ArraySegment[byte]]::new($bytes),
            [System.Net.WebSockets.WebSocketMessageType]::Text,
            $true,
            $receiveCancellation.Token
        ).GetAwaiter().GetResult()

        $receivedTypes = [System.Collections.Generic.HashSet[string]]::new()
        $buffer = New-Object byte[] 8192
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds($RequestTimeoutSeconds)
        while ([DateTimeOffset]::UtcNow -lt $deadline -and
            (-not $receivedTypes.Contains('CONNECTED') -or -not $receivedTypes.Contains('SNAPSHOT'))) {
            $messageBuffer = [IO.MemoryStream]::new()
            do {
                $result = $socket.ReceiveAsync(
                    [ArraySegment[byte]]::new($buffer), $receiveCancellation.Token
                ).GetAwaiter().GetResult()
                if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                    throw 'realtime WebSocket closed before CONNECTED and SNAPSHOT were received'
                }
                $messageBuffer.Write($buffer, 0, $result.Count)
            } while (-not $result.EndOfMessage)

            $message = [Text.Encoding]::UTF8.GetString($messageBuffer.ToArray()) | ConvertFrom-Json
            $receivedTypes.Add([string]$message.type) | Out-Null
            if ([string]$message.type -eq 'SNAPSHOT') {
                Assert-Value -Condition ([string]$message.payload.auctionId -ceq $AuctionId) `
                    -Message 'realtime snapshot auctionId does not match the smoke auction'
            }
            if ([string]$message.type -eq 'ERROR') {
                throw "realtime subscription returned an error code $([string]$message.payload.code)"
            }
        }
        Assert-Value -Condition ($receivedTypes.Contains('CONNECTED')) -Message 'realtime CONNECTED message was not received'
        Assert-Value -Condition ($receivedTypes.Contains('SNAPSHOT')) -Message 'realtime SNAPSHOT message was not received'
        Write-Host '[PASS] realtime-websocket-snapshot'
    } finally {
        if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
            try {
                $socket.CloseAsync(
                    [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                    'smoke complete',
                    [Threading.CancellationToken]::None
                ).GetAwaiter().GetResult()
            } catch {
                $socket.Abort()
            }
        }
        $receiveCancellation.Dispose()
        $connectCancellation.Dispose()
        $socket.Dispose()
    }
}

function Receive-RealtimeMessage {
    param(
        [Parameter(Mandatory = $true)][System.Net.WebSockets.ClientWebSocket]$Socket,
        [Parameter(Mandatory = $true)][int]$TimeoutMilliseconds
    )

    $cancellation = [Threading.CancellationTokenSource]::new($TimeoutMilliseconds)
    try {
        $buffer = New-Object byte[] 8192
        $messageBuffer = [IO.MemoryStream]::new()
        do {
            $result = $Socket.ReceiveAsync(
                [ArraySegment[byte]]::new($buffer), $cancellation.Token
            ).GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                throw 'Realtime WebSocket closed while waiting for the multi-instance event.'
            }
            $messageBuffer.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        return [Text.Encoding]::UTF8.GetString($messageBuffer.ToArray()) | ConvertFrom-Json
    } finally {
        $cancellation.Dispose()
    }
}

function Invoke-RealtimeMultiInstanceCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string]$AuctionId
    )

    foreach ($port in @(9104, 9204)) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
            Assert-Value -Condition ([string]$health.status -eq 'UP') `
                -Message "Realtime instance $port is not healthy"
        } catch {
            throw "Realtime multi-instance check requires healthy instances on ports 9104 and 9204. Instance $port is unavailable."
        }
    }

    $sockets = [System.Collections.Generic.List[object]]::new()
    try {
        foreach ($port in @(9104, 9204)) {
            $ticketResponse = Invoke-SmokeRequest `
                -Step "realtime-ticket-$port" `
                -Method POST `
                -Path '/api/realtime/tickets' `
                -ExpectedStatus 200 `
                -Headers @{ Authorization = $Authorization } `
                -ApiEnvelope `
                -Quiet
            $ticket = [string]$ticketResponse.data.ticket
            Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace($ticket)) `
                -Message "realtime ticket for instance $port is empty"

            $socket = [System.Net.WebSockets.ClientWebSocket]::new()
            $socket.Options.SetRequestHeader('Origin', 'http://127.0.0.1:5173')
            $connectCancellation = [Threading.CancellationTokenSource]::new($RequestTimeoutSeconds * 1000)
            try {
                $uri = [Uri]::new(
                    "ws://127.0.0.1:$port/ws/auctions?ticket=$([Uri]::EscapeDataString($ticket))"
                )
                $socket.ConnectAsync($uri, $connectCancellation.Token).GetAwaiter().GetResult()
            } finally {
                $connectCancellation.Dispose()
            }
            Assert-Value -Condition ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) `
                -Message "Realtime instance $port WebSocket did not open"
            $sockets.Add([pscustomobject]@{ Port = $port; Socket = $socket })
        }

        foreach ($entry in $sockets) {
            $requestId = New-StepTraceId -Step "realtime-subscribe-$($entry.Port)"
            $subscribe = @{
                type = 'SUBSCRIBE'
                protocolVersion = 1
                requestId = $requestId
                payload = @{ auctionId = $AuctionId; lastSequenceNo = 0 }
            } | ConvertTo-Json -Compress
            $bytes = [Text.Encoding]::UTF8.GetBytes($subscribe)
            $entry.Socket.SendAsync(
                [ArraySegment[byte]]::new($bytes),
                [System.Net.WebSockets.WebSocketMessageType]::Text,
                $true,
                [Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()
        }

        foreach ($entry in $sockets) {
            $received = @()
            while ($received.Count -lt 2 -or $received.type -notcontains 'CONNECTED' -or
                $received.type -notcontains 'SNAPSHOT') {
                $received += Receive-RealtimeMessage -Socket $entry.Socket -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            }
            Assert-Value -Condition ($received.type -contains 'CONNECTED' -and $received.type -contains 'SNAPSHOT') `
                -Message "Realtime instance $($entry.Port) did not return CONNECTED and SNAPSHOT"
            $snapshot = @($received | Where-Object { $_.type -eq 'SNAPSHOT' })[0]
            Assert-Value -Condition ([string]$snapshot.payload.auctionId -ceq $AuctionId) `
                -Message "Realtime instance $($entry.Port) snapshot auctionId mismatch"
        }

        $bidRequestId = "smoke-multi-bid-$([Guid]::NewGuid().ToString('N').Substring(0, 16))"
        $bid = Invoke-SmokeRequest `
            -Step 'multi-instance-bid' `
            -Method POST `
            -Path '/api/bids' `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $Authorization; 'X-Request-Id' = $bidRequestId } `
            -Body @{ auctionId = $AuctionId; amount = '100.00' } `
            -ApiEnvelope
        Assert-Value -Condition ([long]$bid.data.lastSequenceNo -eq 1) `
            -Message 'multi-instance bid sequence is not 1'
        $receiveOperations = foreach ($entry in $sockets) {
            $buffer = New-Object byte[] 8192
            $cancellation = [Threading.CancellationTokenSource]::new($RequestTimeoutSeconds * 1000)
            [pscustomobject]@{
                Port = [int]$entry.Port
                Socket = $entry.Socket
                Buffer = $buffer
                Cancellation = $cancellation
                Task = $entry.Socket.ReceiveAsync(
                    [ArraySegment[byte]]::new($buffer), $cancellation.Token)
            }
        }
        $events = @{}
        foreach ($operation in $receiveOperations) {
            try {
                $result = $operation.Task.GetAwaiter().GetResult()
                $messageBuffer = [IO.MemoryStream]::new()
                $messageBuffer.Write($operation.Buffer, 0, $result.Count)
                while (-not $result.EndOfMessage) {
                    $result = $operation.Socket.ReceiveAsync(
                        [ArraySegment[byte]]::new($operation.Buffer),
                        $operation.Cancellation.Token).GetAwaiter().GetResult()
                    $messageBuffer.Write($operation.Buffer, 0, $result.Count)
                }
                $candidate = [Text.Encoding]::UTF8.GetString($messageBuffer.ToArray()) | ConvertFrom-Json
                if ([string]$candidate.type -eq 'BID_ACCEPTED') {
                    $events[$operation.Port] = $candidate
                } elseif ([string]$candidate.type -eq 'ERROR') {
                    throw "Realtime instance $($operation.Port) returned error $($candidate.payload.code)"
                }
            } finally {
                $operation.Cancellation.Dispose()
            }
        }
        $eventIds = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($entry in $sockets) {
            $event = $events[[int]$entry.Port]
            Assert-Value -Condition ($null -ne $event) `
                -Message "Realtime instance $($entry.Port) did not receive BID_ACCEPTED"
            Assert-Value -Condition ([string]$event.payload.auctionId -ceq $AuctionId -and
                [long]$event.payload.sequenceNo -eq 1) `
                -Message "Realtime instance $($entry.Port) returned an invalid BID_ACCEPTED"
            $eventIds.Add([string]$event.payload.eventId) | Out-Null
        }
        Assert-Value -Condition ($eventIds.Count -eq 1) `
            -Message 'The two Realtime instances did not fan out the same eventId'
        Write-Host '[PASS] realtime-multi-instance-fanout'
        return $bid
    } finally {
        foreach ($entry in $sockets) {
            if ($entry.Socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                try {
                    $entry.Socket.CloseAsync(
                        [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                        'multi-instance smoke complete',
                        [Threading.CancellationToken]::None
                    ).GetAwaiter().GetResult()
                } catch { $entry.Socket.Abort() }
            }
            $entry.Socket.Dispose()
        }
    }
}

function Invoke-RealtimeRestartRecoveryCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string]$AuctionId
    )

    $secondaryScript = Join-Path $PSScriptRoot 'realtime-instance.ps1'
    $sockets = [System.Collections.Generic.List[object]]::new()
    try {
        foreach ($port in @(9104, 9204)) {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3
            Assert-Value -Condition ([string]$health.status -eq 'UP') `
                -Message "Realtime instance $port is not healthy"
            $ticketResponse = Invoke-SmokeRequest `
                -Step "realtime-restart-ticket-$port" -Method POST `
                -Path '/api/realtime/tickets' -ExpectedStatus 200 `
                -Headers @{ Authorization = $Authorization } -ApiEnvelope -Quiet
            $socket = [System.Net.WebSockets.ClientWebSocket]::new()
            $socket.Options.SetRequestHeader('Origin', 'http://127.0.0.1:5173')
            $connectCancellation = [Threading.CancellationTokenSource]::new($RequestTimeoutSeconds * 1000)
            try {
                $uri = [Uri]::new("ws://127.0.0.1:$port/ws/auctions?ticket=$([Uri]::EscapeDataString([string]$ticketResponse.data.ticket))")
                $socket.ConnectAsync($uri, $connectCancellation.Token).GetAwaiter().GetResult()
            } finally { $connectCancellation.Dispose() }
            Assert-Value -Condition ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) `
                -Message "Realtime instance $port WebSocket did not open"
            $sockets.Add([pscustomobject]@{ Port = $port; Socket = $socket })
        }

        foreach ($entry in $sockets) {
            $subscribe = @{
                type = 'SUBSCRIBE'; protocolVersion = 1; requestId = New-StepTraceId -Step "restart-subscribe-$($entry.Port)"
                payload = @{ auctionId = $AuctionId; lastSequenceNo = 0 }
            } | ConvertTo-Json -Compress
            $bytes = [Text.Encoding]::UTF8.GetBytes($subscribe)
            $entry.Socket.SendAsync([ArraySegment[byte]]::new($bytes),
                [System.Net.WebSockets.WebSocketMessageType]::Text, $true,
                [Threading.CancellationToken]::None).GetAwaiter().GetResult()
            $messages = @()
            while ((@($messages | ForEach-Object { [string]$_.type }) -notcontains 'SNAPSHOT')) {
                $messages += Receive-RealtimeMessage -Socket $entry.Socket -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            }
        }

        & $secondaryScript -Action Stop -AcknowledgeImpact
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
        do {
            $secondaryUp = $false
            try { $secondaryUp = ([string](Invoke-RestMethod http://127.0.0.1:9204/actuator/health -TimeoutSec 1).status -eq 'UP') } catch { }
            if (-not $secondaryUp) { break }
            Start-Sleep -Milliseconds 500
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        # Allow Nacos/RocketMQ consumer-group rebalancing to move the partition to 9104.
        Start-Sleep -Seconds 8

        $bid = Invoke-SmokeRequest -Step 'restart-recovery-bid' -Method POST -Path '/api/bids' `
            -ExpectedStatus 200 -Headers @{ Authorization = $Authorization; 'X-Request-Id' = "smoke-restart-bid-$([Guid]::NewGuid().ToString('N').Substring(0, 16))" } `
            -Body @{ auctionId = $AuctionId; amount = '100.00' } -ApiEnvelope
        Assert-Value -Condition ([long]$bid.data.lastSequenceNo -eq 1) -Message 'restart recovery bid sequence is not 1'

        $primary = @($sockets | Where-Object { $_.Port -eq 9104 })[0]
        $primaryEvent = $null
        while ($null -eq $primaryEvent) {
            $candidate = Receive-RealtimeMessage -Socket $primary.Socket -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            if ([string]$candidate.type -eq 'BID_ACCEPTED') { $primaryEvent = $candidate }
        }
        Assert-Value -Condition ([long]$primaryEvent.payload.sequenceNo -eq 1) `
            -Message 'primary Realtime did not receive the bid while secondary was stopped'

        & $secondaryScript -Action Start
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(45)
        do {
            try { $secondaryUp = ([string](Invoke-RestMethod http://127.0.0.1:9204/actuator/health -TimeoutSec 1).status -eq 'UP') } catch { $secondaryUp = $false }
            if ($secondaryUp) { break }
            Start-Sleep -Milliseconds 750
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        Assert-Value -Condition $secondaryUp -Message 'secondary Realtime did not recover'

        $ticketResponse = Invoke-SmokeRequest -Step 'restart-recovery-ticket' -Method POST `
            -Path '/api/realtime/tickets' -ExpectedStatus 200 -Headers @{ Authorization = $Authorization } -ApiEnvelope -Quiet
        $recovered = [System.Net.WebSockets.ClientWebSocket]::new()
        $recovered.Options.SetRequestHeader('Origin', 'http://127.0.0.1:5173')
        $recovered.ConnectAsync([Uri]::new("ws://127.0.0.1:9204/ws/auctions?ticket=$([Uri]::EscapeDataString([string]$ticketResponse.data.ticket))"),
            [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        $subscribe = @{ type = 'SUBSCRIBE'; protocolVersion = 1; requestId = New-StepTraceId -Step 'restart-recovery-subscribe'; payload = @{ auctionId = $AuctionId; lastSequenceNo = 0 } } | ConvertTo-Json -Compress
        $recovered.SendAsync([ArraySegment[byte]]::new([Text.Encoding]::UTF8.GetBytes($subscribe)), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        $snapshot = $null
        while ($null -eq $snapshot) {
            $message = Receive-RealtimeMessage -Socket $recovered -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            if ([string]$message.type -eq 'SNAPSHOT') { $snapshot = $message }
        }
        Assert-Value -Condition ([long]$snapshot.payload.lastSequenceNo -ge 1 -and [long]$snapshot.payload.bidCount -ge 1) `
            -Message 'restarted Realtime did not recover the latest Auction snapshot'
        Write-Host '[PASS] realtime-restart-snapshot-recovery'
        $recovered.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'restart recovery complete', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    } finally {
        foreach ($entry in $sockets) {
            if ($entry.Socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) { try { $entry.Socket.Abort() } catch { } }
            $entry.Socket.Dispose()
        }
    }
}

function Invoke-RealtimeBrokerRecoveryCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string]$AuctionId
    )

    $rocketMqScript = Join-Path $PSScriptRoot 'rocketmq-outage.ps1'
    $socket = $null
    $brokerSuspended = $false
    try {
        $ticket = Invoke-SmokeRequest -Step 'realtime-broker-ticket' -Method POST `
            -Path '/api/realtime/tickets' -ExpectedStatus 200 -Headers @{ Authorization = $Authorization } -ApiEnvelope -Quiet
        $socket = [System.Net.WebSockets.ClientWebSocket]::new()
        $socket.Options.SetRequestHeader('Origin', 'http://127.0.0.1:5173')
        $socket.ConnectAsync([Uri]::new("ws://127.0.0.1:9104/ws/auctions?ticket=$([Uri]::EscapeDataString([string]$ticket.data.ticket))"),
            [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        $subscribe = @{ type = 'SUBSCRIBE'; protocolVersion = 1; requestId = New-StepTraceId -Step 'broker-recovery-subscribe'; payload = @{ auctionId = $AuctionId; lastSequenceNo = 0 } } | ConvertTo-Json -Compress
        $socket.SendAsync([ArraySegment[byte]]::new([Text.Encoding]::UTF8.GetBytes($subscribe)), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
        $snapshot = $null
        while ($null -eq $snapshot) {
            $message = Receive-RealtimeMessage -Socket $socket -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            if ([string]$message.type -eq 'SNAPSHOT') { $snapshot = $message }
        }

        & $rocketMqScript -Action Suspend -AcknowledgeImpact
        $brokerSuspended = $true
        $bid = Invoke-SmokeRequest -Step 'broker-recovery-bid' -Method POST -Path '/api/bids' `
            -ExpectedStatus 200 -Headers @{ Authorization = $Authorization; 'X-Request-Id' = "smoke-broker-recovery-$([Guid]::NewGuid().ToString('N').Substring(0, 16))" } `
            -Body @{ auctionId = $AuctionId; amount = '100.00' } -ApiEnvelope
        Assert-Value -Condition ([long]$bid.data.lastSequenceNo -eq 1) -Message 'broker recovery bid sequence is not 1'

        & $rocketMqScript -Action Resume
        $brokerSuspended = $false
        $event = $null
        while ($null -eq $event) {
            $message = Receive-RealtimeMessage -Socket $socket -TimeoutMilliseconds ($RequestTimeoutSeconds * 1000)
            if ([string]$message.type -eq 'BID_ACCEPTED') { $event = $message }
        }
        Assert-Value -Condition ([string]$event.payload.auctionId -ceq $AuctionId -and
            [long]$event.payload.sequenceNo -eq 1) `
            -Message 'connected Realtime client did not receive the Broker-recovered BID_ACCEPTED event'
        Write-Host '[PASS] realtime-broker-backlog-recovery'
        return $bid
    } finally {
        if ($brokerSuspended) { try { & $rocketMqScript -Action Resume } catch { } }
        if ($null -ne $socket) {
            if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) { try { $socket.Abort() } catch { } }
            $socket.Dispose()
        }
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

function ConvertFrom-SmokeDurationSeconds {
    param(
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $normalized = $Value.Trim().ToLowerInvariant()
    if ($normalized -notmatch '^(?<amount>[1-9][0-9]*)(?<unit>s|m|h)$') {
        throw "$Name must use a positive whole-number duration such as 90s, 2m, or 1h."
    }
    $amount = [long]$Matches['amount']
    $multiplier = switch ($Matches['unit']) {
        's' { $amount }
        'm' { $amount * 60 }
        'h' { $amount * 3600 }
    }
    return $multiplier
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
    $paymentWindowSeconds = 0L
    if ($ReliableTrade -and $ReliableTradeCoverage -eq 'All') {
        $paymentWindow = Get-SmokeEnvironmentValue -Name 'TIDEBID_TRADE_PAYMENT_WINDOW' -Values $values
        if ([string]::IsNullOrWhiteSpace($paymentWindow)) {
            throw 'ReliableTrade All requires TIDEBID_TRADE_PAYMENT_WINDOW in .env; use 2m for local acceptance.'
        }
        $paymentWindowSeconds = ConvertFrom-SmokeDurationSeconds `
            -Value $paymentWindow `
            -Name 'TIDEBID_TRADE_PAYMENT_WINDOW'
        if ($paymentWindowSeconds -lt 60 -or $paymentWindowSeconds -gt ($ReliableTradeTimeoutSeconds - 15)) {
            throw "ReliableTrade All requires a payment window from 60 seconds through $($ReliableTradeTimeoutSeconds - 15) seconds."
        }
    }
    return [pscustomobject]@{
        AdminUsername = $adminUsername
        AdminPassword = $adminPassword
        PaymentWindowSeconds = $paymentWindowSeconds
    }
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

    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($null -eq $curl) {
        throw 'OSS PUT requires curl.exe on Windows; the signed URL was suppressed.'
    }

    $runtimeDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime\smoke'
    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
    $uploadFile = Join-Path $runtimeDirectory "oss-upload-$([Guid]::NewGuid().ToString('N')).bin"
    [System.IO.File]::WriteAllBytes($uploadFile, $Content)

    function ConvertTo-CurlConfigValue {
        param([Parameter(Mandatory = $true)][string]$Value)

        if ($Value -match "[`r`n]") {
            throw 'OSS signed request contains an invalid line break.'
        }
        return $Value.Replace('\', '\\').Replace('"', '\"')
    }

    $configLines = [System.Collections.Generic.List[string]]::new()
    $configLines.Add("url = `"$(ConvertTo-CurlConfigValue -Value $UploadUrl)`"")
    $configLines.Add('request = "PUT"')
    $configLines.Add("upload-file = `"$(ConvertTo-CurlConfigValue -Value $uploadFile)`"")
    $configLines.Add("header = `"Content-Type: $(ConvertTo-CurlConfigValue -Value $contentType)`"")
    foreach ($entry in $headers.GetEnumerator()) {
        $header = "$(ConvertTo-CurlConfigValue -Value ([string]$entry.Key)): $(ConvertTo-CurlConfigValue -Value ([string]$entry.Value))"
        $configLines.Add("header = `"$header`"")
    }

    try {
        $curlConfig = ($configLines -join "`n") + "`n"
        $statusText = $curlConfig | & $curl.Source `
            --config - `
            --noproxy '.aliyuncs.com' `
            --silent `
            --output NUL `
            --max-time $RequestTimeoutSeconds `
            --write-out '%{http_code}' 2>$null
        $curlExitCode = $LASTEXITCODE
        $statusCode = 0
        if ([int]::TryParse(([string]$statusText).Trim(), [ref]$statusCode) -and
            $curlExitCode -eq 0 -and $statusCode -in @(200, 201)) {
            Write-Host "[PASS] oss-put traceId=$script:currentTraceId"
            return
        }
        if ($statusCode -gt 0) {
            throw "OSS PUT failed with HTTP status $statusCode (curlExitCode=$curlExitCode); the signed URL and response body were suppressed."
        }
        throw "OSS PUT failed before an HTTP status was received (curlExitCode=$curlExitCode); the signed URL and response body were suppressed."
    } finally {
        Remove-Item -LiteralPath $uploadFile -Force -ErrorAction SilentlyContinue
    }
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

function Get-SmokeWallet {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Authorization
    )

    $result = Invoke-SmokeRequest `
        -Step "$Label-wallet" `
        -Method GET `
        -Path '/api/wallets/me' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $Authorization } `
        -ApiEnvelope `
        -Quiet
    return [pscustomobject]@{
        Available = ConvertTo-InvariantDecimal $result.data.availableBalance 'availableBalance'
        Frozen = ConvertTo-InvariantDecimal $result.data.frozenBalance 'frozenBalance'
    }
}

function Wait-AuctionTerminal {
    param(
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][ValidateSet('CLOSED_SOLD', 'CLOSED_UNSOLD')][string]$ExpectedStatus
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReliableTradeTimeoutSeconds)
    do {
        $result = Invoke-SmokeRequest `
            -Step 'auction-close-status' `
            -Method GET `
            -Path "/api/auctions/$AuctionId" `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $Authorization } `
            -ApiEnvelope `
            -Quiet
        $status = [string]$result.data.sessionStatus
        if ($status -eq $ExpectedStatus) {
            Write-Host "[PASS] auction-terminal traceId=$script:currentTraceId status=$status"
            return $result.data
        }
        if ($status -in @('CLOSED_SOLD', 'CLOSED_UNSOLD')) {
            throw "auction reached unexpected terminal state $status instead of $ExpectedStatus"
        }
        Start-Sleep -Milliseconds 750
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "auction did not reach $ExpectedStatus within $ReliableTradeTimeoutSeconds seconds"
}

function Wait-BuyerOrder {
    param(
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string[]]$ExpectedStatuses,
        [Parameter()][string]$ExpectedSettlementStatus = ''
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReliableTradeTimeoutSeconds)
    do {
        $result = Invoke-SmokeRequest `
            -Step 'buyer-order-status' `
            -Method GET `
            -Path '/api/orders/mine?page=1&size=50' `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $Authorization } `
            -ApiEnvelope `
            -Quiet
        $matching = @($result.data.items | Where-Object { [string]$_.auctionId -ceq $AuctionId })
        if ($matching.Count -gt 1) {
            throw 'buyer order query returned duplicate orders for one auction'
        }
        if ($matching.Count -eq 1) {
            $order = $matching[0]
            $statusMatches = $ExpectedStatuses -contains [string]$order.status
            $settlementMatches = [string]::IsNullOrEmpty($ExpectedSettlementStatus) -or
                [string]$order.sellerSettlementStatus -eq $ExpectedSettlementStatus
            if ($statusMatches -and $settlementMatches) {
                Write-Host "[PASS] buyer-order-status traceId=$script:currentTraceId status=$([string]$order.status) settlement=$([string]$order.sellerSettlementStatus)"
                return $order
            }
        }
        Start-Sleep -Milliseconds 750
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "buyer order did not reach status [$($ExpectedStatuses -join ',')] and settlement '$ExpectedSettlementStatus' within $ReliableTradeTimeoutSeconds seconds"
}

function Wait-SmokeWallet {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][decimal]$ExpectedAvailable,
        [Parameter(Mandatory = $true)][decimal]$ExpectedFrozen
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($ReliableTradeTimeoutSeconds)
    do {
        $wallet = Get-SmokeWallet -Label $Label -Authorization $Authorization
        if ($wallet.Available -eq $ExpectedAvailable -and $wallet.Frozen -eq $ExpectedFrozen) {
            Write-Host "[PASS] $Label-wallet-balance available=$($wallet.Available.ToString('F2', $invariantCulture)) frozen=$($wallet.Frozen.ToString('F2', $invariantCulture))"
            return $wallet
        }
        Start-Sleep -Milliseconds 750
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "$Label wallet did not reach expected available/frozen balances within $ReliableTradeTimeoutSeconds seconds"
}

function New-ApprovedSmokeAuction {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Suffix,
        [Parameter(Mandatory = $true)][string]$SellerAuthorization,
        [Parameter(Mandatory = $true)][string]$AdminAuthorization,
        [Parameter(Mandatory = $true)][byte[]]$ImageBytes
    )

    $checksum = ConvertTo-Sha256Hex -Bytes $ImageBytes
    $requestToken = "$Label-$($Suffix.Substring(0, 8))"
    $uploadIntent = Invoke-SmokeRequest `
        -Step "$Label-create-upload-intent" `
        -Method POST `
        -Path '/api/assets/upload-intents' `
        -ExpectedStatus 201 `
        -Headers @{ Authorization = $SellerAuthorization; 'X-Request-Id' = "smoke-upload-$requestToken" } `
        -Body @{
            originalFilename = "smoke-$Label-$Suffix.png"
            contentType = 'image/png'
            contentLength = $ImageBytes.Length
            checksumSha256 = $checksum
        } `
        -ApiEnvelope
    Send-SmokeImageToOss `
        -UploadUrl ([string]$uploadIntent.data.uploadUrl) `
        -RequiredHeaders $uploadIntent.data.requiredHeaders `
        -Content $ImageBytes

    $startAt = [DateTimeOffset]::UtcNow.AddSeconds(75)
    $endAt = $startAt.AddSeconds(45)
    $draft = Invoke-SmokeRequest `
        -Step "$Label-create-auction-draft" `
        -Method POST `
        -Path '/api/assets' `
        -ExpectedStatus 201 `
        -Headers @{ Authorization = $SellerAuthorization; 'X-Request-Id' = "smoke-draft-$requestToken" } `
        -Body @{
            title = "Smoke $Label $($Suffix.Substring(0, 8))"
            description = "Stage 03 $Label reliable-trade smoke auction."
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
    $itemId = [string]$draft.data.itemId
    $auctionId = [string]$draft.data.auctionId
    $submission = Invoke-SmokeRequest `
        -Step "$Label-submit-auction" `
        -Method POST `
        -Path "/api/assets/$itemId/submit" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $SellerAuthorization; 'X-Request-Id' = "smoke-submit-$requestToken" } `
        -Body @{ itemVersion = [long]$draft.data.itemVersion; sessionVersion = [long]$draft.data.sessionVersion } `
        -ApiEnvelope
    $review = Invoke-SmokeRequest `
        -Step "$Label-approve-auction" `
        -Method POST `
        -Path "/api/admin/assets/$itemId/reviews" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $AdminAuthorization; 'X-Request-Id' = "smoke-review-$requestToken" } `
        -Body @{
            decision = 'APPROVE'
            submissionVersion = [int]$submission.data.submissionVersion
            comment = "Approved by stage 03 $Label smoke verification."
        } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$review.data.sessionStatus -eq 'SCHEDULED') `
        -Message "$Label auction was not scheduled after approval"
    return [pscustomobject]@{ ItemId = $itemId; AuctionId = $auctionId }
}

function Register-SmokeBuyerForAuction {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][string]$Authorization,
        [Parameter(Mandatory = $true)][string]$Suffix
    )

    $registration = Invoke-SmokeRequest `
        -Step "$Label-register-auction" `
        -Method POST `
        -Path '/api/registrations' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $Authorization; 'X-Request-Id' = "smoke-$Label-$($Suffix.Substring(0, 8))" } `
        -Body @{ auctionId = $AuctionId } `
        -ApiEnvelope
    $final = Wait-RegistrationFinal `
        -Label $Label `
        -RegistrationId ([string]$registration.data.registrationId) `
        -Authorization $Authorization
    Assert-Value -Condition ([string]$final.status -eq 'REGISTERED') `
        -Message "$Label registration failed with code $([string]$final.failureCode)"
    return $final
}

function Assert-NoAuctionOrder {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$AuctionId,
        [Parameter(Mandatory = $true)][string]$Authorization
    )

    $result = Invoke-SmokeRequest `
        -Step "$Label-no-order" `
        -Method GET `
        -Path $Path `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $Authorization } `
        -ApiEnvelope `
        -Quiet
    $matching = @($result.data.items | Where-Object { [string]$_.auctionId -ceq $AuctionId })
    Assert-Value -Condition ($matching.Count -eq 0) -Message "$Label unexpectedly found an order for an unsold auction"
    Write-Host "[PASS] $Label-no-order traceId=$script:currentTraceId"
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
    $checksum = ConvertTo-Sha256Hex -Bytes $imageBytes
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

    $startAt = if ($ReliableTrade) {
        [DateTimeOffset]::UtcNow.AddSeconds(75)
    } else {
        [DateTimeOffset]::UtcNow.AddMinutes(2)
    }
    $endAt = if ($ReliableTrade) {
        $startAt.AddSeconds($ReliableTradeAuctionDurationSeconds)
    } else {
        $startAt.AddMinutes(10)
    }
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

    if ($RealtimeBrokerRecovery) {
        $buyerOneBid = Invoke-RealtimeBrokerRecoveryCheck `
            -Authorization $buyerOneAuthorization `
            -AuctionId $auctionId
    } elseif ($RealtimeRestartRecovery) {
        Invoke-RealtimeRestartRecoveryCheck `
            -Authorization $buyerOneAuthorization `
            -AuctionId $auctionId
    } elseif ($RealtimeMultiInstance) {
        $buyerOneBid = Invoke-RealtimeMultiInstanceCheck `
            -Authorization $buyerOneAuthorization `
            -AuctionId $auctionId
    } elseif ($RealtimeProxy) {
        Invoke-RealtimeWebSocketCheck `
            -Authorization $buyerOneAuthorization `
            -AuctionId $auctionId `
            -LastSequenceNo 0
    }

    if ($PauseBeforeFirstBid) {
        Write-Host 'Fault-drill checkpoint reached before the first bid.'
        Write-Host 'In another terminal, suspend or restore RocketMQ as required, then return here.'
        Wait-SmokeCheckpoint -Prompt 'Submit the bid after completing the external fault action.'
    }

    if (-not ($RealtimeMultiInstance -or $RealtimeRestartRecovery -or $RealtimeBrokerRecovery)) {
        $buyerOneBidRequest = "smoke-bid1-$($suffix.Substring(0, 12))"
        $buyerOneBid = Invoke-SmokeRequest `
            -Step 'buyer1-bid' `
            -Method POST `
            -Path '/api/bids' `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $buyerOneBidRequest } `
            -Body @{ auctionId = $auctionId; amount = '100.00' } `
            -ApiEnvelope
        Assert-Value -Condition (([long]$buyerOneBid.data.lastSequenceNo -eq 1) -and
            ([long]$buyerOneBid.data.acceptedBids[0].sequenceNo -eq 1)) `
            -Message 'buyer1 bid sequence is not 1'

        $buyerOneBidReplay = Invoke-SmokeRequest `
            -Step 'replay-buyer1-bid' `
            -Method POST `
            -Path '/api/bids' `
            -ExpectedStatus 200 `
            -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = $buyerOneBidRequest } `
            -Body @{ auctionId = $auctionId; amount = '100.00' } `
            -ApiEnvelope
        Assert-Value -Condition ([string]$buyerOneBidReplay.data.acceptedBids[0].bidId -ceq
            [string]$buyerOneBid.data.acceptedBids[0].bidId) `
            -Message 'bid replay returned a different bid ID'
    }

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
    Assert-Value -Condition (([long]$buyerTwoBid.data.lastSequenceNo -eq 2) -and
        ([long]$buyerTwoBid.data.acceptedBids[0].sequenceNo -eq 2)) `
        -Message 'buyer2 bid sequence is not 2'

    if ($PauseAfterSecondBid) {
        Write-Host 'Fault-drill checkpoint reached after both bids committed to MySQL.'
        Write-Host 'Inspect Outbox state, optionally wait past endAt, restore RocketMQ, then return here.'
        Wait-SmokeCheckpoint -Prompt 'Continue terminal-state and downstream assertions.'
    }

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

    if (-not $ReliableTrade) {
        Write-Host "TideBid auction-core smoke test passed. itemId=$itemId auctionId=$auctionId sellerId=$userId buyer1Id=$($buyerOne.UserId) buyer2Id=$($buyerTwo.UserId)."
        return
    }

    $closed = Wait-AuctionTerminal `
        -AuctionId $auctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatus 'CLOSED_SOLD'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $closed.finalPrice 'finalPrice') -eq [decimal]110.00) `
        -Message 'closed auction final price is not 110.00'
    Assert-Value -Condition ([bool]$closed.wonByCurrentUser) `
        -Message 'buyer2 is not marked as the winner after closing'

    $closedHistory = Invoke-SmokeRequest `
        -Step 'verify-closed-bid-history' `
        -Method GET `
        -Path "/api/auctions/$auctionId/bids?page=1&size=20" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization } `
        -ApiEnvelope
    Assert-Value -Condition ([long]$closedHistory.data.total -eq 2) `
        -Message 'closed auction bid history is not available or has changed'
    $closedHistoryItems = @($closedHistory.data.items)
    Assert-Value -Condition ($closedHistoryItems.Count -eq 2) `
        -Message 'closed auction bid history does not contain exactly two records'
    Assert-Value -Condition ([long]$closedHistoryItems[0].sequenceNo -eq 2 -and [bool]$closedHistoryItems[0].mine) `
        -Message 'closed auction bid history does not preserve the winning bid'

    $pendingOrder = Wait-BuyerOrder `
        -AuctionId $auctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatuses @('PENDING_PAYMENT')
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $pendingOrder.finalPrice 'order finalPrice') -eq [decimal]110.00) `
        -Message 'order final price is not 110.00'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $pendingOrder.capturedDepositAmount 'capturedDepositAmount') -eq [decimal]50.00) `
        -Message 'winner deposit was not captured as 50.00'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $pendingOrder.payableAmount 'payableAmount') -eq [decimal]60.00) `
        -Message 'order payable amount is not 60.00'
    Assert-Value -Condition ([bool]$pendingOrder.paymentEligible) -Message 'pending order is not payment eligible'
    $orderId = [string]$pendingOrder.orderId

    if ($PauseBeforeSoldPayment) {
        Write-Host "Fault-drill checkpoint reached before buyer payment. orderId=$orderId buyerId=$($buyerTwo.UserId) amount=60.00"
        if ($PauseAfterSoldPaymentUnknown) {
            Write-Host 'Suspend Account, then return here so Trade persists an unknown debit result.'
            Wait-SmokeCheckpoint -Prompt 'Submit the payment while Account is unavailable.'
        } elseif ($PauseAfterSoldPaymentPendingSettlement) {
            Write-Host 'Suspend RocketMQ Broker, then return here so payment succeeds while seller credit stays in Trade Outbox.'
            Wait-SmokeCheckpoint -Prompt 'Submit the payment while Broker is unavailable.'
        } else {
            Wait-SmokeCheckpoint -Prompt 'Submit the buyer payment.'
        }
    }

    $paymentRequestId = "smoke-pay-$($suffix.Substring(0, 12))"
    $payment = Invoke-SmokeRequest `
        -Step 'pay-order' `
        -Method POST `
        -Path "/api/orders/$orderId/pay" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization; 'X-Request-Id' = $paymentRequestId } `
        -ApiEnvelope
    $expectedPaymentStatus = if ($PauseAfterSoldPaymentUnknown) { 'UNKNOWN' } else { 'SUCCEEDED' }
    Assert-Value -Condition ([string]$payment.data.status -eq $expectedPaymentStatus) `
        -Message "payment did not reach $expectedPaymentStatus (status=$([string]$payment.data.status))"
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $payment.data.amount 'payment amount') -eq [decimal]60.00) `
        -Message 'payment attempt amount is not 60.00'

    $paymentReplay = Invoke-SmokeRequest `
        -Step 'replay-order-payment' `
        -Method POST `
        -Path "/api/orders/$orderId/pay" `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization; 'X-Request-Id' = $paymentRequestId } `
        -ApiEnvelope
    Assert-Value -Condition ([string]$paymentReplay.data.paymentAttemptId -ceq [string]$payment.data.paymentAttemptId) `
        -Message 'payment replay returned a different payment attempt'

    if ($PauseAfterSoldPaymentUnknown) {
        Assert-Value -Condition ([string]$paymentReplay.data.status -eq 'UNKNOWN') `
            -Message 'unknown payment replay changed status before recovery'
        Write-Host "Fault-drill checkpoint reached after Trade persisted UNKNOWN. orderId=$orderId buyerId=$($buyerTwo.UserId) paymentNo=$([string]$payment.data.paymentNo) requestId=$paymentRequestId amount=60.00"
        Write-Host 'Stop Trade, restore Account, execute the same internal debit once, restart the applications, then return here.'
        Wait-SmokeCheckpoint -Prompt 'Continue PAID, settlement and wallet assertions.'
    } elseif ($PauseAfterSoldPaymentPendingSettlement) {
        Assert-Value -Condition ([string]$paymentReplay.data.status -eq 'SUCCEEDED') `
            -Message 'payment replay changed the successful result before seller settlement'
        $paidPendingOrder = Wait-BuyerOrder `
            -AuctionId $auctionId `
            -Authorization $buyerTwoAuthorization `
            -ExpectedStatuses @('PAID') `
            -ExpectedSettlementStatus 'PENDING'
        Assert-Value -Condition ($null -eq $paidPendingOrder.sellerCreditedAt) `
            -Message 'pending seller settlement unexpectedly has sellerCreditedAt'
        Assert-Value -Condition ((ConvertTo-InvariantDecimal $paidPendingOrder.sellerReceivableAmount 'sellerReceivableAmount') -eq [decimal]110.00) `
            -Message 'pending seller settlement amount is not the full 110.00 final price'
        Write-Host "Fault-drill checkpoint reached after payment with seller settlement PENDING. orderId=$orderId sellerId=$userId creditNo=SC:${orderId}:SALE amount=110.00"
        Write-Host 'Suspend Account, restore Broker, verify no seller credit exists, resume Account, then return here.'
        Wait-SmokeCheckpoint -Prompt 'Continue COMPLETED settlement and wallet assertions.'
    }

    $paidOrder = Wait-BuyerOrder `
        -AuctionId $auctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatuses @('PAID') `
        -ExpectedSettlementStatus 'COMPLETED'
    Assert-Value -Condition ($null -ne $paidOrder.paidAt) -Message 'paid order has no paidAt timestamp'
    Assert-Value -Condition ($null -ne $paidOrder.sellerCreditedAt) `
        -Message 'completed seller settlement has no credited timestamp'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $paidOrder.sellerReceivableAmount 'sellerReceivableAmount') -eq [decimal]110.00) `
        -Message 'seller receivable is not the full 110.00 final price'

    $sellerWalletAfter = Get-SmokeWallet -Label 'seller-final' -Authorization $sellerAuthorization
    $winnerWalletAfter = Get-SmokeWallet -Label 'winner-final' -Authorization $buyerTwoAuthorization
    $loserWalletAfter = Get-SmokeWallet -Label 'loser-final' -Authorization $buyerOneAuthorization
    Assert-Value -Condition ($sellerWalletAfter.Available -eq [decimal]10110.00 -and $sellerWalletAfter.Frozen -eq 0) `
        -Message 'seller wallet did not receive the full final price exactly once'
    Assert-Value -Condition ($winnerWalletAfter.Available -eq [decimal]9890.00 -and $winnerWalletAfter.Frozen -eq 0) `
        -Message 'winner wallet does not reflect deposit capture plus 60.00 tail payment'
    Assert-Value -Condition ($loserWalletAfter.Available -eq [decimal]10000.00 -and $loserWalletAfter.Frozen -eq 0) `
        -Message 'loser deposit was not fully released'

    Write-Host "[PASS] reliable-trade-sold-payment itemId=$itemId auctionId=$auctionId orderId=$orderId"
    if ($ReliableTradeCoverage -eq 'Sold') {
        Write-Host "TideBid reliable-trade sold/payment smoke test passed. sellerId=$userId winnerId=$($buyerTwo.UserId) loserId=$($buyerOne.UserId)."
        return
    }

    $unsoldBuyerOneBefore = Get-SmokeWallet -Label 'unsold-buyer1-before' -Authorization $buyerOneAuthorization
    $unsoldBuyerTwoBefore = Get-SmokeWallet -Label 'unsold-buyer2-before' -Authorization $buyerTwoAuthorization
    $unsold = New-ApprovedSmokeAuction `
        -Label 'unsold' `
        -Suffix $suffix `
        -SellerAuthorization $sellerAuthorization `
        -AdminAuthorization $adminAuthorization `
        -ImageBytes $imageBytes
    Register-SmokeBuyerForAuction `
        -Label 'unsold-buyer1' `
        -AuctionId $unsold.AuctionId `
        -Authorization $buyerOneAuthorization `
        -Suffix $suffix | Out-Null
    Register-SmokeBuyerForAuction `
        -Label 'unsold-buyer2' `
        -AuctionId $unsold.AuctionId `
        -Authorization $buyerTwoAuthorization `
        -Suffix $suffix | Out-Null
    Wait-AuctionOpen -AuctionId $unsold.AuctionId -Authorization $buyerOneAuthorization | Out-Null
    $unsoldClosed = Wait-AuctionTerminal `
        -AuctionId $unsold.AuctionId `
        -Authorization $buyerOneAuthorization `
        -ExpectedStatus 'CLOSED_UNSOLD'
    Assert-Value -Condition ($null -eq $unsoldClosed.finalPrice) -Message 'unsold auction unexpectedly has a final price'
    Assert-Value -Condition ([long]$unsoldClosed.bidCount -eq 0) -Message 'unsold auction unexpectedly has bids'
    Wait-SmokeWallet `
        -Label 'unsold-buyer1-released' `
        -Authorization $buyerOneAuthorization `
        -ExpectedAvailable $unsoldBuyerOneBefore.Available `
        -ExpectedFrozen $unsoldBuyerOneBefore.Frozen | Out-Null
    Wait-SmokeWallet `
        -Label 'unsold-buyer2-released' `
        -Authorization $buyerTwoAuthorization `
        -ExpectedAvailable $unsoldBuyerTwoBefore.Available `
        -ExpectedFrozen $unsoldBuyerTwoBefore.Frozen | Out-Null
    Assert-NoAuctionOrder `
        -Label 'unsold-seller' `
        -Path '/api/orders/sales?page=1&size=50' `
        -AuctionId $unsold.AuctionId `
        -Authorization $sellerAuthorization
    Assert-NoAuctionOrder `
        -Label 'unsold-buyer1' `
        -Path '/api/orders/mine?page=1&size=50' `
        -AuctionId $unsold.AuctionId `
        -Authorization $buyerOneAuthorization
    Assert-NoAuctionOrder `
        -Label 'unsold-buyer2' `
        -Path '/api/orders/mine?page=1&size=50' `
        -AuctionId $unsold.AuctionId `
        -Authorization $buyerTwoAuthorization
    Write-Host "[PASS] reliable-trade-unsold itemId=$($unsold.ItemId) auctionId=$($unsold.AuctionId)"
    if ($ReliableTradeCoverage -eq 'SoldAndUnsold') {
        Write-Host 'TideBid reliable-trade sold/payment and unsold/release smoke tests passed.'
        return
    }

    $timeoutSellerBefore = Get-SmokeWallet -Label 'timeout-seller-before' -Authorization $sellerAuthorization
    $timeoutBuyerOneBefore = Get-SmokeWallet -Label 'timeout-buyer1-before' -Authorization $buyerOneAuthorization
    $timeoutBuyerTwoBefore = Get-SmokeWallet -Label 'timeout-buyer2-before' -Authorization $buyerTwoAuthorization
    $timeoutAuction = New-ApprovedSmokeAuction `
        -Label 'timeout' `
        -Suffix $suffix `
        -SellerAuthorization $sellerAuthorization `
        -AdminAuthorization $adminAuthorization `
        -ImageBytes $imageBytes
    Register-SmokeBuyerForAuction `
        -Label 'timeout-buyer1' `
        -AuctionId $timeoutAuction.AuctionId `
        -Authorization $buyerOneAuthorization `
        -Suffix $suffix | Out-Null
    Register-SmokeBuyerForAuction `
        -Label 'timeout-buyer2' `
        -AuctionId $timeoutAuction.AuctionId `
        -Authorization $buyerTwoAuthorization `
        -Suffix $suffix | Out-Null
    Wait-AuctionOpen -AuctionId $timeoutAuction.AuctionId -Authorization $buyerOneAuthorization | Out-Null

    $timeoutBidOne = Invoke-SmokeRequest `
        -Step 'timeout-buyer1-bid' `
        -Method POST `
        -Path '/api/bids' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerOneAuthorization; 'X-Request-Id' = "smoke-timeout-bid1-$($suffix.Substring(0, 8))" } `
        -Body @{ auctionId = $timeoutAuction.AuctionId; amount = '100.00' } `
        -ApiEnvelope
    Assert-Value -Condition (([long]$timeoutBidOne.data.lastSequenceNo -eq 1) -and
        ([long]$timeoutBidOne.data.acceptedBids[0].sequenceNo -eq 1)) `
        -Message 'timeout buyer1 bid sequence is not 1'
    $timeoutBidTwo = Invoke-SmokeRequest `
        -Step 'timeout-buyer2-bid' `
        -Method POST `
        -Path '/api/bids' `
        -ExpectedStatus 200 `
        -Headers @{ Authorization = $buyerTwoAuthorization; 'X-Request-Id' = "smoke-timeout-bid2-$($suffix.Substring(0, 8))" } `
        -Body @{ auctionId = $timeoutAuction.AuctionId; amount = '110.00' } `
        -ApiEnvelope
    Assert-Value -Condition (([long]$timeoutBidTwo.data.lastSequenceNo -eq 2) -and
        ([long]$timeoutBidTwo.data.acceptedBids[0].sequenceNo -eq 2)) `
        -Message 'timeout buyer2 bid sequence is not 2'

    Wait-AuctionTerminal `
        -AuctionId $timeoutAuction.AuctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatus 'CLOSED_SOLD' | Out-Null
    $timeoutPending = Wait-BuyerOrder `
        -AuctionId $timeoutAuction.AuctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatuses @('PENDING_PAYMENT')
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $timeoutPending.capturedDepositAmount 'timeout capturedDepositAmount') -eq [decimal]50.00) `
        -Message 'timeout order did not capture the winner deposit'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $timeoutPending.payableAmount 'timeout payableAmount') -eq [decimal]60.00) `
        -Message 'timeout order payable amount is not 60.00'

    if ($PauseAfterTimeoutPending) {
        $timeoutDeadlineUtc = ([DateTimeOffset]$timeoutPending.paymentDeadline).ToUniversalTime().ToString('O')
        Write-Host "Fault-drill checkpoint reached with timeout order PENDING_PAYMENT. orderId=$([string]$timeoutPending.orderId) paymentDeadlineUtc=$timeoutDeadlineUtc"
        Write-Host 'Suspend RocketMQ, wait past paymentDeadline, verify PAYMENT_TIMEOUT/PENDING settlement in MySQL, restore RocketMQ, then return here.'
        Wait-SmokeCheckpoint -Prompt 'Continue timeout settlement and wallet assertions.'
    }

    $timedOutOrder = Wait-BuyerOrder `
        -AuctionId $timeoutAuction.AuctionId `
        -Authorization $buyerTwoAuthorization `
        -ExpectedStatuses @('PAYMENT_TIMEOUT') `
        -ExpectedSettlementStatus 'COMPLETED'
    Assert-Value -Condition ($null -ne $timedOutOrder.timedOutAt) -Message 'timed-out order has no timedOutAt timestamp'
    Assert-Value -Condition ($null -eq $timedOutOrder.paidAt) -Message 'timed-out order unexpectedly has a paidAt timestamp'
    Assert-Value -Condition (-not [bool]$timedOutOrder.paymentEligible) -Message 'timed-out order remains payment eligible'
    Assert-Value -Condition ((ConvertTo-InvariantDecimal $timedOutOrder.sellerReceivableAmount 'timeout sellerReceivableAmount') -eq [decimal]50.00) `
        -Message 'timeout seller compensation is not exactly the captured deposit'

    Wait-SmokeWallet `
        -Label 'timeout-seller-compensated' `
        -Authorization $sellerAuthorization `
        -ExpectedAvailable ($timeoutSellerBefore.Available + [decimal]50.00) `
        -ExpectedFrozen $timeoutSellerBefore.Frozen | Out-Null
    Wait-SmokeWallet `
        -Label 'timeout-loser-released' `
        -Authorization $buyerOneAuthorization `
        -ExpectedAvailable $timeoutBuyerOneBefore.Available `
        -ExpectedFrozen $timeoutBuyerOneBefore.Frozen | Out-Null
    Wait-SmokeWallet `
        -Label 'timeout-winner-forfeited' `
        -Authorization $buyerTwoAuthorization `
        -ExpectedAvailable ($timeoutBuyerTwoBefore.Available - [decimal]50.00) `
        -ExpectedFrozen $timeoutBuyerTwoBefore.Frozen | Out-Null

    Write-Host "[PASS] reliable-trade-payment-timeout itemId=$($timeoutAuction.ItemId) auctionId=$($timeoutAuction.AuctionId) orderId=$([string]$timedOutOrder.orderId)"
    Write-Host "TideBid reliable-trade full smoke test passed. sellerId=$userId buyer1Id=$($buyerOne.UserId) buyer2Id=$($buyerTwo.UserId)."
} catch {
    throw "TideBid smoke test failed at step '$script:currentStep' traceId=$script:currentTraceId. $($_.Exception.Message)"
}

[CmdletBinding()]
param(
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

$expectedTopics = [ordered]@{
    'tidebid-auction-events' = 'NORMAL'
    'tidebid-account-events' = 'NORMAL'
    'tidebid-trade-events' = 'NORMAL'
    'tidebid-scheduled-commands' = 'DELAY'
}
$expectedGroups = @(
    'tidebid-auction-close-v1',
    'tidebid-account-deposit-v1',
    'tidebid-trade-auction-v1',
    'tidebid-trade-account-v1',
    'tidebid-trade-timeout-v1',
    'tidebid-account-credit-v1',
    'tidebid-realtime-auction-v1'
)

function Get-RequiredJsonProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "Missing $Context '$Name'."
    }
    return $property.Value
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $output = @(& $script:dockerCommand.Source @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage Docker exited with code $LASTEXITCODE."
    }
    return $output
}

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file does not exist: $EnvFile"
}
if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "Compose file does not exist: $composeFile"
}

$composeArguments = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
$brokerIds = @(Invoke-Docker `
        -Arguments ($composeArguments + @('ps', '--quiet', 'rocketmq-broker')) `
        -FailureMessage 'Could not locate the TideBid RocketMQ Broker container.')
$brokerIds = @($brokerIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($brokerIds.Count -ne 1) {
    throw "Expected one running TideBid RocketMQ Broker container, found $($brokerIds.Count)."
}
$brokerId = [string]$brokerIds[0]

$topicJson = (Invoke-Docker `
        -Arguments @('exec', $brokerId, 'cat', '/home/rocketmq/store/config/topics.json') `
        -FailureMessage 'Could not read RocketMQ Topic metadata.') -join [Environment]::NewLine
$groupJson = (Invoke-Docker `
        -Arguments @('exec', $brokerId, 'cat', '/home/rocketmq/store/config/subscriptionGroup.json') `
        -FailureMessage 'Could not read RocketMQ Consumer Group metadata.') -join [Environment]::NewLine

try {
    $topicMetadata = $topicJson | ConvertFrom-Json
    $groupMetadata = $groupJson | ConvertFrom-Json
} catch {
    throw 'RocketMQ metadata is not valid JSON.'
}

$topicTable = Get-RequiredJsonProperty -Object $topicMetadata -Name 'topicConfigTable' -Context 'metadata property'
foreach ($entry in $expectedTopics.GetEnumerator()) {
    $topicConfig = Get-RequiredJsonProperty -Object $topicTable -Name $entry.Key -Context 'Topic'
    if ([int]$topicConfig.perm -ne 6) {
        throw "Topic '$($entry.Key)' must have read/write permission 6."
    }

    $messageType = 'NORMAL'
    $typeProperty = $topicConfig.attributes.PSObject.Properties['message.type']
    if ($null -ne $typeProperty) {
        $messageType = [string]$typeProperty.Value
    }
    if ($messageType -ne $entry.Value) {
        throw "Topic '$($entry.Key)' has message type '$messageType'; expected '$($entry.Value)'."
    }
    Write-Host ("  topic {0}: {1} / RW" -f $entry.Key, $messageType)
}

$groupTable = Get-RequiredJsonProperty -Object $groupMetadata -Name 'subscriptionGroupTable' -Context 'metadata property'
foreach ($groupName in $expectedGroups) {
    $groupConfig = Get-RequiredJsonProperty -Object $groupTable -Name $groupName -Context 'Consumer Group'
    if (-not [bool]$groupConfig.consumeEnable) {
        throw "Consumer Group '$groupName' is disabled."
    }
    if ([bool]$groupConfig.consumeBroadcastEnable) {
        throw "Consumer Group '$groupName' must use clustering rather than broadcast consumption."
    }
    if ([int]$groupConfig.retryMaxTimes -ne 16) {
        throw "Consumer Group '$groupName' must have retryMaxTimes=16."
    }
    Write-Host ("  group {0}: enabled / clustering / retries-16" -f $groupName)
}

Write-Host 'TideBid RocketMQ topology is valid.'

[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(30, 900)]
    [int]$TimeoutSeconds = 240
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

$requiredEnvironmentNames = @(
    'TIDEBID_MYSQL_ROOT_PASSWORD',
    'TIDEBID_ACCOUNT_DB_PASSWORD',
    'TIDEBID_AUCTION_DB_PASSWORD',
    'TIDEBID_TRADE_DB_PASSWORD',
    'TIDEBID_AI_DB_PASSWORD',
    'TIDEBID_REDIS_PASSWORD',
    'TIDEBID_NACOS_PASSWORD',
    'TIDEBID_NACOS_DB_PASSWORD',
    'TIDEBID_NACOS_AUTH_TOKEN',
    'TIDEBID_NACOS_AUTH_IDENTITY_KEY',
    'TIDEBID_NACOS_AUTH_IDENTITY_VALUE'
)
$longRunningServices = @(
    'mysql',
    'redis',
    'nacos',
    'rocketmq-nameserver',
    'rocketmq-broker',
    'rocketmq-proxy',
    'rocketmq-dashboard'
)
$completedServices = @('mysql-bootstrap', 'rocketmq-volume-init')

function Read-DotEnvFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file does not exist: $Path. Copy .env.example to .env and replace every change-me value."
    }

    $values = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($line in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $Path).Path)) {
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
        if ($value.Length -ge 2) {
            $doubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
            $singleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
            if ($doubleQuoted -or $singleQuoted) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $values[$name] = $value
    }
    return $values
}

function Get-EffectiveEnvironmentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)]$DotEnvValues
    )

    $processValue = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ($null -ne $processValue) {
        return $processValue
    }
    if ($DotEnvValues.ContainsKey($Name)) {
        return $DotEnvValues[$Name]
    }
    return $null
}

function Assert-EnvironmentConfiguration {
    param([Parameter(Mandatory = $true)]$DotEnvValues)

    $invalidNames = @()
    foreach ($name in $requiredEnvironmentNames) {
        $value = Get-EffectiveEnvironmentValue -Name $name -DotEnvValues $DotEnvValues
        if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('change-me')) {
            $invalidNames += $name
        }
    }
    if ($invalidNames.Count -gt 0) {
        throw "Set these required values to non-placeholder values in .env or the current process environment: $($invalidNames -join ', ')."
    }

    $encodedToken = Get-EffectiveEnvironmentValue `
        -Name 'TIDEBID_NACOS_AUTH_TOKEN' `
        -DotEnvValues $DotEnvValues
    try {
        $tokenBytes = [Convert]::FromBase64String($encodedToken)
    } catch {
        throw 'TIDEBID_NACOS_AUTH_TOKEN must be valid Base64; its value was not printed.'
    }
    if ($tokenBytes.Length -lt 32) {
        throw 'TIDEBID_NACOS_AUTH_TOKEN must decode to at least 32 bytes.'
    }
}

function Invoke-DockerCommand {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage,
        [switch]$CaptureOutput
    )

    if ($CaptureOutput) {
        $commandOutput = @(& $script:dockerCommand.Source @Arguments)
        if ($LASTEXITCODE -ne 0) {
            throw "$FailureMessage Docker exited with code $LASTEXITCODE."
        }
        return $commandOutput
    }

    & $script:dockerCommand.Source @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage Docker exited with code $LASTEXITCODE."
    }
}

function Get-ComposeServiceStates {
    $outputLines = @(Invoke-DockerCommand `
            -Arguments ($script:composeArguments + @('ps', '--all', '--format', 'json')) `
            -FailureMessage 'Could not inspect TideBid containers.' `
            -CaptureOutput)
    $states = @{}
    foreach ($line in $outputLines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $state = $line | ConvertFrom-Json
        $states[[string]$state.Service] = $state
    }
    return $states
}

function Get-BlockingServiceProblem {
    param([Parameter(Mandatory = $true)]$States)

    foreach ($service in $completedServices) {
        if (-not $States.ContainsKey($service)) {
            continue
        }
        $state = $States[$service]
        if ([string]$state.State -eq 'exited' -and [int]$state.ExitCode -ne 0) {
            return "$service exited with code $($state.ExitCode)"
        }
    }

    foreach ($service in $longRunningServices) {
        if (-not $States.ContainsKey($service)) {
            continue
        }
        $state = $States[$service]
        if ([string]$state.Health -eq 'unhealthy') {
            return "$service became unhealthy"
        }
        if ([string]$state.State -in @('dead', 'exited', 'removing')) {
            return "$service entered state $($state.State)"
        }
    }
    return $null
}

function Test-InfrastructureReady {
    param([Parameter(Mandatory = $true)]$States)

    foreach ($service in $completedServices) {
        if (-not $States.ContainsKey($service)) {
            return $false
        }
        $state = $States[$service]
        if ([string]$state.State -ne 'exited' -or [int]$state.ExitCode -ne 0) {
            return $false
        }
    }
    foreach ($service in $longRunningServices) {
        if (-not $States.ContainsKey($service)) {
            return $false
        }
        $state = $States[$service]
        if ([string]$state.State -ne 'running' -or [string]$state.Health -ne 'healthy') {
            return $false
        }
    }
    return $true
}

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) {
    throw 'Docker CLI was not found. Install and start Docker Desktop, then retry.'
}
if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
    throw "Compose file does not exist: $composeFile"
}

$dotEnvValues = Read-DotEnvFile -Path $EnvFile
Assert-EnvironmentConfiguration -DotEnvValues $dotEnvValues

& $dockerCommand.Source version --format '{{.Server.Version}}' *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop engine is not reachable. Start Docker Desktop and wait until the engine is running.'
}
& $dockerCommand.Source compose version --short *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose v2 is unavailable. Update Docker Desktop and retry.'
}

$composeArguments = @('compose', '--env-file', $EnvFile, '-f', $composeFile)
Invoke-DockerCommand `
    -Arguments ($composeArguments + @('config', '--quiet')) `
    -FailureMessage 'TideBid Compose configuration is invalid.'

Write-Host 'Starting TideBid middleware without deleting containers or named volumes...'
Invoke-DockerCommand `
    -Arguments ($composeArguments + @('up', '--detach')) `
    -FailureMessage 'TideBid middleware could not start. Check for occupied ports and run docker compose logs for the reported service.'

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    $states = Get-ComposeServiceStates
    $blockingProblem = Get-BlockingServiceProblem -States $states
    if ($null -ne $blockingProblem) {
        throw "TideBid middleware startup failed: $blockingProblem. Run docker compose --env-file .env -f infra/compose.yaml logs <service>."
    }
    if (Test-InfrastructureReady -States $states) {
        Write-Host 'TideBid middleware is ready.'
        foreach ($service in $longRunningServices) {
            Write-Host ("  {0}: running / healthy" -f $service)
        }
        foreach ($service in $completedServices) {
            Write-Host ("  {0}: exited / 0 (expected one-time task)" -f $service)
        }
        return
    }
    Start-Sleep -Milliseconds 750
} while ([DateTimeOffset]::UtcNow -lt $deadline)

$lastStates = Get-ComposeServiceStates
$pending = @()
foreach ($service in ($longRunningServices + $completedServices)) {
    if (-not $lastStates.ContainsKey($service)) {
        $pending += "$service=missing"
        continue
    }
    $state = $lastStates[$service]
    $pending += "$service=$($state.State)/$($state.Health)/exit-$($state.ExitCode)"
}
throw "TideBid middleware was not ready within $TimeoutSeconds seconds. States: $($pending -join ', ')."

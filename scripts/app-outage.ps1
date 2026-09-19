[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('account', 'auction', 'trade', 'realtime', 'ai', 'gateway', 'web')]
    [string]$Service,

    [Parameter(Mandatory = $true)]
    [ValidateSet('Status', 'Suspend', 'Resume')]
    [string]$Action,

    [Parameter()]
    [switch]$AcknowledgeImpact,

    [Parameter()]
    [string]$RuntimeDirectory = '',

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$allowedRuntimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot '.runtime'))
if ([string]::IsNullOrWhiteSpace($RuntimeDirectory)) {
    $RuntimeDirectory = Join-Path $allowedRuntimeRoot 'apps'
} elseif (-not [System.IO.Path]::IsPathRooted($RuntimeDirectory)) {
    $RuntimeDirectory = Join-Path $repositoryRoot $RuntimeDirectory
}
$RuntimeDirectory = [System.IO.Path]::GetFullPath($RuntimeDirectory)
if (-not ($RuntimeDirectory -eq $allowedRuntimeRoot -or
        $RuntimeDirectory.StartsWith($allowedRuntimeRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "RuntimeDirectory must stay under $allowedRuntimeRoot."
}
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot '.env'
} elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) {
    $EnvFile = Join-Path $repositoryRoot $EnvFile
}
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

$manifestPath = Join-Path $RuntimeDirectory 'processes.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "TideBid application PID manifest does not exist: $manifestPath"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ([int]$manifest.version -ne 1 -or [string]$manifest.repositoryRoot -ne $repositoryRoot) {
    throw "Runtime manifest does not belong to this TideBid checkout: $manifestPath"
}
$expectedDefinitions = @{
    account = [pscustomobject]@{ Port = 9101; Marker = Join-Path $repositoryRoot 'services\account-service\target\account-service.jar' }
    auction = [pscustomobject]@{ Port = 9102; Marker = Join-Path $repositoryRoot 'services\auction-service\target\auction-service.jar' }
    trade = [pscustomobject]@{ Port = 9103; Marker = Join-Path $repositoryRoot 'services\trade-service\target\trade-service.jar' }
    realtime = [pscustomobject]@{ Port = 9104; Marker = Join-Path $repositoryRoot 'services\realtime-service\target\realtime-service.jar' }
    ai = [pscustomobject]@{ Port = 9105; Marker = Join-Path $repositoryRoot 'services\ai-service\target\ai-service.jar' }
    gateway = [pscustomobject]@{ Port = 9000; Marker = Join-Path $repositoryRoot 'services\gateway-service\target\gateway-service.jar' }
    web = [pscustomobject]@{ Port = 5173; Marker = Join-Path $repositoryRoot 'web\node_modules\vite\bin\vite.js' }
}
$record = @($manifest.processes | Where-Object { [string]$_.name -eq $Service })
if ($record.Count -ne 1) {
    throw "Expected exactly one recorded process for service '$Service', found $($record.Count)."
}
$record = $record[0]
$expected = $expectedDefinitions[$Service]
if ([int]$record.port -ne [int]$expected.Port -or
    -not [string]::Equals(
        [System.IO.Path]::GetFullPath([string]$record.commandMarker),
        [System.IO.Path]::GetFullPath([string]$expected.Marker),
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw "Runtime manifest definition for service '$Service' does not match the TideBid service whitelist."
}

function Get-MatchingProcess {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][string]$Marker
    )
    try {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    } catch {
        throw "Could not inspect recorded process PID $ProcessId; no process was changed: $($_.Exception.Message)"
    }
    if ($null -eq $process -or [string]::IsNullOrWhiteSpace([string]$process.CommandLine) -or
        $process.CommandLine.IndexOf($Marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $null
    }
    return $process
}

$processId = [int]$record.pid
$process = Get-MatchingProcess -ProcessId $processId -Marker ([string]$record.commandMarker)
if ($Action -eq 'Status') {
    if ($null -eq $process) {
        Write-Host "$Service is suspended or its recorded PID no longer belongs to TideBid."
    } else {
        Write-Host "$Service is running at recorded PID $processId on port $([int]$record.port)."
    }
    return
}

if ($Action -eq 'Suspend') {
    if (-not $AcknowledgeImpact) {
        throw 'Suspend interrupts an application process. Re-run with -AcknowledgeImpact after confirming the fault drill.'
    }
    if ($null -eq $process) {
        Write-Host "$Service is already suspended; no process was changed."
        return
    }
    $taskkill = Get-Command taskkill.exe -ErrorAction Stop
    Write-Host "Suspending only TideBid $Service at PID $processId; the PID manifest and logs are preserved..."
    & $taskkill.Source /PID $processId /T /F
    if ($LASTEXITCODE -ne 0 -and
        $null -ne (Get-MatchingProcess -ProcessId $processId -Marker ([string]$record.commandMarker))) {
        throw "Could not suspend TideBid service '$Service'."
    }
    Write-Host "[PASS] TideBid $Service is suspended. Resume it with: .\scripts\app-outage.ps1 -Service $Service -Action Resume"
    return
}

if ($null -ne $process) {
    Write-Host "$Service is already running at recorded PID $processId; no process was started."
    return
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file does not exist: $EnvFile"
}
$listeners = @(Get-NetTCPConnection -LocalPort ([int]$record.port) -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) {
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    throw "Port $([int]$record.port) is occupied by PID(s) $($owners -join ', '); no process was started."
}

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
    if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw 'Invalid environment variable name in .env.'
    }
    $value = $line.Substring($separator + 1).Trim()
    if ($value.Length -ge 2 -and
        (($value.StartsWith('"') -and $value.EndsWith('"')) -or
         ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

$resumeLogDirectory = Join-Path $RuntimeDirectory ('logs\resume-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $resumeLogDirectory -Force | Out-Null
$stdoutLog = Join-Path $resumeLogDirectory "$Service.out.log"
$stderrLog = Join-Path $resumeLogDirectory "$Service.err.log"
if ($Service -eq 'web') {
    $command = (Get-Command node -ErrorAction Stop).Source
    $arguments = @(
        ('"' + [string]$record.commandMarker + '"'), '--host', '127.0.0.1', '--port', '5173', '--strictPort'
    )
    $workingDirectory = Join-Path $repositoryRoot 'web'
    $healthUri = 'http://127.0.0.1:5173/'
} else {
    if (-not (Test-Path -LiteralPath ([string]$record.commandMarker) -PathType Leaf)) {
        throw "Application JAR does not exist: $([string]$record.commandMarker)"
    }
    $command = (Get-Command java -ErrorAction Stop).Source
    $arguments = @('-jar', ('"' + [string]$record.commandMarker + '"'), '--spring.profiles.active=nacos')
    $workingDirectory = $repositoryRoot
    $healthUri = "http://127.0.0.1:$([int]$record.port)/actuator/health"
}

$started = Start-Process `
    -FilePath $command `
    -ArgumentList $arguments `
    -WorkingDirectory $workingDirectory `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru
try {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $ready = $false
    do {
        Start-Sleep -Milliseconds 500
        if ($started.HasExited) {
            throw "$Service exited before becoming ready. Inspect $stderrLog."
        }
        try {
            $response = Invoke-WebRequest -Uri $healthUri -TimeoutSec 2 -UseBasicParsing
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                $ready = $true
                break
            }
        } catch {
            # The endpoint is expected to reject connections until startup completes.
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if (-not $ready) {
        throw "$Service did not become ready within $StartupTimeoutSeconds seconds. Inspect $stdoutLog and $stderrLog."
    }

    $record.pid = [int]$started.Id
    $record.stdoutLog = $stdoutLog
    $record.stderrLog = $stderrLog
    $manifestStartedAt = [string]$manifest.startedAt
    $parsedStartedAt = [DateTimeOffset]::MinValue
    if ([DateTimeOffset]::TryParse(
            $manifestStartedAt,
            [System.Globalization.CultureInfo]::CurrentCulture,
            [System.Globalization.DateTimeStyles]::AssumeLocal,
            [ref]$parsedStartedAt
        )) {
        $manifestStartedAt = $parsedStartedAt.ToString('o')
    }
    $manifestJson = [ordered]@{
        version = 1
        repositoryRoot = $repositoryRoot
        startedAt = $manifestStartedAt
        processes = @($manifest.processes)
    } | ConvertTo-Json -Depth 5
    $temporaryManifestPath = $manifestPath + '.tmp'
    [System.IO.File]::WriteAllText(
        $temporaryManifestPath,
        $manifestJson + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryManifestPath -Destination $manifestPath -Force
} catch {
    if (-not $started.HasExited) {
        & taskkill.exe /PID ([int]$started.Id) /T /F *> $null
    }
    throw
}

Write-Host "[PASS] TideBid $Service resumed at PID $([int]$started.Id) on port $([int]$record.port)."

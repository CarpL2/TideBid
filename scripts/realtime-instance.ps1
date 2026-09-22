[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Status', 'Start', 'Stop')]
    [string]$Action,

    [Parameter()]
    [ValidateRange(1024, 65535)]
    [int]$Port = 9204,

    [Parameter()]
    [string]$RuntimeDirectory = '',

    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [switch]$AcknowledgeImpact
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot '.runtime\realtime-instances'))
if ([string]::IsNullOrWhiteSpace($RuntimeDirectory)) { $RuntimeDirectory = $runtimeRoot }
elseif (-not [System.IO.Path]::IsPathRooted($RuntimeDirectory)) { $RuntimeDirectory = Join-Path $repositoryRoot $RuntimeDirectory }
$RuntimeDirectory = [System.IO.Path]::GetFullPath($RuntimeDirectory)
if (-not ($RuntimeDirectory -eq $runtimeRoot -or $RuntimeDirectory.StartsWith($runtimeRoot + '\', [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "RuntimeDirectory must stay under $runtimeRoot."
}
if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = Join-Path $repositoryRoot '.env' }
elseif (-not [System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile = Join-Path $repositoryRoot $EnvFile }
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)
$manifestPath = Join-Path $RuntimeDirectory 'process.json'
$jarPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'services\realtime-service\target\realtime-service.jar'))

function Import-DotEnv {
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw '.env is missing.' }
    foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw 'Invalid .env entry.' }
        $name = $line.Substring(0, $separator).Trim()
        if ($name -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw 'Invalid environment variable name in .env.' }
        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Test-InstanceProcess {
    param([int]$ProcessId)
    try { $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop } catch { return $null }
    if ($null -eq $process -or [string]$process.CommandLine -notlike "*$jarPath*") { return $null }
    return $process
}
function Read-Manifest {
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { return $null }
    $value = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ([int]$value.version -ne 1 -or [string]$value.repositoryRoot -ne $repositoryRoot) { throw 'Realtime instance manifest does not belong to this checkout.' }
    return $value
}

$manifest = Read-Manifest
if ($Action -eq 'Status') {
    if ($null -eq $manifest) { Write-Host 'Realtime secondary instance is not recorded.'; return }
    $process = Test-InstanceProcess -ProcessId ([int]$manifest.pid)
    if ($null -eq $process) { Write-Host "Realtime secondary instance is stopped (recorded PID $($manifest.pid))." }
    else { Write-Host "Realtime secondary instance is running at PID $($manifest.pid) on port $Port." }
    return
}
if ($Action -eq 'Stop') {
    if (-not $AcknowledgeImpact) { throw 'Stop interrupts a Realtime instance. Retry with -AcknowledgeImpact.' }
    if ($null -eq $manifest) { Write-Host 'Realtime secondary instance is already stopped.'; return }
    $process = Test-InstanceProcess -ProcessId ([int]$manifest.pid)
    if ($null -ne $process) { & taskkill.exe /PID ([int]$manifest.pid) /T /F *> $null }
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
    Write-Host '[PASS] Secondary Realtime instance stopped; primary application and data volumes were unchanged.'
    return
}
if ($null -ne $manifest -and $null -ne (Test-InstanceProcess -ProcessId ([int]$manifest.pid))) {
    Write-Host "Realtime secondary instance is already running at PID $($manifest.pid)."
    return
}
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw 'Realtime JAR is missing. Build the project first.' }
Import-DotEnv
$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -gt 0) { throw "Port $Port is already occupied." }
New-Item -ItemType Directory -Path (Join-Path $RuntimeDirectory 'logs') -Force | Out-Null
$logDir = Join-Path $RuntimeDirectory ('logs\' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$stdout = Join-Path $logDir 'realtime.out.log'; $stderr = Join-Path $logDir 'realtime.err.log'
$java = (Get-Command java -ErrorAction Stop).Source
$process = Start-Process -FilePath $java -ArgumentList @('-jar', ('"' + $jarPath + '"'), '--spring.profiles.active=nacos', "--server.port=$Port", "--spring.cloud.nacos.discovery.port=$Port") -WorkingDirectory $repositoryRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
$manifest = [pscustomobject]@{ version = 1; repositoryRoot = $repositoryRoot; pid = [int]$process.Id; port = $Port; jar = $jarPath; stdoutLog = $stdout; stderrLog = $stderr }
$manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Host "Started secondary Realtime instance PID $($process.Id) on port $Port. Logs: $logDir"

[CmdletBinding()]
param(
    [Parameter()]
    [string]$RuntimeDirectory = ''
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

$manifestPath = Join-Path $RuntimeDirectory 'processes.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    Write-Host 'No TideBid application PID manifest exists. Nothing was stopped.'
    return
}

try {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} catch {
    throw "Runtime manifest is invalid: $manifestPath. Inspect it before removing it."
}
if ([int]$manifest.version -ne 1 -or [string]$manifest.repositoryRoot -ne $repositoryRoot) {
    throw "Runtime manifest does not belong to this TideBid checkout: $manifestPath."
}

$taskkillCommand = Get-Command taskkill.exe -ErrorAction SilentlyContinue
if ($null -eq $taskkillCommand) {
    throw 'Windows taskkill.exe is required to stop hidden TideBid process trees safely.'
}

function Get-MatchingProcess {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][string]$Marker
    )

    try {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    } catch {
        throw "Could not inspect recorded process PID $ProcessId. No process was stopped and the manifest will be preserved: $($_.Exception.Message)"
    }
    if ($null -eq $process -or [string]::IsNullOrWhiteSpace([string]$process.CommandLine)) {
        return $null
    }
    if ($process.CommandLine.IndexOf($Marker, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
        return $null
    }
    return $process
}

$records = @($manifest.processes)
[array]::Reverse($records)
foreach ($record in $records) {
    $processId = [int]$record.pid
    $process = Get-MatchingProcess -ProcessId $processId -Marker ([string]$record.commandMarker)
    if ($null -eq $process) {
        Write-Host "$($record.name): recorded PID $processId is already gone or belongs to another command; it was not touched."
        continue
    }

    Write-Host "Stopping $($record.name) (PID $processId)..."
    & $taskkillCommand.Source /PID $processId /T /F
    if ($LASTEXITCODE -ne 0 -and
        $null -ne (Get-MatchingProcess -ProcessId $processId -Marker ([string]$record.commandMarker))) {
        throw "Could not stop recorded $($record.name) PID $processId. The manifest was preserved."
    }
}

Remove-Item -LiteralPath $manifestPath -Force
Write-Host 'TideBid application processes are stopped and the PID manifest was removed. Logs were preserved.'

[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [string]$LogDirectory = ''
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

if ([string]::IsNullOrWhiteSpace($LogDirectory)) {
    $logsRoot = Join-Path $repositoryRoot '.runtime\apps\logs'
    if (-not (Test-Path -LiteralPath $logsRoot -PathType Container)) {
        throw "Application log root does not exist: $logsRoot"
    }
    $latestRun = Get-ChildItem -LiteralPath $logsRoot -Directory |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $latestRun) {
        throw "No application log run exists below: $logsRoot"
    }
    $LogDirectory = $latestRun.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($LogDirectory)) {
    $LogDirectory = Join-Path $repositoryRoot $LogDirectory
}
$LogDirectory = [System.IO.Path]::GetFullPath($LogDirectory)
if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
    throw "Log directory does not exist: $LogDirectory"
}

$secretValues = @{}
foreach ($line in [System.IO.File]::ReadAllLines($EnvFile)) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) {
        continue
    }
    $parts = $trimmed.Split('=', 2)
    $name = $parts[0].Trim()
    $value = $parts[1].Trim()
    if ($value.Length -ge 2 -and
        (($value.StartsWith('"') -and $value.EndsWith('"')) -or
         ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    if ($name -match '(?i)(PASSWORD|SECRET|TOKEN|ACCESS_KEY|API_KEY|PRIVATE_KEY)' -and
        $name -notmatch '(?i)_PATH$' -and
        $value.Length -ge 6 -and
        $value -notmatch '(?i)^(change-me|replace-me|your-|example)') {
        $secretValues[$name] = $value
    }
}

$highConfidencePatterns = [ordered]@{
    'Authorization bearer value' = '(?i)Authorization\s*[:=]\s*Bearer\s+[^\s,;]+'
    'OSS signed credential query' = '(?i)(x-oss-signature|x-oss-credential|OSSAccessKeyId)='
    'private key material' = '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
}
$findings = [System.Collections.Generic.List[string]]::new()
$logFiles = @(Get-ChildItem -LiteralPath $LogDirectory -Recurse -File -Filter '*.log')
if ($logFiles.Count -eq 0) {
    throw "No .log files were found below: $LogDirectory"
}

foreach ($file in $logFiles) {
    $stream = [System.IO.FileStream]::new(
        $file.FullName,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite
    )
    try {
        $reader = [System.IO.StreamReader]::new($stream)
        try {
            $content = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
    $relativePath = $file.FullName.Substring($LogDirectory.TrimEnd('\').Length).TrimStart('\')
    foreach ($entry in $highConfidencePatterns.GetEnumerator()) {
        if ([regex]::IsMatch($content, $entry.Value)) {
            $findings.Add("$relativePath [$($entry.Key)]")
        }
    }
    foreach ($entry in $secretValues.GetEnumerator()) {
        if ($content.IndexOf([string]$entry.Value, [System.StringComparison]::Ordinal) -ge 0) {
            $findings.Add("$relativePath [configured value for $($entry.Key)]")
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host '[FAIL] Sensitive runtime log findings were detected. Values are intentionally not displayed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
    throw "Runtime log audit failed with $($findings.Count) finding(s)."
}

Write-Host "[PASS] Audited $($logFiles.Count) log file(s) without exposing or finding configured secrets, bearer credentials, OSS signatures or private keys."
Write-Host "Log directory: $LogDirectory"

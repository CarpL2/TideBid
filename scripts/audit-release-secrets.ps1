[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [ValidateRange(1, 500)]
    [int]$RedisStringSampleLimit = 50
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
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "Environment file does not exist: $EnvFile"
}

function Read-DotEnvSecretValues {
    $values = [System.Collections.Generic.List[string]]::new()
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
            $values.Add($value)
        }
    }
    return @($values | Select-Object -Unique)
}

function ConvertTo-ShellSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)

    if ($Value.Contains("'")) {
        throw 'Redis audit encountered an unsupported single quote in an argument; no value was printed.'
    }
    return "'$Value'"
}

function Invoke-RedisCli {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $quotedArguments = $Arguments | ForEach-Object { ConvertTo-ShellSingleQuoted -Value $_ }
    $shellCommand = 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --no-auth-warning --raw ' +
        ($quotedArguments -join ' ')
    $output = @(& $script:dockerCommand.Source compose --env-file $EnvFile -f $composeFile `
            exec -T redis sh -lc $shellCommand)
    if ($LASTEXITCODE -ne 0) {
        throw "Redis audit command failed: $($Arguments[0])."
    }
    return $output
}

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($null -eq $dockerCommand) {
    throw 'Docker CLI was not found.'
}
$gitCommand = Get-Command git -ErrorAction SilentlyContinue
if ($null -eq $gitCommand) {
    throw 'Git CLI was not found.'
}

$secretValues = @(Read-DotEnvSecretValues)
$trackedPaths = @(& $gitCommand.Source -C $repositoryRoot ls-files)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not enumerate Git tracked files.'
}

$forbiddenTrackedPaths = @($trackedPaths | Where-Object {
        $_ -match '(^|/)(\.env|\.runtime)(/|$)' -or
        $_ -match '(?i)\.(pem|key|p12|pfx|jks|log)$'
    })
$textExtensions = @(
    '.java', '.xml', '.yml', '.yaml', '.properties', '.sql', '.ps1', '.md',
    '.json', '.ts', '.vue', '.js', '.mjs', '.example'
)
$specialTextFiles = @('.gitignore', '.gitattributes', '.editorconfig')
$contentFindings = [System.Collections.Generic.List[string]]::new()
$scannedTextFiles = 0
foreach ($relativePath in $trackedPaths) {
    $path = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        continue
    }
    $extension = [System.IO.Path]::GetExtension($path).ToLowerInvariant()
    $leafName = [System.IO.Path]::GetFileName($path).ToLowerInvariant()
    if ($textExtensions -notcontains $extension -and $specialTextFiles -notcontains $leafName) {
        continue
    }
    try {
        $content = [System.IO.File]::ReadAllText($path)
    } catch {
        continue
    }
    $scannedTextFiles++
    if ($content -match '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----') {
        $contentFindings.Add("$relativePath [private key material]")
    }
    foreach ($secretValue in $secretValues) {
        if ($content.IndexOf($secretValue, [System.StringComparison]::Ordinal) -ge 0) {
            $contentFindings.Add("$relativePath [configured secret value]")
            break
        }
    }
}

if ($forbiddenTrackedPaths.Count -gt 0 -or $contentFindings.Count -gt 0) {
    Write-Host '[FAIL] Repository secret audit detected findings; values are intentionally suppressed.'
    $forbiddenTrackedPaths | ForEach-Object { Write-Host "  $_ [forbidden tracked path]" }
    $contentFindings | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
    throw 'Repository secret audit failed.'
}
Write-Host "[PASS] Repository audit: $($trackedPaths.Count) tracked paths and $scannedTextFiles text files contain no runtime credential files or configured secret values."

$redisKeys = @(Invoke-RedisCli -Arguments @('--scan'))
$typeCounts = @{}
$persistentKeys = 0
$sampledStrings = 0
$redisFindings = 0
foreach ($key in $redisKeys) {
    if ([string]::IsNullOrWhiteSpace($key)) {
        continue
    }
    $type = [string](Invoke-RedisCli -Arguments @('TYPE', $key) | Select-Object -First 1)
    if (-not $typeCounts.ContainsKey($type)) {
        $typeCounts[$type] = 0
    }
    $typeCounts[$type]++

    $ttl = [int](Invoke-RedisCli -Arguments @('TTL', $key) | Select-Object -First 1)
    if ($ttl -eq -1) {
        $persistentKeys++
    }

    if ($type -eq 'string' -and $sampledStrings -lt $RedisStringSampleLimit) {
        $value = (Invoke-RedisCli -Arguments @('GET', $key)) -join [Environment]::NewLine
        foreach ($secretValue in $secretValues) {
            if ($value.IndexOf($secretValue, [System.StringComparison]::Ordinal) -ge 0) {
                $redisFindings++
                break
            }
        }
        if ($value -match '(?i)Authorization\s*[:=]\s*Bearer\s+|x-oss-signature=|-----BEGIN .*PRIVATE KEY-----') {
            $redisFindings++
        }
        $sampledStrings++
    }
}

if ($redisFindings -gt 0) {
    throw "Redis sample audit found $redisFindings sensitive-value finding(s); values are intentionally suppressed."
}
$typeSummary = ($typeCounts.GetEnumerator() | Sort-Object Name | ForEach-Object {
        "$($_.Name)=$($_.Value)"
    }) -join ', '
Write-Host "[PASS] Redis audit: keys=$($redisKeys.Count), sampledStrings=$sampledStrings, persistentKeys=$persistentKeys, types=[$typeSummary]."
Write-Host '[PASS] Redis samples contain no configured secret, bearer credential, OSS signature or private-key pattern.'

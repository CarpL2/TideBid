[CmdletBinding()]
param(
    [Parameter()]
    [string]$EnvFile = '',

    [Parameter()]
    [string]$RuntimeDirectory = '',

    [Parameter()]
    [ValidateRange(30, 600)]
    [int]$StartupTimeoutSeconds = 180,

    [Parameter()]
    [switch]$SkipBuild
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
$logsRoot = Join-Path $RuntimeDirectory 'logs'
$serviceDefinitions = @(
    [pscustomobject]@{ Name = 'account'; Jar = 'services/account-service/target/account-service.jar'; Port = 9101 },
    [pscustomobject]@{ Name = 'auction'; Jar = 'services/auction-service/target/auction-service.jar'; Port = 9102 },
    [pscustomobject]@{ Name = 'trade'; Jar = 'services/trade-service/target/trade-service.jar'; Port = 9103 },
    [pscustomobject]@{ Name = 'realtime'; Jar = 'services/realtime-service/target/realtime-service.jar'; Port = 9104 },
    [pscustomobject]@{ Name = 'ai'; Jar = 'services/ai-service/target/ai-service.jar'; Port = 9105 },
    [pscustomobject]@{ Name = 'gateway'; Jar = 'services/gateway-service/target/gateway-service.jar'; Port = 9000 }
)

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
        if ($values.ContainsKey($name)) {
            throw "Duplicate environment variable in .env: $name."
        }

        $value = $line.Substring($separator + 1).Trim()
        if ($value.Length -ge 2) {
            $doubleQuoted = $value.StartsWith('"') -and $value.EndsWith('"')
            $singleQuoted = $value.StartsWith("'") -and $value.EndsWith("'")
            if ($doubleQuoted -or $singleQuoted) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        $values.Add($name, $value)
    }
    return $values
}

function Assert-RequiredEnvironment {
    $requiredNames = @(
        'TIDEBID_ACCOUNT_DB_PASSWORD',
        'TIDEBID_REDIS_PASSWORD',
        'TIDEBID_NACOS_USERNAME',
        'TIDEBID_NACOS_PASSWORD',
        'TIDEBID_JWT_PRIVATE_KEY_PATH',
        'TIDEBID_JWT_PUBLIC_KEY_PATH'
    )
    $invalidNames = @()
    foreach ($name in $requiredNames) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('change-me')) {
            $invalidNames += $name
        }
    }
    if ($invalidNames.Count -gt 0) {
        throw "Set these required application values in .env or the current process environment: $($invalidNames -join ', ')."
    }
}

function Assert-CommandVersion {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Requirement
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "$Requirement Command '$Name' was not found."
    }
    $versionOutput = (& $command.Source @Arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch $Pattern) {
        throw "$Requirement Found: $($versionOutput.Split([Environment]::NewLine)[0])"
    }
    return $command
}

function Resolve-RepositoryPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Path))
}

function Test-ProcessMatchesMarker {
    param(
        [Parameter(Mandatory = $true)][int]$ProcessId,
        [Parameter(Mandatory = $true)][string]$Marker
    )

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process -or [string]::IsNullOrWhiteSpace([string]$process.CommandLine)) {
        return $false
    }
    return $process.CommandLine.IndexOf($Marker, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Test-HttpEndpoint {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2
        return [int]$response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Wait-ApplicationReady {
    param(
        [Parameter(Mandatory = $true)]$Record,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][int]$Timeout
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Timeout)
    do {
        if (-not (Test-ProcessMatchesMarker -ProcessId ([int]$Record.pid) -Marker ([string]$Record.commandMarker))) {
            throw "$($Record.name) exited before becoming ready. Inspect $($Record.stderrLog)."
        }
        if (Test-HttpEndpoint -Uri $Uri) {
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "$($Record.name) did not become ready within $Timeout seconds. Inspect $($Record.stdoutLog) and $($Record.stderrLog)."
}

function Write-ProcessManifest {
    param([Parameter(Mandatory = $true)]$Records)

    $manifest = [ordered]@{
        version = 1
        repositoryRoot = $repositoryRoot
        startedAt = [DateTimeOffset]::UtcNow.ToString('o')
        processes = @($Records)
    }
    $json = $manifest | ConvertTo-Json -Depth 5
    $temporaryPath = $manifestPath + '.tmp'
    [System.IO.File]::WriteAllText(
        $temporaryPath,
        $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryPath -Destination $manifestPath -Force
}

function Get-RecordedActiveProcesses {
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        return @()
    }
    try {
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    } catch {
        throw "Runtime manifest is invalid: $manifestPath. Inspect it before removing it."
    }
    if ([string]$manifest.repositoryRoot -ne $repositoryRoot) {
        throw "Runtime manifest belongs to another repository: $manifestPath."
    }

    $active = @()
    foreach ($record in @($manifest.processes)) {
        if (Test-ProcessMatchesMarker -ProcessId ([int]$record.pid) -Marker ([string]$record.commandMarker)) {
            $active += $record
        }
    }
    return $active
}

function Assert-PortsAvailable {
    param([Parameter(Mandatory = $true)]$Definitions)

    foreach ($definition in $Definitions) {
        $listeners = @(Get-NetTCPConnection `
                -LocalPort ([int]$definition.Port) `
                -State Listen `
                -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) {
            $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
            throw "Port $($definition.Port) for $($definition.Name) is already occupied by PID(s) $($owners -join ', '). Stop that process or change the project port before retrying."
        }
    }
}

function Start-LoggedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$WorkingDirectory,
        [Parameter(Mandatory = $true)][string]$CommandMarker,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][string]$LogDirectory
    )

    $stdoutLog = Join-Path $LogDirectory "$Name.out.log"
    $stderrLog = Join-Path $LogDirectory "$Name.err.log"
    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden `
        -PassThru
    return [pscustomobject]@{
        name = $Name
        pid = [int]$process.Id
        port = $Port
        commandMarker = $CommandMarker
        stdoutLog = $stdoutLog
        stderrLog = $stderrLog
    }
}

function Stop-StartedRecords {
    param([Parameter(Mandatory = $true)]$Records)

    $taskkillCommand = Get-Command taskkill.exe -ErrorAction SilentlyContinue
    $recordArray = @($Records)
    [array]::Reverse($recordArray)
    foreach ($record in $recordArray) {
        $processId = [int]$record.pid
        if (-not (Test-ProcessMatchesMarker -ProcessId $processId -Marker ([string]$record.commandMarker))) {
            continue
        }
        if ($null -ne $taskkillCommand) {
            & $taskkillCommand.Source /PID $processId /T /F *> $null
        } else {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        }
    }
}

New-Item -ItemType Directory -Path $RuntimeDirectory -Force | Out-Null
$existingActiveProcesses = @(Get-RecordedActiveProcesses)
if ($existingActiveProcesses.Count -gt 0) {
    if ($existingActiveProcesses.Count -eq 7) {
        $allReady = $true
        foreach ($record in $existingActiveProcesses) {
            $endpoint = if ([string]$record.name -eq 'web') {
                'http://127.0.0.1:5173/'
            } else {
                "http://127.0.0.1:$($record.port)/actuator/health"
            }
            if (-not (Test-HttpEndpoint -Uri $endpoint)) {
                $allReady = $false
                break
            }
        }
        if ($allReady) {
            Write-Host "TideBid applications are already running from recorded PIDs in $manifestPath."
            return
        }
    }
    throw "A partial or unhealthy TideBid application set is still recorded in $manifestPath. Run .\scripts\stop-apps.ps1 before starting again."
}
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    Remove-Item -LiteralPath $manifestPath -Force
}

$allPortDefinitions = @($serviceDefinitions) + @([pscustomobject]@{ Name = 'web'; Port = 5173 })
Assert-PortsAvailable -Definitions $allPortDefinitions

$dotEnvValues = Read-DotEnvFile -Path $EnvFile
$environmentRestore = @()
foreach ($entry in $dotEnvValues.GetEnumerator()) {
    $existingValue = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    if ([string]::IsNullOrEmpty($existingValue)) {
        $environmentRestore += [pscustomobject]@{ Name = $entry.Key; Value = $existingValue }
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}

$startedRecords = [System.Collections.Generic.List[object]]::new()
try {
    Assert-RequiredEnvironment
    $javaCommand = Assert-CommandVersion `
        -Name 'java' `
        -Arguments @('--version') `
        -Pattern '^(java|openjdk) 21([\.\s]|$)' `
        -Requirement 'Java 21 is required.'
    $mavenCommand = Assert-CommandVersion `
        -Name 'mvn' `
        -Arguments @('--version') `
        -Pattern 'Apache Maven (3\.9\.|[4-9]\.)' `
        -Requirement 'Maven 3.9 or newer is required.'
    $nodeCommand = Assert-CommandVersion `
        -Name 'node' `
        -Arguments @('--version') `
        -Pattern '^v24\.' `
        -Requirement 'Node.js 24 is required.'
    $pnpmCommand = Assert-CommandVersion `
        -Name 'pnpm.cmd' `
        -Arguments @('--version') `
        -Pattern '^11\.' `
        -Requirement 'pnpm 11 is required.'

    $privateKeyPath = Resolve-RepositoryPath `
        -Path ([Environment]::GetEnvironmentVariable('TIDEBID_JWT_PRIVATE_KEY_PATH', 'Process'))
    $publicKeyPath = Resolve-RepositoryPath `
        -Path ([Environment]::GetEnvironmentVariable('TIDEBID_JWT_PUBLIC_KEY_PATH', 'Process'))
    if ([System.IO.Path]::GetFileName($privateKeyPath) -ne 'jwt-private.pem' -or
        [System.IO.Path]::GetFileName($publicKeyPath) -ne 'jwt-public.pem' -or
        [System.IO.Path]::GetDirectoryName($privateKeyPath) -ne [System.IO.Path]::GetDirectoryName($publicKeyPath)) {
        throw 'JWT key paths must share one directory and use jwt-private.pem / jwt-public.pem filenames.'
    }

    if (-not $SkipBuild) {
        Write-Host 'Building TideBid application JARs without rerunning tests...'
        Push-Location $repositoryRoot
        try {
            & $mavenCommand.Source -B -ntp -DskipTests package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven package failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
    }

    foreach ($definition in $serviceDefinitions) {
        $jarPath = Resolve-RepositoryPath -Path ([string]$definition.Jar)
        if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
            throw "Missing application JAR: $jarPath. Run without -SkipBuild."
        }
    }

    $viteEntry = Join-Path $repositoryRoot 'web\node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $viteEntry -PathType Leaf)) {
        Write-Host 'Installing locked frontend dependencies...'
        Push-Location (Join-Path $repositoryRoot 'web')
        try {
            & $pnpmCommand.Source install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) {
                throw "pnpm install failed with exit code $LASTEXITCODE."
            }
        } finally {
            Pop-Location
        }
    }

    Write-Host 'Preparing JWT development keys...'
    & (Join-Path $PSScriptRoot 'generate-jwt-keys.ps1') `
        -OutputDirectory ([System.IO.Path]::GetDirectoryName($privateKeyPath))

    Write-Host 'Importing managed Nacos configuration...'
    & (Join-Path $PSScriptRoot 'import-nacos-config.ps1') `
        -EnvFile $EnvFile `
        -TimeoutSeconds $StartupTimeoutSeconds

    $runId = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $logDirectory = Join-Path $logsRoot $runId
    New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

    foreach ($definition in $serviceDefinitions) {
        $jarPath = Resolve-RepositoryPath -Path ([string]$definition.Jar)
        Write-Host "Starting $($definition.Name) on port $($definition.Port)..."
        $record = Start-LoggedProcess `
            -Name ([string]$definition.Name) `
            -FilePath $javaCommand.Source `
            -ArgumentList @('-jar', ('"' + $jarPath + '"'), '--spring.profiles.active=nacos') `
            -WorkingDirectory $repositoryRoot `
            -CommandMarker $jarPath `
            -Port ([int]$definition.Port) `
            -LogDirectory $logDirectory
        $startedRecords.Add($record)
        Write-ProcessManifest -Records $startedRecords
        Wait-ApplicationReady `
            -Record $record `
            -Uri "http://127.0.0.1:$($definition.Port)/actuator/health" `
            -Timeout $StartupTimeoutSeconds
    }

    Write-Host 'Starting web on port 5173...'
    $webRecord = Start-LoggedProcess `
        -Name 'web' `
        -FilePath $nodeCommand.Source `
        -ArgumentList @(('"' + $viteEntry + '"'), '--host', '127.0.0.1', '--port', '5173', '--strictPort') `
        -WorkingDirectory (Join-Path $repositoryRoot 'web') `
        -CommandMarker $viteEntry `
        -Port 5173 `
        -LogDirectory $logDirectory
    $startedRecords.Add($webRecord)
    Write-ProcessManifest -Records $startedRecords
    Wait-ApplicationReady `
        -Record $webRecord `
        -Uri 'http://127.0.0.1:5173/' `
        -Timeout $StartupTimeoutSeconds

    Write-Host 'TideBid applications are ready.'
    Write-Host '  Web:     http://127.0.0.1:5173/'
    Write-Host '  Gateway: http://127.0.0.1:9000/actuator/health'
    Write-Host "  PIDs:    $manifestPath"
    Write-Host "  Logs:    $logDirectory"
} catch {
    if ($startedRecords.Count -gt 0) {
        Write-Warning 'Application startup failed; stopping only the processes started by this run.'
        Stop-StartedRecords -Records $startedRecords
    }
    if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
        Remove-Item -LiteralPath $manifestPath -Force
    }
    throw
} finally {
    foreach ($entry in $environmentRestore) {
        [Environment]::SetEnvironmentVariable($entry.Name, $entry.Value, 'Process')
    }
}

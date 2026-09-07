[CmdletBinding()]
param(
    [Parameter()]
    [string]$OutputDirectory = '',

    [Parameter()]
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot '.runtime\keys'
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repositoryRoot $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

$javaCommand = Get-Command java -ErrorAction Stop
$javaVersionLine = (& $javaCommand.Source --version | Select-Object -First 1)
if ($javaVersionLine -notmatch '^(java|openjdk) 21([\.\s]|$)') {
    throw "Java 21 is required to generate TideBid JWT keys. Found: $javaVersionLine"
}

$privateKeyPath = Join-Path $OutputDirectory 'jwt-private.pem'
$publicKeyPath = Join-Path $OutputDirectory 'jwt-public.pem'
$helperPath = Join-Path $PSScriptRoot 'JwtKeyGenerator.java'
$generatorArguments = @('--source', '21', $helperPath, $privateKeyPath, $publicKeyPath)
if ($Force) {
    $generatorArguments += '--force'
}

& $javaCommand.Source @generatorArguments
if ($LASTEXITCODE -ne 0) {
    throw "JWT key generation failed with exit code $LASTEXITCODE."
}

Write-Host 'JWT key preparation completed. The private key remains under the Git-ignored .runtime directory.'

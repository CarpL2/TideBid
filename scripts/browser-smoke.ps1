[CmdletBinding()]
param(
    [Parameter()]
    [string]$Url = 'http://127.0.0.1:5173/',

    [Parameter()]
    [ValidateRange(1, 30)]
    [int]$VirtualTimeSeconds = 6
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$chromeCandidates = @(
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
    'C:\Program Files\Microsoft\Edge\Application\msedge.exe'
)
$chrome = $chromeCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($chrome)) {
    throw 'Chrome or Edge executable was not found for the headless browser smoke check.'
}

$runtimeDirectory = Join-Path $repositoryRoot '.runtime\browser-smoke'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$runId = [Guid]::NewGuid().ToString('N')
$profile = Join-Path $runtimeDirectory "profile-$runId"
$domFile = Join-Path $runtimeDirectory "dom-$runId.html"
$errorFile = Join-Path $runtimeDirectory "stderr-$runId.log"
try {
    $arguments = @(
        '--headless=new', '--disable-gpu', '--no-sandbox',
        "--user-data-dir=$profile", '--dump-dom',
        "--virtual-time-budget=$($VirtualTimeSeconds * 1000)",
        '--window-size=1440,1000', $Url
    )
    & $chrome @arguments 2> $errorFile | Out-File -LiteralPath $domFile -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "Headless browser exited with code $LASTEXITCODE."
    }
    $dom = Get-Content -LiteralPath $domFile -Raw
    if ($dom -notmatch '<html') { throw 'Browser did not return an HTML document.' }
    if ($dom -notmatch 'id="app"') { throw 'Vue application root was not present in the rendered DOM.' }
    if ($dom -notmatch 'TideBid|实时竞价') { throw 'Rendered DOM did not contain TideBid application text.' }
    Write-Host "[PASS] browser-smoke url=$Url browser=$([System.IO.Path]::GetFileName($chrome)) domBytes=$([Text.Encoding]::UTF8.GetByteCount($dom))"
} finally {
    Remove-Item -LiteralPath $profile, $domFile, $errorFile -Recurse -Force -ErrorAction SilentlyContinue
}

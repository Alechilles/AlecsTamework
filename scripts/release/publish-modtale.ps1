param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,
    [string]$ChangelogPath = "artifacts/changelog.md",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$ApiKey = $env:MODTALE_API_KEY,
    [string]$ApiToken = $env:MODTALE_API_TOKEN,
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -Path $ConfigPath)) {
    throw "Release config '$ConfigPath' was not found."
}
if (-not (Test-Path -Path $ArtifactPath)) {
    throw "Artifact '$ArtifactPath' was not found."
}
if (-not (Test-Path -Path $ChangelogPath)) {
    throw "Changelog '$ChangelogPath' was not found."
}

$config = Get-Content -Path $ConfigPath -Raw | ConvertFrom-Json
$normalizedVersion = (($Version.Trim()) -replace "^v", "")
$projectId = $config.modtale.projectId

$effectiveApiKey = $ApiKey
if ([string]::IsNullOrWhiteSpace($effectiveApiKey)) {
    # Backward-compat alias for older secret naming.
    $effectiveApiKey = $ApiToken
}

$endpoint = if ([string]::IsNullOrWhiteSpace($projectId)) {
    "https://api.modtale.net/api/v1/projects/<project-id>/versions"
} else {
    "https://api.modtale.net/api/v1/projects/$projectId/versions"
}
$changelog = Get-Content -Path $ChangelogPath -Raw
$rawChannel = $config.modtale.releaseChannel
if ([string]::IsNullOrWhiteSpace($rawChannel)) {
    $rawChannel = "stable"
}
$channelKey = $rawChannel.Trim().ToLowerInvariant()
$channel = switch ($channelKey) {
    "stable" { "RELEASE" }
    "release" { "RELEASE" }
    "beta" { "BETA" }
    "alpha" { "ALPHA" }
    default { $rawChannel.Trim().ToUpperInvariant() }
}

$versionFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.versionFieldName)) { "versionNumber" } else { $config.modtale.versionFieldName }
$changelogFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.changelogFieldName)) { "changelog" } else { $config.modtale.changelogFieldName }
$channelFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.channelFieldName)) { "channel" } else { $config.modtale.channelFieldName }
$gameVersionFieldName = if ([string]::IsNullOrWhiteSpace($config.modtale.gameVersionFieldName)) { "gameVersions" } else { $config.modtale.gameVersionFieldName }

if ($DryRun) {
    Write-Host "Dry-run: would publish '$ArtifactPath' to Modtale project '$projectId'."
    Write-Host "Endpoint: $endpoint"
    Write-Host "Version: $normalizedVersion"
    Write-Host "Channel: $channel"
    if ([string]::IsNullOrWhiteSpace($projectId)) {
        Write-Host "Note: modtale.projectId is empty in $ConfigPath."
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($projectId)) {
    throw "modtale.projectId is empty in $ConfigPath."
}

if ([string]::IsNullOrWhiteSpace($effectiveApiKey)) {
    throw "MODTALE_API_KEY is required when DryRun is false (MODTALE_API_KEY/MODTALE_API_TOKEN env var or -ApiKey/-ApiToken)."
}

$curlArgs = @(
    "-sS",
    "-X", "POST",
    $endpoint,
    "-H", "X-MODTALE-KEY: $effectiveApiKey",
    "-F", "$versionFieldName=$normalizedVersion",
    "-F", "$changelogFieldName=$changelog",
    "-F", "$channelFieldName=$channel"
)

foreach ($gameVersion in @($config.modtale.gameVersions)) {
    $curlArgs += @("-F", "$gameVersionFieldName=$gameVersion")
}

$curlArgs += @("-F", "file=@$ArtifactPath")

$responseTempFile = New-TemporaryFile
$statusCode = & curl.exe @curlArgs `
    -o $responseTempFile `
    -w "%{http_code}"
$statusCode = $statusCode.Trim()

if ($LASTEXITCODE -ne 0) {
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    throw "Modtale upload failed with exit code $LASTEXITCODE."
}

$response = ""
if (Test-Path -Path $responseTempFile) {
    $response = Get-Content -Path $responseTempFile -Raw
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
}

$statusCodeInt = 0
if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
    throw "Modtale upload failed with an invalid HTTP status value '$statusCode'."
}

if ($statusCodeInt -lt 200 -or $statusCodeInt -ge 300) {
    $responseSummary = if ([string]::IsNullOrWhiteSpace($response)) { "<empty>" } else { $response }

    $knownVersionsSummary = ""
    $knownVersionsTempFile = New-TemporaryFile
    $knownVersionsStatus = & curl.exe `
        -sS `
        -o $knownVersionsTempFile `
        -w "%{http_code}" `
        -X GET `
        "https://api.modtale.net/api/v1/meta/game-versions" `
        -H "X-MODTALE-KEY: $effectiveApiKey"
    if ($LASTEXITCODE -eq 0) {
        $knownVersionsBody = Get-Content -Path $knownVersionsTempFile -Raw
        if ($knownVersionsStatus -eq "200" -and -not [string]::IsNullOrWhiteSpace($knownVersionsBody)) {
            $knownVersionsSummary = " Known game versions response: $knownVersionsBody"
        }
    }
    Remove-Item -Path $knownVersionsTempFile -Force -ErrorAction SilentlyContinue

    throw "Modtale upload failed with HTTP status $statusCode. Response: $responseSummary$knownVersionsSummary"
}

Write-Host "Modtale upload completed (HTTP $statusCode)."
if (-not [string]::IsNullOrWhiteSpace($response)) {
    Write-Output $response
}

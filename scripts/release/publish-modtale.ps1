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

$response = & curl.exe @curlArgs
if ($LASTEXITCODE -ne 0) {
    throw "Modtale upload failed with exit code $LASTEXITCODE."
}

Write-Host "Modtale upload completed."
Write-Output $response

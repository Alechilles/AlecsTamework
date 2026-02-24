param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,
    [string]$ChangelogPath = "artifacts/changelog.md",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$ApiToken = $env:CURSEFORGE_API_TOKEN,
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
$projectId = $config.curseforge.projectId

$endpoint = if ([string]::IsNullOrWhiteSpace($projectId)) {
    "https://hytale.curseforge.com/api/projects/<project-id>/upload-file"
} else {
    "https://hytale.curseforge.com/api/projects/$projectId/upload-file"
}
$changelog = Get-Content -Path $ChangelogPath -Raw
$displayName = "$($config.modName) v$normalizedVersion"

$metadataObject = @{
    changelog = $changelog
    changelogType = "markdown"
    displayName = $displayName
    releaseType = $config.curseforge.releaseType
    gameVersions = @($config.curseforge.gameVersions)
}
$metadataJson = $metadataObject | ConvertTo-Json -Depth 16 -Compress

if ($DryRun) {
    Write-Host "Dry-run: would publish '$ArtifactPath' to CurseForge project '$projectId'."
    Write-Host "Endpoint: $endpoint"
    Write-Host "Display name: $displayName"
    if ([string]::IsNullOrWhiteSpace($projectId)) {
        Write-Host "Note: curseforge.projectId is empty in $ConfigPath."
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($projectId)) {
    throw "curseforge.projectId is empty in $ConfigPath."
}

if ([string]::IsNullOrWhiteSpace($ApiToken)) {
    throw "CURSEFORGE_API_TOKEN is required when DryRun is false (env var or -ApiToken)."
}

$response = & curl.exe `
    -sS `
    -X POST `
    $endpoint `
    -H "X-Api-Token: $ApiToken" `
    -F "metadata=$metadataJson" `
    -F "file=@$ArtifactPath"

if ($LASTEXITCODE -ne 0) {
    throw "CurseForge upload failed with exit code $LASTEXITCODE."
}

Write-Host "CurseForge upload completed."
Write-Output $response

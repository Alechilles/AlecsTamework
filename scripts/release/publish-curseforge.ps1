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
$gameVersionTypeIds = @($config.curseforge.gameVersionTypeIds)

$apiBaseUrl = "https://www.curseforge.com/api"
$endpoint = if ([string]::IsNullOrWhiteSpace($projectId)) {
    "$apiBaseUrl/projects/<project-id>/upload-file"
} else {
    "$apiBaseUrl/projects/$projectId/upload-file"
}
$changelog = Get-Content -Path $ChangelogPath -Raw
$displayName = "$($config.modName) v$normalizedVersion"

$metadataObject = @{
    changelog = $changelog
    changelogType = "markdown"
    displayName = $displayName
    releaseType = $config.curseforge.releaseType
    gameVersionTypeIds = $gameVersionTypeIds
}
$metadataJson = $metadataObject | ConvertTo-Json -Depth 16 -Compress

if ($DryRun) {
    Write-Host "Dry-run: would publish '$ArtifactPath' to CurseForge project '$projectId'."
    Write-Host "Endpoint: $endpoint"
    Write-Host "Display name: $displayName"
    if ([string]::IsNullOrWhiteSpace($projectId)) {
        Write-Host "Note: curseforge.projectId is empty in $ConfigPath."
    }
    if ($gameVersionTypeIds.Count -eq 0) {
        Write-Host "Note: curseforge.gameVersionTypeIds is empty in $ConfigPath."
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($projectId)) {
    throw "curseforge.projectId is empty in $ConfigPath."
}

if ([string]::IsNullOrWhiteSpace($ApiToken)) {
    throw "CURSEFORGE_API_TOKEN is required when DryRun is false (env var or -ApiToken)."
}
if ($gameVersionTypeIds.Count -eq 0) {
    throw "curseforge.gameVersionTypeIds is empty in $ConfigPath."
}

$responseTempFile = New-TemporaryFile
$statusCode = & curl.exe `
    -sS `
    -o $responseTempFile `
    -w "%{http_code}" `
    -X POST `
    $endpoint `
    -H "X-Api-Token: $ApiToken" `
    -F "metadata=$metadataJson" `
    -F "file=@$ArtifactPath"
$statusCode = $statusCode.Trim()

if ($LASTEXITCODE -ne 0) {
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    throw "CurseForge upload failed with exit code $LASTEXITCODE."
}

$response = ""
if (Test-Path -Path $responseTempFile) {
    $response = Get-Content -Path $responseTempFile -Raw
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
}

$statusCodeInt = 0
if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
    throw "CurseForge upload failed with an invalid HTTP status value '$statusCode'."
}

if ($statusCodeInt -lt 200 -or $statusCodeInt -ge 300) {
    $responseSummary = if ([string]::IsNullOrWhiteSpace($response)) { "<empty>" } else { $response }
    throw "CurseForge upload failed with HTTP status $statusCode. Response: $responseSummary"
}

Write-Host "CurseForge upload completed (HTTP $statusCode)."
if (-not [string]::IsNullOrWhiteSpace($response)) {
    Write-Output $response
}

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

function Resolve-CurseForgeProjectSlug {
    param(
        [string]$ApiBaseUrl,
        [int]$DependencyProjectId,
        [string]$ApiToken
    )

    $endpoint = "$ApiBaseUrl/projects/$DependencyProjectId"
    $responseTempFile = New-TemporaryFile
    $statusCode = & curl.exe `
        -sS `
        -o $responseTempFile `
        -w "%{http_code}" `
        -X GET `
        $endpoint `
        -H "X-Api-Token: $ApiToken"
    $statusCode = $statusCode.Trim()

    if ($LASTEXITCODE -ne 0) {
        Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
        throw "Failed to resolve CurseForge dependency slug for project '$DependencyProjectId' (curl exit code $LASTEXITCODE)."
    }

    $responseBody = ""
    if (Test-Path -Path $responseTempFile) {
        $responseBody = Get-Content -Path $responseTempFile -Raw
        Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    }

    $statusCodeInt = 0
    if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
        throw "Invalid HTTP status '$statusCode' while resolving CurseForge dependency project '$DependencyProjectId'."
    }
    if ($statusCodeInt -lt 200 -or $statusCodeInt -ge 300) {
        $summary = if ([string]::IsNullOrWhiteSpace($responseBody)) { "<empty>" } else { $responseBody }
        throw "Failed to resolve CurseForge dependency project '$DependencyProjectId' (HTTP $statusCode). Response: $summary"
    }

    $payload = $null
    try {
        $payload = $responseBody | ConvertFrom-Json
    } catch {
        throw "Failed to parse CurseForge dependency project '$DependencyProjectId' response JSON."
    }

    $slug = $null
    if ($null -ne $payload -and $payload.PSObject.Properties["slug"]) {
        $slug = [string]$payload.slug
    } elseif ($null -ne $payload -and $payload.PSObject.Properties["data"] -and $null -ne $payload.data -and $payload.data.PSObject.Properties["slug"]) {
        $slug = [string]$payload.data.slug
    }

    if ([string]::IsNullOrWhiteSpace($slug)) {
        throw "CurseForge dependency project '$DependencyProjectId' did not include a slug in the API response."
    }

    return $slug.Trim()
}

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
$curseforgeConfig = $config.curseforge
$projectId = $curseforgeConfig.projectId
$gameVersionTypeIds = @($curseforgeConfig.gameVersionTypeIds)
$requiredProjectIdsProperty = $curseforgeConfig.PSObject.Properties["requiredProjectIds"]
$requiredProjectIds = if ($null -eq $requiredProjectIdsProperty) { @() } else { @($requiredProjectIdsProperty.Value) }

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
    releaseType = $curseforgeConfig.releaseType
    gameVersionTypeIds = $gameVersionTypeIds
}

$relationsProjects = @()
foreach ($dependencyProjectId in $requiredProjectIds) {
    $projectIdInt = 0
    if (-not [int]::TryParse("$dependencyProjectId", [ref]$projectIdInt)) {
        throw "curseforge.requiredProjectIds entry '$dependencyProjectId' is not a valid project ID."
    }

    $relationProject = @{
        id = $projectIdInt
        type = "requiredDependency"
    }

    if (-not $DryRun) {
        if ([string]::IsNullOrWhiteSpace($ApiToken)) {
            throw "CURSEFORGE_API_TOKEN is required to resolve dependency slugs for requiredProjectIds."
        }
        $relationProject.slug = Resolve-CurseForgeProjectSlug -ApiBaseUrl $apiBaseUrl -DependencyProjectId $projectIdInt -ApiToken $ApiToken
    }

    $relationsProjects += $relationProject
}

if ($relationsProjects.Count -gt 0) {
    $metadataObject.relations = @{
        projects = $relationsProjects
    }
}

$metadataJson = $metadataObject | ConvertTo-Json -Depth 16 -Compress
$metadataTempFile = New-TemporaryFile
Set-Content -Path $metadataTempFile -Value $metadataJson -NoNewline -Encoding utf8

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
    if ($requiredProjectIds.Count -gt 0) {
        Write-Host "Required dependency project IDs: $($requiredProjectIds -join ', ')"
    }
    Remove-Item -Path $metadataTempFile -Force -ErrorAction SilentlyContinue
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
    -F "metadata=<$metadataTempFile;type=application/json" `
    -F "file=@$ArtifactPath"
$statusCode = $statusCode.Trim()

if ($LASTEXITCODE -ne 0) {
    Remove-Item -Path $metadataTempFile -Force -ErrorAction SilentlyContinue
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
    throw "CurseForge upload failed with exit code $LASTEXITCODE."
}
Remove-Item -Path $metadataTempFile -Force -ErrorAction SilentlyContinue

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

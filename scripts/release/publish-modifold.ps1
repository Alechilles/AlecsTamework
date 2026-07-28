param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,
    [string]$ChangelogPath = "artifacts/changelog.md",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$ApiToken = $env:MODIFOLD_API_TOKEN,
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-OptionalPropertyValue {
    param(
        [object]$Object,
        [string]$Name,
        [object]$DefaultValue = $null
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return $DefaultValue
    }

    return $property.Value
}

function Resolve-ModifoldDependencies {
    param(
        [object[]]$Dependencies,
        [string]$ApiBaseUrl
    )

    $resolved = @()
    foreach ($dependency in $Dependencies) {
        $slug = ([string](Get-OptionalPropertyValue -Object $dependency -Name "slug" -DefaultValue "")).Trim()
        $type = ([string](Get-OptionalPropertyValue -Object $dependency -Name "type" -DefaultValue "required")).Trim().ToLowerInvariant()
        $versionId = ([string](Get-OptionalPropertyValue -Object $dependency -Name "versionId" -DefaultValue "")).Trim()
        $versionNumber = ([string](Get-OptionalPropertyValue -Object $dependency -Name "versionNumber" -DefaultValue "")).Trim()

        $dependencyPayload = [ordered]@{
            slug = $slug
            type = $type
        }

        if (-not [string]::IsNullOrWhiteSpace($versionId)) {
            $dependencyPayload["version_id"] = $versionId
        } elseif (-not [string]::IsNullOrWhiteSpace($versionNumber)) {
            $projectEndpoint = "$ApiBaseUrl/projects/$([uri]::EscapeDataString($slug))"
            $project = Invoke-RestMethod -Method Get -Uri $projectEndpoint
            $availableVersions = @($project.versions)
            if ($availableVersions.Count -eq 0) {
                throw "Modifold dependency '$slug' has no public versions to resolve."
            }

            if ($versionNumber -eq "latest") {
                $resolvedVersion = $availableVersions |
                    Sort-Object -Property @{ Expression = {
                        $createdAt = Get-OptionalPropertyValue -Object $_ -Name "created_at" -DefaultValue ""
                        $parsedCreatedAt = [datetime]::MinValue
                        [datetime]::TryParse("$createdAt", [ref]$parsedCreatedAt) | Out-Null
                        $parsedCreatedAt
                    }; Descending = $true } |
                    Select-Object -First 1
            } else {
                $normalizedRequestedVersion = $versionNumber -replace "^v", ""
                $resolvedVersion = $availableVersions |
                    Where-Object { ("$($_.version_number)" -replace "^v", "") -eq $normalizedRequestedVersion } |
                    Select-Object -First 1
            }

            if ($null -eq $resolvedVersion -or [string]::IsNullOrWhiteSpace("$($resolvedVersion.id)")) {
                throw "Could not resolve Modifold dependency '$slug' version '$versionNumber'."
            }

            $dependencyPayload["version_id"] = "$($resolvedVersion.id)"
            Write-Host "Resolved Modifold dependency '$slug' to version '$($resolvedVersion.version_number)' ($($resolvedVersion.id))."
        }

        $resolved += $dependencyPayload
    }

    return $resolved
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
$modifoldProperty = $config.PSObject.Properties["modifold"]
if ($null -eq $modifoldProperty -or $null -eq $modifoldProperty.Value) {
    throw "modifold configuration is missing in $ConfigPath."
}

$modifoldConfig = $modifoldProperty.Value
$projectSlug = ([string](Get-OptionalPropertyValue -Object $modifoldConfig -Name "projectSlug" -DefaultValue "")).Trim()
$releaseChannel = ([string](Get-OptionalPropertyValue -Object $modifoldConfig -Name "releaseChannel" -DefaultValue "release")).Trim().ToLowerInvariant()
$versionPrefix = [string](Get-OptionalPropertyValue -Object $modifoldConfig -Name "versionPrefix" -DefaultValue "")
$apiBaseUrl = ([string](Get-OptionalPropertyValue -Object $modifoldConfig -Name "apiBaseUrl" -DefaultValue "https://api.modifold.com")).TrimEnd("/")
$gameVersions = @(
    @(Get-OptionalPropertyValue -Object $modifoldConfig -Name "gameVersions" -DefaultValue @()) |
        ForEach-Object { "$_".Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$loaders = @(
    @(Get-OptionalPropertyValue -Object $modifoldConfig -Name "loaders" -DefaultValue @()) |
        ForEach-Object { "$_".Trim() } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$dependencies = @(Get-OptionalPropertyValue -Object $modifoldConfig -Name "dependencies" -DefaultValue @())

if ([string]::IsNullOrWhiteSpace($projectSlug)) {
    throw "modifold.projectSlug is empty in $ConfigPath."
}
if (@("release", "beta", "alpha") -notcontains $releaseChannel) {
    throw "modifold.releaseChannel '$releaseChannel' is invalid. Expected release, beta, or alpha."
}
if ($gameVersions.Count -eq 0) {
    throw "modifold.gameVersions is empty in $ConfigPath."
}
if ($loaders.Count -eq 0) {
    throw "modifold.loaders is empty in $ConfigPath."
}

$allowedDependencyTypes = @("required", "optional", "incompatible", "embedded")
foreach ($dependency in $dependencies) {
    $dependencySlug = ([string](Get-OptionalPropertyValue -Object $dependency -Name "slug" -DefaultValue "")).Trim()
    $dependencyType = ([string](Get-OptionalPropertyValue -Object $dependency -Name "type" -DefaultValue "required")).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($dependencySlug)) {
        throw "Each modifold.dependencies entry must include a slug."
    }
    if ($allowedDependencyTypes -notcontains $dependencyType) {
        throw "Modifold dependency '$dependencySlug' has invalid type '$dependencyType'."
    }
}

$normalizedVersion = (($Version.Trim()) -replace "^v", "")
if ([string]::IsNullOrWhiteSpace($normalizedVersion)) {
    throw "Version cannot be empty."
}

$publishedVersion = "$versionPrefix$normalizedVersion"
$endpoint = "$apiBaseUrl/projects/$([uri]::EscapeDataString($projectSlug))/versions"

if ($DryRun) {
    Write-Host "Dry-run: would publish '$ArtifactPath' to Modifold project '$projectSlug'."
    Write-Host "Endpoint: $endpoint"
    Write-Host "Version: $publishedVersion"
    Write-Host "Release channel: $releaseChannel"
    Write-Host "Game versions: $($gameVersions -join ', ')"
    Write-Host "Loaders: $($loaders -join ', ')"
    if ($dependencies.Count -gt 0) {
        $dependencySummary = @($dependencies | ForEach-Object {
            $slug = ([string](Get-OptionalPropertyValue -Object $_ -Name "slug" -DefaultValue "")).Trim()
            $type = ([string](Get-OptionalPropertyValue -Object $_ -Name "type" -DefaultValue "required")).Trim().ToLowerInvariant()
            $versionNumber = ([string](Get-OptionalPropertyValue -Object $_ -Name "versionNumber" -DefaultValue "")).Trim()
            if ([string]::IsNullOrWhiteSpace($versionNumber)) { "${slug}:$type" } else { "${slug}:${type}:$versionNumber" }
        }) -join ", "
        Write-Host "Dependencies: $dependencySummary"
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ApiToken)) {
    throw "MODIFOLD_API_TOKEN is required when DryRun is false (env var or -ApiToken)."
}

$resolvedDependencies = @(Resolve-ModifoldDependencies -Dependencies $dependencies -ApiBaseUrl $apiBaseUrl)
$gameVersionsJson = ConvertTo-Json -InputObject @($gameVersions) -Compress
$loadersJson = ConvertTo-Json -InputObject @($loaders) -Compress
$dependenciesJson = ConvertTo-Json -InputObject @($resolvedDependencies) -Depth 8 -Compress
$resolvedArtifactPath = (Resolve-Path -Path $ArtifactPath).Path
$resolvedChangelogPath = (Resolve-Path -Path $ChangelogPath).Path

$curlArgs = @(
    "-sS",
    "-X", "POST",
    $endpoint,
    "-H", "Authorization: Bearer $ApiToken",
    "-F", "version_number=$publishedVersion",
    "-F", "changelog=<$resolvedChangelogPath;type=text/plain",
    "-F", "release_channel=$releaseChannel",
    "-F", "game_versions=$gameVersionsJson",
    "-F", "loaders=$loadersJson",
    "-F", "dependencies=$dependenciesJson",
    "-F", "file=@$resolvedArtifactPath"
)

$responseTempFile = New-TemporaryFile
try {
    $statusCode = & curl.exe @curlArgs -o $responseTempFile -w "%{http_code}"
    $statusCode = $statusCode.Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Modifold upload failed with exit code $LASTEXITCODE."
    }

    $response = if (Test-Path -Path $responseTempFile) {
        Get-Content -Path $responseTempFile -Raw
    } else {
        ""
    }

    $statusCodeInt = 0
    if (-not [int]::TryParse($statusCode, [ref]$statusCodeInt)) {
        throw "Modifold upload failed with an invalid HTTP status value '$statusCode'."
    }
    if ($statusCodeInt -lt 200 -or $statusCodeInt -ge 300) {
        $responseSummary = if ([string]::IsNullOrWhiteSpace($response)) { "<empty>" } else { $response }
        throw "Modifold upload failed with HTTP status $statusCode. Response: $responseSummary"
    }

    Write-Host "Modifold upload completed (HTTP $statusCode)."
    if (-not [string]::IsNullOrWhiteSpace($response)) {
        Write-Output $response
    }
} finally {
    Remove-Item -Path $responseTempFile -Force -ErrorAction SilentlyContinue
}

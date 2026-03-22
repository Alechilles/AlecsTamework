param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$ArtifactPath,
    [string]$ChangelogPath = "artifacts/changelog.md",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$ApiToken = $env:GITHUB_TOKEN,
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-NormalizedVersion {
    param([string]$RawVersion)
    return (($RawVersion.Trim()) -replace "^v", "")
}

function Resolve-GitHubPrerelease {
    param([object]$Config)

    $githubProperty = $Config.PSObject.Properties["github"]
    if ($null -ne $githubProperty -and $null -ne $githubProperty.Value) {
        $githubConfig = $githubProperty.Value
        $prereleaseProperty = $githubConfig.PSObject.Properties["prerelease"]
        if ($null -ne $prereleaseProperty) {
            return [bool]$prereleaseProperty.Value
        }
    }

    $modtaleProperty = $Config.PSObject.Properties["modtale"]
    if ($null -ne $modtaleProperty -and $null -ne $modtaleProperty.Value) {
        $modtaleConfig = $modtaleProperty.Value
        $channelProperty = $modtaleConfig.PSObject.Properties["releaseChannel"]
        if ($null -ne $channelProperty -and -not [string]::IsNullOrWhiteSpace("$($channelProperty.Value)")) {
            $normalizedChannel = "$($channelProperty.Value)".Trim().ToLowerInvariant()
            return ($normalizedChannel -ne "stable" -and $normalizedChannel -ne "release")
        }
    }

    $curseforgeProperty = $Config.PSObject.Properties["curseforge"]
    if ($null -ne $curseforgeProperty -and $null -ne $curseforgeProperty.Value) {
        $curseforgeConfig = $curseforgeProperty.Value
        $releaseTypeProperty = $curseforgeConfig.PSObject.Properties["releaseType"]
        if ($null -ne $releaseTypeProperty -and -not [string]::IsNullOrWhiteSpace("$($releaseTypeProperty.Value)")) {
            $normalizedType = "$($releaseTypeProperty.Value)".Trim().ToLowerInvariant()
            return ($normalizedType -ne "release")
        }
    }

    return $false
}

function Get-GitHubHeaders {
    param([string]$Token)

    return @{
        Authorization = "Bearer $Token"
        Accept = "application/vnd.github+json"
        "X-GitHub-Api-Version" = "2022-11-28"
    }
}

function Invoke-GitHubApi {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [string]$ContentType = "",
        [string]$Body = ""
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
    }

    if (-not [string]::IsNullOrWhiteSpace($ContentType)) {
        $params.ContentType = $ContentType
    }
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        $params.Body = $Body
    }

    try {
        return Invoke-RestMethod @params
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response -and $response.StatusCode.value__ -eq 404) {
            return $null
        }
        throw
    }
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
$repository = [string]$config.repository
if ([string]::IsNullOrWhiteSpace($repository)) {
    throw "repository is missing in '$ConfigPath'."
}

$normalizedVersion = Get-NormalizedVersion -RawVersion $Version
$tagName = "v$normalizedVersion"
$releaseName = "$($config.modName) v$normalizedVersion"
$isPrerelease = Resolve-GitHubPrerelease -Config $config
$artifactItem = Get-Item -Path $ArtifactPath
$artifactName = $artifactItem.Name
$resolvedArtifactPath = $artifactItem.FullName
$changelog = Get-Content -Path $ChangelogPath -Raw

if ($DryRun) {
    Write-Host "Dry-run: would publish '$resolvedArtifactPath' to GitHub repo '$repository'."
    Write-Host "Tag: $tagName"
    Write-Host "Release name: $releaseName"
    Write-Host "Prerelease: $isPrerelease"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ApiToken)) {
    throw "GITHUB_TOKEN is required when DryRun is false (env var or -ApiToken)."
}

$headers = Get-GitHubHeaders -Token $ApiToken
$releaseUri = "https://api.github.com/repos/$repository/releases/tags/$tagName"
$release = Invoke-GitHubApi -Method "GET" -Uri $releaseUri -Headers $headers

if ($null -eq $release) {
    $createBody = @{
        tag_name = $tagName
        name = $releaseName
        body = $changelog
        draft = $false
        prerelease = $isPrerelease
        generate_release_notes = $false
    } | ConvertTo-Json -Compress

    $release = Invoke-GitHubApi `
        -Method "POST" `
        -Uri "https://api.github.com/repos/$repository/releases" `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $createBody
} else {
    $updateBody = @{
        tag_name = $tagName
        name = $releaseName
        body = $changelog
        draft = $false
        prerelease = $isPrerelease
    } | ConvertTo-Json -Compress

    $release = Invoke-GitHubApi `
        -Method "PATCH" `
        -Uri "https://api.github.com/repos/$repository/releases/$($release.id)" `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $updateBody
}

$existingAsset = $null
if ($release.assets) {
    $existingAsset = @($release.assets) | Where-Object { $_.name -eq $artifactName } | Select-Object -First 1
}

if ($null -ne $existingAsset) {
    Invoke-GitHubApi `
        -Method "DELETE" `
        -Uri "https://api.github.com/repos/$repository/releases/assets/$($existingAsset.id)" `
        -Headers $headers | Out-Null
}

$uploadUrl = ([string]$release.upload_url) -replace "\{\?name,label\}$", ""
$encodedArtifactName = [System.Uri]::EscapeDataString($artifactName)
$uploadHeaders = @{
    Authorization = "Bearer $ApiToken"
    Accept = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}
$artifactBytes = [System.IO.File]::ReadAllBytes($resolvedArtifactPath)
$uploadedAsset = Invoke-RestMethod `
    -Method Post `
    -Uri "${uploadUrl}?name=$encodedArtifactName&label=$encodedArtifactName" `
    -Headers $uploadHeaders `
    -ContentType "application/java-archive" `
    -Body $artifactBytes

Write-Host "GitHub release publish completed for '$tagName'."
if ($null -ne $uploadedAsset -and $uploadedAsset.browser_download_url) {
    Write-Output $uploadedAsset.browser_download_url
}

param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$PrebuiltArtifactPath = "artifacts/release.jar",
    [string]$ConfigPath = ".release/publish-config.json",
    [string]$OutputDir = "artifacts",
    [bool]$PublishGitHub = $true,
    [bool]$PublishCurseForge = $false,
    [bool]$PublishModtale = $false,
    [bool]$DryRun = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -Path $PrebuiltArtifactPath)) {
    throw "Prebuilt artifact '$PrebuiltArtifactPath' was not found."
}

.\scripts\release\validate-release.ps1 `
    -Version $Version `
    -ConfigPath $ConfigPath

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$artifactPathOutput = Join-Path $OutputDir "artifact-path.txt"
.\scripts\release\build-package.ps1 `
    -Version $Version `
    -ConfigPath $ConfigPath `
    -OutputDir $OutputDir `
    -ArtifactPathOutputFile $artifactPathOutput `
    -PrebuiltArtifactPath $PrebuiltArtifactPath

$artifactPath = (Get-Content -Path $artifactPathOutput -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($artifactPath)) {
    throw "Artifact path output was empty."
}

$changelogPath = Join-Path $OutputDir "changelog.md"
.\scripts\release\extract-changelog.ps1 `
    -Version $Version `
    -OutputPath $changelogPath

if ($PublishGitHub) {
    .\scripts\release\publish-github.ps1 `
        -Version $Version `
        -ArtifactPath $artifactPath `
        -ChangelogPath $changelogPath `
        -ConfigPath $ConfigPath `
        -DryRun $DryRun
}

if ($PublishCurseForge) {
    .\scripts\release\publish-curseforge.ps1 `
        -Version $Version `
        -ArtifactPath $artifactPath `
        -ChangelogPath $changelogPath `
        -ConfigPath $ConfigPath `
        -DryRun $DryRun
}

if ($PublishModtale) {
    .\scripts\release\publish-modtale.ps1 `
        -Version $Version `
        -ArtifactPath $artifactPath `
        -ChangelogPath $changelogPath `
        -ConfigPath $ConfigPath `
        -DryRun $DryRun
}

Write-Host "Prebuilt publish flow complete."
Write-Host "Artifact: $artifactPath"
Write-Host "Changelog: $changelogPath"

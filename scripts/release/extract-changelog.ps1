param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$ChangelogPath = "CHANGELOG.md",
    [string]$OutputPath = "artifacts/changelog.md",
    [bool]$IncludeHeading = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-NormalizedVersion {
    param([string]$RawVersion)
    return (($RawVersion.Trim()) -replace "^v", "")
}

if (-not (Test-Path -Path $ChangelogPath)) {
    throw "Changelog file '$ChangelogPath' was not found."
}

$normalizedVersion = Get-NormalizedVersion -RawVersion $Version
$escapedVersion = [regex]::Escape($normalizedVersion)
$sectionPattern = "^##\s+v?$escapedVersion(\s+-|\s*$)"

$lines = Get-Content -Path $ChangelogPath
$startIndex = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $sectionPattern) {
        $startIndex = $i
        break
    }
}

if ($startIndex -lt 0) {
    throw "Could not find changelog heading for version '$normalizedVersion'."
}

$endIndex = $lines.Count
for ($i = $startIndex + 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match "^##\s+") {
        $endIndex = $i
        break
    }
}

[string[]]$sectionLines = $lines[$startIndex..($endIndex - 1)]
if (-not $IncludeHeading) {
    if ($sectionLines.Count -le 1) {
        throw "Changelog section for '$normalizedVersion' is empty."
    }

    $sectionLines = $sectionLines[1..($sectionLines.Count - 1)]
}

$sectionText = ($sectionLines -join "`n").Trim()
if ([string]::IsNullOrWhiteSpace($sectionText)) {
    throw "Changelog section for '$normalizedVersion' is empty."
}

$outputParent = Split-Path -Path $OutputPath -Parent
if (-not [string]::IsNullOrWhiteSpace($outputParent)) {
    New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
}

Set-Content -Path $OutputPath -Value $sectionText
Write-Host "Wrote changelog section to '$OutputPath'."

if ($env:GITHUB_OUTPUT) {
    $resolvedPath = (Resolve-Path -Path $OutputPath).Path
    "changelog_path=$resolvedPath" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}

Write-Output $sectionText

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $BuiltArtifact,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $HytaleServerJar,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $HytaleAssets,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $JavaExecutable,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $SimpleClaimsJar,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $QuestLinesClaimsJar,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $UpgradeSaveSource,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $OutputRoot,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 3600)]
    [int] $DwellSeconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 3600)]
    [int] $StartupTimeoutSeconds,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 600)]
    [int] $ShutdownTimeoutSeconds,

    [switch] $ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "claims-runtime\ClaimsRuntimeHarness.psm1") -Force

try {
    $result = Invoke-ClaimsRuntimeVerification @PSBoundParameters
    $result | ConvertTo-Json -Depth 30
    if (-not $ValidateOnly -and -not $result.passed) {
        exit 1
    }
} catch {
    Write-Error $_
    exit 1
}

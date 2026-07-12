Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Set-ClaimsRuntimeObjectProperty {
    param([psobject] $Object, [string] $Name, [object] $Value)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    } else {
        $property.Value = $Value
    }
}

function Set-ClaimsRuntimePluginEnabled {
    param([psobject] $Mods, [string] $PluginId, [bool] $Enabled)
    $entryProperty = $Mods.PSObject.Properties[$PluginId]
    if ($null -eq $entryProperty -or $null -eq $entryProperty.Value -or
            $null -eq $entryProperty.Value.PSObject.Properties["Enabled"]) {
        Set-ClaimsRuntimeObjectProperty -Object $Mods -Name $PluginId `
            -Value ([pscustomobject][ordered]@{ Enabled = $Enabled })
        return
    }
    Set-ClaimsRuntimeObjectProperty -Object $entryProperty.Value -Name "Enabled" -Value $Enabled
}

function Write-ClaimsRuntimeConfigJson {
    param([string] $Path, [psobject] $Value)
    $json = $Value | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText(
        [IO.Path]::GetFullPath($Path),
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )
}

function Set-ClaimsRuntimeIsolatedServerConfig {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $Home,
        [Parameter(Mandatory = $true)][psobject] $Scenario,
        [Parameter(Mandatory = $true)][psobject] $Manifests
    )

    $configPath = Join-Path $Home "config.json"
    $config = if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } else {
        [pscustomobject][ordered]@{
            Version = 4
            Backup = [pscustomobject][ordered]@{ Enabled = $false }
            Mods = [pscustomobject][ordered]@{}
        }
    }
    if ($null -eq $config.PSObject.Properties["Backup"] -or $null -eq $config.Backup -or
            $null -eq $config.Backup.PSObject.Properties["Enabled"]) {
        Set-ClaimsRuntimeObjectProperty -Object $config -Name "Backup" `
            -Value ([pscustomobject][ordered]@{ Enabled = $false })
    } else {
        Set-ClaimsRuntimeObjectProperty -Object $config.Backup -Name "Enabled" -Value $false
    }
    if ($null -eq $config.PSObject.Properties["Mods"] -or $null -eq $config.Mods) {
        Set-ClaimsRuntimeObjectProperty -Object $config -Name "Mods" `
            -Value ([pscustomobject][ordered]@{})
    }

    $pluginStates = [ordered]@{}
    $pluginStates[$Manifests.tamework.pluginId] = $true
    $pluginStates[$Manifests.simpleClaims.pluginId] = $Scenario.providerKinds -contains "simpleclaims"
    $pluginStates[$Manifests.questLinesClaims.pluginId] = $Scenario.providerKinds -contains "questlines"
    foreach ($entry in $pluginStates.GetEnumerator()) {
        Set-ClaimsRuntimePluginEnabled -Mods $config.Mods -PluginId $entry.Key -Enabled $entry.Value
    }
    Write-ClaimsRuntimeConfigJson -Path $configPath -Value $config

    $readBack = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $checks = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $pluginStates.GetEnumerator()) {
        $actual = $readBack.Mods.PSObject.Properties[$entry.Key].Value.Enabled
        $checks.Add([pscustomobject][ordered]@{
            pluginId = $entry.Key
            expectedEnabled = $entry.Value
            actualEnabled = [bool]$actual
            passed = [bool]$actual -eq $entry.Value
        })
    }
    $backupDisabled = -not [bool]$readBack.Backup.Enabled
    if (-not $backupDisabled -or ($checks | Where-Object { -not $_.passed })) {
        throw "Isolated server config did not preserve the required plugin/backup states."
    }
    return [pscustomobject][ordered]@{
        path = $configPath
        sha256 = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
        backupDisabled = $backupDisabled
        pluginChecks = @($checks)
        passed = $true
    }
}

Export-ModuleMember -Function "Set-ClaimsRuntimeIsolatedServerConfig"

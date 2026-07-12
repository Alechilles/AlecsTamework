Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ClaimsRuntimeLogAnalysis {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string] $Text,
        [Parameter(Mandatory = $true)][string[]] $ExpectedPluginIds,
        [string[]] $ForbiddenPluginIds = @(),
        [AllowEmptyString()][string] $PluginEnablementText = $Text
    )

    $findings = [System.Collections.Generic.List[object]]::new()
    $patterns = [ordered]@{
        "severe-level" = '(?i)\bSEVERE\b'
        "error-level" = '(?i)(?:^|[\s\[])ERROR(?:[\s\]:]|$)'
        "exception" = '(?i)\b(?:Exception in thread|[A-Za-z0-9_.$]+Exception)\b'
        "jvm-error" = '(?i)\b(?:NoClassDefFoundError|LinkageError|VerifyError|UnsatisfiedLinkError)\b'
        "plugin-load-failure" = '(?i)(?:failed to (?:load|setup|start) plugin|lacking dependency|plugin.*DISABLED!)'
        "provider-contract-failure" = '(?i)(?:claim provider|SimpleClaims|QuestLinesClaims).*(?:INCOMPATIBLE|INVALID|contract (?:failed|unavailable)|failed to probe)'
    }
    foreach ($line in ($Text -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        foreach ($entry in $patterns.GetEnumerator()) {
            if ($line -match $entry.Value) {
                $findings.Add([pscustomobject][ordered]@{ category = $entry.Key; line = $line.Trim() })
                break
            }
        }
    }

    $pluginChecks = [System.Collections.Generic.List[object]]::new()
    foreach ($pluginId in $ExpectedPluginIds) {
        $count = [regex]::Matches(
            $PluginEnablementText,
            "(?im)Enabled plugin\s+" + [regex]::Escape($pluginId) + "(?:\s|$)"
        ).Count
        $pluginChecks.Add([pscustomobject][ordered]@{
            pluginId = $pluginId
            expectedEnabledCount = 1
            actualEnabledCount = $count
            passed = $count -eq 1
        })
    }
    foreach ($pluginId in $ForbiddenPluginIds) {
        $count = [regex]::Matches(
            $PluginEnablementText,
            "(?im)Enabled plugin\s+" + [regex]::Escape($pluginId) + "(?:\s|$)"
        ).Count
        $pluginChecks.Add([pscustomobject][ordered]@{
            pluginId = $pluginId
            expectedEnabledCount = 0
            actualEnabledCount = $count
            passed = $count -eq 0
        })
    }
    $booted = $Text.Contains("Hytale Server Booted!")
    $shutdownCompleted = $Text.Contains("Shutdown completed!")
    return [pscustomobject][ordered]@{
        passed = $findings.Count -eq 0 -and $booted -and $shutdownCompleted `
            -and -not ($pluginChecks | Where-Object { -not $_.passed })
        booted = $booted
        shutdownCompleted = $shutdownCompleted
        pluginEvidenceScope = "single canonical server log channel"
        pluginChecks = @($pluginChecks)
        findings = @($findings)
    }
}

Export-ModuleMember -Function "Get-ClaimsRuntimeLogAnalysis"

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ClaimsRuntimeSerrPayload {
    param([string] $Line)
    $pattern = '^\s*(?:\[\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\s+SEVERE\]\s+)?\[SERR\]\s?(.*?)\s*$'
    if ($Line -match $pattern) { return $matches[1] }
    return $null
}

function Get-ClaimsRuntimeKnownDiagnosticExpectation {
    param([psobject] $Policy)
    if ($null -eq $Policy) {
        return [pscustomobject]@{ valid = $true; kind = "none"; lines = @(); identityChecks = @() }
    }
    $mapping = switch ($Policy.scenarioId) {
        "fresh-no-provider" { @{ kind = "none"; ids = @() } }
        "simpleclaims-1.0.38" { @{ kind = "none"; ids = @("Buuz135:SimpleClaims|1.0.38") } }
        "questlines-claims-1.3.1" { @{ kind = "questlines-no-provider"; ids = @("net.evilcraft:QuestLinesClaims|1.3.1") } }
        "both-providers-auto" { @{ kind = "mixed-provider-collision"; ids = @("Buuz135:SimpleClaims|1.0.38", "net.evilcraft:QuestLinesClaims|1.3.1") } }
        "copied-upgrade-save" { @{ kind = "mixed-provider-collision"; ids = @("Buuz135:SimpleClaims|1.0.38", "net.evilcraft:QuestLinesClaims|1.3.1") } }
        default { $null }
    }
    if ($null -eq $mapping) {
        return [pscustomobject]@{ valid = $false; kind = "invalid-scenario"; lines = @(); identityChecks = @() }
    }
    $lines = switch ($mapping.kind) {
        "questlines-no-provider" {
            @(
                "SLF4J: No SLF4J providers were found.",
                "SLF4J: Defaulting to no-operation (NOP) logger implementation",
                "SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details."
            )
        }
        "mixed-provider-collision" {
            @(
                "SLF4J: A SLF4J service provider failed to instantiate:",
                "org.slf4j.spi.SLF4JServiceProvider: org.slf4j.simple.SimpleServiceProvider not a subtype",
                "SLF4J: No SLF4J providers were found.",
                "SLF4J: Defaulting to no-operation (NOP) logger implementation",
                "SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details."
            )
        }
        default { @() }
    }
    $artifacts = @($Policy.providerArtifacts)
    $checks = [System.Collections.Generic.List[object]]::new()
    foreach ($identity in $mapping.ids) {
        $parts = $identity -split '\|', 2
        $expectedHash = switch ($parts[0]) {
            "Buuz135:SimpleClaims" { "664C6F5681695238FD898E851B044A90812AA13282D2A97A0770802182B7683B" }
            "net.evilcraft:QuestLinesClaims" { "9AA23C0CCD0FD8BB70F305D952AA1B9A0BBF1AEC46D9D8D6DAD37E04B3F2F592" }
            default { "" }
        }
        $matches = @($artifacts | Where-Object {
            $_.pluginId -ceq $parts[0] -and $_.version -ceq $parts[1] -and
                $_.sha256 -ceq $expectedHash
        })
        $checks.Add([pscustomobject]@{
            identity = $identity
            expectedSha256 = $expectedHash
            passed = $matches.Count -eq 1
        })
    }
    $valid = $Policy.expectedKind -ceq $mapping.kind -and
        $artifacts.Count -eq $mapping.ids.Count -and -not ($checks | Where-Object { -not $_.passed })
    return [pscustomobject]@{
        valid = $valid
        kind = $mapping.kind
        lines = @($lines)
        identityChecks = @($checks)
    }
}

function Get-ClaimsRuntimeLogAnalysis {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string] $Text,
        [Parameter(Mandatory = $true)][string[]] $ExpectedPluginIds,
        [string[]] $ForbiddenPluginIds = @(),
        [AllowEmptyString()][string] $PluginEnablementText = $Text,
        [AllowEmptyString()][string] $RawProcessStderr = "",
        [psobject] $KnownProviderDiagnosticPolicy
    )

    $findings = [System.Collections.Generic.List[object]]::new()
    $ignoredFindings = [System.Collections.Generic.List[object]]::new()
    $reallocateBaseline = '^\s*(?:\[\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\s+SEVERE\]\s+)?\[SERR\] Reallocate: \d+ to \d+\s*$'
    $expectation = Get-ClaimsRuntimeKnownDiagnosticExpectation -Policy $KnownProviderDiagnosticPolicy
    $serrPayloads = @($Text -split "`r?`n" | ForEach-Object {
        $payload = Get-ClaimsRuntimeSerrPayload -Line $_
        if ($null -ne $payload) { $payload }
    })
    $providerPayloads = @($serrPayloads | Where-Object {
        $_ -match '^SLF4J:' -or $_ -match '^org\.slf4j\.spi\.SLF4JServiceProvider:'
    })
    $clusterMatches = 0
    if ($expectation.lines.Count -gt 0 -and $serrPayloads.Count -ge $expectation.lines.Count) {
        for ($index = 0; $index -le $serrPayloads.Count - $expectation.lines.Count; $index++) {
            $candidate = @($serrPayloads[$index..($index + $expectation.lines.Count - 1)])
            if (($candidate -join "`n") -ceq ($expectation.lines -join "`n")) { $clusterMatches++ }
        }
    }
    $knownDiagnosticsPassed = $expectation.valid -and (
        ($expectation.lines.Count -eq 0 -and $providerPayloads.Count -eq 0) -or
        ($expectation.lines.Count -gt 0 -and $clusterMatches -eq 1 -and
            $providerPayloads.Count -eq $expectation.lines.Count)
    )
    $allowedProviderPayloads = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if ($knownDiagnosticsPassed) {
        foreach ($payload in $expectation.lines) { [void]$allowedProviderPayloads.Add($payload) }
    }
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
        $serrPayload = Get-ClaimsRuntimeSerrPayload -Line $line
        if ($null -ne $serrPayload -and $allowedProviderPayloads.Contains($serrPayload)) {
            $ignoredFindings.Add([pscustomobject][ordered]@{
                category = "known-provider-diagnostic"
                line = $line.Trim()
                rationale = "Exact scenario/provider/hash-scoped SLF4J diagnostic cluster."
            })
            continue
        }
        if ($line -match $reallocateBaseline) {
            $ignoredFindings.Add([pscustomobject][ordered]@{
                category = "base-engine-reallocate-baseline"
                line = $line.Trim()
                rationale = "Exact bounded Hytale [SERR] Reallocate numeric diagnostic."
            })
            continue
        }
        foreach ($entry in $patterns.GetEnumerator()) {
            if ($line -match $entry.Value) {
                $findings.Add([pscustomobject][ordered]@{ category = $entry.Key; line = $line.Trim() })
                break
            }
        }
    }
    foreach ($line in ($RawProcessStderr -split "`r?`n")) {
        if ($line -match '(?i)(?:A fatal error has been detected|Exception in thread|NoClassDefFoundError|LinkageError|VerifyError|UnsatisfiedLinkError|^\s*(?:Caused by:|at\s+[A-Za-z0-9_.$]+\())') {
            $findings.Add([pscustomobject][ordered]@{ category = "raw-process-fatal"; line = $line.Trim() })
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
        passed = $findings.Count -eq 0 -and $knownDiagnosticsPassed `
            -and $booted -and $shutdownCompleted `
            -and -not ($pluginChecks | Where-Object { -not $_.passed })
        booted = $booted
        shutdownCompleted = $shutdownCompleted
        pluginEvidenceScope = "single canonical server log channel"
        diagnosticEvidenceScope = "canonical server log plus raw pre-logger fatal stderr signatures"
        pluginChecks = @($pluginChecks)
        findings = @($findings)
        ignoredFindings = @($ignoredFindings)
        knownProviderDiagnostics = [pscustomobject][ordered]@{
            passed = $knownDiagnosticsPassed
            scenarioId = if ($null -eq $KnownProviderDiagnosticPolicy) { $null } else { $KnownProviderDiagnosticPolicy.scenarioId }
            expectedKind = $expectation.kind
            identityValid = $expectation.valid
            identityChecks = $expectation.identityChecks
            providerArtifacts = if ($null -eq $KnownProviderDiagnosticPolicy) { @() } else { @($KnownProviderDiagnosticPolicy.providerArtifacts) }
            expectedLines = $expectation.lines
            observedProviderLines = $providerPayloads
            clusterMatches = $clusterMatches
        }
    }
}

Export-ModuleMember -Function "Get-ClaimsRuntimeLogAnalysis"

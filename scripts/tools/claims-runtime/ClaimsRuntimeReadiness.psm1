Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeEvidence.psm1") -Force

function Wait-ClaimsRuntimeDuration {
    param([Diagnostics.Process] $Process, [int] $Seconds)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while (-not $Process.HasExited -and $watch.Elapsed.TotalSeconds -lt $Seconds) {
        Start-Sleep -Milliseconds 100
    }
}

function New-ClaimsRuntimeSqliteReadinessProbe {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $JavaExecutable,
        [Parameter(Mandatory = $true)][string] $BuiltArtifact,
        [Parameter(Mandatory = $true)][string] $ProbeSource,
        [Parameter(Mandatory = $true)][string] $DatabasePath,
        [Parameter(Mandatory = $true)][long] $ExpectedCanonicalRows,
        [switch] $AllowGlobalScopeUnknownWorld
    )

    # GetNewClosure creates a dynamic module that does not inherit this module's private imports.
    # Capture the exact imported function bodies so the callback remains self-contained there.
    $invokeSqliteProbe = (Get-Command Invoke-ClaimsRuntimeSqliteProbe `
        -CommandType Function -ErrorAction Stop).ScriptBlock
    $testSqliteEvidence = (Get-Command Test-ClaimsRuntimeSqliteEvidence `
        -CommandType Function -ErrorAction Stop).ScriptBlock
    return {
        try {
            if (-not (Test-Path -LiteralPath $DatabasePath -PathType Leaf)) {
                throw "Scenario database does not exist yet."
            }
            $sampleEvidence = & $invokeSqliteProbe -JavaExecutable $JavaExecutable `
                -BuiltArtifact $BuiltArtifact -ProbeSource $ProbeSource -DatabasePath $DatabasePath
            $sampleValidation = & $testSqliteEvidence -Evidence $sampleEvidence `
                -ExpectedCanonicalRows $ExpectedCanonicalRows `
                -AllowGlobalScopeUnknownWorld:$AllowGlobalScopeUnknownWorld
            [pscustomobject][ordered]@{
                ready = $sampleValidation.passed
                sampledAtUtc = [DateTime]::UtcNow.ToString("o")
                scanSessionState = $sampleEvidence.scanSessionState
                coverageReady = $sampleEvidence.coverageReady
                coverageTotal = $sampleEvidence.coverageTotal
                nonterminalOperations = $sampleEvidence.nonterminalOperations
                canonicalRows = $sampleEvidence.canonicalRows
                profileRows = $sampleEvidence.profileRows
                readinessMode = $sampleValidation.readinessMode
                failedChecks = @($sampleValidation.checks | Where-Object { -not $_.passed } | ForEach-Object name)
            }
        } catch {
            [pscustomobject][ordered]@{
                ready = $false
                sampledAtUtc = [DateTime]::UtcNow.ToString("o")
                error = $_.Exception.ToString()
            }
        }
    }.GetNewClosure()
}

function Wait-ClaimsRuntimeReadiness {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][Diagnostics.Process] $Process,
        [Parameter(Mandatory = $true)][int] $DwellSeconds,
        [scriptblock] $ReadinessProbe,
        [ValidateRange(1, 3600)][int] $ReadinessTimeoutSeconds = 300,
        [ValidateRange(100, 10000)][int] $PollMilliseconds = 1000
    )

    Wait-ClaimsRuntimeDuration -Process $Process -Seconds $DwellSeconds
    if ($null -eq $ReadinessProbe) {
        return [pscustomobject][ordered]@{
            required = $false
            satisfied = $true
            mode = "fixed-dwell"
            timeoutSeconds = $null
            samples = @()
        }
    }

    $samples = [System.Collections.Generic.List[object]]::new()
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $satisfiedAt = $null
    while (-not $Process.HasExited -and $watch.Elapsed.TotalSeconds -lt $ReadinessTimeoutSeconds) {
        try {
            $sample = & $ReadinessProbe
        } catch {
            $sample = [pscustomobject][ordered]@{
                ready = $false
                sampledAtUtc = [DateTime]::UtcNow.ToString("o")
                error = $_.Exception.ToString()
            }
        }
        if ($null -eq $sample) {
            $sample = [pscustomobject][ordered]@{
                ready = $false
                sampledAtUtc = [DateTime]::UtcNow.ToString("o")
                error = "Readiness probe returned no sample."
            }
        }
        $samples.Add($sample)
        $readyProperty = $sample.PSObject.Properties["ready"]
        if ($null -ne $readyProperty -and [bool]$readyProperty.Value) {
            $satisfiedAt = [DateTime]::UtcNow
            break
        }
        if (-not $Process.HasExited) { Start-Sleep -Milliseconds $PollMilliseconds }
    }
    return [pscustomobject][ordered]@{
        required = $true
        satisfied = $null -ne $satisfiedAt
        mode = "sqlite-readiness"
        timeoutSeconds = $ReadinessTimeoutSeconds
        elapsedMilliseconds = $watch.ElapsedMilliseconds
        satisfiedAtUtc = if ($null -eq $satisfiedAt) { $null } else { $satisfiedAt.ToString("o") }
        samples = @($samples)
    }
}

Export-ModuleMember -Function @(
    "New-ClaimsRuntimeSqliteReadinessProbe",
    "Wait-ClaimsRuntimeReadiness"
)

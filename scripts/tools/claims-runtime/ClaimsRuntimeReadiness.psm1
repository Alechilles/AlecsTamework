Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Wait-ClaimsRuntimeDuration {
    param([Diagnostics.Process] $Process, [int] $Seconds)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while (-not $Process.HasExited -and $watch.Elapsed.TotalSeconds -lt $Seconds) {
        Start-Sleep -Milliseconds 100
    }
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

Export-ModuleMember -Function "Wait-ClaimsRuntimeReadiness"

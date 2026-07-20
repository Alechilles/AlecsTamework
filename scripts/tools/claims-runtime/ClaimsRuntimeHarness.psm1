Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeEvidence.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeInputs.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeLogs.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeProcess.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "ClaimsRuntimeReadiness.psm1") -Force

function Get-ClaimsRuntimeScenarioPlan {
    [CmdletBinding()]
    param()

    return @(
        [pscustomobject][ordered]@{
            id = "fresh-no-provider"
            description = "Fresh universe with active claim rules and no claim provider installed"
            providerSetting = "Auto"
            providerKinds = @()
            copiedUpgrade = $false
            providerResolutionAssertion = "not-observable-at-startup; operation-scoped gate"
        },
        [pscustomobject][ordered]@{
            id = "simpleclaims-1.0.38"
            description = "Fresh universe with SimpleClaims 1.0.38"
            providerSetting = "SimpleClaims"
            providerKinds = @("simpleclaims")
            copiedUpgrade = $false
            providerResolutionAssertion = "not-observable-at-startup; operation-scoped gate"
        },
        [pscustomobject][ordered]@{
            id = "questlines-claims-1.3.1"
            description = "Fresh universe with QuestLines Claims 1.3.1"
            providerSetting = "QuestLinesClaims"
            providerKinds = @("questlines")
            copiedUpgrade = $false
            providerResolutionAssertion = "not-observable-at-startup; operation-scoped gate"
        },
        [pscustomobject][ordered]@{
            id = "both-providers-auto"
            description = "Fresh universe with both providers and Auto selection"
            providerSetting = "Auto"
            providerKinds = @("simpleclaims", "questlines")
            copiedUpgrade = $false
            providerResolutionAssertion = "not-observable-at-startup; operation-scoped gate"
        },
        [pscustomobject][ordered]@{
            id = "copied-upgrade-save"
            description = "Copied upgrade universe with both providers and active claim rules"
            providerSetting = "Auto"
            providerKinds = @("simpleclaims", "questlines")
            copiedUpgrade = $true
            providerResolutionAssertion = "not-observable-at-startup; operation-scoped gate"
        }
    )
}

function New-ClaimsRuntimeSettings {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $ProviderSetting
    )

    $document = [ordered]@{
        version = 1
        population = [ordered]@{
            limitPerPlayerOwnedTotal = 3
            perPlayerLimitScope = "Global"
        }
        simpleClaims = [ordered]@{
            provider = $ProviderSetting
            simpleClaimsEnabled = $true
            limitPerClaimChunk = 2
            limitPerClaimTotal = 6
            breedingRequiresClaim = $true
            protectTamedFromNonMembers = $true
        }
        telemetry = [ordered]@{
            enabled = $false
            breadcrumbsEnabled = $false
        }
    }
    if ($document.population.limitPerPlayerOwnedTotal -le 0 -or
            (-not $document.simpleClaims.simpleClaimsEnabled) -or
            $document.simpleClaims.limitPerClaimChunk -le 0 -or
            $document.simpleClaims.limitPerClaimTotal -le 0 -or
            (-not $document.simpleClaims.breedingRequiresClaim)) {
        throw "Claims runtime fixture settings are not genuinely active."
    }
    return $document
}

function Invoke-ClaimsRuntimeProviderContracts {
    param([psobject] $Inputs)
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
    $questVerifier = Join-Path $repoRoot "scripts\tools\verify-questlines-claims-contract.ps1"
    return [pscustomobject][ordered]@{
        simpleClaims = Invoke-SimpleClaimsRuntimeContractCheck `
            -JavaExecutable $Inputs.javaExecutable -JarPath $Inputs.simpleClaimsJar
        questLinesClaims = Invoke-QuestLinesRuntimeContractCheck `
            -JavaExecutable $Inputs.javaExecutable -JarPath $Inputs.questLinesClaimsJar `
            -VerifierScript $questVerifier
        passed = $true
    }
}

function New-ClaimsRuntimeKnownProviderDiagnosticPolicy {
    param([psobject] $Scenario, [psobject] $Inputs, [psobject] $Manifests)
    $kind = switch ($Scenario.id) {
        "questlines-claims-1.3.1" { "questlines-no-provider" }
        "both-providers-auto" { "mixed-provider-collision" }
        "copied-upgrade-save" { "mixed-provider-collision" }
        default { "none" }
    }
    $artifacts = [System.Collections.Generic.List[object]]::new()
    if ($Scenario.providerKinds -contains "simpleclaims") {
        $artifacts.Add([pscustomobject][ordered]@{
            pluginId = $Manifests.simpleClaims.pluginId
            version = $Manifests.simpleClaims.version
            sha256 = (Get-FileHash -LiteralPath $Inputs.simpleClaimsJar -Algorithm SHA256).Hash
        })
    }
    if ($Scenario.providerKinds -contains "questlines") {
        $artifacts.Add([pscustomobject][ordered]@{
            pluginId = $Manifests.questLinesClaims.pluginId
            version = $Manifests.questLinesClaims.version
            sha256 = (Get-FileHash -LiteralPath $Inputs.questLinesClaimsJar -Algorithm SHA256).Hash
        })
    }
    return [pscustomobject][ordered]@{
        scenarioId = $Scenario.id
        expectedKind = $kind
        providerArtifacts = @($artifacts)
    }
}

function Get-ClaimsRuntimePreV6ProfileBaseline {
    param([psobject] $Evidence)
    if ($Evidence.integrityCheck -cne "ok" -or $Evidence.profileRows -lt 0 -or
            $Evidence.migrationV6Count -ne 0 -or $Evidence.migrationV7Count -ne 0 -or
            $Evidence.canonicalRows -ne -1) {
        throw "UpgradeSaveSource must be an integrity-clean pre-v6 Tamework population database."
    }
    return [long]$Evidence.profileRows
}

function Get-ClaimsRuntimeUpgradeBackupEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $DatabasePath,
        [Parameter(Mandatory = $true)][string] $JavaExecutable,
        [Parameter(Mandatory = $true)][string] $BuiltArtifact,
        [Parameter(Mandatory = $true)][string] $ProbeSource,
        [Parameter(Mandatory = $true)][long] $ExpectedProfileRows,
        [Parameter(Mandatory = $true)][string] $ExpectedMigrationVersions,
        [object[]] $PreexistingBackups = @()
    )

    $dataRoot = Split-Path -Path $DatabasePath -Parent
    $files = @(Get-ChildItem -LiteralPath $dataRoot -File -Filter "tamework_pre_v7_*.sqlite.bak" |
        Sort-Object FullName)
    $backups = [System.Collections.Generic.List[object]]::new()
    $preexistingPaths = @($PreexistingBackups | ForEach-Object { [IO.Path]::GetFullPath($_.path) })
    foreach ($file in $files) {
        $sqlite = if ($file.Length -gt 0) {
            Invoke-ClaimsRuntimeSqliteProbe -JavaExecutable $JavaExecutable `
                -BuiltArtifact $BuiltArtifact -ProbeSource $ProbeSource -DatabasePath $file.FullName
        } else {
            $null
        }
        $backups.Add([pscustomobject][ordered]@{
            path = $file.FullName
            length = $file.Length
            sha256 = if ($file.Length -gt 0) {
                (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            } else { $null }
            integrityCheck = if ($null -eq $sqlite) { $null } else { $sqlite.integrityCheck }
            migrationV6Count = if ($null -eq $sqlite) { $null } else { $sqlite.migrationV6Count }
            migrationV7Count = if ($null -eq $sqlite) { $null } else { $sqlite.migrationV7Count }
            migrationVersions = if ($null -eq $sqlite) { $null } else { $sqlite.migrationVersions }
            profileRows = if ($null -eq $sqlite) { $null } else { $sqlite.profileRows }
            canonicalRows = if ($null -eq $sqlite) { $null } else { $sqlite.canonicalRows }
            createdByThisRun = [IO.Path]::GetFullPath($file.FullName) -notin $preexistingPaths
            passed = $file.Length -gt 0 -and $null -ne $sqlite -and
                $sqlite.integrityCheck -ceq "ok" -and $sqlite.migrationV6Count -eq 0 -and
                $sqlite.migrationV7Count -eq 0 -and
                $sqlite.migrationVersions -ceq $ExpectedMigrationVersions -and
                $sqlite.profileRows -eq $ExpectedProfileRows -and $sqlite.canonicalRows -eq -1
        })
    }
    $created = @($backups | Where-Object createdByThisRun)
    return [pscustomobject][ordered]@{
        pattern = "tamework_pre_v7_*.sqlite.bak"
        passed = $created.Count -gt 0 -and -not ($created | Where-Object { -not $_.passed })
        preexistingBeforeStartup = @($PreexistingBackups)
        createdByThisRun = $created
        backups = @($backups)
    }
}

function Invoke-ClaimsRuntimeVerification {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $BuiltArtifact,
        [Parameter(Mandatory = $true)][string] $HytaleServerJar,
        [Parameter(Mandatory = $true)][string] $HytaleAssets,
        [Parameter(Mandatory = $true)][string] $JavaExecutable,
        [Parameter(Mandatory = $true)][string] $SimpleClaimsJar,
        [Parameter(Mandatory = $true)][string] $QuestLinesClaimsJar,
        [Parameter(Mandatory = $true)][string] $UpgradeSaveSource,
        [Parameter(Mandatory = $true)][string] $OutputRoot,
        [Parameter(Mandatory = $true)][ValidateRange(1, 3600)][int] $DwellSeconds,
        [Parameter(Mandatory = $true)][ValidateRange(1, 3600)][int] $StartupTimeoutSeconds,
        [Parameter(Mandatory = $true)][ValidateRange(1, 600)][int] $ShutdownTimeoutSeconds,
        [ValidateRange(1, 3600)][int] $UpgradeReadinessTimeoutSeconds = 300,
        [switch] $ValidateOnly
    )

    $inputs = Get-ClaimsRuntimeInputs `
        -BuiltArtifact $BuiltArtifact -HytaleServerJar $HytaleServerJar `
        -HytaleAssets $HytaleAssets -JavaExecutable $JavaExecutable `
        -SimpleClaimsJar $SimpleClaimsJar -QuestLinesClaimsJar $QuestLinesClaimsJar `
        -UpgradeSaveSource $UpgradeSaveSource -OutputRoot $OutputRoot
    $inputStateBefore = Get-ClaimsRuntimeTrackedInputState -Inputs $inputs
    $manifests = Assert-ClaimsRuntimeManifests -Inputs $inputs
    $scenarios = Get-ClaimsRuntimeScenarioPlan
    $validateSourceEvidence = $null
    $validateExpectedUpgradeRows = $null
    $validateContracts = $null
    if ($ValidateOnly) {
        $tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $tempValidationRoot = Join-Path $tempBase `
            ("tamework-claims-runtime-validate-" + ([Guid]::NewGuid()).ToString("N"))
        New-Item -ItemType Directory -Path $tempValidationRoot | Out-Null
        try {
            $tempProbe = New-ClaimsRuntimeSqliteProbeSource `
                -Destination (Join-Path $tempValidationRoot "ClaimsRuntimeSqliteProbe.java")
            $tempDatabase = Copy-ClaimsRuntimeSqliteProbeSnapshot `
                -SourceDatabase $inputs.upgradeSourceDatabase `
                -DestinationDirectory (Join-Path $tempValidationRoot "source-snapshot")
            $validateSourceEvidence = Invoke-ClaimsRuntimeSqliteProbe `
                -JavaExecutable $inputs.javaExecutable -BuiltArtifact $inputs.builtArtifact `
                -ProbeSource $tempProbe -DatabasePath $tempDatabase
            $validateSourceEvidence.databasePath = $inputs.upgradeSourceDatabase
            $validateSourceEvidence | Add-Member -NotePropertyName probeMode `
                -NotePropertyValue "isolated-snapshot-copy"
            $validateExpectedUpgradeRows = Get-ClaimsRuntimePreV6ProfileBaseline `
                -Evidence $validateSourceEvidence
            $validateContracts = Invoke-ClaimsRuntimeProviderContracts -Inputs $inputs
        } finally {
            $safeTempName = (Split-Path -Path $tempValidationRoot -Leaf) -like "tamework-claims-runtime-validate-*"
            if ($safeTempName -and (Test-ClaimsRuntimePathWithin -Candidate $tempValidationRoot -Root $tempBase)) {
                Remove-Item -LiteralPath $tempValidationRoot -Recurse -Force
            }
        }
    }
    $validation = [pscustomobject][ordered]@{
        mode = if ($ValidateOnly) { "validate-only" } else { "runtime" }
        outputRoot = $inputs.outputRoot
        liveUserDataRoot = $inputs.liveUserDataRoot
        outputRootDoesNotExist = -not (Test-Path -LiteralPath $inputs.outputRoot)
        contractChecksPlanned = @("SimpleClaims 1.0.38", "QuestLines Claims 1.3.1")
        providerContractEvidence = $validateContracts
        sqliteChecksPlanned = @(
            "integrity_check=ok", "journal_mode=wal", "synchronous=FULL", "schema-v6",
            "seven-coverage-dimensions-present", "configured-owner-scope-ready",
            "unknown-per-world-scope-explicitly-fail-closed", "zero-nonterminal-operations",
            "canonical-profile-row-consistency"
        )
        manifests = $manifests
        inputStateBefore = $inputStateBefore
        upgradeSourceSqlite = $validateSourceEvidence
        expectedPostUpgradeCanonicalRows = $validateExpectedUpgradeRows
        scenarios = @($scenarios | ForEach-Object {
            [pscustomobject][ordered]@{ plan = $_; activeSettings = New-ClaimsRuntimeSettings $_.providerSetting }
        })
        providerSelectionScope = "Provider load and binary contracts are startup-observable; actual provider selection is operation-scoped and is not asserted by startup alone."
        upgradeReadinessTimeoutSeconds = $UpgradeReadinessTimeoutSeconds
    }
    if ($ValidateOnly) {
        $validation | Add-Member -NotePropertyName inputImmutability -NotePropertyValue `
            (Compare-ClaimsRuntimeTrackedInputState -Before $inputStateBefore `
                -After (Get-ClaimsRuntimeTrackedInputState -Inputs $inputs))
        if (-not $validation.inputImmutability.passed) {
            throw "An explicit input changed during validate-only checks."
        }
        return $validation
    }

    New-Item -ItemType Directory -Path $inputs.outputRoot | Out-Null
    Write-ClaimsRuntimeJson -Path (Join-Path $inputs.outputRoot "validation-plan.json") -Value $validation
    $inputEvidence = [pscustomobject][ordered]@{
        builtArtifact = Get-ClaimsRuntimeArtifactEvidence -Path $inputs.builtArtifact -ReadManifest
        hytaleServerJar = Get-ClaimsRuntimeArtifactEvidence -Path $inputs.hytaleServerJar
        hytaleAssets = Get-ClaimsRuntimeArtifactEvidence -Path $inputs.hytaleAssets
        simpleClaimsJar = Get-ClaimsRuntimeArtifactEvidence -Path $inputs.simpleClaimsJar -ReadManifest
        questLinesClaimsJar = Get-ClaimsRuntimeArtifactEvidence -Path $inputs.questLinesClaimsJar -ReadManifest
        upgradeSaveSource = $inputs.upgradeSaveSource
        upgradeSourceDatabase = $inputs.upgradeSourceDatabase
        trackedBefore = $inputStateBefore
    }
    Write-ClaimsRuntimeJson -Path (Join-Path $inputs.outputRoot "input-evidence.json") -Value $inputEvidence

    $preflightPath = Join-Path $inputs.outputRoot "provider-contracts.json"
    try {
        $contracts = Invoke-ClaimsRuntimeProviderContracts -Inputs $inputs
        Write-ClaimsRuntimeJson -Path $preflightPath -Value $contracts
    } catch {
        Write-ClaimsRuntimeJson -Path $preflightPath -Value ([pscustomobject][ordered]@{
            passed = $false
            error = $_.Exception.ToString()
        })
        throw
    }

    $probeSource = New-ClaimsRuntimeSqliteProbeSource `
        -Destination (Join-Path $inputs.outputRoot "tools\ClaimsRuntimeSqliteProbe.java")
    $sourceProbeDatabase = Copy-ClaimsRuntimeSqliteProbeSnapshot `
        -SourceDatabase $inputs.upgradeSourceDatabase `
        -DestinationDirectory (Join-Path $inputs.outputRoot "tools\upgrade-source-snapshot")
    $sourceEvidence = Invoke-ClaimsRuntimeSqliteProbe -JavaExecutable $inputs.javaExecutable `
        -BuiltArtifact $inputs.builtArtifact -ProbeSource $probeSource `
        -DatabasePath $sourceProbeDatabase
    $sourceEvidence | Add-Member -NotePropertyName sourceDatabase `
        -NotePropertyValue $inputs.upgradeSourceDatabase
    $sourceEvidence | Add-Member -NotePropertyName probeMode `
        -NotePropertyValue "isolated-snapshot-copy"
    Write-ClaimsRuntimeJson -Path (Join-Path $inputs.outputRoot "upgrade-source-sqlite.json") -Value $sourceEvidence
    $expectedUpgradeRows = Get-ClaimsRuntimePreV6ProfileBaseline -Evidence $sourceEvidence

    $results = [System.Collections.Generic.List[object]]::new()
    foreach ($scenario in $scenarios) {
        $settings = New-ClaimsRuntimeSettings -ProviderSetting $scenario.providerSetting
        $context = Initialize-ClaimsRuntimeScenario -Scenario $scenario -Inputs $inputs `
            -Manifests $manifests -Settings $settings
        $context | Add-Member -NotePropertyName knownProviderDiagnosticPolicy `
            -NotePropertyValue (New-ClaimsRuntimeKnownProviderDiagnosticPolicy `
                -Scenario $scenario -Inputs $inputs -Manifests $manifests)
        Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "scenario-plan.json") -Value $context
        $processResult = $null
        $processForJson = $null
        $logAnalysis = $null
        $sqliteEvidence = $null
        $sqliteValidation = $null
        $backupEvidence = $null
        $errorText = $null
        $readinessProbe = $null
        if ($scenario.copiedUpgrade) {
            $readinessProbe = New-ClaimsRuntimeSqliteReadinessProbe `
                -JavaExecutable $inputs.javaExecutable -BuiltArtifact $inputs.builtArtifact `
                -ProbeSource $probeSource -DatabasePath $context.databasePath `
                -ExpectedCanonicalRows $expectedUpgradeRows -AllowGlobalScopeUnknownWorld
        }
        try {
            $processResult = Invoke-ClaimsRuntimeServerProcess -Context $context -Inputs $inputs `
                -DwellSeconds $DwellSeconds -StartupTimeoutSeconds $StartupTimeoutSeconds `
                -ShutdownTimeoutSeconds $ShutdownTimeoutSeconds `
                -ReadinessProbe $readinessProbe `
                -ReadinessTimeoutSeconds $UpgradeReadinessTimeoutSeconds
            Write-ClaimsRuntimeText -Path (Join-Path $context.evidence "stdout.log") -Value $processResult.stdout
            Write-ClaimsRuntimeText -Path (Join-Path $context.evidence "stderr.log") -Value $processResult.stderr
            $processForJson = $processResult.PSObject.Copy()
            $processForJson.PSObject.Properties.Remove("stdout")
            $processForJson.PSObject.Properties.Remove("stderr")
            Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "process.json") -Value $processForJson

            $combinedLogs = Read-ClaimsRuntimeScenarioLogs -Context $context `
                -Stdout $processResult.stdout -Stderr $processResult.stderr
            Write-ClaimsRuntimeText -Path (Join-Path $context.evidence "combined.log") -Value $combinedLogs
            $canonicalLog = Read-ClaimsRuntimeCanonicalServerLog -Context $context
            if ([string]::IsNullOrWhiteSpace($canonicalLog)) { $canonicalLog = $processResult.stdout }
            Write-ClaimsRuntimeText -Path (Join-Path $context.evidence "canonical-server.log") `
                -Value $canonicalLog
            $logAnalysis = Get-ClaimsRuntimeLogAnalysis -Text $canonicalLog `
                -PluginEnablementText $canonicalLog `
                -RawProcessStderr $processResult.stderr `
                -KnownProviderDiagnosticPolicy $context.knownProviderDiagnosticPolicy `
                -ExpectedPluginIds $context.expectedPluginIds `
                -ForbiddenPluginIds $context.forbiddenProviderIds
            Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "log-analysis.json") -Value $logAnalysis

            if (-not (Test-Path -LiteralPath $context.databasePath -PathType Leaf)) {
                throw "Scenario did not create expected database '$($context.databasePath)'."
            }
            $sqliteEvidence = Invoke-ClaimsRuntimeSqliteProbe -JavaExecutable $inputs.javaExecutable `
                -BuiltArtifact $inputs.builtArtifact -ProbeSource $probeSource `
                -DatabasePath $context.databasePath
            $baseline = if ($scenario.copiedUpgrade) { $expectedUpgradeRows } else { -1 }
            $sqliteValidation = Test-ClaimsRuntimeSqliteEvidence `
                -Evidence $sqliteEvidence -ExpectedCanonicalRows $baseline `
                -AllowGlobalScopeUnknownWorld:$scenario.copiedUpgrade
            Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "sqlite-evidence.json") `
                -Value ([pscustomobject][ordered]@{ evidence = $sqliteEvidence; validation = $sqliteValidation })
            if ($scenario.copiedUpgrade) {
                $backupEvidence = Get-ClaimsRuntimeUpgradeBackupEvidence `
                    -DatabasePath $context.databasePath -JavaExecutable $inputs.javaExecutable `
                    -BuiltArtifact $inputs.builtArtifact -ProbeSource $probeSource `
                    -ExpectedProfileRows $expectedUpgradeRows `
                    -ExpectedMigrationVersions $sourceEvidence.migrationVersions `
                    -PreexistingBackups $context.preexistingPreV6Backups
                Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "pre-v6-backup-evidence.json") `
                    -Value $backupEvidence
            }
        } catch {
            $errorText = $_.Exception.ToString()
        }

        $readinessPassed = (-not $scenario.copiedUpgrade) -or `
            ($null -ne $processResult -and $processResult.readiness.satisfied)
        $processPassed = $null -ne $processResult -and $null -ne $processResult.bootedAtUtc `
            -and $null -ne $processResult.stopSentAtUtc -and -not $processResult.forcedTermination `
            -and $processResult.exitCode -eq 0 `
            -and [string]::IsNullOrWhiteSpace([string]$processResult.lifecycleError) `
            -and $readinessPassed
        $backupPassed = (-not $scenario.copiedUpgrade) -or `
            ($null -ne $backupEvidence -and $backupEvidence.passed)
        $passed = $processPassed -and $null -ne $logAnalysis -and $logAnalysis.passed `
            -and $null -ne $sqliteValidation -and $sqliteValidation.passed `
            -and $backupPassed `
            -and [string]::IsNullOrWhiteSpace($errorText)
        $result = [pscustomobject][ordered]@{
            id = $scenario.id
            description = $scenario.description
            passed = $passed
            processPassed = $processPassed
            readinessPassed = $readinessPassed
            process = if ($null -eq $processResult) { $null } else { $processForJson }
            logAnalysis = $logAnalysis
            sqliteEvidence = $sqliteEvidence
            sqliteValidation = $sqliteValidation
            preV6BackupEvidence = $backupEvidence
            error = $errorText
            evidenceRoot = $context.evidence
        }
        Write-ClaimsRuntimeJson -Path (Join-Path $context.evidence "result.json") -Value $result
        Write-ClaimsRuntimeScenarioSummary -Result $result -Path (Join-Path $context.evidence "summary.md")
        $results.Add($result)
    }

    $inputImmutability = try {
        Compare-ClaimsRuntimeTrackedInputState -Before $inputStateBefore `
            -After (Get-ClaimsRuntimeTrackedInputState -Inputs $inputs)
    } catch {
        [pscustomobject][ordered]@{ passed = $false; error = $_.Exception.ToString(); checks = @() }
    }
    Write-ClaimsRuntimeJson -Path (Join-Path $inputs.outputRoot "input-immutability.json") `
        -Value $inputImmutability
    $summary = [pscustomobject][ordered]@{
        passed = $inputImmutability.passed -and -not ($results | Where-Object { -not $_.passed })
        generatedAtUtc = [DateTime]::UtcNow.ToString("o")
        outputRoot = $inputs.outputRoot
        scenarioCount = $results.Count
        passedCount = @($results | Where-Object passed).Count
        failedCount = @($results | Where-Object { -not $_.passed }).Count
        inputImmutability = $inputImmutability
        results = @($results)
    }
    Write-ClaimsRuntimeJson -Path (Join-Path $inputs.outputRoot "summary.json") -Value $summary
    $summaryLines = [System.Collections.Generic.List[string]]::new()
    $summaryLines.Add("# Claims packaged-runtime startup verification")
    $summaryLines.Add("")
    $summaryLines.Add("Overall: **$(if ($summary.passed) { 'PASS' } else { 'FAIL' })**")
    $summaryLines.Add("Input files unchanged: **$(if ($inputImmutability.passed) { 'PASS' } else { 'FAIL' })**")
    $summaryLines.Add("")
    foreach ($result in $results) {
        $summaryLines.Add("- $(if ($result.passed) { '[PASS]' } else { '[FAIL]' }) $($result.id)")
    }
    Write-ClaimsRuntimeText -Path (Join-Path $inputs.outputRoot "summary.md") `
        -Value (($summaryLines -join [Environment]::NewLine) + [Environment]::NewLine)
    return $summary
}

Export-ModuleMember -Function @(
    "Get-ClaimsRuntimeScenarioPlan",
    "New-ClaimsRuntimeSettings",
    "New-ClaimsRuntimeKnownProviderDiagnosticPolicy",
    "Get-ClaimsRuntimeUpgradeBackupEvidence",
    "Get-ClaimsRuntimeLogAnalysis",
    "Invoke-ClaimsRuntimeVerification"
)

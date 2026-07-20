[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $TelemetryRoot,
    [Parameter(Mandatory = $true)][string] $PlatformRoot,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $HytaleVersion,
    [string] $ExternalHytaleBackupReference,
    [string] $OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$tameworkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$telemetryRoot = [IO.Path]::GetFullPath($TelemetryRoot)
$platformRoot = [IO.Path]::GetFullPath($PlatformRoot)
$evidenceRoot = Join-Path $tameworkRoot "target/persistence-release-evidence"
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $evidenceRoot "candidate.json"
} else {
    $OutputPath = [IO.Path]::GetFullPath($OutputPath)
}

function Invoke-CandidateCommand {
    param(
        [string] $Label,
        [string] $WorkingDirectory,
        [string] $Executable,
        [string[]] $Arguments,
        [string] $LogPath
    )
    Write-Host "[$Label] $Executable $($Arguments -join ' ')"
    Push-Location $WorkingDirectory
    try {
        $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    [IO.File]::WriteAllLines($LogPath, $lines, [Text.UTF8Encoding]::new($false))
    if ($exitCode -ne 0) {
        throw "$Label failed with exit code $exitCode. See $LogPath"
    }
}

function Invoke-GitText {
    param([string] $Root, [string[]] $Arguments)
    $result = @(& git -C $Root @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) {
        throw "git -C $Root $($Arguments -join ' ') failed: $($result -join [Environment]::NewLine)"
    }
    return ($result -join "`n").Trim()
}

function Get-RepositoryEvidence {
    param([string] $Name, [string] $Root)
    $status = Invoke-GitText $Root @("status", "--porcelain")
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        throw "$Name worktree is dirty; release evidence requires an exact clean commit.`n$status"
    }
    return [ordered]@{
        name = $Name
        rootLabel = Split-Path $Root -Leaf
        branch = Invoke-GitText $Root @("branch", "--show-current")
        commit = Invoke-GitText $Root @("rev-parse", "HEAD")
        clean = $true
    }
}

function Get-FileEvidence {
    param([string] $Path)
    $item = Get-Item -LiteralPath $Path
    return [ordered]@{
        fileName = $item.Name
        bytes = $item.Length
        sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        modifiedAtUtc = $item.LastWriteTimeUtc.ToString("o")
    }
}

function Get-TrackedFileEvidence {
    param([string] $Repository, [string] $Root, [string] $RelativePath)
    $path = Join-Path $Root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required $Repository evidence file is missing: $RelativePath"
    }
    $file = Get-FileEvidence $path
    return [ordered]@{
        repository = $Repository
        path = $RelativePath.Replace("\", "/")
        bytes = $file.bytes
        sha256 = $file.sha256
    }
}

function Get-RequiredSurefireReportEvidence {
    param([string] $Root, [string[]] $ClassNames)
    $evidence = @()
    foreach ($className in $ClassNames) {
        $reports = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter "TEST-$className.xml" |
            Where-Object { $_.FullName -match "[\\/]target[\\/]surefire-reports[\\/]" })
        if ($reports.Count -ne 1) {
            throw "Required Surefire report must appear exactly once: $className (found $($reports.Count))"
        }
        $path = $reports[0].FullName
        [xml] $xml = Get-Content -LiteralPath $path -Raw
        $suite = $xml.testsuite
        $failures = [long]$suite.failures
        $errors = [long]$suite.errors
        if ($failures -ne 0 -or $errors -ne 0) {
            throw "Required Surefire report failed: $className failures=$failures errors=$errors"
        }
        $file = Get-FileEvidence $path
        $evidence += [ordered]@{
            className = $className
            tests = [long]$suite.tests
            failures = $failures
            errors = $errors
            skipped = [long]$suite.skipped
            reportSha256 = $file.sha256
        }
    }
    return @($evidence)
}

function Get-RequiredVitestFileEvidence {
    param([string] $ReportPath, [string[]] $RequiredSuffixes)
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    $testFiles = @($report.testResults | ForEach-Object { $_.name.Replace("\", "/") })
    $evidence = @()
    foreach ($suffix in $RequiredSuffixes) {
        $normalizedSuffix = $suffix.Replace("\", "/")
        $matches = @($testFiles | Where-Object {
            $_.EndsWith($normalizedSuffix, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($matches.Count -ne 1) {
            throw "Required Vitest file must appear exactly once: $normalizedSuffix (found $($matches.Count))"
        }
        $evidence += [ordered]@{ path = $normalizedSuffix; status = "passed" }
    }
    return @($evidence)
}

function Get-UnsafePlayerAccessScanEvidence {
    param([string] $Root)
    $sourceRoot = Join-Path $Root "src/main/java"
    $files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter "*.java")
    $patterns = @(
        'PlayerRef\.getComponent\(Player',
        'getComponent\(Player\.getComponentType\(\)\)',
        'Universe\.get\(\).*getPlayers'
    )
    $matches = @($files | Select-String -Pattern $patterns)
    if ($matches.Count -ne 0) {
        $locations = $matches | ForEach-Object {
            "$($_.Path.Substring($sourceRoot.Length + 1)):$($_.LineNumber)"
        }
        throw "Unsafe player-access scan found $($matches.Count) match(es): $($locations -join ', ')"
    }
    return [ordered]@{
        status = "passed"
        filesScanned = $files.Count
        patterns = $patterns
        matchCount = 0
    }
}

function Get-SurefireEvidence {
    param([string] $Root)
    $reports = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter "TEST-*.xml" |
        Where-Object { $_.FullName -match "[\\/]target[\\/]surefire-reports[\\/]" })
    if ($reports.Count -eq 0) { throw "No Surefire reports found under $Root" }
    $tests = 0L; $failures = 0L; $errors = 0L; $skipped = 0L
    foreach ($report in $reports) {
        [xml] $xml = Get-Content -LiteralPath $report.FullName -Raw
        $suite = $xml.testsuite
        $tests += [long]$suite.tests
        $failures += [long]$suite.failures
        $errors += [long]$suite.errors
        $skipped += [long]$suite.skipped
    }
    if ($failures -ne 0 -or $errors -ne 0) {
        throw "Surefire reports under $Root contain failures=$failures errors=$errors"
    }
    return [ordered]@{
        suites = $reports.Count
        tests = $tests
        failures = $failures
        errors = $errors
        skipped = $skipped
        newestReportUtc = ($reports | Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1).LastWriteTimeUtc.ToString("o")
    }
}

function Get-VitestEvidence {
    param([string] $Path)
    $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if (-not $report.success) { throw "Vitest JSON report is not successful: $Path" }
    return [ordered]@{
        suites = [long]$report.numTotalTestSuites
        tests = [long]$report.numTotalTests
        passed = [long]$report.numPassedTests
        failed = [long]$report.numFailedTests
        pending = [long]$report.numPendingTests
    }
}

function Get-PersistencePerformanceEvidence {
    param([string] $Root)
    $reportPath = Join-Path $Root `
        "target/surefire-reports/TEST-com.alechilles.alecstamework.performance.PersistenceResiliencePerformanceGateTest.xml"
    if (-not (Test-Path -LiteralPath $reportPath)) {
        throw "Persistence performance report is missing: $reportPath"
    }
    $text = Get-Content -LiteralPath $reportPath -Raw
    $availability = [regex]::Match($text,
        'availability decisions=(\d+) activeScopes=(\d+) elapsedMs=(\d+) budgetMs=(\d+)')
    $reload = [regex]::Match($text,
        'quarantine reload activeScopes=(\d+) elapsedMs=(\d+) budgetMs=(\d+)')
    if (-not $availability.Success -or -not $reload.Success) {
        throw "Persistence performance measurements are missing from $reportPath"
    }
    return [ordered]@{
        activeScopeFixtureSize = [long]$availability.Groups[2].Value
        availabilityDecisions = [long]$availability.Groups[1].Value
        availabilityElapsedMs = [long]$availability.Groups[3].Value
        availabilityBudgetMs = [long]$availability.Groups[4].Value
        quarantineReloadElapsedMs = [long]$reload.Groups[2].Value
        quarantineReloadBudgetMs = [long]$reload.Groups[3].Value
    }
}

function Read-ZipText {
    param([IO.Compression.ZipArchive] $Archive, [string] $EntryName)
    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry) { throw "Required JAR entry is missing: $EntryName" }
    $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

foreach ($root in @($tameworkRoot, $telemetryRoot, $platformRoot)) {
    if (-not (Test-Path -LiteralPath (Join-Path $root ".git"))) {
        # Linked worktrees use a .git file, which Test-Path accepts without a container qualifier.
        if (-not (Test-Path -LiteralPath (Join-Path $root ".git"))) {
            throw "Not a Git worktree: $root"
        }
    }
}
New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$sourceBefore = @(
    Get-RepositoryEvidence "tamework" $tameworkRoot
    Get-RepositoryEvidence "alecs-telemetry" $telemetryRoot
    Get-RepositoryEvidence "telemetry-platform" $platformRoot
)

$platformTestReport = Join-Path $evidenceRoot "platform-vitest.json"
Invoke-CandidateCommand "tamework-tests" $tameworkRoot (Join-Path $tameworkRoot "mvnw.cmd") `
    @("-q", "test") (Join-Path $evidenceRoot "tamework-tests.log")
Invoke-CandidateCommand "telemetry-tests" $telemetryRoot (Join-Path $telemetryRoot "mvnw.cmd") `
    @("-q", "test") (Join-Path $evidenceRoot "telemetry-tests.log")
Invoke-CandidateCommand "platform-typecheck" $platformRoot "npm.cmd" `
    @("run", "check") (Join-Path $evidenceRoot "platform-typecheck.log")
Invoke-CandidateCommand "platform-lint" $platformRoot "npm.cmd" `
    @("run", "lint") (Join-Path $evidenceRoot "platform-lint.log")
Invoke-CandidateCommand "platform-tests" $platformRoot "npm.cmd" `
    @("exec", "--", "vitest", "run", "--pool=threads", "--maxWorkers=4",
        "--reporter=json", "--outputFile=$platformTestReport") `
    (Join-Path $evidenceRoot "platform-tests.log")
Invoke-CandidateCommand "platform-build" $platformRoot "npm.cmd" `
    @("run", "build") (Join-Path $evidenceRoot "platform-build.log")
Invoke-CandidateCommand "tamework-package" $tameworkRoot (Join-Path $tameworkRoot "mvnw.cmd") `
    @("-q", "-DskipTests", "package") (Join-Path $evidenceRoot "tamework-package.log")

$sourceAfter = @(
    Get-RepositoryEvidence "tamework" $tameworkRoot
    Get-RepositoryEvidence "alecs-telemetry" $telemetryRoot
    Get-RepositoryEvidence "telemetry-platform" $platformRoot
)
for ($index = 0; $index -lt $sourceBefore.Count; $index++) {
    if ($sourceBefore[$index].commit -cne $sourceAfter[$index].commit) {
        throw "$($sourceBefore[$index].name) commit changed while gates were running"
    }
}

$artifact = Get-ChildItem -LiteralPath (Join-Path $tameworkRoot "target") -File -Filter "*.jar" |
    Where-Object { $_.Name -notlike "original-*" } |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if ($null -eq $artifact) { throw "Packaged Tamework JAR was not found" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$requiredEntries = @(
    'com/alechilles/alecstamework/ui/TameworkCommandSelectionPage$CommandSelectionEventData.class',
    'com/alechilles/alecstamework/persistence/sqlite/SqliteSchemaV7Migration.class',
    'com/alechilles/alecstamework/persistence/sqlite/SqliteMigrationBackupService.class',
    'com/alechilles/alecstamework/persistence/incidents/PersistenceResilienceRuntime.class',
    'com/alechilles/alecstamework/persistence/diagnostics/PersistenceDiagnosticsService.class',
    'com/alechilles/alecstamework/metrics/TameworkPersistenceTelemetry.class',
    'com/alechilles/alecstelemetry/api/TelemetryBreadcrumbContext.class',
    'META-INF/services/java.sql.Driver',
    'META-INF/maven/com.alechilles/alecstelemetry-runtime/pom.properties',
    'telemetry/project.json',
    'manifest.json'
)
$archive = [IO.Compression.ZipFile]::OpenRead($artifact.FullName)
try {
    foreach ($entryName in $requiredEntries) {
        if ($null -eq $archive.GetEntry($entryName)) { throw "Required JAR entry is missing: $entryName" }
    }
    $embeddedRuntimeProperties = Read-ZipText $archive `
        'META-INF/maven/com.alechilles/alecstelemetry-runtime/pom.properties'
    $runtimeVersionMatch = [regex]::Match($embeddedRuntimeProperties, '(?m)^version=(.+)$')
    if (-not $runtimeVersionMatch.Success) { throw "Embedded telemetry runtime version is missing" }
    $embeddedRuntimeVersion = $runtimeVersionMatch.Groups[1].Value.Trim()
    $pluginManifest = Read-ZipText $archive 'manifest.json' | ConvertFrom-Json
} finally {
    $archive.Dispose()
}
if ($embeddedRuntimeVersion -cne "1.0.4") {
    throw "Expected embedded telemetry runtime 1.0.4, found $embeddedRuntimeVersion"
}

$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
$nodeVersion = (& node --version).Trim()
$npmVersion = (& npm.cmd --version).Trim()
$schemaSource = Join-Path $tameworkRoot `
    "src/main/java/com/alechilles/alecstamework/persistence/sqlite/SqliteSchemaV7Migration.java"
$telemetryDescriptor = Join-Path $tameworkRoot "src/main/resources/telemetry/project.json"
$requiredTameworkReports = Get-RequiredSurefireReportEvidence $tameworkRoot @(
    "com.alechilles.alecstamework.architecture.AsyncThreadSafetyGuardTest",
    "com.alechilles.alecstamework.architecture.EcsWriteSafetyGuardTest",
    "com.alechilles.alecstamework.architecture.PersistenceDegradationArchitectureTest",
    "com.alechilles.alecstamework.architecture.PersistenceFaultInjectionArchitectureTest",
    "com.alechilles.alecstamework.performance.PersistenceResiliencePerformanceGateTest",
    "com.alechilles.alecstamework.persistence.diagnostics.PersistenceDiagnosticsServiceTest",
    "com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityServiceTest",
    "com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthServiceTest",
    "com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClassifierTest",
    "com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistryTest",
    "com.alechilles.alecstamework.persistence.incidents.PersistenceHistoricalCorpusManifestTest",
    "com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistryTest",
    "com.alechilles.alecstamework.persistence.incidents.PersistenceScopeDurabilityTest",
    "com.alechilles.alecstamework.persistence.recovery.ScopedPersistenceRecoveryCoordinatorTest",
    "com.alechilles.alecstamework.persistence.recovery.StorageRecoveryCoordinatorTest",
    "com.alechilles.alecstamework.persistence.sqlite.HistoricalSchemaPrerequisiteRepairTest",
    "com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueueIsolationTest",
    "com.alechilles.alecstamework.persistence.sqlite.SqliteMigrationBackupServiceTest",
    "com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaV7MigrationTest",
    "com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntimeMigrationTest",
    "com.alechilles.alecstamework.metrics.PersistenceTelemetryDescriptorTest",
    "com.alechilles.alecstamework.metrics.PersistenceTelemetryPrivacyTest",
    "com.alechilles.alecstamework.items.CommandLinkedNpcCaptureServiceTest",
    "com.alechilles.alecstamework.items.CommandLinkedPanelUnloadedNameServiceTest",
    "com.alechilles.alecstamework.items.CommandLostRecoveryCoordinatorTest",
    "com.alechilles.alecstamework.items.CommandWorldChangeEligibilityTest",
    "com.alechilles.alecstamework.items.ManagedCoopLifecycleRecoveryServiceTest",
    "com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinatorTest",
    "com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationAmbiguityContainmentTest",
    "com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationReconciliationServiceIntegrationTest",
    "com.alechilles.alecstamework.ownership.CompanionPermanentDeathCoordinatorTest",
    "com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionServiceTest"
)
$requiredTelemetryReports = Get-RequiredSurefireReportEvidence $telemetryRoot @(
    "com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContextTest",
    "com.alechilles.alecstelemetry.report.ManualReportRedactorTest",
    "com.alechilles.alecstelemetry.runtime.TelemetryBreadcrumbBridgePayloadTest",
    "com.alechilles.alecstelemetry.runtime.TelemetryBreadcrumbBufferTest",
    "com.alechilles.alecstelemetry.runtime.host.TelemetryRuntimeProviderParityTest"
)
$requiredPlatformTests = Get-RequiredVitestFileEvidence $platformTestReport @(
    "tests/persistence-correlation-migration.test.ts",
    "tests/persistence-correlation.test.ts",
    "tests/persistence-incident-timeline.test.ts",
    "tests/privacy-docs.test.ts",
    "tests/privacy-retention-repo.test.ts",
    "tests/telemetry-breadcrumb-contract.test.ts",
    "portal-ui/src/features/issues/persistence-correlation-view-model.test.ts",
    "portal-ui/src/features/issues/persistence-incident-timeline.test.tsx"
)
$dependencyEvidence = @(
    Get-TrackedFileEvidence "tamework" $tameworkRoot "pom.xml"
    Get-TrackedFileEvidence "alecs-telemetry" $telemetryRoot "pom.xml"
    Get-TrackedFileEvidence "alecs-telemetry" $telemetryRoot "runtime/pom.xml"
    Get-TrackedFileEvidence "telemetry-platform" $platformRoot "package-lock.json"
)
$documentationEvidence = @(
    Get-TrackedFileEvidence "tamework" $tameworkRoot "CHANGELOG.md"
    Get-TrackedFileEvidence "tamework" $tameworkRoot "docs/Persistence-Failure-Classification-Catalog.md"
    Get-TrackedFileEvidence "tamework" $tameworkRoot "docs/Persistence-Performance-Budgets.md"
    Get-TrackedFileEvidence "tamework" $tameworkRoot `
        "wiki/Developer-Documentation/Data-and-Persistence/Persistence-Sqlite-and-Data-Paths.md"
    Get-TrackedFileEvidence "tamework" $tameworkRoot `
        "wiki/Developer-Documentation/Tooling-and-Contribution/Integrations-Telemetry-and-Build-Workflow.md"
    Get-TrackedFileEvidence "alecs-telemetry" $telemetryRoot "docs/privacy-policy.md"
    Get-TrackedFileEvidence "alecs-telemetry" $telemetryRoot "wiki/Integration-Guides/Breadcrumbs.md"
    Get-TrackedFileEvidence "alecs-telemetry" $telemetryRoot "wiki/Integration-Guides/Consent-And-Privacy.md"
    Get-TrackedFileEvidence "telemetry-platform" $platformRoot "docs/persistence-correlation.md"
    Get-TrackedFileEvidence "telemetry-platform" $platformRoot "docs/privacy/data-map.md"
    Get-TrackedFileEvidence "telemetry-platform" $platformRoot "docs/privacy/retention-schedule.md"
)
$evidence = [ordered]@{
    evidenceSchemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    sources = $sourceAfter
    runtime = [ordered]@{
        hytaleVersion = $HytaleVersion
        java = $javaVersion
        node = $nodeVersion
        npm = $npmVersion
        persistenceSchema = 7
        embeddedTelemetryRuntime = $embeddedRuntimeVersion
    }
    tests = [ordered]@{
        tamework = Get-SurefireEvidence $tameworkRoot
        telemetry = Get-SurefireEvidence $telemetryRoot
        platform = Get-VitestEvidence $platformTestReport
        requiredTameworkReports = $requiredTameworkReports
        requiredTelemetryReports = $requiredTelemetryReports
        requiredPlatformFiles = $requiredPlatformTests
        platformTypecheck = "passed"
        platformLint = "passed"
        platformBuild = "passed"
    }
    staticAnalysis = [ordered]@{
        unsafePlayerAccess = Get-UnsafePlayerAccessScanEvidence $tameworkRoot
        ecsWriteGuard = "passed-required-report"
        asyncThreadGuard = "passed-required-report"
    }
    dependencies = $dependencyEvidence
    documentation = $documentationEvidence
    performance = Get-PersistencePerformanceEvidence $tameworkRoot
    package = [ordered]@{
        tameworkVersion = $pluginManifest.Version
        artifact = Get-FileEvidence $artifact.FullName
        requiredEntries = $requiredEntries
        schemaV7Source = Get-FileEvidence $schemaSource
        telemetryDescriptor = Get-FileEvidence $telemetryDescriptor
    }
    backupBoundary = [ordered]@{
        wholeSaveBackupCreatedByTamework = $false
        migrationProtection = "verified-transactional-tamework-sqlite-snapshot-only"
        hytaleOwnsWholeSaveBackups = $true
        externalHytaleBackupReference = if ([string]::IsNullOrWhiteSpace($ExternalHytaleBackupReference)) {
            $null
        } else {
            $ExternalHytaleBackupReference.Trim()
        }
    }
    releaseRehearsal = [ordered]@{
        exactRepositoryAndPackageGates = "passed"
        isolatedRuntimeHarnessStatus = "required-after-candidate-build"
        sameUniverseSecondBootStatus = "required-after-isolated-upgrade"
        liveWorldStatus = "pending-user-run"
        rollbackStatus = "pending-operator-selected-hytale-backup"
        platformDeploymentVerification = "pending-deployment-authorization"
        publicCutoverStatus = "not-authorized"
    }
    knownLimitations = @(
        "Candidate evidence does not replace representative copied-player-world gameplay rehearsal.",
        "Platform deployment and public download verification require explicit deployment authorization.",
        "Rollback proof requires an operator-selected Hytale backup paired with its matching pre-v7 Tamework SQLite state."
    )
}

$outputParent = Split-Path -Path $OutputPath -Parent
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
$json = $evidence | ConvertTo-Json -Depth 12
[IO.File]::WriteAllText($OutputPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Host "Persistence release candidate evidence written to $OutputPath"
Write-Host "Candidate SHA-256: $($evidence.package.artifact.sha256)"

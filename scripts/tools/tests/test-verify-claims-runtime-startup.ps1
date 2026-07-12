[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BuiltArtifact,
    [Parameter(Mandatory = $true)][string] $HytaleServerJar,
    [Parameter(Mandatory = $true)][string] $HytaleAssets,
    [Parameter(Mandatory = $true)][string] $JavaExecutable,
    [Parameter(Mandatory = $true)][string] $SimpleClaimsJar,
    [Parameter(Mandatory = $true)][string] $QuestLinesClaimsJar
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$toolsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$moduleRoot = Join-Path $toolsRoot "claims-runtime"
Import-Module (Join-Path $moduleRoot "ClaimsRuntimeHarness.psm1") -Force
Import-Module (Join-Path $moduleRoot "ClaimsRuntimeEvidence.psm1")
Import-Module (Join-Path $moduleRoot "ClaimsRuntimeInputs.psm1")
Import-Module (Join-Path $moduleRoot "ClaimsRuntimeLogs.psm1")
Import-Module (Join-Path $moduleRoot "ClaimsRuntimeProcess.psm1")

function Assert-ClaimsTest {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Assert-ClaimsThrows {
    param([scriptblock] $Action, [string] $Pattern, [string] $Message)
    try {
        & $Action
    } catch {
        Assert-ClaimsTest ($_.Exception.ToString() -match $Pattern) $Message
        return
    }
    throw "ASSERTION FAILED: $Message (no exception was thrown)"
}

function Write-ClaimsTestText {
    param([string] $Path, [string] $Text)
    $parent = Split-Path -Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function New-ClaimsPreV6Fixture {
    param([string] $Root, [string] $Java, [string] $Artifact)
    $database = Join-Path $Root "universe\Tamework\Data\tamework.sqlite"
    New-Item -ItemType Directory -Path (Split-Path $database -Parent) | Out-Null
    $source = Join-Path $Root "ClaimsRuntimeFixture.java"
    $javaSource = @'
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

public final class ClaimsRuntimeFixture {
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        int rows = Integer.parseInt(args[1]);
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                statement.execute("INSERT INTO schema_migrations VALUES (2, 'schema_v2')");
                statement.execute("INSERT INTO schema_migrations VALUES (3, 'schema_v3')");
                statement.execute("INSERT INTO schema_migrations VALUES (4, 'schema_v4')");
                statement.execute("INSERT INTO schema_migrations VALUES (2001, 'legacy_import')");
                statement.execute("CREATE TABLE npc_profiles (profile_id TEXT PRIMARY KEY, owner_uuid TEXT)");
            }
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO npc_profiles(profile_id, owner_uuid) VALUES (?, ?)")) {
                for (int i = 0; i < rows; i++) {
                    insert.setString(1, "profile-" + i);
                    insert.setString(2, i % 2 == 0 ? "owner-" + i : null);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
    }
}
'@
    Write-ClaimsTestText -Path $source -Text $javaSource
    $output = & $Java --class-path $Artifact $source $database 77 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Fixture database creation failed with exit code $LASTEXITCODE`n$output"
    }
    return $database
}

function New-ClaimsFakeProcessContext {
    param([string] $Root, [string] $Name)
    $scenarioHome = Join-Path $Root $Name
    $evidence = Join-Path $scenarioHome "evidence"
    New-Item -ItemType Directory -Path $evidence -Force | Out-Null
    return [pscustomobject]@{ home = $scenarioHome; evidence = $evidence }
}

function New-ClaimsTestDiagnosticPolicy {
    param([string] $ScenarioId, [string] $Kind, [object[]] $Artifacts)
    return [pscustomobject]@{
        scenarioId = $ScenarioId
        expectedKind = $Kind
        providerArtifacts = $Artifacts
    }
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempBase ("tamework-claims-runtime-tests-" + ([Guid]::NewGuid()).ToString("N"))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    foreach ($module in @(Get-ChildItem -LiteralPath $moduleRoot -File -Filter "*.psm1")) {
        $lineCount = (Get-Content -LiteralPath $module.FullName).Count
        Assert-ClaimsTest ($lineCount -le 500) "$($module.Name) has $lineCount raw lines; maximum is 500"
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile(
            $module.FullName, [ref]$tokens, [ref]$errors
        ) | Out-Null
        Assert-ClaimsTest ($errors.Count -eq 0) "$($module.Name) must parse cleanly"
    }

    $plan = @(Get-ClaimsRuntimeScenarioPlan)
    Assert-ClaimsTest ($plan.Count -eq 5) "exactly five packaged-runtime scenarios are planned"
    $expectedIds = @(
        "fresh-no-provider", "simpleclaims-1.0.38", "questlines-claims-1.3.1",
        "both-providers-auto", "copied-upgrade-save"
    )
    Assert-ClaimsTest (($plan.id -join "|") -ceq ($expectedIds -join "|")) "scenario IDs and order are stable"
    Assert-ClaimsTest (@($plan | Where-Object copiedUpgrade).Count -eq 1) "only the upgrade scenario copies a save"
    foreach ($scenario in $plan) {
        Assert-ClaimsTest ($scenario.providerResolutionAssertion -match "operation-scoped") `
            "startup plan must not overclaim operation-scoped provider selection"
        $settings = New-ClaimsRuntimeSettings -ProviderSetting $scenario.providerSetting
        Assert-ClaimsTest ($settings.population.limitPerPlayerOwnedTotal -eq 3) "owner limit is active"
        Assert-ClaimsTest ($settings.population.perPlayerLimitScope -ceq "Global") "owner limit is global"
        Assert-ClaimsTest ($settings.simpleClaims.simpleClaimsEnabled) "claim rules are enabled"
        Assert-ClaimsTest ($settings.simpleClaims.limitPerClaimChunk -eq 2) "chunk limit is active"
        Assert-ClaimsTest ($settings.simpleClaims.limitPerClaimTotal -eq 6) "claim-total limit is active"
        Assert-ClaimsTest ($settings.simpleClaims.breedingRequiresClaim) "claim requirement is active"
        Assert-ClaimsTest ($settings.simpleClaims.protectTamedFromNonMembers) "damage protection is active"
    }

    $goodLog = @"
Enabled plugin Fixture:Tamework
Enabled plugin Fixture:Provider
Hytale Server Booted!
Shutdown completed!
"@
    $goodAnalysis = Get-ClaimsRuntimeLogAnalysis -Text $goodLog `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -ForbiddenPluginIds @("Fixture:Forbidden")
    Assert-ClaimsTest $goodAnalysis.passed "clean boot/shutdown log passes"
    $duplicateAnalysis = Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "Enabled plugin Fixture:Provider`n") `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest (-not $duplicateAnalysis.passed) "duplicate provider enablement fails"
    $crossChannelAnalysis = Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + $goodLog) `
        -PluginEnablementText $goodLog -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest $crossChannelAnalysis.passed `
        "duplicate console/file capture does not masquerade as duplicate plugin registration"
    $badAnalysis = Get-ClaimsRuntimeLogAnalysis `
        -Text ($goodLog + "SEVERE provider contract failed with ExampleException`n") `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest (-not $badAnalysis.passed -and $badAnalysis.findings.Count -gt 0) `
        "severe/provider/exception findings fail"
    $rawFatal = Get-ClaimsRuntimeLogAnalysis -Text $goodLog `
        -RawProcessStderr "Exception in thread main FixtureException" `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest (-not $rawFatal.passed) "raw pre-logger exception stderr remains fatal"
    $reallocateLine = "[2026/07/12 03:07:10 SEVERE] [SERR] Reallocate: 131072 to 1179648"
    $reallocateAnalysis = Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $reallocateLine) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest ($reallocateAnalysis.passed -and
        $reallocateAnalysis.ignoredFindings.Count -eq 1) "exact numeric Reallocate baseline is recorded/ignored"
    $changedReallocate = Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $reallocateLine + " extra") `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider")
    Assert-ClaimsTest (-not $changedReallocate.passed) "changed Reallocate text remains fatal"

    $questArtifact = [pscustomobject]@{
        pluginId = "net.evilcraft:QuestLinesClaims"; version = "1.3.1"
        sha256 = "9AA23C0CCD0FD8BB70F305D952AA1B9A0BBF1AEC46D9D8D6DAD37E04B3F2F592"
    }
    $simpleArtifact = [pscustomobject]@{
        pluginId = "Buuz135:SimpleClaims"; version = "1.0.38"
        sha256 = "664C6F5681695238FD898E851B044A90812AA13282D2A97A0770802182B7683B"
    }
    $questPolicy = New-ClaimsTestDiagnosticPolicy "questlines-claims-1.3.1" `
        "questlines-no-provider" @($questArtifact)
    $questCluster = @"
[2026/07/12 03:06:32 SEVERE] [SERR] SLF4J: No SLF4J providers were found.
[2026/07/12 03:06:32 SEVERE] [SERR] SLF4J: Defaulting to no-operation (NOP) logger implementation
[2026/07/12 03:06:32 SEVERE] [SERR] SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details.
"@
    $questKnown = Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $questCluster) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $questPolicy
    Assert-ClaimsTest ($questKnown.passed -and $questKnown.knownProviderDiagnostics.clusterMatches -eq 1) `
        "exact QL-only provider diagnostic is hash/scenario scoped"
    $wrongLane = New-ClaimsTestDiagnosticPolicy "simpleclaims-1.0.38" "none" @($simpleArtifact)
    Assert-ClaimsTest (-not (Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $questCluster) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $wrongLane).passed) "provider diagnostic in the wrong lane is fatal"
    Assert-ClaimsTest (-not (Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $questCluster + "`n" + $questCluster) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $questPolicy).passed) "duplicate provider cluster is fatal"
    $wrongHashArtifact = $questArtifact.PSObject.Copy()
    $wrongHashArtifact.sha256 = "0" * 64
    $wrongHash = New-ClaimsTestDiagnosticPolicy "questlines-claims-1.3.1" `
        "questlines-no-provider" @($wrongHashArtifact)
    Assert-ClaimsTest (-not (Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $questCluster) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $wrongHash).passed) "changed provider hash revokes the waiver"
    Assert-ClaimsTest (-not (Get-ClaimsRuntimeLogAnalysis `
        -Text ($goodLog + "`n" + ($questCluster -replace 'further details\.', 'details changed.')) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $questPolicy).passed) "changed provider diagnostic text is fatal"
    Assert-ClaimsTest (-not (Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $questCluster + "SEVERE unrelated`n") `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $questPolicy).passed) "unrelated SEVERE remains fatal"

    $bothPolicy = New-ClaimsTestDiagnosticPolicy "both-providers-auto" `
        "mixed-provider-collision" @($simpleArtifact, $questArtifact)
    $bothCluster = @"
[2026/07/12 03:07:06 SEVERE] [SERR] SLF4J: A SLF4J service provider failed to instantiate:
[2026/07/12 03:07:06 SEVERE] [SERR] org.slf4j.spi.SLF4JServiceProvider: org.slf4j.simple.SimpleServiceProvider not a subtype
[2026/07/12 03:07:06 SEVERE] [SERR] SLF4J: No SLF4J providers were found.
[2026/07/12 03:07:06 SEVERE] [SERR] SLF4J: Defaulting to no-operation (NOP) logger implementation
[2026/07/12 03:07:06 SEVERE] [SERR] SLF4J: See https://www.slf4j.org/codes.html#noProviders for further details.
"@
    Assert-ClaimsTest (Get-ClaimsRuntimeLogAnalysis -Text ($goodLog + "`n" + $bothCluster) `
        -ExpectedPluginIds @("Fixture:Tamework", "Fixture:Provider") `
        -KnownProviderDiagnosticPolicy $bothPolicy).passed "exact both-provider collision cluster is recorded"

    $upgradeSource = Join-Path $testRoot "upgrade-source"
    $sourceDatabase = New-ClaimsPreV6Fixture -Root $upgradeSource `
        -Java $JavaExecutable -Artifact $BuiltArtifact
    $probeSource = New-ClaimsRuntimeSqliteProbeSource `
        -Destination (Join-Path $testRoot "ClaimsRuntimeSqliteProbe.java")
    $sourceProbe = Invoke-ClaimsRuntimeSqliteProbe -JavaExecutable $JavaExecutable `
        -BuiltArtifact $BuiltArtifact -ProbeSource $probeSource -DatabasePath $sourceDatabase
    Assert-ClaimsTest ($sourceProbe.integrityCheck -ceq "ok") "pre-v6 fixture is integrity-clean"
    Assert-ClaimsTest ($sourceProbe.profileRows -eq 77) "pre-v6 fixture has 77 profiles"
    Assert-ClaimsTest ($sourceProbe.canonicalRows -eq -1) "pre-v6 fixture has no canonical table"
    Assert-ClaimsTest ($sourceProbe.missingCanonicalRows -eq -1 -and $sourceProbe.orphanCanonicalRows -eq -1) `
        "multi-table probes degrade safely before v6"

    $isolatedCallbackScript = @'
param($ModulePath, $Java, $Artifact, $ProbeSource, $Database)
Import-Module $ModulePath -Force
$probe = New-ClaimsRuntimeSqliteReadinessProbe -JavaExecutable $Java `
    -BuiltArtifact $Artifact -ProbeSource $ProbeSource -DatabasePath $Database `
    -ExpectedCanonicalRows 77
& $probe | ConvertTo-Json -Depth 10 -Compress
'@
    $isolatedRunspace = [Management.Automation.PowerShell]::Create()
    try {
        $null = $isolatedRunspace.AddScript($isolatedCallbackScript)
        foreach ($argument in @(
            (Join-Path $moduleRoot "ClaimsRuntimeReadiness.psm1"),
            $JavaExecutable, $BuiltArtifact, $probeSource, $sourceDatabase
        )) { $null = $isolatedRunspace.AddArgument($argument) }
        $isolatedOutput = @($isolatedRunspace.Invoke())
        $isolatedErrors = @($isolatedRunspace.Streams.Error)
        Assert-ClaimsTest ($isolatedErrors.Count -eq 0) `
            "SQLite readiness callback loads its dependencies in an isolated runspace"
        $isolatedSample = (($isolatedOutput | ForEach-Object ToString) -join "") | ConvertFrom-Json
        Assert-ClaimsTest ($null -eq $isolatedSample.PSObject.Properties["error"]) `
            "SQLite readiness callback resolves its probe and validator dependencies"
        Assert-ClaimsTest ($null -ne $isolatedSample.PSObject.Properties["scanSessionState"] -and
            -not $isolatedSample.ready) "pre-v6 readiness sample is structured but not ready"
    } finally {
        $isolatedRunspace.Dispose()
    }

    $sourceMods = Join-Path $upgradeSource "mods"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "SimpleClaims\data\sentinel.txt") -Text "keep"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "inherited.jar") -Text "do-not-copy"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "Nested\inherited.zip") -Text "do-not-copy"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "SimpleClaims\data\native.dll") -Text "do-not-copy"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "UnpackedPlugin\manifest.json") -Text "{}"
    Write-ClaimsTestText -Path (Join-Path $sourceMods "UnpackedPlugin\Plugin.class") -Text "do-not-copy"
    Write-ClaimsTestText -Path (Join-Path $upgradeSource "backup\old-world.txt") -Text "skip"
    Write-ClaimsTestText -Path (Join-Path $upgradeSource "assetEditor\cache.txt") -Text "skip"
    $sourceConfigPath = Join-Path $upgradeSource "config.json"
    Write-ClaimsTestText -Path $sourceConfigPath -Text (([ordered]@{
        Version = 4
        Backup = [ordered]@{ Enabled = $true }
        Mods = [ordered]@{
            "Alechilles:Alec's Tamework!" = [ordered]@{ Enabled = $false }
            "Buuz135:SimpleClaims" = [ordered]@{ Enabled = $false }
            "net.evilcraft:QuestLinesClaims" = [ordered]@{ Enabled = $false }
        }
    } | ConvertTo-Json -Depth 10) + [Environment]::NewLine)
    $sourceConfigHash = (Get-FileHash -LiteralPath $sourceConfigPath -Algorithm SHA256).Hash
    $preexistingBackup = Join-Path (Split-Path $sourceDatabase -Parent) `
        "tamework_pre_v6_existing.sqlite.bak"
    Copy-Item -LiteralPath $sourceDatabase -Destination $preexistingBackup

    $existingOutput = Join-Path $testRoot "existing-output"
    New-Item -ItemType Directory -Path $existingOutput | Out-Null
    Assert-ClaimsThrows -Action {
        Get-ClaimsRuntimeInputs -BuiltArtifact $BuiltArtifact -HytaleServerJar $HytaleServerJar `
            -HytaleAssets $HytaleAssets -JavaExecutable $JavaExecutable `
            -SimpleClaimsJar $SimpleClaimsJar -QuestLinesClaimsJar $QuestLinesClaimsJar `
            -UpgradeSaveSource $upgradeSource -OutputRoot $existingOutput
    } -Pattern "brand new" -Message "existing output roots are refused"
    $liveCandidate = Join-Path ([Environment]::GetFolderPath(
        [Environment+SpecialFolder]::ApplicationData
    )) ("Hytale\UserData\claims-harness-refusal-" + ([Guid]::NewGuid()).ToString("N"))
    Assert-ClaimsThrows -Action {
        Get-ClaimsRuntimeInputs -BuiltArtifact $BuiltArtifact -HytaleServerJar $HytaleServerJar `
            -HytaleAssets $HytaleAssets -JavaExecutable $JavaExecutable `
            -SimpleClaimsJar $SimpleClaimsJar -QuestLinesClaimsJar $QuestLinesClaimsJar `
            -UpgradeSaveSource $upgradeSource -OutputRoot $liveCandidate
    } -Pattern "live Hytale UserData" -Message "live UserData output roots are refused"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath $liveCandidate)) "live refusal creates nothing"
    $junctionTarget = Join-Path $testRoot "junction-target"
    $junctionPath = Join-Path $testRoot "output-junction"
    New-Item -ItemType Directory -Path $junctionTarget | Out-Null
    New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget | Out-Null
    try {
        Assert-ClaimsThrows -Action {
            Get-ClaimsRuntimeInputs -BuiltArtifact $BuiltArtifact -HytaleServerJar $HytaleServerJar `
                -HytaleAssets $HytaleAssets -JavaExecutable $JavaExecutable `
                -SimpleClaimsJar $SimpleClaimsJar -QuestLinesClaimsJar $QuestLinesClaimsJar `
                -UpgradeSaveSource $upgradeSource -OutputRoot (Join-Path $junctionPath "new-output")
        } -Pattern "reparse point" -Message "junction-backed output roots are refused"
    } finally {
        Remove-Item -LiteralPath $junctionPath -Force
    }

    $scenarioOutput = Join-Path $testRoot "scenario-output"
    $inputs = Get-ClaimsRuntimeInputs -BuiltArtifact $BuiltArtifact -HytaleServerJar $HytaleServerJar `
        -HytaleAssets $HytaleAssets -JavaExecutable $JavaExecutable `
        -SimpleClaimsJar $SimpleClaimsJar -QuestLinesClaimsJar $QuestLinesClaimsJar `
        -UpgradeSaveSource $upgradeSource -OutputRoot $scenarioOutput
    $manifests = Assert-ClaimsRuntimeManifests -Inputs $inputs
    Assert-ClaimsTest `
        ($manifests.tamework.optionalDependencies["Buuz135:SimpleClaims"] -ceq ">=1.0.38 <1.1.0") `
        "Tamework SimpleClaims manifest range is exact"
    Assert-ClaimsTest `
        ($manifests.tamework.optionalDependencies["net.evilcraft:QuestLinesClaims"] -ceq "=1.3.1") `
        "Tamework QuestLines manifest range is exact"
    New-Item -ItemType Directory -Path $scenarioOutput | Out-Null
    $upgradePlan = $plan | Where-Object copiedUpgrade
    $context = Initialize-ClaimsRuntimeScenario -Scenario $upgradePlan -Inputs $inputs `
        -Manifests $manifests `
        -Settings (New-ClaimsRuntimeSettings -ProviderSetting $upgradePlan.providerSetting)
    Assert-ClaimsTest ($context.databasePath -ceq (Join-Path $context.home `
        "universe\Tamework\Data\tamework.sqlite")) "upgrade database is copied under home/universe"
    Assert-ClaimsTest ($context.upgradeCopyEvidence.matchedBeforeStartup) "upgrade DB hash matches before startup"
    $expectedSnapshotFiles = 1 + [int](Test-Path -LiteralPath ($sourceDatabase + "-wal")) +
        [int](Test-Path -LiteralPath ($sourceDatabase + "-shm"))
    Assert-ClaimsTest ($context.upgradeCopyEvidence.snapshotFiles.Count -eq $expectedSnapshotFiles) `
        "copied SQLite snapshot records the main file and every present WAL/SHM sidecar"
    Assert-ClaimsTest $context.settingsConfiguredActive "staged settings read back with every rule active"
    Assert-ClaimsTest ($context.serverConfigEvidence.passed -and
        $context.serverConfigEvidence.backupDisabled) "isolated copied config disables automatic backup"
    Assert-ClaimsTest (-not ($context.serverConfigEvidence.pluginChecks | Where-Object {
        -not $_.actualEnabled
    })) "copied config enables Tamework and both scenario providers"
    Assert-ClaimsTest ((Get-FileHash -LiteralPath $sourceConfigPath -Algorithm SHA256).Hash -ceq
        $sourceConfigHash) "source config remains byte-for-byte unchanged"
    $sourceConfigReadBack = Get-Content -LiteralPath $sourceConfigPath -Raw | ConvertFrom-Json
    Assert-ClaimsTest (-not $sourceConfigReadBack.Mods."Buuz135:SimpleClaims".Enabled -and
        -not $sourceConfigReadBack.Mods."net.evilcraft:QuestLinesClaims".Enabled) `
        "source provider disable flags remain untouched"
    $copyProbe = Invoke-ClaimsRuntimeSqliteProbe -JavaExecutable $JavaExecutable `
        -BuiltArtifact $BuiltArtifact -ProbeSource $probeSource -DatabasePath $context.databasePath
    Assert-ClaimsTest ($copyProbe.profileRows -eq 77) "copied pre-v6 DB still has 77 profiles"
    Assert-ClaimsTest (Test-Path -LiteralPath (Join-Path $context.mods `
        "SimpleClaims\data\sentinel.txt")) "active mod data survives save copy"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.mods "inherited.jar"))) `
        "inherited jar is filtered"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.mods "Nested\inherited.zip"))) `
        "nested inherited zip is filtered"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.mods `
        "SimpleClaims\data\native.dll"))) "native plugin payload is filtered"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.mods "UnpackedPlugin"))) `
        "unpacked plugin payload is filtered"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.home "backup"))) `
        "historical backup tree is excluded"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath (Join-Path $context.home "assetEditor"))) `
        "asset-editor cache is excluded"

    $beforeNewBackup = Get-ClaimsRuntimeUpgradeBackupEvidence `
        -DatabasePath $context.databasePath -JavaExecutable $JavaExecutable `
        -BuiltArtifact $BuiltArtifact -ProbeSource $probeSource `
        -ExpectedProfileRows 77 -ExpectedMigrationVersions $sourceProbe.migrationVersions `
        -PreexistingBackups $context.preexistingPreV6Backups
    Assert-ClaimsTest (-not $beforeNewBackup.passed) "a copied preexisting backup cannot satisfy runtime proof"
    $newBackup = Join-Path (Split-Path $context.databasePath -Parent) `
        "tamework_pre_v6_created.sqlite.bak"
    Copy-Item -LiteralPath $context.databasePath -Destination $newBackup
    $afterNewBackup = Get-ClaimsRuntimeUpgradeBackupEvidence `
        -DatabasePath $context.databasePath -JavaExecutable $JavaExecutable `
        -BuiltArtifact $BuiltArtifact -ProbeSource $probeSource `
        -ExpectedProfileRows 77 -ExpectedMigrationVersions $sourceProbe.migrationVersions `
        -PreexistingBackups $context.preexistingPreV6Backups
    Assert-ClaimsTest ($afterNewBackup.passed -and $afterNewBackup.createdByThisRun.Count -eq 1) `
        "a new non-empty integrity-clean pre-v6 backup satisfies runtime proof"

    $postEvidence = [pscustomobject]@{
        integrityCheck = "ok"; journalMode = "wal"; synchronous = 2
        migrationV6Count = 1; coverageTotal = 7; coverageReady = 7
        coverageDistinctDimensions = 7
        coverageDimensions = "BASE_CONTAINER_BLOCKS,CUSTOM_CONTAINERS,GLOBAL_OWNER,PER_WORLD_OWNER,PLAYER_SAVES,PROFILE_STATE,WORLD_ENTITIES"
        scanSessionState = "READY"; nonterminalOperations = 0
        canonicalRows = 78; profileRows = 78; missingCanonicalRows = 0; orphanCanonicalRows = 0
    }
    $preservation = Test-ClaimsRuntimeSqliteEvidence -Evidence $postEvidence -ExpectedCanonicalRows 77
    Assert-ClaimsTest $preservation.passed "upgrade preservation accepts growth while enforcing a 77-row floor"
    $postEvidence.coverageDimensions = "A,B,C,D,E,F,G"
    Assert-ClaimsTest (-not (Test-ClaimsRuntimeSqliteEvidence -Evidence $postEvidence).passed) `
        "seven arbitrary coverage dimension names cannot satisfy readiness"

    $ledgerRoot = Join-Path $testRoot "ledger"
    New-Item -ItemType Directory -Path $ledgerRoot | Out-Null
    $ledgerInputs = [ordered]@{}
    foreach ($name in @(
        "builtArtifact", "hytaleServerJar", "javaExecutable", "simpleClaimsJar",
        "questLinesClaimsJar", "upgradeSourceDatabase", "hytaleAssets"
    )) {
        $path = Join-Path $ledgerRoot "$name.bin"
        Write-ClaimsTestText -Path $path -Text $name
        $ledgerInputs[$name] = $path
    }
    $ledgerInputs = [pscustomobject]$ledgerInputs
    $ledgerBefore = Get-ClaimsRuntimeTrackedInputState -Inputs $ledgerInputs
    $unchanged = Compare-ClaimsRuntimeTrackedInputState -Before $ledgerBefore `
        -After (Get-ClaimsRuntimeTrackedInputState -Inputs $ledgerInputs)
    Assert-ClaimsTest $unchanged.passed "unchanged explicit inputs pass the immutability ledger"
    Write-ClaimsTestText -Path $ledgerInputs.upgradeSourceDatabase -Text "changed-source-db"
    $changed = Compare-ClaimsRuntimeTrackedInputState -Before $ledgerBefore `
        -After (Get-ClaimsRuntimeTrackedInputState -Inputs $ledgerInputs)
    Assert-ClaimsTest (-not $changed.passed) "changed upgrade source fails the immutability ledger"

    $cmd = (Get-Command cmd.exe -ErrorAction Stop).Source
    $gracefulBatch = Join-Path $testRoot "fake-graceful.cmd"
    Write-ClaimsTestText -Path $gracefulBatch -Text (@'
@echo off
if not exist logs mkdir logs
echo Enabled plugin Fixture:Expected>logs\fake.log
echo Hytale Server Booted!>>logs\fake.log
set /p REQUEST=
echo Shutdown completed!>>logs\fake.log
exit /b 0
'@)
    $fakeInputs = [pscustomobject]@{ javaExecutable = "unused"; hytaleServerJar = "unused"; hytaleAssets = "unused" }
    $gracefulContext = New-ClaimsFakeProcessContext -Root $testRoot -Name "fake-graceful-home"
    $readinessState = [pscustomobject]@{ count = 0 }
    $readinessProbe = {
        $readinessState.count++
        [pscustomobject]@{ ready = $readinessState.count -ge 3; sample = $readinessState.count }
    }.GetNewClosure()
    $graceful = Invoke-ClaimsRuntimeServerProcess -Context $gracefulContext -Inputs $fakeInputs `
        -DwellSeconds 0 -StartupTimeoutSeconds 3 -ShutdownTimeoutSeconds 3 `
        -ReadinessProbe $readinessProbe -ReadinessTimeoutSeconds 5 `
        -ExecutableOverride $cmd -ArgumentOverride @("/d", "/c", $gracefulBatch)
    Assert-ClaimsTest ($null -ne $graceful.bootedAtUtc -and $null -ne $graceful.stopSentAtUtc) `
        "fake server reaches boot and receives graceful stop"
    Assert-ClaimsTest (-not $graceful.forcedTermination -and $graceful.exitCode -eq 0) `
        "fake graceful process exits cleanly"
    Assert-ClaimsTest ([string]::IsNullOrWhiteSpace([string]$graceful.lifecycleError)) `
        "fake graceful process has no lifecycle error"
    Assert-ClaimsTest ($graceful.readiness.satisfied -and $graceful.readiness.samples.Count -eq 3) `
        "readiness polling holds the server until a terminal-ready sample"
    $notReadyContext = New-ClaimsFakeProcessContext -Root $testRoot -Name "fake-not-ready-home"
    $notReady = Invoke-ClaimsRuntimeServerProcess -Context $notReadyContext -Inputs $fakeInputs `
        -DwellSeconds 0 -StartupTimeoutSeconds 3 -ShutdownTimeoutSeconds 3 `
        -ReadinessProbe { [pscustomobject]@{ ready = $false } } -ReadinessTimeoutSeconds 1 `
        -ExecutableOverride $cmd -ArgumentOverride @("/d", "/c", $gracefulBatch)
    Assert-ClaimsTest (-not $notReady.readiness.satisfied -and $notReady.exitCode -eq 0) `
        "readiness timeout remains a failing evidence state even after graceful shutdown"

    $earlyExitBatch = Join-Path $testRoot "fake-early-exit.cmd"
    Write-ClaimsTestText -Path $earlyExitBatch -Text (@'
@echo off
if not exist logs mkdir logs
echo Hytale Server Booted!>logs\fake.log
echo early-exit-stdout
exit /b 7
'@)
    $earlyExitContext = New-ClaimsFakeProcessContext -Root $testRoot -Name "fake-early-exit-home"
    $earlyExit = Invoke-ClaimsRuntimeServerProcess -Context $earlyExitContext -Inputs $fakeInputs `
        -DwellSeconds 1 -StartupTimeoutSeconds 3 -ShutdownTimeoutSeconds 1 `
        -ExecutableOverride $cmd -ArgumentOverride @("/d", "/c", $earlyExitBatch)
    Assert-ClaimsTest ($null -ne $earlyExit.pid -and $earlyExit.exitCode -eq 7 -and
        $earlyExit.stdout.Contains("early-exit-stdout")) "early exit still returns PID/exit/stream evidence"

    $timeoutBatch = Join-Path $testRoot "fake-timeout.cmd"
    Write-ClaimsTestText -Path $timeoutBatch -Text (@'
@echo off
powershell.exe -NoProfile -Command "Start-Sleep -Seconds 20"
'@)
    $timeoutContext = New-ClaimsFakeProcessContext -Root $testRoot -Name "fake-timeout-home"
    $timeout = Invoke-ClaimsRuntimeServerProcess -Context $timeoutContext -Inputs $fakeInputs `
        -DwellSeconds 0 -StartupTimeoutSeconds 1 -ShutdownTimeoutSeconds 1 `
        -ExecutableOverride $cmd -ArgumentOverride @("/d", "/c", $timeoutBatch)
    Assert-ClaimsTest ($timeout.forcedTermination -and $null -eq $timeout.bootedAtUtc) `
        "startup/shutdown timeout forces the fake process tree down"
    $failureContext = New-ClaimsFakeProcessContext -Root $testRoot -Name "fake-start-failure-home"
    Assert-ClaimsThrows -Action {
        Invoke-ClaimsRuntimeServerProcess -Context $failureContext -Inputs $fakeInputs `
            -DwellSeconds 0 -StartupTimeoutSeconds 1 -ShutdownTimeoutSeconds 1 `
            -ExecutableOverride (Join-Path $testRoot "does-not-exist.exe") -ArgumentOverride @()
    } -Pattern "(?i)(cannot find|not found|No such file)" `
        -Message "start failures retain the original process-start error"

    $validateOutput = Join-Path $testRoot "validate-only-must-not-exist"
    $validation = Invoke-ClaimsRuntimeVerification -BuiltArtifact $BuiltArtifact `
        -HytaleServerJar $HytaleServerJar -HytaleAssets $HytaleAssets `
        -JavaExecutable $JavaExecutable -SimpleClaimsJar $SimpleClaimsJar `
        -QuestLinesClaimsJar $QuestLinesClaimsJar -UpgradeSaveSource $upgradeSource `
        -OutputRoot $validateOutput -DwellSeconds 1 -StartupTimeoutSeconds 1 `
        -ShutdownTimeoutSeconds 1 -ValidateOnly
    Assert-ClaimsTest ($validation.mode -ceq "validate-only") "validate-only mode is reported"
    Assert-ClaimsTest $validation.providerContractEvidence.passed "validate-only executes provider contracts"
    Assert-ClaimsTest ($validation.upgradeSourceSqlite.profileRows -eq 77 -and
        $validation.expectedPostUpgradeCanonicalRows -eq 77) "validate-only probes the pre-v6 baseline"
    Assert-ClaimsTest $validation.inputImmutability.passed "validate-only leaves all explicit inputs unchanged"
    Assert-ClaimsTest ($validation.upgradeReadinessTimeoutSeconds -eq 300) `
        "validate-only reports the safe five-minute upgrade-readiness default"
    Assert-ClaimsTest (-not (Test-Path -LiteralPath $validateOutput)) `
        "validate-only creates no output root and launches no Hytale server"

    Write-Output "Claims runtime startup harness self-test passed (no Hytale server was launched)."
} finally {
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $safeName = (Split-Path -Path $resolvedTestRoot -Leaf) -like "tamework-claims-runtime-tests-*"
    $tempPrefix = $tempBase.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $safeWithinTemp = $resolvedTestRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)
    if ($safeName -and $safeWithinTemp -and
            (Test-Path -LiteralPath $resolvedTestRoot)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

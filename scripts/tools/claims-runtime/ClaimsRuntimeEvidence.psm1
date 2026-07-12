Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-ClaimsRuntimeManifest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JarPath
    )

    $resolved = (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolved)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq "manifest.json" })
        if ($entries.Count -ne 1) {
            throw "Expected exactly one root manifest.json in '$resolved'; found $($entries.Count)."
        }
        $reader = [System.IO.StreamReader]::new($entries[0].Open(), [Text.Encoding]::UTF8)
        try {
            $manifest = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    foreach ($field in @("Group", "Name", "Version", "Main")) {
        if ([string]::IsNullOrWhiteSpace([string]$manifest.$field)) {
            throw "Manifest '$resolved' is missing required field '$field'."
        }
    }

    $optionalDependencies = [ordered]@{}
    $optionalProperty = $manifest.PSObject.Properties["OptionalDependencies"]
    if ($null -ne $optionalProperty -and $null -ne $optionalProperty.Value) {
        foreach ($property in $optionalProperty.Value.PSObject.Properties) {
            $optionalDependencies[$property.Name] = [string]$property.Value
        }
    }
    return [pscustomobject][ordered]@{
        path = $resolved
        group = [string]$manifest.Group
        name = [string]$manifest.Name
        version = [string]$manifest.Version
        main = [string]$manifest.Main
        pluginId = "$($manifest.Group):$($manifest.Name)"
        optionalDependencies = $optionalDependencies
    }
}

function Test-ClaimsRuntimeZipEntry {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JarPath,
        [Parameter(Mandatory = $true)]
        [string] $EntryName
    )

    $archive = [System.IO.Compression.ZipFile]::OpenRead(
        (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).Path
    )
    try {
        return $null -ne $archive.GetEntry($EntryName)
    } finally {
        $archive.Dispose()
    }
}

function Get-ClaimsRuntimeArtifactEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [switch] $ReadManifest
    )

    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if (-not $item.PSIsContainer -and $item.Length -le 0) {
        throw "Artifact is empty: '$($item.FullName)'."
    }
    $manifest = if ($ReadManifest) { Read-ClaimsRuntimeManifest -JarPath $item.FullName } else { $null }
    return [pscustomobject][ordered]@{
        path = $item.FullName
        length = if ($item.PSIsContainer) { $null } else { $item.Length }
        lastWriteTimeUtc = $item.LastWriteTimeUtc.ToString("o")
        sha256 = if ($item.PSIsContainer) { $null } else { (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash }
        manifest = $manifest
    }
}

function Resolve-ClaimsRuntimeJavap {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JavaExecutable
    )

    $java = (Resolve-Path -LiteralPath $JavaExecutable -ErrorAction Stop).Path
    $bin = Split-Path -Path $java -Parent
    foreach ($name in @("javap.exe", "javap")) {
        $candidate = Join-Path $bin $name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "The explicit Java executable has no sibling javap tool: '$java'. A full JDK is required."
}

function Invoke-ClaimsRuntimeJavap {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Javap,
        [Parameter(Mandatory = $true)]
        [string] $JarPath,
        [Parameter(Mandatory = $true)]
        [string] $ClassName
    )

    $output = & $Javap -classpath $JarPath -public $ClassName 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "javap failed for $ClassName with exit code $LASTEXITCODE`n$output"
    }
    return $output
}

function Assert-ClaimsRuntimeContractPattern {
    param(
        [string] $ClassName,
        [string] $Output,
        [string] $Pattern,
        [string] $Description,
        [System.Collections.Generic.List[string]] $Failures
    )

    if (-not [regex]::IsMatch($Output, $Pattern, [Text.RegularExpressions.RegexOptions]::CultureInvariant)) {
        $Failures.Add("$ClassName is missing $Description")
    }
}

function Invoke-SimpleClaimsRuntimeContractCheck {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JavaExecutable,
        [Parameter(Mandatory = $true)]
        [string] $JarPath
    )

    $manifest = Read-ClaimsRuntimeManifest -JarPath $JarPath
    if ($manifest.group -cne "Buuz135" -or $manifest.name -cne "SimpleClaims" -or
            $manifest.version -cne "1.0.38" -or $manifest.main -cne "com.buuz135.simpleclaims.Main") {
        throw "SimpleClaims manifest does not match the verified Buuz135:SimpleClaims 1.0.38 contract."
    }

    $javap = Resolve-ClaimsRuntimeJavap -JavaExecutable $JavaExecutable
    $contracts = [ordered]@{
        "com.buuz135.simpleclaims.claim.ClaimManager" = @(
            @{ Pattern = 'public static com\.buuz135\.simpleclaims\.claim\.ClaimManager getInstance\(\);'; Description = 'public static getInstance()' },
            @{ Pattern = 'public com\.buuz135\.simpleclaims\.claim\.chunk\.ChunkInfo getChunkRawCoords\(java\.lang\.String, int, int\);'; Description = 'getChunkRawCoords(String, int, int)' },
            @{ Pattern = 'public com\.buuz135\.simpleclaims\.claim\.party\.PartyInfo getPartyById\(java\.util\.UUID\);'; Description = 'getPartyById(UUID)' },
            @{ Pattern = 'public java\.util\.HashMap<java\.lang\.String, java\.util\.HashMap<java\.lang\.String, com\.buuz135\.simpleclaims\.claim\.chunk\.ChunkInfo>> getChunks\(\);'; Description = 'getChunks() topology map' },
            @{ Pattern = 'public boolean isAllowedToInteract\(java\.util\.UUID, java\.lang\.String, int, int, java\.util\.function\.Predicate<com\.buuz135\.simpleclaims\.claim\.party\.PartyInfo>, java\.lang\.String\);'; Description = 'native damage policy method' }
        )
        "com.buuz135.simpleclaims.claim.chunk.ChunkInfo" = @(
            @{ Pattern = 'public java\.util\.UUID getPartyOwner\(\);'; Description = 'getPartyOwner()' },
            @{ Pattern = 'public int getChunkX\(\);'; Description = 'getChunkX()' },
            @{ Pattern = 'public int getChunkZ\(\);'; Description = 'getChunkZ()' }
        )
        "com.buuz135.simpleclaims.claim.party.PartyInfo" = @(
            @{ Pattern = 'public boolean isTamedDamageEnabled\(\);'; Description = 'isTamedDamageEnabled()' }
        )
    }

    $failures = [System.Collections.Generic.List[string]]::new()
    $outputs = [ordered]@{}
    foreach ($className in $contracts.Keys) {
        $output = Invoke-ClaimsRuntimeJavap -Javap $javap -JarPath $JarPath -ClassName $className
        $outputs[$className] = $output
        foreach ($contract in $contracts[$className]) {
            Assert-ClaimsRuntimeContractPattern -ClassName $className -Output $output `
                -Pattern $contract.Pattern -Description $contract.Description -Failures $failures
        }
    }
    if ($failures.Count -gt 0) {
        throw "SimpleClaims 1.0.38 contract verification failed:`n - $($failures -join "`n - ")"
    }
    return [pscustomobject][ordered]@{
        provider = "SimpleClaims"
        version = "1.0.38"
        passed = $true
        javap = $javap
        classes = @($contracts.Keys)
        output = $outputs
    }
}

function Invoke-QuestLinesRuntimeContractCheck {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JavaExecutable,
        [Parameter(Mandatory = $true)]
        [string] $JarPath,
        [Parameter(Mandatory = $true)]
        [string] $VerifierScript
    )

    $java = (Resolve-Path -LiteralPath $JavaExecutable -ErrorAction Stop).Path
    $javaHome = Split-Path -Path (Split-Path -Path $java -Parent) -Parent
    $previousJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $javaHome
        $output = & $VerifierScript -Jar $JarPath 2>&1 | Out-String
    } finally {
        $env:JAVA_HOME = $previousJavaHome
    }
    return [pscustomobject][ordered]@{
        provider = "QuestLinesClaims"
        version = "1.3.1"
        passed = $true
        verifier = (Resolve-Path -LiteralPath $VerifierScript).Path
        output = $output.Trim()
    }
}

function New-ClaimsRuntimeSqliteProbeSource {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Destination
    )

    $source = @'
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public final class ClaimsRuntimeSqliteProbe {
    private static void emit(String key, Object value) {
        String text = value == null ? "" : String.valueOf(value);
        System.out.println(key + "\t" + text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() && result.getObject(1) != null ? String.valueOf(result.getObject(1)) : "";
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(null, null, table, new String[]{"TABLE"})) {
            return result.next();
        }
    }

    private static boolean tablesExist(Connection connection, String... tables) throws Exception {
        for (String table : tables) {
            if (!tableExists(connection, table)) return false;
        }
        return true;
    }

    private static String tableScalar(Connection connection, String table, String sql) throws Exception {
        return tableExists(connection, table) ? scalar(connection, sql) : "-1";
    }

    private static String multiTableScalar(Connection connection, String sql, String... tables) throws Exception {
        return tablesExist(connection, tables) ? scalar(connection, sql) : "-1";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one SQLite database path.");
        Path database = Path.of(args[0]).toAbsolutePath().normalize();
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:file:" + database.toUri().getRawPath() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url)) {
            emit("database_path", database);
            emit("integrity_check", scalar(connection, "PRAGMA integrity_check"));
            emit("journal_mode", scalar(connection, "PRAGMA journal_mode"));
            emit("synchronous", scalar(connection, "PRAGMA synchronous"));
            emit("migration_v6_count", tableScalar(connection, "schema_migrations",
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 6 AND name = 'schema_v6_companion_population_integrity'"));
            emit("migration_versions", tableScalar(connection, "schema_migrations",
                "SELECT group_concat(version || ':' || name, ',') FROM (SELECT version, name FROM schema_migrations ORDER BY version)"));
            emit("coverage_total", tableScalar(connection, "companion_population_reconciliation",
                "SELECT COUNT(*) FROM companion_population_reconciliation"));
            emit("coverage_ready", tableScalar(connection, "companion_population_reconciliation",
                "SELECT COUNT(*) FROM companion_population_reconciliation WHERE state = 'READY'"));
            emit("coverage_error_count", tableScalar(connection, "companion_population_reconciliation",
                "SELECT COUNT(*) FROM companion_population_reconciliation WHERE last_error IS NOT NULL AND TRIM(last_error) <> ''"));
            emit("coverage_distinct_dimensions", tableScalar(connection, "companion_population_reconciliation",
                "SELECT COUNT(DISTINCT coverage_dimension) FROM companion_population_reconciliation"));
            emit("coverage_dimensions", tableScalar(connection, "companion_population_reconciliation",
                "SELECT group_concat(coverage_dimension, ',') FROM (SELECT DISTINCT coverage_dimension FROM companion_population_reconciliation ORDER BY coverage_dimension)"));
            emit("coverage_rows", tableScalar(connection, "companion_population_reconciliation",
                "SELECT group_concat(coverage_dimension || ':' || coverage_key || ':' || state, ',') FROM (SELECT coverage_dimension, coverage_key, state FROM companion_population_reconciliation ORDER BY coverage_dimension, coverage_key)"));
            emit("per_world_owner_error", tableScalar(connection, "companion_population_reconciliation",
                "SELECT COALESCE(last_error, '') FROM companion_population_reconciliation WHERE coverage_key = 'owner-population:per-world'"));
            emit("scan_session_state", tableScalar(connection, "companion_population_scan_session",
                "SELECT state FROM companion_population_scan_session WHERE singleton_id = 1"));
            emit("nonterminal_operations", tableScalar(connection, "companion_population_operations",
                "SELECT COUNT(*) FROM companion_population_operations WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')"));
            emit("retryable_breeding_operations", tableScalar(connection, "companion_population_operations",
                "SELECT COUNT(*) FROM companion_population_operations WHERE operation_type = 'BREEDING' AND state = 'RETRYABLE'"));
            emit("retryable_operations", tableScalar(connection, "companion_population_operations",
                "SELECT COUNT(*) FROM companion_population_operations WHERE state = 'RETRYABLE'"));
            emit("canonical_rows", tableScalar(connection, "companion_population_state",
                "SELECT COUNT(*) FROM companion_population_state"));
            emit("profile_rows", tableScalar(connection, "npc_profiles", "SELECT COUNT(*) FROM npc_profiles"));
            emit("missing_canonical_rows", multiTableScalar(connection,
                "SELECT COUNT(*) FROM npc_profiles p LEFT JOIN companion_population_state s ON s.profile_id = p.profile_id WHERE s.profile_id IS NULL",
                "npc_profiles", "companion_population_state"));
            emit("orphan_canonical_rows", multiTableScalar(connection,
                "SELECT COUNT(*) FROM companion_population_state s LEFT JOIN npc_profiles p ON p.profile_id = s.profile_id WHERE p.profile_id IS NULL",
                "companion_population_state", "npc_profiles"));
            emit("owned_canonical_rows", multiTableScalar(connection,
                "SELECT COUNT(*) FROM companion_population_state s JOIN npc_profiles p ON p.profile_id = s.profile_id WHERE p.owner_uuid IS NOT NULL",
                "companion_population_state", "npc_profiles"));
            emit("physical_canonical_rows", tableScalar(connection, "companion_population_state",
                "SELECT COUNT(*) FROM companion_population_state WHERE physical_world_name IS NOT NULL"));
            emit("lifecycle_rows", tableScalar(connection, "companion_population_state",
                "SELECT group_concat(lifecycle_state || ':' || amount, ',') FROM (SELECT lifecycle_state, COUNT(*) amount FROM companion_population_state GROUP BY lifecycle_state ORDER BY lifecycle_state)"));
        }
    }
}
'@
    $parent = Split-Path -Path $Destination -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($Destination), $source, [Text.UTF8Encoding]::new($false))
    return [IO.Path]::GetFullPath($Destination)
}

function Copy-ClaimsRuntimeSqliteProbeSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $SourceDatabase,
        [Parameter(Mandatory = $true)][string] $DestinationDirectory
    )
    if (-not (Test-Path -LiteralPath $DestinationDirectory)) {
        New-Item -ItemType Directory -Path $DestinationDirectory | Out-Null
    }
    $destinationDatabase = Join-Path $DestinationDirectory "tamework.sqlite"
    foreach ($suffix in @("", "-wal", "-shm")) {
        $source = $SourceDatabase + $suffix
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination ($destinationDatabase + $suffix) -Force
        }
    }
    if (-not (Test-Path -LiteralPath $destinationDatabase -PathType Leaf)) {
        throw "SQLite probe snapshot is missing its main database file."
    }
    return $destinationDatabase
}

function Invoke-ClaimsRuntimeSqliteProbe {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $JavaExecutable,
        [Parameter(Mandatory = $true)]
        [string] $BuiltArtifact,
        [Parameter(Mandatory = $true)]
        [string] $ProbeSource,
        [Parameter(Mandatory = $true)]
        [string] $DatabasePath
    )

    $database = (Resolve-Path -LiteralPath $DatabasePath -ErrorAction Stop).Path
    $output = & $JavaExecutable --class-path $BuiltArtifact $ProbeSource $database 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "SQLite evidence probe failed for '$database' with exit code $LASTEXITCODE`n$output"
    }
    $values = [ordered]@{}
    foreach ($line in ($output -split "`r?`n")) {
        if ($line -match '^([^\t]+)\t(.*)$') {
            $values[$matches[1]] = $matches[2]
        }
    }
    $required = @(
        "database_path", "integrity_check", "journal_mode", "synchronous",
        "migration_v6_count", "migration_versions", "coverage_total", "coverage_ready", "coverage_error_count",
        "coverage_distinct_dimensions", "coverage_dimensions", "coverage_rows", "per_world_owner_error",
        "scan_session_state",
        "nonterminal_operations", "retryable_operations", "retryable_breeding_operations", "canonical_rows", "profile_rows", "missing_canonical_rows",
        "orphan_canonical_rows", "owned_canonical_rows", "physical_canonical_rows", "lifecycle_rows"
    )
    foreach ($key in $required) {
        if (-not $values.Contains($key)) {
            throw "SQLite evidence probe omitted '$key' for '$database'. Output:`n$output"
        }
    }
    return [pscustomobject][ordered]@{
        databasePath = $values.database_path
        integrityCheck = $values.integrity_check
        journalMode = $values.journal_mode
        synchronous = [long]$values.synchronous
        migrationV6Count = [long]$values.migration_v6_count
        migrationVersions = $values.migration_versions
        coverageTotal = [long]$values.coverage_total
        coverageReady = [long]$values.coverage_ready
        coverageErrorCount = [long]$values.coverage_error_count
        coverageDistinctDimensions = [long]$values.coverage_distinct_dimensions
        coverageDimensions = $values.coverage_dimensions
        coverageRows = $values.coverage_rows
        perWorldOwnerError = $values.per_world_owner_error
        scanSessionState = $values.scan_session_state
        nonterminalOperations = [long]$values.nonterminal_operations
        retryableOperations = [long]$values.retryable_operations
        retryableBreedingOperations = [long]$values.retryable_breeding_operations
        canonicalRows = [long]$values.canonical_rows
        profileRows = [long]$values.profile_rows
        missingCanonicalRows = [long]$values.missing_canonical_rows
        orphanCanonicalRows = [long]$values.orphan_canonical_rows
        ownedCanonicalRows = [long]$values.owned_canonical_rows
        physicalCanonicalRows = [long]$values.physical_canonical_rows
        lifecycleRows = $values.lifecycle_rows
    }
}

function Test-ClaimsRuntimeSqliteEvidence {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [psobject] $Evidence,
        [long] $ExpectedCanonicalRows = -1,
        [switch] $AllowGlobalScopeUnknownWorld
    )

    $checks = [System.Collections.Generic.List[object]]::new()
    function Add-Check([string]$name, [bool]$passed, [object]$actual, [object]$expected) {
        $checks.Add([pscustomobject][ordered]@{ name = $name; passed = $passed; actual = $actual; expected = $expected })
    }
    Add-Check "integrity" ($Evidence.integrityCheck -ceq "ok") $Evidence.integrityCheck "ok"
    Add-Check "journal-mode" ($Evidence.journalMode -ieq "wal") $Evidence.journalMode "wal"
    Add-Check "synchronous" ($Evidence.synchronous -eq 2) $Evidence.synchronous "2 (FULL)"
    Add-Check "schema-v6" ($Evidence.migrationV6Count -eq 1) $Evidence.migrationV6Count 1
    $fullyReady = $Evidence.coverageReady -eq $Evidence.coverageTotal `
        -and $Evidence.coverageErrorCount -eq 0 `
        -and $Evidence.scanSessionState -ceq "READY"
    $notReadyRows = @(([string]$Evidence.coverageRows -split ',') |
        Where-Object { $_ -notmatch ':READY$' })
    $unknownPerWorld = $AllowGlobalScopeUnknownWorld `
        -and $Evidence.coverageReady -eq ($Evidence.coverageTotal - 1) `
        -and $Evidence.coverageErrorCount -eq 1 `
        -and $Evidence.scanSessionState -ceq "ACTIVE" `
        -and $Evidence.perWorldOwnerError -ceq "owned-profiles-have-unknown-world" `
        -and $notReadyRows.Count -eq 1 `
        -and $notReadyRows[0] -ceq "PER_WORLD_OWNER:owner-population:per-world:RECONCILING"
    $readinessAccepted = $fullyReady -or $unknownPerWorld
    Add-Check "coverage-row-count" ($Evidence.coverageTotal -ge 7) $Evidence.coverageTotal ">= 7"
    Add-Check "coverage-ready" $readinessAccepted $Evidence.coverageReady `
        "all rows READY or exact GLOBAL-scope unknown-world sentinel"
    Add-Check "coverage-dimensions" ($Evidence.coverageDistinctDimensions -eq 7) $Evidence.coverageDistinctDimensions 7
    $expectedDimensions = "BASE_CONTAINER_BLOCKS,CUSTOM_CONTAINERS,GLOBAL_OWNER,PER_WORLD_OWNER,PLAYER_SAVES,PROFILE_STATE,WORLD_ENTITIES"
    Add-Check "coverage-dimension-set" ($Evidence.coverageDimensions -ceq $expectedDimensions) `
        $Evidence.coverageDimensions $expectedDimensions
    Add-Check "scan-session" $readinessAccepted $Evidence.scanSessionState `
        "READY or ACTIVE only for exact GLOBAL-scope unknown-world sentinel"
    Add-Check "nonterminal-operations" ($Evidence.nonterminalOperations -eq 0) $Evidence.nonterminalOperations 0
    Add-Check "retryable-operation-kind" `
        ($Evidence.retryableOperations -eq $Evidence.retryableBreedingOperations) `
        $Evidence.retryableOperations "all retryable operations are BREEDING"
    Add-Check "canonical-profile-count" ($Evidence.canonicalRows -eq $Evidence.profileRows) $Evidence.canonicalRows $Evidence.profileRows
    Add-Check "missing-canonical-rows" ($Evidence.missingCanonicalRows -eq 0) $Evidence.missingCanonicalRows 0
    Add-Check "orphan-canonical-rows" ($Evidence.orphanCanonicalRows -eq 0) $Evidence.orphanCanonicalRows 0
    if ($ExpectedCanonicalRows -ge 0) {
        Add-Check "upgrade-canonical-row-preservation" `
            ($Evidence.canonicalRows -ge $ExpectedCanonicalRows) $Evidence.canonicalRows ">= $ExpectedCanonicalRows"
    }
    return [pscustomobject][ordered]@{
        passed = -not ($checks | Where-Object { -not $_.passed })
        readinessMode = if ($fullyReady) { "all-dimensions-ready" } elseif ($unknownPerWorld) {
            "global-ready-per-world-unknown-scope"
        } else { "not-ready" }
        checks = @($checks)
    }
}

Export-ModuleMember -Function @(
    "Read-ClaimsRuntimeManifest",
    "Test-ClaimsRuntimeZipEntry",
    "Get-ClaimsRuntimeArtifactEvidence",
    "Resolve-ClaimsRuntimeJavap",
    "Invoke-SimpleClaimsRuntimeContractCheck",
    "Invoke-QuestLinesRuntimeContractCheck",
    "New-ClaimsRuntimeSqliteProbeSource",
    "Copy-ClaimsRuntimeSqliteProbeSnapshot",
    "Invoke-ClaimsRuntimeSqliteProbe",
    "Test-ClaimsRuntimeSqliteEvidence"
)

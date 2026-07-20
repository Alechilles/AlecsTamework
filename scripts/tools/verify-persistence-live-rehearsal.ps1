[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $CandidateManifest,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $CandidateArtifact,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $RehearsalManifest,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string] $OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Fail-Rehearsal {
    param([string] $Message)
    throw "Persistence live rehearsal evidence rejected: $Message"
}

function Get-RehearsalProperty {
    param([object] $Object, [string] $Name, [string] $Context)
    if ($null -eq $Object) { Fail-Rehearsal "$Context is null" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { Fail-Rehearsal "$Context.$Name is missing" }
    return $property.Value
}

function Get-RehearsalString {
    param([object] $Object, [string] $Name, [string] $Context)
    $value = [string](Get-RehearsalProperty $Object $Name $Context)
    if ([string]::IsNullOrWhiteSpace($value)) { Fail-Rehearsal "$Context.$Name is blank" }
    return $value.Trim()
}

function Get-RehearsalBoolean {
    param([object] $Object, [string] $Name, [string] $Context)
    $value = Get-RehearsalProperty $Object $Name $Context
    if ($value -isnot [bool]) { Fail-Rehearsal "$Context.$Name must be a boolean" }
    return [bool]$value
}

function Get-RehearsalNumber {
    param([object] $Object, [string] $Name, [string] $Context)
    $value = Get-RehearsalProperty $Object $Name $Context
    if ($value -isnot [ValueType] -or $value -is [bool]) {
        Fail-Rehearsal "$Context.$Name must be numeric"
    }
    return [double]$value
}

function Get-RehearsalTimestamp {
    param([object] $Object, [string] $Name, [string] $Context)
    $value = Get-RehearsalProperty $Object $Name $Context
    if ($value -is [DateTimeOffset]) { return $value.ToUniversalTime() }
    if ($value -is [DateTime]) { return ([DateTimeOffset]$value).ToUniversalTime() }
    $text = [string]$value
    if ([string]::IsNullOrWhiteSpace($text)) { Fail-Rehearsal "$Context.$Name is blank" }
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($text, [ref]$parsed)) {
        Fail-Rehearsal "$Context.$Name is not a timestamp"
    }
    return $parsed.ToUniversalTime()
}

function Assert-RehearsalSha256 {
    param([string] $Value, [string] $Context)
    if ($Value -notmatch '^[a-fA-F0-9]{64}$') { Fail-Rehearsal "$Context must be a SHA-256 value" }
    return $Value.ToLowerInvariant()
}

function Assert-RehearsalTrue {
    param([bool] $Value, [string] $Context)
    if (-not $Value) { Fail-Rehearsal "$Context must be true" }
}

function Assert-RehearsalFalse {
    param([bool] $Value, [string] $Context)
    if ($Value) { Fail-Rehearsal "$Context must be false" }
}

function Get-RehearsalArray {
    param([object] $Object, [string] $Name, [string] $Context, [int] $Minimum = 0)
    $value = Get-RehearsalProperty $Object $Name $Context
    if ($null -eq $value) { $items = @() } else { $items = @($value) }
    if ($items.Count -lt $Minimum) {
        Fail-Rehearsal "$Context.$Name must contain at least $Minimum item(s)"
    }
    return @($items)
}

function Get-CandidateSourceCommit {
    param([object] $Candidate, [string] $Name)
    $matches = @((Get-RehearsalArray $Candidate "sources" "candidate" 3) |
        Where-Object { [string]$_.name -ceq $Name })
    if ($matches.Count -ne 1) { Fail-Rehearsal "candidate source '$Name' must appear exactly once" }
    Assert-RehearsalTrue (Get-RehearsalBoolean $matches[0] "clean" "candidate.sources[$Name]") `
        "candidate.sources[$Name].clean"
    return Get-RehearsalString $matches[0] "commit" "candidate.sources[$Name]"
}

function Assert-EvidenceHashes {
    param([object] $Observation, [string] $Context)
    $hashes = Get-RehearsalArray $Observation "evidenceSha256" $Context 1
    foreach ($hash in $hashes) { [void](Assert-RehearsalSha256 ([string]$hash) "$Context.evidenceSha256") }
}

function Assert-Observation {
    param([object] $Observations, [string] $Name, [int] $MinimumAttempts)
    $observation = Get-RehearsalProperty $Observations $Name "rehearsal.observations"
    Assert-RehearsalTrue (Get-RehearsalBoolean $observation "passed" "observation.$Name") `
        "observation.$Name.passed"
    $attempts = Get-RehearsalNumber $observation "attemptCount" "observation.$Name"
    if ($attempts -lt $MinimumAttempts -or $attempts -ne [Math]::Floor($attempts)) {
        Fail-Rehearsal "observation.$Name.attemptCount must be an integer >= $MinimumAttempts"
    }
    Assert-EvidenceHashes $observation "observation.$Name"
    return [ordered]@{ name = $Name; attemptCount = [long]$attempts; passed = $true }
}

$candidatePath = (Resolve-Path -LiteralPath $CandidateManifest -ErrorAction Stop).Path
$candidateArtifactPath = (Resolve-Path -LiteralPath $CandidateArtifact -ErrorAction Stop).Path
$rehearsalPath = (Resolve-Path -LiteralPath $RehearsalManifest -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $candidateArtifactPath -PathType Leaf)) {
    Fail-Rehearsal "candidate artifact must be a file"
}
$candidate = Get-Content -LiteralPath $candidatePath -Raw | ConvertFrom-Json
$rehearsal = Get-Content -LiteralPath $rehearsalPath -Raw | ConvertFrom-Json

if ((Get-RehearsalNumber $candidate "evidenceSchemaVersion" "candidate") -ne 1) {
    Fail-Rehearsal "candidate.evidenceSchemaVersion must equal 1"
}
if ((Get-RehearsalString (Get-RehearsalProperty $candidate "releaseRehearsal" "candidate") `
        "exactRepositoryAndPackageGates" "candidate.releaseRehearsal") -cne "passed") {
    Fail-Rehearsal "candidate exact repository and package gates have not passed"
}
$candidateBackupBoundary = Get-RehearsalProperty $candidate "backupBoundary" "candidate"
Assert-RehearsalFalse `
    (Get-RehearsalBoolean $candidateBackupBoundary "wholeSaveBackupCreatedByTamework" `
        "candidate.backupBoundary") `
    "candidate.backupBoundary.wholeSaveBackupCreatedByTamework"
$candidateGeneratedAt = Get-RehearsalTimestamp $candidate "generatedAtUtc" "candidate"
if ($candidateGeneratedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5)) {
    Fail-Rehearsal "candidate.generatedAtUtc is in the future"
}
if ((Get-RehearsalNumber $rehearsal "evidenceSchemaVersion" "rehearsal") -ne 1) {
    Fail-Rehearsal "rehearsal.evidenceSchemaVersion must equal 1"
}
$candidateArtifactRecord = Get-RehearsalProperty (Get-RehearsalProperty $candidate "package" "candidate") `
    "artifact" "candidate.package"
$candidateSha = Assert-RehearsalSha256 `
    (Get-RehearsalString $candidateArtifactRecord "sha256" "candidate.package.artifact") `
    "candidate.package.artifact.sha256"
$actualCandidateSha = (Get-FileHash -LiteralPath $candidateArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualCandidateSha -cne $candidateSha) {
    Fail-Rehearsal "actual candidate artifact hash does not match the candidate manifest"
}
$reportedSha = Assert-RehearsalSha256 `
    (Get-RehearsalString $rehearsal "candidateArtifactSha256" "rehearsal") `
    "rehearsal.candidateArtifactSha256"
if ($reportedSha -cne $candidateSha) { Fail-Rehearsal "candidate artifact hash does not match" }

$sourceCommits = Get-RehearsalProperty $rehearsal "sourceCommits" "rehearsal"
$verifiedSources = [ordered]@{}
foreach ($name in @("tamework", "alecs-telemetry", "telemetry-platform")) {
    $expected = Get-CandidateSourceCommit $candidate $name
    $actual = Get-RehearsalString $sourceCommits $name "rehearsal.sourceCommits"
    if ($actual -cne $expected) { Fail-Rehearsal "source commit mismatch for $name" }
    $verifiedSources[$name] = $actual
}

$parsedCompletedAt = Get-RehearsalTimestamp $rehearsal "completedAtUtc" "rehearsal"
if ($parsedCompletedAt -lt $candidateGeneratedAt) {
    Fail-Rehearsal "rehearsal.completedAtUtc predates the frozen candidate"
}
if ($parsedCompletedAt -gt [DateTimeOffset]::UtcNow.AddMinutes(5)) {
    Fail-Rehearsal "rehearsal.completedAtUtc is in the future"
}

$requiredCategories = @("fresh", "current", "old-managed-coop", "high-population", "historical-conflict")
$seenCategories = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$seenFixtureIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$categoryFixtures = [ordered]@{}
$fixtureEvidence = @()
$startupComparisons = 0
foreach ($fixture in (Get-RehearsalArray $rehearsal "fixtures" "rehearsal" 1)) {
    $id = Get-RehearsalString $fixture "id" "rehearsal.fixture"
    if ($id -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { Fail-Rehearsal "fixture id is not opaque/safe: $id" }
    if (-not $seenFixtureIds.Add($id)) { Fail-Rehearsal "duplicate fixture id '$id'" }
    Assert-RehearsalTrue (Get-RehearsalBoolean $fixture "sourceImmutable" "fixture.$id") `
        "fixture.$id.sourceImmutable"
    Assert-RehearsalFalse (Get-RehearsalBoolean $fixture "wholeSaveBackupCreatedByTamework" "fixture.$id") `
        "fixture.$id.wholeSaveBackupCreatedByTamework"
    $categories = Get-RehearsalArray $fixture "categories" "fixture.$id" 1
    foreach ($categoryValue in $categories) {
        $category = [string]$categoryValue
        if ($requiredCategories -cnotcontains $category) { Fail-Rehearsal "fixture.$id has unknown category '$category'" }
        [void]$seenCategories.Add($category)
        if (-not $categoryFixtures.Contains($category)) { $categoryFixtures[$category] = $id }
    }
    $boots = Get-RehearsalNumber $fixture "candidateBoots" "fixture.$id"
    if ($boots -lt 2 -or $boots -ne [Math]::Floor($boots)) {
        Fail-Rehearsal "fixture.$id.candidateBoots must be an integer >= 2"
    }
    $bootHashes = Get-RehearsalArray $fixture "bootArtifactSha256" "fixture.$id" ([int]$boots)
    if ($bootHashes.Count -ne [int]$boots) { Fail-Rehearsal "fixture.$id must record one artifact hash per boot" }
    foreach ($hash in $bootHashes) {
        if ((Assert-RehearsalSha256 ([string]$hash) "fixture.$id.bootArtifactSha256") -cne $candidateSha) {
            Fail-Rehearsal "fixture.$id used a different artifact"
        }
    }
    $baselineMs = Get-RehearsalNumber $fixture "baselineServerReadyMs" "fixture.$id"
    $candidateMs = Get-RehearsalNumber $fixture "candidateServerReadyMs" "fixture.$id"
    if ($baselineMs -le 0 -or $candidateMs -le 0) { Fail-Rehearsal "fixture.$id startup timings must be positive" }
    $allowedDelta = [Math]::Max(2000.0, $baselineMs * 0.20)
    if (($candidateMs - $baselineMs) -gt $allowedDelta) {
        Fail-Rehearsal "fixture.$id startup regression exceeds max(2000 ms, 20 percent)"
    }
    $startupComparisons++
    Assert-EvidenceHashes $fixture "fixture.$id"
    $fixtureHashes = @((Get-RehearsalArray $fixture "evidenceSha256" "fixture.$id" 1) |
        ForEach-Object { ([string]$_).ToLowerInvariant() })
    $fixtureEvidence += [ordered]@{
        id = $id; categories = @($categories); candidateBoots = [long]$boots
        baselineServerReadyMs = $baselineMs; candidateServerReadyMs = $candidateMs
        evidenceSha256 = $fixtureHashes
    }
}
foreach ($category in $requiredCategories) {
    if (-not $seenCategories.Contains($category)) { Fail-Rehearsal "missing fixture category '$category'" }
}
if (@($categoryFixtures.Values | Select-Object -Unique).Count -ne $requiredCategories.Count) {
    Fail-Rehearsal "fresh/current/old-coop/high-population/historical coverage requires five distinct fixtures"
}
if ($startupComparisons -lt 1) { Fail-Rehearsal "at least one startup comparison is required" }

$observations = Get-RehearsalProperty $rehearsal "observations" "rehearsal"
$observationEvidence = @(
    Assert-Observation $observations "login-tame-and-two-spawns" 2
    Assert-Observation $observations "managed-coop-old-and-new-multi-resident" 4
    Assert-Observation $observations "manual-and-passive-breeding-repeat" 4
    Assert-Observation $observations "inventory-and-storage-capture-release" 4
    Assert-Observation $observations "cleanup-death-lost-revival" 4
    Assert-Observation $observations "same-and-cross-world-recall" 4
    Assert-Observation $observations "hold-follow-restart-no-teleport" 2
    Assert-Observation $observations "linked-panel-canonical-state-name" 2
    Assert-Observation $observations "scoped-fault-and-recovery" 2
    Assert-Observation $observations "diagnostic-export-and-telemetry-correlation" 1
)

$performance = Get-RehearsalProperty $rehearsal "performance" "rehearsal"
$profiles = Get-RehearsalNumber $performance "linkedProfiles" "rehearsal.performance"
$coops = Get-RehearsalNumber $performance "managedCoops" "rehearsal.performance"
if ($profiles -lt 1000) { Fail-Rehearsal "performance.linkedProfiles must be >= 1000" }
if ($coops -lt 100) { Fail-Rehearsal "performance.managedCoops must be >= 100" }
$baselineP95 = Get-RehearsalNumber $performance "baselineTickP95Ms" "rehearsal.performance"
$candidateP95 = Get-RehearsalNumber $performance "candidateTickP95Ms" "rehearsal.performance"
if ($baselineP95 -lt 0 -or $candidateP95 -lt 0 -or ($candidateP95 - $baselineP95) -gt 0.25) {
    Fail-Rehearsal "candidate tick p95 exceeds the 0.25 ms live budget"
}
Assert-RehearsalFalse `
    (Get-RehearsalBoolean $performance "diagnosticExportCreatedLongTick" "rehearsal.performance") `
    "performance.diagnosticExportCreatedLongTick"
Assert-RehearsalFalse `
    (Get-RehearsalBoolean $performance "telemetryUploadCreatedLongTick" "rehearsal.performance") `
    "performance.telemetryUploadCreatedLongTick"
Assert-EvidenceHashes $performance "rehearsal.performance"
$performanceHashes = @((Get-RehearsalArray $performance "evidenceSha256" "rehearsal.performance" 1) |
    ForEach-Object { ([string]$_).ToLowerInvariant() })

$findings = Get-RehearsalArray $rehearsal "classifiedFindings" "rehearsal"
$findingEvidence = @()
foreach ($finding in $findings) {
    $reason = Get-RehearsalString $finding "reasonCode" "classifiedFinding"
    if ($reason -notmatch '^[a-z0-9][a-z0-9._-]{0,127}$') { Fail-Rehearsal "classified finding reason is not safe" }
    $disposition = Get-RehearsalString $finding "disposition" "classifiedFinding.$reason"
    if ($disposition -notmatch '^[a-z0-9][a-z0-9._-]{0,127}$') { Fail-Rehearsal "classified finding disposition is not safe" }
    Assert-RehearsalFalse (Get-RehearsalBoolean $finding "releaseBlocking" "classifiedFinding.$reason") `
        "classifiedFinding.$reason.releaseBlocking"
    $hash = Assert-RehearsalSha256 `
        (Get-RehearsalString $finding "evidenceSha256" "classifiedFinding.$reason") `
        "classifiedFinding.$reason.evidenceSha256"
    $findingEvidence += [ordered]@{ reasonCode = $reason; disposition = $disposition; evidenceSha256 = $hash }
}
if (@(Get-RehearsalArray $rehearsal "unresolvedWarnings" "rehearsal").Count -ne 0) {
    Fail-Rehearsal "unresolvedWarnings must be empty"
}

$backup = Get-RehearsalProperty $rehearsal "backupBoundary" "rehearsal"
Assert-RehearsalFalse (Get-RehearsalBoolean $backup "wholeSaveBackupCreatedByTamework" "backupBoundary") `
    "backupBoundary.wholeSaveBackupCreatedByTamework"
$snapshot = Get-RehearsalProperty $backup "sqliteMigrationSnapshot" "backupBoundary"
if ((Get-RehearsalString $snapshot "scope" "backupBoundary.sqliteMigrationSnapshot") -cne "tamework_sqlite_only") {
    Fail-Rehearsal "SQLite migration snapshot scope must be tamework_sqlite_only"
}
$snapshotHash = Assert-RehearsalSha256 `
    (Get-RehearsalString $snapshot "sha256" "backupBoundary.sqliteMigrationSnapshot") `
    "backupBoundary.sqliteMigrationSnapshot.sha256"
if ((Get-RehearsalString $snapshot "integrityCheck" "backupBoundary.sqliteMigrationSnapshot") -cne "ok") {
    Fail-Rehearsal "SQLite migration snapshot integrity must be ok"
}
if ((Get-RehearsalNumber $snapshot "sourceSchema" "backupBoundary.sqliteMigrationSnapshot") -ge 7 -or
        (Get-RehearsalNumber $snapshot "targetSchema" "backupBoundary.sqliteMigrationSnapshot") -ne 7) {
    Fail-Rehearsal "SQLite migration snapshot must prove a pre-v7 to v7 transition"
}

$rollback = Get-RehearsalProperty $rehearsal "rollback" "rehearsal"
Assert-RehearsalTrue (Get-RehearsalBoolean $rollback "passed" "rollback") "rollback.passed"
$backupReference = Get-RehearsalString $rollback "operatorHytaleBackupReference" "rollback"
if ($backupReference -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$') {
    Fail-Rehearsal "rollback operator backup reference must be opaque and path-free"
}
$rollbackSqliteHash = Assert-RehearsalSha256 `
    (Get-RehearsalString $rollback "matchingPreV7SqliteSha256" "rollback") `
    "rollback.matchingPreV7SqliteSha256"
if ($rollbackSqliteHash -cne $snapshotHash) {
    Fail-Rehearsal "rollback SQLite hash must match the verified pre-v7 migration snapshot"
}
$priorJarHash = Assert-RehearsalSha256 `
    (Get-RehearsalString $rollback "priorJarSha256" "rollback") "rollback.priorJarSha256"
if ($priorJarHash -ceq $candidateSha) { Fail-Rehearsal "rollback prior JAR must differ from the candidate" }
Assert-RehearsalTrue (Get-RehearsalBoolean $rollback "restoredCopyBootPassed" "rollback") `
    "rollback.restoredCopyBootPassed"
Assert-RehearsalTrue (Get-RehearsalBoolean $rollback "schemaV7AbsentAfterRestore" "rollback") `
    "rollback.schemaV7AbsentAfterRestore"
Assert-RehearsalTrue (Get-RehearsalBoolean $rollback "postV7ProgressLossAcknowledged" "rollback") `
    "rollback.postV7ProgressLossAcknowledged"
Assert-EvidenceHashes $rollback "rollback"
$rollbackHashes = @((Get-RehearsalArray $rollback "evidenceSha256" "rollback" 1) |
    ForEach-Object { ([string]$_).ToLowerInvariant() })
Assert-RehearsalTrue (Get-RehearsalBoolean $rehearsal "operatorSignedOff" "rehearsal") `
    "rehearsal.operatorSignedOff"

$output = [ordered]@{
    evidenceSchemaVersion = 1
    verifiedAtUtc = [DateTime]::UtcNow.ToString("o")
    status = "passed"
    candidateArtifactSha256 = $candidateSha
    candidateManifestSha256 = (Get-FileHash -LiteralPath $candidatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceCommits = $verifiedSources
    rehearsalManifestSha256 = (Get-FileHash -LiteralPath $rehearsalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    fixtures = $fixtureEvidence
    fixtureCategories = @($requiredCategories)
    observations = $observationEvidence
    performance = [ordered]@{
        linkedProfiles = [long]$profiles; managedCoops = [long]$coops
        baselineTickP95Ms = $baselineP95; candidateTickP95Ms = $candidateP95
        evidenceSha256 = $performanceHashes
    }
    classifiedFindings = $findingEvidence
    rollback = [ordered]@{
        passed = $true; operatorHytaleBackupReference = $backupReference
        evidenceSha256 = $rollbackHashes
    }
    backupBoundary = [ordered]@{
        wholeSaveBackupCreatedByTamework = $false
        migrationProtection = "verified-tamework-sqlite-snapshot-only"
    }
}
$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$outputParent = Split-Path -Path $outputFullPath -Parent
if (-not (Test-Path -LiteralPath $outputParent)) {
    New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
}
[IO.File]::WriteAllText(
    $outputFullPath,
    ($output | ConvertTo-Json -Depth 12) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Persistence live rehearsal evidence passed: $outputFullPath"

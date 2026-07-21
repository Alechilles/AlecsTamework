[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-LiveTest {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Write-LiveTestJson {
    param([string] $Path, [object] $Value)
    [IO.File]::WriteAllText(
        $Path,
        ($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )
}

function Copy-LiveTestObject {
    param([object] $Value)
    return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}

function New-LiveTestObservation {
    param([int] $Attempts, [string] $Hash)
    return [ordered]@{ passed = $true; attemptCount = $Attempts; evidenceSha256 = @($Hash) }
}

function New-LiveTestFixture {
    param([string] $Id, [string] $Category, [string] $CandidateHash)
    return [ordered]@{
        id = $Id
        categories = @($Category)
        sourceImmutable = $true
        wholeSaveBackupCreatedByTamework = $false
        candidateBoots = 2
        bootArtifactSha256 = @($CandidateHash, $CandidateHash)
        baselineServerReadyMs = 10000
        candidateServerReadyMs = 11999
        evidenceSha256 = @("b" * 64)
    }
}

function Invoke-LiveTestVerifier {
    param(
        [string] $PowerShell,
        [string] $Verifier,
        [string] $CandidatePath,
        [string] $CandidateArtifactPath,
        [string] $RehearsalPath,
        [string] $OutputPath
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $text = & $PowerShell -NoLogo -NoProfile -File $Verifier `
            -CandidateManifest $CandidatePath -CandidateArtifact $CandidateArtifactPath `
            -RehearsalManifest $RehearsalPath `
            -OutputPath $OutputPath 2>&1 | Out-String
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = $text }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Assert-LiveTestRejects {
    param(
        [string] $Name,
        [object] $Rehearsal,
        [string] $Pattern,
        [string] $Root,
        [string] $PowerShell,
        [string] $Verifier,
        [string] $CandidatePath,
        [string] $CandidateArtifactPath
    )
    $inputPath = Join-Path $Root "$Name.json"
    $outputPath = Join-Path $Root "$Name-output.json"
    Write-LiveTestJson $inputPath $Rehearsal
    $result = Invoke-LiveTestVerifier $PowerShell $Verifier $CandidatePath $CandidateArtifactPath `
        $inputPath $outputPath
    Assert-LiveTest ($result.exitCode -ne 0) "$Name must be rejected"
    $normalizedText = $result.text -replace '\s+', ' '
    Assert-LiveTest ($normalizedText -match $Pattern) "$Name must report '$Pattern': $($result.text)"
    Assert-LiveTest (-not (Test-Path -LiteralPath $outputPath)) "$Name must not emit passing output"
}

function Assert-LiveTestCandidateRejects {
    param(
        [string] $Name,
        [object] $Candidate,
        [object] $Rehearsal,
        [string] $Pattern,
        [string] $Root,
        [string] $PowerShell,
        [string] $Verifier,
        [string] $CandidateArtifactPath
    )
    $candidatePath = Join-Path $Root "$Name-candidate.json"
    $inputPath = Join-Path $Root "$Name.json"
    $outputPath = Join-Path $Root "$Name-output.json"
    Write-LiveTestJson $candidatePath $Candidate
    Write-LiveTestJson $inputPath $Rehearsal
    $result = Invoke-LiveTestVerifier $PowerShell $Verifier $candidatePath $CandidateArtifactPath `
        $inputPath $outputPath
    Assert-LiveTest ($result.exitCode -ne 0) "$Name must be rejected"
    $normalizedText = $result.text -replace '\s+', ' '
    Assert-LiveTest ($normalizedText -match $Pattern) "$Name must report '$Pattern': $($result.text)"
    Assert-LiveTest (-not (Test-Path -LiteralPath $outputPath)) "$Name must not emit passing output"
}

$toolsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$verifier = Join-Path $toolsRoot "verify-persistence-live-rehearsal.ps1"
$template = Join-Path $toolsRoot "templates\persistence-live-rehearsal-template.json"
$powerShell = (Get-Process -Id $PID).Path
$testRoot = Join-Path ([IO.Path]::GetTempPath()) `
    ("tamework-live-rehearsal-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $testRoot | Out-Null

$candidateArtifactPath = Join-Path $testRoot "candidate.jar"
[IO.File]::WriteAllText($candidateArtifactPath, "exact candidate bytes", [Text.UTF8Encoding]::new($false))
$candidateHash = (Get-FileHash -LiteralPath $candidateArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
$evidenceHash = "b" * 64
$snapshotHash = "c" * 64
$priorJarHash = "d" * 64
$commits = [ordered]@{
    tamework = "1" * 40
    "alecs-telemetry" = "2" * 40
    "telemetry-platform" = "3" * 40
}
$candidate = [ordered]@{
    evidenceSchemaVersion = 1
    generatedAtUtc = [DateTimeOffset]::UtcNow.AddMinutes(-1).ToString("o")
    sources = @(
        [ordered]@{ name = "tamework"; commit = $commits.tamework; clean = $true },
        [ordered]@{ name = "alecs-telemetry"; commit = $commits["alecs-telemetry"]; clean = $true },
        [ordered]@{ name = "telemetry-platform"; commit = $commits["telemetry-platform"]; clean = $true }
    )
    package = [ordered]@{ artifact = [ordered]@{ sha256 = $candidateHash } }
    backupBoundary = [ordered]@{ wholeSaveBackupCreatedByTamework = $false }
    releaseRehearsal = [ordered]@{ exactRepositoryAndPackageGates = "passed" }
}
$rehearsal = [ordered]@{
    evidenceSchemaVersion = 2
    completedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    candidateArtifactSha256 = $candidateHash
    sourceCommits = $commits
    fixtures = @(
        New-LiveTestFixture "fixture-fresh" "fresh" $candidateHash
        New-LiveTestFixture "fixture-current" "current" $candidateHash
        New-LiveTestFixture "fixture-coop" "old-managed-coop" $candidateHash
        New-LiveTestFixture "fixture-scale" "high-population" $candidateHash
        New-LiveTestFixture "fixture-history" "historical-conflict" $candidateHash
    )
    observations = [ordered]@{
        "login-tame-and-two-spawns" = New-LiveTestObservation 2 $evidenceHash
        "managed-coop-old-and-new-multi-resident" = New-LiveTestObservation 4 $evidenceHash
        "manual-and-passive-breeding-repeat" = New-LiveTestObservation 4 $evidenceHash
        "inventory-and-storage-capture-release" = New-LiveTestObservation 4 $evidenceHash
        "cleanup-death-lost-revival" = New-LiveTestObservation 4 $evidenceHash
        "same-and-cross-world-recall" = New-LiveTestObservation 4 $evidenceHash
        "hold-follow-restart-no-teleport" = New-LiveTestObservation 2 $evidenceHash
        "linked-panel-canonical-state-name" = New-LiveTestObservation 2 $evidenceHash
        "scoped-fault-and-recovery" = New-LiveTestObservation 2 $evidenceHash
        "diagnostic-export-and-telemetry-correlation" = New-LiveTestObservation 1 $evidenceHash
    }
    performance = [ordered]@{
        linkedProfiles = 1000
        managedCoops = 100
        baselineTickP95Ms = 4.0
        candidateTickP95Ms = 4.25
        diagnosticExportCreatedLongTick = $false
        telemetryUploadCreatedLongTick = $false
        evidenceSha256 = @($evidenceHash)
    }
    classifiedFindings = @(
        [ordered]@{
            reasonCode = "owned-profiles-have-unknown-world"
            disposition = "bounded-per-world-readiness"
            releaseBlocking = $false
            evidenceSha256 = $evidenceHash
        }
    )
    unresolvedWarnings = @()
    backupBoundary = [ordered]@{
        wholeSaveBackupCreatedByTamework = $false
        sqliteMigrationSnapshot = [ordered]@{
            scope = "tamework_sqlite_only"
            sha256 = $snapshotHash
            integrityCheck = "ok"
            sourceSchema = 7
            targetSchema = 8
        }
    }
    rollback = [ordered]@{
        passed = $true
        operatorHytaleBackupReference = "hytale-backup:rehearsal-42"
        matchingPreV8SqliteSha256 = $snapshotHash
        priorJarSha256 = $priorJarHash
        restoredCopyBootPassed = $true
        schemaV8AbsentAfterRestore = $true
        postV8ProgressLossAcknowledged = $true
        evidenceSha256 = @($evidenceHash)
    }
    operatorSignedOff = $true
}

try {
    $candidatePath = Join-Path $testRoot "candidate.json"
    $validPath = Join-Path $testRoot "valid.json"
    $validOutput = Join-Path $testRoot "valid-output.json"
    Write-LiveTestJson $candidatePath $candidate
    Write-LiveTestJson $validPath $rehearsal
    $valid = Invoke-LiveTestVerifier $powerShell $verifier $candidatePath $candidateArtifactPath `
        $validPath $validOutput
    Assert-LiveTest ($valid.exitCode -eq 0) "valid rehearsal must pass: $($valid.text)"
    $verified = Get-Content -LiteralPath $validOutput -Raw | ConvertFrom-Json
    Assert-LiveTest ($verified.status -ceq "passed") "verified output is passed"
    Assert-LiveTest ($verified.candidateArtifactSha256 -ceq $candidateHash) "candidate hash is retained"
    Assert-LiveTest ($verified.candidateManifestSha256 -match '^[a-f0-9]{64}$') `
        "candidate manifest hash is retained"
    Assert-LiveTest (-not $verified.backupBoundary.wholeSaveBackupCreatedByTamework) `
        "verified output preserves the no-whole-save-backup boundary"
    Assert-LiveTest ($verified.PSObject.Properties["rehearsalPath"] -eq $null) `
        "verified output does not export a save/evidence path"

    $mismatch = Copy-LiveTestObject $rehearsal
    $mismatch.candidateArtifactSha256 = "e" * 64
    Assert-LiveTestRejects "artifact-mismatch" $mismatch "artifact hash does not match" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $substitutedArtifactPath = Join-Path $testRoot "substituted.jar"
    [IO.File]::WriteAllText($substitutedArtifactPath, "different bytes", [Text.UTF8Encoding]::new($false))
    Assert-LiveTestRejects "actual-artifact-mismatch" $rehearsal `
        "actual candidate artifact hash does not match" `
        $testRoot $powerShell $verifier $candidatePath $substitutedArtifactPath

    $ungatedCandidate = Copy-LiveTestObject $candidate
    $ungatedCandidate.releaseRehearsal.exactRepositoryAndPackageGates = "failed"
    Assert-LiveTestCandidateRejects "ungated-candidate" $ungatedCandidate $rehearsal `
        "exact repository and package gates have not passed" `
        $testRoot $powerShell $verifier $candidateArtifactPath

    $unsafeCandidate = Copy-LiveTestObject $candidate
    $unsafeCandidate.backupBoundary.wholeSaveBackupCreatedByTamework = $true
    Assert-LiveTestCandidateRejects "candidate-whole-save-backup" $unsafeCandidate $rehearsal `
        "candidate.backupBoundary.wholeSaveBackupCreatedByTamework" `
        $testRoot $powerShell $verifier $candidateArtifactPath

    $futureCandidate = Copy-LiveTestObject $candidate
    $futureCandidate.generatedAtUtc = [DateTimeOffset]::UtcNow.AddMinutes(10).ToString("o")
    Assert-LiveTestCandidateRejects "future-candidate" $futureCandidate $rehearsal `
        "candidate.generatedAtUtc is in the future" `
        $testRoot $powerShell $verifier $candidateArtifactPath

    $sameFixture = Copy-LiveTestObject $rehearsal
    foreach ($fixture in $sameFixture.fixtures) { $fixture.id = "fixture-shared" }
    Assert-LiveTestRejects "shared-fixture" $sameFixture "duplicate fixture id" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $wrongBoot = Copy-LiveTestObject $rehearsal
    $wrongBoot.fixtures[0].bootArtifactSha256[1] = "e" * 64
    Assert-LiveTestRejects "wrong-boot-artifact" $wrongBoot "used a different artifact" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $slowStartup = Copy-LiveTestObject $rehearsal
    $slowStartup.fixtures[0].candidateServerReadyMs = 12001
    Assert-LiveTestRejects "startup-budget" $slowStartup "startup regression exceeds" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $shortObservation = Copy-LiveTestObject $rehearsal
    $shortObservation.observations.PSObject.Properties["manual-and-passive-breeding-repeat"].Value.attemptCount = 3
    Assert-LiveTestRejects "short-observation" $shortObservation "attemptCount must" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $slowTick = Copy-LiveTestObject $rehearsal
    $slowTick.performance.candidateTickP95Ms = 4.251
    Assert-LiveTestRejects "tick-budget" $slowTick "tick p95 exceeds" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $unresolved = Copy-LiveTestObject $rehearsal
    $unresolved.unresolvedWarnings = @("something-unclassified")
    Assert-LiveTestRejects "unresolved-warning" $unresolved "unresolvedWarnings must be empty" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $unsafeBackup = Copy-LiveTestObject $rehearsal
    $unsafeBackup.backupBoundary.wholeSaveBackupCreatedByTamework = $true
    Assert-LiveTestRejects "whole-save-backup" $unsafeBackup "wholeSaveBackupCreatedByTamework must be false" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $wrongRollbackState = Copy-LiveTestObject $rehearsal
    $wrongRollbackState.rollback.matchingPreV8SqliteSha256 = "e" * 64
    Assert-LiveTestRejects "rollback-sqlite" $wrongRollbackState "must match the verified pre-v8" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $unsigned = Copy-LiveTestObject $rehearsal
    $unsigned.operatorSignedOff = $false
    Assert-LiveTestRejects "unsigned" $unsigned "operatorSignedOff must be true" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $predating = Copy-LiveTestObject $rehearsal
    $predating.completedAtUtc = [DateTimeOffset]::UtcNow.AddMinutes(-2).ToString("o")
    Assert-LiveTestRejects "predating-candidate" $predating "predates the frozen candidate" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $future = Copy-LiveTestObject $rehearsal
    $future.completedAtUtc = [DateTimeOffset]::UtcNow.AddMinutes(10).ToString("o")
    Assert-LiveTestRejects "future-completion" $future "completedAtUtc is in the future" `
        $testRoot $powerShell $verifier $candidatePath $candidateArtifactPath

    $templateResult = Invoke-LiveTestVerifier $powerShell $verifier $candidatePath $candidateArtifactPath $template `
        (Join-Path $testRoot "template-output.json")
    Assert-LiveTest ($templateResult.exitCode -ne 0) "unfilled template must fail closed"
    Assert-LiveTest ($templateResult.text -match "completedAtUtc|candidateArtifactSha256") `
        "template failure identifies an unfilled required field"

    Write-Host "Persistence live rehearsal verifier self-test passed."
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

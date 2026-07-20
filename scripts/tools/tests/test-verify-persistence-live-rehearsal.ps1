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
        [string] $RehearsalPath,
        [string] $OutputPath
    )
    $text = & $PowerShell -NoLogo -NoProfile -File $Verifier `
        -CandidateManifest $CandidatePath -RehearsalManifest $RehearsalPath `
        -OutputPath $OutputPath 2>&1 | Out-String
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = $text }
}

function Assert-LiveTestRejects {
    param(
        [string] $Name,
        [object] $Rehearsal,
        [string] $Pattern,
        [string] $Root,
        [string] $PowerShell,
        [string] $Verifier,
        [string] $CandidatePath
    )
    $inputPath = Join-Path $Root "$Name.json"
    $outputPath = Join-Path $Root "$Name-output.json"
    Write-LiveTestJson $inputPath $Rehearsal
    $result = Invoke-LiveTestVerifier $PowerShell $Verifier $CandidatePath $inputPath $outputPath
    Assert-LiveTest ($result.exitCode -ne 0) "$Name must be rejected"
    Assert-LiveTest ($result.text -match $Pattern) "$Name must report '$Pattern': $($result.text)"
    Assert-LiveTest (-not (Test-Path -LiteralPath $outputPath)) "$Name must not emit passing output"
}

$toolsRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$verifier = Join-Path $toolsRoot "verify-persistence-live-rehearsal.ps1"
$template = Join-Path $toolsRoot "templates\persistence-live-rehearsal-template.json"
$powerShell = (Get-Process -Id $PID).Path
$testRoot = Join-Path ([IO.Path]::GetTempPath()) `
    ("tamework-live-rehearsal-tests-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $testRoot | Out-Null

$candidateHash = "a" * 64
$evidenceHash = "b" * 64
$snapshotHash = "c" * 64
$priorJarHash = "d" * 64
$commits = [ordered]@{
    tamework = "1" * 40
    "alecs-telemetry" = "2" * 40
    "telemetry-platform" = "3" * 40
}
$candidate = [ordered]@{
    sources = @(
        [ordered]@{ name = "tamework"; commit = $commits.tamework; clean = $true },
        [ordered]@{ name = "alecs-telemetry"; commit = $commits["alecs-telemetry"]; clean = $true },
        [ordered]@{ name = "telemetry-platform"; commit = $commits["telemetry-platform"]; clean = $true }
    )
    package = [ordered]@{ artifact = [ordered]@{ sha256 = $candidateHash } }
}
$rehearsal = [ordered]@{
    evidenceSchemaVersion = 1
    completedAtUtc = "2026-07-20T12:00:00Z"
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
            sourceSchema = 6
            targetSchema = 7
        }
    }
    rollback = [ordered]@{
        passed = $true
        operatorHytaleBackupReference = "hytale-backup:rehearsal-42"
        matchingPreV7SqliteSha256 = $snapshotHash
        priorJarSha256 = $priorJarHash
        restoredCopyBootPassed = $true
        schemaV7AbsentAfterRestore = $true
        postV7ProgressLossAcknowledged = $true
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
    $valid = Invoke-LiveTestVerifier $powerShell $verifier $candidatePath $validPath $validOutput
    Assert-LiveTest ($valid.exitCode -eq 0) "valid rehearsal must pass: $($valid.text)"
    $verified = Get-Content -LiteralPath $validOutput -Raw | ConvertFrom-Json
    Assert-LiveTest ($verified.status -ceq "passed") "verified output is passed"
    Assert-LiveTest ($verified.candidateArtifactSha256 -ceq $candidateHash) "candidate hash is retained"
    Assert-LiveTest (-not $verified.backupBoundary.wholeSaveBackupCreatedByTamework) `
        "verified output preserves the no-whole-save-backup boundary"
    Assert-LiveTest ($verified.PSObject.Properties["rehearsalPath"] -eq $null) `
        "verified output does not export a save/evidence path"

    $mismatch = Copy-LiveTestObject $rehearsal
    $mismatch.candidateArtifactSha256 = "e" * 64
    Assert-LiveTestRejects "artifact-mismatch" $mismatch "artifact hash does not match" `
        $testRoot $powerShell $verifier $candidatePath

    $sameFixture = Copy-LiveTestObject $rehearsal
    foreach ($fixture in $sameFixture.fixtures) { $fixture.id = "fixture-shared" }
    Assert-LiveTestRejects "shared-fixture" $sameFixture "duplicate fixture id" `
        $testRoot $powerShell $verifier $candidatePath

    $wrongBoot = Copy-LiveTestObject $rehearsal
    $wrongBoot.fixtures[0].bootArtifactSha256[1] = "e" * 64
    Assert-LiveTestRejects "wrong-boot-artifact" $wrongBoot "used a different artifact" `
        $testRoot $powerShell $verifier $candidatePath

    $slowStartup = Copy-LiveTestObject $rehearsal
    $slowStartup.fixtures[0].candidateServerReadyMs = 12001
    Assert-LiveTestRejects "startup-budget" $slowStartup "startup regression exceeds" `
        $testRoot $powerShell $verifier $candidatePath

    $shortObservation = Copy-LiveTestObject $rehearsal
    $shortObservation.observations.PSObject.Properties["manual-and-passive-breeding-repeat"].Value.attemptCount = 3
    Assert-LiveTestRejects "short-observation" $shortObservation "attemptCount must" `
        $testRoot $powerShell $verifier $candidatePath

    $slowTick = Copy-LiveTestObject $rehearsal
    $slowTick.performance.candidateTickP95Ms = 4.251
    Assert-LiveTestRejects "tick-budget" $slowTick "tick p95 exceeds" `
        $testRoot $powerShell $verifier $candidatePath

    $unresolved = Copy-LiveTestObject $rehearsal
    $unresolved.unresolvedWarnings = @("something-unclassified")
    Assert-LiveTestRejects "unresolved-warning" $unresolved "unresolvedWarnings must be empty" `
        $testRoot $powerShell $verifier $candidatePath

    $unsafeBackup = Copy-LiveTestObject $rehearsal
    $unsafeBackup.backupBoundary.wholeSaveBackupCreatedByTamework = $true
    Assert-LiveTestRejects "whole-save-backup" $unsafeBackup "wholeSaveBackupCreatedByTamework must be false" `
        $testRoot $powerShell $verifier $candidatePath

    $wrongRollbackState = Copy-LiveTestObject $rehearsal
    $wrongRollbackState.rollback.matchingPreV7SqliteSha256 = "e" * 64
    Assert-LiveTestRejects "rollback-sqlite" $wrongRollbackState "must match the verified pre-v7" `
        $testRoot $powerShell $verifier $candidatePath

    $unsigned = Copy-LiveTestObject $rehearsal
    $unsigned.operatorSignedOff = $false
    Assert-LiveTestRejects "unsigned" $unsigned "operatorSignedOff must be true" `
        $testRoot $powerShell $verifier $candidatePath

    $templateResult = Invoke-LiveTestVerifier $powerShell $verifier $candidatePath $template `
        (Join-Path $testRoot "template-output.json")
    Assert-LiveTest ($templateResult.exitCode -ne 0) "unfilled template must fail closed"
    Assert-LiveTest ($templateResult.text -match "completedAtUtc|candidateArtifactSha256") `
        "template failure identifies an unfilled required field"

    Write-Host "Persistence live rehearsal verifier self-test passed."
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

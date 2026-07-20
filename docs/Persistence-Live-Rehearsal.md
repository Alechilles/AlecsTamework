# Persistence Live Rehearsal Evidence

The schema-v7 single cutover has two different evidence stages:

1. `verify-persistence-release-candidate.ps1` proves source, automated, package, privacy, and isolated-runtime prerequisites for one exact candidate.
2. `verify-persistence-live-rehearsal.ps1` validates the operator-observed client, copied-world, performance, and rollback gates that automation cannot honestly infer.

The second verifier does not operate Hytale, install a JAR, create a backup, copy a save, or decide that an observation passed. It independently hashes the supplied candidate JAR, validates a deliberately completed manifest, binds it to the frozen source/package manifest and candidate lifetime, and emits a privacy-safe summary. An unfilled template fails closed.

## Files

- Verifier: `scripts/tools/verify-persistence-live-rehearsal.ps1`
- Template: `scripts/tools/templates/persistence-live-rehearsal-template.json`
- Contract self-test: `scripts/tools/tests/test-verify-persistence-live-rehearsal.ps1`

## Required fixtures

The manifest must identify five distinct disposable fixtures. Only opaque fixture IDs are recorded:

- fresh world;
- current world;
- world with pre-existing managed coops;
- high-population world;
- historical-conflict world.

Every fixture must retain an immutable source, have a unique opaque ID, boot the exact candidate at least twice, record the candidate hash for every boot, attach SHA-256 evidence references, and compare server-ready time with the same fixture's baseline. The allowed startup regression is 2,000 ms or 20 percent of baseline, whichever is greater.

The verifier records no save path, world name, player identity, NPC identity, or coordinate. It requires only opaque IDs, counts, timings, and SHA-256 evidence references.

## Required live observations

Every observation must be marked passed, meet its minimum attempt count, and include at least one SHA-256 evidence reference:

| Observation | Minimum attempts |
| --- | ---: |
| Tame and run two consecutive tamed spawns immediately after login | 2 |
| Intake/outtake multiple residents through old and new managed coops | 4 |
| Repeat manual and passive breeding | 4 |
| Capture/release from inventory and storage | 4 |
| Cleanup, real death, lost recovery, and revival | 4 |
| Same-world and cross-world recall | 4 |
| Hold/follow restart without unsolicited teleport | 2 |
| Linked-panel canonical status and name fallback | 2 |
| One scoped fault and verified recovery | 2 |
| Diagnostic export and telemetry correlation | 1 |

Attempt counts represent completed observed units, not clicks or retries. A failed attempt is not converted to passed by repeating it until one succeeds. Classify and fix or explicitly retain every warning before proceeding.

## Performance evidence

The high-population fixture must contain at least 1,000 linked profiles and 100 managed coops. Candidate world-tick p95 may add no more than 0.25 ms over the same fixture's baseline. Diagnostic export and telemetry upload must not create a tick beyond the server's existing long-tick threshold. The performance and rollback sections each require their own SHA-256 evidence references; the verified summary retains those hashes without exporting their raw source files.

## Findings

`unresolvedWarnings` must be empty. A known non-blocking condition belongs in `classifiedFindings` with:

- a stable reason code;
- a stable disposition;
- `releaseBlocking: false` only after review;
- a SHA-256 reference to its evidence.

This prevents an unexplained warning from disappearing into prose. The verifier intentionally rejects free-form paths and identities in this section.

## Backup and rollback boundary

The manifest must prove all of the following:

- Tamework did not create a whole-save backup.
- The pre-v7 migration artifact is a verified `tamework_sqlite_only` snapshot.
- The snapshot opens with SQLite integrity `ok`.
- The rollback used an operator-selected Hytale/host backup reference.
- The restored Tamework SQLite hash is the same verified pre-v7 snapshot hash.
- The restored copy boots the exact prior JAR without schema v7.
- The operator acknowledges that post-v7 progress is intentionally absent after rollback.

The verifier never creates or validates the contents of a Hytale backup. It verifies only the operator's bounded evidence contract. Do not put a save path, archive, database, player name, or raw UUID into the manifest.

## Verification command

Copy the template into a private rehearsal workspace, replace every placeholder from observed evidence, then run:

```powershell
.\scripts\tools\verify-persistence-live-rehearsal.ps1 `
  -CandidateManifest ".\target\persistence-release-evidence\candidate.json" `
  -CandidateArtifact ".\target\Alec's Tamework! v2.17.0.jar" `
  -RehearsalManifest "C:\rehearsal\persistence-live-rehearsal.json" `
  -OutputPath "C:\rehearsal\persistence-live-rehearsal-verified.json"
```

Only the verified output and appropriately redacted build/test evidence belong in the release-evidence package. Keep raw saves, databases, migration snapshots, screenshots containing identities, and unredacted logs outside it.

The self-test is:

```powershell
.\scripts\tools\tests\test-verify-persistence-live-rehearsal.ps1
```

It proves the happy path and rejects manifest or actual-JAR drift, pre-candidate or future-dated observations, fixture reuse, mixed-JAR boots, startup/tick regressions, insufficient domain repetitions, unresolved warnings, Tamework whole-save backup claims, mismatched rollback SQLite state, missing sign-off, and the unfilled template.

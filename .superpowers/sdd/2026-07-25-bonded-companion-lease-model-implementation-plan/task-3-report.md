# Task 3 Report: Bonded Lifecycle and Full-Snapshot Boundary

## Status

Implemented the isolated bonded lifecycle domain, current-roster policy
resolution, profile-owned full-snapshot wrapper/codec, offline presentation
mapping, and the static generic-persistence boundary. No projection, world
mutation, capture orchestration, UI composition, runtime composition, or
HyDragon code was added.

## Delivered Domain

- Kept the exact `STORED`, `ACTIVE`, and `DEAD` state vocabulary.
- Added immutable bonded profile, lease, and policy contracts.
- Added a registry-backed resolver that snapshots the current accepted roster
  generation at each candidate mutation and fences the expected policy
  revision.
- Added explicit transition outcomes for capture/provision to stored,
  stored-to-active summon, active-to-stored store, confirmed active death, and
  exact-price dead-to-stored revival.
- Rejected every other state transition with `INVALID_STATE`; policy, owner,
  revision, role, owned capacity, active capacity, cooldown, feature toggle,
  and revive-price failures have distinct result codes.
- Preserved signed Hytale timestamps. Session and cooldown calculations use
  ordering and exact arithmetic; a zero session produces an unlimited lease
  and a zero cooldown remains unset.
- Added a same-operation replay fast path without creating a generic operation,
  roster, lifecycle, profile, population, or outbox dependency. The bonded
  SQLite operation table remains the durable idempotency authority when the
  later runtime composition commits these pure transition results.

## Snapshot and Presentation Boundary

- Added a versioned bonded envelope around the existing complete resident-state
  codec. Its JavaDoc makes the stable profile authoritative and the source/live
  NPC UUID informational because projections are disposable.
- Preserved name, owner/tame and command data, health, happiness, needs,
  breeding, progression, traits, talents, life stage/gender, attachments, and
  exact namespaced JSON extension payload strings.
- Added a source-neutral reader entry point and a focused full-state merge
  operation without changing existing coop capture/restore behavior.
- A later partial store retains prior optional components and extension
  namespaces that were not observable in the new capture. Present newer values
  still replace their matching prior values.
- Added a role-resolver-driven presentation mapper for display name, species,
  gender, level, health, happiness, and configured presentation data. It reads
  only the durable snapshot and role configuration and does not require a live
  NPC.

## TDD Evidence

- The initial required tests were written before production classes. The first
  focused run failed during test compilation on the absent bonded profile,
  policy, transition, snapshot, codec, and mapper types.
- The first green run passed 16 tests after the minimum implementation.
- The new source-neutral reader entry point was separately tested red-first:
  `TameworkFullStateSnapshotReaderTest` failed to compile on the absent method,
  then passed after the boundary was added.

## Verification

- Required focused command:
  `./mvnw test -Dtest=BondedCompanionStateMachineTest,BondedCompanionSnapshotCodecTest,BondedCompanionPersistenceBoundaryTest`
  - PASS: 16 tests, 0 failures, 0 errors, 0 skipped.
- Touched snapshot regressions plus Task 3:
  `./mvnw test -Dtest=BondedCompanionStateMachineTest,BondedCompanionSnapshotCodecTest,BondedCompanionPersistenceBoundaryTest,TameworkFullStateSnapshotReaderTest,CoopResidentStateSnapshotCodecTest`
  - PASS: 26 tests, 0 failures, 0 errors, 0 skipped.
- Task 3 plus replacement/thread architecture guards:
  `./mvnw test -Dtest=BondedCompanionStateMachineTest,BondedCompanionSnapshotCodecTest,BondedCompanionPersistenceBoundaryTest,ReplacementPersistenceArchitectureGuardTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest`
  - PASS: 34 tests, 0 failures, 0 errors, 0 skipped.
- Full `./mvnw test`:
  - Completed: 3,078 tests, 4 failures, 0 errors, 1 skipped.
  - The failures are exactly the four pre-existing deterministic source-text
    failures documented in Tasks 1-2 against untouched files:
    - `HytaleCaptureTameLiveBoundaryArchitectureTest.targetMutationDetachesSpawnAuthorityAndRequestsDetachedRoleChange`
    - `CaptureChannelVfxSystemTest.channelSupportsLegacyParticlesAndHomingMoteCadence`
    - `CommandNpcRelocationServiceTest.importRecoveryRunsOnlyForCleanExplicitRecallExhaustion`
    - `SpawnerFeatureHandlerTest.captureRejectsStackedSpawnerItemsBeforeMetadataWrite`
- Required player/thread-affinity grep: PASS, no matches.
- Forbidden bonded imports of generic command, timed, lifecycle, profile,
  population, operation, projection, or outbox APIs: PASS, no matches.
- `git diff --check`: PASS.
- New bonded production classes are all below 500 lines; the largest is the
  447-line transition service.
- No task-created Maven or Surefire process remains. The existing Hytale server
  and IDE processes were left untouched.
- `scripts/tools/check-agent-docs.ps1`: attempted, but the isolated worktree
  does not materialize the repository-root `AGENTS.md` required by the script.

## Concerns

- Repository-wide verification remains non-green only because of the same four
  unrelated baseline failures listed above.
- This task deliberately stops at pure lifecycle/snapshot decisions. Later
  tasks must atomically translate accepted results into the bonded store and
  world projection flows; they must continue to rely on the bonded SQLite
  operation table for durable replay across intervening operations/restarts.

## Review Fix Round

All four Task 3 review findings were addressed in a separate follow-up change.
This section supersedes the earlier description of the single-operation replay
fast path.

- Creation and store now reject snapshots whose embedded owner or role does
  not match the canonical request/profile. The embedded role is also checked
  against the current policy allowlist before a store merge can occur.
- Finite session or cooldown arithmetic that lands exactly on absolute `0` is
  rejected with `VALIDATION_FAILED`. This preserves `0` exclusively as the
  unlimited/unset sentinel while retaining valid negative Hytale timestamps.
- Each accepted operation now records an immutable receipt containing its
  action, owner, expected profile and policy revisions, request time, canonical
  profile identity, and a SHA-256 identity of stable payload fields. Receipt
  history follows the immutable profile across later mutations, so an exact
  stale retry returns the current profile without rolling state back. Reusing
  an operation ID for a different action, payload, or revision returns
  `IDEMPOTENCY_CONFLICT`; ownership is checked before replay classification.
- Snapshot extension keys are sorted during encoding so semantically identical
  extension maps produce stable operation payload identities.
- The static boundary guard now also rejects generic command, provisioning,
  profile, population, revival, command-item, and spawner API imports, including
  `CommandTimedSummoningApi`, `NpcProfilesApi`, `ProfileDataApi`, and
  `PopulationGroupApi`.
- Transition validation and receipt construction were extracted into focused
  collaborators. Every bonded production class remains below 500 lines; the
  largest is `BondedCompanionTransitionService` at 494 lines.

### Review TDD and Verification

- RED: after introducing only the new result-code symbols, the focused run
  completed 16 tests with 6 expected failures. They covered create/store owner
  and role mismatch, finite lease/cooldown collisions at
  `now=-5000,duration=5s`, cross-action/payload/revision operation-ID conflicts,
  and exact replay after an intervening mutation.
- Focused lifecycle/snapshot/boundary tests: PASS, 22 tests.
- Combined focused, touched snapshot seams, persistence architecture, ECS write,
  and async thread-safety guards: PASS, 50 tests.
- Full `./mvnw test`: completed 3,084 tests with 4 failures, 0 errors, and
  1 skipped. The failures are the same four pre-existing source-text failures
  listed in the original verification section; no new full-suite failures were
  introduced.
- Required player/thread-affinity grep: PASS, no matches.
- Expanded forbidden bonded-import grep and architecture test: PASS, no matches.
- `git diff --check`: PASS.
- `scripts/tools/check-agent-docs.ps1`: attempted from Git Bash via
  `powershell.exe`; it cannot run in this linked worktree because the repository
  root `AGENTS.md` is not materialized there.
- No task-created Maven or Surefire process remained after verification.

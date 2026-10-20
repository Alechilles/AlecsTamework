# Persistence Verification Matrix

Name the production regression before adding a test. A useful test exercises
production behavior and observes a result: a record, a file on disk, a fence
decision, a refusal, an event, or player-visible output. Extend the existing
test class for the area before creating a new one.

## Select Evidence by Change

Test classes are under `src/test/java/com/alechilles/alecstamework/`.

| Change | Minimum focused evidence | Existing tests |
| --- | --- | --- |
| Index rules: revision check, generation order, lookups, tombstones, after-unlock events | Unit test on `CompanionIndex` | `companion/index/CompanionIndexTest`, `CompanionLocationTest` |
| Record or snapshot file format | Round trip plus an old document without the new field | `companion/store/CompanionRecordBsonTest` |
| Store layout, load, unreadable files, writer ordering, `flushNow`, backoff, shutdown flush | Temp-directory test of the files and futures | `companion/store/CompanionStoreTest`, `CompanionStorageTest`, `CompanionWriterTest` |
| Fence decision | One case per rule | `companion/live/CompanionFenceTest`, `LoadedBodiesTest` |
| Change detector, snapshot document, respawn strip list, UI summary | Unit test of the tracker or document | `companion/live/CompanionChangeTrackerTest`, `CompanionSnapshotsTest`, `CompanionRespawnTest`, `CompanionSummariesTest` |
| A flow: record change on success, refusal, failed write (revert) and a newer change winning | Flow test with fakes for the flush and the spawner; simulate a crash window by skipping the after-commit step | `companion/flow/CaptureFlowTest`, `StoreFlowTest`, `RestoreFlowTest`, `RestoreRulesTest`, `ReleaseFlowTest`, `RosterSummonsTest`, `CompanionRegistrationTest`, `CompanionTransitionsTest`, `SnapshotPatchTest`, `SummonExpirySchedulerTest` |
| Capture items: identity, ownership mode, Forget, Recall, pickup cache | Decision or flow test | `companion/item/CaptureItemFlowsTest`, `CaptureItemOwnershipTest`, `AdmissionCacheTest` |
| Coop slots, intake, release, imported residents | Flow or slot test | `companion/coop/CoopIntakeFlowTest`, `CoopReleaseTest`, `CoopSlotsTest`, `CoopImportedResidentsTest` |
| Caps and admission providers | Refusal and claim outcomes, including a provider timeout | `companion/admission/CompanionAdmissionTest`, `CompanionAdmissionGateTest`, `ProviderAdmissionTest`, `ProviderDecisionCacheTest` |
| Bonded companions | Result codes and record changes through the API | `companion/bonded/IndexBondedCompanionApiTest`, `BondedRecordsTest`, `BondedAdmissionTest` |
| Public API over the index | Consumer-visible result | `api/internal/IndexNpcProfilesApiTest`, `IndexProfileDataApiTest`, `IndexPopulationGroupApiTest`, `IndexDiagnosticsApiTest`, `IndexTameworkApiTest` |
| Importer and legacy handling | SQL fixture per affected schema through reader, mapper and import run; old body, item and escrow decisions | `companion/migrate/CompanionImporterTest`, `LegacyReaderTest`, `LegacyMapperTest`, `LegacySourceTest`, `LegacyBodyResolutionTest`, `LegacyItemAdoptionTest`, `LegacyBodyLocateTest`, `EscrowRefundTest` |
| Module open, migration-required mode, start-fresh | Temp-directory test of the module state | `companion/runtime/CompanionPersistenceModuleTest` |
| Settings or data-path store | Filesystem test of the caller-visible result | `settings/TameworkSettingsStoreTest`, `TameworkDataPathServiceTest` |
| Runtime system, ECS write or world-thread hand-off | Focused behavior test plus the guards below | `architecture/EcsWriteSafetyGuardTest`, `AsyncThreadSafetyGuardTest` |

Engine behavior (what a chunk saves, what `addEntity` does with a stamped
holder, fence removals on load) needs live evidence on `runAllMods` with the
exact artifact, across a clean restart and a forced kill.

Do not add tests that grep source, pin private structure, count incidental
files or JSON keys, or prove that a symbol exists. The two architecture guards
protect documented thread and ECS-write invariants; do not widen their
exceptions to make a shortcut pass.

## Commands

Run focused tests first:

```bash
bash ../gradlew -p .. :alecstamework:test --tests 'com.alechilles.alecstamework.companion.store.*'
```

For a system, tick, async, ECS write or world-thread change, also run:

```bash
bash ../gradlew -p .. :alecstamework:test \
  --tests '*EcsWriteSafetyGuardTest' \
  --tests '*AsyncThreadSafetyGuardTest'
```

When the importer's SQLite driver or its packaging changes, run the packaged
check (`SqlitePackagingIT`):

```bash
bash ../gradlew -p .. :alecstamework:packagingTest
```

Run the full suite when a change touches shared behavior or several areas:

```bash
bash ../gradlew -p .. :alecstamework:test
```

After agent docs, package layout, scripts or skills change, run the agent-doc
check from Git Bash:

```bash
pwsh -NoProfile -ExecutionPolicy Bypass \
  -File scripts/tools/check-agent-docs.ps1
```

Use `bash ../gradlew -p .. stageAllModAssets` only when staged runtime evidence
is needed. Launch `runAllMods` only for an explicit live acceptance task.

# Persistence Authority Map

Use this map before broad searches. Packages are under
`src/main/java/com/alechilles/alecstamework/`. Verify names against the
current commit.

## Where Things Live

| Package | Owns | Start with |
| --- | --- | --- |
| `companion/index` | The in-memory index and the immutable record: location kinds (`LIVE`, `ITEM`, `COOP`, `STORED`, `DEAD`, `LOST`, `RELEASED`), stored reasons, UI summary, domain claims, extension entries, record scope | `CompanionIndex`, `CompanionRecord`, `CompanionLocation`, `CompanionSummary`, `ExtensionEntries` |
| `companion/store` | Files: layout, owner-file and snapshot BSON, write-behind, file I/O through `StorageManager` | `CompanionStorage`, `CompanionStore`, `CompanionWriter`, `CompanionRecordBson`, `SnapshotEnvelope`, `HytaleCompanionFileIo` |
| `companion/live` | Bodies in a world: the stamp component, generation fence, loaded-body map, change detector, snapshots, summaries, respawn preparation | `TameworkCompanionComponent`, `CompanionFence`, `CompanionBodySystem`, `LoadedBodies`, `CompanionChangeDetectorSystem`, `CompanionFingerprints`, `CompanionSaves`, `CompanionSnapshots`, `CompanionSummaries`, `CompanionRespawn` |
| `companion/flow` | Record transitions and the flows that move companions: tame and adoption, capture, restore (recall, recover, revive, summon), store, death, loss, world removal, owner release, summon expiry, spawning from a snapshot | `CompanionTransitions`, `CompanionBodyLifecycle`, `CompanionRegistration`, `CaptureFlow`, `RestoreFlow`, `RestoreRules`, `StoreFlow`, `ReleaseFlow`, `RosterSummons`, `SummonExpiryScheduler`, `SnapshotPatch`, `HytaleCompanionSpawner` |
| `companion/item` | Capture items: identity keys, ownership mode decisions, Forget, Recall and destroyed items, pickup admission cache | `CaptureItemKeys`, `CaptureItemOwnership`, `CaptureItemFlows`, `AdmissionCache` |
| `companion/coop` | Coop slots on the block, intake, release, block break, schedule, imported residents | `TameworkCoopSlotsComponent`, `CoopSlots`, `CoopIntakeFlow`, `CoopRelease`, `CoopBreakSystem`, `CoopScheduleSystem` |
| `companion/admission` | Owned, deployed and group caps under the index lock; external admission providers | `CompanionAdmission`, `CompanionAdmissionGate`, `ProviderAdmission`, `ProviderDecisionCache` |
| `companion/bonded` | Bonded companions as index records; the bonded API over the index | `IndexBondedCompanionApi`, `BondedRecords`, `BondedAdmission` |
| `companion/migrate` | 3.x/4.x import and the lazy handling of old bodies, items, coops and players | `package-info.java` first, then `CompanionImporter`, `LegacyReader`, `LegacyMapper`, `LegacyBodyResolution`, `LegacyItemAdoption`, `LegacyBodyLocator` |
| `companion/runtime` | Module that opens the store, owns the index, writer, threads and loaded map; lock-free queries | `CompanionPersistenceModule`, `CompanionQueries` |
| `api/internal` | Public API 3.0.0 over the index | `IndexTameworkApi`, `IndexNpcProfilesApi`, `IndexProfileDataApi`, `IndexPopulationGroupApi`, `IndexDiagnosticsApi` |
| `settings` | Server settings, the settings announcement, the progression clock, data-path resolution. Not companion state | `TameworkSettingsStore`, `TameworkSettingsAnnouncementStore`, `AnimalProgressionClockStore`, `TameworkDataPathService`, `TameworkDataPathLayout` |

`companion/{identity,capture,lifecycle,placement,population,snapshot}` hold
small shared types (profile ids, capture formulas, spawn placement, group
definitions). Composition is in `Tamework.java` and
`TameworkCompanionRuntimeParticipants.java`.

## Files

All under `<universe>/Tamework/Companions/` (`CompanionStorage.root`):

- `meta.json`: format, created-by version, and an import or fresh-start receipt.
- `owners/<ownerUuid>.json` and `owners/_unowned.json`: every record of one
  owner, rewritten whole on any change.
- `snapshots/<profileId>.json`: the latest snapshot. Format 1 is a full-entity
  BSON; format 0 is imported 3.x/4.x state, replaced on first restore.
- `legacy-aliases.json`: old NPC UUID to profile id, written once by the import.
- `locate-progress.json`: progress of the saved-chunk pass after an import.

An unreadable owner file is renamed `<name>.unreadable-<timestamp>` and that
owner starts empty. An unreadable record inside a readable file is kept and
written back unchanged, and its id is never adopted. Import reports go to the
Tamework data folder (`import-report-<UTC time>.txt`, or
`import-report-failed.txt`).

## Threads and Ownership

- World threads: ECS callbacks, short locked index changes, snapshot
  serialization of their own bodies, spawns.
- `tamework-companion-writer`: every file write. Flush every 250 ms
  (`CompanionPersistenceModule.FLUSH_INTERVAL_MS`) or on `flushNow`, which
  times out after 5 s. Failed writes stay pending and retry with backoff from
  1 s to 30 s.
- `tamework-companion-reader`: snapshot file reads (`readSnapshot`) and the
  folder size for diagnostics.
- `tamework-companion-timers`: summon expiry.
- `tamework-companion-load` (up to 8 threads): reads and decodes owner files
  inside `CompanionStore.loadAll` at startup. The results are merged on the
  calling thread in file order, and the pool is gone when `loadAll` returns.
- Shutdown: the `ShutdownEvent` handler at priority -28 runs the final flush
  while `StorageManager` still runs; it reads no ECS state.
- After-unlock index listeners (`addAfterUnlockListener`) run on the thread
  that made the change, often a world thread; they must not block or touch
  another world's entities.

## Evidence Order

For the intended design: ADR 0011, then current code and its Javadoc, then
behavior tests. The external rework spec
(`CodexDocs/alecstamework/persistence-rework/2026-09-30-persistence-rework-spec.md`)
has the full rationale; where it and the code differ, the code wins and the
difference is worth reporting.

For what a server actually did: the loaded jar, the files in the companion
folder, server logs, and `diagnostics().getPersistenceDiagnostics()` (records
by location, pending writes, last flush time, last failure, unreadable
records). Do not let current source override evidence from a stale loaded
artifact.

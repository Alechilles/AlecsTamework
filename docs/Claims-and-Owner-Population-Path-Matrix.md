# Claims and Owner Population Path Matrix

This document is the implementation audit map for QuestLinesClaims, Simple Claims,
and Tamework's per-player tame limit. It records every supported companion mutation
path against the two canonical quantities:

- **owner delta**: change to the number of non-released companions assigned to an owner;
- **claim delta**: change to the number of physical companions occupying a claim scope.

The canonical row is `companion_population_state`. Admission is allowed only from a
fully reconciled index, and a prepared transition must reach exactly one terminal
state: committed, canceled, or compensating/recovered. In-memory counters are views
of that durable authority, not an independent source of truth.

## Mutation path matrix

`+N` and `-N` below describe the successful path. A denied or canceled operation has
zero durable delta. Source finalization must never happen before the population commit.

| Player/runtime entry point | Population path | Owner delta | Claim delta | Source or side-effect finalization | Primary evidence |
| --- | --- | ---: | ---: | --- | --- |
| Optimized tame interaction (`ActionTameworkInteract`) | `InteractionOwnerAdmissionService` -> `OwnerMutationScheduler` | New ownership `+1`; already-owned `0` | Unowned physical companion becoming owned `+1`; otherwise `0` | Consume the interaction item and apply tame effects only from the applied continuation | `InteractionOwnerAdmissionServiceTest`, `ActionTameworkSetOwnerContinuationTest` |
| Standalone set-owner action/command (`ActionTameworkSetOwner`, `TameworkSetOwnerCommand`) | `OwnerMutationAdmissionPlanFactory` -> `OwnerMutationScheduler` | Set new owner `+1`; transfer old `-1`, new `+1`; clear `-1`; same owner `0` | Only a physical unowned-to-owned transition adds `+1`; transfer is `0`; clearing physical ownership removes `-1` | Component/profile writes run from the admitted continuation; denial leaves both unchanged | `ActionTameworkSetOwnerMutationPlanTest`, `TameworkSetOwnerCommandMutationPlanTest`, `OwnerMutationContinuationArchitectureTest` |
| Legacy tame adoption (`LegacyTamedOwnershipBridge`) | `OwnerDerivedAuthorityMutationService` | `+1` for first authoritative adoption | `+1` when the adopted NPC is physical | Link/adoption effects follow the durable mutation | `LegacyTamedOwnershipBridgeResultTest` |
| Spawn-tamed/admin batch (`TameworkNpcSpawnTamedCommand`) | `NpcOwnedBatchSpawnService` -> `CompanionPreparedSpawnService` | `+1` per successfully committed NPC | `+1` per physical spawned NPC | A prepared entity holder is added only inside the commit protocol; partial batches cancel unused units | `CompanionPreparedSpawnServiceOrderTest`, `PreparedSpawnCrashBoundaryArchitectureTest` |
| Filled spawner restore/adoption (`SpawnerFeatureHandler`) | `SpawnerPreparedSpawnService` -> `CompanionPreparedSpawnService` | Existing owner `0`; explicit adoption `+1` | Dormant item to physical NPC `+1` | `CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM` consumes the item only after commit | `SpawnerFeatureHandlerTest`, `CompanionSpawnSourceFinalizationContextTest`, `CompanionSpawnCommitContinuationTest` |
| Managed coop capture (`CommandCoopManagedWildCaptureSystem`) | `CoopPopulationMutationService` / `CoopCaptureLedgerTransaction` | Normally `0`; capture-with-owner-clear uses the explicit `-1` owner plan | Physical to coop-dormant `-1` | Coop ledger, snapshot, and canonical population transition share the transaction; entity removal follows success | `CoopCaptureLedgerTransactionTest`, `CompanionPopulationRepositoryTest` |
| Managed coop release | `CoopPreparedReleaseSpawnService` -> `CoopPopulationReleaseAdmissionService` | `0` for retained owner | Coop-dormant to physical `+1` | Ledger source becomes released only after spawn/population commit | `CoopPopulationReleaseAdmissionServiceTest`, `CoopReleaseSpawnCompletionTest`, `CoopPreparedReleaseSpawnArchitectureTest` |
| Revivable death (`CommandLinkedNpcDeathService`) | `CommandLinkedNpcDeathProfileWriter` plus removal classifier | `0` | Physical to death snapshot `-1` | Durable death/profile snapshot is written before physical removal; the source remains available for revive | `CompanionRemovalLifecycleClassifierTest`, `CommandRespawnServiceTest` |
| Revive (`CommandRespawnService`) | prepared restore spawn -> `CompanionPreparedSpawnService` | `0` | Death snapshot to physical `+1` | Death source is finalized only after commit; ambiguous outcomes retain recoverable state | `CommandRespawnServiceTest`, `CompanionRevivePolicyTest`, `CompanionSpawnCommitContinuationTest` |
| Lost relocation fallback (`CommandLinkedNpcLostService`) | lost snapshot; recovery through `CommandLostFallbackSpawnService` | `0` | Drop/lost `-1`; fallback recovery `+1` | Lost source remains pending until the replacement spawn commits | `CommandRelocationTimeoutDecisionTest`, `CommandRelocationRestoreArchitectureTest`, `CompanionSpawnCommitContinuationTest` |
| Recall, teleport, or rehome (`CommandNpcRelocationService`) | `CommandRelocationAdmissionGate` -> `CompanionRelocationAdmissionService` | Global `0`; per-world source `-1`, destination `+1` | Source claim `-1`, destination claim `+1` | Pending relocation is retained on denial/timeout; the old physical record is not discarded before destination commit | `CompanionRelocationAdmissionServiceTest`, `CommandNpcRelocationServiceTest`, `PendingRelocationAdmissionStateTest` |
| Manual or passive breeding | `BreedingPopulationAdmissionService` -> prepared child batch | `+N` grouped by each resolved child owner | `+N` for successfully materialized children | Stable birth plan and replay journal own the handoff; every unused or failed child unit is canceled/recovered | `BreedingPopulationHeadroomIntegrationTest`, `BreedingPopulationReplayServiceTest`, `BreedingPreparedHandoffTerminalityTest`, `BreedingPopulationBatchAdmissionCoordinatorTest` |
| Owner release/cull | `CommandOwnerReleaseService` / `CommandOwnerCullService` -> owner mutation authority | `-1` per released owned profile | `-1` when that profile is physical | Permanent release/cull durability precedes destructive entity removal | `CommandOwnerCullContinuationTest`, `PermanentDeletionPopulationArchitectureTest` |
| Permanent non-revivable death | `CompanionPermanentDeathCoordinator` and retention systems | `-1` | `-1` | `CompanionPermanentDeathHold` retains the corpse until the release transition is durable | `CompanionPermanentDeathHoldTest`, `PermanentDeletionPopulationArchitectureTest` |
| Public population API | `RuntimePopulationPolicyAuthority` | Request-defined | Request-defined | Caller must commit or cancel the returned prepared admission; operation keys provide idempotency | `RuntimePopulationPolicyAuthorityTest`, `PopulationPolicyApiV2Test` |
| Natural load/unload/movement | runtime reconciliation observations | `0`; a known world move updates per-world scope `-1/+1` | Physical claim movement `-1/+1`; unload alone is not permanent removal | Coalesced observations update location; startup reconciliation remains the recovery authority | `CompanionPopulationRuntimeReconcilerTest`, `CoalescedCompanionPopulationWriterTest` |
| `/tw npcclean` | `NpcCleanOwnershipGuard` | `0`; it may remove only NPCs proven unowned | Removal affects only proven-unowned occupancy | Cleanup is denied while indexes are not ready or ownership is unresolved | `NpcCleanOwnershipGuardTest`, `ClaimsOwnerPopulationStructuralPolicyTest` |

## Admission and finalization invariants

1. Resolve identity, lifecycle, owner scope, physical scope, and claim provider generation.
2. Require global owner readiness for positive owner deltas, per-world readiness for
   positive scoped owner deltas, and claim readiness for positive claim deltas.
3. Prepare with a stable operation key. A retry must return the existing operation,
   not reserve capacity twice.
4. Apply the world/entity side effect on the world thread.
5. Commit canonical state. Publish the resulting in-memory owner and claim views.
6. Finalize a consumed source (spawner item, death/lost/coop record) only after commit.
7. On definite pre-apply failure, cancel. On ambiguous post-apply failure, preserve
   evidence and enter recovery; never guess that the side effect did or did not occur.

QuestLinesClaims and Simple Claims are generation-bound providers. A provider reload,
disable, or replacement invalidates the lookup/admission session. Missing or ambiguous
provider capability fails closed for positive claim admissions. Damage-policy lookup is
separate: it follows its documented fail-open behavior so an integration outage does
not make companions globally invulnerable.

## Reconciliation evidence coverage

Startup reconciliation seals these independent catalogs before reporting ready:

- SQLite profiles and dormant lifecycle records;
- detached saved-world entity chunks, including dead physical entities;
- stored and online player inventories;
- base block item containers;
- explicitly registered and sealed custom persisted-container providers.

Each mutable Hytale catalog is snapshotted in deterministic order and checked again at
completion. A changed player UUID set or saved-chunk index set invalidates that scan;
it cannot be promoted to ready. Direct contract coverage lives in
`HytaleStoredPlayerInventoryEvidenceSourceTest`,
`HytaleSavedWorldEvidenceSourceTest`,
`CompanionPopulationReconciliationCatalogTest`, and
`CompanionPopulationStartupReconcilerTest`.

## Hytale 0.5.6 API inventory used by this design

The implementation was checked against the local 0.5.6 server API/source inventory:

- Plugins: `PluginManager#getPlugin(PluginIdentifier)`,
  `PluginManager#getAvailablePlugins`, `PluginBase#getState`. `JavaPlugin` does not
  provide a `getServer()` capability shortcut.
- Startup/catalogs: `Universe#getUniverseReady`, `Universe#getWorlds`,
  `Universe#getPlayers`, and `Universe#getPlayerStorage`.
- Saved worlds: `World#getChunkStore`, `ChunkStore#getLoader`,
  `IChunkLoader#getIndexes`, `IChunkLoader#loadHolder`, and `ChunkUtil` index helpers.
- Stored players: `PlayerStorage#getPlayers` and `PlayerStorage#load(UUID)`.
- Detached evidence: `Holder#getComponent`, `EntityChunk#getEntityHolders`,
  `BlockComponentChunk#getEntityHolders`, and `ItemContainerBlock#getItemContainer`.
- World-thread work: `World#execute`, `World#getEntityRef`, ECS stores, and command
  buffers. System command buffers are consumed between systems; ref-change observers
  can run before pending component writes are consumed, which is why permanent-death
  removal uses a retained hold marker.
- Death lifecycle: `DeathComponent`, `DeferredCorpseRemoval`,
  `DeathSystems.TickCorpseRemoval`, `DeathSystems.CorpseRemoval`, and
  `NPCSystems.OnDeathSystem`. The vanilla deferred-removal marker is not a durable
  ownership record.
- Permission bypass: `PermissionsModule.registerPermission`,
  `PermissionsModule.get`, and `PermissionsModule#hasPermission(UUID, String)`.

## Unknown ownership scope and recovery guidance

An owned profile whose `ownership_world_name` is unknown still counts toward its
owner's **global** tame limit. It must not be silently assigned to the currently loaded
world. Per-world owner readiness remains `RECONCILING`, so positive per-world admissions
stay denied until persisted evidence establishes the scope or an explicit supported
release removes the ownership.

There is no automatic population-repair command. Diagnostics expose readiness,
coverage, pending operations, and failure reasons; they do not rewrite canonical rows.
For recovery:

1. Stop the server and back up the world/save plus the SQLite database and its `-wal`
   and `-shm` files.
2. Preserve logs and diagnostics output, including coverage keys and pending operation
   IDs.
3. Prefer restoring a known-good backup or restoring the missing authoritative source
   (player inventory, chunk, coop/death/lost record) and rerunning reconciliation.
4. Use a normal supported release/cull flow when the companion is intentionally gone.
5. Do not hand-edit counts or delete population rows. Any exceptional database repair
   should be an offline, reviewed identity/source correction with a rollback copy.

Focused validation commands:

```powershell
.\mvnw.cmd -Dtest=CompanionPopulationSchemaMigrationTest,HytaleSavedWorldEvidenceSourceTest,HytaleStoredPlayerInventoryEvidenceSourceTest,CompanionPopulationReconciliationCatalogTest,CompanionPopulationStartupReconcilerTest test
.\mvnw.cmd test
```

# Claims and Owner Population Path Matrix

This document is the implementation audit map for QuestLinesClaims, Simple Claims,
and Tamework's per-player tame limit. It records every supported companion mutation
path against the two canonical quantities:

- **owner delta**: change to the number of non-released companions assigned to an owner;
- **claim delta**: change to the number of physical companions occupying a claim scope.

The canonical row is `companion_population_state`. Admission is allowed only from a
fully reconciled index. A transition moves through durable `PREPARED`, `APPLYING`, and,
when rollback is required, `COMPENSATING` phases before reaching a terminal `COMMITTED`
or `FAILED` state. Breeding adds terminal `RETRYABLE`: it releases global readiness and
the per-profile nonterminal lock while retaining the exact child plan for marker-aware replay.
`BREEDING` rows in `RETRYABLE` (and committed birth evidence still needed for replay) must
not be removed by generic completed-operation pruning. In-memory counters are views of that
durable authority, not an independent source of truth.

## Mutation path matrix

`+N` and `-N` below describe the successful path. A denied or canceled operation has
zero durable delta. Source finalization must never happen before the population commit.

| Player/runtime entry point | Population path | Owner delta | Claim delta | Source or side-effect finalization | Primary evidence |
| --- | --- | ---: | ---: | --- | --- |
| Optimized tame interaction (`ActionTameworkInteract`) | `InteractionOwnerAdmissionService` -> `OwnerMutationScheduler` | New ownership `+1`; already-owned `0` | Unowned physical companion becoming owned `+1`; otherwise `0` | Consume the interaction item and apply tame effects only from the applied continuation | `InteractionOwnerAdmissionServiceTest`, `ActionTameworkSetOwnerContinuationTest` |
| Standalone set-owner action/command (`ActionTameworkSetOwner`, `TameworkSetOwnerCommand`) | `OwnerMutationAdmissionPlanFactory` -> `OwnerMutationScheduler` | Set new owner `+1`; transfer old `-1`, new `+1`; clear `-1`; same owner `0` | Only a physical unowned-to-owned transition adds `+1`; transfer is `0`; clearing physical ownership removes `-1` | Component/profile writes run from the admitted continuation; denial leaves both unchanged | `ActionTameworkSetOwnerMutationPlanTest`, `TameworkSetOwnerCommandMutationPlanTest`, `OwnerMutationContinuationArchitectureTest` |
| Legacy tame adoption (`LegacyTamedOwnershipBridge`) | `OwnerDerivedAuthorityMutationService` | `+1` for first authoritative adoption | `+1` when the adopted NPC is physical | Link/adoption effects follow the durable mutation | `LegacyTamedOwnershipBridgeResultTest` |
| Spawn-tamed/admin batch (`TameworkNpcSpawnTamedCommand`) | `NpcOwnedBatchSpawnService` -> `CompanionPreparedSpawnService` | `+1` per successfully committed NPC | `+1` per physical spawned NPC | A prepared entity holder is added only inside the commit protocol; partial batches cancel unused units | `CompanionPreparedSpawnServiceOrderTest`, `PreparedSpawnCrashBoundaryArchitectureTest` |
| Filled spawner restore/adoption (`SpawnerFeatureHandler`) | `SpawnerPreparedSpawnService` -> `CompanionPreparedSpawnService` | Retained stored owner `0`; cleared-to-unowned `0`; null-to-spawning-owner `+1` | Retained/new owner to physical NPC `+1`; unowned restore `0` | `CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM` consumes the item only after commit; denied/canceled legacy adoption releases its provisional identity exactly once | `SpawnerFeatureHandlerTest`, `CompanionSpawnLegacyAdmissionIntegrationTest`, `CompanionSpawnCommitContinuationTest` |
| Managed coop runtime capture (`ManagedCoopRuntimeSystem`) | `ManagedCoopCaptureRuntimeAdapter` -> `CoopPopulationCaptureAdmissionService` -> `ManagedCoopPopulationCaptureCommitter` | Normally `0`; capture-with-owner-clear uses the explicit `-1` owner plan | Physical to coop-dormant `-1` | Resident slot, full snapshot, canonical population state, and exact capture operation commit atomically; source retirement starts only after success and only an actual `REMOVE` callback or separately sealed all-world evidence may prove absence, never chunk `UNLOAD` | `CoopPopulationCaptureAdmissionServiceTest`, `ManagedCoopCaptureCoordinatorTest`, `ManagedCoopCaptureSourceRetirementServiceTest`, `ManagedCoopPopulationAtomicityTest` |
| Managed coop release | `ManagedCoopReleaseRuntimeAdapter` -> `ManagedCoopReleasePopulationCoordinator` -> `CoopPopulationReleaseAdmissionService` | `0` for retained owner | Coop-dormant to physical `+1` | Exact planned UUID/profile and release claim are installed before spawn; the revision-stable all-world loaded projection index and sealed saved-world marker evidence must agree before absence or persisted adoption is authoritative and are rechecked after asynchronous population preparation. A stale generation cancels population-only while retaining the release claim for fresh recovery. Resident, population, and operation finalize atomically, while alternate/duplicate/dead markers and ambiguous post-add outcomes retain evidence | `CoopPopulationReleaseAdmissionServiceTest`, `ManagedCoopReleaseProjectionProbeTest`, `ManagedCoopPersistedReleaseProjectionRecoveryServiceTest`, `ManagedCoopReleasePopulationCoordinatorTest`, `ManagedCoopReleaseSpawnOrchestratorTest`, `ManagedCoopPopulationAtomicityTest` |
| Revivable death (`CompanionRevivableDeathPopulationSystem`, `CommandLinkedNpcDeathService`) | immediate runtime dormant projection plus durable death/profile snapshot | `0` | Physical to `DEAD_REVIVABLE` `-1` as soon as death is observed | The owner slot remains; durable death/profile evidence stays available for revive | `CompanionDeathPopulationProjectorTest`, `CompanionRemovalLifecycleClassifierTest`, `CommandRespawnServiceTest` |
| Revive (`CommandRespawnService`) | prepared restore spawn -> `CompanionPreparedSpawnService` | `0` | Death snapshot to physical `+1` | Death source is finalized only after commit; ambiguous outcomes retain recoverable state | `CommandRespawnServiceTest`, `CompanionRevivePolicyTest`, `CompanionSpawnCommitContinuationTest` |
| Lost relocation fallback (`CommandLinkedNpcLostService`) | lost snapshot; recovery through `CommandLostRecoveryCoordinator` | `0` | Drop/lost `-1`; fallback recovery `+1` | Lost source remains pending until the replacement spawn commits | `CommandRelocationTimeoutDecisionTest`, `CommandRelocationRestoreArchitectureTest`, `CompanionSpawnCommitContinuationTest` |
| Recall, teleport, or rehome (`CommandNpcRelocationService`) | `CommandRelocationAdmissionGate` -> `CompanionRelocationAdmissionService` | Global `0`; per-world source `-1`, destination `+1` | Source claim `-1`, destination claim `+1` | Pending relocation is retained on denial/timeout; an unavoidable observed over-cap move preserves the companion, emits a throttled warning, and increments `unavoidablePerWorldOverCapRelocations` | `CompanionRelocationAdmissionServiceTest`, `CommandNpcRelocationServiceTest`, `CompanionPopulationRuntimeReconcilerTest` |
| Manual or passive breeding | `BreedingPopulationAdmissionService` -> prepared child batch | `+N` grouped by each resolved child owner | `+N` for successfully materialized children | Stable birth plan, deterministic child identity, projection marker, and replay journal own the handoff. Component UUID, legacy NPC UUID, and marker must all match before commit, and restart-absence evidence is rechecked inside the final holder callback. Parent capture joins in-flight preparation and waits for every unspawned unit to become durably terminal; delayed stages reject pending or non-`ACTIVE` canonical parents. An absent crash-interrupted child becomes `RETRYABLE` and reuses only its exact unowned revision-zero baseline; one exact persisted marker plus matching ordinary physical evidence converges as committed, while all conflicting evidence fails closed | `BreedingPopulationHeadroomIntegrationTest`, `BreedingPopulationReplayServiceTest`, `BreedingPopulationRetryBaselineResolverTest`, `BreedingPersistedProjectionReplayGuardTest`, `BreedingPreparedPopulationRegistryTest`, `BreedingCaptureCancellationServiceTest`, `BreedingParentLifecycleGateTest`, `BreedingOffspringSpawnFallbackTest`, `CompanionPopulationBatchAdmissionCoordinatorTest` |
| Owner release/cull | `CommandOwnerReleaseService` / `CommandOwnerCullService` -> owner mutation authority | `-1` per released owned profile | `-1` when that profile is physical | Permanent release/cull durability precedes destructive entity removal | `CommandOwnerCullContinuationTest`, `PermanentDeletionPopulationArchitectureTest` |
| Permanent non-revivable death | `CompanionPermanentDeathCoordinator` and retention systems | `-1` | `-1` | `CompanionPermanentDeathHold` retains the corpse until the release transition is durable; rejected world callbacks clear only safe pre-apply barriers or recognize an already durable release | `CompanionPermanentDeathCoordinatorTest`, `LeaseBoundWorldDispatcherTest`, `PermanentDeletionPopulationArchitectureTest` |
| Public population API | `RuntimePopulationPolicyAuthority` | Request-defined | Request-defined | Caller must commit or cancel the returned prepared admission; operation keys provide idempotency | `RuntimePopulationPolicyAuthorityTest`, `PopulationPolicyApiV2Test` |
| Bundled API self-test fixtures (`/tw api test prepare`, `reset`) | `ApiSelfTestPopulationAuthority` -> `OwnerMutationScheduler` | Fixture assignment `+1`; reset release `-1` | Physical fixture assignment/release `+1/-1` | Uses journaled `ADMIN_FORCE` assignment and durable permanent release through lease-bound world dispatch; no direct owner/profile bypass | `ApiSelfTestPopulationArchitectureTest` |
| Natural load/unload/movement | runtime reconciliation observations | `0`; a known world move updates per-world scope `-1/+1` | Physical claim movement `-1/+1`; unload alone is not permanent removal | Coalesced observations update location; startup reconciliation remains the recovery authority | `CompanionPopulationRuntimeReconcilerTest`, `CoalescedCompanionPopulationWriterTest` |
| `/tw npcclean` | `NpcCleanOwnershipGuard` | `0`; it may remove only NPCs proven unowned | Removal affects only proven-unowned occupancy | Cleanup is denied while indexes are not ready or ownership is unresolved | `NpcCleanOwnershipGuardTest`, `ClaimsOwnerPopulationStructuralPolicyTest` |

## Admission and finalization invariants

1. Resolve identity, lifecycle, owner scope, physical scope, and claim provider generation.
2. Require global owner readiness for positive owner deltas, per-world readiness for
   positive scoped owner deltas, and claim readiness for positive claim deltas.
3. Prepare with a stable operation key. A retry must return the existing operation,
   not reserve capacity twice.
4. Immediately before apply, refresh provider topology and committed occupancy outside
   the reservation lock, then atomically validate the snapshot revision and recompute
   owner/claim headroom while excluding only this reservation's own pending slots.
5. Persist `APPLYING` while the owner and claim reservations remain held, then apply
   the world/entity side effect on the world thread.
6. On success, commit canonical state, publish the resulting in-memory owner and claim
   views, then finalize a consumed source (spawner item, death/lost/coop record).
7. If an applied mutation must roll back, persist `COMPENSATING` before reversing live
   state or releasing capacity. Restore derived/source state first and the canonical
   owner last; only then close the journal as `FAILED` and release both reservations.
8. On definite pre-apply failure, cancel. On ambiguous post-apply or partial-compensation
   failure, preserve evidence and enter recovery; never guess which side effects ran.
9. Every lease-bearing world handoff has one accepted-never-started rejection path.
   Its watchdog performs terminal cleanup exactly once; a late queued wrapper is a no-op.

Spawner restore ownership is an explicit four-case policy. A non-null stored owner is
always authoritative when `CaptureClearsOwner=false`, so its restore is owner-zero-delta
and claim `+1`. If that stored owner is null, `SpawnSetsOwner=false` keeps the NPC
unowned, while `SpawnSetsOwner=true` assigns the spawning player through a cap-checked
null-to-owner transition (`+1` owner and `+1` claim). `CaptureClearsOwner=true` produces
the same two unowned/assigned outcomes because no stored owner remains. A canonical
unowned restore is therefore not treated as a same-owner zero-delta. Legacy items may
hold one provisional identity lease; commit promotes it, while denial/cancellation
releases it exactly once even if callbacks retry.

QuestLinesClaims and Simple Claims are generation-bound providers. Provider probes
re-read live PluginManager state for each operation and retain reflected positive
contracts only through weak references keyed to plugin, classloader, and reflection
generation. Setup/reload, disable, replacement, or settings changes invalidate the
lookup/admission session, and there is no generation-blind bridge fallback. Missing or
ambiguous provider capability fails closed for positive claim admissions. Damage-policy
lookup is separate: it follows its documented fail-open behavior so an integration
outage does not make companions globally invulnerable.

QuestLines Claims `1.3.1` extent authority comes only from a complete, non-empty
`getChunks()` result. Supported collection, map, and array shapes are snapshotted before
mapping; every chunk must supply X, Z, and the requested world. Positive and negative
claim/coordinate accessor discovery is cached for that bridge generation. Missing or
malformed chunk data fails closed instead of falling back to scalar extent fields.

## Reconciliation evidence coverage

Startup reconciliation seals these independent catalogs before reporting ready:

- SQLite profiles and dormant lifecycle records;
- detached saved-world entity chunks, including dead physical entities;
- stored and online player inventories;
- base block item containers;
- explicitly registered and sealed custom persisted-container providers.

Before building the catalog, startup asks Hytale to load every persisted world directory
that is not already represented by a live save path. The saved-world directory catalog
is still captured independently of live `World` objects. If an immediate world
directory is omitted, unreadable, changes during the scan, fails to load, or cannot be
mapped to a live world's save path, world-entity and base-container coverage remain
unsealed. A failed world load therefore cannot disappear from the reconciliation input
and produce false `READY` state.

A saved entity carrying `DeathComponent` is corpse evidence, not live or unloaded
claim occupancy. Reconciliation records it as `DEAD_REVIVABLE`; its saved chunk may be
retained only as recovery context, while the lifecycle keeps it out of claim counts.
For an interrupted revive, the surviving corpse proves the old dead state rather than
the new active target. A simultaneous live representation and corpse for one UUID, or
two physical representations of aliases for one profile, quarantines reconciliation
instead of selecting a representation and risking a duplicate revive.

Each process acquires a fresh persisted scan epoch; cursors are never resumed across a
restart because mutable chunk and inventory contents may have changed while the process
was down. Saved chunk files are read under the same universe/world saving fence used by
Hytale backups: background saving is paused and in-flight saves are drained until the
scan completes. A deterministic all-world loaded-NPC snapshot is captured separately,
and any identity/marker revision change before finalization invalidates the pass. A changed
player UUID set or saved-chunk index set likewise cannot be promoted to ready. Both owner
coverage dimensions are durably reset to `RECONCILING` before scanning, including when a
previous process left `READY` rows. A complete pass publishes readiness in this order:
exact scan-session `READY`, post-write revision fence, projection-evidence seal, both owner
coverage writes, then a final revision/seal fence. Any failure invalidates that exact session
and degrades the in-memory registry and durable coverage instead of exposing a partial `READY`.
Direct contract coverage lives in
`HytaleStoredPlayerInventoryEvidenceSourceTest`,
`HytaleSavedWorldEvidenceSourceTest`,
`CompanionPopulationReconciliationCatalogTest`,
`CompanionPopulationStartupReconcilerTest`, and
`CompanionPopulationScanSessionRepositoryTest`. Persistent world-directory coverage is
locked by `PersistentWorldDirectoryCatalogTest` and
`PersistedWorldCoverageLoaderTest`.

A bounded historical operation ambiguity does not automatically invalidate this complete coverage.
Before publishing unrelated profiles as ready, startup must durably fence both the exact operation and
its exact profile. The journal remains nonterminal and visible in diagnostics; only that operation and
profile are denied. Bootstrap treats it as contained only while both v7 fences are active. If either
fence is absent or its durable write fails, owner and claim readiness remains broadly fail-closed.

A saved-evidence contradiction can also be contained when every conflicting UUID resolves through
the canonical alias table to exactly one profile. Startup durably fences each unique affected profile,
leaves its existing population row unchanged and conservatively counted, and excludes all known UUID
aliases of those profiles only from the repair input. The unfiltered evidence remains available to the
projection seal and diagnostics. Healthy profiles then merge normally and may publish `READY`.
Unknown, multiply mapped, or unreadable identities and failed durable fences still degrade both owner
coverage dimensions.

A direct owner-component clear is not proof of release. Unless it matches an in-flight
prepared transition or an explicit durable `RELEASED` operation, runtime reconciliation
keeps the canonical slot and queues a component repair through the ownership mutation
facade. This avoids turning an external component mutation into unjournaled capacity.

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

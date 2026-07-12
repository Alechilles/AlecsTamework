---
title: "Persistence, SQLite, and Data Paths"
order: 10
published: true
draft: false
---
# Persistence, SQLite, and Data Paths

Parent: [Data and Persistence](/mod/alecs-tamework/data-and-persistence) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Runtime root
`TameworkDataPathService` resolves the runtime data directory, and `TameworkPersistenceRuntime` owns the SQLite lifecycle inside that directory.

## Main database pieces
- `SqliteConnectionManager`
- `SqliteSchemaMigrator`
- `PersistenceWriteQueue`
- `PersistenceHealthService`
- `ApiProfileDataRepository`
- `CaptureRepository`
- `CoopLedgerRepository`
- `DeathRepository`
- `LostRepository`
- `NpcProfileRepository`
- `CompanionPopulationRepository`
- `CompanionPopulationReconciliationRepository`

## Runtime behavior
- Initializes `tamework.sqlite`
- Opens SQLite in write-ahead logging (`WAL`) mode with `PRAGMA synchronous=FULL`. Population commits therefore request a durable WAL flush before SQLite reports success (subject to the operating system and storage device honoring flush requests), rather than relying on the weaker normal-synchronous mode.
- Runs schema migration
- Imports legacy `.dat` data once when needed
- Schedules WAL checkpoints, vacuum, and snapshot pruning
- Exposes queue metrics and health diagnostics
- Routes profile-scoped experimental API writes through the same queued SQLite path
- Emits persistence-backed profile, capture, death, and lost change callbacks through a repository observer
- Maintains one canonical population row per companion profile plus nonterminal operation journals for crash recovery
- Coalesces physical-location observations instead of writing SQLite on every movement tick

## Persistence domains
- Captured NPC records
- Coop ledger state
- Death snapshots
- Lost-companion state
- Shared NPC profile snapshots
- Experimental profile-scoped extension JSON in `api_profile_data`
- Canonical owner/lifecycle/ownership-world and physical-chunk state in `companion_population_state`
- Prepared/applying/compensating mutation journals in `companion_population_operations`
- Resumable coverage cursors and source evidence for population reconciliation

## Schema v6 population migration

Before schema v6 is applied to an existing database, Tamework creates a transactionally consistent SQLite snapshot with `VACUUM INTO`. The backup is stored beside the live database as `tamework_pre_v6_YYYYMMDD-HHMMSS.sqlite.bak` (with a numeric suffix if needed). Keep the complete save and this backup together when testing an upgrade.

The migration is additive. It seeds population rows from existing profile, capture, coop, death, and lost state without deleting companions. A legacy profile may be over the newly configured cap; reconciliation adopts it and reports over-cap state, while later positive admissions remain blocked. Do not delete rows to make counts fit.

## Population readiness

Population readiness is separate for:

- global owner counts
- per-world owner counts
- physical claim occupancy

Startup initially reports `LOADING`/`RECONCILING`. Positive capped owner changes, transfers, restores, and placements fail closed until the required dimensions are authoritative. Existing data remains available for recovery; an uncertain source is never presented as a zero population.

Per-world readiness can remain reconciling when a canonical owner is known but its authoritative ownership world is not. Dormant profiles keep their last known ownership world; Tamework does not invent a current world from an offline player or stale entity UUID.

An owned profile with an unknown ownership world still consumes its owner's global slot. It cannot authorize a positive per-world admission until persisted evidence establishes the scope or a normal supported release removes the ownership.

There is no automatic population-repair command. Population diagnostics are read-only: they report readiness, incomplete coverage, and pending-operation reasons without rewriting canonical state. Before recovery, stop the server and back up the complete save plus `tamework.sqlite`, `tamework.sqlite-wal`, and `tamework.sqlite-shm` when present. Prefer restoring the missing authoritative source or a known-good complete backup, then rerun reconciliation. Do not hand-edit counters, population rows, or operation journals; exceptional database correction should be performed offline from reviewed identity/source evidence with a rollback copy.

## Reconciliation coverage

The startup catalog is bounded, resumable, and source-generation aware. It covers, in deterministic source order:

1. SQLite profile/lifecycle state and known companion UUID aliases.
2. Saved world entities, including known identities that no longer have an owner component after an applied owner clear.
3. Base world block-item containers.
4. Persisted/offline player saves, then live online player inventories.
5. Explicitly registered and sealed custom persisted-container sources.

Every player inventory section is scanned, including storage, armor, hotbar, utility, `Tool`, and backpack. Captured items inside nested item containers are scanned recursively with depth, container-count, stack-count, and identity-cycle bounds. Base container blocks and registered custom containers participate in separate coverage dimensions; an unsealed custom-container catalog prevents false `READY` status.

Saved-world directories are cataloged separately from successfully constructed live `World` objects. If an immediate saved-world directory is missing from the live catalog, unreadable, changes during the scan, or cannot be mapped back to its live save path, both saved-entity and base-container coverage remain unsealed. A failed world load therefore cannot be mistaken for an empty authoritative world.

Evidence is reconciled by canonical profile/UUID alias. Duplicate observations do not create another owner slot. Conflicting or incomplete evidence degrades readiness rather than discarding a stored companion.

## Crash-recoverable admissions

Owner/claim admissions follow durable `PREPARED -> APPLYING -> terminal` journals:

- Capacity and the intended old/new state are prepared before a live spawn, owner change, restore, or relocation.
- `APPLYING` is recorded before the mutation is attempted.
- Immediately before the live mutation, provider topology and committed occupancy are refreshed outside locks; snapshot revision and cap headroom are then validated atomically while excluding only that operation's own pending slots.
- Commit writes the confirmed canonical owner/lifecycle/location state and closes the journal.
- Cancellation or compensation restores the prior state and closes unused capacity.

After a crash, startup orders physical evidence ahead of weaker profile-only state when determining whether an applying mutation happened. It can finalize an observed new state, close a provably unapplied preparation, or degrade/quarantine ambiguity. Capacity is not reopened from an ambiguous journal merely because the process restarted.

This ordering is essential for source-item flows: reserve first, write `APPLYING`, perform the spawn, and confirm physical identity before finalizing the exact source item/record. A failed pre-spawn path keeps the filled spawner/coop/death/lost source available; a failure after a live identity exists is surfaced as degraded rather than silently reopening capacity.

Coop capture/release carries its expected prior resident source in the population operation. The coop row is source-validated and updated in the same SQLite transaction that commits canonical population state and finalizes the journal. If the slot changed or any write fails, all three remain uncommitted; an independent queued coop write cannot report success while population state rolls back.

Breeding retries use deterministic identities rather than generating a new child after every restart. The pair attempt key is derived from the sorted parent UUIDs plus their persisted cooldown generations, and each child profile/NPC UUID is derived from that attempt and child key. A committed child is therefore the durable replay tombstone for that unit, while unspawned units are cancelled instead of leaking reservations.

Lifecycle release is equally explicit. `DEAD_REVIVABLE` remains owned; permanent cull/release and deaths without a configured/supported revive path commit `RELEASED` with no owner. A generic entity removal or temporary owner-component absence is not proof that capacity may be reopened.

An owner component removed without an in-flight prepared transition or an explicit durable `RELEASED` operation is treated as an unjournaled mutation. Runtime reconciliation retains the canonical owner slot and queues restoration through the owner mutation facade instead of interpreting the component clear as a release.

## Permanent-death corpse retention and recovery evidence

Hytale `0.5.6` dispatches every matching `DeathSystems.OnDeathSystem` observer for a newly added `DeathComponent` before consuming those observers' command buffers. Removing and re-adding `DeathComponent` in one observer therefore cannot prevent later death observers from running and can duplicate downstream death work.

For a direct death with no supported revive path, Tamework leaves `DeathComponent` in place. Its fallback observer runs after the NPC death observer and queues a marked `DeferredCorpseRemoval` hold after the role-authored corpse timer. A retention system runs before both vanilla corpse-removal systems, keeps an owned permanent-death corpse held while durable release is pending, and retries release preparation at a throttled interval. The hold is a distinct marker that survives component cloning; Tamework never treats an ordinary vanilla corpse timer as its hold or shortens that timer. If world shutdown rejects a deferred callback, or accepts it but never starts it, a dispatch-start watchdog aligned with the reservation lease invokes state-independent cleanup. A pre-apply rejection cancels the prepared operation and clears the process-local pending barrier so retention can retry; a post-apply rejection clears that barrier only after the commit proves durable, while ambiguous state remains held. Retention checks the pending barrier before canonical ownerless `RELEASED` state because the in-memory index can reflect a release before SQLite durability. Once safe cleanup removes the barrier or a durable commit callback clears it, normal corpse removal can resume; any queued callback that starts after watchdog rejection is a no-op.

Saved-world reconciliation records a physical entity with `DeathComponent` as dead physical evidence, separately from a live physical entity. A revivable corpse repairs to `DEAD_REVIVABLE`, never `UNLOADED`; its saved chunk is recovery context and does not consume claim occupancy. During recovery of an interrupted revive, the surviving corpse proves the old dead state rather than an active replacement. During recovery of an `APPLYING` permanent-death journal, an ownerless dead physical observation can prove that both the owner clear and death occurred. An ownerless live physical observation remains ambiguous and does not reopen capacity. Conflicting live/dead observations, duplicate physical locations, or simultaneous corpse/live aliases for one profile degrade reconciliation instead of choosing whichever source was scanned last.

## Upgrade and rollback guidance

1. Stop the server and copy the complete universe/save, not only `tamework.sqlite`.
2. Start the new build on that copy and wait for population diagnostics to reach `READY` (or investigate the reported dimension/reason).
3. Verify owned active, unloaded, captured, cooped, dead, and lost companions remain represented once each.
4. Verify offline inventories and nested captured items were covered.
5. Test a new acquisition, transfer to a full owner, and stored-companion restore before upgrading the real save.

The supported data rollback after schema migration is restoration of the complete pre-migration save/database backup. Additive tables can remain on disk, but an older build's cross-world counter is not safe with `GLOBAL` caps enabled. Disable the cap before any code rollback that cannot understand schema v6 state.

## Maintenance advice
- Treat persistence health degradation as a first-class runtime signal
- Keep long-running DB maintenance off the hot path
- Prefer repository-level writes over bypassing the queue
- Do not edit population rows or nonterminal journals by hand. Capture diagnostics and backups before repair work.

## Related Pages
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)




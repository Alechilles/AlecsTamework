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
- `ManagedCoopResidentRepository`
- `CoopLifecycleOperationRepository`
- `ManagedCoopImportRepository`
- `DeathRepository`
- `LostRepository`
- `NpcProfileRepository`

## Runtime behavior
- Initializes `tamework.sqlite`
- Runs schema migration
- Imports legacy `.dat` data once when needed
- Schedules WAL checkpoints, vacuum, and snapshot pruning
- Exposes queue metrics and health diagnostics
- Routes profile-scoped experimental API writes through the same queued SQLite path
- Emits persistence-backed profile, capture, death, and lost change callbacks through a repository observer
- Uses completion-aware queued writes and drains accepted work during shutdown instead of silently dropping it
- Rebuilds trusted managed-coop resident and lifecycle-operation indexes before admitting normal runtime work

## Persistence domains
- Captured NPC records
- Coop ledger state
- Schema-v5 managed-coop authority, resident-slot, UUID-claim, and lifecycle-operation state
- Schema-v5 vanilla-resident import sessions, immutable source fingerprints, dispositions, and quarantined conflicts
- Death snapshots
- Lost-companion state
- Shared NPC profile snapshots
- Experimental profile-scoped extension JSON in `api_profile_data`

## Maintenance advice
- Treat persistence health degradation as a first-class runtime signal
- Keep long-running DB maintenance off the hot path
- Prefer repository-level writes over bypassing the queue
- Treat `profile_id` as canonical NPC identity and entity UUIDs as aliases. Recovery must resolve all known aliases before it can conclude that a profile has no live or housed representation.
- Keep profile state, managed slot state, and lifecycle operation changes in one transaction when they represent one capture or release.
- Import ambiguity is a health condition, not permission to choose a resident. Retain evidence and fail closed until the exact binding or absence proof is available.
- Treat imported-source absence proof as process-scoped live evidence. Persist it for audit, but recheck and refresh it after restart before finalizing a restored vanilla block list.
- Use `/tw debugdb integrity` for SQLite, foreign-key, identity, lifecycle, and import invariants; use `/tw coop audit` for the runtime-facing managed-coop summary.
- `/tw coop rollback-preflight` is read-only. It can identify queue/operation/conflict/integrity blockers and pre-v5 SQLite snapshots, but a supported downgrade still requires the matching complete pre-v5 save; SQLite alone is not a complete-save backup.

## Related Pages
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)




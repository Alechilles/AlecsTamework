---
title: "Persistence, SQLite, and Data Paths"
order: 10
published: true
draft: false
---
# Persistence, SQLite, and Data Paths

Parent: [Developer Documentation Index](/mod/alecs-tamework/developer-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

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

## Runtime behavior
- Initializes `tamework.sqlite`
- Runs schema migration
- Imports legacy `.dat` data once when needed
- Schedules WAL checkpoints, vacuum, and snapshot pruning
- Exposes queue metrics and health diagnostics
- Routes profile-scoped experimental API writes through the same queued SQLite path
- Emits persistence-backed profile, capture, death, and lost change callbacks through a repository observer

## Persistence domains
- Captured NPC records
- Coop ledger state
- Death snapshots
- Lost-companion state
- Shared NPC profile snapshots
- Experimental profile-scoped extension JSON in `api_profile_data`

## Maintenance advice
- Treat persistence health degradation as a first-class runtime signal
- Keep long-running DB maintenance off the hot path
- Prefer repository-level writes over bypassing the queue

## Related Pages
- [Command Runtime and Linked Panel Internals](/mod/alecs-tamework/command-runtime-and-linked-panel-internals)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)

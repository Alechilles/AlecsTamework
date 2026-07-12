---
title: "Diagnostics API Reference"
order: 13
published: true
draft: false
---
# Diagnostics API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.7.0`)**
> This reference tracks the current `diagnostics()` contract in `TameworkApi`.

Capability: `DIAGNOSTICS`

## Entry Point
`TameworkApi.diagnostics() -> DiagnosticsApi`

## Methods
- `PersistenceDiagnosticsView getPersistenceDiagnostics()`
- `PopulationDiagnosticsView getPopulationDiagnostics()`

## `PersistenceDiagnosticsView`
- `databasePath`
- `sqliteBytes`
- `walBytes`
- `shmBytes`
- `totalBytes`
- `queueMetrics`
- `health`

`queueMetrics` fields:
- `queueDepth`
- `lastBatchSize`
- `maxBatchSize`
- `batchesProcessed`
- `operationsProcessed`
- `retryAttempts`
- `failedBatches`
- `averageBatchSize`
- `averageWriteMs`
- `lastBatchWriteMs`
- `lastFailureReason`
- `lastFailureAtMs`

`health` fields:
- `status`
- `reason`
- `lastFailureAtMs`

## `PopulationDiagnosticsView`

This snapshot combines owner/claim readiness, committed and pending capacity, provider lookup behavior, and startup reconciliation progress.

### `readiness`

- `ownerGlobal`
- `ownerPerWorld`
- `claimOccupancy`

Values are strings so newer runtimes can add states compatibly. Current owner readiness includes `LOADING`, `RECONCILING`, `READY`, `DEGRADED`, and `UNAVAILABLE`; claim occupancy reports its corresponding runtime state. A positive capped admission is not authoritative until its relevant dimension is `READY`.

### `counts`

- `trackedProfiles`
- `committedOwnerProfiles`
- `pendingOwnerSlots`
- `committedClaimProfiles`
- `pendingClaimSlots`
- `overCapOwnerBuckets`
- `observedOverCapClaimBuckets`

Unknown values use `-1`. Owner profiles include every canonical non-null owner across active and dormant lifecycle states. Claim profiles include owned `ACTIVE` and durably `UNLOADED` physical occupancy only.

### `ownerReservations` and `claimReservations`

- `created`
- `committed`
- `canceled`
- `expired`
- `invalidated`

Owner invalidation is currently reported as zero; claim reservations can be invalidated when settings, provider generation, or topology no longer matches their preparation context.

### `claimLookups`

- `sessions`
- `requests`
- `uniqueChunks`
- `providerCalls`
- `cacheHits`
- `providerStateChanges`
- `snapshotCount`
- `totalSnapshotNanos`
- `lastSnapshotNanos`
- `lastProviderCallNanos`
- `targetedRefreshCount`
- `totalTargetedRefreshNanos`
- `lastTargetedRefreshNanos`
- `provider`

Snapshot timing covers population snapshot construction. Targeted-refresh timing is separate and
covers apply-time provider/topology refreshes used to validate a prepared reservation immediately
before mutation.

The `provider` context reflects the current operation policy even before the first lookup. It exposes:

- `requestedProvider` / `resolvedProvider`
- `providerId`
- `state` and `reason`
- `pluginVersion`
- `generationToken`
- `settingsRevision`

This makes `Auto` behavior and installed-but-broken providers visible. Treat `generationToken` as opaque diagnostic text.

### `activeRules`

- `operation`
- `ownerLimit` / `ownerScope`
- `claimLimitPerChunk` / `claimLimitTotal`
- `requireClaim`

These are the effective rule values for the operation represented by the snapshot (currently
`BREEDING`), after master-switch and operation activation logic has been applied. Inactive claim
rules therefore appear as zero/false instead of echoing dormant configured values. Older callers
can continue using the original `PopulationDiagnosticsView` and `LookupMetricsView` constructors;
the added values default to unknown/zero for those constructors.

### `reconciliation`

- `state` and `reason`
- `scannedUnits` / `totalUnits`
- `profileCount`
- `duplicateObservations`
- `recoveredOperations`
- `canceledOperations`
- `startedAtMs` / `completedAtMs`

Reconciliation is bounded and resumable. A `RECONCILING` or `DEGRADED` snapshot explains why positive population admissions are failing closed rather than reporting an unsafe zero count.

## Notes
- Intended for tooling/admin diagnostics, not gameplay rules.
- Snapshot values are point-in-time and may change rapidly while writes are active.
- Population diagnostics are read-only. They do not reserve capacity or repair ambiguous evidence.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [In-Game API Self-Test Smoke Recipe](/mod/alecs-tamework/in-game-api-self-test-smoke-recipe)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)



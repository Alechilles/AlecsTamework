---
title: "Diagnostics API Reference"
order: 13
published: true
draft: false
---
# Diagnostics API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This reference tracks the current `diagnostics()` contract in `TameworkApi`.

Capabilities: `DIAGNOSTICS`, with the additive `PERSISTENCE_RESILIENCE`
contract introduced in API `0.8.0` and retained in `0.9.0`.

## Entry Point
`TameworkApi.diagnostics() -> DiagnosticsApi`

## Methods
- `PersistenceDiagnosticsView getPersistenceDiagnostics()`
- `PopulationDiagnosticsView getPopulationDiagnostics()`
- `PersistenceResilienceView getPersistenceResilience()`
- `PersistenceMutationAvailabilityView queryPersistenceAvailability(PersistenceMutationAvailabilityRequest request)`
- `Optional<PersistenceIncidentSummaryView> findPersistenceIncident(String incidentIdOrUniquePrefix)`

## Persistence resilience

`getPersistenceResilience()` is a process-local, read-only snapshot. It reports storage state and reason, the actionable storage incident id when present, active incident/quarantine totals, persisted feature-circuit states, and evidence-coverage states. Circuit entries contain `domain`, `enabled`, `reasonCode`, and `updatedAtMs`. Coverage entries contain `dimension`, `status`, `ready`, `reasonCode`, `generation`, `updatedAtMs`, `coveredScopeCount`, `absenceAuthoritative`, and `nextSafeTrigger`.

`queryPersistenceAvailability(...)` applies the same fail-closed storage, exact-scope quarantine, circuit, and evidence gate used by Tamework. The request is value-only: domain, operation kind, public scope references, required evidence dimensions, mutation direction, optional correlation ids, and whether a source or live projection may already exist. It does not reserve capacity, consume a source, clear an incident, or mutate canonical state. The response contains `status`, stable `reasonCode`, and an optional short incident id.

`findPersistenceIncident(...)` accepts an exact incident id or a unique prefix and returns a bounded, sanitized occurrence. Scope keys are never exposed; only installation-local scope hashes and authority dimensions are returned. This method may read SQLite and must not be called from a world tick callback.

Older implementations retain binary compatibility through default methods: the resilience snapshot and availability query fail closed, and incident lookup returns empty.

## API 0.9 and schema-v8 diagnostics

The bounded persistence snapshot and integrity audit include operation rows for
capture policy, bonded vessels, population groups, and companion provisioning.
Integrity checks detect duplicate capture/provisioning origins, duplicate
active vessel profiles, duplicate nonterminal vessel generations, and duplicate
nonterminal population-group operations for one profile.

This persistence visibility does not advertise a gameplay capability. A schema
table or diagnostic row may exist while its feature authority remains
recovering or unavailable. Check `getCapabilities()` and the feature-specific
readiness/result before mutation.

Operator commands remain:

- `/tw diagnose`
- `/tw diagnose population`
- `/tw diagnose vessel <binding-or-profile>`
- `/tw diagnose provisioning <caller-namespace> <idempotency-key>`
- `/tw debugdb health`
- `/tw debugdb incidents [open|all]`
- `/tw debugdb incident <incident-id-or-unique-prefix>`
- `/tw debugdb retry <incident-id>`
- `/tw debugdb integrity`
- `/tw debugdb export [recent|incident <incident-id>]`

`retry` requests an evidence verifier; it cannot force-clear a quarantine or
manufacture missing source evidence. Support exports are redacted and do not
include the SQLite database or complete Hytale save.

`/tw diagnose` prints a bounded summary of API capabilities, capture recovery,
bonded-vessel/group/provisioning readiness and operation counts, persistence
incidents/quarantines, and relevant evidence coverage. The focused population,
vessel, and provisioning forms provide sanitized correlation and recovery
details without exposing raw item JSON or owner UUIDs. All diagnose forms are
read-only; they do not mutate, repair, or retry an operation.

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
- `PERSISTENCE_RESILIENCE` means the additive read-only contract is implemented. It does not authorize integrations to bypass Tamework admission or recovery.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [In-Game API Self-Test Smoke Recipe](/mod/alecs-tamework/in-game-api-self-test-smoke-recipe)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)



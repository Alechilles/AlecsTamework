# ADR 0001: Persistence Replacement Boundaries

- Status: Accepted
- Date: 2026-07-23

## Context

Tamework's unreleased persistence implementation grew from a public seven-table schema into
50 tables and many overlapping repositories, queues, recovery paths, and lifecycle authorities.
Because releases after June 30 were tester-only, preserving the unreleased v5-v9 lineage would
perpetuate complexity without protecting a public compatibility promise.

## Decision

The replacement persistence system follows these non-negotiable boundaries:

1. Use a fresh `tamework-state.sqlite` database whose schema begins at version 1.
2. Import public v2-v4 databases through a read-only, restartable importer.
3. Refuse unreleased v5-v9 databases without modifying them. Testers restore a public backup or
   create a new world.
4. Never dual-write the old and replacement databases.
5. Cut over complete feature slices; no feature may mix old and replacement persistence at
   runtime.
6. Keep domain contracts in `persistence.kernel`, `persistence.operation`,
   `persistence.projection`, and `persistence.control`. SQLite details stay behind
   `persistence.adapter.sqlite`.
7. Represent reads as `Found`, `Absent`, or `Failed`. Storage and decode failures are never
   successful absence.
8. Execute one logical operation in one explicit database transaction. Unrelated operations are
   not implicitly batched.
9. Treat zero as a valid revision and generation. For world timestamps, zero alone is unset and
   negative values are valid.
10. Remove the superseded runtime after all feature slices pass their replacement gates.

## Enforcement

- `PersistenceConsolidationInventoryGuardTest` makes legacy dependencies and size decrease-only.
- `PersistenceConsolidationInvariantManifestTest` requires named replacement evidence for every
  hard-won invariant.
- `LegacyPersistenceFixtureTest` fixes the accepted public input and refused development formats.
- The phase gates in the persistence consolidation plan require the full suite before old code is
  deleted and again before release.

## Consequences

The refactor accepts a deliberate internal compatibility break for unreleased builds. In return,
Tamework gets one persistence lineage, one transaction model, explicit failure semantics, and a
bounded path for deleting rather than wrapping the accumulated runtime.

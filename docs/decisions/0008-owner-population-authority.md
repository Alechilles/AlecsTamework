# ADR 0008: Owner Population Authority and Admission

- Status: Accepted for Phase 5A implementation
- Date: 2026-07-23

## Context

The unreleased July implementation gave owner population its own lifecycle row,
operation phases, repository graph, readiness state, and publication callbacks.
That preserved valuable admission and reconciliation invariants, but made the
population subsystem a second authority over the same companion.

Tamework also supports global and per-world owner limits. A dormant owned
companion must retain its authoritative owner-world bucket even when it has no
physical location, so the existing lifecycle `worldKey` cannot represent both
physical location and owner-population scope.

## Decision

`companion_lifecycle` remains the only committed owner and lifecycle authority.
It gains an `ownerWorldKey` value that is independent of the physical
`LifecycleLocation`:

- owned profiles retain `ownerWorldKey` through capture, coop, death, lost,
  roster storage, and provisioning;
- active projection location still uses the lifecycle location's world key;
- an admitted rehome may revision-fence and change `ownerWorldKey`;
- an owned profile with unknown owner-world evidence is not silently omitted
  from capacity decisions; it remains unreadiness or quarantine evidence.

Committed population counts are SQL queries and rebuildable projections of
canonical lifecycle rows. There is no replacement
`companion_population_state` table.

## Durable reservations

Capacity-increasing work attaches typed population reservations to its existing
shared operation envelope. A reservation records:

- operation and profile IDs;
- expected lifecycle revision;
- `GLOBAL` or `PER_WORLD` owner scope and normalized scope key;
- positive capacity delta and snapshotted limit;
- creation time.

The reservation has no phase column. Its phase and recovery state are the
operation envelope's shared phase. Feature operations do not create nested
population operations or a population-specific recovery queue.

The single writer prepares an envelope and all required reservations in one
transaction. Admission counts canonical committed rows plus positive
reservations belonging to nonterminal envelopes. A pending negative transition
never releases capacity before its canonical commit. This makes concurrent
over-admission impossible without relying on a pre-commit cache update.

The public population API may expose a population transition as its own
registered operation kind, but it uses the same reservation participant,
envelope, recovery registry, readiness graph, and shutdown tracking as every
other feature.

## Finalization and projections

Durable finalization:

1. verifies the exact prepared reservation and source lifecycle revision;
2. commits the lifecycle/owner/owner-world transition;
3. records shared operation completion evidence;
4. appends a self-contained lifecycle projection event;
5. retires the reservation.

All five changes share one transaction. Projection consumers run only after
commit. The owner population index derives committed counts solely from
lifecycle projection events and can be rebuilt from a canonical lifecycle read.
Reservations may be shown separately as pending headroom, but never become a
second committed-count authority.

## Reconciliation evidence

World reconciliation uses durable evidence batches and observations rather than
JSON target context. Each batch is boot- and generation-scoped and has an
explicit `OPEN`, `SEALED`, or `FAILED` result.

Absence is actionable only when every required disk and live source for the
relevant world is sealed at the same generation. Incomplete, failed, stale, or
mixed-generation evidence cannot prove absence. Positive exact evidence may
advance reconciliation independently.

Contradictions are quarantined at the narrowest proven scope. When the affected
profiles cannot be separated safely for an owner-capacity decision, the owner
scope is quarantined through the shared incident and quarantine stores; there
is no population-specific health system.

## Schema lineage

The replacement lineage has never shipped, so Phase 5 amends fresh schema v1
and its hash in place. It does not create a migration from an earlier
replacement development schema. Released public v2-v4 data continues to import
through the existing read-only importer, while development v5-v9 databases
remain rejected.

## Consequences

- Owner capacity has one committed source of truth.
- Every feature shares one admission participant instead of manually joining
  population callbacks.
- Restart recovery finds reservations through the same operation registry.
- Per-world dormant ownership is explicit rather than inferred from a physical
  location or metadata hint.
- Projection equivalence and sealed-absence rules are independently testable.

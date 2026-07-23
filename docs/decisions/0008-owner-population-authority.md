# ADR 0008: Owner Population Authority and Admission

- Status: Implemented
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

Fresh schema v1 stores this evidence in `owner_population_reservation`. Its
primary key is the owning operation plus normalized owner scope. The row has
foreign keys to the shared envelope and canonical profile, and deliberately has
no phase, lease, attempt, failure, or recovery columns.

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

## Implementation evidence

Phase 5A is implemented without copying the unreleased population runtime:

- `companion_lifecycle` is the only committed owner and owner-world row;
- `OwnerPopulationProjectionIndex` rebuilds from canonical lifecycle and consumes
  the same self-contained lifecycle events emitted by every lifecycle-changing
  operation;
- `owner_population_reservation` is an envelope-owned preparation participant
  with no phase, lease, attempt, readiness, or recovery state;
- `population_evidence_batch` and `population_evidence_observation` contain only
  source results and exact observations;
- `owner_population_transition` and `owner_population_reconciliation` are
  registry-owned shared operations with the normal recovery and publication
  paths;
- a matching exact positive observation advances only the canonical lifecycle
  reconciliation generation;
- sealed absence and owner contradictions retain the canonical population count
  and write the existing incident, profile quarantine, and affected owner
  quarantine records;
- same-generation evidence that arrives after an earlier positive observation
  can still contain a contradiction, while older or duplicate matching evidence
  is stale.

The July invariants were retained by behavior rather than by repository shape:

| Invariant | Replacement proof |
| --- | --- |
| Dormant owned profiles count globally and per owner world | canonical store and projection-index tests |
| Concurrent requests cannot over-admit | serialized transition-operation test |
| Preparation and finalization are exact and idempotent | reservation participant and transition-operation tests |
| Restart recovery owns pending work | registry-routed recovery tests |
| Process crashes do not duplicate or lose admission | three-boundary forked-JVM crash test |
| Failed, open, stale, or mixed evidence cannot prove absence | sealed-evidence store tests |
| Conflicts do not silently choose an owner or free capacity | reconciliation containment tests |
| A bounded conflict blocks the affected owner, not global persistence | owner-scope quarantine test |

The focused Phase 5A aggregate contains 37 passing tests, including three
forked-JVM crash boundaries. The final full-suite and architecture gates remain
part of Phase 8.

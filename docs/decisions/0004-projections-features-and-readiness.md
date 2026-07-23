# ADR 0004: Projections, Feature Descriptors, and Readiness

- Status: Accepted
- Date: 2026-07-23

## Projection ordering

Canonical mutations append projection events in the same SQLite transaction. Events use one
monotonic sequence and carry operation ID, event type, aggregate ID, aggregate revision, payload
version, payload, and creation time.

Consumers:

- receive only committed events;
- acknowledge a durable sequence after idempotent application;
- ignore an already-applied aggregate revision;
- leave failed events pending for replay;
- rebuild from canonical state before outbox compaction is enabled.

No transaction callback mutates a gameplay cache. Publication always happens after commit.

## Feature descriptors

Every persistence-affecting feature has one static descriptor. It declares:

- feature ID and persistence domain;
- owned canonical/detail tables and adapters;
- every operation definition;
- operation scope extraction;
- startup dependencies and canonical loader;
- projection consumers;
- recovery handler and readiness evidence;
- circuit policy and narrowest quarantine scope;
- shutdown participant;
- metrics and diagnostics.

Descriptors are ordinary immutable Java composition, not reflection or a plugin system. A feature
cannot be mutation-ready while any required descriptor capability is missing. The descriptor
registry is the only catalog used by startup, recovery, containment, shutdown, and diagnostics.

## Startup and readiness

Startup is one dependency graph:

```text
classify/open -> validate schema -> load canonical state -> recover operations
-> catch up projections -> load feature detail -> await world evidence
-> reconcile -> read ready -> mutation ready
```

Readiness is derived from completed graph nodes and control-plane state. Features do not keep
independent readiness booleans. The shared levels are:

- `CLOSED`
- `CANONICAL_READ_ONLY`
- `RECOVERING`
- `PROJECTION_READY`
- `WORLD_EVIDENCE_PENDING`
- `MUTATION_READY`
- `GLOBAL_READ_ONLY`

Scoped quarantine is evaluated alongside readiness and does not create another global level.

## Consequences

The same registration makes forgotten recovery and shutdown wiring structurally impossible.
Projection replay replaces pre-commit callbacks, and one readiness graph replaces the July
collection of feature-specific gates.

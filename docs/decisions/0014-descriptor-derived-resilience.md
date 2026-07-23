# ADR 0014: Descriptor-Derived Resilience and Diagnostics

- Status: Accepted for Phase 5G implementation
- Date: 2026-07-23

## Context

The replacement already has:

- one static feature descriptor registry;
- one startup/readiness and operation-admission coordinator;
- durable `persistence_incident`, `persistence_quarantine`, and
  `feature_circuit` tables;
- shared operation recovery and unknown-outcome containment;
- passive kernel metric hooks.

The pieces are not yet one runtime control plane. A circuit row does not affect
admission, a quarantine created after startup is not reflected in the
process-local gate until restart, and an unbounded writer/readback failure does
not force the running replacement into global read-only. The old v7-v9
resilience packages contain useful vocabulary, but they also carry a separate
feature catalog, repositories tied to the old write queue, and duplicated
availability state.

## Decision

### The feature registry remains the only catalog

Every health, circuit, readiness, recovery, shutdown, metric, and diagnostic
view iterates `PersistenceFeatureRegistry`. No separate list of supported
features, domains, operation kinds, or metrics namespaces is introduced.

Registry construction rejects duplicate feature hooks and metrics namespaces.
Recovery and projection registries continue to prove exact operation and
consumer coverage against the same descriptors.

### Reuse the existing control tables

Phase 5G adds no table. Startup synchronizes `feature_circuit` with the exact
descriptor set:

- a missing registered feature receives a default `CLOSED` row;
- an existing row retains its durable state and failure evidence;
- an unknown feature row is a schema/control-plane mismatch and startup fails
  globally read-only;
- circuit rows never become a second operation or lifecycle authority.

`persistence_incident` remains immutable failure evidence, and
`persistence_quarantine` remains the exact durable mutation fence linked to an
open incident.

### One live admission coordinator

The startup coordinator also owns the in-process circuit and quarantine view.
It is seeded from canonical startup evidence and durable circuit rows.

An operation unknown outcome publishes its exact scopes to the coordinator only
after the incident and quarantines commit or exact readback proves that commit.
The same process therefore denies overlapping mutations immediately; unrelated
scopes remain available.

An `OPEN` or `HALF_OPEN` bounded feature circuit blocks that feature and every
descriptor that depends on it. Core identity and lifecycle use
`GLOBAL_FAIL_CLOSED`; opening either moves the whole runtime to global
read-only. Circuit state is never inferred from a missing row.

### Unbounded storage ambiguity is global

The kernel reports typed writer and read outcomes to the same control plane.
These outcomes enter global read-only:

- an unresolved transaction outcome;
- failed exact commit readback;
- corruption, schema, I/O, or storage-unavailable evidence;
- an unscoped read failure whose safety cannot be bounded.

Busy/retryable outcomes do not open a global failure mode. Bounded domain
conflicts and positively contained live ambiguity remain scoped.

When storage itself is unavailable, global read-only is authoritative
process state; the runtime does not pretend it durably recorded an incident it
could not safely write.

### Diagnostics are snapshots, not another state machine

The public diagnostics snapshot joins:

- descriptor metadata and metrics namespace;
- graph-derived readiness;
- durable circuit and quarantine state;
- operation counts by shared phase and descriptor;
- outbox head and consumer checkpoints;
- open incidents;
- passive writer/read counters and the last global failure.

It exposes codes and counts, not raw operation payloads or player metadata.
Shutdown reports descriptor coverage from the same registry and drains the one
shared workflow/kernel path.

## Consequences

- Resilience coverage cannot lag when a feature is added: an incomplete
  descriptor, recovery route, circuit row, or diagnostic entry fails an exact
  registry-derived test.
- A durable bounded quarantine affects the current process immediately and
  survives restart.
- Storage ambiguity has one conservative global response.
- No v7-v9 feature circuit catalog, availability service, recovery repository,
  or diagnostics database reader is ported into the replacement.
- The fresh schema remains at 29 tables.

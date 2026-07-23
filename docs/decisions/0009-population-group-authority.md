# ADR 0009: Population Group Assignment and Admission

- Status: Implemented
- Date: 2026-07-23

## Context

The unreleased population-group implementation introduced its own classification
statuses, operation state machine, count evidence lifecycle, recovery catalog,
event receipt store, runtime index publication callbacks, and readiness path.
Its important behavior is smaller:

- a role deterministically maps to zero or more winning group policies;
- owned and active limits are all-or-none;
- admission includes committed membership plus positive pending work;
- a per-world group needs an authoritative owner-world bucket;
- restart recovery must use the exact policy and source revisions accepted before
  the crash.

Group assignment explains how a profile is classified. It must not become a
second owner or lifecycle authority.

## Decision

`companion_profile` remains the role authority and `companion_lifecycle` remains
the owner, owner-world, and lifecycle authority.

Population-group detail is normalized into:

- one `population_group_classification` row per classified profile, carrying the
  exact role, policy revision, source metadata revision, source lifecycle
  revision, and monotonically increasing assignment revision;
- zero or more `population_group_membership` rows beneath that classification,
  carrying only group ID and `GLOBAL` or `PER_WORLD` scope;
- positive `population_group_reservation` rows owned by a shared operation
  envelope.

Membership rows never copy owner, owner-world, lifecycle state, or committed
counts. Committed group counts join membership to the current canonical
lifecycle. A released or unowned profile therefore stops consuming a group
bucket without a separate group cleanup mutation.

## Classification

The existing asset compiler remains responsible for deterministic policy
selection:

- higher priority wins duplicate logical group IDs;
- asset ID order breaks equal-priority ties;
- role matching is exact and case-sensitive;
- group IDs are sorted and unique;
- zero limits mean unlimited;
- admin force policy does not bypass group limits.

The persistence request carries the resulting sorted policy snapshot. Preparation
verifies its exact canonical role, metadata revision, lifecycle revision, owner,
and owner-world evidence. Durable work replaces the classification and complete
membership set with one compare-and-set assignment revision.

A profile with no matching groups still receives a classification row. Absence
of membership therefore cannot be confused with classification that never ran.

## Admission

The shared operation preparation transaction derives positive owned and active
deltas by comparing current membership with the requested policy snapshot.
Each positive bucket becomes one `population_group_reservation`:

- operation and profile ID;
- expected lifecycle revision;
- owner, group, normalized scope, and owner-world bucket;
- positive owned and/or active delta;
- snapshotted owned and active limits;
- policy revision and creation time.

The row has no phase, lease, attempt, retry, failure, readiness, or recovery
columns. Pending status comes only from the owning shared envelope.

Admission counts current canonical lifecycle membership plus positive
reservations on nonterminal envelopes. Negative deltas never release headroom
before durable commit. Assignment replacement, outcome evidence, outbox insert,
and reservation retirement commit in the same transaction.

## Projection and lag

`PopulationGroupProjectionIndex` is rebuildable from classifications,
memberships, and canonical lifecycles. It consumes:

- self-contained group-assignment events;
- canonical lifecycle events for owner, owner-world, and active/owned state;
- metadata profile events for role and metadata-revision correlation.

The index never publishes before commit. Its shared projection checkpoint is the
readiness evidence. Source revisions on classification rows make stale role or
impossible lifecycle ordering detectable without adding classification status
or readiness columns.

A membership may remain stored after a lifecycle transition because the group
classification is still valid. Counts always use the latest lifecycle event or
canonical join. A role mismatch, missing lifecycle, missing per-world owner
bucket, or an assignment that refers to a future source revision is lag and
blocks group mutation readiness through the descriptor DAG.

## Recovery and containment

`population_group_assignment` is a registered database-only operation. Startup
decodes its typed policy snapshot and re-enters the same assignment adapter.
There is no group recovery catalog or group operation table.

Source revision, assignment revision, membership, and reservations provide exact
readback. Ambiguous or contradictory scope is contained through the shared
incident/quarantine stores at operation, profile, or owner scope.

## Consequences

- Group assignment is detail, not owner/lifecycle authority.
- Counts cannot drift because of copied owner or lifecycle columns.
- Empty classifications are explicit.
- Limits compose with every future feature through one reusable preparation
  participant.
- Recovery and readiness reuse the shared registries.
- The unreleased classification statuses, group operation phases, count-evidence
  states, receipt table, recovery service, and publication callbacks are not
  ported.

## Implementation evidence

- Fresh schema v1 owns only classification, normalized membership, and
  shared-envelope reservation tables for this feature.
- One typed `population_group_assignment` operation verifies exact profile,
  lifecycle, owner, owner-world, assignment, and policy revisions.
- Serialized preparation counts current canonical lifecycle membership plus
  positive reservations and prevents two profiles from consuming one remaining
  group slot.
- Assignment replacement, one self-contained outbox event, operation outcome,
  and reservation retirement commit in the same transaction.
- The rebuildable group index consumes assignment, canonical lifecycle, and
  metadata events; stale roles, impossible lifecycle ordering, and a missing
  owner-world bucket are explicit lag.
- An unowned classification is valid and consumes no per-world bucket; lag is
  raised only when a canonical owner exists without its required owner world.
- Startup recovery decodes the durable policy snapshot and re-enters the same
  assignment adapter through the shared recovery registry.
- The focused Phase 5B gate passes 21 tests covering planning, codecs, normalized
  SQLite storage, projection replay/rebuild, limits, concurrency, recovery,
  public composition, and descriptor-derived readiness.

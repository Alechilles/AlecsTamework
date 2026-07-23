# ADR 0012: Companion Provisioning Authority

- Status: Accepted for Phase 5E implementation
- Date: 2026-07-23

## Context

The unreleased provisioning implementation spreads one behavior across:

- a 906-line coordinator with ten private provisioning states;
- a 486-line population backend;
- a 730-line Hytale projection adapter;
- separate provisioning-operation and command-link-intent tables;
- owner-population, group-population, spawn, timed-summon, and command-roster
  operation chains;
- process-local preparation and resumption maps.

That machinery exists mainly to make dormant profile creation look like an
external live mutation. It is not one. A dormant grant changes only canonical
SQLite authorities. The replacement writer already serializes those mutations,
and the shared operation envelope already owns idempotency, recovery, failure
classification, publication, and scope fencing.

Provisioning is unreleased. Its v8/v9 journal states and table layouts are not
compatibility requirements.

## Decision

### One immutable provenance record

`provisioning_record` is the only provisioning-specific table. It is keyed by
stable profile ID and stores:

- caller namespace and caller idempotency key;
- optional diagnostic correlation ID;
- the group-policy revision frozen when the profile was granted;
- the shared operation ID that created the record;
- the signed creation timestamp.

The caller origin is unique. A deterministic profile ID and opaque provisioning
location key are derived from the length-delimited caller origin, so an
idempotent retry addresses the same payload even when it supplies a new shared
operation ID.

The record is immutable and retained as provenance after activation, roster
storage, death, revival, or release. It does not copy owner, role, lifecycle,
location, command family, slot, group membership, alias, projection status,
operation phase, recovery status, readiness, or failure reason.

### Dormant creation is one database-only operation

`companion_provisioning` atomically commits:

- stable profile identity and metadata;
- canonical `PROVISIONED_DORMANT` lifecycle at `PROVISIONING`;
- the immutable provisioning record;
- complete population-group classification;
- optional command-family roster membership;
- canonical projection events;
- retirement of exact owner and group-owned reservations;
- the shared operation's durable evidence.

Preparation reserves one owner slot in every configured owner scope and one
owned slot in every resolved group scope. It reserves zero active slots and no
physical claim. Stable profile IDs in reservation rows are planned identities,
so reservation foreign keys do not require a profile row before the durable
creation transaction. The operation envelope's exact profile and owner
participants remain the reservation fence.

The role resolver supplies the complete, snapshotted policy set. Callers cannot
supply arbitrary authoritative group IDs. An explicit empty resolved set is
valid; missing or unavailable role/policy authority fails before submission.

Optional roster membership is not a second intent or operation. The durable
transaction reads the current family revision under the one serialized writer,
inserts the deterministic free slot, and advances the family revision in the
same commit as the profile. A failure rolls back both.

### Optional live projection is a separate shared operation

`provisioning_activation` is the only provisioning live workflow. It:

1. validates the exact provisioning record, dormant lifecycle, assignment,
   group policy, and optional command membership/timed policy;
2. reserves only positive group-active capacity;
3. leases the target alias and fences canonical lifecycle;
4. invokes one idempotent world-thread spawn/receipt boundary;
5. atomically promotes the alias, commits `ACTIVE`, optionally creates the
   initial active timed lease, retires reservations, and emits outbox evidence.

An unavailable world is retryable and retains the exact shared envelope,
lifecycle fence, alias lease, and group reservations. An ambiguous live outcome
uses shared scoped containment.

Dormant creation remains successful if optional activation is denied before
preparation or cannot yet be requested. The caller may retry activation for the
same profile; it never re-runs profile creation. Once an activation envelope
exists, startup recovery re-enters the same registered adapter and exact live
boundary.

### Roster and timed-state semantics

An optional command membership makes a provisioned-dormant profile visible in
the roster, but it does not rewrite canonical lifecycle to
`ROSTER_STORED`. `PROVISIONED_DORMANT` records that the companion has never had
a live projection or stored live snapshot.

The first successful command-linked activation may create the active timed lease
in the same durable transaction. A later timed store installs the current
snapshot and moves canonical lifecycle to `ROSTER_STORED`. Consequently:

- a failed initial projection starts no lease;
- a dormant profile with no lease routes its first Summon through provisioning
  activation;
- subsequent Summon/Dismiss operations use the ordinary timed-summon workflow;
- no synthetic empty timed snapshot or intermediate roster-promotion operation
  is required.

### Death and revival

Provisioning provenance is not a second lifecycle. Death, lost handling, and
revival continue to mutate only canonical lifecycle, alias, snapshot, group,
economic, and timed authorities through their shared operations. The immutable
provisioning record merely proves that an unlinked profile is eligible for
configured revival. It survives those transitions unchanged.

### Projection, query, and diagnostics

A rebuildable provisioning projection joins provisioning records to canonical
identity and lifecycle. Missing identity/lifecycle, mismatched provisioning
location while dormant, or stale event revisions are explicit fail-closed lag.
Operation status and recovery phase come only from `operation_envelope`; there
is no provisioning status column, recovery scanner, readiness flag, or mutable
session cache.

## Consequences

- One focused table replaces two provisioning tables and a private journal.
- Two shared operation kinds replace ten provisioning phases plus chained
  population and command-link operations.
- Dormant profile, group classification, owner capacity, and command membership
  cannot tear across a crash.
- Failed optional projection preserves exactly one owned dormant profile and
  creates no timed lease.
- Provisioned death/revival eligibility no longer depends on a hidden command
  item or copied lifecycle state.
- The process-local provisioning journal, preparation maps, bespoke recovery
  loop, and separate command-link intent are not ported.

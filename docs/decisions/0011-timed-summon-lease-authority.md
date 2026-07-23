# ADR 0011: Timed Summon Lease Authority

- Status: Accepted for Phase 5D implementation
- Date: 2026-07-23

## Context

The unreleased timed-summon implementation spreads one behavior across a
1,269-line repository, an 859-line coordinator, a 613-line Hytale projection
adapter, and three feature tables:

- summon sessions;
- summon snapshots;
- summon operations.

It duplicates lifecycle with `RESTORING` and `STORING`, duplicates shared
operation phases, coordinates separate population operations, maintains a
mutable session cache, and can publish that cache from transaction-scoped work
before the outer transaction commits. It also clamps timestamps to nonnegative
values even though valid Hytale world timestamps can be negative.

The feature is unreleased. Its v9 table layout and transitional vocabulary are
not compatibility requirements.

## Decision

### One focused lease detail

`timed_summon_lease` is the only new timed-summon authority. It is keyed only by
stable profile ID and owns:

- optimistic lease revision;
- optional active summon-session ID;
- optional nonnegative remaining duration;
- optional signed cooldown-until timestamp;
- snapshotted config identity and revision;
- active duration, cooldown duration, logout policy, and warning thresholds;
- warning thresholds already emitted for the current session;
- optional signed checkpoint timestamp;
- signed creation and update timestamps.

It does not store owner, command family, slot, role, alias, lifecycle state,
location, active count, operation phase, retry state, recovery lease, or
readiness. Those remain in their existing canonical authorities.

A session ID is present only while canonical lifecycle is `ACTIVE` or
`UNLOADED`. A null remaining duration means unlimited only when a session ID is
present and the snapshotted active duration is zero. A roster-stored lease has
no session, remaining duration, checkpoint, or emitted-warning receipts.

### Existing authorities remain authoritative

- `command_roster_membership` proves the exact family and slot.
- `companion_lifecycle` proves physical presence and owns the only state and
  location revision path.
- `companion_alias` owns the current or leased live NPC identity.
- the existing `companion_snapshot` authority stores the current
  `timed_summon` snapshot.
- population-group membership and reservations own active-cap admission.
- `operation_envelope` owns every prepare/live/retry/unknown/durable/published
  phase and recovery lease.

There is no timed-summon operation, snapshot, session-state, or recovery table.

### Two operation kinds

`timed_summon_lease_mutation` is database-only. It supports exact:

- initial stored or already-active registration;
- stored policy refresh;
- active-session checkpoint with nonincreasing remaining time and a monotonic
  superset of warning receipts.

It cannot change lifecycle or manufacture a new active session for a
roster-stored profile.

`timed_summon_transition` is the sole external mutation workflow and has two
typed variants:

1. Summon
   - exact roster-stored lifecycle, slot, membership, lease, policy, current
     timed snapshot, group assignment, and cooldown evidence;
   - reserves only positive group admission through the shared population-group
     participant;
   - leases the target alias and fences canonical lifecycle during preparation;
   - invokes one idempotent world-thread spawn/receipt boundary;
   - atomically promotes the alias, retires the source snapshot, commits
     `ACTIVE`, starts one lease session, retires group reservations, and emits
     outbox evidence.
2. Store
   - exact active or unloaded lifecycle, current alias, slot, membership,
     session, remaining-time checkpoint, policy, and target snapshot evidence;
   - fences canonical lifecycle before destructive world work;
   - invokes one idempotent snapshot/despawn/receipt boundary;
   - atomically installs the current timed snapshot, retires the alias, commits
     `ROSTER_STORED`, clears the session, starts the signed cooldown, and emits
     outbox evidence.

The lifecycle operation fence advances once at preparation and once at durable
completion. `RESTORING` and `STORING` are not added to canonical lifecycle:
the shared envelope phase plus `active_operation_id` already owns that
intermediate state. Active capacity remains occupied during storage because the
canonical lifecycle stays active or unloaded until the durable commit.

### Time and warning semantics

Persisted world timestamps are signed. Cooldown absence is null, not a
positivity check. Cooldown is active exactly when `now < cooldownUntil`.
Overflowing positive duration addition saturates at `Long.MAX_VALUE`; negative
timestamps are never clamped.

Remaining duration is nonnegative elapsed duration, not an epoch timestamp.
Runtime countdown uses process-monotonic elapsed time. A canonical rebuild
starts a new process observation at the persisted remaining duration, so server
downtime does not decrement or replenish the lease. Shutdown, warning crossings,
availability boundaries, and normal periodic work may checkpoint through the
same lease-mutation operation.

Warning thresholds are snapshotted, positive, unique, descending, and below the
finite active duration. Emitted thresholds are durable session receipts.
Player notification occurs only from the committed projection change; a failed
transaction cannot consume a warning in memory.

### Projection, recovery, and late worlds

`TimedSummonProjectionIndex` rebuilds from lease rows, roster memberships, and
canonical lifecycles. It consumes self-contained lease, roster-membership, and
lifecycle events. It exposes inconsistent joins as fail-closed lag and never
serves a second lifecycle state.

Both normal execution and startup recovery use the same typed adapter and live
boundary. The boundary must prove the exact operation receipt; entity absence
alone is not completion. An unavailable destination/source world returns
retryable evidence, leaves the operation and lifecycle fence durable, blocks
new mutation for that profile, and can be resumed by the same recovery entry
after the world loads. Truly ambiguous effects use shared unknown containment.

### Cross-feature integration

Provisioning, tame-and-link capture, paid revival, death, and lost handling may
not update a lease independently. Their later replacement slices either:

- compose a focused lease participant into their existing shared transaction;
  or
- leave the lease inconsistent only as explicit projection lag that blocks
  readiness until the descriptor-owned reconciliation repairs it.

The final cutover gate requires all such paths to be atomic; lag is a migration
and failure detector, not normal runtime behavior.

## Consequences

- The timed feature adds one detail table instead of three.
- Two shared operation kinds replace seven feature operation kinds and a second
  phase vocabulary.
- Canonical lifecycle and population counts remain consistent across crashes.
- Server restart cannot silently replenish or consume a lease.
- Negative Hytale timestamps remain valid for checkpoint and cooldown logic.
- The old session cache, repository listeners, direct transaction callbacks,
  separate population operation chain, and custom recovery scanner are not
  ported.

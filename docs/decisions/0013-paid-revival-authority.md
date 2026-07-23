# ADR 0013: Paid Revival Authority

- Status: Accepted for Phase 5F implementation
- Date: 2026-07-23

## Context

The unreleased paid-revival implementation spreads one action across:

- a 747-line coordinator with nine private states;
- a 970-line repository;
- paid-revival operation, cost, reservation, refund, and apply-plan tables;
- a separate population admission operation;
- process-local activation handles and owner-join recovery;
- command-roster, timed-lease, death-snapshot, alias, and population writes
  duplicated around the feature journal.

Most of that state restates the shared operation phase, canonical lifecycle,
group capacity, command membership, timed lease, or refund delivery evidence.
It also makes a multi-item charge and a live restoration look like unrelated
workflows even though the player initiated one action.

Paid revival is unreleased. The v9 table layout and private state names are not
compatibility requirements.

## Decision

### One shared operation, no paid-revival journal

`paid_revival` is one typed shared operation. Its immutable payload freezes:

- owner, profile, command family, slot, and exact membership revision;
- the exact `DEAD_REVIVABLE` lifecycle and current death snapshot;
- the resolved group policy and active-capacity transition;
- target alias, world, placement fingerprint, and spawn receipt;
- resolved config ID/revision and ordered multi-item cost;
- deterministic inventory source reservations and charge receipt;
- optional first active timed-lease snapshot;
- signed request timestamp.

The operation envelope owns idempotency, phase, recovery, participants,
containment, and terminal result. There is no `paid_revival_operation`, cost,
reservation, refund-status, or apply-plan table.

A quote is not durable authority. Runtime confirmation re-resolves the config,
inventory, placement, roster, lifecycle, and policy before submitting the
frozen payload. Duplicate caller confirmation addresses the same semantic
operation and cannot adopt a different quote, source plan, or target.

### Preparation composes existing authorities

The preparation transaction:

1. proves the exact current death snapshot and `DEAD_REVIVABLE` lifecycle;
2. proves the exact command-family membership;
3. reserves only positive group-active capacity;
4. leases the target alias;
5. advances canonical lifecycle once with the shared operation fence.

No owned-capacity reservation is added because revivable death remains owned.
The death snapshot remains current until positive live completion. A denial
before preparation creates no operation, charge, alias, or capacity
reservation.

### One typed composite live boundary

The live boundary owns the external portion as one idempotent action: exact
inventory charge plus restoration of the same profile. It resolves to one of
five typed outcomes:

- `CONFIRMED`: exact charge and spawn receipts are both positive;
- `NO_CHARGE`: no irreversible charge or spawn occurred;
- `REFUND_REQUIRED`: charge is positively proven and spawn absence is
  positively proven;
- `RETRYABLE`: replaying the same receipt-addressed action is safe;
- `UNKNOWN`: the result is ambiguous and must be contained.

The boundary may not report `NO_CHARGE` from absence alone and may not report
`REFUND_REQUIRED` without positive charge evidence. Inventory receipt
implementation is a runtime concern, but this persistence contract does not
accept a best-effort or process-local inference.

`NO_CHARGE` and `REFUND_REQUIRED` both use the shared `COMPENSATING` protocol.
The durable compensation code distinguishes them across restart. No-charge
compensation retires the alias and group reservation and restores dead
lifecycle without delivering items. Refund-required compensation first
creates the exact immutable refund claim, then invokes the idempotent refund
delivery boundary and records its positive receipt before the same canonical
cleanup.

### Generic refund claims are multi-item recipes

The existing generic `refund_claim` becomes a normalized recipe:

- one claim header per operation owns recipient, reason, receipt, timestamps,
  and delivery evidence;
- ordered `refund_claim_item` rows own exact item IDs and quantities;
- a claim must contain at least one item;
- claim creation and item insertion are atomic;
- delivery completion requires the exact claim receipt and positive external
  evidence.

Capture uses a one-line recipe. Paid revival uses its complete frozen cost.
This is one compensation authority shared by features, not a paid-revival
refund subsystem.

### Successful durable commit

After `CONFIRMED`, one transaction:

- revalidates the command membership, death snapshot, lifecycle fence, and
  immutable request;
- promotes the leased alias;
- retires the current death snapshot;
- commits canonical `ACTIVE` lifecycle;
- optionally creates the initial active timed lease;
- retires the exact group-active reservations;
- appends paid-revival, profile, lifecycle, and optional timed-lease events;
- commits the shared operation as durable.

Command membership remains the same row and provisioning provenance, when
present, remains unchanged. Publication and startup recovery use the existing
descriptor-derived projection and recovery registries.

## Consequences

- One shared operation replaces nine paid-revival states and five
  feature-specific tables.
- Charge, spawn, refund, lifecycle, alias, group, roster, and timed behavior
  have explicit single authorities.
- Multi-item compensation is reusable by capture and later economic features.
- A stale quote, stack, config revision, membership, snapshot, lifecycle, or
  capacity policy fails before charge.
- Restart converges to no charge/no revival, one charge/one revival, one exact
  refund, or bounded unknown containment; it cannot silently lose a proven
  charge.
- The runtime inventory implementation must supply durable receipt evidence;
  the persistence layer will not guess from missing stacks.

# ADR 0010: Command Family and Roster Authority

- Status: Accepted for Phase 5C implementation
- Date: 2026-07-23

## Context

The unreleased roster implementation stores a second role, profile revision, and
command lifecycle state on every membership. It also owns a feature-specific
operation receipt table, synchronous repository reads, revision-proof caches,
post-write listener lists, and direct update seams used independently by capture,
death, provisioning, timed summon, and revival repositories.

The intended behavior is smaller:

- an owner and stable command-family ID identify a durable roster;
- one profile has one unambiguous roster slot;
- membership preserves command preferences independently of physical access
  items;
- canonical profile metadata supplies role and presentation data;
- canonical lifecycle supplies owner, state, location, and transition revision;
- all action views are rebuildable projections;
- roster and lifecycle changes share the replacement operation envelope.

The feature is unreleased, so its v9 tables and public DTO implementation details
do not constrain the fresh schema.

## Decision

### Family and slot identity

`command_family` identifies one roster by `(owner_uuid, family_id)` and owns only
its optimistic roster revision and timestamps.

`command_roster_membership` owns one stable `slot_id` plus:

- profile ID;
- family owner and ID;
- membership revision;
- optional command group;
- bulk-command selection;
- optional home world and complete home coordinates;
- timestamps.

`profile_id` and `slot_id` are independently unique. A profile therefore cannot
occupy two families or slots at once. This intentionally chooses one
unambiguous command authority over the unreleased ability to create overlapping
family rows.

The canonical `COMMAND_ROSTER` lifecycle location key is the stable slot ID.
No delimiter-encoded owner/family/profile string is authoritative.

### Authority boundaries

Membership does not store role, profile revision, lifecycle state, NPC alias,
display name, owner-world, active count, summon state, or recovery phase.

- `companion_profile` remains metadata and role authority.
- `companion_lifecycle` remains owner, owner-world, state, location, and revision
  authority.
- population-group classification remains group-policy authority.
- timed leases, provisioning, and revival add their own detail in later slices
  but cannot redefine roster or lifecycle truth.

Roster reads and projections join these authorities. A metadata or lifecycle
change never requires rewriting a membership merely to refresh copied columns.

### Operations

Two typed operation kinds use the shared envelope:

1. `command_roster_membership`
   - create, update, move, or remove one slot;
   - exact family, membership, metadata, lifecycle, owner, and policy evidence;
   - removal is rejected while canonical lifecycle is roster-stored in that
     slot;
   - a move atomically advances both affected family revisions.
2. `command_roster_transition`
   - compare-and-transition canonical lifecycle for an existing exact slot;
   - permits only explicit command-compatible lifecycle pairs;
   - roster storage requires `ROSTER_STORED` plus that membership's slot;
   - a transition away from roster storage must preserve the same owner and
     membership.

The transition operation accepts the exact population-group policy snapshot used
for positive active admission. A reusable preparation participant validates it
against current group assignment and reserves only positive deltas. Later timed
summon, provisioning, capture-link, and revival operations reuse the same
participant rather than introducing group-specific extension callbacks.

There is no roster receipt, roster operation, command-state, or roster recovery
table. Idempotency, phase, lease, retry, failure, outcome, and recovery routing
belong to the shared envelope.

### Projections and lag

`CommandRosterProjectionIndex` rebuilds from families, memberships, canonical
profiles, and canonical lifecycles. It consumes:

- self-contained membership events;
- canonical lifecycle events;
- metadata and alias profile events.

The index publishes immutable owner/family views only after commit. Missing
profiles or lifecycles, owner mismatch, a roster-stored lifecycle pointing at a
different/missing slot, and impossible source revision ordering are explicit
lag. Item metadata and UI caches remain disposable projections.

### Cross-feature consistency

Death, lost, capture, coop, provisioning, timed summon, and revival may not
write roster tables independently. Their replacement operations either:

- leave durable membership intact and change only canonical lifecycle/detail; or
- include the focused roster participant in the same unit of work.

This preserves dead and lost roster rows for recovery while preventing
`CAPTURED`, `COOP`, or `ROSTER_STORED` from claiming conflicting physical
locations.

## Consequences

- One profile has one command slot and one canonical physical location.
- Roster rows survive loss or transfer of every access item.
- Role and lifecycle drift cannot hide behind copied membership columns.
- Group limits compose through one reservation participant and the shared
  envelope.
- Command actions use monotonic projection events rather than pre-commit cache
  mutation.
- The unreleased roster operation table, command-state column, revision-proof
  cache, repository listener list, and direct cross-repository update seams are
  not ported.

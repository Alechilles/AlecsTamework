# Owner population groups

Status: Proposed
Depends on: existing canonical owner/claim population admission and persistence
HyDragon counterparts: [Soul Bond and Miniwyvern](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/soul-bond-miniwyvern.md) and
[dragon content and encounters](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/dragon-content-encounters.md)

## Goal

Add config-defined companion groups with atomic per-owner owned and active
limits. HyDragon uses this to allow many owned full dragons but only one summoned
full dragon, and to permit only one Soul Bond Miniwyvern per player.

Group limits extend the existing canonical owner/claim admission transaction.
They are not command-item limits, live-entity scans, or an independent counter.
Every operation that can change owner, role/group classification, lifecycle, or
projection capacity must reserve all applicable constraints together.

## Fixed counting semantics

For a non-null owner, a profile consumes a group **owned** slot in every
lifecycle except `RELEASED`.

A profile consumes a group **active** slot exactly when its canonical lifecycle
is:

- `ACTIVE`;
- durably `UNLOADED`; or
- committed `RESTORING`.

A pending dormant-to-active or new-active reservation also consumes pending
active headroom. Consequently, chunk unload does not free an active slot, and
two concurrent restores cannot both pass a limit of one. `CAPTURED`, `COOP`,
`DEAD_REVIVABLE`, `LOST`, `UNKNOWN_DORMANT`, and the new
`PROVISIONED_DORMANT` do not consume active group capacity.

These predicates are safety invariants, not configurable lifecycle lists.

## Non-goals

- Replacing the global total-owner limit or physical claim-provider limit.
- Counting command links, items, live UUIDs, or currently loaded entities.
- Deleting or forcibly storing existing companions after a cap reduction.
- Letting API callers self-assert an unverified group ID.
- Hardcoding full-dragon or Miniwyvern roles in Tamework.

## Configuration

Add `TwPopulationGroupConfig` at:

`Server/Tamework/PopulationGroups/*.json`

Each asset defines one logical group and its role membership:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Enabled` | boolean `true` | Disabled assets do not participate in resolution. |
| `Priority` | integer `0` | Winner among assets with the same `GroupId`; higher wins. |
| `GroupId` | nonblank string | Stable logical identity persisted with canonical profiles/operations. |
| `RoleIds` | string array, empty | Exact canonical role IDs in this group; explicit arrays replace inherited arrays. |
| `Limits` | object | Per-owner limits and scope. |

`Limits` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `MaxOwnedPerOwner` | integer `0` | Maximum owned profiles in the group; `0` is unlimited. |
| `MaxActivePerOwner` | integer `0` | Maximum active profiles in the group; `0` is unlimited. |
| `Scope` | `Global` | `Global` or `PerWorld`; world bucket uses retained authoritative ownership world while dormant. |

Limits must be non-negative. Group IDs are case-sensitive stable data IDs and
must use a mod-owned prefix. For duplicate enabled assets with one `GroupId`,
higher priority wins; equal priority selects the case-insensitive
lexicographically smaller asset ID. An asset may not repeat a role ID.

A role may belong to multiple different groups. Every matched group is an AND
constraint: all groups, the global owner cap, and the destination claim cap must
admit the transition. Group IDs are sorted before reservation so lock/order and
diagnostic output are deterministic.

### HyDragon examples

```json
{
  "Enabled": true,
  "Priority": 100,
  "GroupId": "hydragon:full_dragons",
  "RoleIds": [
    "Tamed_NordicDrake",
    "Tamed_Hydra",
    "Tamed_RockDrakeT1",
    "Tamed_RockDrakeT2",
    "Tamed_RockDrakeT3"
  ],
  "Limits": {
    "MaxOwnedPerOwner": 0,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

The Rock Drake tamed role IDs above are proposed downstream assets; they do not
exist in the current HyDragon pack yet.

```json
{
  "Enabled": true,
  "Priority": 100,
  "GroupId": "hydragon:soulbound_mini",
  "RoleIds": [ "Tamed_Wyvern_Mini" ],
  "Limits": {
    "MaxOwnedPerOwner": 1,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

Miniwyvern creation remains Soul Bond-exclusive; the owned group limit is the
Tamework-side race-safe enforcement behind HyDragon's entitlement check.

## Inheritance, resolution, and reload

- Omitted top-level scalar/array/object values inherit from the parent.
- An explicit `Limits` object inherits missing nested fields.
- Explicit `RoleIds` replaces the parent array; roles never append or union.
- Explicit scalar zero is an authored unlimited value, not omission.
- The legacy two-argument fallback delegates to the nested-aware overload.
- Codec tooltips document all inheritance and `0 = unlimited` semantics.
- The family resolves by logical `GroupId` first, then builds an immutable
  role-to-group-set index from the winning definitions.
- Ordinary asset loaded/removed events validate and atomically swap the compiled
  index. `/tw reloadconfig` remains item-feature-only and does not directly
  reload this family.
- Add `POPULATION_GROUP` to internal/public config-family enums and emit
  `ConfigReloadedEvent` after a valid index swap.
- Ambiguous, invalid, or unresolved group mapping fails closed for affected
  positive admissions; an unsuccessful reload retains the last valid index.

## Durable classification

Dynamic config lookup alone is unsafe during restart, role change, or config
reload. Canonical population state therefore persists the sorted group IDs and
the config-classification revision used to derive them. Every population
operation records its old/new role, group set, owner, lifecycle, scope world,
and expected profile/population revisions.

On profile creation or first adoption, Tamework resolves groups from the target
canonical role before acquiring the population lock. On role change, it resolves
the target group set and performs old-group release plus new-group admission as
one operation. A crash replays the persisted old/new sets rather than consulting
a potentially changed live config.

Config changes schedule controlled reconciliation:

1. Build and validate a new immutable mapping revision.
2. Mark the affected classification coverage reconciling.
3. Backfill canonical group sets from durable profile roles.
4. Rebuild committed counts and reconcile pending operations.
5. Publish readiness only after coverage is authoritative.

Existing companions that become over cap remain intact. Counts report the
overage and future positive admissions are denied until capacity becomes
available. Config deletion never erases persisted classification while evidence
is incomplete.

## Admission model

Group counters live under the same lock-confined authority as
`OwnerPopulationIndex` and share the owner/claim
`CompanionPopulationAdmissionCoordinator` operation. Focused collaborators
should hold group policy/count logic rather than expanding the already-large
index or config classes.

For each `(owner, group, scope bucket)` maintain committed and pending owned and
active counts. Transition deltas include:

| Transition | Owned delta | Active delta |
| --- | ---: | ---: |
| New owned `ACTIVE` | `+1` | `+1` |
| New owned `CAPTURED` | `+1` | `0` |
| New owned `PROVISIONED_DORMANT` | `+1` | `0` |
| `CAPTURED/COOP/DEAD_REVIVABLE/LOST/UNKNOWN_DORMANT/PROVISIONED_DORMANT -> RESTORING/ACTIVE` | `0` | `+1` |
| `UNLOADED -> ACTIVE` | `0` | `0` |
| `ACTIVE -> UNLOADED` | `0` | `0` |
| `ACTIVE/UNLOADED/RESTORING -> CAPTURED/COOP/DEAD_REVIVABLE/LOST/PROVISIONED_DORMANT` | `0` | `-1` at durable commit |
| Owner transfer while active | `-1/+1` by owner | `-1/+1` by owner |
| Role/group change | old groups `-1`, new groups `+1` as applicable | same, atomically |
| Permanent release | `-1` | `-1` only if active-classified |

Preparation checks committed plus pending positive deltas and reserves every
constraint atomically. Claim-for-apply revalidates settings/provider/group
generations and expected revisions. Commit applies all deltas; cancel/expiry
releases all of them. A partial group reservation is never visible.

The existing `ADMIN_OVERRIDE` admission mode does not bypass population-group
owned or active limits. This is intentional: a generic admin path must not
silently defeat a group used as a uniqueness invariant such as Soul Bond.
Legacy/adoption reconciliation may preserve an already over-cap profile, but
creating or activating another positive delta remains denied until policy or
population changes through an explicit audited operation.

There is no public/internal `force=true` escape hatch for positive group deltas.
Admin commands may inspect, reconcile, release an existing profile, or apply an
explicit audited config/policy change, but `ADMIN_OVERRIDE`, creation commands,
revive commands, and direct API calls all remain subject to group caps. An
emergency administrative repair may preserve/reclassify already-authoritative
state; it cannot manufacture owned/active headroom or create another unique
companion.

`MaxActive` is separate from physical claim occupancy even though both count
`ACTIVE` and `UNLOADED`: group active additionally counts `RESTORING` and is
owner/group scoped, while claim limits are location/provider scoped.

## Mutation-path coverage

All paths in `docs/Claims-and-Owner-Population-Path-Matrix.md` must supply target
role/group classification and use the unified authority, including:

- tame/set-owner and first wild capture;
- legacy adoption and admin/API creation;
- disposable and bonded spawner restore/store;
- revive and lost recovery;
- managed coop capture/release;
- breeding and batch birth;
- owner transfer/clear;
- role transformation;
- relocation when scope is `PerWorld`;
- release, cull, and non-revivable death.

No internal repository write, public API path, command action, or external
integration may bypass group admission. Existing zero-delta transitions remain
available during degraded read authority only when their persisted
classification and exact identity prove that they do not consume new group
capacity.

## Generic companion provisioning

The current public `NpcProfilesApi` is read-only, so integrations cannot safely
create an owned canonical companion. Add capability
`COMPANION_PROVISIONING` and a default fail-closed
`TameworkApi.companionProvisioning()` accessor.

`CompanionProvisioningRequest` contains:

- stable caller namespace plus idempotency key and optional correlation ID;
- owner UUID;
- exact target role ID;
- desired initial disposition `PROVISIONED_DORMANT` or `ACTIVE`;
- retained authoritative ownership world (required when any group is
  `PerWorld`);
- destination world/location when `ACTIVE` is requested;
- optional initial display name/home context supported by canonical profiles;
- expected config/policy generation where supplied for diagnostics.

`callerNamespace` is required, nonblank, and part of the unique durable origin
with `idempotencyKey`; equal keys in different namespaces do not alias. An
optional correlation ID is diagnostic linkage only. A nonnegative expected
policy revision must match at preparation. The current-policy sentinel `-1`
resolves once and persists that revision in the operation, so retry/recovery
cannot silently adopt a later policy.

The caller does not supply authoritative group IDs, profile IDs, owner-count
deltas, or a live NPC UUID. Tamework resolves the role, group set, global owner
policy, and destination claim context.

Provisioning is a two-stage idempotent workflow:

1. Derive one provisional/canonical profile identity from the stable operation
   and prepare a dedicated internal `PROVISION_DORMANT` no-claim admission.
   Under the same owner/group lock and durable admission journal, it reserves
   global owner capacity and every positive group-owned delta but deliberately
   reserves no physical claim and no active delta. It requires retained
   ownership-world context and forbids a spawn destination. This is not the
   current V1 `NEW_OWNERSHIP` path, whose NPC/claim/destination requirements
   remain unchanged.
2. Commit exactly one canonical owned profile in lifecycle
   `PROVISIONED_DORMANT` and publish its profile ID. A retry with the same key
   returns it.
3. If `ACTIVE` was requested, prepare a normal dormant-to-`RESTORING` admission
   for that same profile, then project it through the standard planned-spawn
   pipeline.
4. If projection succeeds, commit `ACTIVE`. If placement, claim/group capacity,
   or world work fails, retain the one owned profile dormant/recoverable and
   return a partial-success status; do not delete/recreate it.

This separation prevents a failed optional projection from rolling back a
successfully claimed one-time companion or causing a retry to create another.
It also keeps group owned/active deltas in the same existing authority.

`PROVISION_DORMANT` claim-for-apply revalidates owner/group generations and
commits the canonical profile plus owned counters in one transaction. It never
calls a claim provider. Only stage 3 uses normal restore admission and reserves
destination claim plus active group capacity. Cancellation/expiry of stage 1
releases its owner/group reservation exactly once.

The no-claim operation kind is coordinator-internal and can be selected only by
`CompanionProvisioningApi` after validating the provisioning request. Public
`PopulationAdmissionRequest` V1 continues rejecting dormant provisioning and
continues requiring its NPC/claim context; V2 callers cannot select
`PROVISION_DORMANT` directly or use it as a generic owner-cap bypass.

Add `PROVISIONED_DORMANT` to internal `CompanionLifecycleState` and public
`PopulationCompanionLifecycle`. It means an intentionally created, owned,
non-physical profile with no vessel/coop/death/lost authority. It consumes an
owned slot, consumes no active or claim slot, and may transition only through
normal restore admission or permanent release. It is distinct from
`UNKNOWN_DORMANT`, which remains conservative unresolved evidence. Every
lifecycle switch, persistence codec, reconciliation classifier, public mapper,
and compatibility test handles the new value explicitly or uses a safe default
branch.

Proposed immutable results distinguish `PROVISIONED_ACTIVE`,
`PROVISIONED_DORMANT`, `PARTIAL_DORMANT`, `ALREADY_PROVISIONED`, `DENIED`,
`UNAVAILABLE`, and `QUARANTINED`, and include caller namespace/idempotency
origin, optional diagnostic correlation ID, operation/profile IDs, committed
lifecycle, profile revision, projection reason, and population decision. Add
post-commit
`CompanionProvisionedEvent`; it is a notification, not creation authority.

The durable operation view/query also represents nonterminal and partial
results. `findOperation(callerNamespace, idempotencyKey)` returns `PREPARING`,
`PREPARED`, `APPLYING`, `DORMANT_COMMITTED`, `PROJECTING`, `COMMITTED`,
`PARTIAL_DORMANT`, `CANCELED`, `TERMINAL_DENIED`, or `QUARANTINED` state with
the original origin/correlation and canonical profile once allocated. It does not
collapse `PARTIAL_DORMANT` to denial or not-found. Retry resumes optional
projection for that profile and never starts another dormant-create stage.

The API does not consume a caller's item or write its entitlement ledger.
HyDragon links the returned profile and consumes its Soul Bond idempotently
under the [integration contract](integration-contract.md). Tamework never uses
`ProfileDataApi` as canonical provisioning state.

Provisioned companions also qualify for death/revive without a command-tool
link. `CompanionRevivePolicy` treats an authoritative, non-released provisioning
record plus the effective role's enabled revive policy as a supported path.
Death commits `DEAD_REVIVABLE`, preserves the provisioning/profile identity and
owned group slot, and releases active/claim occupancy. Revive uses normal
active/group/claim admission and restores the same profile. Add canonical
`ProvisionedCompanionDeathRecordedEvent` and
`ProvisionedCompanionRevivedEvent`; they do not depend on nonempty `toolIds` or
the existing command-link-conditioned death event. This avoids requiring a
hidden command item merely to keep a Soul Bond companion recoverable.

## Public API and events

Add capabilities `POPULATION_GROUPS` and `COMPANION_PROVISIONING`.

The existing `PopulationAdmissionRequest` record is not modified. Introduce a
V2 request or an additive overload carrying `targetRoleId`; Tamework resolves
the authoritative group set and rejects a caller-provided set that does not
match. Existing V1 requests that could create/change an owned profile without a
known target role fail closed when group policy could apply; safe zero-delta
legacy operations retain compatibility.

The internal V2 operation kind includes `PROVISION_DORMANT`, available only
through the provisioning coordinator. It carries ownership-world context,
forbids a live NPC, destination, and claim token, and produces the same
journaled owner/group decision shape as other admissions. Neither V1 nor direct
public V2 callers may select it. Public callers also cannot select
`ADMIN_OVERRIDE` to bypass group limits.

A default fail-closed `PopulationGroupApi` exposes immutable:

- resolved group definitions by ID and role;
- committed/pending counts and limits by authorized owner/group/scope;
- classification/reconciliation readiness;
- group portions of a population admission decision.

It provides no increment/decrement or unjournaled mutation methods. Existing
`PopulationAdmissionApi` remains the mutation authority and automatically
enforces groups.

Expose the group reader through a default fail-closed
`PolicyApi.populationGroups()` method. New V2/default methods preserve linkage
for clients and external implementations compiled against API `0.8.0`.

Post-commit immutable events:

- `PopulationGroupMembershipChangedEvent` for durable role/classification
  change;
- `PopulationGroupLimitChangedEvent` after a valid config revision/reconcile;
- existing profile/lifecycle events remain informational and are not used as a
  second counter.

## Failure and recovery

- Loading, reconciling, degraded, unavailable, ambiguous membership, or missing
  target role denies positive group deltas.
- Denial leaves source item, NPC, owner, role, lifecycle, inventory, and all
  counters unchanged.
- Commit failure enters compensation before releasing a source owner/group
  classification; ambiguous rollback is quarantined.
- Start-watchdog expiry cancels every owner/claim/group reservation exactly once.
- Recovery uses persisted old/new group sets and operation IDs; it never
  re-resolves an in-flight operation against a new mapping revision.
- Existing over-cap rows are preserved and tagged; no reconciliation job
  deletes, stores, or transfers them to satisfy a cap.
- Group counters derive from canonical state and are rebuildable; cached counts
  are not an independent source of truth.

## Migration and diagnostics

Schema migration adds the `PROVISIONED_DORMANT` lifecycle, group classification
to canonical population state, and old/new operation payloads plus indexes
needed for owner/group/scope counts.
It also adds a provisioning-operation record with a unique
`(caller_namespace, idempotency_key)` constraint, provisional/canonical profile
identity, dormant/active stage, population operation correlations, result, and
recovery status.
Coordinate this as schema v8 with the other suite tables, or use one ordered
suite migration; do not give independent features conflicting schema numbers.

Backfill reads durable profile role and population lifecycle/owner/world.
Unknown roles or missing configs remain classified as unresolved and block
positive affected operations until repaired. All v7 rows and operations are
preserved, and migration is backup-first, transactional, idempotent, and
rollback-tested.

`/tw diagnose population` adds:

- winning group config IDs/revisions and mapping conflicts;
- committed and pending owned/active counts by owner/group/scope;
- unresolved/backfill/over-cap profile counts;
- oldest pending group reservation and operation correlation;
- readiness, quarantines, and persistence incident reason codes.

`/tw diagnose provisioning <caller-namespace> <idempotency-key>` reads the
durable provisioning operation by its full origin and reports nonterminal,
dormant-committed, projecting, partial-dormant, terminal, and recovery state,
including the canonical profile/revision once allocated. It is bounded and
read-only. `/tw api test` must leave one failed-projection fixture long enough
to prove the diagnostic returns that same `PARTIAL_DORMANT` profile after a
restart/recovery pass, then release it through the normal path.

Metrics expose aggregate counts/latencies only; no owner/profile identifiers.
Diagnostics are bounded and read-only unless an explicit existing repair command
is invoked.

## Implementation file map

| Area | Existing anchor | Proposed responsibility |
| --- | --- | --- |
| Asset family | new `config/assets/TwPopulationGroupConfig` and registry | Codec, inheritance, deterministic group/role index |
| Registration | `Tamework` asset-store setup/events; config override/UI registries | Path, store, reload event, editor/schema support |
| Group domain | new focused classes under `ownership/groups` | Policy resolver, immutable classification, keys/counts/constraints |
| Unified authority | `OwnerPopulationIndex`, `CompanionPopulationAdmissionCoordinator` | Reserve owner/claim/group constraints under one lock/operation |
| Requests/plans | owner transition drafts, spawn/breeding/coop/revive planners | Carry verified old/new roles and group sets |
| Provisioning | new focused coordinator/facade using planned spawn and owner admission | Idempotent dormant profile creation and optional projection |
| Persistence | `CompanionPopulationStateRecord`, population repository/bootstrap/operation records | Durable classification, migration, recovery/reconciliation |
| Public API | `PopulationAdmissionApi`, `PopulationGroupApi`, `CompanionProvisioningApi`, V2 request/decision views, `api/internal` | Target-role context, provisioning, group views, capabilities/events |
| Diagnostics | population metrics/diagnostics, commands, selftest | Counts, conflicts, readiness, fixtures |

`OwnerPopulationIndex` is already at its target size and `TwCompanionConfig` is
already oversized. Group behavior must be extracted rather than bolted into
either class. Update canonical wiki config/API pages, examples, config indexes,
CHANGELOG, generated agent index, and `/tw api test` in the implementation PR.

## Acceptance tests

### Config and resolution

1. Codec accepts valid zero/unlimited and positive limits and rejects negatives,
   blank group IDs, duplicate roles, and invalid scope.
2. Omitted fields inherit; a partial `Limits` object nested-inherits; explicit
   `RoleIds` replaces the parent array.
3. Duplicate logical group definitions resolve by priority then asset ID.
4. A role in several group IDs resolves all groups in deterministic order and
   all limits apply.
5. Disabled configs do not resolve; ambiguous/invalid mappings retain the last
   valid index and fail affected positive admissions closed.
6. Asset loaded/removed events publish `POPULATION_GROUP` only after a valid
   atomic index swap; `/tw reloadconfig` behavior remains truthful.

### Counting and concurrency

7. Owned counts every non-released lifecycle with a non-null owner.
8. Active counts committed `ACTIVE`, durable `UNLOADED`, and committed
   `RESTORING`, excluding all other lifecycles including
   `PROVISIONED_DORMANT`.
9. Pending dormant-to-active/new-active reservation consumes active headroom.
10. Two concurrent `CAPTURED -> ACTIVE` reservations at max one produce one
    winner, including across different vessels/items/world callbacks.
11. `UNLOADED -> ACTIVE` and `ACTIVE -> UNLOADED` are zero active delta.
12. Active capacity releases only on durable transition to a dormant lifecycle.
13. Cancel, expiry, start-watchdog rejection, and compensation release each
    pending delta exactly once.
14. MaxOwned one blocks a second Soul Bond profile even when the first is
    captured, dead, lost, unloaded, or restoring.
15. Zero limits remain observable but never deny.
16. Multiple groups, global owner cap, and claim cap reserve all-or-none.
17. Owner transfer and role/group change debit old and credit new buckets
    atomically, with no transient bypass.
18. `PerWorld` buckets retain dormant authoritative ownership world and rehome
    atomically; `Global` ignores world.
19. Existing `ADMIN_OVERRIDE` never bypasses a positive group-owned or active
    limit, including the Soul Bond uniqueness group.

### Lifecycle path integration

20. Wild capture, tame, admin/API creation, breeding, spawner summon, bonded
    summon/store, revive, lost recovery, managed coop release, role change,
    transfer, release, cull, and permanent death all route through one group
    authority.
21. Command-item `MaxActive` changes do not affect or bypass group counts.
22. Live entity unload/despawn alone never frees capacity.
23. Denied admission leaves world, item, profile, owner, inventory, and command
    links untouched.
24. No repository or integration path can create a positive grouped profile
    without an admission operation and verified target role.

### Reload, migration, and recovery

25. Cap shrink preserves existing over-cap profiles and blocks only later
    positive deltas.
26. Role mapping add/remove/change enters reconciliation, persists new
    classifications, and does not open headroom prematurely.
27. Restart/fault injection at PREPARED, APPLYING, APPLIED, COMPENSATING, and
    terminal boundaries reconstructs exact committed/pending counts.
28. In-flight operations replay persisted old/new groups after config reload.
29. Legacy rows backfill from durable role; unresolved evidence fails closed.
30. Schema migration is backup-first, idempotent, rollback-safe, and preserves
    all v7 rows/operations.
31. Rebuilding indexes from canonical state yields the same group counts.

### API, diagnostics, and architecture

32. Old public request/API binaries link; new default methods fail closed when
    capability is unavailable.
33. V2 callers provide target role, not authoritative group IDs; spoofed or
    stale expected groups are rejected.
34. Events are immutable, post-commit, logically once, and listener failures are
    isolated.
35. `/tw api test` includes cap boundary, concurrent reservation, cancellation,
    role change, and unavailable-facade fixtures.
36. Diagnostics report committed/pending/over-cap/unresolved data accurately and
    never mutate state.
37. Telemetry contains no owner/profile/group member identifiers.
38. Architecture tests prove no live-entity scan, parallel shadow counter,
    direct repository bypass, unsafe world-thread persistence wait, or oversized
    group logic added to `OwnerPopulationIndex`/`TwCompanionConfig`.
39. Concurrent provisioning with the same idempotency key produces and returns
    one canonical profile and one owned group delta.
40. Different idempotency keys cannot bypass `MaxOwnedPerOwner=1` for the same
    owner/role group.
41. Dormant provisioning uses `PROVISION_DORMANT`, reserves owner/group-owned
    capacity under the unified lock/journal, and creates no physical claim or
    destination requirement.
42. Active provisioning reserves active/claim capacity only for the already
    committed dormant profile; projection failure returns
    `PROVISIONED_DORMANT` and retry never creates a replacement profile.
43. Restart/fault injection at dormant-profile and optional-projection
    boundaries converges to one profile and accurate owned/active counts.
44. Spoofed group IDs are impossible in the request; an invalid/missing target
    role or unavailable authority fails closed before profile creation.
45. The default unavailable provisioning facade preserves old API linkage and
    mutates nothing.
46. `CompanionProvisionedEvent` is immutable, post-commit, logically once, and
    listener-failure-safe.
47. `/tw api test` covers dormant/active/failed-projection/concurrent/idempotent
    provisioning and removes its fixture profiles through normal release.
48. Lifecycle codec/API/switch tests round-trip `PROVISIONED_DORMANT`, count it
    as owned but not active/claim occupancy, and never conflate it with
    `UNKNOWN_DORMANT`, `CAPTURED`, or `LOST`.
49. An active provisioned companion with enabled role revive policy and no
    command links dies into `DEAD_REVIVABLE`, preserves its owned slot/profile,
    and releases active/claim occupancy.
50. Reviving that companion reserves active/group/claim capacity and restores
    the same profile; denial leaves it dead/revivable and creates no replacement.
51. Provisioned death/revive events are command-link-independent, immutable,
    post-commit, logically once, and listener-failure-safe.
52. Equal idempotency strings under two caller namespaces do not alias one
    operation; retries within a namespace do, and neither path bypasses group
    limits.
53. `PopulationAdmissionRequest` V1 rejects `PROVISION_DORMANT`; only the
    provisioning coordinator can prepare the internal no-claim operation under
    the unified owner/group lock and journal.
54. Dormant provisioning reserves one owner/group-owned slot and exactly zero
    active/physical-claim slots, supplies no NPC/destination/claim token, and
    cannot be invoked as a public admission bypass.
55. `ADMIN_FORCE`/`ADMIN_OVERRIDE` and admin creation/revive commands cannot
    exceed group owned/active limits; administrative reconciliation may only
    preserve or repair already-authoritative state.
56. Provisioning operation queries and diagnostics expose preparing through
    partial/terminal states by full `(caller namespace, idempotency key)` origin
    and never turn `PARTIAL_DORMANT` into another profile creation.

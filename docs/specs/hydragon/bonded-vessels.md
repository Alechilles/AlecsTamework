# Bonded companion vessels

Status: Implementation and automated release verification complete
Depends on: [capture policy](capture-policy.md), canonical profiles, population
admission, and [population groups](population-groups.md) for production use
HyDragon counterpart: [capture, summoning, and maintenance](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/capture-summoning-maintenance.md)

## Goal

The implemented opt-in spawner mode keeps one non-stackable item durably
bound to one canonical companion profile across stored, active, dead, lost, and
recovery states. It supports HyDragon's requirement that each Draconic Stone
always refers to the same dragon while the player may own multiple stones.

A binding uses a durable, monotonically increasing generation. Every accepted
transition compare-and-sets the expected generation and publishes a new one.
Copied, replayed, or stale item stacks therefore fail closed even if their
metadata otherwise looks valid.

## Non-goals

- Dragon-specific charge, energy, summon duration, materials, or repair costs.
- Treating a command-tool ID or inventory slot as durable vessel identity.
- Generic capture chance; that is specified in [capture policy](capture-policy.md).
- Replacing disposable capture containers. They remain the default.
- Automatically deleting an active companion because its item cannot be found.
- Allowing arbitrary plugins to edit binding tables or force lifecycle states.

For the HyDragon MVP, Tamework supplies a short swap cooldown and the durable
death/repair transition seam. Energy, duration, and charge systems are optional
future HyDragon extensions, not baseline vessel behavior.

## Configuration

`TwSpawnerConfig` exposes a top-level `Vessel` object at:

`Server/Tamework/Items/Spawners/*.json`

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Mode` | `Disposable` | `Disposable` preserves 3.0.0 behavior; `Bonded` enables this specification. |
| `StateItemIds` | map, empty | Optional item IDs for `Stored`, `Active`, `Dead`, `Lost`, and `Unavailable`; explicit map replaces inherited map. One config may reuse an item ID for multiple states because metadata and durable state disambiguate it. |
| `TransitionCooldownMs` | integer `0` | Tamework-authoritative swap cooldown started after either successful summon or successful store. HyDragon sets this to `10000` for the MVP. |
| `StoreMaxDistance` | number `0` | Maximum distance to the exact linked live projection; `0` uses the existing spawn/capture distance policy. |
| `StoreParticleSystem` | string/null | Successful store VFX. |
| `StoreSoundEvent` | string/null | Successful store sound. |
| `RequireOwner` | boolean `true` | Only the profile owner may use or store the vessel. |
| `AllowStoreInCombat` | boolean `false` | Whether an active companion with combat state may be stored. |

`EmptyItemId` remains the unbound item. `FilledItemId` is the default stored
projection. Missing state item IDs fall back to `FilledItemId`; metadata and the
durable binding state remain authoritative even when visual item IDs are shared.
`StateItemIds.Unavailable` is only an optional display projection for a proven
present item under administrative/evidence quarantine; it is not a companion or
binding lifecycle value.

Bonded source items must have maximum stack size one. Config resolution rejects
or disables a bonded feature when any configured state item is stackable,
missing, or itself bound to an incompatible spawner config. The compiled item
registry indexes `EmptyItemId`, `FilledItemId`, and every explicit or fallback
state item ID directly; bonded dispatch does not require an `_State_` naming
pattern. Cross-config item collisions are rejected deterministically, while
same-config state aliases are valid.

### Example

```json
{
  "EmptyItemId": "Draconic_Stone",
  "FilledItemId": "*Draconic_Stone_State_Filled",
  "Vessel": {
    "Mode": "Bonded",
    "StateItemIds": {
      "Stored": "*Draconic_Stone_State_Filled",
      "Active": "*Draconic_Stone_State_Active",
      "Dead": "*Draconic_Stone_State_Damaged",
      "Lost": "*Draconic_Stone_State_Unavailable"
    },
    "TransitionCooldownMs": 10000,
    "StoreMaxDistance": 12.0,
    "StoreParticleSystem": "HyDragon_Dragon_Store",
    "StoreSoundEvent": "SFX_HyDragon_Dragon_Store",
    "RequireOwner": true,
    "AllowStoreInCombat": false
  }
}
```

## Inheritance and reload

- Omitted `Vessel` inherits the parent's complete section.
- An explicit object inherits missing nested scalar fields.
- Explicit `StateItemIds` replaces the entire parent map; state entries do not
  merge individually.
- `Mode: Disposable` is an explicit child override.
- The two-argument inheritance method delegates to the nested-aware overload,
  and codec documentation describes nested fallback and map replacement.
- `/tw reloadconfig` recompiles the spawner/vessel item registry. A failed
  validation leaves the last valid registry active.
- In-flight operations retain their prepared config ID, config revision, state
  item mapping, and policy fingerprint. A reload cannot change the source or
  destination item midway through a transition.
- Changing `Bonded` to `Disposable` does not erase existing bindings. Existing
  vessels become administratively unavailable until a compatible bonded config
  returns or an explicit audited migration releases them.

## Canonical binding model

Each binding persists at least:

- `binding_id`: random immutable UUID distinct from item/tool/profile IDs;
- `profile_id`: unique canonical companion profile ID;
- `generation`: positive monotonic integer;
- `config_id` and last accepted config revision;
- `lifecycle_state`: `STORED`, `SUMMONING`, `ACTIVE`, `STORING`, `DEAD`,
  `LOST`, `RELEASING`, or `RELEASED`;
- independent `item_projection_status`: `PRESENT`, `MISSING`, `AMBIGUOUS`,
  `REISSUE_PENDING`, or `QUARANTINED`;
- authoritative owner UUID and expected profile revision;
- current active projection UUID/location when applicable;
- durable transition `cooldown_until_ms` (`0` is unset; any nonzero, including a
  negative Hytale world-time value, is valid);
- last committed item ID plus bounded holder/container/slot evidence;
- active operation ID, prior/new generation, timestamps, and diagnostic reason.

Database constraints enforce one non-released binding per profile and one
current generation per binding. Item metadata carries `binding_id`,
`profile_id`, `generation`, config ID, and display state, but never outranks the
durable row.

### Generation fencing

1. A mutation supplies the item's binding ID and expected generation `g`.
2. Preparation validates that the durable binding is at `g` and reserves
   candidate `g+1` in one nonterminal operation.
3. Claim-for-apply compare-and-sets the binding to a transition owned by the
   operation while retaining current generation `g` and reserving `g+1`. Only
   one operation for `(binding_id, g)` can win.
4. The profile/population/binding transaction publishes `g+1` as `APPLIED`.
5. Source finalization rewrites the exact one-item stack with `g+1` and its new
   state item ID, validates the replacement fingerprint, then closes the
   operation as `COMMITTED`. Cancellation before apply leaves `g` authoritative;
   an ambiguous partial apply is quarantined and repaired from the journal.

If two copied stacks both contain generation `g`, the first committed use moves
authority to `g+1`; every later use of either `g` copy is stale and denied. If a
copy is made after that transition, the same rule repeats at the next use.
There is no metadata-only "refresh" that can bless a copied stack.

## Lifecycle transitions

### First capture and binding

After a successful [capture-policy](capture-policy.md) outcome, Tamework creates
the canonical profile and binding, moves the profile to `CAPTURED`, and replaces
the exact empty source item with the stored state in the same journaled
operation. Initial generation is `1`. If binding/item/profile finalization
cannot converge, the operation compensates or quarantines; it never leaves a
newly tamed active NPC alongside a usable stored vessel.

### Summon

1. Validate the exact stored stack, owner, binding generation/state, profile
   revision, config, destination, and cooldown.
2. Prepare owner, claim, and every applicable group reservation with target
   lifecycle `RESTORING`. Pending `RESTORING` consumes active group capacity.
3. Prepare binding transition `STORED(g) -> SUMMONING(g+1)`.
4. Claim all tokens immediately before the world mutation.
5. Spawn exactly one NPC projection with the canonical profile and publish its
   stable identity.
6. Commit profile lifecycle `ACTIVE`, population, binding generation/state, and
   the new `cooldown_until_ms` together as durable `APPLIED`, then re-resolve
   the exact planned live identity.
7. Compare-and-set the exact stored source stack to the planned active item and
   generation `g+1`, validating both expected and replacement fingerprints.
8. Complete the versioned source-finalization journal, mark the overall vessel
   operation `COMMITTED`, and only then emit the canonical summon event. A
   denial or pre-spawn failure retains the stored item at `g` and its prior
   cooldown. Compensation after `APPLIED` restores both transition state and
   its prior cooldown deadline.

The existing disposable flow that replaces a filled item with an empty item and
clears captured metadata is bypassed only for `Mode: Bonded`.

### Store

An active vessel may store only the live projection whose canonical profile and
binding generation match. `TameworkSpawn` may expose toggle semantics, or a
dedicated item interaction may delegate to the same vessel service; neither may
run ordinary capture eligibility or chance again.

The store operation snapshots the companion, prepares `ACTIVE(g) ->
STORING(g+1)`, revalidates owner/distance/combat/source/profile, durably enters
applying, removes the exact live projection, and writes lifecycle `CAPTURED`,
population, binding generation/state, and the new `cooldown_until_ms` together
as `APPLIED`. It then
compare-and-sets the active item to the exact planned stored replacement,
validates the replacement fingerprint, closes source finalization, marks
`COMMITTED`, and emits the store event. Active
group occupancy is released only when the durable `CAPTURED` transition is
applied. A crash cannot produce both an authoritative stored state and an
authoritative active projection.

### Death and lost state

A revivable death records the profile as `DEAD_REVIVABLE`, releases its active
group/physical occupancy, and moves the binding to `DEAD` with a new generation.
Tamework attempts to rewrite the current item to its dead state. If the item is
offline or not discoverable, the durable binding still invalidates the prior
generation; the missing projection is queued for evidence-based repair.

An ordinary non-death runtime despawn of an active bonded projection is treated
as an implicit store: the profile becomes `CAPTURED`, the binding advances to
`STORED`, and the exact active vessel is rewritten to its stored state. It is
not classified as `LOST` merely because the engine removed the live entity.

`LOST` follows the same authority pattern. Neither state automatically creates
a replacement item. HyDragon's repair interaction consumes Revitalizing Essence
and requests the supported `DEAD -> STORED` transition through the public API;
Tamework owns the binding/profile mutation, while HyDragon owns the material
transaction and cracked-stone presentation.

When a vessel asset declares durability, the `ACTIVE -> DEAD` item projection
sets durability to zero and `DEAD -> STORED` restores it to the asset-backed
maximum. Other summon/store projections preserve current durability.

Repair is a cross-plugin saga, not an assumed distributed transaction:

1. HyDragon chooses a stable namespaced idempotency key and asks Tamework to
   prepare `DEAD(g) -> STORED(g+1)` for the exact binding, generation, owner,
   and damaged source fingerprint. Preparation reserves the transition but
   does not mutate the binding or consume material.
2. HyDragon durably records its repair operation, then exact-CAS consumes the
   configured materials and marks `MATERIAL_CONSUMED` under that same key.
3. HyDragon claims/commits the prepared Tamework transition. Tamework advances
   profile/binding/item state once and returns the recorded result on retries.
4. HyDragon marks its operation committed only after Tamework reports terminal
   success. A transient/unknown result is retried with the same key and is not
   refunded while Tamework might still commit.
5. A terminal Tamework denial before apply compensates the consumed material
   to the exact source when possible; if exact reinsertion is unsafe, HyDragon
   creates its own durable owner recovery claim. Compensation is idempotent and
   never authorizes a second vessel transition.

After restart HyDragon reacquires or queries the Tamework operation by
caller/idempotency key rather than persisting a process-local token. Tamework's
bounded operation status distinguishes not-found/prepared/applied/committed/
terminal-denied so repair recovery cannot both refund and complete.

Bonded death/revive qualification is independent of command-tool links.
`CompanionRevivePolicy` treats an authoritative non-released bonded binding as a
supported revive path even when `toolIds` is empty; otherwise a bonded dragon
would incorrectly follow permanent-death/`RELEASED`. Vessel lifecycle
observation and canonical vessel events likewise do not depend on the existing
command-link-conditioned `NpcCapturedEvent`, `NpcDeathRecordedEvent`, or
`NpcLostRecordedEvent` emissions.

### Transfer, drop, and item loss

Moving or dropping the authoritative item does not change profile ownership.
With `RequireOwner`, a non-owner holder can carry but cannot use or store it.
Container evidence is updated opportunistically and reconciled from saved
online/offline inventories and recursive containers; it is not the identity.

If the only authoritative vessel projection is lost or destroyed, Tamework
retains the profile/binding lifecycle unchanged and sets only
`item_projection_status` to `MISSING` or `AMBIGUOUS`. An active dragon remains
`ACTIVE` and consumes active capacity; a stored dragon remains `STORED`.
Recovery must retire the old generation and issue a new generation under an
audited, owner-verified operation. It must never clone the profile, change
lifecycle merely because evidence is unavailable, or guess that an unscanned
item is gone while reconciliation coverage is incomplete.

### Permanent release

Unbinding/permanent profile release is a distinct destructive admin or
integration operation. It retires the binding generation and verifies no active
projection before committing profile release and marking the binding
`RELEASED`. After the deferred [companion-inventory](companion-inventory.md)
system is implemented, the same operation must evacuate inventory before
profile release. Normal item use cannot invoke it accidentally.

## Atomicity and recovery

Vessel operations use the existing prepare/apply/commit/compensate durability
model and the persistence resilience circuit. The operation row records binding
and profile revisions, before/after states, source fingerprint, planned item
replacement, population tokens, live projection evidence, and recovery phase.

Binding generation/state, canonical profile/lifecycle, population changes, and
the planned transition cooldown deadline are written in one SQLite transaction
or savepoint at the `APPLIED` boundary.
`ProfileDataApi` queue acceptance is never binding authority. Item
source-finalization follows as a recoverable second boundary, matching the
existing prepared-spawn protocol.

Cooldown validation reads the durable binding/operation value and therefore
survives restart and item movement. It uses `0` only as the unset sentinel and
compares timestamps by ordering; it never treats a negative Hytale world-time
epoch as absent.

Recovery rules:

- database new/item old: repair the exact proven source stack to the committed
  generation; otherwise quarantine rather than accepting the old item;
- item new/database old: roll forward only with the matching operation and
  exact world/profile evidence, otherwise restore the item to the old state;
- live projection and stored authority both present: suppress/retire only the
  projection whose exact binding-generation marker proves it stale;
- unknown saved-world/inventory coverage: defer destructive conclusions;
- late callback: compare operation ID/generation and return the recorded result;
- persistence unavailable/degraded: deny positive/destructive transitions.

Version/generalize `CompanionSpawnSourceFinalizationContext` so it records the
vessel action, binding/profile IDs, old/new generations, exact planned item
payload, expected source fingerprint, and replacement fingerprint. Recovery
validates the recorded replacement rather than recomputing an empty/active item
from the current config. It covers relevant online/offline inventory sections
and bounded nested containers, or marks unsupported evidence coverage incomplete
and quarantines.

Bonded dispatch runs before legacy spawner heuristics. An active state item is
not captured evidence merely because its ID contains `_State_` or its metadata
has a legacy `Captured` flag. Stored bonded evidence is interpreted through
binding ID/generation first, and no bonded transition calls legacy
`clearCapturedMetadata()` in a way that erases binding/profile identity.

World-thread work captures IDs and revision fences, never `Player` component
instances. SQLite work remains asynchronous; systems use `CommandBuffer` or
queue owning-world callbacks for ECS writes.

## Public API and events

Capability `BONDED_VESSELS` and the default fail-closed
`TameworkApi.bondedVessels()` accessor expose these immutable types:

- `BondedVesselView`
- `BondedVesselTransitionRequest`
- `BondedVesselTransitionDecision`
- `BondedVesselTransitionToken`
- `BondedVesselState` and stable reason enums

Minimum operations:

- lookup by binding ID or profile ID;
- validate an item projection against binding/generation;
- prepare, claim-for-apply, commit, and cancel supported transitions;
- identify external callers by namespace plus idempotency key;
- `resumeTransition(request)`, which revalidates the original transition,
  revisions, actor, and current exact holder/container/slot/inventory-revision/
  item-fingerprint evidence before minting a fresh token for the existing
  nonterminal operation after process/server restart;
- `findOperation(callerNamespace, idempotencyKey)`, which queries nonterminal
  and terminal durable state without requiring a process-local token;
- inspect subsystem readiness and a bounded operation status.

Tokens are opaque, expire, and cannot be fabricated or reused for a different
binding/generation. They are process-local apply capabilities, not durable
restart identity, and callers never serialize them. Resume is idempotent: it
does not prepare a second operation, extend policy/config authority, or reserve
another generation. Arbitrary state setters are not exposed.

The public durable operation status distinguishes `PREPARED`, `APPLYING`,
`APPLIED`, `COMMITTED`, `CANCELED`, `TERMINAL_DENIED`, and `QUARANTINED`.
`APPLIED` is materially different from a terminal denial: Tamework may still
complete exact source finalization, so an external material saga must retry or
resume and must not refund. `TERMINAL_DENIED` proves that authoritative apply
did not happen. Not-found, unavailable, timeout, and unknown remain
indeterminate/fail-closed and never authorize either a new repair key or a
refund.

Events `BondedVesselBoundEvent`, `BondedVesselStateChangedEvent`, and
`BondedVesselBindingInvalidatedEvent` are immutable post-commit notifications.
They include operation/binding/profile IDs, prior/new state and generation,
owner, config ID, reason, and timestamps. Listener failure cannot change the
transaction.

Canonical vessel events are emitted independently of command-link-based legacy
events. Summon/store events fire only after source-finalization closure; death
state events fire after the durable profile/population/binding transaction even
if item rewrite remains queued. Recovery suppresses a duplicate logical event
by operation ID or emits one event with `recovered=true`; it never produces an
indistinguishable second success.

## Migration and diagnostics

- Schema v8 adds binding and vessel-operation tables, constraints, indexes, and
  recovery readers without modifying legacy item metadata in place.
- `Mode: Disposable` is the default, so existing configs/items retain behavior.
- Bonded dispatch never converts an unbound filled item into a vessel. It leaves
  the item unchanged and returns a bounded admin/player explanation. A new
  binding originates only from a successful bonded capture using an eligible
  unbound source item.
- Schema migration is idempotent, backed up before v7-to-v8 migration, rolls
  back its marker and DDL together, and preserves all v7 data.
- `/tw diagnose vessel <binding-or-profile>` reports lifecycle and item-
  projection status, generation,
  profile/config revision, last item evidence, open operation, population
  correlation, quarantine, and stable reason code.
- Normal diagnostics are read-only and bounded. Repair/reissue requires a
  separate explicit command, owner proof, confirmation, audit record, and
  authoritative evidence coverage.

## Implementation file map

| Area | Existing anchor | Implemented responsibility |
| --- | --- | --- |
| Config | `config/assets/TwSpawnerConfig`, `config/ItemFeatureConfig` | `Vessel` codec, validation, nested inheritance, runtime snapshot |
| Item orchestration | `items/SpawnerFeatureHandler`, `SpawnerPreparedSpawnService`, `SpawnerSourceItemTransaction` | Route disposable vs bonded; exact source finalization |
| Binding domain | `vessels` and `vessels/runtime` packages | Focused binding resolver, transition planner/coordinator, generation validator, source repair |
| Identity/lifecycle | `items/SpawnerNpcIdentityService`, `lifecycle`, `ownership` | Canonical profile/projection transitions |
| Population | owner/claim admission plus [group admission](population-groups.md) | Reserve/commit/cancel before summon/store/revive |
| Persistence | `persistence/sqlite`, `persistence/operation`, resilience/recovery | Schema v8 binding/operation repositories and recovery |
| Evidence | existing recursive inventory/saved-world evidence sources | Locate projections without treating location as authority |
| API/events | `api`, `api/internal` | Default unavailable facade, views/tokens/events/capability |
| Diagnostics | `commands`, `persistence/diagnostics`, `selftest` | Bounded inspect, explicit repair, live fixtures |

`SpawnerFeatureHandler` and `TwSpawnerConfig` are already beyond the preferred
new-class size. Vessel orchestration/codecs are extracted into focused classes;
the implementation must not expand either into a larger multi-domain class.

## Acceptance tests

### Config and compatibility

1. A config with no `Vessel` remains byte-for-behavior disposable.
2. `Mode: Disposable` never creates binding rows or generation metadata.
3. Omitted/partial `Vessel` sections inherit according to the contract.
4. Explicit `StateItemIds` replaces the parent map.
5. Bonded config rejects stackable, missing, or incompatible cross-config item
   definitions; repeated item IDs within one config remain valid state aliases.
6. Reload atomically swaps only a valid compiled registry and pins operations to
   one revision.

### Binding and anti-duplication

7. First capture produces one profile, one binding, one generation-1 stored
   item, and no live projection.
8. A profile cannot have two non-released bindings.
9. Two concurrent uses of generation `g` have one CAS winner.
10. A copied/stale `g` item fails after authority advances to `g+1`.
11. A copied active item cannot store, summon, repair, rebind, or advance the
    profile.
12. Generation never decreases or repeats across committed transitions,
    restart, compensation, or item reissue.
13. A changed source slot/stack/holder before apply cancels safely.
14. Tool links and identical item IDs do not make two stacks the same binding.

### Lifecycle and population

15. Stored-to-active reserves owner, claim, and all group capacity before spawn.
16. Pending `RESTORING` prevents a concurrent second active-group summon.
17. Spawn denial leaves stored state/item/generation unchanged.
18. Successful summon produces one exact canonical live projection and active
    item generation at a visible, terrain-safe position in the player's front
    arc; companion recall/relocation also searches the front arc before side or
    rear fallbacks.
19. Store targets only the linked canonical profile and never reruns capture
    chance.
20. Active occupancy releases only after durable store/death/lost transition;
    an ordinary non-death engine despawn converges through the store transition.
21. Death creates one `DEAD` binding generation even with duplicate death
    callbacks and an offline vessel item.
22. Revive/repair cannot bypass group admission or stale-generation checks.
23. Cross-owner possession is unusable when `RequireOwner=true` and cannot
    transfer canonical ownership.
24. A successful summon and a successful store each start the same configured
    transition cooldown; denial/failure does not start it.
25. A bonded companion with no command-tool link still enters
    `DEAD_REVIVABLE`/`DEAD` and can use the supported repair/revive path.
26. Active bonded item states bypass legacy `_State_`/`Captured` heuristics and
    never appear as dormant captured-item population evidence.

### Failure, restart, and reconciliation

27. Fault injection at every database, world-spawn/removal, and item-write
    checkpoint converges to one authoritative generation/state.
28. Crash after database advance but before item rewrite repairs only the exact
    proven source; missing evidence quarantines.
29. Crash after item rewrite but before database commit rolls forward or back
    only from matching journal/evidence.
30. No recovery path leaves both authoritative stored state and active
    projection.
31. Late callbacks return the recorded decision without another generation.
32. Incomplete saved-world/offline-inventory coverage never authorizes reissue
    or stale-projection deletion.
33. Persistence degraded/unavailable fails closed without blocking a world
    thread.
34. Source finalization validates the recorded replacement fingerprint and
    cannot recompute a replacement from reloaded config.
35. Summon/store events wait for source-journal closure; recovered replay is
    deduplicated or explicitly marked `recovered`.
36. `cooldown_until_ms` advances in the same `APPLIED` transaction as binding
    generation/state, is durable before source closure/success event, survives
    restart, honors negative world-time values, and prevents the opposite
    transition until expiry.

### Migration, API, and diagnostics

37. Schema v8 migration is idempotent, rollback-safe, and preserves every v7
    profile, command link, population row, and legacy filled item.
38. Bonded dispatch leaves an unbound filled item unchanged with clear feedback.
39. No config, public API, or ordinary interaction can convert an unbound filled
    item into a bonded generation; a new binding requires a successful bonded
    capture from an eligible unbound source item.
40. Old API clients link; the new accessor's default facade fails closed.
41. Public views are immutable and tokens cannot be forged/reused.
42. Each event fires once logically after commit; listener exceptions are
    isolated.
43. `/tw diagnose` correlates binding, generation, profile, item evidence,
    population operation, and persistence incident without mutating state.
44. `/tw api test` exercises bind/summon/store/death/stale-copy/recovery and
    leaves its fixture world and inventories clean.
45. Architecture guards find no unsafe player-component access, direct ECS
    writes from runtime systems, or new oversized orchestrator classes.
46. Every configured/fallback state item ID is indexed directly and a valid
    bonded config works when none of its IDs contain `_State_`.
47. Losing an item while the binding is `ACTIVE` changes only projection
    status; profile/binding lifecycle and active group occupancy remain active.
    The same loss while `STORED` retains stored lifecycle.
48. Repair retries and concurrent callbacks with one caller/idempotency key
    consume configured material at most once and commit exactly one
    `DEAD(g) -> STORED(g+1)` transition.
49. Crash/fault injection before material consumption, after material
    consumption, after Tamework apply, and before either journal closes
    converges to exactly one repaired vessel or one terminal denial plus one
    material refund/recovery claim, never both a refund and repaired vessel.
50. A prepared repair can be resumed after process/server restart from its
    original request origin and current exact source evidence without retaining
    or serializing the old token.
51. `findOperation` exposes both nonterminal and terminal durable status;
    `APPLIED` blocks refund while `TERMINAL_DENIED` alone permits compensation,
    and not-found/unavailable/unknown remain fail-closed.

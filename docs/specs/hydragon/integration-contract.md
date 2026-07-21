# Tamework-HyDragon integration contract

Status: Proposed
Related Tamework specs: [index](README.md), [capture policy](capture-policy.md),
[bonded vessels](bonded-vessels.md), [population groups](population-groups.md),
and deferred [companion inventory](companion-inventory.md)
HyDragon counterparts: [plugin architecture](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/plugin-architecture.md),
[capture and maintenance](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/capture-summoning-maintenance.md),
[Soul Bond and Miniwyvern](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/soul-bond-miniwyvern.md), and
[dragon content and encounters](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/dragon-content-encounters.md)

## Purpose

This document defines the stable seam between Tamework 3.x and the HyDragon
Java plugin. It prevents HyDragon from depending on Tamework internals and
prevents Tamework from acquiring dragon-specific behavior.

### Goals

- Give HyDragon mutation-authoritative, capability-gated APIs for every generic
  companion operation it cannot implement safely from assets.
- Keep profile, vessel, population, and provisioning identity under one
  Tamework persistence/recovery authority; later companion inventory follows
  the same boundary in its own update.
- Preserve source/binary behavior for 3.0.0-era consumers that do not opt in.

### Non-goals

- Declaring Public API `0.9.0` stable/1.0 or removing capability checks.
- Supporting HyDragon against Tamework 2.x.
- Defining HyDragon balance, encounter choreography, elemental abilities,
  recipes, art, localization, or player entitlement storage.
- Allowing event listeners, profile JSON, or direct repositories to authorize a
  Tamework mutation.

## Dependency and bootstrap

HyDragon declares:

```json
{
  "Dependencies": {
    "Alechilles:Alec's Tamework!": ">=3.0.0 <4.0.0"
  }
}
```

At plugin startup HyDragon obtains `TameworkApi` through the supported plugin
accessor, null-checks it, records `getApiVersion()`, and checks capabilities
before registering interactions or listeners. It never reads Tamework's SQLite
database, casts internal implementation classes, reflects into services, or
caches mutable `Tw*Config` instances.

This additive surface bumps the experimental Public API from `0.8.0` to
`0.9.0`. The API version remains independent of the Tamework mod version and is
not a substitute for capability checks.

### Capabilities

Tamework adds these additive enum values:

- `CAPTURE_POLICY`
- `BONDED_VESSELS`
- `POPULATION_GROUPS`
- `COMPANION_PROVISIONING`

Feature gates are independent; existing capabilities are not one global
all-or-nothing list:

| HyDragon surface | Hard capabilities | Missing-capability result |
| --- | --- | --- |
| Authoritative companion mutations | `PROFILES`, `POLICY`, `PERSISTENCE_RESILIENCE` plus the feature-specific capabilities below | Disable that positive/destructive mutation; never substitute local state |
| Configured custom requirements/effects | `INTERACTION_EXTENSIONS` | Disable only interactions whose config names a HyDragon handler |
| Elemental/profile-scoped ability state | `PROFILE_DATA` | Disable persistence-dependent abilities; capture/vessels/Soul Bond entitlement continue |
| Post-commit lifecycle/presentation consumers | `EVENTS` | Disable event-dependent HyDragon consumers; do not poll repositories or reinterpret mutation success |
| Startup config inspection | `CONFIG_READ` | Mark config introspection unavailable; rely only on HyDragon-owned assets and disable any feature requiring Tamework view validation |
| Operator health bridge | `DIAGNOSTICS` | Warn that integrated operator diagnostics are unavailable; gameplay authority is unchanged |

Feature-specific gates:

- The full-dragon Draconic Stone capture/summon loop requires
  `CAPTURE_POLICY`, `BONDED_VESSELS`, `POPULATION_GROUPS`, and the shared
  profile/policy/persistence capabilities. If one is absent, only that stone
  loop is disabled with one actionable startup error.
- The core Soul Bond provisioning transaction requires healthy `PROFILES`,
  `POLICY`, `PERSISTENCE_RESILIENCE`, `POPULATION_GROUPS`, and
  `COMPANION_PROVISIONING`. The shipped Soul Bond item interaction additionally
  requires `INTERACTION_EXTENSIONS`; missing `EVENTS` disables only its
  post-commit presentation/listeners. `PROFILE_DATA` is not required for the
  HyDragon-owned entitlement or profile creation. Soul Bond remains available
  when `CAPTURE_POLICY` or `BONDED_VESSELS` is absent because it provisions a
  Soul Bond-exclusive Miniwyvern directly rather than capturing/binding one.
- No feature emulates a missing Tamework authority with ad-hoc metadata.

`COMPANION_INVENTORY` is not part of API `0.9.0` or the initial HyDragon
release. HyDragon does not query it or register a backpack interaction. The
deferred [companion-inventory specification](companion-inventory.md) will assign
its API/migration version and capability gate when that update is scheduled.

## Stable identity and data ownership

| Data | Authority | Consumer rule |
| --- | --- | --- |
| Canonical profile identity, owner, role, lifecycle and revision | Tamework | HyDragon keys all durable per-dragon data by `profile_id` |
| Population group membership/count/reservation | Tamework | HyDragon supplies config membership and requests mutations through the public admission API |
| Vessel binding ID, generation, state and current projection | Tamework | HyDragon treats item metadata as a display/interaction projection only |
| Deferred companion inventory and recovery claims | Future Tamework update | HyDragon will request open/summary operations; it must never serialize a backpack into profile data |
| Vessel `DEAD`/damaged lifecycle, binding generation and durable `TransitionCooldownMs` | Tamework | HyDragon configures `10000` ms in the vessel asset and never duplicates condition/cooldown authority in profile data or code |
| Stone tier values, repair material transaction, cosmetics and localization | HyDragon | Invoke supported Tamework vessel transitions; compensate material changes idempotently if the transition fails |
| Soul Bond player entitlement | HyDragon | Persist by player UUID in HyDragon storage; the Miniwyvern profile remains Tamework-owned |
| Elemental archetype and ability state | HyDragon | Store profile-scoped JSON under namespace `Alechilles:HyDragon` |
| Models, roles, items, recipes, VFX, status effects and spawn assets | HyDragon | Reference only stable Tamework type/config IDs |

Live entity UUIDs, command-tool links, inventory slots, and vessel item
locations are not durable domain identities.

## Required IDs and baseline policy

- Flight gate item: `Tamework_Flightmasters_Talisman`.
- Full-dragon logical group: `hydragon:full_dragons` with unlimited owned and
  one active per owner.
- Soulbound Miniwyvern logical group: `hydragon:soulbound_mini` with
  one owned and one active per owner.
- Miniwyvern wild and tamed roles are excluded from every ordinary Draconic
  Stone `AllowedRoles` and capture-policy role map. Creation is performed only
  by the HyDragon Soul Bond workflow.
- Every bonded Draconic Stone sets Tamework
  `Vessel.TransitionCooldownMs: 10000`; there is no second HyDragon swap-cooldown
  field or timer.
- HyDragon public interaction-extension IDs use a stable `hydragon:` prefix.
  Tamework reserves `tamework:` for its own extensions.

If Hytale asset IDs cannot contain a colon in a particular family, the asset
filename/config asset ID may use an underscore-safe form while the persisted
`GroupId`, Java extension IDs, and profile-data namespaces retain their
namespaced form.

Energy, maximum summon duration, and charge depletion are deferred optional
HyDragon extensions. They are not required for the MVP and are not Tamework
binding/population authority.

## Public API additions

### Capture policy

`configs()` exposes immutable item-side capture mechanics and role-scoped
`TwCapturePolicyConfig` difficulty in new versioned views rather than changing
the constructor of the existing `SpawnerConfigView`.
`CaptureAttemptResolvedEvent` reports the committed attempt ID, applicable
config IDs/revisions, actor, source item ID, target UUID/profile when known,
role, effective power/chance/condition inputs, outcome (`CAPTURED` or
`FAILED_ROLL`), result reason, and timestamps. Precondition/capability/capacity
denials occur before attempt resolution and do not emit this success/failure-roll
event. The event is emitted after the attempt result is durable.
HyDragon registers namespaced, side-effect-free special encounter eligibility
through the capture requirement extension added with this capability; a public
event is never a cancelable pre-commit policy hook.

### Bonded vessels

`TameworkApi.bondedVessels()` returns a `BondedVesselsApi` when
`BONDED_VESSELS` is present. At minimum it supports immutable lookup by binding
ID and profile ID plus mutation-bound summon, store, and state-transition
requests. Every request carries a caller namespace, idempotency key, expected
binding generation, expected profile revision, actor UUID, and intended
transition. Tokens are
opaque and follow prepare/claim/apply/commit/cancel semantics.

Events:

- `BondedVesselBoundEvent`
- `BondedVesselStateChangedEvent`
- `BondedVesselBindingInvalidatedEvent`

All include binding ID, old/new generation, profile ID, state, operation ID,
and immutable timestamps. Stale-generation denial is diagnostic, not an event
that HyDragon may reinterpret as success.

### Population groups

Group limits are automatically enforced by the existing
`PopulationAdmissionApi`; callers cannot opt out. New immutable request context
may include expected group IDs for diagnostics, but Tamework resolves and
validates authoritative membership. `PopulationGroupApi` provides read-only
definitions/counts and a reconciliation status view. It does not offer a
non-transactional "increment" method.

Events:

- `PopulationGroupMembershipChangedEvent`
- `PopulationGroupLimitChangedEvent`

### Companion provisioning

`TameworkApi.companionProvisioning()` is a default fail-closed accessor for a
generic mutation-bound creation API. A request includes a stable caller
namespace plus idempotency key, owner UUID, target role ID, desired initial disposition
(`PROVISIONED_DORMANT` or `ACTIVE`), retained ownership-world context, and
destination/world context when active projection is requested. Tamework
resolves role-based group membership; callers cannot assert a bypassing group.

Provisioning first creates and commits exactly one owned dormant canonical
profile through owner/group admission. If active projection was requested, a
second journaled restore admission projects that same profile. Projection
failure returns the one profile as dormant/recoverable and never creates a
replacement on retry. The result includes operation/profile IDs, committed
lifecycle, projection status/reason, and population decision. A successful
`CompanionProvisionedEvent` is post-commit and idempotent by operation ID.
An authoritative non-released provisioning record plus enabled role revive
policy also qualifies the companion for `DEAD_REVIVABLE` without a command-tool
link. Provisioned death/revive events are canonical and command-link-independent;
revive restores the same profile through normal active/group/claim admission.

### Deferred companion inventory

This surface is not implemented or advertised in Public API `0.9.0`. A later
API version will add `TameworkApi.companionInventories()` with
summary/open-session/recovery operations. HyDragon does not receive repository
access or a mutable backing collection. The future events are
successful-post-commit notifications:

- `CompanionInventoryChangedEvent`
- `CompanionInventoryDispositionEvent` for overflow, escrow, quarantine, and
  recovery-claim transitions

### Compatibility shape

New root accessors should be `default` methods returning an unavailable facade
or `Optional` so an external implementation of the experimental `TameworkApi`
does not fail linkage. Existing records and public constructors are not changed;
new data uses new versioned view/request types. New enum values are additive,
and consumers must use default branches when switching over capabilities or
event reasons.

## Event ordering and idempotency

For one successful first capture and summon cycle, externally visible ordering
is:

1. Capture eligibility is revalidated.
2. The capture attempt outcome is durably recorded exactly once.
3. Population/binding state enters its durable applying state.
4. Ownership/lifecycle/entity changes are applied on the owning world thread.
5. Canonical profile/population/binding state commits as `APPLIED`.
6. The exact source item is finalized and its journal closes as `COMMITTED`.
7. New authoritative capture/vessel/group events are emitted in operation
   sequence order. Existing command-link-conditioned profile/capture events are
   compatibility notifications, not authoritative coverage.

HyDragon handlers must be idempotent by `operationId` or `attemptId`. They may
enqueue later work but must not block the emitting thread or perform SQLite
I/O synchronously. Tamework catches and logs listener exceptions; a HyDragon
listener failure cannot roll back a committed Tamework operation.

A repeated event, callback, or API request with the same caller namespace and
idempotency key returns the previously committed decision. It never rolls capture chance again,
increments a binding generation twice, creates another profile, or consumes
another group slot.

## Failure contract

| Condition | Required result |
| --- | --- |
| Missing hard capability | HyDragon disables related mutations; no fallback write path |
| Tamework loading/reconciling/degraded/unavailable | Positive or destructive mutation fails closed with retryable feedback |
| Capture roll fails | NPC/owner/role/health/effects remain unchanged; empty item remains; configured failure cooldown applies |
| Source item moved or changed before apply | Cancel operation; do not mutate NPC or binding |
| Stale/copied vessel generation | Deny and retain item as non-authoritative evidence; expose repair/quarantine diagnostic |
| Group cap reached | Deny before spawning/reviving; retain stored vessel and profile state |
| Listener or cosmetic effect fails after commit | Gameplay commit remains; record degraded presentation diagnostic |
| HyDragon profile-data write fails | Do not claim a Tamework mutation succeeded on HyDragon's behalf; retry idempotently or compensate domain state |
| Deferred inventory capacity shrinks | In the later inventory update, move excess slots to durable overflow and never delete them |
| Deferred profile deletion with inventory items | In the later inventory update, atomically create an owner recovery claim or block deletion |

## Reload behavior

- `/tw reloadconfig` reloads `TwSpawnerConfig`, including item-side capture
  mechanics and vessel sections, and item-feature registries.
- Capture-policy and population-group families update from normal asset
  loaded/removed events. The deferred companion-inventory family will use that
  same lifecycle when implemented.
- API `0.9.0` adds `CAPTURE_POLICY` and `POPULATION_GROUP` to
  `ConfigReloadedEvent`. Existing `SPAWNER` covers only item-side capture
  mechanics and vessel-section changes; `COMPANION_INVENTORY` is deferred.
- An in-flight operation is pinned to its prepared config revision. If the
  relevant item, role, or group config changes before claim-for-
  apply, revalidation either accepts the pinned safe operation or cancels it;
  it never silently executes under a different formula or capacity.
- Reload invalidation never deletes existing profiles or bindings. The later
  inventory implementation must extend this invariant to inventory rows.

## Migration contract

- Tamework coordinates the suite persistence changes in one backup-first,
  transactional schema v8 plan. A failed migration rolls back DDL and schema
  marker and preserves all v7 data. Schema v8 excludes companion-inventory
  tables and claims; the deferred system receives the next appropriate
  migration version when scheduled.
- Public API advances additively from `0.8.0` to `0.9.0`; old records and
  abstract interface methods are not mutated. New accessors are default
  fail-closed methods and new data uses new/V2 immutable types.
- HyDragon raises its manifest dependency to `>=3.0.0 <4.0.0` before shipping
  configs or code that reference the new capabilities.
- Legacy spawner/profile/item adoption follows the subsystem evidence rules;
  the integration layer never guesses ambiguous identity or silently opts a
  legacy disposable item into a bonded generation.
- Startup runs migration and population/binding reconciliation before HyDragon
  enables positive mutations. Existing content remains inspectable while a
  feature is gated unavailable.

## Implementation file map

| Area | Tamework anchors | Required change |
| --- | --- | --- |
| API root/capabilities | `api/TameworkApi`, `TameworkApiCapability`, `api/internal/TameworkApiImpl` | API `0.9.0`, discrete capabilities, default unavailable facades |
| Policies/config reads | `api/PolicyApi`, `TameworkConfigReadApi`, `api/internal/ApiMapper` | V2/default methods and immutable subsystem views |
| Events | `api/TameworkEventsApi`, `api/internal/TameworkEventBus` | New immutable post-commit event types and once-logical dispatch |
| Config families | `Tamework` asset registration, internal/public config enums, override/UI schema registries | Current capture-policy and group family wiring; inventory is deferred |
| Persistence | `persistence/sqlite`, operation/recovery/incidents/health | Coordinated schema v8 and subsystem readiness |
| Live contract tests | `selftest/ApiSelfTestRunner`, API compatibility/unit tests | Advertise/test every capability and unavailable fallback |
| Public documentation | `wiki/Modder-Documentation/Public-API`, config references/recipes/indexes, `CHANGELOG.md` | Update API version/dependency examples and integration recipes atomically |

## Registration lifecycle

HyDragon registers interaction requirements/effects and event listeners during
plugin start and closes every returned `AutoCloseable` during stop/reload. It
must not register duplicate IDs after a partial reload. Registration failure is
isolated by feature, and the startup report names the failed extension ID.

Suggested HyDragon extension IDs include:

- `hydragon:soul_bond_available`
- `hydragon:create_soulbound_miniwyvern`
- `hydragon:vessel_condition_allows_summon`
- `hydragon:consume_revitalizing_essence`
- `hydragon:apply_elemental_archetype`
- `hydragon:special_encounter_capture_ready`

Tamework remains responsible for the actual profile/vessel/population
transaction behind these effects.

## Diagnostics

`/tw api test` must report each current capability and run non-destructive
fixtures for capture attempts, generation fencing, group admission, and
dormant/active/failed-projection companion provisioning.
`/tw diagnose` includes config IDs/revisions, subsystem readiness,
open operations, quarantines, stale vessel evidence, and group
counts/reservations. Player-facing messages omit raw SQLite details and binding
secrets; admin output includes correlation IDs.

HyDragon's startup diagnostics log its version, detected Tamework mod/API
versions, capability matrix, registered extension IDs, and configured group
IDs. Initial-release diagnostics do not report backpack support because the
feature is not registered.

## Acceptance tests

1. HyDragon loads against Tamework 3.x with every required capability and
   registers each extension/listener once.
2. Missing capture-policy or vessel capability disables only the full-dragon
   stone loop; Soul Bond remains available when its profile/population
   capabilities are healthy.
3. Missing `POPULATION_GROUPS` disables both stone and Soul Bond positive
   admissions without changing existing profiles, items, NPCs, or owners.
4. Missing `COMPANION_PROVISIONING` disables only Soul Bond creation; the
   full-dragon stone loop and existing Miniwyvern profiles remain usable.
5. HyDragon registers no backpack action or inventory persistence for the
   initial release, and API `0.9.0` does not advertise `COMPANION_INVENTORY`.
6. A consumer compiled against the pre-change API still links and uses existing
   methods.
7. Capability switches with unknown future enum values do not throw.
8. Miniwyvern roles cannot be captured by any ordinary Draconic Stone.
9. Soul Bond-created Miniwyverns enter the unique group and a second creation
   is atomically denied.
10. Provisioning retries/concurrency return one canonical Miniwyvern profile;
    failed active projection leaves it dormant/recoverable.
11. `Tamework_Flightmasters_Talisman` is the only baseline flight gate referenced
   by the shipped HyDragon configuration.
12. No HyDragon source imports `api.internal`, `persistence`, `ownership`,
   `items` implementation, or SQLite packages from Tamework.
13. A duplicated callback with one operation ID produces one durable mutation
    and one logical event sequence.
14. Listener exceptions do not roll back committed capture, vessel,
    provisioning, or population state and are visible in
    diagnostics.
15. Config reload during a prepared operation cannot mix old eligibility with a
    new chance formula or group limit.
16. Server restart between every prepare/apply/commit boundary converges to one
    profile, one binding generation, and correct group counts.
17. Tamework unavailable/degraded states fail closed without blocking the world
    thread.
18. The packaged integration smoke test completes the end-to-end loop listed in
    the suite [README](README.md#definition-of-suite-completion).
19. A Soul Bond Miniwyvern with no command-tool link dies into a recoverable
    state and revives as the same profile through group admission.
20. Provisioning uniqueness is scoped by `(caller namespace, idempotency key)`:
    same-pair retries return one result, while equal keys in two namespaces do
    not alias and still cannot bypass group/entitlement limits.

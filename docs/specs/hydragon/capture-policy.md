# Configurable capture policy

Status: Implementation and automated release verification complete
Runtime: Public API `0.9.0` / Tamework 3.0.0; capability activation remains capture-journal-recovery-gated
Depends on: existing `TwSpawnerConfig`, `TameworkSpawn`, canonical profiles, and
population admission
HyDragon counterpart: [capture, summoning, and maintenance](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/capture-summoning-maintenance.md)

## Goal

Extend Tamework's authoritative capture transaction with generic item-side
capture power/chance and role-scoped target difficulty, minimum power,
guaranteed power, and probabilistic success.
The policy must support HyDragon's five Draconic Stone tiers without naming
those tiers in Tamework.

The attempt outcome is resolved exactly once at the commit boundary after
health, required effect, distance, role, source item, ownership, population,
and target identity are revalidated. A failed roll does not tame, own, rename,
change role, despawn, damage, heal, or otherwise mutate the NPC. Source-item
handling follows the spawner's snapshotted `SourceConsumption` policy: the
default retains the item, while HyDragon opts into `ResolvedAttempt` and spends
one stone on either terminal result. The generic spend/apply transaction is
defined by [command-roster capture and death restoration](command-roster-capture-revival.md).

## Non-goals

- Species-specific formulas, ore names, rarity tables, or player skill systems.
- Client-authoritative random rolls.
- Catch-up retries that silently generate another roll.
- Changing the default deterministic behavior of existing spawner configs.
- Replacing the existing health/effect/channel/ownership/role requirements.

## Configuration

Item mechanics are additive fields on `TwSpawnerConfig` assets at:

`Server/Tamework/Items/Spawners/*.json`

New fields are direct members of the existing `Capture` object so its current
nested-field fallback logic applies:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `ChanceMode` | `Guaranteed` | `Guaranteed` preserves exact 3.0.0 capture behavior and ignores target chance/difficulty; `Probability` opts into this specification. |
| `Power` | integer `0` | Generic capability of this capture item. Must be non-negative. |
| `BaseChance` | number `1.0` | Base success probability in `[0,1]`. The default preserves guaranteed legacy capture. |
| `ChancePerPower` | number `0.0` | Non-negative additive chance for each power point above the role minimum. |
| `MinimumChance` | number `0.0` | Lower clamp in `[0,1]`. |
| `MaximumChance` | number `1.0` | Upper clamp in `[0,1]`, not less than `MinimumChance`. |
| `FailureCooldownMs` | integer `0` | Cooldown applied only after a resolved failed roll. |
| `FailureParticleSystem` | string/null | Optional failure VFX. |
| `FailureSoundEvent` | string/null | Optional failure sound. |

Target difficulty lives in a new role-scoped family:

`Server/Tamework/CapturePolicies/*.json` (`TwCapturePolicyConfig`)

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Enabled` | boolean `true` | Excludes this asset from resolution when false. |
| `Priority` | integer `0` | Higher priority wins; equal priority uses case-insensitive asset-ID order, then case-sensitive asset-ID order. |
| `RoleIds` | string array, empty | Exact source role IDs; explicit arrays replace inherited arrays. An enabled empty array is invalid rather than a global/default match. |
| `Difficulty` | object | Role-side chance policy; partial explicit objects inherit missing nested fields. |
| `Requirements` | spec array, empty | Namespaced, side-effect-free external eligibility requirements; explicit arrays replace inherited arrays. |

`Difficulty` fields:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `MinimumPower` | integer `0` | Lower power is ineligible and never rolls. |
| `Resistance` | number `0.0` | Additive chance removed before multiplication. |
| `ChanceMultiplier` | number `1.0` | Multiplies the adjusted chance; must be non-negative. |
| `MissingHealthBonus` | number `0.0` | Maximum additive bonus contributed by the target's clamped missing-health fraction; must be non-negative. |
| `GuaranteedAtPower` | integer/null | At or above this power, success is `1.0` after eligibility. |

Each `Requirements` entry mirrors Tamework's extension-spec vocabulary:

| Field | Type/default | Meaning |
| --- | --- | --- |
| `Id` | nonblank namespaced string | Registered capture requirement ID. |
| `Param` | string/null | Optional short parameter. |
| `Values` | string array, empty | Optional immutable values passed to the handler. |
| `JsonPayload` | valid JSON text/null | Optional bounded structured parameters; it is config, not mutable state. |

If no capture-policy config resolves for a role, the role-side values use the
listed defaults. This deliberately keeps species resistance out of every stone
item. Public API `0.9.0` does not include item-local role overrides; a later
API may add an advanced explicit replace-map only if a real
cross-mod use case cannot be represented by role priority.

`ChanceMode: Guaranteed` bypasses the entire `TwCapturePolicyConfig`, including
`Difficulty` and `Requirements`. It therefore records no target-policy config
revision and cannot be changed by a role policy added after the item was
authored. `ChanceMode: Probability` is the explicit opt-in to target-policy
resolution and custom capture requirements.

For an eligible role whose item uses `ChanceMode: Probability`:

```text
powerDelta = max(0, Power - MinimumPower)
missingHealthFraction = clamp(1 - currentHealth / maxHealth, 0, 1)
rawChance = (BaseChance
             + powerDelta * ChancePerPower
             + MissingHealthBonus * missingHealthFraction
             - Resistance)
            * ChanceMultiplier
effectiveChance = clamp(rawChance, MinimumChance, MaximumChance)
```

Exact stored/tamed role mapping occurs after success and does not change which
source-role policy is evaluated. A role with no resolved
`TwCapturePolicyConfig` uses the listed difficulty defaults. Health is sampled
only after terminal health/effect revalidation; a missing or non-positive
maximum-health value denies before rolling. Non-finite values,
`MissingHealthBonus < 0`, and probabilities outside `[0,1]` invalidate the
asset rather than being silently normalized.

Role resolution sorts by priority descending, case-insensitive asset ID
ascending, and finally case-sensitive asset ID ascending. This remains
deterministic for otherwise valid asset IDs that differ only by case. Enabled
assets with empty `RoleIds` are rejected with their asset ID; they never become
an implicit global policy.

### Example

```json
{
  "EmptyItemId": "Draconic_Stone",
  "FilledItemId": "*Draconic_Stone_State_Filled",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "NordicDrake",
      "Hydra",
      "RockDrakeT1",
      "RockDrakeT2",
      "RockDrakeT3"
    ]
  },
  "Capture": {
    "RequireTamed": false,
    "TamesTarget": true,
    "MaxHealthPercent": 20.0,
    "RequiredEffectId": "Tw_Status_Tranquilized",
    "ChanceMode": "Probability",
    "Power": 1,
    "BaseChance": 0.42,
    "ChancePerPower": 0.10,
    "MinimumChance": 0.05,
    "MaximumChance": 0.95,
    "FailureCooldownMs": 2500,
    "FailureParticleSystem": "HyDragon_Capture_Failed",
    "FailureSoundEvent": "SFX_HyDragon_Capture_Failed"
  }
}
```

This ordinary Draconic Stone example intentionally excludes `Wyvern_Mini`.
HyDragon must exclude both wild and tamed Miniwyvern roles from every ordinary
stone allowlist/policy because Miniwyverns are Soul Bond-exclusive and are
created through [companion provisioning](population-groups.md#generic-companion-provisioning),
not capture.

The Hydra target policy is authored once:

```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [ "Hydra" ],
  "Requirements": [
    {
      "Id": "hydragon:special_encounter_capture_ready",
      "Param": "grounded_phase"
    }
  ],
  "Difficulty": {
    "MinimumPower": 3,
    "Resistance": 0.20,
    "ChanceMultiplier": 0.70,
    "MissingHealthBonus": 0.25,
    "GuaranteedAtPower": 5
  }
}
```

A higher-tier child may inherit all policy fields and explicitly replace only
the power:

```json
{
  "Parent": "HyDragonDraconicStone",
  "EmptyItemId": "Draconic_Stone_Ancient",
  "FilledItemId": "*Draconic_Stone_Ancient_State_Filled",
  "Capture": {
    "Power": 5
  }
}
```

## Inheritance and reload

- Omitted `Capture` inherits the full parent section.
- Explicit `Capture` inherits every missing nested field, including the new
  scalar fields.
- `ChanceMode` is an ordinary scalar: omission inherits; an explicit
  `Guaranteed` overrides an inherited `Probability` mode.
- Scalar zero and `false` values are explicit values, not omission.
- `TwSpawnerConfig`'s existing parent fallback method delegates to its
  nested-aware overload; the overload is extended for every field above.
- `TwCapturePolicyConfig` follows the same parent-fallback contract as
  `TwCompanionConfig`: omitted `Difficulty` inherits fully, an explicit object
  inherits missing nested fields, and explicit `RoleIds` replaces the parent
  array. Explicit `Requirements` likewise replaces rather than appends to the
  parent array.
- Codec documentation states the inheritance and map replacement behavior.
- `/tw reloadconfig` rebuilds only the item-side spawner feature registry.
  `TwCapturePolicyConfig` updates through normal asset loaded/removed events.
  Invalid updates retain the last valid compiled role index and report the
  asset ID.
- An attempt is pinned to both its resolved spawner and target-policy config IDs
  and revisions. Reload never changes the formula for a durably prepared
  attempt.

## Attempt lifecycle

### Identity

`CaptureAttemptCoordinator` creates a cryptographically unpredictable attempt ID
for every capture entry point. A channeled interaction allocates it on
`TameworkCaptureChannel.Begin`. A non-channel `TameworkCaptureOwner`/
`TameworkCaptureWild` action allocates it at interaction dispatch, before the
first asynchronous hop, and carries it through the terminal action context.
Public/direct callers supply a namespaced idempotency key that maps to the same
attempt on retry. The registry binds the attempt to actor UUID, target UUID,
canonical profile ID/revision when known, source inventory/slot fingerprint,
spawner config ID/revision, optional target-policy config ID/revision, and
expiration. `Cancel` closes an unrolled attempt.

No `Probability` path may enter `captureFromNpcAction()` without a stable
attempt ID. Failure to allocate/persist or propagate the ID denies before
entropy. A retry/duplicate callback must reuse the dispatch or caller key; it
must not generate a UUID at terminal completion.

Retries of `Complete` reuse that attempt ID. A new channel after terminal
failure is a new gameplay attempt and receives a new ID.

### Commit sequence

1. Resolve the exact empty source stack and prepared channel attempt.
2. Revalidate terminal role, source item, target identity, health threshold,
   required effect, range, owner policy, and target liveness on the owning
   world thread. Resolve registered custom requirements only for
   `ChanceMode: Probability`; guaranteed mode bypasses the target policy.
3. Prepare all required canonical profile and owner/claim/group admission using
   the same operation correlation ID. If authority is not ready or capacity is
   denied, cancel without rolling.
4. Persist an unrolled capture-attempt journal row containing the immutable
   inputs and resolved config revisions. Guaranteed mode records the target
   policy as explicitly bypassed rather than resolving a matching asset.
5. Fence the target entity/profile revision and exact source slot. Revalidate
   all mutable requirements immediately before resolving the attempt.
6. Atomically compare-and-set the journal from `PREPARED` to either
   `RESOLVED_FAILURE` or `RESOLVED_SUCCESS/APPLYING`, recording the effective
   chance, formula inputs, resolution timestamp, and a nullable entropy sample.
   The same transaction records the failure cooldown deadline when applicable.
   This is the only operation allowed to obtain random entropy for that attempt
   ID, and it obtains none for guaranteed outcomes.
7. On failure, cancel prepared population capacity and leave the NPC untouched.
   Finalize the source according to the snapshotted `SourceConsumption` policy,
   activate the already-recorded coordinator cooldown, dispatch failure
   feedback once, mark the attempt terminal, and emit the post-commit event.
   `ResolvedAttempt` cannot publish the result until one source item is durably
   consumed; the default policy leaves the item unchanged.
8. On success, return to the owning world thread, recheck the target/source
   fences, claim admission for apply, then run the existing capture finalizer.
9. Commit profile, lifecycle `CAPTURED`, population, capture metadata, and item
   replacement through the existing durable operation boundaries. Mark the
   attempt `COMMITTED` and emit events only after durable success.
10. If post-roll apply cannot proceed, retain the successful recorded outcome
    while recovery retries or compensates the same operation. It never rolls
    again.

The random provider is invoked only by this terminal commit service; it is never
called by `SpawnerCapturePolicyService.canCapture()`, channel-begin validation,
or another reusable eligibility predicate. Those paths may run more than once.

No persistence stage may block the world thread. A target/source fence held
across an asynchronous hop is a logical revision fence, not a Java monitor.

### Journal states

`PREPARED`, `RESOLVED_FAILURE`, `RESOLVED_SUCCESS`, `APPLYING`, `COMMITTED`,
`CANCELED`, `COMPENSATING`, and `QUARANTINED` are durable. Terminal rows may be
compacted after the configured idempotency horizon, but a compact tombstone
must prevent a late duplicate callback from becoming a fresh roll.

### Failure behavior

- Precondition or cap denial: no roll, no mutation, no failure cooldown.
- Player/channel cancellation: no roll and no mutation.
- Resolved failed probability: no NPC/owner/profile mutation. Apply
  `FailureCooldownMs` and configured presentation; retain the source under the
  default policy or consume exactly one under `ResolvedAttempt`.
- Random provider failure: fail closed before resolution and keep the attempt
  retryable with the same ID; do not substitute `Math.random()`.
- Persistence unavailable: fail closed before roll or quarantine a previously
  resolved success for recovery.
- Source stack mismatch after a success roll: do not capture; compensate/cancel
  admission and close or quarantine the same attempt according to durability.
- Cosmetic failure after terminal result does not alter the result.
- If the coordinator cannot hydrate/enforce a still-live durable cooldown, new
  actor/config attempts fail closed until the row is readable; the resolved
  outcome is never converted into another roll.

Failure cooldown authority is the capture-attempt journal/coordinator, keyed by
actor UUID and spawner config ID. The terminal failure row durably records its
`failure_cooldown_until_ms`; a later channel for that actor/config is denied
before rolling until it expires. The coordinator never writes cooldown metadata
to the source stack. Default-policy stacks remain byte-for-behavior unchanged;
`ResolvedAttempt` stacks are exact-CAS decremented by the separate durable spend
step. Journal reads/writes remain asynchronous and must not block the world
thread.

## Public API and events

Capability `CAPTURE_POLICY` advertises the implemented capture-policy surface.

Fail-closed default methods on `InteractionExtensionApi` provide namespaced
`CaptureRequirementHandler`
registration. The immutable context includes attempt/config IDs, terminal vs
pre-commit phase, target ref/role/store, actor UUID/player when safely available,
held item ID, health fraction, and expected profile revision. Handlers execute
synchronously on the owning world thread, must be deterministic and
side-effect-free, and return a stable allow/deny reason. Missing handlers,
exceptions, or registration-generation changes deny before rolling. Events are
notifications and cannot substitute for this pre-commit requirement seam.

Immutable `SpawnerCaptureMechanicsView` and `CapturePolicyConfigView`
objects through versioned config-read methods. Do not add constructor fields to
the existing `SpawnerConfigView`.

`CaptureAttemptResolvedEvent` contains:

- attempt and operation IDs;
- spawner and target-policy config IDs/revisions and source item ID;
- actor UUID, target UUID, profile ID when known, and source role ID;
- power, minimum power, current/max health, clamped missing-health fraction,
  configured condition bonus, effective chance, and guarantee flag;
- outcome (`CAPTURED`, `FAILED_ROLL`) and stable reason code;
- resolved and emitted timestamps.

The event contains the effective roll only in admin/debug builds; normal public
payloads need the probability and result, not secret entropy. Dispatch occurs
after the result is durable. Recovered success emits at most one logical event,
identified by attempt ID.

## Diagnostics and migration

- Existing configs decode as `ChanceMode=Guaranteed`. They skip the entire
  target policy, including power/difficulty/chance and custom requirements, and
  preserve exact 3.0.0 capture behavior even if a later role policy matches
  their target.
- No released HyDragon filled items exist, so this feature defines no
  HyDragon-specific item migration or compatibility path.
- `/tw diagnose capture-attempt <id>` reports summary counters for prepared,
  resolved-failure, applying, quarantined, recovered, and duplicate callbacks.
- Admin output shows formula inputs, config revision, state, population token
  correlation, and persistence incident ID. Player output never reveals random
  entropy.
- Startup schema migration creates the attempt journal transactionally with the
  normal SQLite backup/migration boundary.
- Stale prepared attempts are canceled; resolved successes are recovered or
  compensated, never discarded and rerolled.

## Implementation file map

| Area | Existing anchor | Implemented responsibility |
| --- | --- | --- |
| Item asset schema | `config/assets/TwSpawnerConfig` | Codec, validation, inheritance, conversion of new item mechanics |
| Target asset schema | new `config/assets/TwCapturePolicyConfig` and role index | Role resolution, difficulty codec, validation, inheritance |
| Runtime feature DTO | `config/ItemFeatureConfig` | Immutable compiled capture policy |
| Channel/eligibility | `items/CaptureChannel*`, `items/SpawnerCapturePolicyService` | Attempt identity and side-effect-free terminal eligibility only |
| Chance commit | `SpawnerCaptureChanceService`, called by `SpawnerFeatureHandler.captureFromNpcAction` | Exactly-once formula/entropy resolution after source and target fencing |
| Orchestration | `items/SpawnerFeatureHandler` | Coordinate services only; do not embed formula/persistence logic |
| Finalization | `items/SpawnerCaptureFinalizerService` | Apply only a durably successful attempt |
| Population | `ownership/OwnerPopulationRuntime`, admission coordinators | Prepare/cancel owner, claim, and group capacity before a roll |
| Persistence | `persistence/sqlite`, `persistence/operation` | Capture-attempt schema/repository/journal/recovery |
| Public API | `api`, `api/internal/TameworkApiImpl`, event bus | Capability, config view, immutable event |
| Capture extensions | `api/InteractionExtensionApi` or focused capture facade; `api/internal` registry | Namespaced side-effect-free requirement registration and generation fencing |
| Diagnostics | `persistence/diagnostics`, `commands`, `selftest` | Inspection, counters, repair/recovery fixture |

Implemented classes remain focused, including `SpawnerCaptureChanceService`,
`CaptureAttemptRepository`, `CaptureAttemptRecoveryService`, and
`CaptureAttemptDiagnosticsService`; `SpawnerFeatureHandler` remains an
orchestrator.
`TwSpawnerConfig` and `SpawnerFeatureHandler` are already beyond the preferred
new-class size, so new codecs/calculation/journal behavior must be extracted
rather than expanding either multi-domain class.

## Acceptance tests

### Codec and inheritance

1. A legacy config with no new fields resolves `ChanceMode=Guaranteed`, skips
   target policy difficulty and requirements entirely, and preserves exact
   3.0.0 capture behavior.
2. Every item and target scalar boundary (`0`, `1`, zero cooldown, power zero)
   decodes exactly.
3. Invalid probabilities, negative power/cooldown/multiplier/chance-per-power or
   health bonus, non-finite values, and `MinimumChance > MaximumChance` reject
   the asset.
4. Omitted `Capture` inherits the entire parent section.
5. A partial explicit `Capture` inherits every missing nested field.
6. `TwCapturePolicyConfig` omitted `Difficulty` and partial explicit
   `Difficulty` obey full/nested inheritance respectively.
7. Explicit target-policy `RoleIds` replaces rather than merges the parent
   array.
8. Missing target policy uses documented defaults.
9. Child `Power=0` and explicit `ChanceMode=Guaranteed` are not mistaken for
   omission.
10. `/tw reloadconfig` updates only item mechanics, while target-policy asset
    events atomically swap a valid role index and publish `CAPTURE_POLICY`.

### Formula and policy

11. `Guaranteed` mode never resolves target difficulty/requirements or invokes
    the random provider, even when a matching role policy defines both.
12. `Probability` minimum power denial occurs before random-provider invocation.
13. The formula matches table-driven values below, at, and above minimum power.
14. Minimum/maximum clamps are inclusive and deterministic.
15. `MissingHealthBonus` is zero-compatible, uses clamped health, and matches
    exact full-health, threshold-health, zero-health, overheal, and invalid-max
    boundary cases after terminal revalidation.
16. `GuaranteedAtPower` produces success without invoking entropy.
17. Higher-priority role policy wins deterministically; equal priorities use
    case-insensitive then case-sensitive asset-ID ordering, and disabled
    policies do not resolve.
18. The source wild role, not its tamed override, selects the role policy.
19. Health, required effect, distance, ownership, and allowed-role requirements
    are enforced at terminal completion and immediately before commit. Channel
    begin may establish the channel/aura before terminal health/tranquilizer
    requirements pass, preserving current behavior.

### Atomicity and idempotency

20. A failed roll leaves role, owner, profile, health, effects, and entity
    unchanged. The default policy leaves the source unchanged;
    `ResolvedAttempt` decrements its quantity exactly once. Cooldown state
    exists only in the attempt coordinator.
21. A failed roll durably records exactly one actor/config cooldown and sends
    feedback once even when `Complete` is delivered twice; restart preserves
    the remaining cooldown without an additional stack mutation.
22. Duplicate completion callbacks read one journal result and invoke entropy
    exactly once only for an outcome that requires a sample; guaranteed paths
    invoke it zero times.
23. Concurrent completion for the same attempt permits one CAS winner.
24. Capacity denial or unavailable population authority cancels before rolling.
25. Reload between begin and prepare binds the attempt to one item-policy and
    one target-policy revision; it never combines formulas.
26. Target health/effect/role, source slot, owner, and profile revision changes
    at each asynchronous boundary cancel safely.
27. Success followed by source-finalization failure does not reroll and does not
    create a second profile or active projection.
28. Failure at each persistence checkpoint converges after restart to one
    terminal outcome.
29. Resolved-success recovery uses the recorded result and same operation ID.
30. Terminal journal compaction retains a tombstone sufficient to reject late
    duplicate callbacks.
31. No persistence wait occurs on the Hytale world thread.

### Compatibility, API, and diagnostics

32. Existing deterministic spawner tests remain unchanged and pass.
33. Wild capture still atomically tames/owns only after a successful result.
34. Public config views are immutable and old `SpawnerConfigView` binaries link.
35. The event fires after durability, once logically, with defensive data.
36. Listener exceptions cannot change capture outcome.
37. `/tw api test` covers guaranteed success with an RNG provider whose
    invocation count remains exactly zero, deterministic injected probabilistic
    failure, duplicate callback, and capability presence.
38. `/tw diagnose` correlates an attempt with both config revisions, population
    operation, persistence incident, and recovery disposition.
39. Performance tests show no per-tick allocation or scan proportional to all
    profiles; work is bounded to an active channel/attempt.
40. Duplicate `EmptyItemId` spawner bindings are rejected deterministically
    instead of overwriting by asset-map iteration order.
41. Custom capture requirements run at terminal completion and immediately
    before commit, never during reusable chance calculation.
42. Missing/throwing/unregistered custom requirement handlers fail closed before
    entropy, return a stable reason, and do not mutate target/item/owner state.
43. Explicit `Requirements` replaces the inherited array, and an in-flight
    attempt cannot mix handler registration generations.
44. An enabled policy with empty `RoleIds` is rejected and can never become a
    global policy; a disabled empty policy remains inert.
45. Every ordinary HyDragon Draconic Stone rejects `Wyvern_Mini` and
    `Tamed_Wyvern_Mini` without rolling, while Soul Bond provisioning remains
    the sole creation path.
46. Channeled, `TameworkCaptureOwner`, and `TameworkCaptureWild` probability
    paths all propagate one stable attempt ID; duplicate direct-action callbacks
    reuse it and resolve at most one entropy sample.
47. A probability action with missing/unpersistable attempt identity fails
    before entropy or mutation rather than generating an ID at completion.

# Companion Movement Speed Design

## Goal

Give every companion one consistent movement-speed calculation that applies both
to normal NPC movement and native player-controlled mounting. The calculation
must support species baselines, equipped attachments, traits, level growth, and
purchased talents.

## Scope

This design covers companions mounted through the native Tamework mount path
(`MountMode` unset or `NATIVE`). It does not revive or extend the legacy
`TameworkRide` controller. `TameworkMountedGlide` and `TameworkAvatarFlight`
remain separate movement modes and are not changed by this feature.

The feature also preserves normal, unmounted AI movement speed changes. It is
not limited to riding.

## Background

Native mounting changes the rider's `MovementManager` using the role's
`MountMovementConfig`; it does not use the mounted NPC's motion-controller
speed. Therefore an `EntityEffect` on the companion is appropriate for normal
NPC movement, but is not sufficient to control the speed of a native-mounted
rider.

Hytale 0.5.7 supplies both required primitives:

- `NPCEntity#getCurrentHorizontalSpeedMultiplier` multiplies active entity
  effects' `ApplicationEffects.HorizontalSpeedMultiplier` values.
- `MovementManager` accepts `MovementSettings`, applies them as its defaults,
  and sends an `UpdateMovementSettings` packet to the rider.

Tamework already combines trait, level-growth, and talent multipliers under a
shared effect key through `CompanionProgressionModifierService`.

## Speed Model

The raw effective multiplier is:

```text
speciesBaseMultiplier
  x product(matchingAttachmentMultipliers)
  x progressionMoveSpeedMultiplier
```

`progressionMoveSpeedMultiplier` is the existing resolved
`MoveSpeedMultiplier` from traits, level growth, and purchased talents.

The result is clamped to the active role config's explicit minimum and maximum,
then quantized to the nearest 5% step. The quantized value is the single source
of truth for both unmounted NPC movement and native-mounted rider movement.
This avoids visible disagreement between the two modes and lets the NPC path
use static `EntityEffect` assets.

The initial supported range is 0.50 through 2.00, inclusive. The implementation
will supply a `Tw_MovementSpeed_050` through `Tw_MovementSpeed_200` asset set
at 5% increments. The existing `Tw_Trait_MoveSpeed_*` assets remain readable
for compatibility but are no longer the effect family managed by the runtime.

## Configuration

Introduce a role-scoped, inheritable `TwCompanionMovementConfig` asset family
under:

```text
Server/Tamework/CompanionMovement/*.json
```

The family follows normal Tamework parent fallback and enabled/priority role
resolution. For a role, one highest-priority matching config supplies the
species baseline and clamp bounds. If no config matches, the feature uses
neutral values: baseline `1.0`, range `0.50` to `2.00`, and no attachment
modifiers. Existing progression speed behavior therefore remains active without
requiring a new config.

```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": ["Tamed_Moose_Bull", "Tamed_Moose_Cow"],
  "BaseMoveSpeedMultiplier": 0.90,
  "MinMoveSpeedMultiplier": 0.50,
  "MaxMoveSpeedMultiplier": 2.00,
  "AttachmentModifiers": [
    {
      "Slot": "Saddle",
      "Values": ["Yes"],
      "Multiplier": 1.10
    }
  ]
}
```

Every matching attachment rule multiplies the result. This intentionally lets
different equipment slots stack. Authors should avoid overlapping rules for the
same slot/value pair unless they intend that stacking. An omitted config field
inherits through the established asset fallback contract; explicit arrays
replace the parent array.

`MountMovementConfig` stays on the role. It provides the species' full native
rider movement profile (jumping, acceleration, and handling); companion
movement config only scales that profile's `BaseSpeed` at runtime.

## Runtime Architecture

### Pure resolution

`CompanionMovementSpeedResolver` is a pure domain service. Given the role id,
stored effective attachments, and progression multiplier, it returns a value
object containing the raw multiplier, clamped multiplier, quantized multiplier,
and the selected config/rules for diagnostics and testing.

`CompanionProgressionModifierService` remains the only owner of trait, level,
and talent aggregation. The new resolver consumes its `MoveSpeedMultiplier`
result rather than duplicating that logic.

### NPC effect application

`CompanionMovementSpeedEffectService` owns exactly one Tamework-managed
`Tw_MovementSpeed_*` effect per companion. It adds, replaces, or removes that
effect as the resolved speed changes and invalidates the NPC horizontal-speed
cache after a change.

It removes only the reserved `Tw_MovementSpeed_*` family and the legacy
`Tw_Trait_MoveSpeed_*` family during migration. It never removes unrelated
base-game or third-party effects.

The existing trait-only move-speed path is replaced by this service so
progression speed cannot be applied twice.

### Native rider application

`NativeMountMovementSettingsService` begins with the active role's
`MountMovementConfig` packet, makes a copy, scales its `BaseSpeed` by the
quantized companion multiplier, and applies/updates it through the current
rider's `MovementManager`.

It only runs for a valid active native mount. It never changes an unmounted
player, and it does not attempt to restore rider settings on dismount: the
existing native dismount path retains responsibility for that reset.

### Synchronization

`CompanionMovementSpeedSyncSystem` reconciles a compact fingerprint containing
role, effective attachment selections, progression multiplier, selected
movement-config revision, and native mount state. It handles world load,
external API writes, breeding/spawn restoration, and config changes without
requiring each producer to know every movement consumer.

The held-item attachment interaction requests an immediate reconciliation after
a successful attachment mutation so adding or removing a saddle does not wait
for the periodic sweep. The periodic system remains the correctness backstop.

Tick/system code resolves any rider through the active world/store using stable
identity and `store.getComponent`; it must not call
`PlayerRef.getComponent(Player)` or scan `Universe.getPlayers()`.

## Error Handling and Compatibility

- Missing movement config, entity-effect asset, component type, or active
  rider causes that application path to be skipped safely and logged once with
  the role/config/effect context.
- A missing companion movement config is valid and resolves to neutral species
  and attachment values.
- Invalid numeric modifiers (`NaN`, infinity, or non-positive values) are
  rejected by codec validation where possible and normalize to their documented
  neutral/fallback value at runtime as a defensive boundary.
- Existing saves with `Tw_Trait_MoveSpeed_*` effects migrate on the next
  reconciliation; no player action or data migration is required.

## Test Plan

Unit tests cover:

- role config selection, inheritance, priorities, and neutral fallback;
- species, attachment, and progression multiplication;
- clamp behavior and 5% quantization at boundaries;
- multiple matching attachment rules and unsupported/missing attachment values;
- managed-effect replacement and legacy-effect removal without touching an
  unrelated effect;
- copying and scaling native movement settings while preserving non-speed
  properties.

System and architecture tests cover:

- reconciliation after a stored attachment change and a progression change;
- rider settings update only while the companion has an active native mount;
- normal dismount leaves the existing reset path intact;
- tick code contains no prohibited player-component access pattern.

Run `./mvnw test` and the project ECS/thread-safety grep before completion.

## Documentation

Add a config-reference page with field definitions, inheritance behavior,
stacking rules, and a saddled-species example. Update the configuration index,
player/modder documentation as appropriate, and `CHANGELOG.md` with the final
player-facing behavior once the feature is implemented.

# Optimized Interactions (TwInteractionConfig)

## Overview
Tamework replaces large NPC interaction instruction trees with a single action call. The flow is driven by `TwInteractionConfig` assets and executed by `TameworkInteract`.

## Asset location
`<ModRoot>/Server/Tamework/Interactions/*.json`

## Config resolution
- If action field `ConfigId` is provided, that config id is used.
- Otherwise the role param named by `TwGlobalConfig.InteractionDefaults.InteractionConfigParam` (default `InteractionConfigId`) is used when present.
- Otherwise the enabled config with the highest `Priority` whose `RoleIds` includes the NPC role is selected.

`Priority` defaults to `0`. Higher values win. For equal priorities, current asset-map iteration order applies.

## Global defaults
Parameter/alarm names used by the interaction system are defined under `TwGlobalConfig.InteractionDefaults`.
If you rename these keys, update role params and/or action overrides accordingly.

## Interaction order
`Interactions` is evaluated in authored order. The first enabled entry whose requirements pass is executed.

## Prompts (optional)
If the role runs `TameworkInteractPrompt`, Tamework shows the first matching entry's prompt.

Per-entry prompt controls:
- `PromptHint` (translation key)
- `ShowPrompt` (hide prompt for specific entries)

Default prompt keys:
- `server.interactionHints.generic`
- `server.interactionHints.tame`
- `server.interactionHints.feed`
- `server.interactionHints.harvest`
- `server.interactionHints.harvestContext`
- `server.interactionHints.mount`
- `server.interactionHints.modeCycle`
- `server.interactionHints.breed`
- `server.interactionHints.custom`

Define these in `Server/Languages/en-US/server.lang` without the `server.` prefix.

## Cooldowns
- Cooldowns are real-time seconds.
- Entry `CooldownSeconds` overrides config `Cooldowns.InteractionSeconds`.
- Alarm id format: `<InteractionCooldownAlarmPrefix>_<ConfigId>_<index>`.
- Prefix comes from `TwGlobalConfig.InteractionDefaults.InteractionCooldownAlarmPrefix`.

Use `/tw getalarm` to inspect cooldown/harvest alarms.

## Preset interactions
Preset entries provide default behavior plus optional `Requires` + `Effects` add-ons.

### Tame
Common fields:
- `UseLovedItems`
- `ItemsInHand`
- `ItemsParam`
- `Role`
- `RoleParam` (overrides `Role` when resolved)

Behavior:
- Requires untamed NPC and matching held item.
- Sets tamed + owner, consumes held item, optional role swap.

### Feed
Common fields:
- `UseLovedItems`
- `ItemsInHand`
- `Heal`
- `ItemsParam`

Behavior:
- Requires tamed NPC and matching held item.
- Heals/consumes item.
- Applies shared happiness gain (`TwHappinessConfig` or defaults) with trait scaling via `HappinessGainMultiplier`.
- Applies manual needs refill rules from `TwNeedsConfig.ManualRefill` when configured.
- Companion hydration can also consume feed trough water charges through needs/runtime systems; trough water states can be refilled via compatible bucket interactions.

### Harvest
Common fields:
- `RequireTamed`
- `RequireHarvestable`
- `RequireHarvestAlarmReady`
- `RequireHarvestInteractionContext`

Behavior:
- Uses role params named by `TwGlobalConfig.InteractionDefaults` for harvestability/context.
- Uses alarm `TwGlobalConfig.InteractionDefaults.HarvestAlarmName` (default `Harvest_Ready`).
- Runs `$Harvest` state when valid.

### Mount
Common fields:
- `RequireTamed`
- `RequireOwner`
- `RequireMountable`
- `RequireCrouching`

Behavior:
- Attempts mount via `NPCMountComponent`.
- Uses mount anchor params.
- Hides active custom nameplates while mounted and restores on dismount.

### ModeCycle
Common fields:
- `RequireTamed`
- `RequireOwner`
- `ShowFloatingText`
- `ShowUiMessage`
- `Cycle`

Default cycle when empty: `Hold -> Idle -> Defend`.

### Breed
Common fields:
- `RequireTamed`
- `MinHappiness`
- `FertilityBonus`
- `ManualSelectionSeconds`

Behavior:
- Ensures progression state exists.
- Enforces `TwBreedingConfig.Eligibility` gates (`RequireTamed`, `RequireAdult`, `RequireNotSleeping`, `RequireNotInCombat`).
- Uses effective fertility: `(sharedHappiness * FertilityMultiplier) + FertilityBonus`.
- Manual breeding marks the interacted NPC for that player only. The same player must interact with both intended NPCs before `ManualSelectionSeconds` expires.
- Manual breeding is independent from `TwBreedingConfig.PassiveBreeding.Enabled`, the `/tw settings` passive breeding toggle, and the per-NPC breeding enable toggle. Cooldowns and eligibility gates such as tame, adult, ownership, gender, and role compatibility still apply.
- `MinHappiness` is ignored when the happiness system or breeding happiness requirement is disabled.
- When a manually selected pair is found: applies parent cooldown, pair movement, hearts, delayed offspring spawn.
- Pairing can require the same role, require different adult roles in one lifecycle family, allow any adult in one lifecycle family, or explicitly allow any role through `TwBreedingConfig.Pairing.RoleCompatibility`.
- If `TwBreedingConfig.Gender.Enabled` and `RequireDifferentGender` are enabled, partner selection also requires one male and one female companion.
- Offspring flow supports baby-role preference, persisted weighted adult-role selection, life-stage initialization, trait/attachment inheritance, and growth timing.

## Custom interactions
`Type: "Custom"` exposes full `Requires` + `Effects` control.

Example:
```json
{
  "Type": "Custom",
  "Requires": { "All": { "IsTamed": true } },
  "Effects": { "SetState": { "State": "Idle" } }
}
```

### `TameworkLaunchProjectile`
Launches a projectile using a solved high-angle ballistic arc instead of the source entity's current look pitch.

Fields:
- `ProjectileId` required projectile asset id.
- `Target` optional enum: `USER`, `OWNER`, `TARGET`. Defaults to `TARGET`.
- `TargetSlot` optional NPC marked target slot. When present, Tamework first tries the source NPC's marked target in that slot and falls back to `Target` resolution if none is present.
- `YawSpreadDegrees` optional symmetric yaw spread applied after the arc is solved.
- `PitchSpreadDegrees` optional symmetric pitch spread applied after the arc is solved.
- `FailIfNoSolution` optional bool. Defaults to `true`.
- `TrajectoryMode` optional enum: `HIGH_ANGLE` or `DIRECT`. Defaults to `HIGH_ANGLE`.
- `RandomAroundSourceMinRadius` optional inner radius for a random landing point centered on the source entity.
- `RandomAroundSourceMaxRadius` optional outer radius for a random landing point centered on the source entity. When greater than `0`, this mode overrides entity-target resolution.
- `RandomAroundSourceVerticalOffset` optional Y offset applied to the random landing point.
- `ImpactEffect` optional nested block. When present with positive values, the projectile applies an `EntityEffect` in a radius at its final impact position.
- `LingeringHazard` optional nested ground-hazard block. When present with positive values, the spawned projectile creates a hidden lingering damage zone when it dies.

`ImpactEffect` fields:
- `EffectId` required entity effect asset id.
- `Radius` application radius around the projectile impact point.
- `ExcludeSource` optional bool, defaults to `true`.

`LingeringHazard` fields:
- `Radius` damage radius around the projectile impact point.
- `DurationSeconds` total linger time after impact.
- `TickIntervalSeconds` time between damage pulses.
- `DamagePerTick` damage applied on each pulse.
- `ExcludeSource` optional bool, defaults to `true`.
- `SourceTypeId` optional environment-source id used when the original shooter can no longer be resolved.
- `EffectId` optional entity effect asset id to reapply on each hazard pulse.

Behavior:
- Uses the projectile's `MuzzleVelocity` and `Gravity` to solve the high-angle lob when `TrajectoryMode` is `HIGH_ANGLE`.
- Uses a direct point-at-target pitch when `TrajectoryMode` is `DIRECT`.
- Uses the normal projectile spawn path after solving, so projectile asset offsets such as `VerticalCenterShot`, `HorizontalCenterShot`, `DepthShot`, and `PitchAdjustShot` still apply.
- If no valid arc exists and `FailIfNoSolution` is `true`, the interaction fails cleanly.
- Random-around-source targeting samples a uniform point in the authored radius band, which is useful for source-centered area denial barrages.
- `ImpactEffect` applies on projectile removal, which lets a single authored effect cover direct hits and explosion splashes.
- `LingeringHazard` damage is driven server-side from the projectile's final transform position when the projectile is removed.
- Player movement effects are handled by the base game; Tamework does not resync player `HorizontalSpeedMultiplier` values.

Example:
```json
{
  "Type": "TameworkLaunchProjectile",
  "ProjectileId": "Hydra_Rain_Ice_Ball",
  "TargetSlot": "CAETargetSlot",
  "TrajectoryMode": "DIRECT",
  "YawSpreadDegrees": 4.0,
  "PitchSpreadDegrees": 2.0
}
```

Area denial example:
```json
{
  "Type": "TameworkLaunchProjectile",
  "ProjectileId": "Hydra_Rain_Ice_Ball",
  "RandomAroundSourceMinRadius": 4.0,
  "RandomAroundSourceMaxRadius": 10.0,
  "RandomAroundSourceVerticalOffset": 0.0,
  "ImpactEffect": {
    "EffectId": "Chilled",
    "Radius": 5.0,
    "ExcludeSource": true
  },
  "LingeringHazard": {
    "Radius": 4.0,
    "DurationSeconds": 6.0,
    "TickIntervalSeconds": 1.0,
    "DamagePerTick": 5.0,
    "ExcludeSource": true,
    "EffectId": "Chilled"
  }
}
```

## Requirements
`Requires` has two buckets:
- `All`: every listed requirement set must pass.
- `Any`: at least one listed requirement set must pass.

Within each requirement type array, any one entry can satisfy that type.

### Basic booleans
- `LovedItems`
- `IsHarvestable`
- `IsMountable`
- `IsTamed`
- `IsNotTamed`
- `PlayerHandEmpty`
- `PlayerCrouching`
- `PlayerIsOwner`
- `HarvestAlarmReady`
- `HarvestInteractionContext`

### `ItemsInHand`
Fields:
- `Items`
- `ItemsParam`
- `Quantity`
- `Operator` (`AnyOf`, `NoneOf`)

### `ItemsInInventory`
Fields:
- `Items`
- `ItemsParam`
- `Quantity`

### `ItemsEquipped`
Fields:
- `Items`
- `ItemsParam`
- `Slots` (`Head`, `Chest`, `Hands`, `Legs`, `Armor`, `Equipped`, `Utility`, `Accessory`, `Accessories`)

### `Parameter`
Fields:
- `Name`
- `Operator` (`Equals`, `NotEquals`, `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual`)
- `Match` (`Any`, `All`)
- `Value`

### `NpcHealthPercent`
Fields:
- `Operator`
- `Value` (`0-100` scale)

### `AlarmState`
Fields:
- `AlarmParam`
- `Name`
- `State` (`Unset`, `Active`, `Passed`)

### `NpcState`
Fields:
- `State`
- `SubState`

### `PlayerMovementState`
Field:
- `State` (`Crouching`, `Walking`, `Running`, `Sprinting`, `Idle`, `Mounting`, `Sleeping`)

### `InteractionContext`
Fields:
- `Context`
- `ContextParam`

## Effects
Common effect families:
- State/ownership: `SetTamed`, `SetOwner`, `SetState`, `SetRole`, `ModifyStats`
- Item operations: `RemoveItemsHand`, `AddItemsHand`, `RemoveItemsInventory`, `AddItemInventory`
- Presentation: `ShowFloatingText`, `ShowUiMessage`, `PlaySound`, `SpawnParticles`
- Utility: `DropItem`, `Mount`, `TriggerNpcHook`

`SpawnParticles` supports node/attachment targeting:
- `AttachTarget` (`Position`, `Entity`, `Node`)
- `AttachNode`
- `OffsetParam`
- `PlayerOnly`

## Action usage in roles
```json
"Actions": [
  {
    "Type": "LockOnInteractionTarget",
    "TargetSlot": { "Compute": "MasterTargetSlot" }
  },
  { "Type": "TameworkInteract" }
]
```

Optional action overrides:
- `ConfigId`
- `LovedItems`
- `IsMountable`
- `IsHarvestable`
- `HarvestInteractionContext`

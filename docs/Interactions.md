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

Behavior:
- Ensures progression state exists.
- Enforces `TwBreedingConfig.Eligibility` gates (`RequireTamed`, `RequireAdult`, `RequireNotSleeping`, `RequireNotInCombat`).
- Uses effective fertility: `(sharedHappiness * FertilityMultiplier) + FertilityBonus`.
- When ready pair found: applies parent cooldown, pair movement, hearts, delayed offspring spawn.
- Offspring flow supports baby-role preference, life-stage initialization, trait/attachment inheritance, and growth timing.

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

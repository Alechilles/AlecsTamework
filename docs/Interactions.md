# Optimized Interactions (TwInteractionConfig)

## Overview
Tamework provides an optimized interaction pipeline that replaces large NPC interaction instruction trees with a single action call. The pipeline is driven by `TwInteractionConfig` assets and executed by the `Action_Tamework_Interact` NPC action.

## Asset location
`<ModRoot>/Server/Tamework/Interactions/*.json`

## Config resolution
- If the action passes `ConfigId`, that asset id is used directly.
- Otherwise the first enabled config whose `RoleIds` contains the NPC role id is selected.
- If multiple configs match a role, selection order depends on asset map iteration. Avoid overlapping `RoleIds` or use `ConfigId` overrides.

## Interaction order
Interactions are evaluated in the order listed in the `Interactions` array. The first enabled entry whose requirements pass is executed.

## Common fields
- `Enabled` defaults to true when omitted.
- `CooldownSeconds` is defined on each interaction entry but is not enforced by the current implementation.
- `Cooldowns.InteractionSeconds` exists at the config level but is not enforced by the current implementation.

## Preset interactions
Preset entries provide simple defaults with minimal configuration.

### Tame
Fields:
- `ConsumeItem` (default true when omitted)
- `UseLovedItems` (default true)
- `ItemsInHand` (item id or array)
- `ItemsParam` (role parameter name that returns string or string array)

Requirements:
- NPC must not be tamed.
- Held item must match the resolved item list.

Effects:
- Sets `TameworkTamedComponent` true.
- Sets `TameworkOwnerComponent` to the interacting player.
- Consumes the held item if `ConsumeItem` is true.

### Feed
Fields:
- `ConsumeItem` (default true)
- `UseLovedItems` (default true)
- `ItemsInHand` (item id or array)
- `ItemsParam` (role parameter name)

Requirements:
- NPC must be tamed.
- Held item must match the resolved item list.

Effects:
- Currently a placeholder (no heal logic yet).
- Consumes the held item if `ConsumeItem` is true.

### Harvest
Fields:
- `RequireTamed` (default true)
- `RequireHarvestable` (default true)
- `RequireHarvestAlarmReady` (default true)
- `RequireHarvestInteractionContext` (default true)

Requirements:
- Uses role parameters `IsHarvestable` and `HarvestInteractionContext`.
- Uses the `Harvest_Ready` alarm on the NPC.

Effects:
- Sets state `$Harvest` on the NPC. The role should handle the alarm and drops inside `$Harvest`.

### Mount
Fields:
- `RequireTamed` (default true)
- `RequireOwner` (default true)
- `RequireMountable` (default true)
- `RequireCrouching` (default true)

Effects:
- Not yet implemented (logs a warning).

### ModeCycle
Fields:
- `RequireTamed` (default true)
- `RequireOwner` (default true)
- `Cycle` (array of `ModeStep` entries)

`ModeStep` fields:
- `State`
- `SubState`
- `Message` (currently logged only)

If `Cycle` is empty, the default cycle is `Hold -> Idle -> Defend`.

### Breed
Fields:
- `RequireTamed` (default true)
- `MinHappiness` (reserved)
- `FertilityBonus` (reserved)

Effects:
- Not yet implemented (logs a warning).

## Custom interactions
Custom entries allow full requirement and effect control:

```
{
  "Type": "Custom",
  "Requires": { "All": { "IsTamed": true } },
  "Effects": { "StartHarvest": true }
}
```

### Requirement structure
`Requires` contains two buckets:
- `All`: every requirement in the bucket must pass.
- `Any`: at least one requirement in the bucket must pass.

Array based requirements inside each bucket use any match semantics. For example, if `NpcState` contains two entries under `All`, the bucket is satisfied if either state matches. Use separate custom interactions if you need strict per entry AND logic.

### Basic toggle requirements
These are simple boolean toggles:
- `LovedItems`
- `IsHarvestable`
- `IsMountable`
- `IsTamed`
- `IsNotTamed`
- `PlayerCrouching`
- `PlayerIsOwner`
- `HarvestAlarmReady`
- `HarvestInteractionContext`

`HarvestInteractionContext` treats a blank context as valid (no tool required).

### ItemsInHand requirement
Fields:
- `Items` (item id or array)
- `Param` (role parameter name that returns string or string array)

The items list is the union of `Items` and the role parameter if provided.

### ItemsEquipped requirement
Fields:
- `Items` (item id or array)
- `Slots` (slot enum or array)

Slot enum values:
`Head`, `Chest`, `Hands`, `Legs`, `Armor`, `Equipped`, `Utility`, `Accessory`, `Accessories`

If `Items` is empty but `Slots` are provided, the requirement checks that any item is equipped in those slots.

### Parameter requirement
Fields:
- `Name` (role parameter name)
- `Operator` (`Equals`, `NotEquals`, `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual`)
- `Match` (`Any` or `All`)
- `Values` (string or array)

Numeric comparisons are used when both the role parameter and the target value parse as numbers. Otherwise string equality is used for `Equals` and `NotEquals`.

### AlarmState requirement
Fields:
- `Name` (alarm id)
- `State` (`Unset`, `Active`, `Passed`)

If an alarm does not exist, it is treated as `Unset`.

### NpcState requirement
Fields:
- `State`
- `SubState`

`State` may include `Primary.SubState`. If `State` is empty and `SubState` is provided, the requirement matches any state with that substate name.

### PlayerMovementState requirement
Field:
- `State`

Allowed values:
`Crouching`, `Walking`, `Running`, `Sprinting`, `Idle`, `Mounting`, `Sleeping`

### InteractionContext requirement
Fields:
- `Context`
- `Param`

If `Context` is blank, `Param` is resolved from the role. The context must exist and match a contextual interaction on the NPC for the current player.

## Effects
Custom effects support the following fields:
- `StartTaming`
- `StartBreeding` (not implemented)
- `ApplyFeeding` (currently no heal logic)
- `StartHarvest`
- `Mount` (not implemented)
- `ToggleMode`
- `ModeCycle` (used by ToggleMode)
- `ConsumeItem`
- `TriggerNpcHook`
- `PlaySound` (reserved, not implemented)
- `SpawnParticles` (reserved, not implemented)
- `DropItem` (reserved, not implemented)

### TriggerNpcHook
Fields:
- `HookId`
- `PlayerOnly`
- `Consume`

The hook effect stores `HookId`, `PlayerId`, `PlayerName`, `HeldItemId`, and `TimestampMs` on the NPC in a `TameworkHookComponent`. Use the `TameworkHook` sensor to react to that data.

## Action usage in NPC roles
Example interaction instruction snippet:

```json
"Actions": [
  {
    "Type": "LockOnInteractionTarget",
    "TargetSlot": { "Compute": "MasterTargetSlot" }
  },
  {
    "Type": "TameworkInteract",
    "ConfigId": { "Compute": "InteractionConfigId" },
    "LovedItems": { "Compute": "LovedItems" },
    "IsMountable": { "Compute": "IsMountable" },
    "IsHarvestable": { "Compute": "IsHarvestable" },
    "HarvestInteractionContext": { "Compute": "HarvestInteractionContext" }
  }
]
```

`Action_Tamework_Interact` uses role parameters by default, and the action fields above override those values when provided.

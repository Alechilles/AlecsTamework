# Optimized Interactions (TwInteractionConfig)

## Overview
Tamework replaces large NPC interaction instruction trees with a single action call. The flow is driven by `TwInteractionConfig` assets and executed by the `Action_Tamework_Interact` NPC action.

## Asset location
`<ModRoot>/Server/Tamework/Interactions/*.json`

## Config resolution
- If the action passes `ConfigId`, that asset id is used.
- Otherwise the role parameter named by `TwGlobalConfig.InteractionConfigParam` (default `InteractionConfigId`) is used if present.
- Otherwise the enabled config with the highest `Priority` whose `RoleIds` contains the NPC role id is selected.

`Priority` defaults to `0`; higher values win. If multiple configs share the same priority, selection order follows asset map iteration.

## Global defaults
Default parameter names and alarm names used by the interaction system live in `TwGlobalConfig`
(`Server/Tamework/Global/*.json`). If you change those names, update your role params and/or
action overrides to match.

## Interaction order
`Interactions` is evaluated in order. The first enabled entry whose requirements pass is executed.

## Cooldowns
Cooldowns are enforced in real-time seconds (not game time).
`CooldownSeconds` on an entry overrides `Cooldowns.InteractionSeconds` on the config.
Cooldowns are stored as NPC alarms named
`<InteractionCooldownAlarmPrefix>_<ConfigId>_<index>` where the prefix comes from `TwGlobalConfig`
(default `TameworkInteract_Cooldown`).
You can inspect them with `/tw getalarm`.
Contextual interactions (e.g., harvest + interaction context) will block fall-through if their cooldown
or harvest alarm is not ready.

## Preset interactions
Preset entries provide simple defaults plus optional `Requires` + `Effects` add‑ons.

### Tame
Fields:
- `UseLovedItems` (default true)
- `ItemsInHand` (item id or array)
- `ItemsParam` (role parameter name that returns string, string[], or JSON array string)

Requirements:
- NPC must be untamed.
- Held item must match the resolved item list.

Effects:
- Sets tamed true and owner to the interacting player.
- Consumes the held item.

### Feed
Fields:
- `UseLovedItems` (default true)
- `ItemsInHand` (string, object, or array)
- `Heal` (global fallback)
- `ItemsParam` (role parameter name)

`ItemsInHand` entries can be:
- `"ItemId"` (string)
- `{ "Item": "ItemId", "Heal": 8 }`
- or an array of either form

`ItemsParam` supports:
- role param string array of item ids
- or a JSON array string containing item ids and/or `{ "Item": "...", "Heal": 4 }` objects

Requirements:
- NPC must be tamed.
- Held item must match the resolved item list.

Effects:
- Heals the NPC by the per‑item override or the global `Heal`.
- Shows floating combat text for healing (if a player is present).
- Consumes the held item.

### Harvest
Fields:
- `RequireTamed` (default true)
- `RequireHarvestable` (default true)
- `RequireHarvestAlarmReady` (default true)
- `RequireHarvestInteractionContext` (default true)

Requirements:
- Uses role parameters named by `TwGlobalConfig.IsHarvestableParam` and
  `TwGlobalConfig.HarvestContextParam` (defaults `IsHarvestable`, `HarvestInteractionContext`).
- Uses the `TwGlobalConfig.HarvestAlarmName` alarm on the NPC (default `Harvest_Ready`).

Effects:
- Sets NPC state `$Harvest` (role should handle alarm/drops in that state).

### Mount
Fields:
- `RequireTamed` (default true)
- `RequireOwner` (default true)
- `RequireMountable` (default true)
- `RequireCrouching` (default true)

Effects:
- Attempts to mount the NPC using `NPCMountComponent`.
- Uses role params `MountAnchorX/Y/Z` and optional `MountMovementConfig`.

### ModeCycle
Fields:
- `RequireTamed` (default true)
- `RequireOwner` (default true)
- `ShowFloatingText` (default false)
- `ShowUiMessage` (default false)
- `Cycle` (array of `ModeStep` entries)

`ModeStep` fields:
- `State`
- `SubState`
- `Message` (used for `ShowFloatingText` / `ShowUiMessage`)

If `Cycle` is empty, default cycle is `Hold -> Idle -> Defend`.

### Breed
Fields:
- `RequireTamed` (default true)
- `MinHappiness` (reserved)
- `FertilityBonus` (reserved)

Effects:
- Not implemented yet (logs a warning).

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
`Requires` contains two buckets:
- `All`: all requirements must pass.
- `Any`: at least one requirement must pass.

Within each requirement array, any entry can satisfy that requirement type. Empty arrays are ignored.

### Basic toggles
- `LovedItems` (uses `TwGlobalConfig.LovedItemsParam`, default `LovedItems`)
- `IsHarvestable` (uses `TwGlobalConfig.IsHarvestableParam`, default `IsHarvestable`)
- `IsMountable` (uses `TwGlobalConfig.IsMountableParam`, default `IsMountable`)
- `IsTamed`
- `IsNotTamed`
- `PlayerCrouching`
- `PlayerIsOwner`
- `HarvestAlarmReady`
- `HarvestInteractionContext` (blank context is allowed; uses `TwGlobalConfig.HarvestContextParam`)

### ItemsInHand
Fields:
- `Items` (item id or array)
- `ItemsParam` (role parameter name)
- `Quantity` (minimum stack size)

### ItemsInInventory
Fields:
- `Items` (item id or array)
- `ItemsParam` (role parameter name)
- `Quantity` (minimum total quantity)

`ItemsParam` for item requirements accepts a role param that returns:
- a string array of item ids
- or a JSON array string of item ids and/or objects with `Item`/`item` fields

### ItemsEquipped
Fields:
- `Items` (optional item id or array)
- `ItemsParam` (role parameter name)
- `Slots` (slot enum array)

Slot values:
`Head`, `Chest`, `Hands`, `Legs`, `Armor`, `Equipped`, `Utility`, `Accessory`, `Accessories`

If `Items` is empty but `Slots` are provided, any item in those slots passes.

### Parameter
Fields:
- `Name` (role parameter name)
- `Operator` (`Equals`, `NotEquals`, `GreaterThan`, `GreaterThanOrEqual`, `LessThan`, `LessThanOrEqual`)
- `Match` (`Any` or `All`)
- `Value` (string or array)

Numeric comparison is used if both the param and value parse as numbers; otherwise string equality is used for `Equals` and `NotEquals`.

### AlarmState
Fields:
- `AlarmParam` (role parameter name for the alarm id)
- `Name` (alarm id)
- `State` (`Unset`, `Active`, `Passed`)

If an alarm does not exist, it is treated as `Unset`.

### NpcState
Fields:
- `State`
- `SubState`

`State` may include `Primary.SubState`. If `State` is blank and `SubState` is provided, any state with that substate name matches.

### PlayerMovementState
Field:
- `State` with values `Crouching`, `Walking`, `Running`, `Sprinting`, `Idle`, `Mounting`, `Sleeping`

### InteractionContext
Fields:
- `Context`
- `ContextParam` (role parameter name)

If `ContextParam` resolves to a value, it is used. Otherwise `Context` is used. The context must exist and match a contextual interaction on the NPC.

## Effects
Effects are defined under `Effects` in any interaction type.

Available effects:
- `SetTamed` `{ "Value": true | false }`
- `SetOwner` `{ "Source": "Player" | "None" | "Custom", "Uuid": "...", "Name": "..." }`
- `ModifyStats` `{ "Stats": [ { "StatId": "...", "Amount": 5 } ] }`
- `SetState` `{ "State": "...", "SubState": "..." }`
- `RemoveItemsHand` `{ "Quantity": 1 }`
- `RemoveItemsInventory` `{ "Items": [ { "Item": "...", "Quantity": 1 } ] }`
- `AddItemInventory` `{ "Items": [ { "Item": "...", "Quantity": 1 } ] }`
- `Mount` `true`
- `PlaySound` `{ "SoundEvent": "...", "Volume": 1, "Pitch": 1, "Offset": [0,0,0], "PlayerOnly": false }`
- `SpawnParticles` `{ "ParticleSystem": "...", "Offset": [0,0,0], "Color": "#RRGGBB", "PlayerOnly": false }`
- `DropItem` `{ "Item": "...", "DropList": "...", "QuantityMin": 1, "QuantityMax": 1, "ThrowSpeed": 0 }`
- `TriggerNpcHook` `{ "HookId": "...", "PlayerOnly": true, "Consume": true }`
- `ShowFloatingText` `{ "Message": "+10 HP" }`
- `ShowUiMessage` `{ "Message": "Mode: Defend" }`

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

`TameworkInteract` uses role parameters by default (names defined in `TwGlobalConfig`), and the
action fields above override those values when provided.

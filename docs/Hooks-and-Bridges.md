# Tamework Hooks and Instruction Bridge

## Overview
The hook system lets an interaction effect signal an NPC instruction chain without changing states or alarms. This is useful when you want a complex instruction sequence but still want to trigger it from the optimized interaction pipeline.

## How it works
- `TriggerNpcHook` effect writes a `TameworkHookComponent` on the NPC.
- `TameworkHook` sensor matches that component by `HookId` and exposes extra info to the instruction context.
- The sensor can consume the hook so it only fires once.

## TriggerNpcHook effect
Location: `TwInteractionConfig` custom effects.

Fields:
- `HookId` (required)
- `PlayerOnly` (if true, requires a player interaction)
- `Consume` (marks the hook to be consumed when matched)

When triggered, the component stores:
- `HookId`
- `PlayerId`
- `PlayerName`
- `HeldItemId`
- `TimestampMs`

## TameworkHook sensor
Sensor builder id: `TameworkHook`

Fields:
- `HookId` (required)
- `Consume` (optional, clears the hook when matched)

Important:
- NPC instructions use the `Sensor` field (singular). `Sensors` is not a valid instruction field and will default to an always-matching instruction, which can cause the hook actions to fire every tick.

Extra info params provided:
- `HookId`
- `HookPlayerId`
- `HookPlayerName`
- `HookHeldItemId`
- `HookTimestampMs`
- `HookHasTargetPosition`
- `HookTargetX`
- `HookTargetY`
- `HookTargetZ`

When target-position values are present, `TameworkHook` also exposes them through the sensor position provider, so movement instructions can seek directly to hook targets.

## Example
Custom interaction effect:
```json
{
  "Type": "Custom",
  "Requires": { "All": { "PlayerIsOwner": true } },
  "Effects": {
    "TriggerNpcHook": {
      "HookId": "ShowModeUi",
      "PlayerOnly": true,
      "Consume": true
    }
  }
}
```

NPC instructions using the hook:
```json
{
  "Sensor": {
    "Type": "TameworkHook",
    "HookId": "ShowModeUi",
    "Consume": true
  },
  "Actions": [
    { "Type": "PlaySound", "SoundEventId": "SFX_Torch_Swing_Right_Local" }
  ]
}
```

## Notes
- Hooks are stored on the NPC component, so they persist until consumed or replaced.
- Use unique `HookId` values to avoid collisions when multiple systems emit hooks.
- Command-item move/home commands use this bridge pattern with hook ids:
  - `Tamework.Command.MoveToPosition.RaycastHit`
  - `Tamework.Command.MoveToPosition.StoredHome`

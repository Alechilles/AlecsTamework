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

## Claim provider bridge

Claim-aware population limits use an optional, provider-neutral bridge. The verified contracts are:

- QuestLines Claims exactly `1.3.1` (`net.evilcraft:QuestLinesClaims`).
- SimpleClaims `>=1.0.38 <1.1.0` (`Buuz135:SimpleClaims`).

These gates accept build metadata (for example, `1.3.1+vendor.2`) but reject prerelease
versions until that prerelease's reflected contract has been verified.

The provider is resolved once per top-level operation, not retained permanently by tame, spawn, or breeding services. A `/tw settings` change or claim-plugin lifecycle change therefore affects the next operation while an already-prepared operation keeps its original settings revision and provider generation.

Before a prepared mutation is applied, Tamework performs a targeted provider/topology and
occupancy refresh. The short apply lock validates the refreshed snapshot revision and recomputes
headroom while excluding only the operation's own pending slots, so movement or another admission
that consumes capacity after preparation cannot be overwritten by a stale decision.

Provider selection is strict:

- An explicit provider is never substituted.
- `Auto` tries QuestLines Claims first and tries SimpleClaims only when QuestLines Claims is absent or disabled.
- If QuestLines Claims is installed but not ready, incompatible, or errors during probing, `Auto` does not fall through to SimpleClaims.
- Active population-rule errors fail closed. SimpleClaims damage lookup/invocation errors fail open.

Claim population and SimpleClaims damage are independent capabilities. The legacy `SimpleClaimsEnabled` value is the master claim-integration switch, but population also requires a non-`Off` provider plus a relevant population rule, while damage also requires `ProtectTamedFromNonMembers`. QuestLines Claims supplies population policy only; damage protection remains SimpleClaims-specific.

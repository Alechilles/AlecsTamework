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

## SimpleClaims bridge

SimpleClaims is the supported optional claims integration. Tamework calls it
directly for released breeding-per-claim limits and its native
tamed-companion damage decision.

- Breeding may require a claim and may apply per-chunk or total claim limits.
- Damage protection follows SimpleClaims' native owner, member, ally, party,
  administrator, full-world, permission, and outsider rules.
- A SimpleClaims lookup failure does not make a companion invulnerable.

The ordinary Tamework owner cap is separate. It counts canonical owned
profiles in its configured global/per-world scope and uses durable positive
reservations; it does not use claims or provider selection. There is no
provider-neutral claims bridge. Tamework resolves the live SimpleClaims plugin
before each policy use and shares one reflected capability set for that plugin
generation. A stop, replacement, or Tamework shutdown invalidates the cached
capabilities so an old plugin class loader is not reused.

## Bonded capture completion

Integrations that use `StoreBondedCompanion` receive a
`BondedCompanionCaptureResolvedEvent` after the stored bonded profile and its
exact source-cleanup intent commit. The event contains one
`BondedCompanionCaptureEvidenceView` with stable operation, attempt, owner,
roster, family, source NPC, profile, role, item/config, policy, disposition,
outcome, reason, world, and commit-time evidence.

The event proves bonded profile durability. It does not claim that physical
source cleanup or capture-item finalization has finished; those steps occur
after the commit and remain independently recoverable.

Event subscriptions are live notifications. Restart-sensitive integrations
must also call
`BondedCompanionApi.findCapture(ownerUuid, rosterId, sourceNpcUuid)`. That
lookup reads dedicated capture-source authority retained for the bonded
profile's lifetime; pruning bounded operation history cannot release the
source NPC identity or erase replay evidence. `NOT_FOUND` means no matching
profile-lifetime capture proof; `UNAVAILABLE` or `INTERNAL_FAILURE` must be
treated as unknown, not as proof that capture did not occur. Deleting the
bonded profile also removes its capture proof through the same transactionally
enforced lifecycle.

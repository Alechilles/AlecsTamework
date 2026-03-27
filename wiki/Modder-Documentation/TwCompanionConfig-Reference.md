---
title: "TwCompanionConfig Reference"
order: 15
published: true
draft: false
---
# TwCompanionConfig Reference

Parent: [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

## What It Controls
`TwCompanionConfig` is the role-scoped companion policy family. Use it when a specific set of NPC roles should share ownership rules, command behavior, revive policy, and cross-world travel handling.

Use this family for:
- ownership protection behavior by role
- command recall, return-home, and revive policy by role
- cross-world follow and transfer-failure policy

Do not put global relocation infrastructure here. Retry windows and linked-panel unlink confirmation stay in [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference).

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Companion/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role
- Fallback: when no matching role config exists, the runtime falls back to global companion-compatible values from `TwGlobalConfig`

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- Alias handling matters for `DeadRespawnCooldownMs` and `DeadRespawnCooldownMins`; either key counts as an explicit override.
- `TwCompanionConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "General": { "Enabled": true, "Priority": 0 },
  "RoleIds": [],
  "OwnershipProtection": { "...": "..." },
  "Command": {
    "...": "...",
    "Travel": { "...": "..." }
  }
}
```

## Section Reference
### `General`
- `Enabled`: disables the asset entirely when `false`.
- `Priority`: used for role-match selection.

### `RoleIds`
- List of NPC role ids this config applies to.
- Explicit array values replace the parent array.

### `OwnershipProtection`
- `BlockOwnerDamage`: blocks owner damage to owned NPCs.
- `BlockAllPlayerDamageIfOwned`: blocks any player damage once the NPC is owned.
- `InvulnerableIfOwned`: makes owned NPCs invulnerable.

### `Command`
These fields control effective companion behavior once the command system targets a matching role.

- `ReturnHomeTeleportDistance`: distance at which return-home can teleport instead of pathing the entire way.
- `ReturnHomePathDistanceBeforeTeleport`: path-distance threshold before teleport fallback is considered.
- `ReturnHomeTeleportDelayMs`: delay before return-home teleport executes.
- `RecallSafeSpawnDistance`: preferred spawn distance when recalling an unloaded companion.
- `RecallForceRelocateDistance`: distance beyond which recall can force relocation.
- `DeadRespawnEnabled`: enables revive/respawn for this role.
- `DeadRespawnCooldownMs`: cooldown in milliseconds before revive is available again.
- `DeadRespawnCooldownMins`: human-friendly alias for the same cooldown. If both are set, the minutes key wins.
- `DeadRespawnFollowRetryDelayMs`: follow retry delay after respawn.
- `DeadRespawnDistanceClose`
- `DeadRespawnDistanceNear`
- `DeadRespawnDistanceMid`
- `DeadRespawnDistanceFar`
These define the ordered placement rings the revive runtime can try.
- `PlacementMinRelativeY`: minimum allowed vertical placement offset.
- `PlacementMaxRelativeY`: maximum allowed vertical placement offset.

### `Command.Travel`
- `CrossWorldRecallEnabled`: allows recall to bridge world changes.
- `OnTransferFailure`: what to do when the target transfer cannot complete.
- `FollowMasterOnWorldChange`: automatically migrate the companion when the owner changes worlds.
- `FollowMasterOnWorldChangeStateFilter`: only auto-follow across worlds when the companion is in one of these states.

Accepted `OnTransferFailure` values:
- `QueueForRecall`
- `MarkLost`
- `Ignore`

## Global vs Role Boundary
Use `TwCompanionConfig` for behavior policy:
- who can damage the NPC
- whether revive is enabled
- how far recall or return-home can go
- whether cross-world travel is allowed

Use `TwGlobalConfig.Command` for shared runtime infrastructure:
- relocation retry intervals
- max relocation wait
- max retry attempts
- linked-panel unlink confirmation

## Defaults, Aliases, and Compatibility Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Companion/TwCompanionConfig_Default.json` is the shipped baseline.
- `DeadRespawnCooldownMins` is the preferred human-friendly authoring key and overrides `DeadRespawnCooldownMs` when both are present.
- If no role-scoped match exists, effective settings fall back to global compatibility values from `TwGlobalConfig`.
- `FollowMasterOnWorldChangeStateFilter` is an explicit array. If you author it in a child asset, it replaces the parent list.

## Minimal Example
```json
{
  "General": {
    "Enabled": true,
    "Priority": 50
  },
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "OwnershipProtection": {
    "BlockOwnerDamage": true
  }
}
```

## Common Pattern Example
```json
{
  "General": {
    "Enabled": true,
    "Priority": 100
  },
  "RoleIds": [
    "My_Tamed_Wolf",
    "My_Tamed_Wolf_Baby"
  ],
  "OwnershipProtection": {
    "BlockOwnerDamage": true,
    "BlockAllPlayerDamageIfOwned": false,
    "InvulnerableIfOwned": false
  },
  "Command": {
    "ReturnHomeTeleportDistance": 96.0,
    "RecallSafeSpawnDistance": 20.0,
    "RecallForceRelocateDistance": 80.0,
    "DeadRespawnEnabled": true,
    "DeadRespawnCooldownMins": 10,
    "PlacementMinRelativeY": -2.0,
    "PlacementMaxRelativeY": 4.0,
    "Travel": {
      "CrossWorldRecallEnabled": true,
      "OnTransferFailure": "QueueForRecall",
      "FollowMasterOnWorldChange": true,
      "FollowMasterOnWorldChangeStateFilter": [
        "Follow",
        "Defend",
        "Aggressive"
      ]
    }
  }
}
```

## Gotchas
- This family only applies after the command or revive runtime resolves a role match. It does not make an NPC commandable by itself.
- `Travel` is nested under `Command`, not a separate top-level section.
- If a child asset explicitly authors `Travel`, only missing nested keys inherit. Authored arrays like `FollowMasterOnWorldChangeStateFilter` replace the parent list.
- Global relocation retry settings still come from `TwGlobalConfig`.

## Related Pages
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [Command System and Linked Panel Guide](/mod/alecs-tamework/command-system-and-linked-panel-guide)

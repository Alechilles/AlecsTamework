---
title: "TwCompanionConfig Reference"
order: 15
published: true
draft: false
---
# TwCompanionConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwCompanionConfig` is the role-scoped companion policy family. Use it when a
specific set of NPC roles should share command distances, paid revival,
timed-summon/storage, placement behavior, and cross-world travel handling.

Use this family for:
- command recall, return-home, exact revival cost/cooldown, and revive
  placement policy by role
- timed summon duration, dismissal/expiry cooldown, warning thresholds, and
  logout storage policy
- cross-world follow and transfer-failure policy

Ownership damage protection and the server-wide revive master gate are
controlled by `/tw settings`. `Command.Revive.Enabled` is the matching role's
paid-revival policy. Legacy flat revive fields still load for older packs.

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
  "Command": {
    "...": "...",
    "Revive": { "...": "..." },
    "Summon": { "...": "..." },
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

### `Command`
These fields control effective companion behavior once the command system targets a matching role.

- `ReturnHomeTeleportDistance`: distance at which return-home can teleport instead of pathing the entire way.
- `ReturnHomePathDistanceBeforeTeleport`: path-distance threshold before teleport fallback is considered.
- `ReturnHomeTeleportDelayMs`: delay before return-home teleport executes.
- `RecallSafeSpawnDistance`: preferred spawn distance when recalling an unloaded companion.
- `RecallForceRelocateDistance`: distance beyond which recall can force relocation.
- `DeadRespawnCooldownMs`: legacy cooldown alias.
- `DeadRespawnCooldownMins`: legacy minute-based cooldown alias.
- `DeadRespawnFollowRetryDelayMs`: follow retry delay after respawn.
- `DeadRespawnDistanceClose`
- `DeadRespawnDistanceNear`
- `DeadRespawnDistanceMid`
- `DeadRespawnDistanceFar`
These define the ordered placement rings the revive runtime can try.
- `PlacementMinRelativeY`: minimum allowed vertical placement offset.
- `PlacementMaxRelativeY`: maximum allowed vertical placement offset.

### `Command.Revive`

- `Enabled`: enables paid command-panel revival for matching roster-backed
  companions.
- `GameplayCooldownMs`: non-negative delay after death; `0` adds no delay.
- `Costs`: ordered AND recipe. Every unique `ItemId`/positive `Quantity`
  component is required. An explicit array replaces the inherited recipe.
- `InsufficientCostMessage`: optional localization key used when any component
  is missing.

The panel displays the exact quote before confirmation. Admission and the
complete recipe are rechecked before charge. A proven terminal failure creates
one exact refund claim; an unavailable or denied operation charges nothing.

### `Command.Summon`

- `Enabled`: enables owner/command-family roster summon and dismiss/storage.
- `ActiveDurationMs`: non-negative active duration; `0` is unlimited.
- `ResummonCooldownMs`: non-negative cooldown after dismissal or expiry.
- `AutoStoreOnOwnerLogout`: stores an active companion when the owner logs
  out.
- `ExpiryWarningThresholdsMs`: strictly descending, unique, positive
  remaining-time thresholds below a positive active duration. An explicit
  array replaces the inherited list.

### `Command.Travel`
- `CrossWorldRecallEnabled`: allows recall to bridge world changes.
- `OnTransferFailure`: what to do when the target transfer cannot complete.
- `FollowMasterOnWorldChange`: automatically migrate the companion when the owner changes worlds. The shipped default is `false`; explicit cross-world Recall is unaffected.
- `FollowMasterOnWorldChangeStateFilter`: only auto-follow across worlds when the companion is in one of these states.

Accepted `OnTransferFailure` values:
- `QueueForRecall`
- `MarkLost`
- `Ignore`

`MarkLost` is a retained config name, not permission to author the canonical
`LOST` lifecycle. In the replacement runtime it abandons the failed relocation
retry and logs the drop. Only positive destructive-removal evidence can create
`LOST`.

## Global vs Role Boundary
Use `TwCompanionConfig` for behavior policy:
- how far recall or return-home can go
- whether cross-world travel is allowed
- paid revival cost/cooldown and placement tuning
- timed summon/storage balance and logout behavior

Use `/tw settings` for:
- who can damage owned NPCs
- whether revive is enabled

Use `TwGlobalConfig.Command` for shared runtime infrastructure:
- relocation retry intervals
- max relocation wait
- max retry attempts
- linked-panel unlink confirmation

When an enabled role-scoped config matches, `Command.Revive` and
`Command.Summon` are authoritative. Legacy dead-respawn cooldown fields remain
input aliases; `Revive.GameplayCooldownMs` wins when the nested section is
present.

## Legacy Settings-Owned Fields Accepted
Older packs may still contain ownership protection and revive enablement keys in `TwCompanionConfig`. Tamework continues to decode those keys for compatibility, but new configs should not author them, `/tw config` hides them, and `/tw settings` wins at runtime.

## Defaults, Aliases, and Compatibility Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Companion/TwCompanionConfig_Default.json` is the shipped baseline.
- New configs should use `Revive.GameplayCooldownMs`; the older
  `DeadRespawnCooldownMins` and `DeadRespawnCooldownMs` fields remain readable.
- Settings-owned legacy fields remain readable for old packs, but `/tw settings` wins at runtime and `/tw config` hides those fields.
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
  ]
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
  "Command": {
    "ReturnHomeTeleportDistance": 96.0,
    "RecallSafeSpawnDistance": 20.0,
    "RecallForceRelocateDistance": 80.0,
    "Revive": {
      "Enabled": true,
      "GameplayCooldownMs": 600000,
      "Costs": [
        { "ItemId": "Ingredient_Dragon_Heart", "Quantity": 1 },
        { "ItemId": "Ingredient_Essence", "Quantity": 8 }
      ],
      "InsufficientCostMessage": "server.my_mod.revive.missing_cost"
    },
    "Summon": {
      "Enabled": true,
      "ActiveDurationMs": 600000,
      "ResummonCooldownMs": 120000,
      "AutoStoreOnOwnerLogout": true,
      "ExpiryWarningThresholdsMs": [60000, 30000, 10000]
    },
    "PlacementMinRelativeY": -2.0,
    "PlacementMaxRelativeY": 4.0,
    "Travel": {
      "CrossWorldRecallEnabled": true,
      "OnTransferFailure": "QueueForRecall",
      "FollowMasterOnWorldChange": false,
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
- A global cooldown value does not override a matching role-scoped cooldown.
- `Costs` is an AND recipe, not weighted alternatives. Duplicate item IDs,
  zero/negative quantities, and invalid warning thresholds reject the config.
- Relocation timeout or retry exhaustion never creates canonical `LOST`
  state, regardless of `OnTransferFailure`.

## Related Pages
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [Command System and Linked Panel Guide](/mod/alecs-tamework/command-system-and-linked-panel-guide)




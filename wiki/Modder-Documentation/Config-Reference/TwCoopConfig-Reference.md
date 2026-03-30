---
title: "TwCoopConfig Reference"
order: 24
published: true
draft: false
---
# TwCoopConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

## What It Controls
`TwCoopConfig` defines Tamework-managed coop behavior for a specific `CoopId`. It controls who can be captured into the coop, what residents can live there, how produce is generated, and how identity is preserved during release.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Coops/*.json`
- Scope: coop-scoped
- Resolution key: `CoopId`
- Resolution: highest enabled `Priority` for the requested coop id

Compatibility note:
- Current assets live under `Items/Coops`. Older references to `Farming/Coops` are stale.

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- `TwCoopConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "CapturePolicy": { "...": "..." },
  "LifecycleRules": { "...": "..." },
  "ProduceRules": { "...": "..." },
  "IdentityRules": { "...": "..." }
}
```

## Section Reference
### `Enabled`, `Priority`, `CoopId`
- `Enabled`
- `Priority`
- `CoopId`: stable coop identifier used by runtime lookup

### `CapturePolicy`
- `RequireTamed`
- `OwnerRestricted`
- `RequireOwner`
- `ParticleSystem`
- `SoundEvent`

### `LifecycleRules`
- `MaxResidents`: max resident count the coop can hold.
- `ResidentRoamStartHour`: game-hour start for resident roaming.
- `ResidentRoamEndHour`: game-hour end for resident roaming.
- `ResidentSpawnOffset`: placement offset used when residents are spawned out of the coop.
- `CaptureWildNPCsInRange`: allows nearby wild residents to be captured automatically.
- `WildCaptureRadius`: range for wild capture.
- `AcceptedRoleIds`: role ids allowed to live in this coop.

`ResidentSpawnOffset` fields:
- `X`
- `Y`
- `Z`

### `ProduceRules`
- `DropsByRole`: map of resident role id to item drop list id.
- `IntervalGameHours`: how often production ticks in game hours.
- `ItemsPerTick`: how many items are produced per tick.

### `IdentityRules`
- `RequireSnapshotOnRelease`: requires a stored resident snapshot before release is allowed.
- `PreserveUUID`: restores the same UUID instead of treating release as a new entity identity.

## Defaults and Cross-System Notes
- The shipped example asset is `src/main/resources/Server/Tamework/Items/Coops/TwCoopConfig_Example_Coop_Chicken.json`.
- `AcceptedRoleIds` and `DropsByRole` are explicit array/map values and replace parent content when authored in a child asset.
- Coop runtime preserves more than produce state. It also uses resident snapshots and identity rules to decide how safe a release path is.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "LifecycleRules": {
    "MaxResidents": 6,
    "AcceptedRoleIds": [
      "tamed_chicken"
    ]
  },
  "ProduceRules": {
    "DropsByRole": {
      "tamed_chicken": "Drop_Chicken_Produce"
    },
    "IntervalGameHours": 1,
    "ItemsPerTick": 1
  }
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "CoopId": "Coop_Chicken",
  "CapturePolicy": {
    "RequireTamed": false,
    "OwnerRestricted": false,
    "RequireOwner": false,
    "ParticleSystem": "Entities/Basic/Particles/Love/Hearts",
    "SoundEvent": "SFX_Pet_Interact_Success"
  },
  "LifecycleRules": {
    "MaxResidents": 6,
    "ResidentRoamStartHour": 6,
    "ResidentRoamEndHour": 18,
    "ResidentSpawnOffset": {
      "X": 0.0,
      "Y": 0.0,
      "Z": 3.0
    },
    "CaptureWildNPCsInRange": true,
    "WildCaptureRadius": 10.0,
    "AcceptedRoleIds": [
      "chicken",
      "tamed_chicken"
    ]
  },
  "ProduceRules": {
    "DropsByRole": {
      "Chicken": "Drop_Chicken_Produce",
      "Tamed_Chicken": "Drop_Chicken_Produce"
    },
    "IntervalGameHours": 1,
    "ItemsPerTick": 1
  },
  "IdentityRules": {
    "RequireSnapshotOnRelease": true,
    "PreserveUUID": false
  }
}
```

## Gotchas
- `CoopId` is the lookup key. Keep it stable once content ships.
- `DropsByRole` keys must match the role ids your coop will actually host.
- Coop configs are not part of `/tw reloadconfig`.

## Related Pages
- [Coop and Feed Trough Guide](/mod/alecs-tamework/coop-and-feed-trough-guide)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



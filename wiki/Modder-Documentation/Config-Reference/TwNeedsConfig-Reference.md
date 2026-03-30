---
title: "TwNeedsConfig Reference"
order: 21
published: true
draft: false
---
# TwNeedsConfig Reference

Parent: [Config Reference Index](/mod/alecs-tamework/config-reference-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

## What It Controls
`TwNeedsConfig` controls hunger and thirst progression. It defines decay, passive refill, manual refill, happiness penalties, tick timing, owner-offline behavior, and optional starvation or dehydration damage.

Use it when you want companions to:
- get hungry or thirsty over time
- eat from nearby containers or drink near water
- gain hunger or thirst from player interactions
- lose happiness when neglected
- take damage from extreme need depletion

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Needs/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- `TwNeedsConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [],
  "Values": { "...": "..." },
  "Decay": { "...": "..." },
  "HappinessImpact": { "...": "..." },
  "PassiveRefill": { "...": "..." },
  "ManualRefill": { "...": "..." },
  "Timing": { "...": "..." },
  "TickPolicy": { "...": "..." },
  "Damage": { "...": "..." }
}
```

## Section Reference
### `Enabled`, `Priority`, `RoleIds`
- `Enabled`: disables the config when `false`.
- `Priority`: used during role-match resolution.
- `RoleIds`: roles this asset applies to.

### `Values`
- `HungerDefault`
- `HungerMin`
- `HungerMax`
- `ThirstDefault`
- `ThirstMin`
- `ThirstMax`

These define initial value and clamp range for both needs.

### `Decay`
- `HungerPerMinute`: passive hunger drain.
- `ThirstPerMinute`: passive thirst drain.

### `HappinessImpact`
- `HungerPenaltyAtMin`: max happiness penalty when hunger reaches minimum.
- `ThirstPenaltyAtMin`: max happiness penalty when thirst reaches minimum.
- `PenaltyCurvePower`: curve shape used between full and empty need values.

### `PassiveRefill`
Container-driven hunger refill:
- `SweepIntervalSeconds`
- `NearbyContainerFeedEnabled`
- `ContainerSearchRadius`
- `ContainerVerticalScanRadius`
- `ContainerConsumeRadius`
- `ContainerFoodItemIds`
- `HungerGainPerConsumedItem`
- `MaxContainerItemsConsumedPerSweep`

Water-driven thirst refill:
- `NearbyWaterDrinkEnabled`
- `WaterSearchRadius`
- `WaterVerticalScanRadius`
- `WaterConsumeRadius`
- `ThirstGainPerSweepNearWater`

### `ManualRefill`
- `HungerGainOnFeedInteraction`: hunger restored by compatible feed interactions.
- `ThirstGainOnWaterBucket`: thirst restored by compatible bucket use.
- `WaterBucketItemIds`: items allowed to satisfy the manual water refill path.

### `Timing`
- `Basis`: duration basis for needs timers.

Accepted values:
- `REAL_TIME`
- `WORLD_TIME_SCALED`

### `TickPolicy`
- `Mode`: how needs progress when the owner is offline.
- `OwnerOfflineGraceHours`: grace period before offline policy changes apply.
- `OwnerOfflineDecayMultiplier`: decay multiplier after the grace window.

Accepted `Mode` values:
- `OWNER_ONLINE_GRACE_THEN_DECAY`

### `Damage`
- `Enabled`: master gate for need-driven damage.
- `Model`: damage calculation model.
- `DualNeedRule`: how hunger and thirst combine when both are critical.
- `StarvationDamagePerMinute`
- `DehydrationDamagePerMinute`
- `Lethal`: whether the damage is allowed to kill the NPC

Accepted `Model` values:
- `MIN_ONLY_FLAT`

Accepted `DualNeedRule` values:
- `USE_HIGHER_ONLY`

## Defaults and Cross-System Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Needs/TwNeedsConfig_Default.json` is the shipped baseline.
- Feed interactions and water-bucket interactions can refill needs through `ManualRefill`.
- Nearby container feeding is how feed-trough style food consumption integrates with the needs system.
- Need levels also feed the modifier bands in [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference).
- `Timing.Basis` and `TickPolicy` work together. A world-time basis still respects the owner-offline policy that decides when decay should advance.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Values": {
    "HungerDefault": 100.0,
    "HungerMin": 0.0,
    "HungerMax": 100.0,
    "ThirstDefault": 100.0,
    "ThirstMin": 0.0,
    "ThirstMax": 100.0
  },
  "Decay": {
    "HungerPerMinute": 1.2,
    "ThirstPerMinute": 1.8
  }
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [
    "My_Tamed_Wolf",
    "My_Tamed_Wolf_Baby"
  ],
  "Values": {
    "HungerDefault": 100.0,
    "HungerMin": 0.0,
    "HungerMax": 100.0,
    "ThirstDefault": 100.0,
    "ThirstMin": 0.0,
    "ThirstMax": 100.0
  },
  "Decay": {
    "HungerPerMinute": 1.2,
    "ThirstPerMinute": 1.8
  },
  "PassiveRefill": {
    "SweepIntervalSeconds": 15,
    "NearbyContainerFeedEnabled": true,
    "ContainerSearchRadius": 7.0,
    "ContainerFoodItemIds": [
      "Plant_Fruit_Apple",
      "Plant_Fruit_Azure"
    ],
    "HungerGainPerConsumedItem": 25.0,
    "NearbyWaterDrinkEnabled": true,
    "WaterSearchRadius": 4.0,
    "ThirstGainPerSweepNearWater": 20.0
  },
  "ManualRefill": {
    "HungerGainOnFeedInteraction": 20.0,
    "ThirstGainOnWaterBucket": 30.0,
    "WaterBucketItemIds": [
      "Container_Bucket"
    ]
  },
  "Timing": {
    "Basis": "REAL_TIME"
  },
  "TickPolicy": {
    "Mode": "OWNER_ONLINE_GRACE_THEN_DECAY",
    "OwnerOfflineGraceHours": 72,
    "OwnerOfflineDecayMultiplier": 1.0
  },
  "Damage": {
    "Enabled": false,
    "Model": "MIN_ONLY_FLAT",
    "DualNeedRule": "USE_HIGHER_ONLY",
    "StarvationDamagePerMinute": 2.0,
    "DehydrationDamagePerMinute": 3.0,
    "Lethal": true
  }
}
```

## Gotchas
- `ContainerFoodItemIds`, `WaterBucketItemIds`, and other arrays replace parent values when authored in a child asset.
- Needs refill and happiness adjustment are separate systems. Feeding can restore hunger and also add happiness, but the values come from different config families.
- If you enable damage, confirm your refill paths are reachable for that species or you can create unavoidable death loops.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [Coop and Feed Trough Guide](/mod/alecs-tamework/coop-and-feed-trough-guide)


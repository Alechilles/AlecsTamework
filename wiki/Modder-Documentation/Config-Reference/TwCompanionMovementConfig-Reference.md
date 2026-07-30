---
title: "TwCompanionMovementConfig Reference"
order: 20
published: true
draft: false
---
# TwCompanionMovementConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwCompanionMovementConfig` lets a pack scale a tamed companion's travel speed. The same resolved multiplier applies while the companion walks and while it is natively ridden. `MountMovementConfig` still establishes the native mount's base controls; this config only scales the resolved speed.

Assets live under:

```text
Server/Tamework/CompanionMovement/*.json
```

## Resolution and inheritance

Configs are role-scoped through `RoleIds`. Only enabled configs participate. For each normalized role id, Tamework selects one config in this order:

1. Higher `Priority`
2. Case-insensitive lowest config id when priorities tie

There is no field-by-field merge between matching configs. If no config matches, movement stays neutral at `1.00`.

Parent fallback is supported. A child keeps every top-level field it explicitly authors and inherits omitted fields from its parent. `RoleIds` and `AttachmentModifiers` are arrays: when a child explicitly supplies either array, it replaces the parent's whole array, including when the child supplies `[]`.

## Fields

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `Enabled` | boolean | `true` | Disabled configs are ignored. |
| `Priority` | integer | `0` | Higher values win for the same role. |
| `RoleIds` | string array | `[]` | NPC role ids this config covers; matching ignores case and surrounding whitespace. |
| `BaseMoveSpeedMultiplier` | number | `1.00` | Starting multiplier before attachment and progression factors. |
| `MinMoveSpeedMultiplier` | number | `0.50` | Lower final bound. |
| `MaxMoveSpeedMultiplier` | number | `2.00` | Upper final bound. |
| `AttachmentModifiers` | array | `[]` | Optional attachment factors evaluated after the base multiplier. |

Each `AttachmentModifiers` entry has `Slot`, `Values`, and `Multiplier`. It matches when the companion's effective attachment selection has the same slot and one of the listed values; matching ignores case and surrounding whitespace. Multiple matching entries all contribute.

## Calculation and limits

Tamework calculates the result in this order:

```text
BaseMoveSpeedMultiplier
  x every matching AttachmentModifiers.Multiplier
  x progression MoveSpeedMultiplier (traits, level growth, and talents)
  = raw multiplier
  -> clamp to MinMoveSpeedMultiplier..MaxMoveSpeedMultiplier
```

The supported effective range is `0.50` through `2.00`. Authored minimum and maximum bounds are themselves normalized into that range; if they are reversed, Tamework swaps them before clamping. Non-finite or non-positive factors are treated as neutral `1.00`.

Unmounted companions quantize the clamped multiplier to the nearest `0.05` (5%) step so the runtime can use static entity effects. Native mounts instead apply the exact clamped multiplier to the rider's runtime movement settings, so smaller level, trait, talent, and attachment changes are visible immediately without additional entity-effect assets.

## Saddle example

This example gives the `Horse` role a 10% base increase and another 10% when its `Saddle` selection is `Yes`. Enable it only after confirming that those role and attachment ids exist in the target pack.

```json
{
  "RoleIds": ["Horse"],
  "BaseMoveSpeedMultiplier": 1.10,
  "AttachmentModifiers": [
    { "Slot": "Saddle", "Values": ["Yes"], "Multiplier": 1.10 }
  ]
}
```

With a progression multiplier of `1.05`, that saddle example resolves `1.10 x 1.10 x 1.05 = 1.2705`. An unmounted companion receives the nearest static-effect step, `1.25`; a native rider receives the exact `1.2705` multiplier.

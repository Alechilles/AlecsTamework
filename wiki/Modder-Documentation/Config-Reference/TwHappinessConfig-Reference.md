---
title: "TwHappinessConfig Reference"
order: 20
published: true
draft: false
---
# TwHappinessConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

## What It Controls
`TwHappinessConfig` defines the shared wellbeing score used by Tamework progression systems. It controls baseline happiness, convergence, event-based gains and losses, and contextual modifiers from hunger, thirst, nearby population, and owner proximity.

This config is especially important when you use:
- feed and pet interactions
- breeding readiness based on happiness
- linked-panel wellbeing displays
- needs-driven mood pressure

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Happiness/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- `TwHappinessConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [],
  "Values": { "...": "..." },
  "Equilibrium": { "...": "..." },
  "Impulses": { "...": "..." },
  "Modifiers": { "...": "..." }
}
```

## Section Reference
### `Enabled`, `Priority`, `RoleIds`
- `Enabled`: disables the asset when `false`.
- `Priority`: used during role-match resolution.
- `RoleIds`: roles this config applies to. Explicit array values replace the parent list.

### `Values`
- `CurrentDefault`: initial happiness value for newly initialized progression state.
- `Min`: minimum allowed happiness.
- `Max`: maximum allowed happiness.

### `Equilibrium`
- `BaseSetpoint`: target value the system naturally drifts toward over time.
- `ConvergencePerMinute`: how strongly the current happiness moves toward the setpoint each minute.

### `Impulses`
- `GainOnFeed`: additive happiness gain from feeding interactions.
- `GainOnPet`: additive happiness gain from petting or similar positive interactions.
- `LoseOnDamage`: additive happiness loss from taking damage.

### `Modifiers`
These modifiers shift the equilibrium result up or down.

Nested `Hunger`:
- `Enabled`
- `Bands`: ordered list of percent bands

Each `Hunger.Bands` entry supports:
- `Id`: stable internal identifier
- `Label`: user-facing label
- `MinPercent`
- `MaxPercent`
- `Offset`: happiness adjustment applied while the need value is inside that band

Nested `Thirst` uses the same shape as `Hunger`.

Nested `Population`:
- `Enabled`
- `Radius`: nearby scan radius
- `Bands`: count-based offset rules

Each `Population.Bands` entry supports:
- `Id`
- `Label`
- `MinCount`
- `MaxCount`
- `Offset`

Additional field:
- `OwnerNearbyOffset`: flat bonus applied when the owner is nearby

## Defaults and Cross-System Notes
- The bundled default asset in `src/main/resources/Server/Tamework/Happiness/TwHappinessConfig_Default.json` is the shipped baseline.
- Feed interactions use `Impulses.GainOnFeed` and can also be multiplied by traits such as `HappinessGainMultiplier`.
- Needs do not directly live in this asset. Hunger and thirst values come from [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference), then feed into the modifier bands here.
- Breeding-ready checks often combine `TwHappinessConfig` with `TwBreedingConfig.Happiness.Threshold`.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Values": {
    "CurrentDefault": 60.0,
    "Min": 0.0,
    "Max": 100.0
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
    "CurrentDefault": 50.0,
    "Min": 0.0,
    "Max": 100.0
  },
  "Equilibrium": {
    "BaseSetpoint": 50.0,
    "ConvergencePerMinute": 8.0
  },
  "Impulses": {
    "GainOnFeed": 5.0,
    "GainOnPet": 3.0,
    "LoseOnDamage": 10.0
  },
  "Modifiers": {
    "Hunger": {
      "Enabled": true,
      "Bands": [
        {
          "Id": "well_fed",
          "Label": "Well-fed",
          "MinPercent": 80.0,
          "MaxPercent": 100.0,
          "Offset": 10.0
        },
        {
          "Id": "hungry",
          "Label": "Hungry",
          "MinPercent": 10.0,
          "MaxPercent": 40.0,
          "Offset": -15.0
        }
      ]
    },
    "Population": {
      "Enabled": true,
      "Radius": 14.0,
      "Bands": [
        {
          "Id": "social",
          "Label": "Social",
          "MinCount": 1,
          "MaxCount": 8,
          "Offset": 8.0
        }
      ]
    },
    "OwnerNearbyOffset": 5.0
  }
}
```

## Gotchas
- Modifier bands are authored arrays. A child config that authors `Bands` replaces the parent list.
- Hunger and thirst percent bands assume the values exposed by `TwNeedsConfig`.
- Keep band ranges intentional and non-overlapping. Tamework applies the authored band logic, not automatic normalization.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)



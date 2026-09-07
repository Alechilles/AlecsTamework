---
title: "TwHappinessConfig Reference"
order: 20
published: true
draft: false
---
# TwHappinessConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

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
- `ConvergencePerMinute`: how many happiness points the underlying mood moves toward its environmental target per real minute. Active timed effects are added separately and do not decay through convergence.

### `Impulses`
- `GainOnFeed`: additive happiness gain from feeding interactions.
- `HandFeedDurationMinutes`: real-minute duration of the separate hand-feeding effect.
- `FeedImpulseDurationMinutes`: real-minute duration of food-consumption, petting, and damage effects.
- `GainOnPet`: additive happiness gain from petting or similar positive interactions.
- `LoseOnDamage`: additive happiness loss from taking damage.
- `FeedItemImpulses`: per-item consumed-feed impulse map (`ItemId -> delta`).
- `FeedParamImpulses`: per-family consumed-feed impulse map (`ParamKey -> delta`).

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

### Timed effects (unreleased correction)

Feeding, petting, and damage apply flat, signed effects for their configured
duration. Displayed happiness is the underlying mood plus the active effects,
limited by `Values.Min` and `Values.Max`. The underlying mood continues to move
toward its environmental target, so poor conditions can still reduce happiness
while a positive effect is active. Breeding uses the resulting displayed value.

Refreshing the same effect key resets its expiration without stacking another
copy. Different effect keys can coexist; there is no single global care-bonus
cap. Food profiles may use item-specific keys. Consumed-food effects can also
come from autonomous feeding, while the hand-feeding effect is separate.
Disposition scales positive effects and softens negative effects using the
existing multiplier rules. An effect retains its applied amount until refreshed
or expired. Expiration removes that contribution, without a second deduction
from the underlying mood. Happiness limits do not discard the underlying mood.

For example, an underlying mood of 96 with an active +12 effect displays 100.
If conditions stay unchanged, expiration returns it to 96, not 88. Negative
effects behave the same way at the lower limit.

The existing happiness component, capture items, and captured snapshots now also
retain `BaseValue` (the underlying mood). Older saved state without that value keeps its
current happiness and clears its legacy effect timers on first reconciliation;
the old, partly decayed contributions cannot be reconstructed reliably. Timers
use wall-clock deadlines, so saving or capturing does not pause or renew them.
An explicit happiness set rebases the mood and clears existing timed effects on
the next reconciliation.

- The bundled default asset in `src/main/resources/Server/Tamework/Happiness/TwHappinessConfig_Default.json` is the shipped baseline.
- Feed interactions use `Impulses.GainOnFeed` for hand-feed gain and can also be multiplied by traits such as `HappinessGainMultiplier`.
- Consumed item and feed-family impulses can be authored separately with `FeedItemImpulses` and `FeedParamImpulses`.
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
    "HandFeedDurationMinutes": 15.0,
    "FeedImpulseDurationMinutes": 15.0,
    "GainOnPet": 3.0,
    "LoseOnDamage": 10.0,
    "FeedItemImpulses": {
      "Tw_Feed_Herbivore": 4.0,
      "Tw_Feed_Carnivore": 4.0
    },
    "FeedParamImpulses": {
      "Herbivore": 3.0,
      "Carnivore": 3.0
    }
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
- `GainOnFeed` and consumed-feed impulses are separate channels. Do not assume one setting controls both.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)




---
title: "TwTraitConfig Reference"
order: 23
published: true
draft: false
---
# TwTraitConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwTraitConfig` defines trait pools, how many traits an NPC can roll, how inheritance and mutation behave, and the numerical ranges each trait can contribute.

Use it when you want:
- persistent stat variation between companions
- inheritance and mutation across breeding generations
- linked-panel trait icons and labels
- mod-specific trait pools per role

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Traits/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- `TwTraitConfig` is not reloaded by `/tw config reload`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [],
  "Selection": { "...": "..." },
  "Inheritance": { "...": "..." },
  "Traits": [
    { "...": "..." }
  ]
}
```

## Section Reference
### `Enabled`, `Priority`, `RoleIds`
- `Enabled`
- `Priority`
- `RoleIds`

### `Selection`
- `MaxTraitsPerNpc`: max number of traits one NPC can carry.
- `RollCountWeights`: weighted distribution for how many traits are rolled.
- `AllowDuplicateTraits`: allows the same trait definition to be selected more than once.
- `UseSeededRandom`: keeps rolls deterministic when the runtime provides seeded randomness.

`RollCountWeights` fields:
- `Count0`
- `Count1`
- `Count2`
- `Count3`
- `Count4`

### `Inheritance`
- `AllowInheritance`: master gate for parent-to-offspring trait inheritance.
- `InheritanceChance`: chance that a trait inherits instead of rolling fresh.
- `MutationChance`: chance that an inherited trait mutates away from the inherited value.
- `PairAlignmentRangeInfluence`: how strongly parent compatibility influences the offspring range.
- `PreferParentTraits`: biases toward the parent trait pool during inheritance.

### `Traits`
Each entry in `Traits` defines one trait type.

Fields:
- `Id`: stable internal trait id.
- `DisplayName`: user-facing name.
- `Description`: optional language key for the next line of the trait tooltip.
  Omitted or blank descriptions use the built-in description for known effect keys.
  Custom effects without a description keep the existing tooltip header.
- `EffectKey`: runtime effect this trait modifies.
- `IconPath`: icon asset used by the linked panel and related UI.
- `Weight`: chance to appear in natural rolls.
- `InheritanceWeight`: weight when inheritance is selecting among parent traits.
- `MutationPreference` (development): `HIGHER`, `LOWER`, or `NONE` (default).
  Defines which mutation value is better for optional husbandry reroll protection.
  Missing or unrecognized values disable protection for that trait. This does
  not change mutation frequency or existing saved trait values.
- `NaturalMin`
- `NaturalMax`
- `BreedingMin`
- `BreedingMax`
- `Default`: default value if no random roll is used.
- `Flags`: optional string tags for your own categorization or downstream logic.
- `ConflictsWith`: list of trait ids that should not appear together.

Authoring guidance:
- `NaturalMin` and `NaturalMax` define the normal wild or non-breeding range.
- `BreedingMin` and `BreedingMax` define the larger inherited or bred range.
- `IconPath` should point at a real asset if you want icon rendering instead of glyph fallback.

### Description placeholders

Put the template in your language files and reference its key from `Description`.
For example, `"Description": "mymod.traits.strength.description"` can refer to:

```text
mymod.traits.strength.description = {direction} attack damage by {percent}%.
```

A value of `1.2` displays “Increases attack damage by 20%.”; `0.8` displays
“Decreases attack damage by 20%.” The template is resolved in the viewer's language.

| Placeholder | Meaning |
| --- | --- |
| `{direction}` | Localized “Increases”, “Decreases”, or “Changes” for a neutral effect. |
| `{percent}` | Absolute effect change in percent, without the `%` symbol. |
| `{signedPercent}` | The same percentage with a positive or negative sign. |
| `{value}` | The trait's stored numeric value. |
| `{points}` | Absolute happiness offset in FLAT disposition mode; zero otherwise. |

Numbers use the viewer's locale and up to two decimal places. Percentage changes
are relative to the neutral multiplier `1`, not the configured `Default` or the
breeding-range bar. `DamageTakenMultiplier` uses the inverse: `1.2` means 16.67%
less damage received. Harvest uses its clamped 0–100% bonus chance. FLAT
disposition selects direction from its resolved happiness offset and uses points.
Descriptions explain this trait's contribution; other modifiers can affect the final result.

`Description` follows the existing `Traits` array inheritance: omitting `Traits`
inherits it, while an explicit array replaces the parent's entries. Existing
assets need no edits to receive built-in descriptions. The config editor discovers
the field from the codec, and normal asset load/remove events refresh it.

## Defaults and Cross-System Notes
- The optional `Alec's Tamework! Examples` pack includes
  `Server/Tamework/Traits/TwTraitsDefault.json` for its sample roles.
- Example effect keys include `HappinessGainMultiplier`,
  `FertilityMultiplier`, `MaxHealthMultiplier`, `SizeMultiplier`,
  `MoveSpeedMultiplier`, `DamageTakenMultiplier`, `DamageDealtMultiplier`,
  and `HarvestDoubleDropChanceMultiplier`.
- `ConflictsWith` is how you prevent incompatible traits from coexisting in the same roll set.
- Traits only affect offspring inheritance when [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference) also enables `InheritTraits`.

## Minimal Example
```json
{
  "Enabled": true,
  "Priority": 50,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Selection": {
    "MaxTraitsPerNpc": 2,
    "RollCountWeights": {
      "Count0": 0.2,
      "Count1": 0.5,
      "Count2": 0.3,
      "Count3": 0.0,
      "Count4": 0.0
    }
  },
  "Traits": [
    {
      "Id": "Trait_Swiftness",
      "DisplayName": "Swiftness",
      "EffectKey": "MoveSpeedMultiplier",
      "Weight": 1.0,
      "InheritanceWeight": 1.0,
      "NaturalMin": 0.9,
      "NaturalMax": 1.1,
      "BreedingMin": 0.8,
      "BreedingMax": 1.3,
      "Default": 1.0
    }
  ]
}
```

## Common Pattern Example
```json
{
  "Enabled": true,
  "Priority": 100,
  "RoleIds": [
    "My_Tamed_Wolf"
  ],
  "Selection": {
    "MaxTraitsPerNpc": 4,
    "RollCountWeights": {
      "Count0": 0.15,
      "Count1": 0.25,
      "Count2": 0.4,
      "Count3": 0.15,
      "Count4": 0.05
    },
    "AllowDuplicateTraits": false,
    "UseSeededRandom": true
  },
  "Inheritance": {
    "AllowInheritance": true,
    "InheritanceChance": 0.6,
    "MutationChance": 0.1,
    "PairAlignmentRangeInfluence": 0.6,
    "PreferParentTraits": true
  },
  "Traits": [
    {
      "Id": "Trait_Swiftness",
      "DisplayName": "Swiftness",
      "EffectKey": "MoveSpeedMultiplier",
      "IconPath": "Tamework/LinkedPanelIcons/Trait_Swiftness.png",
      "Weight": 0.75,
      "InheritanceWeight": 0.75,
      "NaturalMin": 0.85,
      "NaturalMax": 1.25,
      "BreedingMin": 0.6,
      "BreedingMax": 2.0,
      "Default": 1.0,
      "Flags": [
        "movement"
      ],
      "ConflictsWith": [
        "Trait_Sluggish"
      ]
    }
  ]
}
```

## Gotchas
- `Traits` is an explicit array. Child assets replace the entire parent list when they author it.
- `MutationPreference` travels with each trait entry when the array is inherited.
  A provider must also supply a positive `harmfulMutationRerollChance`; preference
  alone does not change rolls. Protection rerolls once and keeps the better of
  the two mutation values, which can still be worse than the inherited value.
- `EffectKey` is runtime-coupled. Built-in keys work automatically, custom keys only do something when a mod registers a matching Trait Effects API handler, and unregistered keys are inert.
- Broken or missing `IconPath` values do not stop the trait from working, but the UI will fall back instead of showing the intended icon.

## Related Pages
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [Trait Effects API Reference](/mod/alecs-tamework/trait-effects-api-reference)
- [TwBreedingConfig Reference](/mod/alecs-tamework/twbreedingconfig-reference)
- [TwHappinessConfig Reference](/mod/alecs-tamework/twhappinessconfig-reference)




---
title: "TwNamesConfig Reference"
order: 26
published: true
draft: false
---
# TwNamesConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwNamesConfig` defines random name pools used by naming-item UI randomization.

Use it when you want:
- curated random name pools by region/species/theme
- parent fallback for shared base pools with pack-specific overrides
- reusable random-name IDs referenced by `TwNameItemConfig.Naming.RandomNamesId`

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Names/*.json`
- Scope: id-scoped pool dictionary
- Resolution: pool id is read from naming config and resolved in merged active names map

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level keys inherit from parent pools.
- Explicit arrays replace parent pool arrays for that same key.
- `TwNamesConfig` is not reloaded by `/tw reloadconfig`; it refreshes through normal asset load/remove flow.

## Top-Level Structure
```json
{
  "NorthAmericaMale": ["Liam", "Noah"],
  "NorthAmericaFemale": ["Olivia", "Emma"],
  "GermanMale": ["Emil", "Matteo"]
}
```

Each top-level key is a pool id. Each value is a string array of candidate names.

## Usage in Naming Items
`TwNameItemConfig` field:
- `Naming.RandomNamesId`

Behavior:
- If the id resolves to a non-empty pool, naming UI randomize is enabled.
- If it is missing or empty, manual naming still works.
- When an NPC has gender from an enabled `TwBreedingConfig.Gender` section, randomization prefers matching `...Male` or `...Female` pool sections. Ungendered NPCs keep the existing merged-pool behavior.

## Minimal Example
```json
{
  "WolfCompanion": [
    "Ash",
    "Koda",
    "Rune"
  ]
}
```

## Common Pattern Example
```json
{
  "NorthAmericaMale": [
    "Liam",
    "Noah",
    "Oliver"
  ],
  "NorthAmericaFemale": [
    "Olivia",
    "Emma",
    "Charlotte"
  ],
  "BarnyardFriendly": [
    "Poppy",
    "Clover",
    "Maple",
    "Sunny"
  ]
}
```

## Gotchas
- Pool IDs are string keys; keep naming consistent because other configs reference them by id.
- Empty arrays effectively disable random suggestions for that pool.
- Child-authored pool arrays replace parent arrays for the same key.

## Related Pages
- [TwNameItemConfig Reference](/mod/alecs-tamework/twnameitemconfig-reference)
- [Naming System Guide](/mod/alecs-tamework/naming-system-guide)
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)

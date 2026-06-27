---
title: "TwFoodConfig Reference"
order: 19
published: true
draft: false
---
# TwFoodConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwFoodConfig` defines role food profiles in one place. It controls favorite/taming foods, other accepted foods, needs-driven container consumption priority, consumed-feed happiness deltas, and the command target HUD food strip.

Use it when an animal has role-specific foods and you want feeding, needs, and HUD display to agree.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Food/*.json`
- Scope: role-scoped
- Resolution: highest enabled `Priority` whose `RoleIds` contains the NPC role, or whose `RoleOverrides` contains that role
- JSON files do not need a `Type` field. The asset folder defines the type as `TwFoodConfig`.

## Inheritance and Overrides
- Parent fallback is supported.
- Omitted top-level sections inherit from the parent.
- Explicit `Foods` and `Happiness` object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value instead of merging.
- `RoleOverrides` is a replacement map when explicitly authored.
- Inside a role override, each authored food category replaces that family category. Missing categories keep the family value.

For example, if a family has `Compatible: ["Tw_Feed_Herbivore"]` and a role override has `Compatible: ["Plant_Crop_Wheat_Item"]`, that role's compatible foods are only wheat.

## Top-Level Structure
```json
{
  "Enabled": true,
  "Priority": 0,
  "RoleIds": [
    "Example_Tamed_Sheep"
  ],
  "Foods": {
    "Preferred": [
      "Plant_Crop_Wheat_Item"
    ],
    "Premium": [
      "Tw_Feed_Premium_Herbivore"
    ],
    "Compatible": [
      "Tw_Feed_Herbivore"
    ],
    "Disliked": [
      "Tw_Feed_Generic"
    ]
  },
  "Happiness": {
    "Preferred": 6,
    "Premium": 10,
    "Compatible": 2,
    "Disliked": -10
  },
  "RoleOverrides": {}
}
```

## Food Categories
- `Preferred`: the animal's favorite/taming food. Untamed HUDs show only this category.
- `Premium`: high-value food. Needs-driven container consumption prefers this before preferred foods.
- `Compatible`: accepted normal food.
- `Disliked`: food the animal can eat, but which applies a negative happiness effect.

Needs consume foods in this order:
1. `Premium`
2. `Preferred`
3. `Compatible`
4. `Disliked`

Tamed command target HUDs show all configured categories in the display order `Preferred`, `Premium`, `Compatible`, `Disliked`, with the happiness value shown near each item icon.

## Happiness
`Happiness` defines the consumed-feed happiness delta for each category.

Recommended baseline:
```json
{
  "Preferred": 6,
  "Compatible": 2,
  "Premium": 10,
  "Disliked": -10
}
```

Zero is allowed when a food should be accepted without changing happiness.

## Role Overrides
Use `RoleOverrides` when one family asset covers several related roles but a specific role has a different preferred food.

```json
{
  "RoleIds": [
    "Example_Tamed_Deer_Doe",
    "Example_Tamed_Deer_Stag"
  ],
  "Foods": {
    "Preferred": [
      "Plant_Crop_Lettuce_Item"
    ],
    "Compatible": [
      "Tw_Feed_Herbivore"
    ]
  },
  "RoleOverrides": {
    "Example_Tamed_Deer_Stag": {
      "Foods": {
        "Preferred": [
          "Plant_Crop_Wheat_Item"
        ]
      }
    }
  }
}
```

In this example, the stag uses wheat as preferred food and still keeps the family compatible herbivore feed. If the override also authored `Compatible`, that category would replace the family compatible list.

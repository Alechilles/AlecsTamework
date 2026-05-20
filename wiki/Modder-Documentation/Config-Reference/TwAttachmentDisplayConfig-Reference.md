---
title: "TwAttachmentDisplayConfig Reference"
order: 18
published: true
draft: false
---
# TwAttachmentDisplayConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Alec's Tamework Wiki](/mod/alecs-tamework/alecs-tamework-wiki)

`TwAttachmentDisplayConfig` gives raw model attachment selections player-friendly labels. Captured spawner tooltips use these labels automatically when DynamicTooltipsLib is installed, and other UI surfaces can reuse the same config.

## Location

`Server/Tamework/AttachmentDisplays/*.json`

## Example

```json
{
  "Type": "TwAttachmentDisplayConfig",
  "Enabled": true,
  "Priority": 0,
  "Entries": [
    {
      "Id": "base-game-cattle",
      "AppliesTo": {
        "RoleIds": ["Cow", "Yak"],
        "ModelIds": ["Cow"],
        "RoleNamespaces": ["Hytale"],
        "ModelNamespaces": ["Hytale"]
      },
      "Sets": {
        "BaseColor": {
          "Label": "Coat",
          "Values": {
            "Black": "Black Coat",
            "White": "White Coat"
          }
        }
      }
    }
  ]
}
```

## Fields

- `Enabled`: optional, defaults to `true`.
- `Priority`: optional, defaults to `0`. Higher priority wins after match specificity.
- `Entries`: array of display entries. One file can cover many NPCs.

Entry fields:

- `Id`: optional stable ID for deterministic tie-breaking.
- `AppliesTo`: optional filters. If omitted or empty, the entry is a global fallback.
- `Sets`: map of raw attachment set IDs to labels and raw value labels.

`AppliesTo` supports:

- `RoleIds`
- `ModelIds`
- `RoleNamespaces`
- `ModelNamespaces`

## Resolution

Tamework resolves each attachment line using exact model matches first, then exact role matches, model namespace matches, role namespace matches, and global fallback entries. If a value is not mapped, the raw attachment ID is shown.

This config only affects display text. It does not change captured attachment data, spawn behavior, breeding inheritance, or attachment migrations.

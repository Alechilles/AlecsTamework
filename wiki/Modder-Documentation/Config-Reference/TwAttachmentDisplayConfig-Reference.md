---
title: "TwAttachmentDisplayConfig Reference"
order: 18
published: true
draft: false
---
# TwAttachmentDisplayConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Alec's Tamework Wiki](/mod/alecs-tamework/alecs-tamework-wiki)

`TwAttachmentDisplayConfig` gives raw model attachment selections player-friendly labels. Captured spawner item descriptions use these labels automatically through base Hytale item display metadata, and other UI surfaces can reuse the same config.

## Location

`Server/Tamework/AttachmentDisplays/*.json`

## Example

```json
{
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
          "Label": "server.myMod.attachments.coat.label",
          "Values": {
            "Black": "server.myMod.attachments.coat.black",
            "White": "server.myMod.attachments.coat.white"
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
- `Sets`: map of raw attachment set IDs to display labels and value labels.

`Label` and each `Values` value accept language keys. Define them in every
supported `Server/Languages/<locale>/server.lang` catalog, without the `server.`
prefix in the catalog itself. For example:

```properties
myMod.attachments.coat.label=Coat
myMod.attachments.coat.black=Black Coat
myMod.attachments.coat.white=White Coat
```

The target HUD resolves these labels in the viewer's language. Captured-item
tooltips retain translation keys so the same item can display in each viewer's
language. Existing literal labels remain supported. Set IDs and value IDs remain
unchanged and must not be translated.

`AppliesTo` supports:

- `RoleIds`
- `ModelIds`
- `RoleNamespaces`
- `ModelNamespaces`

## Resolution

Tamework resolves each attachment line using exact model matches first, then exact role matches, model namespace matches, role namespace matches, and global fallback entries. If a value is not mapped, the raw attachment ID is shown.

This config only affects display text. It does not change captured attachment data, spawn behavior, breeding inheritance, or attachment migrations.

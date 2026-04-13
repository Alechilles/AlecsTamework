---
title: "TwNameItemConfig Reference"
order: 18
published: true
draft: false
---
# TwNameItemConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwNameItemConfig` binds naming rules to a specific item. It decides which NPC roles can be named, what ownership and taming checks are required, what names are allowed, and what feedback happens on success.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- Scope: item-scoped
- Resolution key: `ItemId`
- Runtime reload: `/tw reloadconfig` reloads naming configs into the item feature registry

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays replace the parent value.
- `TwNameItemConfig` is one of the item families refreshed by `/tw reloadconfig`.

## Top-Level Structure
```json
{
  "ItemId": "Item_Naming_Tag",
  "AllowedRoles": { "...": "..." },
  "Naming": { "...": "..." }
}
```

## Section Reference
### `ItemId`
- Item id that resolves this config.

Compatibility note:
- The current runtime resolves naming configs by `ItemId`. Older docs that refer to `ItemIds` are stale.

### `AllowedRoles`
Accepted `Mode` values:
- `AllowAll`
- `Allowlist`
- `Denylist`

Fields:
- `Mode`
- `Allowlist`
- `Denylist`

### `Naming`
- `RequireTamed`: requires the target NPC to be tamed.
- `RequireOwner`: requires the using player to be the owner.
- `AllowUnownedWhenRequireOwner`: when `RequireOwner` is true, still allows naming if the NPC has no owner.
- `AllowRename`: allows replacing an existing Tamework name.
- `MinLength`
- `MaxLength`
- `AllowedChars`
- `TrimWhitespace`
- `ReplaceExisting`: allows replacing an existing non-Tamework display name.
- `RandomNamesId`: optional `TwNamesConfig` asset id used by the naming UI randomize button.
- `ConsumeItem`: consumes one item on success.
- `CooldownMs`
- `SoundEvent`
- `ParticleSystem`

Accepted `AllowedChars` presets:
- `Any`
- `LettersNumbersSpaces`
- `LettersNumbers`
- `LettersSpaces`
- `Letters`
- `Numbers`
- `Ascii`

Custom validation is also supported through:
- `Regex:<pattern>`

## Runtime Notes
- The item interaction is `TameworkNameNpc`.
- When the interaction succeeds, Tamework tries to open the naming UI.
- If `RandomNamesId` resolves to a valid `TwNamesConfig` pool, the UI randomize button is enabled.
- If the page cannot be opened, the system falls back to chat input.
- The server re-validates ownership and taming on submission; client-side UI is not the only gate.

## Minimal Example
```json
{
  "ItemId": "Item_Naming_Tag",
  "Naming": {
    "RequireTamed": true,
    "RequireOwner": true,
    "MinLength": 1,
    "MaxLength": 24,
    "AllowedChars": "LettersNumbersSpaces"
  }
}
```

## Common Pattern Example
```json
{
  "ItemId": "Tamework_Nametag_Example",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "Mob_Tamework_Example",
      "Mob_Tamework_Example_Baby",
      "Cat_Pet"
    ]
  },
  "Naming": {
    "RequireTamed": true,
    "RequireOwner": true,
    "AllowUnownedWhenRequireOwner": true,
    "AllowRename": true,
    "MinLength": 1,
    "MaxLength": 24,
    "AllowedChars": "LettersNumbersSpaces",
    "TrimWhitespace": true,
    "ReplaceExisting": true,
    "RandomNamesId": "NorthAmericaMale",
    "ConsumeItem": true,
    "CooldownMs": 5000,
    "SoundEvent": "SFX_Tamework_NameTag",
    "ParticleSystem": "Hearts"
  }
}
```

## Gotchas
- `AllowedChars` is server-side policy, not only UI validation.
- `AllowRename` and `ReplaceExisting` solve different problems: one is about existing Tamework names, the other is about replacing non-Tamework display names.
- `/tw reloadconfig` is required after editing naming configs during development.

## Related Pages
- [Naming System Guide](/mod/alecs-tamework/naming-system-guide)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)




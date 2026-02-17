# Naming Items (TwNameItemConfig)

## Overview
Naming items use `TwNameItemConfig` assets to control who can name an NPC and what names are allowed.
Items trigger naming via the `TameworkNameNpc` item interaction, which opens a chat-based prompt.

## Asset location
`<ModRoot>/Server/Tamework/Items/Naming/*.json`

## Interaction usage
Add `TameworkNameNpc` to an item’s `Interactions` block:

```json
{
  "Id": "Item_Naming_Tag",
  "Interactions": {
    "Primary": [
      { "Type": "TameworkNameNpc" }
    ]
  }
}
```

If a naming config is found for the item (by `ItemId`), it is used. If none is found, defaults apply.

## AllowedRoles
Controls which NPC roles can be named.

Fields:
- `Mode`: `AllowAll`, `Allowlist`, or `Denylist`
- `Allowlist`: list of role ids
- `Denylist`: list of role ids

## Naming settings
Fields:
- `RequireTamed` (default true). Requires a tamed NPC (Tamework tamed component or a role id that starts with `Tamed`).
- `RequireOwner` (default true). Requires the player to be the owner.
- `AllowUnownedWhenRequireOwner` (default false). When `RequireOwner` is true, allows naming NPCs with no owner.
- `AllowRename` (default true). Allows renaming if a Tamework name already exists.
- `ReplaceExisting` (default true). Allows overriding non‑Tamework display names.
- `MinLength` (default 1). Minimum name length.
- `MaxLength` (default 24). Maximum name length.
- `AllowedChars` (default `LettersNumbersSpaces`). Preset or regex string.
- `TrimWhitespace` (default true). Trims leading/trailing whitespace.
- `ConsumeItem` (default false). Consumes one item on successful naming.
- `CooldownMs` (optional). Cooldown applied to the held naming item after success.
- `SoundEvent` / `ParticleSystem` (optional). Feedback on success.

Allowed character presets:
- `Any`
- `LettersNumbersSpaces`
- `LettersNumbers`
- `LettersSpaces`
- `Letters`
- `Numbers`
- `Ascii`

Custom regex is supported via `AllowedChars: "Regex:<pattern>"`.

## Chat flow
When the interaction succeeds, the player is prompted to type a name in chat.
- Type the desired name in chat to apply it.
- Type `cancel` to cancel the request.

The server re‑validates ownership/tamed state on submission.

## Persistence
Names are stored in `TameworkNpcNameComponent` and re‑applied when the NPC is loaded.
Spawner capture preserves the Tamework name and restores it on spawn.

## Example config
```json
{
  "ItemId": "Item_Naming_Tag",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [ "Cat_Pet", "Dog_Pet" ]
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
    "ConsumeItem": false,
    "CooldownMs": 15000,
    "SoundEvent": "SFX_Tamework_NameTag",
    "ParticleSystem": "Hearts"
  }
}
```

## Example assets
Example assets included in the mod:
- `Server/Tamework/Items/Naming/NameItem_Tamework_Example.json`
- `Server/Item/Items/Naming/Tamework_Nametag_Example.json`

Translations used by the example item:
- `items.Tamework_Nametag_Example.name`
- `items.Tamework_Nametag_Example.description`

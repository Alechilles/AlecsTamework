# Naming Items (TwNameItemConfig)

## Overview
Naming items use `TwNameItemConfig` assets to control who can name an NPC and what names are allowed.
Items trigger naming via the `TameworkNameNpc` item interaction, which opens a text input UI.

## Runtime Architecture (Contributor View)
Naming runtime is split into:
- Orchestrator: `NamingFeatureHandler`
- NPC info + ownership/tamed checks: `NamingNpcInfoService`
- Effect application (component writes, cooldowns, consume, feedback): `NamingEffectService`

When changing naming flow, keep policy checks and effect execution in their existing service boundaries.

## Asset location
`<ModRoot>/Server/Tamework/Items/Naming/*.json`

The framework's default name pool remains in the main jar. The sample naming
config and item are in the optional `Alec's Tamework! Examples` asset pack.
Install and explicitly enable that pack before using the sample paths below.

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
- `RandomNamesId` (optional). References a `TwNames` asset id for in-UI random name suggestions.
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

## Name input flow
When the interaction succeeds, the player gets a naming UI with a text field and `Apply` / `Cancel` buttons.
- If `RandomNamesId` resolves to a valid `TwNames` pool, a `Random` button is shown.
- Clicking `Random` replaces the text in the input field only (it does not apply the name).
- Enter the desired name and click `Apply` to submit.
- Click `Cancel` to cancel the request.
- If the page cannot be opened, naming automatically falls back to chat input (`cancel` still cancels).

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
    "RandomNamesId": "TwNamesDefault",
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
Example assets in the optional `Alec's Tamework! Examples` asset pack:
- `examples/asset-pack/Server/Tamework/Items/Naming/TwNameExample.json`
- `src/main/resources/Server/Tamework/Names/TwNamesDefault.json`
- `examples/asset-pack/Server/Item/Items/Naming/Tamework_Nametag_Example.json`

Translations used by the example item:
- `items.Tamework_Nametag_Example.name`
- `items.Tamework_Nametag_Example.description`

---
title: "TwSpawnerConfig Reference"
order: 17
published: true
draft: false
---
# TwSpawnerConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls
`TwSpawnerConfig` binds a capture-and-spawn behavior set to a specific spawner item. It controls which roles can be captured, local owner restrictions, item cooldowns and effects, and filled-item icon and tooltip behavior.

## Asset Location and Resolution
- Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- Scope: item-scoped
- Resolution key: `EmptyItemId`
- Runtime reload: `/tw reloadconfig` reloads spawner assets into the item feature registry

## Inheritance and Reload
- Parent fallback is supported.
- Omitted top-level object sections inherit from the parent.
- Explicit object sections inherit missing nested keys from the parent.
- Explicit arrays and maps replace the parent value.
- `AllowedRoles.Allowlist`, `AllowedRoles.Denylist`, `IconOverrides`, and `IconOverridesByRole` all replace the parent value when explicitly authored.

## Top-Level Structure
```json
{
  "EmptyItemId": "Spawner_My_Creature",
  "FilledItemId": "*Spawner_My_Creature_State_Filled",
  "IconDefault": "Icons/Spawner_Default.png",
  "AllowedRoles": { "...": "..." },
  "Capture": { "...": "..." },
  "Spawn": { "...": "..." },
  "IconOverrides": [],
  "IconOverridesByRole": {},
  "TooltipMode": "Additive"
}
```

## Section Reference
### `EmptyItemId`
- Required.
- Item id for the empty spawner item that this asset binds to.

### `FilledItemId`
- Optional.
- Filled variant item id used after a successful capture.

### `IconDefault`
- Optional default icon path for filled-item rendering and tooltip presentation.

### `AllowedRoles`
Controls which NPC roles can be captured or spawned with this item.

Accepted `Mode` values:
- `AllowAll`
- `Allowlist`
- `Denylist`

Fields:
- `Mode`
- `Allowlist`
- `Denylist`

### `Capture`
- `ClearsOwner`: legacy compatibility field. New configs should use `/tw settings` for capture owner clearing.
- `RequireTamed`: requires the NPC to be tamed before capture succeeds.
- `OwnerRestricted`: restricts capture to the owner when ownership exists.
- `RequireOwner`: explicit owner-presence requirement for this item flow.
- `ParticleSystem`
- `SoundEvent`
- `CooldownMs`
- `MaxDistance`

### `Spawn`
- `AssignsOwner`: legacy compatibility field. New configs should use `/tw settings` for spawn owner assignment.
- `OwnerRestricted`: restricts spawn use to the spawner owner when ownership exists on the item.
- `RequireOwner`: explicit owner-presence requirement for this item flow.
- `ParticleSystem`
- `SoundEvent`
- `CooldownMs`
- `MaxDistance`

### `IconOverrides`
Array of conditional icon overrides. Each entry supports:
- `Icon`
- `Attachments`

`Attachments` is a map of attachment-set name to expected attachment option. Tamework uses it to match a captured NPC’s metadata to the correct icon.

### `IconOverridesByRole`
Map of role id to `IconOverrides` arrays. Use it when icon rules differ per role instead of only by attachment combination.

### `TooltipMode`
Controls DynamicTooltipsLib composition when that optional integration is present.

Accepted values:
- `Additive`: append Tamework lines such as `Name` and `Role`
- `Replace`: replace the base description text with Tamework tooltip output

## Defaults and Cross-System Notes
- The shipped example asset is `src/main/resources/Server/Tamework/Items/Spawners/Spawner_Tamework_Example.json`.
- Captured Tamework names and progression metadata are preserved on the item and restored on spawn.
- Capture owner clearing and spawn owner assignment are settings-owned runtime policy.
- Spawner capture and spawn also integrate with linked-companion sync and other runtime systems. This config only defines the author-facing policy.

## Minimal Example
```json
{
  "EmptyItemId": "Spawner_My_Creature",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "My_Tamed_Wolf"
    ]
  },
  "Capture": {
    "RequireTamed": true
  },
  "Spawn": {
    "OwnerRestricted": true
  }
}
```

## Common Pattern Example
```json
{
  "EmptyItemId": "Spawner_Tamework_Example",
  "FilledItemId": "*Spawner_Tamework_Example_State_Filled",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [
      "Mob_Tamework_Example",
      "Mob_Tamework_Example_Baby"
    ]
  },
  "Capture": {
    "RequireTamed": true,
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "Spawn": {
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "TooltipMode": "Additive"
}
```

## Gotchas
- `EmptyItemId` is the resolution key. If two active configs target the same item, selection becomes a config-resolution problem instead of an item-authoring problem.
- `RequireOwner` is an explicit override, not the same thing as `OwnerRestricted`.
- Use `/tw settings` for the global capture/spawn owner-transfer defaults.
- `IconOverrides` and `IconOverridesByRole` are explicit array/map values and replace the parent content when authored in a child asset.
- `/tw reloadconfig` is required after editing spawner configs during development.

## Related Pages
- [Spawner System Guide](/mod/alecs-tamework/spawner-system-guide)
- [TwNameItemConfig Reference](/mod/alecs-tamework/twnameitemconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)




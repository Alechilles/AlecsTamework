# Spawner Config (TwSpawnerConfig)

## Overview
Spawner items use `TwSpawnerConfig` assets to control capture and spawn behavior. These assets are converted into per item feature configs at runtime and drive `SpawnerFeatureHandler` and `TameworkSpawn` interactions.

## Asset location
`<ModRoot>/Server/Tamework/Items/Spawners/*.json`

## Core fields
- `EmptyItemId` (required). The empty spawner item id to bind this config to.
- `FilledItemId` (optional). The filled variant item id, if used.
- `IconDefault` (optional). Default icon override used for filled items.

## AllowedRoles
Controls which NPC roles can be captured or spawned.

Fields:
- `Mode`: `AllowAll`, `Allowlist`, or `Denylist`
- `Allowlist`: list of role ids
- `Denylist`: list of role ids

## Capture settings
Fields:
- `ClearsOwner` (default true). Clears owner on capture.
- `RequireTamed` (default true). Only allow capture if NPC is tamed (Tamework tamed component or a role id that starts with `Tamed`).
- `OwnerRestricted` (default true). If true, only the owner can capture.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on capture.
- `SoundEvent` (optional). Sound event to play on capture.
- `CooldownMs` (optional). Per item capture cooldown.
- `MaxDistance` (optional). Max distance for capture.

## Spawn settings
Fields:
- `AssignsOwner` (default true). Assigns owner to spawned NPC.
- `OwnerRestricted` (default true). If true, only the owner can spawn.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on spawn.
- `SoundEvent` (optional). Sound event to play on spawn.
- `CooldownMs` (optional). Per item spawn cooldown.
- `MaxDistance` (optional). Max distance for spawn.

Captured Tamework NPC names are stored on the spawner item and restored on spawn.

## Icon overrides
Optional overrides for filled spawner icons based on attachments or role.

Fields:
- `IconOverrides`: array of overrides with `Icon` and `Attachments` map.
- `IconOverridesByRole`: map of role id to override arrays.

Attachment maps use the NPC attachment keys as the match criteria.

## Example
```json
{
  "EmptyItemId": "Spawner_Tamework_Example",
  "FilledItemId": "*Spawner_Tamework_Example_State_Filled",
  "AllowedRoles": {
    "Mode": "Allowlist",
    "Allowlist": [ "Mob_Tamework_Interact_Test" ]
  },
  "Capture": {
    "ClearsOwner": true,
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  },
  "Spawn": {
    "AssignsOwner": true,
    "OwnerRestricted": true,
    "ParticleSystem": "Poof_Small",
    "SoundEvent": "SFX_Tamework_Poof",
    "CooldownMs": 500,
    "MaxDistance": 5
  }
}
```

## Reloading
Use `/tw reloadconfig` to reload spawner configs into the item feature registry.

# Spawner Config (TwSpawnerConfig)

## Overview
Spawner items use `TwSpawnerConfig` assets to control capture and spawn behavior. These assets are converted into per-item feature configs at runtime and executed through `TameworkSpawn` + spawner services.

## Runtime Architecture (Contributor View)
Spawner runtime is split into an orchestrator plus focused services:
- Orchestrator: `SpawnerFeatureHandler`
- Policy + validation: `SpawnerCapturePolicyService`, `SpawnerRolePolicyService`, `SpawnerOwnershipPolicyService`
- Metadata + identity/state: `SpawnerCaptureMetadataService`, `SpawnerNpcIdentityService`, `SpawnerNpcStateService`, `SpawnerItemStackMetadataService`
- Placement/effects/inventory: `SpawnerSpawnPositionService`, `SpawnerEffectService`, `SpawnerPlayerInventoryService`
- Capture finalization and linked-companion sync: `SpawnerCaptureFinalizerService`, `SpawnerLinkedNpcSyncService`

When extending spawner behavior, add logic to these service domains instead of centralizing it in the orchestrator.

## Asset location
`<ModRoot>/Server/Tamework/Items/Spawners/*.json`

## Core fields
- `EmptyItemId` (required). The empty spawner item id to bind this config to.
- `FilledItemId` (optional). The filled variant item id, if used.
- `IconDefault` (optional). Default icon override used for filled items.
- `TooltipMode` (optional, default `Additive`). Controls DynamicTooltipsLib composition for captured-spawner tooltip lines.
  - `Additive`: appends Tamework lines (`Name`, `Role`) to the base tooltip.
  - `Replace`: writes Tamework lines as override description text.

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

The easiest way to author icon overrides is the Blockbench UI workflow in
[Spawner Icon Generation](Spawner-Icon-Generation.md). Use the batch manifest
workflow on that page when you need to regenerate a large curated set across
many models or upstream mod archives.

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
Use `/tw reloadconfig` to reload spawner, naming, and command item configs into the item feature registries.
When DynamicTooltipsLib is present, Tamework also invalidates and refreshes tooltip caches on reload.

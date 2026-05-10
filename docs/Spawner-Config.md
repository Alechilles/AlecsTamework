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
- `RequireTamed` (default true). Only allow capture if NPC is tamed (Tamework tamed component or a role id that starts with `Tamed`).
- `OwnerRestricted` (default true). If true, only the owner can capture.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on capture.
- `SoundEvent` (optional). Sound event to play on capture.
- `CooldownMs` (optional). Per item capture cooldown.
- `MaxDistance` (optional). Max distance for capture.

## Spawn settings
Fields:
- `OwnerRestricted` (default true). If true, only the owner can spawn.
- `RequireOwner` (optional override). If set, explicitly require or skip owner checks.
- `ParticleSystem` (optional). Particle system to play on spawn.
- `SoundEvent` (optional). Sound event to play on spawn.
- `CooldownMs` (optional). Per item spawn cooldown.
- `MaxDistance` (optional). Max distance for spawn.

Captured Tamework NPC names are stored on the spawner item and restored on spawn.

`Capture.ClearsOwner` and `Spawn.AssignsOwner` are controlled by `/tw settings`. Older configs that still contain those fields continue to load, but new item configs should not author them.

## Icon overrides
Optional overrides for filled spawner icons based on attachments or role.

Fields:
- `IconOverrides`: array of overrides with `Icon` and `Attachments` map.
- `IconOverridesByRole`: map of role id to override arrays.

Attachment maps use the NPC attachment keys as the match criteria.

### Batch generation helper
Use `scripts/tools/generate_spawner_icon_overrides.py` to generate
`IconOverridesByRole` from a model's `RandomAttachmentSets`.

Example:
```bash
python scripts/tools/generate_spawner_icon_overrides.py \
  --asset-root src/main/resources \
  --model Server/Models/Livestock/Sheep.json \
  --spawner-config Server/Tamework/Items/Spawners/Spawner_Tamework_Example.json \
  --roles Sheep,Tamed_Sheep \
  --include-empty-set Fleece \
  --icon-template "Icons/ItemsGenerated/Spawner_Sheep_{role}_{set_fleece}_{set_basecolor}.png" \
  --write-spawner Server/Tamework/Items/Spawners/Spawner_Tamework_Example.generated.json
```

Notes:
- `--include-empty-set <SetName>` adds an explicit empty option for harvested/removed states.
- Empty attachment maps cannot match as an override at runtime; use `IconDefault` for that state.
- By default, unknown roles are derived from `AllowedRoles.Allowlist` when `--roles` is omitted.
- Model sources can be read directly from a zip using `mod.zip!Server/Models/...json`.

### Renderer job export
The same tool writes renderer jobs JSON for external pipelines (for example a
Blockbench plugin/script worker) with:
- output icon path per role/combination
- selected attachment option assets (model + texture)
- resolved absolute file paths under `Common/`
- camera + icon-size metadata

Example:
```bash
python scripts/tools/generate_spawner_icon_overrides.py \
  --asset-root src/main/resources \
  --model "C:/Users/22ale/AppData/Roaming/Hytale/UserData/Mods/Aures_Livestock_10_03_2026.zip!Server/Models/Livestock/Sheep.json" \
  --roles Sheep,Tamed_Sheep \
  --include-empty-set Fleece \
  --icon-template "Icons/ItemsGenerated/Spawner_Sheep_{role}_{set_fleece}_{set_basecolor}.png" \
  --renderer-jobs-out .tmp/sheep_render_jobs.json \
  --icon-size 128 \
  --camera-scale 1.0 \
  --camera-rotation 22.5,45,22.5 \
  --camera-translation 0,-13.5
```

### Blockbench batch renderer worker
Use `scripts/tools/blockbench/tamework_spawner_icon_batch_renderer.js` as a
local Blockbench plugin to consume the renderer jobs JSON and write all icon
PNGs in one run.

Setup:
1. Copy the plugin file into your Blockbench plugins folder:
   - Windows: `%APPDATA%/Blockbench/plugins/`
   - Optional helper:
     `powershell -ExecutionPolicy Bypass -File scripts/tools/blockbench/install_tamework_spawner_icon_batch_renderer.ps1`
2. In Blockbench, open `File -> Plugins`, then load/enable
   `tamework_spawner_icon_batch_renderer.js`.
3. Ensure the `Hytale Models` plugin is enabled (this provides the
   `blockymodel` codec the batch renderer depends on).

Run:
1. In Blockbench, use `Tools -> Run Tamework Spawner Icon Batch`.
2. Select the jobs JSON generated by
   `generate_spawner_icon_overrides.py --renderer-jobs-out ...`.
3. The worker loads each base model + selected attachments and writes PNGs to
   each job's `outputIconFile` path.

Notes:
- The worker uses the jobs `defaults` camera metadata (`rotation`, `scale`,
  `translation`).
- `translation` is applied as a screen-space pixel offset after capture.
- Jobs continue after per-entry failures; a completion dialog summarizes
  success/fail counts and sample errors.

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
  }
}
```

## Reloading
Use `/tw reloadconfig` to reload spawner, naming, and command item configs into the item feature registries.
When DynamicTooltipsLib is present, Tamework also invalidates and refreshes tooltip caches on reload.

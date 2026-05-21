---
title: "Spawner Icon Generation"
order: 2
published: true
draft: false
---
# Spawner Icon Generation

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework includes a Blockbench plugin and a Python generator for producing filled spawner icons and spawner icon override entries from model `RandomAttachmentSets`.

Use the Blockbench wizard for normal single-model work. Use the batch manifest workflow when a mod needs to regenerate a large curated icon set across many models, roles, or upstream archives.

## Prerequisites

- Install or enable the Hytale Models Blockbench plugin. The Tamework renderer depends on Blockbench's `blockymodel` codec.
- Install the Tamework Blockbench plugin:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/tools/blockbench/install_tamework_spawner_icon_batch_renderer.ps1
```

- After installing, open Blockbench and confirm the Tools menu contains:
  - `Generate + Run Tamework Spawner Batch Wizard`
  - `Run Tamework Spawner Batch (From Jobs JSON)`

## Recommended UI Workflow

Use this flow when generating icons for one model and when you want to preview the camera, choose attachment sets, render PNGs, and optionally write the spawner JSON from one dialog.

1. Open Blockbench.
2. Use `Tools -> Generate + Run Tamework Spawner Batch Wizard`.
3. In `Source`, choose the model JSON path.
4. Optionally choose a spawner JSON path.
5. In `Variants`, choose how combinations are generated.
6. Use `Calculate Combos` before rendering.
7. In `Camera & Frame`, tune icon size, zoom, rotation, and screen position.
8. Enable `Auto Frame` when a batch contains differently sized models and the renderer should zoom out and recenter each captured PNG from its visible pixels.
9. Use `Preview First Combo` to verify framing.
10. In `Outputs`, choose whether to save jobs/manifest JSON and whether to write spawner overrides.
11. Enable `Shared Role Group` when multiple selected roles look identical and should share one `IconOverrideGroups` entry and one icon file per attachment combo.
12. Click `Run Batch`.

The wizard renders the icons, writes any selected JSON outputs, and shows a completion summary. If `Write Spawner Overrides` is enabled, it merges generated entries into `IconOverridesByRole` by default, or into `IconOverrideGroups` when `Shared Role Group` is enabled.

## Jobs JSON Workflow

Use this flow when renderer jobs already exist, usually from the Python generator or a previous wizard run.

1. Open Blockbench.
2. Use `Tools -> Run Tamework Spawner Batch (From Jobs JSON)`.
3. Select the jobs JSON file.
4. The plugin loads each base model plus selected attachments and writes PNGs to each job's `outputIconFile`.

Jobs use the schema `tamework.spawner-icon-render-jobs.v1`. Each job contains the resolved model, texture, selected attachment assets, output icon path, and camera metadata needed by the renderer.

## Python Single-Model Workflow

The Python generator can create override data and renderer jobs without opening the Blockbench wizard. This is useful for repeatable local scripts or when the render step will happen later through the jobs JSON action.

```bash
python scripts/tools/generate_spawner_icon_overrides.py \
  --asset-root src/main/resources \
  --model Server/Models/Livestock/Sheep.json \
  --spawner-config Server/Tamework/Items/Spawners/Spawner_Tamework_Example.json \
  --roles Sheep,Tamed_Sheep \
  --include-empty-set Fleece \
  --icon-template "Icons/ItemsGenerated/Spawner_Sheep_{set_fleece}_{set_basecolor}.png" \
  --icon-override-mode group \
  --camera-auto-frame \
  --write-spawner Server/Tamework/Items/Spawners/Spawner_Tamework_Example.generated.json \
  --renderer-jobs-out .tmp/sheep_render_jobs.json
```

Notes:

- `--include-empty-set <SetName>` adds an explicit empty option for harvested or removed attachment states.
- Models with no `RandomAttachmentSets` generate one `base` render job. In shared group mode, that base icon is written as `IconOverrideGroups[].IconDefault`.
- If `--roles` is omitted, roles are derived from `AllowedRoles.Allowlist` when a spawner config is provided.
- Model sources can be read directly from a zip using `mod.zip!Server/Models/...json`.
- `--icon-override-mode group` writes one `IconOverrideGroups` entry for all selected roles. In group mode, use an icon template without `{role}` when the roles should reference the same PNG.
- `--camera-auto-frame` writes renderer metadata that asks the Blockbench plugin to zoom out and recenter each output using screenshot alpha bounds.

## Batch Manifest Workflow

Use a batch manifest when a mod needs to maintain a curated matrix of models and attachment sets. Shared source roots keep release-specific paths in one place, while each entry references a source id and a model-relative path.

Example manifest:

```json
{
  "defaults": {
    "iconTemplate": "Icons/ItemsGenerated/Spawner_{combo_slug}.png",
    "iconOverrideMode": "group",
    "rendererName": "Animal Husbandry curated icons",
    "iconSize": 128,
    "cameraScale": 1.0,
    "cameraRotation": [22.5, 45, 22.5],
    "cameraTranslation": [0, -13.5],
    "cameraAutoFrame": true,
    "cameraAutoFramePadding": 4,
    "cameraAutoFrameMaxAttempts": 6
  },
  "sources": {
    "baseGame": {
      "modelsRoot": "${HYTALE_INSTALL}/release/package/game/latest/Server/Models"
    },
    "auresLivestock": {
      "modelsRoot": "${MANIFEST_DIR}/sources/Aures_Livestock.zip!Server/Models"
    }
  },
  "entries": [
    {
      "id": "goat_base",
      "source": "baseGame",
      "model": "Livestock/Goat.json",
      "roles": ["Goat", "Goat_Tamed"],
      "keepAttachmentSets": ["BaseColor", "Horns"]
    },
    {
      "id": "goat_aures",
      "source": "auresLivestock",
      "model": "Livestock/Goat.json",
      "roles": "Goat,Goat_Tamed",
      "keepAttachmentSets": ["BaseColor", "Horns"]
    }
  ]
}
```

Run:

```bash
python scripts/tools/generate_spawner_icon_overrides.py \
  --asset-root src/main/resources \
  --batch-manifest tools/animal_husbandry_icons.batch.json \
  --spawner-config Server/Tamework/Items/Spawners/AHSpawnSoulLantern.json \
  --write-spawner Server/Tamework/Items/Spawners/AHSpawnSoulLantern.generated.json \
  --manifest-out .tmp/animal_husbandry_icon_manifest.json \
  --renderer-jobs-out .tmp/animal_husbandry_render_jobs.json
```

Then open Blockbench and use `Tools -> Run Tamework Spawner Batch (From Jobs JSON)` with the generated jobs file.

## Batch Manifest Notes

- `sources.<id>.modelsRoot` supports normal directories and zip roots such as `Some_Mod.zip!Server/Models`.
- Source paths can use environment variables like `${HYTALE_INSTALL}`.
- `${MANIFEST_DIR}` resolves to the directory containing the batch manifest.
- Relative `modelsRoot` values resolve relative to the manifest file.
- Entry `model` paths are relative to the chosen source's `modelsRoot`; leading slashes are ignored.
- `keepAttachmentSets` limits generated combinations to the visual attachment sets that should affect icons. Omit it to generate all sets.
- Entries can override defaults including `iconTemplate`, `iconSize`, `cameraScale`, `cameraRotation`, `cameraTranslation`, `includeEmptySets`, `cameraAutoFrame`, `cameraAutoFramePadding`, `cameraAutoFrameMaxAttempts`, `emptyValueToken`, `iconOverrideMode`, and `maxCombos`.
- `cameraAutoFrame` keeps fixed authored rotation and baseline zoom, then the Blockbench renderer zooms out only when the visible pixels touch the configured padding and recenters the result before applying any explicit screen translation.
- `iconOverrideMode: "group"` writes one shared `IconOverrideGroups` entry per manifest entry and one render job per attachment combo. The default `byRole` mode keeps the older `IconOverridesByRole` output.
- Entries whose model has no `RandomAttachmentSets` should omit `keepAttachmentSets`; they generate a single `{combo_slug}` value of `base`. In group mode, that icon becomes the group's `IconDefault`.

## Output Files

- Icon PNGs are written under the configured output directory relative to `Common/`.
- Manifest JSON records the generated combinations and role mappings.
- Jobs JSON records the render instructions consumed by the Blockbench plugin.
- Spawner JSON output contains merged `IconOverridesByRole` entries in default mode, or appended `IconOverrideGroups` entries in shared group mode. Shared groups may include `IconDefault` for a base-only role set.
- During rendering, the Blockbench plugin closes each temporary model project after its screenshot is captured so large batches do not accumulate hundreds of open Blockbench tabs.

## Related Pages

- [Spawner System Guide](/mod/alecs-tamework/spawner-system-guide)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)

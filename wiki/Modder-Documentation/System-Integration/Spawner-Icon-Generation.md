---
title: "Spawner Icon Generation"
order: 2
published: true
draft: false
---
# Spawner Icon Generation

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework includes a Blockbench plugin and a Python generator for producing companion icon PNGs and shared `TwDynamicIconConfig` assets from model `RandomAttachmentSets`.

Use the Blockbench wizard for normal single-model work. Use the batch manifest workflow when a mod needs to regenerate a large curated icon set across many models, roles, or upstream archives.

## Prerequisites

- Install or enable the Hytale Models Blockbench plugin. The Tamework renderer depends on Blockbench's `blockymodel` codec.
- Install the Tamework Blockbench plugin:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/tools/blockbench/install_tamework_spawner_icon_batch_renderer.ps1
```

- After installing, open Blockbench and confirm the Tools menu contains:
  - `Generate + Run Tamework Dynamic Icon Wizard`
  - `Run Tamework Dynamic Icon Batch (From Jobs JSON)`

## Recommended UI Workflow

Use this flow when generating icons for one model and when you want to preview the camera, choose attachment sets, render PNGs, and write the dynamic icon JSON from one dialog.

1. Open Blockbench.
2. Use `Tools -> Generate + Run Tamework Dynamic Icon Wizard`.
3. In `Source`, choose the model JSON path.
4. Enter the roles that share this appearance. No spawner JSON is required.
5. In `Variants`, choose how combinations are generated.
6. Use `Calculate Combos` before rendering.
7. In `Camera & Frame`, tune icon size, zoom, rotation, and screen position.
8. Enable `Auto Frame` when a batch contains differently sized models and the renderer should zoom out and recenter each captured PNG from its visible pixels.
9. Use `Preview First Combo` to verify framing.
10. In `Outputs`, choose whether to save jobs/manifest JSON and enable `Write Dynamic Icon Asset`.
11. Set `Dynamic Icon Asset Path (optional)` to choose the output; by default it uses `Server/Tamework/DynamicIcons/<modelName>.json`. All selected roles share its variants and PNGs.
12. Click `Run Batch`.

The wizard renders the icons, writes the selected outputs, and shows a completion
summary. Its config contains `RoleIds`, ordered `IconOverrides`, and `IconDefault`
for a base-only model. It replaces the chosen generated config file, so keep
hand-authored rules in separate assets or include them in the generation source.
It does not edit spawner assets.

## Jobs JSON Workflow

Use this flow when renderer jobs already exist, usually from the Python generator or a previous wizard run.

1. Open Blockbench.
2. Use `Tools -> Run Tamework Dynamic Icon Batch (From Jobs JSON)`.
3. Select the jobs JSON file.
4. The plugin loads each base model plus selected attachments and writes PNGs to each job's `outputIconFile`.

Jobs use the schema `tamework.spawner-icon-render-jobs.v1`. Each job contains the resolved model, texture, selected attachment assets, output icon path, and camera metadata needed by the renderer.

## Python Single-Model Workflow

The Python generator can create override data and renderer jobs without opening the Blockbench wizard. This is useful for repeatable local scripts or when the render step will happen later through the jobs JSON action.

```bash
python scripts/tools/generate_spawner_icon_overrides.py \
  --asset-root examples/asset-pack \
  --model Server/Models/Livestock/Tamework_Example.json \
  --roles Mob_Tamework_Example,Mob_Tamework_Example_Baby \
  --include-empty-set Fur \
  --icon-template "Icons/ItemsGenerated/Tamework_Spawner_{combo_slug}.png" \
  --camera-auto-frame \
  --dynamic-icon-id TwDynamicIconExample \
  --dynamic-icons-output-dir Server/Tamework/DynamicIcons \
  --renderer-jobs-out .tmp/sheep_render_jobs.json
```

Notes:

- `--include-empty-set <SetName>` adds an explicit empty option for harvested or removed attachment states.
- Models with no `RandomAttachmentSets` generate one `base` render job and use that PNG as `IconDefault`.
- Supply `--roles` explicitly. Roles are no longer inferred from spawner configs.
- Model sources can be read directly from a zip using `mod.zip!Server/Models/...json`.
- All selected roles share one asset. `{role}` uses the first supplied role for shared paths.
- `--write-dynamic-icon <path>` chooses a single output file; otherwise use `--dynamic-icons-output-dir` and `--dynamic-icon-id`.
- `--icon-default`, `--enabled` / `--no-enabled`, and `--priority` set optional config fields.
- `--camera-auto-frame` writes renderer metadata that asks the Blockbench plugin to zoom out and recenter each output using screenshot alpha bounds.

## Batch Manifest Workflow

Use a batch manifest when a mod needs to maintain a curated matrix of models and attachment sets. Shared source roots keep release-specific paths in one place, while each entry references a source id and a model-relative path.

Example manifest:

```json
{
  "defaults": {
    "iconTemplate": "Icons/ItemsGenerated/Spawner_{combo_slug}.png",
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
      "iconTemplate": "Icons/ItemsGenerated/MyMod/BaseGoat/{combo_slug}.png",
      "source": "baseGame",
      "model": "Livestock/Goat.json",
      "roles": ["Goat", "Goat_Tamed"],
      "keepAttachmentSets": ["BaseColor", "Horns"]
    },
    {
      "id": "goat_aures",
      "iconTemplate": "Icons/ItemsGenerated/MyMod/AuresGoat/{combo_slug}.png",
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
  --dynamic-icons-output-dir Server/Tamework/DynamicIcons \
  --dynamic-icon-id-prefix AH_DynamicIcon \
  --manifest-out .tmp/animal_husbandry_icon_manifest.json \
  --renderer-jobs-out .tmp/animal_husbandry_render_jobs.json
```

Then open Blockbench and use `Tools -> Run Tamework Dynamic Icon Batch (From Jobs JSON)` with the generated jobs file.

## Batch Manifest Notes

- `sources.<id>.modelsRoot` supports normal directories and zip roots such as `Some_Mod.zip!Server/Models`.
- Source paths can use environment variables like `${HYTALE_INSTALL}`.
- `${MANIFEST_DIR}` resolves to the directory containing the batch manifest.
- Relative `modelsRoot` values resolve relative to the manifest file.
- Entry `model` paths are relative to the chosen source's `modelsRoot`; leading slashes are ignored.
- `keepAttachmentSets` limits generated combinations to the visual attachment sets that should affect icons. Omit it to generate all sets.
- `renderAttachmentDefaults` supplies fixed set-to-option choices for omitted visual details, such as `{"Eyes": "Brown", "Mane": "Short"}`. These details appear in every rendered PNG but do not multiply combinations or constrain the dynamic icon rules. A set cannot be both fixed and generated.
- Prepare inherited model fields and `DefaultAttachments` before calling the generator. The generator consumes complete model JSON; Animal Husbandry's `prepare_models.py` resolves its pinned sources and patch-provided appearances into temporary render inputs.
- Entries can override defaults including `iconTemplate`, `iconSize`, `cameraScale`, `cameraRotation`, `cameraTranslation`, `includeEmptySets`, `cameraAutoFrame`, `cameraAutoFramePadding`, `cameraAutoFrameMaxAttempts`, `emptyValueToken`, `iconDefault`, `enabled`, `priority`, and `maxCombos`.
- `cameraAutoFrame` keeps fixed authored rotation and baseline zoom, then the Blockbench renderer zooms out only when the visible pixels touch the configured padding and recenters the result before applying any explicit screen translation.
- Entries with the same normalized role set merge into one dynamic asset in manifest order. Keep more specific attachment predicates before generic ones. The first default is retained.
- Output IDs use `<dynamic-icon-id-prefix>_<firstRole>`, for example `AH_DynamicIcon_Goat`. The generator rejects asset filename collisions between different role sets. It also rejects conflicting render jobs that target the same PNG path, including entries sharing a role set; identical jobs can share a PNG.
- Use the same `enabled` and `priority` for entries sharing a role set; they become one config.
- Generation replaces each output asset; it does not merge with hand edits in existing files.
- Entries whose model has no `RandomAttachmentSets` should omit `keepAttachmentSets`; they generate a single `{combo_slug}` value of `base`. That icon becomes the asset's `IconDefault`.

## Output Files

- Icon PNGs are written under the configured output directory relative to `Common/`.
- Manifest JSON records the generated combinations and role mappings.
- Jobs JSON records the render instructions consumed by the Blockbench plugin.
- Dynamic icon JSON output contains `RoleIds`, ordered `IconOverrides`, and an optional `IconDefault`. Capture items and both command panels discover these assets by role.
- During rendering, the Blockbench plugin closes each temporary model project after its screenshot is captured so large batches do not accumulate hundreds of open Blockbench tabs.

## Related Pages

- [Spawner System Guide](/mod/alecs-tamework/spawner-system-guide)
- [TwDynamicIconConfig Reference](/mod/alecs-tamework/twdynamiciconconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)

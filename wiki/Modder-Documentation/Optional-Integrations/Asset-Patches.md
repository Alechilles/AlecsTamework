---
title: "Asset Patches"
order: 12
published: true
draft: false
---
# Asset Patches

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Optional asset patches let a mod ship Tamework-powered behavior as an optional integration. The base asset stays valid without Tamework, while patch files under `Server/Tamework/Patches/**/*.json` are applied only when Tamework is installed.

Use subdirectories under `Server/Tamework/Patches` to group patches by mod, integration, or feature.

Tamework scans patch files before server JSON asset validation, applies them to their target JSON or JSON-like asset, and writes patched copies into the generated patch pack. Supported targets include NPC roles/templates, item assets and root/item interactions, projectiles, particles, entity effects, drops, Tamework config assets, and other server-side JSON-like asset files. At runtime, `/tw patches reload` rescans patch files and refreshes generated files in place.

NPC template patches are one use case of the asset patch system. Item patches are another common use case: a downstream mod can ship a vanilla-safe item asset, then patch in `TameworkSpawn`, `TameworkCommand`, `TameworkNameNpc`, or other Tamework-only action wiring only when Tamework is installed.

## Pages

- [Asset Patch Operations](/mod/alecs-tamework/asset-patch-operations): `Add`, `Merge`, `Replace`, `Remove`, `Insert`, JSON paths, anchors, and idempotency.
- [Asset Patch Macros](/mod/alecs-tamework/asset-patch-macros): compact helpers for common Tamework instruction branches.
- [Asset Patch Workflow](/mod/alecs-tamework/asset-patch-workflow): how to design patchable assets and test them safely.
- [Asset Patch Troubleshooting](/mod/alecs-tamework/asset-patch-troubleshooting): common validation, reload, and spawn failures.

## Patch Shape

```json
{
  "Id": "MyMod_Livestock_Tamework",
  "Target": "Server/Item/Items/Commands/My_Item.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

Operations support `Add`, `Merge`, `Replace`, `Remove`, and `Insert`. Paths use JSON pointer syntax, such as `/RootItemInteraction/Actions` or `/InteractionInstruction/Instructions`.

`Insert` can use `Find` anchors, `Before`/`After` placement, `Existing` idempotency matchers, and `Required: false` for optional anchors.

## Macros

Macros are convenience expansions, not automatic placement. They still require explicit paths and anchors.

- `TameworkInteractionBridge`: inserts `TameworkInteractPrompt` and `TameworkInteract` branches.
- `TameworkHookInstruction`: inserts a `TameworkHook` sensor branch.
- `TameworkStateInstruction`: inserts a branch referencing a Tamework instruction component.

Tamework includes a bundled NPC fixture at `Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`, `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json`, and `Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`.

## Reload Contract

Startup generation happens before server-side JSON asset validation, so generated patch outputs can satisfy validation when Tamework is present.

Runtime reload is target-specific:

- Tamework does not call Hytale's generic asset-store reload path from live commands because that path can block the world thread.
- Hytale can still pick up generated-pack file changes through its normal asset watcher. `/tw patches selftest` observes generated-pack reload events for NPC role/template, item, Tamework config, particle, and common fixtures.
- Common asset hot-reload confirmation means the generated file is active in Hytale's server-side common asset registry with the expected hash. It is not a client acknowledgment.
- Unknown paths are reported as restart-required.

Targets reported as restart-required are still regenerated on disk, but Tamework did not observe a safe hot-reload completion for that target.

## Diagnostics

Use `/tw patches status` to inspect the last patch run and `/tw patches reload` to refresh generated files from currently loaded mods. Use `/tw patches selftest` when you need an end-to-end live check of generation and reload classification. Status output lists generated targets, removed generated targets, hot-reloaded targets, failures, skipped operations, and targets that require a restart. `/tw patches reload` regenerates files; `/tw patches selftest` additionally waits for Hytale's asynchronous generated-pack reload events.

If a required anchor is missing, Tamework logs the patch id, operation id, target, and failure reason. The failed target is not published as a partial generated asset.

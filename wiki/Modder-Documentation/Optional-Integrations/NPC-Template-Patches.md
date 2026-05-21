---
title: "NPC Template Patches"
order: 12
published: true
draft: false
---
# NPC Template Patches

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

NPC template patches let a mod ship Tamework-powered NPC behavior as an optional integration. The base role/template stays valid without Tamework, while patch files under `Server/Tamework/Patches/**/*.json` are applied only when Tamework is installed.

Use subdirectories under `Server/Tamework/Patches` to group patches by mod, integration, or feature.

Tamework scans patch files before NPC validation, applies them to their target role/template JSON, writes patched copies, and loads those generated copies into the NPC builder manager. At runtime, `/tw patches reload` rescans patch files and refreshes generated files in place.

## Pages

- [NPC Template Patch Operations](/mod/alecs-tamework/npc-template-patch-operations): `Add`, `Merge`, `Replace`, `Remove`, `Insert`, JSON paths, anchors, and idempotency.
- [NPC Template Patch Macros](/mod/alecs-tamework/npc-template-patch-macros): compact helpers for common Tamework instruction branches.
- [NPC Template Patch Workflow](/mod/alecs-tamework/npc-template-patch-workflow): how to design patchable templates and test them safely.
- [NPC Template Patch Troubleshooting](/mod/alecs-tamework/npc-template-patch-troubleshooting): common validation, reload, and spawn failures.

## Patch Shape

```json
{
  "Id": "MyMod_Livestock_Tamework",
  "Target": "Server/NPC/Roles/_Core/Templates/My_Template.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

Operations support `Add`, `Merge`, `Replace`, `Remove`, and `Insert`. Paths use JSON pointer syntax, such as `/InteractionInstruction/Instructions`.

`Insert` can use `Find` anchors, `Before`/`After` placement, `Existing` idempotency matchers, and `Required: false` for optional anchors.

## Macros

Macros are convenience expansions, not automatic placement. They still require explicit paths and anchors.

- `TameworkInteractionBridge`: inserts `TameworkInteractPrompt` and `TameworkInteract` branches.
- `TameworkHookInstruction`: inserts a `TameworkHook` sensor branch.
- `TameworkStateInstruction`: inserts a branch referencing a Tamework instruction component.

Tamework includes a bundled fixture at `Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`, `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json`, and `Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`.

## Diagnostics

Use `/tw patches status` to inspect the last patch run and `/tw patches reload` to refresh generated files from currently loaded mods.

If a required anchor is missing, Tamework logs the patch id, operation id, target, and failure reason. The failed target is not published as a partial generated template.

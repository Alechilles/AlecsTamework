---
title: "NPC Template Patches"
order: 12
published: true
draft: false
---
# NPC Template Patches

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

NPC template patches let a mod ship Tamework-powered NPC behavior as an optional integration. The base role/template stays valid without Tamework, while patch files under `Server/Tamework/NpcTemplatePatches/*.json` are applied only when Tamework is installed.

Tamework scans patch files before NPC validation, applies them to their target role/template JSON, writes the patched output into a disposable generated cache, and registers that cache as a runtime-only asset pack. The generated cache is wiped and rebuilt on startup and `/tw templatepatches reload`, so removing Tamework or the source mod does not leave active generated templates behind.

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

## Diagnostics

Use `/tw templatepatches status` to inspect the last patch run and `/tw templatepatches reload` to rebuild the generated pack from currently loaded mods.

If a required anchor is missing, Tamework logs the patch id, operation id, target, and failure reason. The failed target is not published as a partial generated template.

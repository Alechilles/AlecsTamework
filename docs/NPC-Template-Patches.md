# NPC Template Patches

NPC template patches let another mod ship Tamework-powered role/template behavior without making Tamework a required dependency.

The base mod keeps its normal NPC templates free of `Tamework*` builders. It can then place optional patch files under:

```text
Server/Tamework/Patches/**/*.json
```

Subdirectories are supported and recommended for grouping patches by integration, mod, or feature. When Tamework is not installed, those files are just inert Tamework assets. When Tamework is installed, it scans them before NPC role validation, applies them to their target templates, writes the patched templates into a disposable generated cache, and registers that cache as a runtime-only asset pack.

Generated patch output is not written into a normal auto-loaded asset location. If Tamework or the source mod is removed, stale generated files are not active; the cache is wiped and rebuilt on startup and on `/tw patches reload`.

## Patch File Shape

```json
{
  "Id": "MyMod_Livestock_Tamework",
  "Target": "Server/NPC/Roles/_Core/Templates/My_Template.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

- `Id`: stable patch id used in diagnostics.
- `Target`: NPC role/template JSON path to patch.
- `Priority`: lower values apply first for the same target.
- `Enabled`: optional; defaults to `true`.
- `Operations`: ordered raw operations and macro operations.

## Raw Operations

Supported raw `Op` values:

- `Add`: add a field or array item at `Path`.
- `Merge`: deep-merge an object into an existing object at `Path`.
- `Replace`: replace an existing value at `Path`.
- `Remove`: remove an existing value at `Path`.
- `Insert`: insert into an array at `Path`.

Paths use JSON pointer syntax, such as `/InteractionInstruction/Instructions`.

`Insert` supports:

- `Position`: `Start`, `End`, `Before`, or `After`.
- `Find`: object matcher for `Before` or `After` anchors.
- `Existing`: object matcher that skips the insert when the generated value is already present.
- `Required`: defaults to `true`; set `false` for optional anchors.

## Macros

Macros expand into raw operations. They are convenience helpers, not automatic feature placement. Every macro still needs the same explicit `Path`, `Position`, and `Find` anchor information that a raw insert would need.

Supported v1 macros:

- `TameworkInteractionBridge`: inserts `TameworkInteractPrompt` and `TameworkInteract` branches.
- `TameworkHookInstruction`: inserts a branch with a `TameworkHook` sensor.
- `TameworkStateInstruction`: inserts a branch that references a Tamework component instruction.

See `docs/examples/AH_Livestock_TemplatePatch.json` for an AH-livestock-style fixture.

Tamework also ships an in-game fixture:

- Base template: `Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`
- Role: `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json`
- Patch: `Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`

The base template is intentionally barebones and avoids Tamework builders. The patch adds the Tamework interaction bridge, command states, needs seeking, and breeding-pair movement so the role can be tested with Tamework's existing example spawner and tools.

## Diagnostics

Use:

```text
/tw patches status
/tw patches reload
```

`status` prints the last scan/generation summary, generated targets, skipped operations, and failures.

`reload` wipes the generated cache, rescans currently loaded packs, republishes generated templates, and asks the NPC builder manager to reload the generated pack.

If a required anchor is missing, Tamework logs the patch id, operation id, target, and failure reason. The target is not published as a partial generated template.

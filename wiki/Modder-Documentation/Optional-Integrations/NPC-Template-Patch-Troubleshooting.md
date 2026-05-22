---
title: "NPC Template Patch Troubleshooting"
order: 16
published: true
draft: false
---
# NPC Template Patch Troubleshooting

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Optional Asset Patches](/mod/alecs-tamework/npc-template-patches)

Use this page when a patch does not apply, validation fails, or a patched NPC, item, config, particle, or other JSON-like asset behaves differently after `/tw patches reload`.

## First Checks

Run:

```text
/tw patches status
```

Then check the server log for the patch id, operation id, target path, and failure reason.

For an end-to-end live check, run:

```text
/tw patches selftest
```

This creates isolated fixtures in Tamework's self-test asset pack, applies real optional patches, waits briefly for Hytale's generated-pack reload events, and reports per-target results as generated successfully, hot-reloaded successfully, restart required, or failed. After testing, run `/tw patches selftest cleanup` to remove the fixtures and prune generated self-test outputs.

## Patch File Is Not Found

Likely causes:

- The file is not under `Server/Tamework/Patches`.
- The file extension is not `.json`.
- The JSON is invalid.
- The mod containing the patch file is not loaded.
- The patch has `"Enabled": false`.

Expected layout:

```text
Server/Tamework/Patches/MyMod/MyPatch.json
```

## Target Is Not Found

Check:

- The `Target` path exactly matches the asset path inside the loaded mod.
- The target file exists before Tamework patches are applied.
- The source mod is installed and loaded.

Do not target the generated patch output. Target the original source asset.

## Anchor Is Not Found

Check:

- `Path` points to the correct array.
- `Position` is `Before` or `After` only when `Find` is present.
- `Find` matches the actual JSON shape.
- The anchor is unique enough to avoid matching the wrong branch.

Good anchor:

```json
{
  "$Comment": "Patch anchor: command behaviors"
}
```

Use `Required: false` only if the behavior is truly optional for that target.

## Parameter Is Missing

Example symptom:

```text
Parameter MasterTargetSlot does not exist or is private
```

Fix by merging the parameter earlier in the same patch or in a lower-priority patch:

```json
{
  "Id": "add-master-target-slot",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "MasterTargetSlot": {
      "Value": "MasterTarget"
    }
  }
}
```

## State Is Missing

Example symptom:

```text
State required by a parameter does not exist: Follow
```

Fix by adding the state before adding transitions or branches that reference it.

```json
{
  "Id": "add-follow-state",
  "Op": "Add",
  "Path": "/_ExportStates/-",
  "Value": "Follow"
}
```

## Behavior Duplicates After Reload

Likely cause: a raw `Insert` operation does not have an `Existing` matcher.

```json
{
  "Id": "add-follow-behavior",
  "Op": "Insert",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: command behaviors"
  },
  "Existing": {
    "Instructions": {
      "$Contains": {
        "Reference": "Component_Tamework_Instruction_Follow"
      }
    }
  },
  "Value": {
    "Instructions": [
      {
        "Reference": "Component_Tamework_Instruction_Follow"
      }
    ]
  }
}
```

Macros add their own idempotency matchers for generated branches. Raw inserts should usually define `Existing`.

## Spawn Fails After Reload

Runtime reload regenerates generated patch files in place. It does not promise that every asset family can be safely reloaded while the server is running.

If startup works but spawning after reload fails:

- Run `/tw patches status`.
- Check whether the target failed during the reload run.
- Verify the generated target still exists in the generated patch output.
- Check whether the target is listed as restart-required.
- Restart the server after changing target identities, target paths, or asset names.

## Patched Item Still Uses Vanilla Behavior

Check:

- The base item is the patch target, not the generated output.
- The patch inserts into the action array the item actually uses, such as `/Interactions/Primary/Interactions`.
- `/tw patches status` lists the item target as generated.
- `/tw patches selftest` can observe generated-pack item reloads on this Hytale build. If a real item patch still behaves as vanilla, check the generated JSON and restart the server to rule out asset-family-specific runtime state.
- The patched action type and config id match the Tamework feature, such as `TameworkSpawn`, `TameworkCommand`, or `TameworkNameNpc`.

## Restart Required After Reload

This is expected for asset families without a known safe runtime reload path or without an observed Hytale generated-pack reload event. Tamework regenerates the generated file and reports the target clearly instead of pretending it took effect.

Known hot-reload routes:

- NPC role/template targets reload through the NPC builder manager.
- `/tw patches selftest` observes Hytale generated-pack reload events for item, Tamework config, and particle fixtures.

Common assets, unknown target paths, and target families without observed generated-pack reload events require a restart. Tamework does not call Hytale's generic asset-store reload path from live patch commands because that path can block the world thread.

`/tw patches selftest` intentionally includes a restart-required common fixture so operators can confirm that restart-required reporting is working instead of being mistaken for a hot reload.

## Macro Fails

Check:

- `Op` is `"Macro"`.
- `Macro` is one of the supported macro names.
- `Path` points to an existing array.
- `Position: "Before"` or `Position: "After"` includes a matching `Find`.
- Required macro options are present.

Required macro options:

- `TameworkInteractionBridge`: no required option, but `Options.ActionFields.ConfigId` is normally needed.
- `TameworkHookInstruction`: `Options.HookId`.
- `TameworkStateInstruction`: `Options.Component`.

## Interaction Fires Every Tick

Prefer `TameworkInteractionBridge` for normal `TwInteractionConfig` wiring. It creates a prompt branch and a separate `HasInteracted` branch.

If writing raw operations, make sure the final `TameworkInteract` action is gated by an interaction sensor such as `HasInteracted`, not by a sensor that remains true every tick.

## Follow Mode Does Not Work

Check:

- The follow command state or flag is created by the patch.
- Required parameters such as target slots are merged before use.
- The behavior branch is inserted into the instruction array that the template actually runs.
- Base wandering or idle behavior still exists so the NPC has a fallback when not following.

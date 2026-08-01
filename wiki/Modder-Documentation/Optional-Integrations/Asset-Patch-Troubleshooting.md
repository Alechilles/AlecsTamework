---
title: "Asset Patch Troubleshooting"
order: 16
published: true
draft: false
---
# Asset Patch Troubleshooting

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Asset Patches](/mod/alecs-tamework/asset-patches)

Use this page when a Patchwork definition is not discovered, generation fails, validation fails, or a patched NPC, item, config, particle, or other JSON-like asset behaves differently after `/patchwork reload`.

## First Checks

Run:

```text
/patchwork status
```

Then check the server log for the patch id, operation id, target path, and failure reason.

For an isolated generation and condition check, run:

```text
/patchwork selftest
```

This creates one isolated Patchwork run, verifies expected JSON pointers, and cleans that exact run without modifying the production generated pack. Patchwork 1.0.0 truthfully reports a successful self-test as restart-required because it validates generation rather than live Hytale activation.

## Patch File Is Not Found

Likely causes:

- The file is not under `Server/Patchwork/Patches`.
- The file extension is not `.json`.
- The JSON is invalid.
- The mod containing the patch file is not loaded.
- The patch has `"Enabled": false`.
- A different neutral definition shadows a matching legacy definition in the same source pack.
- The runtime shown by `/patchwork status` is passive or incompatible.

Expected layout:

```text
Server/Patchwork/Patches/MyMod/MyPatch.json
```

## Target Is Not Found

Check:

- The `Target` path exactly matches the asset path inside the loaded mod.
- The target file exists before Patchwork applies definitions.
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

## Spawn Fails After Regeneration

`/patchwork reload` regenerates patch files but does not promise live Hytale activation.

If startup works but spawning after regeneration fails:

- Run `/patchwork status`.
- Check whether the target failed during the reload run.
- Verify the generated target still exists in the generated patch output.
- Check whether the target is listed as restart-required.
- Restart the server before testing the regenerated target.

## Patched Item Still Uses Vanilla Behavior

Check:

- The base item is the patch target, not the generated output.
- The patch inserts into the action array the item actually uses, such as `/Interactions/Primary/Interactions`.
- `/patchwork status` lists the item target as generated and the generated-pack integrity is valid.
- The server was restarted after the most recent `/patchwork reload`.
- The patched action type and config id match the Tamework feature, such as `TameworkSpawn`, `TameworkCommand`, or `TameworkNameNpc`.

## Restart Required After Reload

This is the expected Patchwork 1.0.0 result after `/patchwork reload`. Patchwork commits the desired generated state to disk but does not invoke Hytale's generic live-reload path. Tamework deliberately contributes no host-specific reload adapter, so restart the server to consume the changed generated pack.

Do not treat file presence alone as proof of activation. For `stale`, `rollback-failed`, or `RECOVERY_REQUIRED`, preserve the diagnostic directories, stop repeated reload attempts, and follow the first related Patchwork error in the server log.

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

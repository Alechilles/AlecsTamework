# NPC Template Patch Troubleshooting

Use this page when a patch does not apply, validation fails, or an NPC behaves differently after `/tw patches reload`.

## First Checks

Run:

```text
/tw patches status
```

This shows the most recent patch run and whether targets were published, skipped, or failed.

Then check the server log for the patch id, operation id, target path, and failure reason. Patch failures are intended to be specific enough that you can find the bad operation directly.

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

Subdirectories are fine. Files outside `Server/Tamework/Patches` are ignored by the patch scanner.

## Target Is Not Found

Example symptom:

```text
Patch target not found: Server/NPC/Roles/_Core/Templates/MyCow.json
```

Check:

- The `Target` path exactly matches the asset path inside the loaded mod.
- The target file exists before Tamework patches are applied.
- The target uses forward slashes or otherwise normalizes to the same path.
- The source mod is installed and loaded.

Do not target the generated patch output. Target the original source asset.

## Anchor Is Not Found

Example symptom:

```text
Insert operation could not find requested anchor
```

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

Matching operation:

```json
{
  "Id": "insert-command-branch",
  "Op": "Insert",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: command behaviors"
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

Use `Required: false` only if the behavior is truly optional for that target. If the behavior must exist, keep the operation required so validation catches template drift.

## Parameter Is Missing

Example symptom:

```text
Parameter MasterTargetSlot does not exist or is private
```

The patched template references a parameter before that parameter exists.

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

Keep setup operations before behavior operations.

## State Is Missing

Example symptom:

```text
State required by a parameter does not exist: Follow
```

The patched template references a state that the target does not define or export.

Fix by adding the state before adding transitions or branches that reference it. Also verify that the target template actually supports the state section you are editing.

```json
{
  "Id": "add-follow-state",
  "Op": "Add",
  "Path": "/_ExportStates/-",
  "Value": "Follow"
}
```

If the same patch supports multiple template shapes, use separate target-specific patches instead of making one fragile patch handle every layout.

## Behavior Duplicates After Reload

Likely cause: an `Insert` operation does not have an `Existing` matcher.

Add one:

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

Macros add their own idempotency matchers for generated branches. Raw inserts should usually define `Existing` explicitly.

## Spawn Fails After Reload

Runtime reload refreshes generated patch files in place. It does not safely unregister the generated pack while the server is running, because NPC validation can still hold references to loaded builders.

If startup works but spawning after reload fails:

- Run `/tw patches status`.
- Check whether the target failed during the reload run.
- Verify the generated target still exists in the generated patch output.
- Restart the server after changing the set of generated targets or renaming target templates.

Reload is best for iterating on operation contents. Startup validation is still the safest test after changing target identities, target paths, or asset names.

## Macro Fails

Check:

- `Op` is `"Macro"`.
- `Macro` is one of the supported macro names.
- `Path` points to an existing array.
- `Position: "Before"` or `Position: "After"` includes a matching `Find`.
- Required macro options are present.

Required macro options:

- `TameworkInteractionBridge`: no required option, but `Options.ActionFields.ConfigId` is normally needed for real interaction configs.
- `TameworkHookInstruction`: `Options.HookId`.
- `TameworkStateInstruction`: `Options.Component`.

## Interaction Fires Every Tick

This usually means prompt behavior and final interaction behavior are not separated correctly.

Prefer `TameworkInteractionBridge` for normal `TwInteractionConfig` wiring. It creates a prompt branch and a separate `HasInteracted` branch.

If writing raw operations, make sure the final `TameworkInteract` action is gated by an interaction sensor such as `HasInteracted`, not by a sensor that remains true every tick.

## Follow Mode Does Not Work

Check:

- The follow command state or flag is actually created by the patch.
- Required parameters such as target slots are merged before use.
- The behavior branch is inserted into the instruction array that the template actually runs.
- The target branch has a sensor or enabled condition that can become true.
- Base wandering or idle behavior still exists so the NPC has a fallback when not following.

When porting an existing livestock or companion template, compare the generated patched output against a known working Tamework-enabled template.

## Generated Output Looks Wrong

Inspect the generated patched file. It is the final JSON that NPC validation sees.

Use the generated output to answer these questions:

- Did the patch target the file you expected?
- Did operations run in the expected order?
- Did an `Existing` matcher skip an insert?
- Did a `Merge` replace a scalar where you expected a nested object merge?
- Did a macro insert both branches?

If the generated output is correct but validation still fails, the issue is likely a base-game NPC schema or behavior requirement rather than the patch engine.

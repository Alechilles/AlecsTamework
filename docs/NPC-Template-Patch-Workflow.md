# NPC Template Patch Authoring Workflow

The safest patchable template is a normal base-game template first and a Tamework template second. Build the unpatched version so it validates and plays correctly without Tamework, then add optional patch files that upgrade it when Tamework is installed.

## 1. Keep the Base Template Valid

Do not place Tamework-only actions, sensors, motions, components, states, or parameters directly in the base template if the source mod should work without Tamework.

Base template:

```json
{
  "Type": "Abstract",
  "StartState": "Idle",
  "Parameters": {
    "MaxSpeed": {
      "Value": 4
    }
  },
  "Instructions": [
    {
      "$Comment": "Patch anchor: behaviors"
    }
  ],
  "InteractionInstruction": {
    "Instructions": [
      {
        "$Comment": "Patch anchor: interactions"
      }
    ]
  }
}
```

This template is intentionally plain. The comments are valid JSON fields and give patches stable insertion points.

## 2. Add Stable Anchors

Use anchors when a later patch needs to insert behavior in a specific place.

Good anchors are:

- Unique within the array.
- Descriptive enough that another modder understands the intent.
- Stable across normal template edits.

```json
{
  "$Comment": "Patch anchor: command behaviors"
}
```

Avoid using array indexes as long-term patch targets. Indexes change when a template grows.

## 3. Create the Patch File

Patch files belong under:

```text
Server/Tamework/Patches
```

Subdirectories are supported:

```text
Server/Tamework/Patches/MyMod/Livestock/MyCow.json
```

Basic patch file:

```json
{
  "Id": "MyMod_MyCow_Tamework",
  "Target": "Server/NPC/Roles/_Core/Templates/MyCow.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

`Target` should point to the original asset that should be patched. Use forward slashes for readability.

## 4. Add Parameters Before Using Them

If later operations reference new parameters, merge those parameters first.

```json
{
  "Id": "add-tamework-parameters",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "CanFollow": {
      "Value": true,
      "Description": "Whether this NPC can use Tamework follow behavior."
    },
    "MasterTargetSlot": {
      "Value": "MasterTarget",
      "Description": "Target slot used to store the owner."
    }
  }
}
```

This avoids validation errors where an action, sensor, or instruction references a parameter that does not exist yet.

## 5. Add States Before Referencing Them

If a patched branch uses a new state, add the state before adding transitions, sensors, or actions that reference it.

```json
{
  "Id": "add-follow-state",
  "Op": "Add",
  "Path": "/_ExportStates/-",
  "Value": "Follow",
  "Required": false
}
```

Only use `Required: false` when the state list may not exist on every supported template. If the state is required for the target template, leave the operation required so a bad patch fails loudly.

## 6. Insert Behavior with Idempotency

Use `Insert` with both `Find` and `Existing`.

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
    "$Comment": "Tamework patch: follow behavior",
    "Instructions": [
      {
        "Reference": "Component_Tamework_Instruction_Follow"
      }
    ]
  }
}
```

`Existing` matters during reloads and compatibility work. It prevents duplicate branches if the same behavior is already present.

## 7. Use Macros for Common Tamework Branches

When a branch follows a standard Tamework pattern, use a macro.

```json
{
  "Id": "add-interaction-bridge",
  "Op": "Macro",
  "Macro": "TameworkInteractionBridge",
  "Path": "/InteractionInstruction/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: interactions"
  },
  "Options": {
    "ActionFields": {
      "ConfigId": "TwIntMyCow"
    }
  }
}
```

Macros are easiest to read when the patch still does setup explicitly. Merge parameters first, add states second, then run macros that reference those pieces.

## 8. Split Large Integrations

Prefer several focused patches over one huge patch:

```text
Server/Tamework/Patches/MyMod/Livestock/10_Common.json
Server/Tamework/Patches/MyMod/Livestock/20_Interactions.json
Server/Tamework/Patches/MyMod/Livestock/30_FollowCommands.json
```

Use `Priority` when order matters across files:

```json
{
  "Id": "MyMod_Livestock_Common",
  "Target": "Server/NPC/Roles/_Core/Templates/MyCow.json",
  "Priority": 10,
  "Operations": []
}
```

Lower priority runs first. If two patches have the same priority, `Id` decides the order.

## 9. Test Both Modes

Test without Tamework:

1. Remove or disable Tamework.
2. Start the game or server.
3. Confirm the base role/template validates.
4. Spawn the NPC and verify its base behavior.

Test with Tamework:

1. Install Tamework and the source mod.
2. Start the server.
3. Run `/tw patches status`.
4. Spawn the NPC.
5. Verify the Tamework behavior.
6. Run `/tw patches reload`.
7. Spawn again and verify there are no duplicate interactions or missing builders.

## Review Checklist

- The base template has no Tamework-only references.
- Patch files live under `Server/Tamework/Patches`.
- Each patch has a stable `Id` and correct `Target`.
- Operations that create parameters run before operations that use them.
- Operations that create states run before operations that reference them.
- Inserts use stable anchors instead of indexes.
- Inserts include `Existing` unless duplication is impossible.
- Optional operations use `Required: false` only when skipping is acceptable.
- Runtime reload is tested after startup validation.

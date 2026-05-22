---
title: "NPC Template Patch Workflow"
order: 15
published: true
draft: false
---
# NPC Template Patch Workflow

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Optional Asset Patches](/mod/alecs-tamework/npc-template-patches)

The safest patchable template is a normal base-game template first and a Tamework template second. Build the unpatched version so it validates and plays correctly without Tamework, then add optional patches that upgrade it when Tamework is installed.

The same rule applies to every optional asset patch target. Keep the base item, projectile, particle, drop table, entity effect, or Tamework config valid on its own, then let `Server/Tamework/Patches` add Tamework-only fields when the framework is present.

## 1. Keep the Base Template Valid

Do not place Tamework-only actions, sensors, motions, components, states, or parameters directly in the base template if the source mod should work without Tamework.

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

## 2. Add Stable Anchors

Use anchors when a later patch needs to insert behavior in a specific place.

```json
{
  "$Comment": "Patch anchor: command behaviors"
}
```

Good anchors are unique, descriptive, and stable across normal edits.

## 3. Create the Patch File

Patch files belong under:

```text
Server/Tamework/Patches
```

Subdirectories are supported:

```text
Server/Tamework/Patches/MyMod/Livestock/MyCow.json
```

```json
{
  "Id": "MyMod_MyCow_Tamework",
  "Target": "Server/NPC/Roles/_Core/Templates/MyCow.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

For item assets, target the base item and insert Tamework-only actions into the relevant action array:

```json
{
  "Id": "MyMod_CommandItem_Tamework",
  "Target": "Server/Item/Items/Commands/MyCommandItem.json",
  "Operations": [
    {
      "Id": "add-command-action",
      "Op": "Insert",
      "Path": "/RootItemInteraction/Actions",
      "Position": "End",
      "Existing": {
        "Type": "TameworkCommand"
      },
      "Value": {
        "Type": "TameworkCommand",
        "ConfigId": "TwCommandItem_MyCommandItem"
      }
    }
  ]
}
```

## 4. Add Setup Before Behavior

Merge parameters before using them:

```json
{
  "Id": "add-tamework-parameters",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "CanFollow": {
      "Value": true
    },
    "MasterTargetSlot": {
      "Value": "MasterTarget"
    }
  }
}
```

Add states before transitions, sensors, or actions reference them:

```json
{
  "Id": "add-follow-state",
  "Op": "Add",
  "Path": "/_ExportStates/-",
  "Value": "Follow",
  "Required": false
}
```

## 5. Insert Behavior Safely

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
    "Instructions": [
      {
        "Reference": "Component_Tamework_Instruction_Follow"
      }
    ]
  }
}
```

`Existing` prevents duplicate branches during reloads and compatibility work.

## 6. Split Large Integrations

Prefer focused patches over one huge patch:

```text
Server/Tamework/Patches/MyMod/Livestock/10_Common.json
Server/Tamework/Patches/MyMod/Livestock/20_Interactions.json
Server/Tamework/Patches/MyMod/Livestock/30_FollowCommands.json
```

Use `Priority` when order matters across files. Lower priority runs first.

## 7. Test Both Modes

Without Tamework:

1. Remove or disable Tamework.
2. Start the game or server.
3. Confirm the base role/template validates.
4. Spawn the NPC and verify its base behavior.

With Tamework:

1. Install Tamework and the source mod.
2. Start the server.
3. Run `/tw patches status`.
4. Spawn the NPC.
5. Verify the Tamework behavior.
6. Run `/tw patches reload`.
7. Spawn or use the patched target again and verify there are no duplicate interactions, missing builders, or stale item actions.
8. Run `/tw patches status` and check whether any generated target is listed as restart-required.

## Checklist

- The base template has no Tamework-only references.
- Patch files live under `Server/Tamework/Patches`.
- Operations that create parameters run before operations that use them.
- Operations that create states run before operations that reference them.
- Inserts use stable anchors instead of indexes.
- Inserts include `Existing` unless duplication is impossible.
- Runtime reload is tested after startup validation.
- Restart-required targets are documented for users or server operators.

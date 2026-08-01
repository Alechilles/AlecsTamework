---
title: "Asset Patch Workflow"
order: 15
published: true
draft: false
---
# Asset Patch Workflow

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Asset Patches](/mod/alecs-tamework/asset-patches)

The safest patchable asset is a normal base-game asset first and a Tamework asset second. Build the unpatched version so it validates and plays correctly without Tamework, then add optional patches that upgrade it when Tamework is installed.

The same rule applies to every optional asset patch target. Keep the base item, projectile, particle, drop table, entity effect, or Tamework config valid on its own, then let `Server/Patchwork/Patches` add Tamework-only fields when the framework is present.

## 1. Keep the Base Asset Valid

Do not place Tamework-only actions, sensors, motions, components, states, parameters, item actions, or config references directly in the base asset if the source mod should work without Tamework.

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
Server/Patchwork/Patches
```

Subdirectories are supported:

```text
Server/Patchwork/Patches/MyMod/Livestock/MyCow.json
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
Server/Patchwork/Patches/MyMod/Livestock/10_Common.json
Server/Patchwork/Patches/MyMod/Livestock/20_Interactions.json
Server/Patchwork/Patches/MyMod/Livestock/30_FollowCommands.json
```

Use `Priority` when order matters across files. Lower priority runs first.

## 7. Test Both Modes

Without Tamework:

1. Remove or disable Tamework.
2. Start the game or server.
3. Confirm the base role/template validates.
4. Spawn the NPC and verify its base behavior.

With Patchwork and Tamework:

1. Install Tamework and the source mod. Tamework already embeds Patchwork; a standalone Patchwork jar is optional.
2. Start the server.
3. Run `/patchwork status` and confirm the expected runtime and definition root are active.
4. Spawn the NPC.
5. Verify the Tamework behavior.
6. Edit a definition and run `/patchwork reload`.
7. Confirm the target is regenerated and reported as restart-required.
8. Restart the server, then spawn or use the patched target again and verify there are no duplicate interactions, missing builders, or stale item actions.

## Checklist

- The base asset has no Tamework-only references.
- Item, projectile, particle, drop, entity-effect, and config targets stay valid before patches run.
- Patch files live under `Server/Patchwork/Patches`. The legacy Tamework root is compatibility-only.
- Operations that create parameters run before operations that use them.
- Operations that create states run before operations that reference them.
- Inserts use stable anchors instead of indexes.
- Inserts include `Existing` unless duplication is impossible.
- Explicit regeneration and restart activation are tested after startup validation.
- Restart-required targets are documented for users or server operators.

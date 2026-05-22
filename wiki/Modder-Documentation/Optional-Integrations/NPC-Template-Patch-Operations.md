---
title: "NPC Template Patch Operations"
order: 13
published: true
draft: false
---
# NPC Template Patch Operations

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Optional Asset Patches](/mod/alecs-tamework/npc-template-patches)

Raw operations edit JSON directly with explicit paths and values. Use them for setup, custom branches, and anything that does not fit a macro.

```json
{
  "Id": "add-follow-flag",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "CanFollow": { "Value": true }
  }
}
```

## Paths

`Path` uses JSON pointer syntax:

- `/Parameters` means the `Parameters` object at the document root.
- `/Instructions/0` means the first entry in the `Instructions` array.
- `/Instructions/-` means the end of an array for `Add`.
- `~1` means `/` inside a path token.
- `~0` means `~` inside a path token.

Parent paths must already exist. Patches are meant to modify known template structure, not create an entire template from nothing.

## Required

Operations are required by default. A required operation failure stops the generated target from being published.

```json
{
  "Id": "remove-old-branch-if-present",
  "Op": "Remove",
  "Path": "/LegacyBehavior",
  "Required": false
}
```

Use `Required: false` only for compatibility branches where skipping is acceptable.

## Add

`Add` adds an object field or inserts into an array.

```json
{
  "Id": "add-tamework-marker",
  "Op": "Add",
  "Path": "/TameworkPatched",
  "Value": true
}
```

Append to an array:

```json
{
  "Id": "append-state",
  "Op": "Add",
  "Path": "/_ExportStates/-",
  "Value": "Follow"
}
```

## Merge

`Merge` deep-merges an object into an existing object.

```json
{
  "Id": "merge-command-params",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "MasterTargetSlot": {
      "Value": "MasterTarget"
    },
    "CanFollow": {
      "Value": true
    }
  }
}
```

Use `Merge` for parameters, nested settings, and small config fragments.

## Replace

`Replace` changes an existing field or array entry. The target must already exist.

```json
{
  "Id": "replace-start-state",
  "Op": "Replace",
  "Path": "/StartState",
  "Value": "Idle"
}
```

## Remove

`Remove` deletes an existing field or array entry.

```json
{
  "Id": "remove-obsolete-flag",
  "Op": "Remove",
  "Path": "/Obsolete"
}
```

Prefer anchor-based replacement patterns over index-based removes when possible. Indexes are fragile if the target template changes.

## Insert

`Insert` inserts a value into an array. It is the most important operation for NPC behavior patches because it can place a branch near a stable anchor.

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

`Position` can be `Start`, `End`, `Before`, or `After`. `Before` and `After` require `Find`.

## Find and Existing

`Find` is a partial object matcher:

```json
"Find": {
  "$Comment": "Patch anchor: command behaviors"
}
```

Nested fields are supported:

```json
"Find": {
  "Sensor": {
    "Type": "HasInteracted"
  }
}
```

Use `$Contains` to match inside an array:

```json
"Existing": {
  "Instructions": {
    "$Contains": {
      "Reference": "Component_Tamework_Instruction_Follow"
    }
  }
}
```

`Existing` makes an `Insert` idempotent. If the matcher is already present in the target array, the insert is skipped.

## Ordering

For one target:

1. Disabled patches are ignored.
2. Patches are sorted by `Priority`, lowest first.
3. Patches with the same priority are sorted by `Id`.
4. Operations run in the order listed.
5. Macro operations expand inline at their position.

This lets one operation create an anchor and a later operation use it.

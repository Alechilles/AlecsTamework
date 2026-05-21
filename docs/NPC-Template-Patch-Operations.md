# NPC Template Patch Raw Operations

Raw operations are the basic building blocks of template patching. They edit JSON directly with explicit paths and values.

Every operation has an `Id`, an `Op`, and usually a `Path`:

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

Parent paths must already exist. Patches are intended to modify known template structure, not invent entire templates from nothing.

## Required

Operations are required by default. A required operation failure stops the target from being published.

Use `Required: false` only for optional compatibility branches:

```json
{
  "Id": "remove-old-branch-if-present",
  "Op": "Remove",
  "Path": "/LegacyBehavior",
  "Required": false
}
```

If an optional operation fails, it is recorded as skipped instead of failing the generated target.

## Add

`Add` adds an object field or inserts into an array.

Add an object field:

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

Insert at an array index:

```json
{
  "Id": "insert-first-state",
  "Op": "Add",
  "Path": "/_ExportStates/0",
  "Value": "Hold"
}
```

Use `Add` when you know the destination field or array position. Use `Insert` when you want anchor-based placement.

## Merge

`Merge` deep-merges an object into an existing object.

```json
{
  "Id": "merge-command-params",
  "Op": "Merge",
  "Path": "/Parameters",
  "Value": {
    "MasterTargetSlot": {
      "Value": "MasterTarget",
      "Description": "Target slot used to store the owner."
    },
    "CanFollow": {
      "Value": true,
      "Description": "Whether Follow command behavior is enabled."
    }
  }
}
```

If both the existing value and incoming value are objects, their child fields are merged. Otherwise, the incoming value replaces the existing field.

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

Replace an array entry:

```json
{
  "Id": "replace-first-export-state",
  "Op": "Replace",
  "Path": "/_ExportStates/0",
  "Value": "Idle"
}
```

Use `Replace` when the original value is wrong for the patched version and should not be preserved.

## Remove

`Remove` deletes an existing field or array entry.

```json
{
  "Id": "remove-obsolete-flag",
  "Op": "Remove",
  "Path": "/Obsolete"
}
```

Optional remove:

```json
{
  "Id": "remove-old-follow-branch",
  "Op": "Remove",
  "Path": "/Instructions/3",
  "Required": false
}
```

Prefer anchor-based replacement patterns over index-based `Remove` when possible. Indexes are fragile if the target template changes.

## Insert

`Insert` inserts a value into an array. It is the most important operation for NPC behavior patches because it lets you place a branch near a stable anchor.

Insert after an anchor:

```json
{
  "Id": "add-follow-behavior",
  "Op": "Insert",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: command behaviors"
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

`Position` can be:

- `Start`: insert at the beginning of the array.
- `End`: insert at the end of the array. This is the default.
- `Before`: insert before the first matching `Find` object.
- `After`: insert after the first matching `Find` object.

## Find Matchers

`Find` is a partial object matcher. This finds an array entry with the same comment:

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
"Find": {
  "Instructions": {
    "$Contains": {
      "Reference": "Component_Tamework_Instruction_Follow"
    }
  }
}
```

## Existing Matchers

`Existing` makes an `Insert` idempotent. If the matcher is already present in the target array, the operation is skipped.

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

Use `Existing` for every insert that might be re-applied during testing or by multiple compatibility patches.

## Operation Ordering

For one target:

1. Disabled patches are ignored.
2. Patches are sorted by `Priority`, lowest first.
3. Patches with the same priority are sorted by `Id`.
4. Operations run in the order listed in each patch.
5. Macro operations expand into raw operations at their position in that sequence.

This means you can create anchors in one operation and use them in a later operation.

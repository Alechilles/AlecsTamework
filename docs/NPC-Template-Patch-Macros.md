# NPC Template Patch Macros

Macros are shortcuts for common Tamework behavior branches. They keep patch files readable, but they do not guess where behavior belongs.

A macro still needs the same placement information as an `Insert` operation:

- `Path`: the array to insert into.
- `Position`: `Start`, `End`, `Before`, or `After`.
- `Find`: the anchor used by `Before` or `After`.
- `Required`: whether missing placement should fail the target.
- `Options`: macro-specific settings.

```json
{
  "Id": "add-interactions",
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

Use raw operations when the behavior is unique. Use macros when the behavior follows one of the common Tamework patterns below.

## TameworkInteractionBridge

`TameworkInteractionBridge` wires a template to a `TwInteractionConfig`.

It inserts two interaction branches:

- A prompt branch with `TameworkInteractPrompt`.
- An interaction branch with `HasInteracted`, target locking, and `TameworkInteract`.

Patch operation:

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

Conceptually, the macro creates branches like this:

```json
{
  "Sensor": {
    "Type": "Any"
  },
  "Continue": true,
  "Actions": [
    {
      "Type": "TameworkInteractPrompt",
      "ConfigId": "TwIntMyCow"
    }
  ]
}
```

```json
{
  "Sensor": {
    "Type": "HasInteracted"
  },
  "Actions": [
    {
      "Type": "LockOnInteractionTarget",
      "TargetSlot": "InteractionTarget"
    },
    {
      "Type": "LockOnInteractionTarget",
      "TargetSlot": "MasterTarget"
    },
    {
      "Type": "TameworkInteract",
      "ConfigId": "TwIntMyCow"
    }
  ]
}
```

`ActionFields` are copied onto both Tamework interaction actions. Use this for fields such as `ConfigId` that both the prompt and final interaction should share.

The macro automatically adds idempotency checks for the generated Tamework action types, so a reload does not duplicate the branches.

## TameworkHookInstruction

`TameworkHookInstruction` inserts a branch gated by a `TameworkHook` sensor. Use it when another Tamework system queues behavior by hook id.

```json
{
  "Id": "add-fed-hook",
  "Op": "Macro",
  "Macro": "TameworkHookInstruction",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: behaviors"
  },
  "Options": {
    "HookId": "AfterFeed",
    "Consume": true,
    "Instructions": [
      {
        "Reference": "Component_Tamework_Instruction_Update_Happiness"
      }
    ]
  }
}
```

Conceptual output:

```json
{
  "Enabled": {
    "Compute": true
  },
  "Continue": true,
  "Sensor": {
    "Type": "TameworkHook",
    "HookId": "AfterFeed",
    "Consume": true
  },
  "Instructions": [
    {
      "Reference": "Component_Tamework_Instruction_Update_Happiness"
    }
  ]
}
```

`HookId` is required. `Consume` defaults to `true`. `Instructions` is optional, but a hook branch without instructions is usually only useful as a control-flow marker.

The macro skips insertion when a branch with the same `TameworkHook` sensor and `HookId` already exists.

## TameworkStateInstruction

`TameworkStateInstruction` inserts a branch that references a Tamework instruction component. Use it for compact state or command behavior wiring.

```json
{
  "Id": "add-follow-instruction",
  "Op": "Macro",
  "Macro": "TameworkStateInstruction",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: command behaviors"
  },
  "Options": {
    "Component": "Component_Tamework_Instruction_Follow",
    "Enabled": {
      "Compute": "CanFollow"
    }
  }
}
```

Conceptual output:

```json
{
  "Enabled": {
    "Compute": "CanFollow"
  },
  "Continue": true,
  "Instructions": [
    {
      "Component": "Component_Tamework_Instruction_Follow"
    }
  ]
}
```

`Component` is required. `Enabled` is optional and defaults to:

```json
{
  "Compute": true
}
```

You can also include a `Sensor` option when the branch should only run under a specific condition. The sensor object is copied directly into the generated branch, so it must be a valid sensor for the target template.

```json
{
  "Id": "add-conditional-follow-instruction",
  "Op": "Macro",
  "Macro": "TameworkStateInstruction",
  "Path": "/Instructions",
  "Position": "After",
  "Find": {
    "$Comment": "Patch anchor: command behaviors"
  },
  "Options": {
    "Component": "Component_Tamework_Instruction_Follow",
    "Enabled": {
      "Compute": "CanFollow"
    },
    "Sensor": {
      "Type": "Any"
    }
  }
}
```

The macro skips insertion when a branch already contains the same instruction component.

## Choosing a Macro

Use `TameworkInteractionBridge` for player interaction menus and command items backed by `TwInteractionConfig`.

Use `TameworkHookInstruction` for behavior triggered by Tamework systems that emit hook ids.

Use `TameworkStateInstruction` for one instruction component branch where the only custom pieces are `Enabled`, `Sensor`, and the component id.

Use raw `Insert` when the branch needs several sensors, actions, motions, state transitions, or custom ordering that does not match a macro.

## Common Macro Mistakes

Do not point a macro at an object. Macros insert branches into arrays, so `Path` must resolve to an existing array.

Do not omit `Find` when using `Position: "Before"` or `Position: "After"`. The macro cannot place itself without an anchor.

Do not rely on macros to create missing template sections. Add or merge the parent object or array first, then run the macro later in the same patch.

Do not put Tamework-only action or sensor references in the base template. Keep the base template valid without Tamework and add Tamework references only through patch files.

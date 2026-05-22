---
title: "NPC Template Patch Macros"
order: 14
published: true
draft: false
---
# NPC Template Patch Macros

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Optional Asset Patches](/mod/alecs-tamework/npc-template-patches)

Macros are shortcuts for common Tamework behavior branches. They keep patch files readable, but they still need explicit placement.

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

Macros support `Path`, `Position`, `Find`, `Required`, and `Options`.

## TameworkInteractionBridge

`TameworkInteractionBridge` wires a template to a `TwInteractionConfig`.

It inserts:

- A prompt branch with `TameworkInteractPrompt`.
- An interaction branch with `HasInteracted`, target locking, and `TameworkInteract`.

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

`ActionFields` are copied onto both Tamework interaction actions. The macro adds idempotency checks for the generated Tamework action types.

## TameworkHookInstruction

`TameworkHookInstruction` inserts a branch gated by a `TameworkHook` sensor.

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

`HookId` is required. `Consume` defaults to `true`.

## TameworkStateInstruction

`TameworkStateInstruction` inserts a branch that references one Tamework instruction component.

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

`Component` is required. `Enabled` is optional and defaults to `{ "Compute": true }`. `Sensor` is optional and is copied directly into the generated branch.

## When Not to Use a Macro

Use raw `Insert` when the branch needs several sensors, actions, motions, state transitions, or custom ordering that does not match a macro.

Do not put Tamework-only action or sensor references in the base template. Keep the base template valid without Tamework and add Tamework references only through patch files.

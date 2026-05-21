# NPC Template Patches

NPC template patches let another mod ship Tamework-powered NPC behavior without making Tamework a required dependency.

The base mod keeps its normal NPC role/template assets valid without Tamework. Optional patch files live under:

```text
Server/Tamework/Patches/**/*.json
```

When Tamework is not installed, those files are inert because the base game does not load Tamework patch assets. When Tamework is installed, it scans the patch files before NPC validation, applies them to their target JSON assets, writes generated patched copies, and loads those generated copies into the NPC builder manager.

Use this system when a mod should work in two modes:

- Without Tamework: the NPC uses only base-game-safe behavior.
- With Tamework: the NPC gains Tamework actions, sensors, states, command behavior, needs, breeding, or other integration behavior.

## Pages

- [Raw Operations](NPC-Template-Patch-Operations.md): `Add`, `Merge`, `Replace`, `Remove`, `Insert`, JSON paths, anchors, and idempotency.
- [Macros](NPC-Template-Patch-Macros.md): compact helpers for common Tamework instruction branches.
- [Authoring Workflow](NPC-Template-Patch-Workflow.md): how to design patchable templates and test them safely.
- [Troubleshooting](NPC-Template-Patch-Troubleshooting.md): common validation, reload, and spawn failures.

## Minimal Example

The target template should include stable anchors where optional behavior can be inserted:

```json
{
  "Type": "Abstract",
  "StartState": "Idle",
  "Parameters": {
    "MaxSpeed": { "Value": 4 }
  },
  "Instructions": [
    { "$Comment": "Patch anchor: behaviors" }
  ]
}
```

The patch targets that template and inserts Tamework behavior only when Tamework is present:

```json
{
  "Id": "MyCow_Tamework_Follow",
  "Target": "Server/NPC/Roles/_Core/Templates/MyCow.json",
  "Operations": [
    {
      "Id": "add-follow-flag",
      "Op": "Merge",
      "Path": "/Parameters",
      "Value": {
        "CanFollow": { "Value": true }
      }
    },
    {
      "Id": "add-follow-behavior",
      "Op": "Insert",
      "Path": "/Instructions",
      "Position": "After",
      "Find": { "$Comment": "Patch anchor: behaviors" },
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
  ]
}
```

## Patch File Shape

```json
{
  "Id": "MyMod_Livestock_Tamework",
  "Target": "Server/NPC/Roles/_Core/Templates/My_Template.json",
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

Fields:

- `Id`: stable patch id used in logs and `/tw patches status`.
- `Target`: target JSON asset path. Leading `/` is optional and normalized away.
- `Priority`: lower values apply first when multiple patches target the same file. Defaults to `0`.
- `Enabled`: optional switch. Defaults to `true`.
- `Operations`: ordered raw operations and macro operations.

## Runtime Behavior

On startup, Tamework scans loaded asset packs, groups patches by `Target`, applies enabled patches by `Priority` then `Id`, and publishes generated targets as a generated runtime pack.

At runtime, `/tw patches reload` rescans loaded packs and refreshes generated files in place. It does not unregister or delete the generated pack while the server is running, because doing so can cause the NPC builder manager or asset monitor to drop generated builders.

Use diagnostics commands while testing:

```text
/tw patches status
/tw patches reload
```

`status` prints the last patch run summary plus generated, failed, and skipped rows. `reload` is useful while editing patch files in a running dev server, but a restart is still the safest test when changing which generated targets exist.

## Bundled Fixture

Tamework includes a working fixture:

- Base template: `Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`
- Role: `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json`
- Patch: `Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`
- Interaction config: `Server/Tamework/Interactions/TwIntExamplePatch.json`

The base template intentionally avoids Tamework builders. The patch adds the Tamework parameters, states, transitions, interactions, command behavior, needs behavior, and breeding-pair movement.

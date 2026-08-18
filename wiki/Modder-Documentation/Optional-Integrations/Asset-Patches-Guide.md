---
title: "Asset Patches"
slug: "asset-patches"
order: 12
published: true
draft: false
---
# Asset Patches

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Tamework includes Patchwork 1.3.3 for non-destructive JSON asset patches. Patchwork is standalone and embeddable: the highest compatible runtime version wins, and standalone wins only an equal-version tie. Passive copies forward their host contributions to the elected runtime.

Asset-only packs need no Java integration. Declare Patchwork as a dependency and put definitions under:

```text
Server/Patchwork/Patches/**/*.json
```

Declare Tamework too when a definition uses Tamework behavior or one of its macros. Patchwork also reads the legacy `Server/Tamework/Patches/**/*.json` root while Tamework is installed. New work should use the neutral root; a matching enabled neutral definition shadows its legacy copy within the same source pack.

Patchwork runs before server JSON validation and writes patched copies to its generated pack without modifying source assets.

## Pages

- [Asset Patch Operations](/mod/alecs-tamework/asset-patch-operations): `Add`, `Merge`, `Replace`, `Remove`, `Insert`, JSON pointers, anchors, and idempotency.
- [Asset Patch Macros](/mod/alecs-tamework/asset-patch-macros): Tamework's three optional host macros.
- [Asset Patch Workflow](/mod/alecs-tamework/asset-patch-workflow): designing patchable assets and testing them safely.
- [Asset Patch Troubleshooting](/mod/alecs-tamework/asset-patch-troubleshooting): election, discovery, validation, generation, and restart-required behavior.

## Patch Shape

Use `Target` for one asset or `Targets` for several:

```json
{
  "Id": "MyMod_Livestock_Tamework",
  "Targets": [
    "Server/NPC/Roles/_Core/Templates/MyCow.json",
    "Server/NPC/Roles/_Core/Templates/MySheep.json"
  ],
  "Priority": 0,
  "Enabled": true,
  "Operations": []
}
```

`Target` and `Targets` are mutually exclusive. Lower priorities apply first. Operations support `Add`, `Merge`, `Replace`, `Remove`, `Insert`, and host-contributed macros.

## Conditions

`When` can check installed mods, mod or server versions, another asset, the current target, a JSON pointer in the target or another asset, or JSON under a registered Java mod data root. Conditions compose with `All`, `Any`, and `Not`.

```json
{
  "Id": "MyMod_Conditional_Bridge",
  "Target": "Server/NPC/Roles/_Core/Templates/MyCow.json",
  "When": {
    "All": [
      { "ModInstalled": "alec:animal_husbandry" },
      {
        "JsonPathEquals": {
          "Source": {
            "Type": "ModData",
            "Mod": "Example:Mod",
            "Path": "settings/config.json"
          },
          "Path": "/features/myFeature",
          "Value": true
        }
      }
    ]
  },
  "Operations": []
}
```

Mod-data paths remain below a registered data root. `TameworkSetting` is retired; use `JsonPathEquals` with a `ModData` source instead.

## Tamework Macros

- `TameworkInteractionBridge` inserts `TameworkInteractPrompt` and `TameworkInteract` branches.
- `TameworkHookInstruction` inserts a `TameworkHook` sensor branch.
- `TameworkStateInstruction` inserts a branch referencing a Tamework instruction component.

Macros require Tamework to be installed and still need explicit paths and anchors. The optional `Alec's Tamework! Examples` pack uses
`Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`,
`Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example_Patch.json`, and
`Server/Patchwork/Patches/Examples/Tamework_Example_Patch.json`.

## Administration and Reloads

Patchwork generates during early startup asset load, after an authorized `/patchwork reload`, and after relevant edits in a directory asset pack. It waits briefly so a small edit burst becomes one regeneration pass. Archive-pack and unregistered mod-data changes still need a manual reload or restart.

```text
/patchwork status
/patchwork reload
/patchwork selftest
```

Commands require `patchwork.admin` and default to `hytale:Admin`. Status reports runtime election, contributions, roots, generation results, integrity, and per-target outcomes.

Patchwork 1.3.3 can confirm a live reload for monitored Hytale server stores when Hytale reports the expected generated provider and asset path. Tamework contributes no separate host adapter. Common, custom, unknown, disabled-monitor, or unconfirmed routes remain `restart-required`; restart the server to activate those changes. `/patchwork selftest` validates isolated generation and conditions without modifying the production pack.

Generated pack ID: `Alechilles:Patchwork_GeneratedPatches`

Default root: `<server-or-save-root>/mods/Alechilles_Patchwork/GeneratedPatches`

Do not edit generated output by hand or place definitions inside the generated root.

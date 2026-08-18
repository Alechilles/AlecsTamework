---
title: "Setup and Quick Start"
order: 2
published: true
draft: false
---
# Setup and Quick Start

Parent: [Start Here](/mod/alecs-tamework/start-here) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Use this page when you want to add Alec's Tamework as a dependency and get a basic interactive NPC working quickly.

## 1. Add the dependency
Add Tamework in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "*"
},
"IncludesAssetPack": true
```

## 2. Pick your interaction path
- Use the optimized path for most content: `TameworkInteract` plus `TwInteractionConfig`
- Use the vanilla path when you need full manual control and are willing to author more JSON

## 3. Install or copy optional examples
The main Tamework jar does not include sample NPCs, items, or progression
configs. Build or install the separate `Alec's Tamework! Examples`
asset pack when you need a starting point. It declares Tamework as a
dependency and is disabled by default. Explicitly enable it before using the
sample assets; production servers that do not use the samples can omit it.

After installation, copy and adapt these files:
- Template: `examples/asset-pack/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json`
- Simple template: `examples/asset-pack/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json`
- Matching role example: `examples/asset-pack/Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json`
- Optional patch fixture: `examples/asset-pack/Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json` + `examples/asset-pack/Server/Patchwork/Patches/Examples/Tamework_Example_Patch.json`
- Example configs: `examples/asset-pack/Server/Tamework/...`

## 4. Add an interaction config
Create a `TwInteractionConfig` under:
`<ModRoot>/Server/Tamework/Interactions/`

Wire the action into your template:

```json
"InteractionInstruction": {
  "Actions": [
    {
      "Type": "LockOnInteractionTarget",
      "TargetSlot": { "Compute": "MasterTargetSlot" }
    },
    { "Type": "TameworkInteract" }
  ]
}
```

Optional prompt updater:

```json
{
  "Continue": true,
  "Sensor": { "Type": "Any" },
  "Actions": [ { "Type": "TameworkInteractPrompt" } ]
}
```

## 5. Add optional item systems
- Spawner items: create `TwSpawnerConfig` and wire `TameworkSpawn`
- Naming items: create `TwNameItemConfig` and wire `TameworkNameNpc`
- Name pools: create `TwNamesConfig` and point naming items at `Naming.RandomNamesId`
- Command items: create `TwCommandItemConfig` and wire `TameworkCommand`
- Configured coop capture/release: create `TwCoopConfig` for the target
  `CoopId`

## 6. Add translations
Add prompt, item, and UI keys in:
`Server/Languages/en-US/server.lang`

## 7. Reload or test
- `/tw reloadconfig` reloads spawner, naming, and command item registries
- Other config families update through the normal asset registry load and remove flow

## Notes
- Tamework ships as a jar with embedded framework `Common/` and `Server/`
  assets. The example graph is a separate opt-in asset pack.
- Runtime ordering keeps Tamework early in the load pass and removes legacy standalone `Alec's Tamework! (Assets)` packs if detected.

## Related Pages
- [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring)
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)




<img width="400" height="400" alt="Alec&#39;sTamework400Transparent" src="https://github.com/user-attachments/assets/251cbac2-26ea-4daf-b552-30594e96f8da" />

# Alec's Tamework!
A modular taming framework for Hytale that focuses on **fast setup** and **clean long‑term maintenance**. Add ownership, taming, capture/spawn, and rich NPC interactions without 600+ lines of JSON.

## What the Tamework Offers
- **Optimized interactions** via `TwInteractionConfig` assets: clear requirements + effects, ordered matching, cooldowns, and role‑based overrides.
  - Set up complex interaction chains in ~20 lines of JSON that would take over 600 with vanilla `InteractionInstructions`.
- **Prompted interactions** with `TameworkInteractPrompt` and translation keys (fully customizable or hidden per entry).
  - System automatically decides what prompt to show the user based on interaction requirements.
- **Parameter‑driven configs** so one asset can power many NPC variants.
- **Ownership + tamed state** stored on NPCs and persisted through reloads.
- **Spawner items** that capture and spawn NPCs while preserving attachments/variants.
- **Hooks and bridges** that let interaction effects trigger your own instruction chains.
- **Polished examples** (simple and full templates, testbeds, and spawner samples).

The goal of Tamework is to make it easier for modders to turn their NPCs into interesting, highly interactive companions.

## Quick Start (2.0.0)
1. Add the dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "2.0.0"
},
"IncludesAssetPack": true
```

Asset pack load order note: Hytale loads asset packs alphabetically by folder name, so your mod folder must come after `.Alec's Tamework!`. The `manifest.json` name does not affect load order for now, but hopefully it will in the near future.

2. Choose your interaction path:
   Tamework's optimized system (`TameworkInteract` + `TwInteractionConfig`) or vanilla instruction flow (full control but a lot more work).
   This quick-start guide will use `TameworkInteract`, but you can find more information on the components for use with the [vanilla flow here](https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla).

3. Copy a template:
   `Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json` or `Template_Tamework_Example_Simple.json`

4. Copy a matching NPC role and tweak values:
   `Server/NPC/Roles/Creature/Mammal/Mob_Tamework_Example.json` (or the simple variant)

5. Create a `TwInteractionConfig` under:
   `<ModRoot>/Server/Tamework/Interactions/`

6. Wire the interaction instruction in your template:

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

Optional prompt updater (see the example template for full usage):

```json
{
  "Continue": true,
  "Sensor": { "Type": "Any" },
  "Actions": [ { "Type": "TameworkInteractPrompt" } ]
}
```

7. Spawner items (optional):
   Create a `TwSpawnerConfig` asset under `<ModRoot>/Server/Tamework/Items/Spawners/` and wire your item with `TameworkSpawn`.

8. Add translations in `Server/Languages/en-US/server.lang`.

## Configuration Overview
- **TwGlobalConfig**: default parameter names + interaction defaults.
  Location: `<ModRoot>/Server/Tamework/Global/*.json`
- **TwSpawnerConfig**: spawner capture/spawn behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- After editing spawner configs, use `/tw reloadconfig`.

## Documentation (Wiki)
- Home: https://github.com/Alechilles/AlecsTamework/wiki
- Quick‑Start: https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start
- Interactions (Optimized): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Optimized
- Interactions (Vanilla): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla
- Items: https://github.com/Alechilles/AlecsTamework/wiki/Items
- Spawner Config (Assets): https://github.com/Alechilles/AlecsTamework/wiki/Item-Config
- Actions and Sensors: https://github.com/Alechilles/AlecsTamework/wiki/Actions-and-Sensors
- Hooks and Bridges: https://github.com/Alechilles/AlecsTamework/wiki/Hooks-and-Bridges
- Templates: https://github.com/Alechilles/AlecsTamework/wiki/Templates
- Components: https://github.com/Alechilles/AlecsTamework/wiki/Components
- Troubleshooting: https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting

## Issue Reporting
If you run into a bug or behavior issue, please submit a report:
https://github.com/Alechilles/AlecsTamework/issues

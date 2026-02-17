<img width="400" height="400" alt="Alec&#39;sTamework400Transparent" src="https://github.com/user-attachments/assets/251cbac2-26ea-4daf-b552-30594e96f8da" />

# Alec's Tamework!
A modular taming framework for Hytale that focuses on fast, easy setup and empowering moders to turn custom NPCs into interesting, highly interactive companions. Add ownership, taming, capture/spawn, and rich NPC interactions without 600+ lines of JSON in every NPC template.

## Core Features
- **Interaction System** - Use `TwInteractionConfig` assets to create NPC interactions quickly and easily
  - Full asset editor GUI support unlike the base game instructions system
  - Requires ~10% as much JSON as the base InteractionInstrucitons system for a vast majority of cases
  - Interaction prompts (Ex: "Press F to...") update automatically based on interaction requirements
  - Can pass in parameters so the same instructions can be used for many different NPCs
  - `TameworkHook` sensor to allow triggering actions in the vanilla system for when you need the most granular control
- **Ownership and Tamed Components** - Enforce ownership and tamed status of NPCs with extremely simple actions
  - Sensors for ownership and tamed status
  - Allow/disallow players to interact with or harm NPCs that do or do not belong to them (100% configurable, globally and per NPC)
- **Capture/Spawn NPCs With Metadata** - Capture system saves metadata on capture and replicates the exact same mob on respawn
  - Includes random attachments such as varying textures, models, etc.
  - Also includes components like tamed status and ownership
    - Ownership can optionally be cleared on capture and re-set on spawn to enable player trading of captured NPCs
  - This will be expanded in the near future as new systems such as breeding, traits, talents, etc. are added. Everything will always be saved on capture.
- **Naming Items** - Name tamed NPCs via chat using `TwNameItemConfig` + `TameworkNameNpc`
  - Works with any custom item; not a fixed nametag item
  - Per-item rules for roles, ownership, allowed characters, rename rules, and more
- **Examples and Documentation** - Plenty of examples and thorough documenation to help you get started integrating Tamework
  - [Check out the wiki here](https://github.com/Alechilles/AlecsTamework/wiki)

## Coming Soon
- **Command Flute/Whistle/Etc**
  - Technically any item, it will be an action that can be called by any item you want
  - Remotely command multiple mobs to change modes, attack a target, and more
- **Needs System**
  - Hunger, thirst, happiness, space etc.
- **Breeding**
  - Passive happiness-based system *and* simpler more Minecraft-like breed on interact system
- **Trait system**
  - Highly integrated with breeding
  - Size, attachments (so colors, models, etc), harvest rates, strength, health, etc.
- **NPC XP/leveling system**
  - Gain XP passively when with owner or when doing certain configurable actions
- **Talent trees**
  - Create your own talent trees for your NPCs
  - Allow unlocking new behaviors, stat increases, etc.
  - Will include a talent tree UI
- **Atittude group override system**
  - Vanilla attitude groups can't be changed at runtime, but this will be a parallel system that we will have more freedom to work with

## Quick Start (2.0.0)
1. Add the dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "2.0.0"
},
"IncludesAssetPack": true
```

**Asset pack load order note:** Hytale loads asset packs alphabetically by folder name, so your mod folder must come after `.Alec's Tamework!`. The `manifest.json` name does not affect load order for now, but it's meant to, so just keep in the habit of doing that for the future when Hypixel fixes it in the near future.

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

8. Naming items (optional):
   Create a `TwNameItemConfig` asset under `<ModRoot>/Server/Tamework/Items/Naming/` and add `TameworkNameNpc` to your item’s `Interactions`.
   After editing spawner or naming configs, use `/tw reloadconfig`.

9. Add translations in `Server/Languages/en-US/server.lang`.

## Configuration Overview
- **TwGlobalConfig**: default parameter names + interaction defaults.
  Location: `<ModRoot>/Server/Tamework/Global/*.json`
- **TwSpawnerConfig**: spawner capture/spawn behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- **TwNameItemConfig**: naming item behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- After editing spawner or naming configs, use `/tw reloadconfig`.

## Documentation (Wiki)
- Home: https://github.com/Alechilles/AlecsTamework/wiki
- Quick‑Start: https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start
- Interactions (Optimized): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Optimized
- Interactions (Vanilla): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla
- Items: https://github.com/Alechilles/AlecsTamework/wiki/Items
- Spawner Config (Assets): https://github.com/Alechilles/AlecsTamework/wiki/Item-Config
- Naming Items: https://github.com/Alechilles/AlecsTamework/wiki/Naming-Items
- Actions and Sensors: https://github.com/Alechilles/AlecsTamework/wiki/Actions-and-Sensors
- Hooks and Bridges: https://github.com/Alechilles/AlecsTamework/wiki/Hooks-and-Bridges
- Templates: https://github.com/Alechilles/AlecsTamework/wiki/Templates
- Components: https://github.com/Alechilles/AlecsTamework/wiki/Components
- Troubleshooting: https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting

## Issue Reporting
If you run into a bug or behavior issue, please submit a report:
https://github.com/Alechilles/AlecsTamework/issues

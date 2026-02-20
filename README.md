[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1447962?style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-tamework)
[![Discord](https://img.shields.io/discord/1468261809739005996?style=for-the-badge&logo=discord&logoColor=%23ffffff&logoSize=auto&label=Alec%27s%20Mods%20Discord&color=%235865F2)](https://discord.gg/E8n8RgTTdq)
[![GitHub Repo stars](https://img.shields.io/github/stars/Alechilles/AlecsCats?style=for-the-badge&logo=github&label=GitHub)](https://github.com/Alechilles/AlecsTamework)

# Alec's Tamework!
A modular taming framework for Hytale that focuses on fast setup and giving modders reusable systems for companion NPCs. Add ownership, taming, capture/spawn, command tools, and rich NPC interactions without huge custom instruction trees in every role.

## Core Features
- **Interaction System** - Use `TwInteractionConfig` assets to create NPC interactions quickly and easily.
  - Full asset-editor GUI support unlike large vanilla instruction chains.
  - Requires far less JSON than the base interaction flow in most cases.
  - Interaction prompts update automatically from requirements.
  - Role parameters let one interaction config drive many NPC roles.
  - `TriggerNpcHook` + `TameworkHook` bridges optimized interactions into custom instruction logic.
- **Ownership and Tamed Components** - Enforce ownership and tamed status with simple actions/sensors.
  - Sensors for ownership and tamed status.
  - Global and per-role behavior controls for who can interact with or damage owned NPCs.
- **Capture/Spawn NPCs With Metadata** - Capture saves metadata and restores the same NPC on spawn.
  - Includes random attachments such as varying textures, models, etc.
  - Also includes components like tamed status and ownership
    - Ownership can optionally be cleared on capture and re-set on spawn to enable player trading of captured NPCs
  - This will be expanded in the near future as new systems such as breeding, traits, talents, etc. are added. Everything will always be saved on capture.
- **Naming Items** - Name tamed NPCs via chat using `TwNameItemConfig` + `TameworkNameNpc`.
  - Works with any custom item; not a fixed nametag item
  - Per-item rules for roles, ownership, allowed characters, rename rules, and more
- **Command Item System** - Build custom command tools with `TwCommandItemConfig` + `TameworkCommand`.
  - Link/unlink NPCs to each tool.
  - Left-click executes the selected command; right-click opens the radial command wheel.
  - Supports command steps like state changes, target assignment, move-to-ping, set/return-home, and hook triggers.
  - Includes off-screen command queueing + chunk preload retries for recall/return-home relocation.
  - Command relocation tuning is configurable in `TwGlobalConfig`.
- **Examples and Documentation** - Plenty of examples and thorough documentation to help you integrate Tamework.
  - [Check out the wiki here](https://github.com/Alechilles/AlecsTamework/wiki)

## Coming Soon
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
- **Attitude group override system**
  - Vanilla attitude groups can't be changed at runtime, but this will be a parallel system that we will have more freedom to work with

## Quick Start (2.1.x)
1. Add the dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "2.1.0"
},
"IncludesAssetPack": true
```

**Asset pack note:** Tamework ships as a jar with embedded `Common/` + `Server/` assets. At load time, Tamework also enforces early asset-pack ordering and removes legacy standalone `Alec's Tamework! (Assets)` packs/archives when detected.

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

9. Command items (optional):
   Create a `TwCommandItemConfig` asset under `<ModRoot>/Server/Tamework/Items/Commands/` and add `TameworkCommand` interactions to your tool item:

```json
"Interactions": {
  "Primary": {
    "Interactions": [ { "Type": "TameworkCommand" } ]
  },
  "Secondary": {
    "Interactions": [ { "Type": "TameworkCommand", "CommandId": "OpenSelectionMenu" } ]
  }
}
```

10. After editing spawner, naming, or command item configs, use `/tw reloadconfig`.

11. Add translations in `Server/Languages/en-US/server.lang`.

## Configuration Overview
- **TwGlobalConfig**: default parameter names + interaction defaults.
  Location: `<ModRoot>/Server/Tamework/Global/*.json`
- **TwSpawnerConfig**: spawner capture/spawn behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Spawners/*.json`
- **TwNameItemConfig**: naming item behavior.
  Location: `<ModRoot>/Server/Tamework/Items/Naming/*.json`
- **TwCommandItemConfig**: command-tool behavior and command list.
  Location: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- After editing spawner, naming, or command item configs, use `/tw reloadconfig`.

## Documentation (Wiki)
- Home: https://github.com/Alechilles/AlecsTamework/wiki
- Quick‑Start: https://github.com/Alechilles/AlecsTamework/wiki/Quick-Start
- Interactions (Optimized): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Optimized
- Interactions (Vanilla): https://github.com/Alechilles/AlecsTamework/wiki/Interactions-Vanilla
- Items: https://github.com/Alechilles/AlecsTamework/wiki/Items
- Spawner Config (Assets): https://github.com/Alechilles/AlecsTamework/wiki/Item-Config
- Naming Items: https://github.com/Alechilles/AlecsTamework/wiki/Naming-Items
- Command Items: https://github.com/Alechilles/AlecsTamework/wiki/Command-Items
- Actions and Sensors: https://github.com/Alechilles/AlecsTamework/wiki/Actions-and-Sensors
- Hooks and Bridges: https://github.com/Alechilles/AlecsTamework/wiki/Hooks-and-Bridges
- Templates: https://github.com/Alechilles/AlecsTamework/wiki/Templates
- Components: https://github.com/Alechilles/AlecsTamework/wiki/Components
- Troubleshooting: https://github.com/Alechilles/AlecsTamework/wiki/Troubleshooting

## Issue Reporting
If you run into a bug or behavior issue, please submit a report:
https://github.com/Alechilles/AlecsTamework/issues

[![Tamework](https://img.shields.io/curseforge/dt/1447962?label=Tamework&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-tamework)
[![Cats](https://img.shields.io/curseforge/dt/1432112?label=Cats&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-cats)
[![Nametags](https://img.shields.io/curseforge/dt/1464844?label=Nametags&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-nametags)
[![Animal Husbandry](https://img.shields.io/curseforge/dt/1480275?label=Animal%20Husbandry&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry)

[![Discord](https://img.shields.io/discord/1468261809739005996?style=for-the-badge&logo=discord&logoColor=white&label=Join%20Discord&color=rgb(88,101,242))](https://discord.gg/E8n8RgTTdq)
[![Buy me a coffee](https://img.shields.io/badge/ko--fi-Support%20Me-ff5f5f?logo=ko-fi&style=for-the-badge)](https://ko-fi.com/alechilles) [![Creator Code](https://img.shields.io/badge/Creator%20Code-Alec-00AEEF?style=for-the-badge)](https://hytale.com/)

[![Sponsored By HytaleModding Grant Program](https://github.com/user-attachments/assets/a03709e3-445a-4e58-8ec5-591688490c5d)](https://hytalemodding.dev/en/grants)

## Now Includes a Universal, Non-Destructive Asset Patcher!
- Add, merge, and insert JSON into *any* Hytale asset at runtime
- Supports hot-reloading
- Inspired by Hytalor
- [Learn More](https://wiki.hytalemodding.dev/mod/alecs-tamework/npc-template-patches)

# Alec's Tamework!
Alec's Tamework is a modular taming framework for Hytale built to let modders add rich NPC features through assets, templates, and config-driven systems instead of writing custom Java code. It is designed to empower artists, designers, and less technical modders who want advanced companion behavior without first building their own framework.

Tamework also aims to establish a shared standard for tameable NPCs in Hytale so different mods can work on top of similar ownership, naming, command, progression, and companion-management systems instead of forcing players to learn a different workflow for every mod.

## This Is a Library
Tamework is a framework dependency for other mods. It does not add a standalone gameplay expansion by itself.

If you are a player looking for gameplay built on Tamework, start with [Alec's Animal Husbandry](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry) or the wiki for the specific mod you are using.

## Why Tamework
- **No Java required for most integrations**: the main integration path is built around JSON assets, templates, role wiring, and `Tw*Config` files rather than custom Java systems.
- **Non-destructive asset patching**: Add, merge, and insert JSON into *any* Hytale asset at runtime with hot-reload support.
- **A shared standard for tameable NPCs**: mods built on Tamework can present familiar ownership, naming, command, linked-panel, breeding, and progression behavior instead of inventing incompatible one-off systems.
- **Optimized interactions**: build taming, feeding, mounting, harvesting, breeding, and custom interactions with `TwInteractionConfig` and `TameworkInteract`.
- **Ownership and tame-state systems**: use reusable builders and role-scoped policy for owner checks, protection rules, and companion behavior.
- **Spawner, naming, and command items**: capture and respawn NPCs with metadata, name companions with custom items, and build command tools with radial and linked-panel support.
- **Linked companion runtime**: manage loaded, unloaded, dead, and lost companions through a linked panel with recall, home, revive, and related flows.
- **Progression systems**: add happiness, needs, breeding, life stage, and trait-driven variation through config-driven systems.
- **Advanced extension points when needed**: bridge into custom logic through hooks and optional integrations without giving up the higher-level framework.

## What Integration Looks Like
Integrating Tamework is usually a content-authoring workflow, not a programming workflow. Mods can use it in two ways:

### Required Dependency
- Wire your desired Tamework behavior components directly into your NPC templates.
- Add configs for the Tamework systems you want to support. 
- Add Alec's Tamework as a required dependency when deploying to CurseForge.

### Optional Dependency
- Keep your base assets clean of any references to Tamework functionality.
- Create asset patches under `Server/Tamework/Patches` that add Tamework actions, interactions, etc. to your NPC templates at runtime only when Tamework is installed. 
- Add configs for the Tamework systems you want to support.
- Add Alec's Tamework as an optional dependency when deploying to CurseForge.

In both cases, no Java is required: copy and adapt examples, enable the systems you want through comprehensive configs, and polish. The full setup and implementation details live can be found in the wiki.

## Documentation
- [Wiki Home](https://wiki.hytalemodding.dev/mod/alecs-tamework)
- [Player Guides](https://wiki.hytalemodding.dev/mod/alecs-tamework/player-guides)
- [Modder Documentation](https://wiki.hytalemodding.dev/mod/alecs-tamework/modder-documentation)
- [Developer Documentation](https://wiki.hytalemodding.dev/mod/alecs-tamework/developer-documentation)

## Roadmap
- [Tamework Roadmap](https://curious-bench-850.notion.site/32c1f4061f368026b735f19a8187a480?v=32c1f4061f3680ab877b000cdda43a23)

## Issue Reporting
If you run into a bug, integration issue, or behavior problem, report it in the Discord server:

https://discord.gg/E8n8RgTTdq



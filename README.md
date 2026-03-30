[![Cats](https://img.shields.io/curseforge/dt/1432112?label=Cats&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-cats)
[![Tamework](https://img.shields.io/curseforge/dt/1447962?label=Tamework&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-tamework)
[![Nametags](https://img.shields.io/curseforge/dt/1464844?label=Nametags&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-nametags)
[![Animal Husbandry](https://img.shields.io/curseforge/dt/1480275?label=Animal%20Husbandry&style=for-the-badge&logo=curseforge&color=rgb(241%2C100%2C54))](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry)

[![Discord](https://img.shields.io/discord/1468261809739005996?style=for-the-badge&logo=discord&logoColor=white&label=Join%20Discord&color=rgb(88,101,242))](https://discord.gg/E8n8RgTTdq)
[![Buy me a coffee](https://img.shields.io/badge/ko--fi-Support%20Me-ff5f5f?logo=ko-fi&style=for-the-badge)](https://ko-fi.com/alechilles)

[![Creator Code](https://img.shields.io/badge/Creator%20Code-Alec-00AEEF?style=for-the-badge)](https://hytale.com/)

# Alec's Tamework!
Alec's Tamework is a modular taming framework for Hytale built to let modders add rich NPC features through assets, templates, and config-driven systems instead of writing custom Java code. It is designed to empower artists, designers, and less technical modders who want advanced companion behavior without first building their own framework.

Tamework also aims to establish a shared standard for tameable NPCs in Hytale so different mods can work on top of similar ownership, naming, command, progression, and companion-management systems instead of forcing players to learn a different workflow for every mod.

## This Is a Library
Tamework is a framework dependency for other mods. It does not add a standalone gameplay expansion by itself.

If you are a player looking for gameplay built on Tamework, start with [Alec's Animal Husbandry](https://www.curseforge.com/hytale/mods/alecs-animal-husbandry) or the wiki for the specific mod you are using.

## Why Tamework
- **No Java required for most integrations**: the main integration path is built around JSON assets, templates, role wiring, and `Tw*Config` files rather than custom Java systems.
- **A shared standard for tameable NPCs**: mods built on Tamework can present familiar ownership, naming, command, linked-panel, breeding, and progression behavior instead of inventing incompatible one-off systems.
- **Optimized interactions**: build taming, feeding, mounting, harvesting, breeding, and custom interactions with `TwInteractionConfig` and `TameworkInteract`.
- **Ownership and tame-state systems**: use reusable builders and role-scoped policy for owner checks, protection rules, and companion behavior.
- **Spawner, naming, and command items**: capture and respawn NPCs with metadata, name companions with custom items, and build command tools with radial and linked-panel support.
- **Linked companion runtime**: manage loaded, unloaded, dead, and lost companions through a linked panel with recall, home, revive, and related flows.
- **Progression systems**: add happiness, needs, breeding, life stage, and trait-driven variation through config-driven systems.
- **Advanced extension points when needed**: bridge into custom logic through hooks and optional integrations without giving up the higher-level framework.

## What Integration Looks Like
Integrating Tamework is usually a content-authoring workflow, not a programming workflow. In practice, modders typically:

1. Add Tamework as a dependency and include its asset pack.
2. Copy and adapt example templates, roles, items, and config assets.
3. Wire Tamework builders and interactions into their NPC roles and item assets.
4. Enable the systems they want through `TwInteractionConfig`, companion policy, item configs, and progression configs.
5. Add prompts, translations, and polish, then test the resulting NPC behavior in-game.

For many mods, that work can stay entirely in JSON and asset authoring. The full setup and implementation details live in the modder wiki.

## Documentation
- [Wiki Home](https://wiki.hytalemodding.dev/mod/alecs-tamework)
- [Player Guides Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/player-guides-index)
- [Getting Started Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/getting-started-index)
- [Companion Controls Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/companion-controls-index)
- [Systems Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/systems-index)
- [Troubleshooting and Glossary Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/troubleshooting-and-glossary-index)
- [Modder Documentation Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/modder-documentation-index)
- [Start Here Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/start-here-index)
- [Public API Overview](https://wiki.hytalemodding.dev/mod/alecs-tamework/public-api-overview)
- [Public API Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/public-api-index)
- [API Recipes Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/api-recipes-index)
- [System Integration Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/system-integration-index)
- [Config Reference Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/config-reference-index)
- [Testing and Diagnostics Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/testing-and-diagnostics-index)
- [Optional Integrations Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/optional-integrations-index)
- [Developer Documentation Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/developer-documentation-index)
- [Core Architecture Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/core-architecture-index)
- [Runtime Subsystems Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/runtime-subsystems-index)
- [Data and Persistence Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/data-and-persistence-index)
- [Tooling and Contribution Index](https://wiki.hytalemodding.dev/mod/alecs-tamework/tooling-and-contribution-index)

## For Contributors
- [Source Repository](https://github.com/Alechilles/AlecsTamework)
- [Contributing Guide](https://github.com/Alechilles/AlecsTamework/blob/main/CONTRIBUTING.md)
- [Architecture Doc](https://github.com/Alechilles/AlecsTamework/blob/main/docs/Architecture.md)
- [Developer Documentation](https://wiki.hytalemodding.dev/mod/alecs-tamework/developer-documentation-index)

## Roadmap
- [Tamework Roadmap](https://curious-bench-850.notion.site/32c1f4061f368026b735f19a8187a480?v=32c1f4061f3680ab877b000cdda43a23)

## Issue Reporting
If you run into a bug, integration issue, or behavior problem, report it in the Discord server:

https://discord.gg/E8n8RgTTdq

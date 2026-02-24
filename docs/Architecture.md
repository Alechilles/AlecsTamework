# Architecture Overview

This document is a high-level map of how Alec's Tamework is organized and where to make changes safely.

## Core concepts
- Tamework is a framework mod that supplies reusable NPC actions, sensors, components, and asset driven configuration that other mods can reference.
- Two layers: an asset layer (NPC templates, items, particles, interaction configs) and a plugin layer (components, actions, sensors, runtime behavior).
- Asset driven configuration is preferred so other mods can override behavior without patching Java.
- Runtime systems are organized as orchestrators plus focused services (selection, validation, persistence, relocation, UI view-models, feedback).

## Major subsystems
- NPC actions and sensors
- Optimized interactions pipeline (TwInteractionConfig + Action_Tamework_Interact)
- Hook and instruction bridge (TriggerNpcHook effect + TameworkHook sensor)
- Companion progression (TwHappinessConfig/TwBreedingConfig/TwTraitConfig + bootstrap + persistence bridges)
- Spawner items (TwSpawnerConfig + TameworkSpawn + SpawnerFeatureHandler orchestrating spawner services)
- Naming items (TwNameItemConfig + TameworkNameNpc + NamingFeatureHandler orchestrating naming services)
- Command items (TwCommandItemConfig + TameworkCommand + CommandItemFeatureHandler orchestrating command services)
- Command relocation pipeline (CommandNpcRelocationService + CommandNpcRelocationOnLoadSystem)
- Linked companions panel runtime (TameworkCommandSelectionPage + UI helper services + per-row action routing)
- Ownership and taming (components, owner interaction blocking, damage filters)
- Localization and messages (translation discovery + owner denial messages)
- Asset-pack ordering and legacy-pack replacement at early `LoadAssetEvent`

## Key behaviors
- Action_Tamework_Interact resolves a TwInteractionConfig and executes the first matching interaction entry.
- The interaction pipeline is split into resolution, selection, and execution helpers to isolate matching, cooldowns, and effects.
- TwInteractionConfig supports preset interactions (Tame, Feed, Harvest, Mount, ModeCycle, Breed) plus fully custom requirements and effects.
- Shared happiness progression is stored in `TameworkHappinessComponent` and resolved from `TwHappinessConfig`; breeding reads/mirrors this value for compatibility while partner/offspring logic evolves.
- The hook system allows interaction effects to emit a hook signal that can be consumed by NPC instruction sensors.
- TwSpawnerConfig assets are converted into per-item feature configs and executed through dedicated spawner services (policy, metadata, identity/state, effects, placement, inventory).
- TwNameItemConfig assets are resolved per item and executed through naming services (NPC info/ownership checks plus effect application).
- TwCommandItemConfig assets are indexed by item id and executed through command services (resolution, recipients, link mutation, step execution, relocation dispatch, respawn, feedback, panel entry building).
- Linked companions panel provides per-NPC actions (`Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive`) with incremental row refresh while open.
- Recall/return-home/revive use shared safe placement + relocation queueing for unloaded linked NPCs with chunk preload retries.
- Dead linked NPC snapshots persist so dead/revive state survives relog and restart.
- Owner protection can block owner damage, all player damage, or make owned NPCs invulnerable via settings.

## Where to look
- Plugin entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Actions: `src/main/java/com/alechilles/alecstamework/npc/actions`
- Sensors: `src/main/java/com/alechilles/alecstamework/npc/sensors`
- Components: `src/main/java/com/alechilles/alecstamework/npc/components`
- Interaction config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwInteractionConfig.java`
- Happiness config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwHappinessConfig.java`
- Spawner config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwSpawnerConfig.java`
- Naming config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwNameItemConfig.java`
- Command config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwCommandItemConfig.java`
- Progression bootstrap and services: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionProgressionBootstrapService.java` and `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionHappinessService.java`
- Spawner orchestration + services: `src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java` and `src/main/java/com/alechilles/alecstamework/items/Spawner*Service.java`
- Naming orchestration + services: `src/main/java/com/alechilles/alecstamework/items/NamingFeatureHandler.java` and `src/main/java/com/alechilles/alecstamework/items/Naming*Service.java`
- Command orchestration + services: `src/main/java/com/alechilles/alecstamework/items/CommandItemFeatureHandler.java` and `src/main/java/com/alechilles/alecstamework/items/Command*Service.java`
- Command relocation queue + on-load system: `src/main/java/com/alechilles/alecstamework/items/CommandNpcRelocationService.java` and `src/main/java/com/alechilles/alecstamework/npc/systems/CommandNpcRelocationOnLoadSystem.java`
- Command UI page + helpers: `src/main/java/com/alechilles/alecstamework/ui/TameworkCommandSelectionPage.java` and `src/main/java/com/alechilles/alecstamework/ui/*Service.java`
- Global config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwGlobalConfig.java`
- Example assets: `src/main/resources/Server/Tamework`

## Versioned docs
Public end user docs live in the separate wiki repo. Internal docs live here under `/docs`.

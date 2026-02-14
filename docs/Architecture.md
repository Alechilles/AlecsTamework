# Architecture Overview

This document is a high level map of how Alec's Tamework is organized and why the major systems exist. It is intended to help new contributors orient quickly.

## Core concepts
- Tamework is a framework mod that supplies reusable NPC actions, sensors, components, and asset driven configuration that other mods can reference.
- Two layers: an asset layer (NPC templates, items, particles, interaction configs) and a plugin layer (components, actions, sensors, runtime behavior).
- Asset driven configuration is preferred so other mods can override behavior without patching Java.

## Major subsystems
- NPC actions and sensors
- Optimized interactions pipeline (TwInteractionConfig + Action_Tamework_Interact)
- Hook and instruction bridge (TriggerNpcHook effect + TameworkHook sensor)
- Spawner items (TwSpawnerConfig assets + SpawnerFeatureHandler + TameworkSpawn interaction)
- Ownership and taming (components, owner interaction blocking, damage filters)
- Localization and messages (translation discovery + owner denial messages)

## Key behaviors
- Action_Tamework_Interact resolves a TwInteractionConfig and executes the first matching interaction entry.
- The interaction pipeline is split into resolution, selection, and execution helpers to isolate matching, cooldowns, and effects.
- TwInteractionConfig supports preset interactions (Tame, Feed, Harvest, Mount, ModeCycle, Breed) plus fully custom requirements and effects.
- The hook system allows interaction effects to emit a hook signal that can be consumed by NPC instruction sensors.
- TwSpawnerConfig assets are converted into per item feature configs and are used for capture and spawn logic.
- Owner protection can block owner damage, all player damage, or make owned NPCs invulnerable via settings.

## Where to look
- Plugin entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Actions: `src/main/java/com/alechilles/alecstamework/npc/actions`
- Sensors: `src/main/java/com/alechilles/alecstamework/npc/sensors`
- Components: `src/main/java/com/alechilles/alecstamework/npc/components`
- Interaction config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwInteractionConfig.java`
- Spawner config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwSpawnerConfig.java`
- Spawner handler + item interaction: `src/main/java/com/alechilles/alecstamework/items` and `src/main/java/com/alechilles/alecstamework/interactions`
- Global config asset: `src/main/java/com/alechilles/alecstamework/config/assets/TwGlobalConfig.java`
- Example assets: `src/main/resources/Server/Tamework`

## Versioned docs
Public end user docs live in the separate wiki repo. Internal docs live here under `/docs`.

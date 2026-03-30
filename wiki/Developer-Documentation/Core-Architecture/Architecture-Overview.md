---
title: "Architecture Overview"
order: 2
published: true
draft: false
---
# Architecture Overview

Parent: [Core Architecture](/mod/alecs-tamework/core-architecture-index) | [Developer Documentation](/mod/alecs-tamework/developer-documentation-index)

Tamework is split into two broad layers:
- asset layer: NPC templates, items, particle assets, bundled `Server/Tamework` configs, and translation content
- plugin layer: Java code for actions, sensors, services, systems, persistence, UI, and commands

## Major subsystems
- NPC action, sensor, and filter builder registration under `npc/`
- Optimized interaction runtime under `interactions/` plus config assets under `config/assets/`
- Item runtimes under `items/`
- Linked-panel and command UI under `ui/`
- Progression systems under `npc/progression/` and `npc/systems/`
- Ownership and damage behavior under `ownership/` and `damage/`
- Persistence under `persistence/sqlite/`
- Commands under `commands/`
- Metrics and integrations under `metrics/` and `integration/`

## Main design pattern
The codebase prefers a thin orchestrator plus focused collaborator services. That pattern is most visible in the spawner, naming, and command runtimes.

## Where to start
- Entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Builder registration: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Config assets: `src/main/java/com/alechilles/alecstamework/config/assets`
- Bundled examples: `src/main/resources/Server/Tamework`

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)



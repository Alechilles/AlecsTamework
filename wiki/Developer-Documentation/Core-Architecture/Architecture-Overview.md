---
title: "Architecture Overview"
order: 2
published: true
draft: false
---
# Architecture Overview

Parent: [Core Architecture](/mod/alecs-tamework/core-architecture) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

Tamework is split into two broad layers:
- asset layer: NPC templates, items, particle assets, framework `Server/Tamework` configs, and translation content
- optional example layer: the separately installed `Alec's Tamework! Examples` asset pack with sample NPCs, items, progression configs, translations, and art
- plugin layer: Java code for actions, sensors, services, systems, persistence, UI, and commands

## Major subsystems

- NPC action, sensor, and filter builder registration under `npc/`
- Optimized interaction runtime under `interactions/` plus config assets under `config/assets/`
- Item runtimes under `items/`
- Linked-panel and command UI under `ui/`
- Progression systems under `npc/progression/` and `npc/systems/`
- Ownership and damage behavior under `ownership/` and `damage/`
- Replacement persistence contracts and runtime under `persistence/`, SQLite
  adapters under `persistence/adapter/sqlite/`, and gameplay authors under
  `items/persistence/`
- Commands under `commands/`
- Metrics and integrations under `metrics/` and `integration/`

## Main design pattern

The codebase prefers a thin orchestrator plus focused collaborator services.
That pattern is most visible in the spawner, naming, command, and persistence
runtimes.

Persistence has one production composition and one facade bundle. Gameplay
authors submit canonical capture, release, coop, population, roster,
timed-summon, provisioning, revival, dormant, restoration, and profile-data
operations through those facades; tick and ECS systems freeze live facts and
do not own storage.

The persistence database is `tamework-state.sqlite`, beginning with a fresh
schema-v1 lineage. Released v2-v4 SQLite sources and the released DAT bundle are
read-only import inputs. Unreleased v5-v9 databases are refused unchanged.

## Where to start

- Entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Persistence composition:
  `src/main/java/com/alechilles/alecstamework/TameworkPersistenceComposition.java`
- Persistence decisions: `docs/decisions/0001-0007`
- Restored feature inventory:
  `docs/Required-Persistence-Feature-Inventory.md`
- Builder registration: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Config assets: `src/main/java/com/alechilles/alecstamework/config/assets`
- Framework assets: `src/main/resources/Server/Tamework`
- Optional examples: `examples/asset-pack/Server/Tamework` and the matching
  `Common`/`Server` assets

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)




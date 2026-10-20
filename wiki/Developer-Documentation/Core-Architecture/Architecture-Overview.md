---
title: "Architecture Overview"
order: 2
published: true
draft: false
---
# Architecture Overview

Parent: [Core Architecture](/mod/alecs-tamework/core-architecture) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

Tamework is split into two broad layers:
- asset layer: NPC templates, items, particle assets, framework `Server/Tamework` configs, translation content, and reusable media used by dependent mods
- optional example layer: the separately installed `Alec's Tamework! Examples` asset pack with sample NPCs, items, progression configs, translations, and sample-specific art
- plugin layer: Java code for actions, sensors, services, systems, persistence, UI, and commands

## Major subsystems

- NPC action, sensor, and filter builder registration under `npc/`
- Optimized interaction runtime under `interactions/` plus config assets under `config/assets/`
- Item runtimes under `items/`
- Linked-panel and command UI under `ui/`
- Progression systems under `npc/progression/` and `npc/systems/`
- Ownership and damage behavior under `ownership/` and `damage/`
- Companion persistence under `companion/`: the in-memory index (`index/`),
  the file store and writer (`store/`), live body rules and the generation fence
  (`live/`), capture, release, restore, store and death flows (`flow/`), capture
  items (`item/`), coops (`coop/`), population admission (`admission/`), the
  3.x/4.x importer (`migrate/`), and the module that wires them (`runtime/`)
- Settings and data-path stores under `settings/`
- Commands under `commands/`
- Metrics and integrations under `metrics/` and `integration/`

## Main design pattern

The codebase prefers a thin orchestrator plus focused collaborator services.
That pattern is most visible in the spawner, naming, command, and persistence
runtimes.

Companion persistence has one authority: the companion index. Each companion
is one immutable record with a stable profile ID, an owner, a location
(`LIVE`, `ITEM`, `COOP`, `STORED`, `DEAD`, `LOST` or `RELEASED`) and a
generation. The live body, a capture item or a coop slot only holds the
companion; it carries the profile ID and generation, and the record says which
holder is current.

Writes go through one global index lock and are saved by a write-behind thread
as JSON files under `universe/Tamework/Companions`. Reads are lock-free. Flows
that remove or spawn a body commit the record to disk first, then apply the live
effect. The world thread never reads or writes companion files. There is no
database; 3.x and 4.x SQLite data is imported once at the first 5.0 start, and
2.x data is not imported.

See [Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
for the files, the writer and the generation fence.

## Where to start

- Entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Companion persistence module:
  `src/main/java/com/alechilles/alecstamework/companion/runtime/CompanionPersistenceModule.java`
- Persistence decision: `docs/decisions/0011-companion-index-persistence.md`
- Builder registration: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Config assets: `src/main/java/com/alechilles/alecstamework/config/assets`
- Framework assets: `src/main/resources/Server/Tamework`
- Optional examples: `examples/asset-pack/Server/Tamework` and the matching
  `Common`/`Server` assets

## Related Pages
- [Bootstrap, Builder Registration, and Extension Points](/mod/alecs-tamework/bootstrap-builder-registration-and-extension-points)
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)




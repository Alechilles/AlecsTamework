# Architecture Overview

This document is a high-level map of how Alec's Tamework is organized and where to make changes safely.

## Core concepts
- Tamework is a framework mod supplying reusable NPC actions/sensors/components plus asset-driven runtime systems.
- Two layers: asset layer (NPC templates/items/particles/config assets) and plugin layer (components, actions, sensors, services, systems).
- Runtime is intentionally decomposed into orchestrators + focused services (selection, validation, persistence, relocation, UI view-models, feedback).

## Major subsystems
- Immutable runtime activation plan and deferred participant registration
  (`runtime` and `runtime/activation`); see
  [Runtime Activation](Runtime-Activation.md)
- NPC action/sensor/filter builder registration (`TameworkNpcBuilderRegistrar`)
- Optimized interaction pipeline (`TwInteractionConfig` + `TameworkInteract`)
- Hook bridge (`TriggerNpcHook` + `TameworkHook`)
- Companion progression (`TwHappinessConfig`, `TwNeedsConfig`, `TwBreedingConfig`, `TwTraitConfig`, lifecycle/attachment sync, attachment migrations)
- Role-scoped companion policy (`TwCompanionConfig`) with global fallback
- Spawner item runtime (`TwSpawnerConfig` + `TameworkSpawn` + spawner services)
- Naming item runtime (`TwNameItemConfig` + `TameworkNameNpc` + naming services)
- Command item runtime (`TwCommandItemConfig` + `TameworkCommand` + command services)
- Command relocation and restoration pipeline (`CommandNpcRelocationService`,
  `CommandLinkedNpcStateSnapshotService`, `CommandCompanionRestorationService`,
  and the on-load relocation system)
- Linked companions panel + command radial UI (mode/sort/filter/group management + per-row actions)
- Settings announcement UI (`TameworkSettingsAnnouncementService`) with first-run welcome copy and version-specific upgrade notices.
- Coop capture/release integration (`TwCoopConfig`) for direct live NPC
  handoff and filled capture-item intake (`companion/coop`)
- Per-player owned and deployed limits plus role-defined population groups and
  admission providers, checked inside the companion index lock
  (`companion/admission`)
- Direct SimpleClaims integration for breeding limits and native tamed-NPC
  damage policy
- Companion persistence (see
  [ADR 0011](decisions/0011-companion-index-persistence.md)): an in-memory
  companion index (`companion/index`) records where each companion is. A
  write-behind file store (`companion/store`) saves one JSON file per owner and
  one snapshot per companion not in a world under
  `universe/Tamework/Companions`. There is no database.
- Live bodies (`companion/live`): a per-companion generation fence removes
  stale bodies, a change detector saves component changes of idle companions,
  and full-entity snapshots capture companions that leave the world.
- Flows (`companion/flow`, `companion/item`): capture, release, restore, store,
  death, ownership, and summons commit the index first, then apply live
  effects.
- One-time import of 3.x and 4.x saves, a background locate pass for bodies 4.x
  left in unloaded chunks, and a refund of unfinished 4.x revive payments
  (`companion/migrate`). 2.x saves are refused.
- Public API 3.0 surfaces for companion profiles, profile extension data,
  bonded companions, capture policy, progression, command links, population
  groups, admission providers, diagnostics, and interaction extensions
  (`api/internal/Index*Api.java`); see the
  [HyDragon Integration Guide](../wiki/Modder-Documentation/System-Integration/HyDragon-Integration-Guide.md)
- Thin embedded Patchwork lifecycle and Tamework macro contribution (`integration/patchwork`). Patchwork owns patch discovery, generation, election, and `/patchwork`; new definitions use `Server/Patchwork/Patches`, while the legacy Tamework root remains readable for compatibility.
- Framework assets: `src/main/resources/Server/Tamework`
- Optional examples: `examples/asset-pack/Server/Tamework` and the matching
  `Common`/`Server` assets
- Asset-set gates and tranquilizer recipe visibility reconciliation (`TwGlobalConfig.AssetSets`)
- Metrics telemetry bootstrap + dependency forwarding (`TameworkHStatsIntegration`)

## Key behaviors
- `TameworkInteract` resolves one config and executes the first enabled matching entry.
- Interaction flow is split across resolver/selector/effect helpers for maintainability.
- `TwInteractionConfig` supports preset interactions (`Tame`, `Feed`, `Harvest`, `Mount`, `ModeCycle`, `Breed`) and custom requirement/effect combinations.
- Shared progression state persists via happiness/needs/breeding/traits/life-stage/attachments components and is restored across capture/spawn + death/respawn flows.
- Stable NPC `profile_id` is the durable identity; live entity UUIDs change
  when a companion respawns. The index maps the current body to its profile.
- Whoever holds a companion owns its state. A live body's components are
  authoritative; the index records its location as one of `LIVE`, `ITEM`,
  `COOP`, `STORED`, `DEAD`, `LOST`, or `RELEASED` (`LocationKind`). Command
  status, restoration, capture, and coop behavior read that location.
- Every holder change raises the companion's generation and stamps it on the
  new body or capture item. An older body or item copy is refused or removed
  when it is next seen (`CompanionFence`).
- Recall and restore respawn the companion from its saved snapshot
  (`RestoreFlow`). A companion in another world, or one whose body is missing,
  respawns at the next generation; no copy stays behind.
- A destroyed or despawned capture item makes its companion `LOST`, and the
  owner can recover it from its snapshot.
- Configured coops capture live NPCs and filled capture items and release
  residents. Breaking a coop releases its residents beside the block.
- Manual and passive breeding use the released breeding flow and apply direct
  SimpleClaims limits when configured.
- Command items keep their links. The linked panel reads live components for
  loaded companions and index summaries for the rest (`CompanionQueries`).
- Linked panel supports both linked and nearby modes, plus sort/filter/group assignment and group manager flows.
- Ownership/damage behavior resolves effective policy through `TwCompanionConfig` with `TwGlobalConfig` fallback.
- `limitPerPlayerOwnedTotal` counts every owned companion (out, in items,
  rosters, coops, dead, and lost). `limitPerPlayerDeployedTotal` counts
  summoned companions, loaded or not. Both are checked inside the index lock.
- Item-linked companions keep free death/Lost restoration. Roster companions
  use the role's paid-revival quote: payment is charged, then refunded if the
  revive fails. Both paths restore the same profile from its snapshot.
- Bonded companions are records on the same index. They are stored on session
  expiry, logout, and world change.
- A companion becomes `DEAD` only from a saved death and `LOST` only from
  positive evidence (a removed portal world, a destroyed capture item, or a
  body the locate pass could not find). Unloading is not evidence.
- Owners can spend and reset talent points for unloaded dead and lost ordinary
  companions from the saved snapshot. After any restore, trait and talent stat
  modifiers are reapplied.
- Runtime combat and Public API damage evaluation share one live owner-policy resolver: owner component first, then command-link owner, then persisted NPC-name owner, with role-effective protection settings.
- Settings announcements are selected per player: no announcement history shows the welcome message; later notices appear once only when their announcement ID is new to that player and they are updating from an older Tamework version.

## Where to look
- Entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Runtime activation: `src/main/java/com/alechilles/alecstamework/runtime`
- Builder registration: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Actions: `src/main/java/com/alechilles/alecstamework/npc/actions`
- Sensors: `src/main/java/com/alechilles/alecstamework/npc/sensors`
- Components: `src/main/java/com/alechilles/alecstamework/npc/components`
- Config assets: `src/main/java/com/alechilles/alecstamework/config/assets`
- Command runtime: `src/main/java/com/alechilles/alecstamework/items/Command*`
- Command UI: `src/main/java/com/alechilles/alecstamework/ui`
- Metrics: `src/main/java/com/alechilles/alecstamework/metrics`
- Ownership policy: `src/main/java/com/alechilles/alecstamework/ownership`
- SimpleClaims bridge: `src/main/java/com/alechilles/alecstamework/integration/simpleclaims`
- Companion persistence module:
  `src/main/java/com/alechilles/alecstamework/companion/runtime/CompanionPersistenceModule.java`
- Companion index, store, live bodies, flows, and import:
  `src/main/java/com/alechilles/alecstamework/companion/{index,store,live,flow,item,coop,admission,migrate}`
- Settings and data-path stores:
  `src/main/java/com/alechilles/alecstamework/settings`
- Framework assets: `src/main/resources/Server/Tamework`
- Optional examples: `examples/asset-pack/Server/Tamework` and matching
  `Common`/`Server` assets

## Versioned docs
Canonical public and contributor docs now live under `/wiki` in the main repo. `/docs` remains as legacy source material used to seed that wiki.

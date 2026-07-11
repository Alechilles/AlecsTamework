# Architecture Overview

This document is a high-level map of how Alec's Tamework is organized and where to make changes safely.

## Core concepts
- Tamework is a framework mod supplying reusable NPC actions/sensors/components plus asset-driven runtime systems.
- Two layers: asset layer (NPC templates/items/particles/config assets) and plugin layer (components, actions, sensors, services, systems).
- Runtime is intentionally decomposed into orchestrators + focused services (selection, validation, persistence, relocation, UI view-models, feedback).

## Major subsystems
- NPC action/sensor/filter builder registration (`TameworkNpcBuilderRegistrar`)
- Optimized interaction pipeline (`TwInteractionConfig` + `TameworkInteract`)
- Hook bridge (`TriggerNpcHook` + `TameworkHook`)
- Companion progression (`TwHappinessConfig`, `TwNeedsConfig`, `TwBreedingConfig`, `TwTraitConfig`, lifecycle/attachment sync, attachment migrations)
- Role-scoped companion policy (`TwCompanionConfig`) with global fallback
- Spawner item runtime (`TwSpawnerConfig` + `TameworkSpawn` + spawner services)
- Naming item runtime (`TwNameItemConfig` + `TameworkNameNpc` + naming services)
- Command item runtime (`TwCommandItemConfig` + `TameworkCommand` + command services)
- Command relocation/death snapshot pipeline (`CommandNpcRelocationService`, `CommandLinkedNpcDeathService`, on-load relocation system)
- Linked companions panel + command radial UI (mode/sort/filter/group management + per-row actions)
- Settings announcement UI (`TameworkSettingsAnnouncementService`) with first-run welcome copy and version-specific upgrade notices.
- Managed coop runtime (`TwCoopConfig`)
- Canonical owned-companion population runtime (`OwnerPopulationRuntime`) with atomic owner/claim reservations, provider-generation-scoped lookups, and lifecycle-aware physical occupancy
- Schema v6 population reconciliation (`ownership/reconciliation`) across profile state, saved worlds, base/custom containers, offline/online inventories, and nonterminal operation journals
- Optional asset patch generation (`Server/Tamework/Patches`) for JSON-like server assets that should stay valid when Tamework is absent
- Asset-set gates and tranquilizer recipe visibility reconciliation (`TwGlobalConfig.AssetSets`)
- Metrics telemetry bootstrap + dependency forwarding (`TameworkHStatsIntegration`)

## Key behaviors
- `TameworkInteract` resolves one config and executes the first enabled matching entry.
- Interaction flow is split across resolver/selector/effect helpers for maintainability.
- `TwInteractionConfig` supports preset interactions (`Tame`, `Feed`, `Harvest`, `Mount`, `ModeCycle`, `Breed`) and custom requirement/effect combinations.
- Shared progression state persists via happiness/needs/breeding/traits/life-stage/attachments components and is restored across capture/spawn + death/respawn flows.
- Command tools persist linked NPC metadata, active/inactive status, panel preferences, and group metadata directly on the item.
- Linked panel supports both linked and nearby modes, plus sort/filter/group assignment and group manager flows.
- Ownership/damage behavior resolves effective policy through `TwCompanionConfig` with `TwGlobalConfig` fallback.
- Owner slots are indexed by canonical profile across active and dormant lifecycle states; claim occupancy is indexed only for owned active/durably unloaded physical profiles. Positive admissions fail closed until the relevant readiness dimension is authoritative.
- Tamework-controlled owner/placement mutations prepare capacity before world changes, claim it immediately before apply, then commit or compensate through a durable journal. Natural claim-boundary movement is observed but not blocked.
- Coop capture/release commits its validated resident-ledger transition in the same SQLite transaction as canonical population state and the admission journal. Breeding derives pair and child identities from restart-stable inputs so replay reaches the same profiles instead of creating duplicates.
- A revivable death remains an owned dormant lifecycle state. Permanent cull/release and a death with no supported revive path use an explicit durable release transition; entity removal by itself never reopens owner capacity.
- Runtime combat and Public API damage evaluation share one live owner-policy resolver: owner component first, then command-link owner, then persisted NPC-name owner, with role-effective protection settings.
- Settings announcements are selected per player: no announcement/version history shows the welcome message, older recorded Tamework versions show the current update notice, and current-version history suppresses automatic notices.

## Where to look
- Entrypoint: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Builder registration: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Actions: `src/main/java/com/alechilles/alecstamework/npc/actions`
- Sensors: `src/main/java/com/alechilles/alecstamework/npc/sensors`
- Components: `src/main/java/com/alechilles/alecstamework/npc/components`
- Config assets: `src/main/java/com/alechilles/alecstamework/config/assets`
- Command runtime: `src/main/java/com/alechilles/alecstamework/items/Command*`
- Command UI: `src/main/java/com/alechilles/alecstamework/ui`
- Metrics: `src/main/java/com/alechilles/alecstamework/metrics`
- Owner/claim admission: `src/main/java/com/alechilles/alecstamework/ownership` and `src/main/java/com/alechilles/alecstamework/integration/claims`
- Population persistence/reconciliation: `src/main/java/com/alechilles/alecstamework/persistence/sqlite` and `src/main/java/com/alechilles/alecstamework/ownership/reconciliation`
- Owner/claim mutation audit matrix and recovery guidance: `docs/Claims-and-Owner-Population-Path-Matrix.md`
- Bundled assets/examples: `src/main/resources/Server/Tamework`

## Versioned docs
Canonical public and contributor docs now live under `/wiki` in the main repo. `/docs` remains as legacy source material used to seed that wiki.

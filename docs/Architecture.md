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
- Managed coop runtime (`TwCoopConfig`) with schema-v5 resident, lifecycle-operation, and import journals
- Canonical owned-companion population runtime (`OwnerPopulationRuntime`) with atomic owner/claim reservations, weak generation-bound provider contracts, and lifecycle-aware physical occupancy
- Schema v7/v8 persistence resilience and population reconciliation
  (`persistence/*`, `ownership/reconciliation`) with durable scoped
  incidents/quarantines, resumable persisted scan sessions, capture attempts,
  bonded-vessel operations, population-group evidence, companion provisioning,
  and saved-world loading across profile state, worlds, containers,
  inventories, and nonterminal journals
- Experimental Public API 0.9 integration authorities and fail-closed feature
  gates (`api/*`, `items/capturepolicy`, `ownership/groups`, `vessels`,
  `provisioning`); see [API 0.9 and HyDragon integration](API-0.9-HyDragon-Integration.md)
- Optional asset patch generation (`Server/Tamework/Patches`) for JSON-like server assets that should stay valid when Tamework is absent
- Asset-set gates and tranquilizer recipe visibility reconciliation (`TwGlobalConfig.AssetSets`)
- Metrics telemetry bootstrap + dependency forwarding (`TameworkHStatsIntegration`)

## Key behaviors
- `TameworkInteract` resolves one config and executes the first enabled matching entry.
- Interaction flow is split across resolver/selector/effect helpers for maintainability.
- `TwInteractionConfig` supports preset interactions (`Tame`, `Feed`, `Harvest`, `Mount`, `ModeCycle`, `Breed`) and custom requirement/effect combinations.
- Shared progression state persists via happiness/needs/breeding/traits/life-stage/attachments components and is restored across capture/spawn + death/respawn flows.
- Stable NPC `profile_id` is the durable identity; live entity UUIDs are replaceable aliases. Recovery and command records deduplicate by profile once canonical identity is available.
- An enabled/configured managed coop is Tamework-authoritative for occupancy and lifecycle, while an unmanaged coop remains purely vanilla. The runtime intentionally does not synchronize a second Tamework representation beside vanilla residents.
- Managed authority is granted only for an exact observed coop id whose base `FarmingCoopAsset.CaptureWildNPCsInRange` is explicitly `false`; otherwise Tamework rejects the overlay so vanilla and Tamework automatic intake cannot run together.
- Legacy vanilla residents cross that boundary only through fingerprint-approved import. Exact deployed residents are adopted in place after a durable binding/marker; current-boot absence proof retires vanilla ownership without spawning a replacement. Persisted release adoption revalidates the exact saved/loaded evidence generation after asynchronous population preparation and cancels only that provisional population capability when the generation changes.
- Stale managed aliases are suppressible only while a current exact coop/config scan authorizes their site and an exact retained projection marker independently validates. Cross-world aliases use two owning-thread proof hops and otherwise remain fail-closed.
- Manual and passive breeding share one pending-aware birth-job and capacity-admission pipeline, including one-job-per-parent and one-spawn-claim invariants. Parent capture waits for durable terminal cancellation of every prepared child, retains the parent-identity fence through coop persistence or capture-crate source removal, and delayed stages require lock-consistent `ACTIVE` canonical parent lifecycle evidence.
- Command tools persist linked NPC metadata, active/inactive status, panel preferences, and group metadata directly on the item.
- Linked panel supports both linked and nearby modes, plus sort/filter/group assignment and group manager flows.
- Ownership/damage behavior resolves effective policy through `TwCompanionConfig` with `TwGlobalConfig` fallback.
- Owner slots are indexed by canonical profile across active and dormant lifecycle states; claim occupancy is indexed only for owned active/durably unloaded physical profiles. Positive admissions fail closed until the relevant readiness dimension is authoritative.
- Tamework-controlled owner/placement mutations prepare capacity before world changes and durably enter `APPLYING` immediately before the world side effect. Rollback durably enters `COMPENSATING` first, restores derived/source state before restoring the canonical owner, then closes the journal and releases owner/claim reservations. Ambiguous partial rollback remains quarantined for reconciliation. Natural claim-boundary movement is observed but not blocked.
- Coop capture/release commits its validated resident-ledger transition in the same SQLite transaction as canonical population state and the admission journal. Breeding derives pair and child identities from restart-stable inputs so replay reaches the same profiles instead of creating duplicates.
- A revivable death immediately becomes an owned dormant lifecycle state: it keeps its owner slot but releases physical claim occupancy as soon as death is observed. Permanent cull/release and a death with no supported revive path use an explicit durable release transition; entity removal by itself never reopens owner capacity.
- World-thread handoffs that hold population capacity use a lease-aligned start watchdog. Accepted work that never starts is rejected exactly once, and a late queued wrapper becomes a no-op.
- Bundled `/tw api test` fixture setup and reset use the production journaled owner-mutation authority, including `ADMIN_FORCE` assignment and durable permanent release.
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

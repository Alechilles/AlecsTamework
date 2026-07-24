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
- Command relocation and restoration pipeline (`CommandNpcRelocationService`,
  `CommandLinkedNpcStateSnapshotService`, `CommandCompanionRestorationService`,
  and the on-load relocation system)
- Linked companions panel + command radial UI (mode/sort/filter/group management + per-row actions)
- Settings announcement UI (`TameworkSettingsAnnouncementService`) with first-run welcome copy and version-specific upgrade notices.
- Coop capture/release integration (`TwCoopConfig`) for direct live NPC
  handoff; filled spawner items use the canonical captured-spawner release
  operation
- A simple loaded-owner cap used by taming and ownership changes
- Direct SimpleClaims integration for breeding limits and native tamed-NPC
  damage policy
- Canonical companion identity, saved death/Lost restoration, and namespaced
  profile extension data
- One replacement persistence composition over the fresh
  `tamework-state.sqlite` schema-v1 lineage. Public v2-v4 sources and the
  released DAT bundle are imported read-only; unreleased v5-v9 sources are
  refused unchanged.
- One shared idempotent operation protocol and one ordered projection outbox
  for every persistence-backed gameplay mutation. Features supply typed
  payloads and focused live boundaries instead of their own journals, queues,
  or cache authorities.
- One bounded feature-control plane derived from
  `PublicPersistenceFeatureRegistry`. Its shared `feature_circuit` rows gate
  affected mutations and feed diagnostics such as `openCircuits`; they do not
  recreate the old v5 failure catalogs or feature-specific health systems.
- Public API integration surfaces for canonical profiles, profile extension
  data, capture policy, progression, command links, and interaction extensions;
  see the
  [HyDragon Integration Guide](../wiki/Modder-Documentation/System-Integration/HyDragon-Integration-Guide.md)
- Optional asset patch generation (`Server/Tamework/Patches`) for JSON-like server assets that should stay valid when Tamework is absent
- Asset-set gates and tranquilizer recipe visibility reconciliation (`TwGlobalConfig.AssetSets`)
- Metrics telemetry bootstrap + dependency forwarding (`TameworkHStatsIntegration`)

## Key behaviors
- `TameworkInteract` resolves one config and executes the first enabled matching entry.
- Interaction flow is split across resolver/selector/effect helpers for maintainability.
- `TwInteractionConfig` supports preset interactions (`Tame`, `Feed`, `Harvest`, `Mount`, `ModeCycle`, `Breed`) and custom requirement/effect combinations.
- Shared progression state persists via happiness/needs/breeding/traits/life-stage/attachments components and is restored across capture/spawn + death/respawn flows.
- Stable NPC `profile_id` is the durable identity; live entity UUIDs are replaceable aliases. Recovery and command records deduplicate by profile once canonical identity is available.
- Live feature boundaries such as configured-coop capture resolve the current
  entity UUID alias back to that stable profile before submitting a mutation;
  an alias rotation never creates a second identity or makes the companion
  ineligible by itself.
- `ACTIVE`, `UNLOADED`, `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, `LOST`,
  `RELEASED`, and `UNRESOLVED` form the sole durable companion lifecycle.
  Command status, restoration, capture, and coop behavior read that lifecycle
  instead of inferring durable state from feature-local snapshots or caches.
- Public imports keep ordinary no-flag profiles `UNRESOLVED` offline. Once
  Hytale reports all startup worlds loaded, sealed entity-store evidence
  resolves a matching NPC to `ACTIVE` and sealed absence to `UNLOADED`; an
  empty pre-world universe never proves absence.
- Configured coops capture live NPCs and release live residents directly. Capture
  items are not a second coop-intake protocol.
- Manual and passive breeding use the released breeding flow and apply direct
  SimpleClaims limits when configured.
- Command tools persist linked NPC metadata, active/inactive status, panel preferences, and group metadata directly on the item.
- Linked panel supports both linked and nearby modes, plus sort/filter/group assignment and group manager flows.
- Ownership/damage behavior resolves effective policy through `TwCompanionConfig` with `TwGlobalConfig` fallback.
- The owner cap counts currently loaded owned NPCs. It is not a durable dormant
  ledger and does not reserve future capacity.
- Linked companions restore from saved death or Lost state without payment.
  The death snapshot's immutable respawn deadline is also projected into the
  linked panel, so display and restoration admission use the same canonical
  timing fact.
- Dormant transitions require positive evidence: a saved death, an explicit
  destructive `REMOVE`, or terminal removal of a delete-on-remove world.
  Unload, absence, and timeout are not evidence that a companion is dead or
  Lost.
- The unreleased July paid-revival, durable population/group, provisioning,
  command-roster, timed-summon, captured-item coop-intake, rehearsal, and
  duplicate command/spawner persistence-state cache authorities are not part
  of the replacement runtime. Free death/Lost restoration, the live owner cap,
  direct-live coop capture/release, filled-spawner capture/release, and the
  shared bounded feature-control plane remain.
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
- Ownership policy: `src/main/java/com/alechilles/alecstamework/ownership`
- SimpleClaims bridge: `src/main/java/com/alechilles/alecstamework/integration/simpleclaims`
- Persistence composition:
  `src/main/java/com/alechilles/alecstamework/TameworkPersistenceComposition.java`
- Persistence contracts/runtime/adapters:
  `src/main/java/com/alechilles/alecstamework/persistence`
- Gameplay persistence authors:
  `src/main/java/com/alechilles/alecstamework/items/persistence`
- Bundled assets/examples: `src/main/resources/Server/Tamework`

## Versioned docs
Canonical public and contributor docs now live under `/wiki` in the main repo. `/docs` remains as legacy source material used to seed that wiki.

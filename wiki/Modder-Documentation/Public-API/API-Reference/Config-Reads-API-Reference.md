---
title: "Config Reads API Reference"
order: 9
published: true
draft: false
---
# Config Reads API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This reference tracks the current `configs()` contract in `TameworkApi`.

Capability: `CONFIG_READ`

## Entry Point
`TameworkApi.configs() -> TameworkConfigReadApi`

## Methods
Global + interaction:
- `GlobalConfigView getGlobalConfig()`
- `Optional<InteractionConfigView> getInteractionConfigById(String id)`
- `Optional<InteractionConfigView> resolveInteractionConfigForRole(String roleId)`

Role-scoped config families:
- `getCompanionConfigById(...)` / `resolveCompanionConfigForRole(...)`
- `getHappinessConfigById(...)` / `resolveHappinessConfigForRole(...)`
- `getNeedsConfigById(...)` / `resolveNeedsConfigForRole(...)`
- `getBreedingConfigById(...)` / `resolveBreedingConfigForRole(...)`
- `getTraitConfigById(...)` / `resolveTraitConfigForRole(...)`

Item-scoped config families:
- `getSpawnerConfigById(...)` / `resolveSpawnerConfigForItemId(...)`
- `getNameItemConfigById(...)` / `resolveNameItemConfigForItemId(...)`
- `getCommandItemConfigById(...)` / `resolveCommandItemConfigForItemId(...)`

API 0.9 capture and bonded-vessel views:

- `getSpawnerCaptureMechanicsById(...)` / `resolveSpawnerCaptureMechanicsForItemId(...)`
- `getCapturePolicyById(...)` / `resolveCapturePolicyForRole(...)`
- `getSpawnerVesselConfigById(...)` / `resolveSpawnerVesselConfigForItemId(...)`

API 0.9 population-group views:

- `getPopulationGroupById(...)`
- `resolvePopulationGroupsForRole(...)`

## View Types
- `GlobalConfigView`
- `InteractionConfigView`
- `RoleScopedConfigView`
- `SpawnerConfigView`
- `NameItemConfigView`
- `CommandItemConfigView`
- `SpawnerCaptureMechanicsView`
- `CapturePolicyConfigView`
- `SpawnerVesselConfigView`
- `PopulationGroupDefinitionView`

## Notes
- Returned views are detached immutable DTOs.
- `detailsJson` fields provide a compact JSON representation of resolved config details.
- Config reload events are emitted through `events()` as `ConfigReloadedEvent`.
- API 0.9 default methods return empty views for older/unwired
  implementations. Require the corresponding feature capability before using
  a view to enable gameplay.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Events API Reference](/mod/alecs-tamework/events-api-reference)



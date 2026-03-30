---
title: "Config Reads API Reference"
order: 8
published: true
draft: false
---
# Config Reads API Reference

Parent: [API Reference Index](/mod/alecs-tamework/api-reference-index) | [Public API Index](/mod/alecs-tamework/public-api-index)

> **Experimental API Contract (`0.4.0`)**
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

## View Types
- `GlobalConfigView`
- `InteractionConfigView`
- `RoleScopedConfigView`
- `SpawnerConfigView`
- `NameItemConfigView`
- `CommandItemConfigView`

## Notes
- Returned views are detached immutable DTOs.
- `detailsJson` fields provide a compact JSON representation of resolved config details.
- Config reload events are emitted through `events()` as `ConfigReloadedEvent`.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Events API Reference](/mod/alecs-tamework/events-api-reference)

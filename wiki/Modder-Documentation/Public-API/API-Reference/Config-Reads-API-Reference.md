---
title: "Config Reads API Reference"
order: 9
published: true
draft: false
---
# Config Reads API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Stable API Contract (`1.0.0`)**
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
- `getLevelingConfigById(...)` / `resolveLevelingConfigForRole(...)`
- `getTraitConfigById(...)` / `resolveTraitConfigForRole(...)`
- `getTalentConfigById(...)` / `resolveTalentConfigForRole(...)`

Item-scoped config families:
- `getSpawnerConfigById(...)` / `resolveSpawnerConfigForItemId(...)`
- `getNameItemConfigById(...)` / `resolveNameItemConfigForItemId(...)`
- `getCommandItemConfigById(...)` / `resolveCommandItemConfigForItemId(...)`

API 0.9 capture views:

- `getSpawnerCaptureMechanicsById(...)` / `resolveSpawnerCaptureMechanicsForItemId(...)`
- `getCapturePolicyById(...)` / `resolveCapturePolicyForRole(...)`


## View Types
- `GlobalConfigView`
- `InteractionConfigView`
- `RoleScopedConfigView`
- `SpawnerConfigView`
- `NameItemConfigView`
- `CommandItemConfigView`
- `SpawnerCaptureMechanicsView`
- `CapturePolicyConfigView`

## Global population limits
`GlobalConfigView.population()` returns the effective per-player limits (the
`/tw settings` values). `0` means no limit, and both limits use
`perPlayerLimitScope()` (`PerWorld` or `Global`).

- `limitPerPlayerOwnedTotal()`: every companion the player owns (out in the
  world, stored, in items, in coops, dead and lost).
- `limitPerPlayerDeployedTotal()`: the player's companions out in the world,
  loaded or not. Added in 5.0. The two-argument `PopulationView` constructor
  remains and sets it to `0`.

## Notes
- Returned views are detached immutable DTOs.
- `detailsJson` fields provide a compact JSON representation of resolved config details.
- Config reload events are emitted through `events()` as `ConfigReloadedEvent`.
- Capture-specific reads return `Optional.empty()` when the capture-policy
  surface is unavailable. Require `CAPTURE_POLICY` before using those views to
  enable gameplay.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Events API Reference](/mod/alecs-tamework/events-api-reference)



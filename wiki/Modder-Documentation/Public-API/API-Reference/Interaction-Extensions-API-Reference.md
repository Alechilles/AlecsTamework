---
title: "Interaction Extensions API Reference"
order: 10
published: true
draft: false
---
# Interaction Extensions API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.6.0`)**
> This reference tracks the current `interactionExtensions()` contract in `TameworkApi`.

Capability: `INTERACTION_EXTENSIONS`

## Entry Point
`TameworkApi.interactionExtensions() -> InteractionExtensionApi`

## Methods
- `AutoCloseable registerRequirement(String id, InteractionRequirementHandler handler)`
- `AutoCloseable registerEffect(String id, InteractionEffectHandler handler)`
- `AutoCloseable registerPreset(InteractionPresetDefinition preset)`
- `Optional<InteractionPresetDefinition> getPreset(String id)`
- `Set<String> listRequirementIds()`
- `Set<String> listEffectIds()`
- `Set<String> listPresetIds()`

## ID Rules
- IDs must be nonblank.
- IDs are normalized to lowercase internally.
- Re-registering the same ID replaces the previous handler/preset.
- Closing the returned `AutoCloseable` unregisters that exact registration.

## Runtime Behavior
- Requirement handlers return `boolean` pass/fail.
- Effect handlers return `boolean` success/failure.
- Handler exceptions are caught and logged as warnings; failed handler calls return `false`.

## Related Types
- `InteractionRequirementSpec`
- `InteractionEffectSpec`
- `InteractionRequirementContext`
- `InteractionEffectContext`
- `InteractionPresetDefinition`

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Interaction Extension Registration Recipe](/mod/alecs-tamework/interaction-extension-registration-recipe)
- [Register Interaction Extensions in Plugin Lifecycle Recipe](/mod/alecs-tamework/register-interaction-extensions-in-plugin-lifecycle-recipe)



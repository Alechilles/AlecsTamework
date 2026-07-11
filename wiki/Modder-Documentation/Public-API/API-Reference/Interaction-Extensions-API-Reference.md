---
title: "Interaction Extensions API Reference"
order: 11
published: true
draft: false
---
# Interaction Extensions API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.7.0`)**
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
- The `tamework:` namespace is reserved for built-in handlers and cannot be registered through the public API.
- Re-registering the same non-reserved ID replaces the previous handler/preset.
- Closing the returned `AutoCloseable` unregisters that exact registration.

## Built-in Attachment Extensions

- `tamework:model_supports_attachment` is a custom requirement whose `Param` names an attachment slot. Optional `Values` require at least one supported option.
- `tamework:set_attachment_from_held_item` is a custom effect whose `Param` names an attachment slot and whose `Values` contain exact `ItemId=AttachmentValue` mappings.

The attachment effect owns item consumption. It validates the live held item and current model before changing persisted/live attachment state, and it does not consume an item when the mutation fails or is already applied.

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



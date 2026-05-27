---
title: "Trait Effects API Reference"
order: 11
published: true
draft: false
---
# Trait Effects API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.6.0`)**
> This reference tracks the current `traitEffects()` contract in `TameworkApi`.

Capability: `TRAIT_EFFECTS`

## Entry Point
`TameworkApi.traitEffects() -> TraitEffectApi`

## Methods
- `AutoCloseable registerEffectKey(String effectKey, TraitEffectHandler handler)`
- `Set<String> listEffectKeys()`

## ID Rules
- Effect keys must be nonblank.
- Effect keys are normalized to lowercase internally.
- Re-registering the same key replaces the previous handler.
- Closing the returned `AutoCloseable` unregisters that exact handler.
- Use namespaced keys, such as `example.genetics:scalepattern`, to avoid collisions.

## Runtime Behavior
- Tamework calls registered handlers during existing trait-effect resync paths.
- Built-in Tamework effect keys still run first and are not replaced by registered custom handlers.
- Handler calls are synchronous on the existing resync path, so keep them fast, idempotent, and main-thread safe.
- The resolved value is the product of active finite trait values whose `EffectKey` matches the registered key.
- If a registered key has no active contributing traits, Tamework still calls the handler with `value = 1.0` and an empty contribution list so the handler can clear its own effect.
- Handler exceptions are caught and logged as warnings; they do not crash the world thread.
- A handler that returns `false` is treated as unsuccessful but does not interrupt Tamework's own resync.

## Context
`TraitEffectContext` includes:
- `effectKey`: the normalized registered effect key.
- `value`: the resolved multiplier product, or `1.0` when no traits contribute.
- `contributions`: active `TraitEffectContribution` values.
- `npcRef` and `store`: the live NPC reference and entity store for main-thread ECS-safe reads/writes.
- `npcUuid`: the NPC UUID when available.
- `profileId`: the Tamework profile ID when the NPC has a profile.
- `roleId`: the resolved NPC role ID when available.
- `traitConfigId`: the resolved trait config ID when available.

`TraitEffectContribution` includes:
- `traitId`
- `displayName`
- `value`
- `effectKey`

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [TwTraitConfig Reference](/mod/alecs-tamework/twtraitconfig-reference)
- [Register Custom Trait Effect Key Recipe](/mod/alecs-tamework/register-custom-trait-effect-key-recipe)

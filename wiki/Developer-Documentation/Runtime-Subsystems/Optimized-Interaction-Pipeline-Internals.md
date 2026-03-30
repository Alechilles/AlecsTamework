---
title: "Optimized Interaction Pipeline Internals"
order: 6
published: true
draft: false
---
# Optimized Interaction Pipeline Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems-index) | [Developer Documentation](/mod/alecs-tamework/developer-documentation-index)

## Core entry points
- Action builder: `BuilderActionTameworkInteract`
- Prompt updater: `BuilderActionTameworkInteractPrompt`
- Config asset: `TwInteractionConfig`

## Internal behavior
- Resolve one config
- Evaluate interactions in authored order
- Stop at the first enabled entry whose requirements pass
- Apply effects, cooldowns, prompt behavior, and any follow-on bridge data

## Why the system exists
It replaces very large vanilla instruction chains with a data-driven config surface while still allowing hook-based escape hatches into custom behavior.

## Important coupling points
- `TwGlobalConfig.InteractionDefaults` supplies shared param names and alarm names
- `TwNeedsConfig` and `TwHappinessConfig` feed the `Feed` preset
- `TwBreedingConfig` feeds the `Breed` preset
- `TriggerNpcHook` bridges into downstream instruction graphs

## Maintenance advice
- Keep requirement evaluation and effect application modular
- Avoid smuggling role-specific policy into the interaction runtime when it belongs in the config families
- Use explicit `ConfigId` selection when debugging ambiguous config matches

## Related Pages
- [Config Loading, Registries, Inheritance, and Overrides](/mod/alecs-tamework/config-loading-registries-inheritance-and-overrides)
- [Ownership, Damage, and Progression Internals](/mod/alecs-tamework/ownership-damage-and-progression-internals)



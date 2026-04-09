---
title: "Interaction Paths and Role Wiring"
order: 4
published: true
draft: false
---
# Interaction Paths and Role Wiring

Parent: [Start Here](/mod/alecs-tamework/start-here) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This page is about implementation flow. Use it to choose the right interaction path, wire the role correctly, and know which reference pages hold the exact schema.

## Choose an Interaction Path
### Recommended path: optimized interactions
Use `TameworkInteract` plus [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference) when:
- your interaction can be expressed as a preset or a requirements-plus-effects flow
- you want less JSON than a full vanilla instruction tree
- you want prompt generation and config-driven reuse across many roles
- you want to bridge into instruction logic only where needed

### Full vanilla path
Use a vanilla instruction flow when:
- the interaction is highly bespoke and does not map cleanly to Tamework presets or effects
- you need a one-off behavior tree that is easier to understand as explicit instructions
- you want total control over branching, timing, or nonstandard AI flow

Tamework does not force one path everywhere. Use the optimized path for the common cases and bridge into custom logic only where the config model stops being efficient.

## Recommended Wiring Flow
1. Create or choose the NPC role and template.
2. Add the interaction action node:
```json
"Actions": [
  {
    "Type": "LockOnInteractionTarget",
    "TargetSlot": { "Compute": "MasterTargetSlot" }
  },
  { "Type": "TameworkInteract" }
]
```
3. Add `TameworkInteractPrompt` if you want prompt text to update in the HUD.
4. Create a `TwInteractionConfig` in `Server/Tamework/Interactions/`.
5. Decide how the config should resolve:
   - direct `ConfigId` on the action
   - role param via `TwGlobalConfig.InteractionDefaults.InteractionConfigParam`
   - plain `RoleIds` + `Priority`
6. Add any required role params such as loved-item, mountable, or harvestable params.
7. If the interaction needs custom instruction behavior, emit a hook and consume it with `TameworkHook`.

## Role Wiring Checklist
- The role can be locked to the interaction target.
- `TameworkInteract` is present on the interaction action chain.
- `TameworkInteractPrompt` is present if prompts matter.
- The role exposes any params referenced by `TwGlobalConfig.InteractionDefaults`.
- The role has compatible state names for mode cycling or state effects.
- If `TriggerNpcHook` is used, a matching `TameworkHook` consumer exists downstream.

## When to Use Action Overrides
Put the shared interaction behavior in `TwInteractionConfig`. Use action-level overrides only when the role needs a one-off value.

Common action-side overrides:
- `ConfigId`
- `LovedItems`
- `IsMountable`
- `IsHarvestable`
- `HarvestInteractionContext`

If the same override keeps repeating across roles, move the value back into shared params or config.

## Preset Choice Guide
- Use `Tame` for ownership + tame + optional role swap.
- Use `Feed` for item consumption, healing, happiness gain, and needs refill.
- Use `Harvest` for harvest-ready checks and harvest state handoff.
- Use `Mount` for rideable interactions with ownership and crouch gates.
- Use `ModeCycle` for command-style mode toggles on direct interaction.
- Use `Breed` when the interaction should enter the breeding runtime.
- Use `Custom` when you need the requirements and effects model without a preset wrapper.

The exact field schema for each preset lives in [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference).

## Hook Bridging Pattern
Use a hook when:
- the requirements are config-friendly
- the side effect is too custom for the built-in effects

Pattern:
1. `TwInteractionConfig` entry emits `TriggerNpcHook`.
2. The role or template runs `TameworkHook`.
3. Your instruction flow consumes the hook id and optional payload.

This keeps the interaction authoring simple while preserving full custom behavior where it belongs.

## Troubleshooting Flow
1. Confirm the expected config actually resolves.
2. If multiple configs could match, set `ConfigId`.
3. Confirm role params referenced by `TwGlobalConfig.InteractionDefaults` exist.
4. Use `/tw getalarm` for harvest or cooldown issues.
5. Use `/tw debugprompt` when prompt output is stale or missing.
6. Use `/tw debughook` if a hook bridge is not firing or being consumed.

## Related Pages
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [Projectile Combat and Hazard Interactions Guide](/mod/alecs-tamework/projectile-combat-and-hazard-interactions-guide)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)




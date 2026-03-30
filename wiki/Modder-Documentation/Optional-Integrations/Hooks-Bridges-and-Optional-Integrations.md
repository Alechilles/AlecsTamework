---
title: "Hooks, Bridges, and Optional Integrations"
order: 11
published: true
draft: false
---
# Hooks, Bridges, and Optional Integrations

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations-index) | [Modder Documentation](/mod/alecs-tamework/modder-documentation-index)

This page covers the boundary between config-driven authoring and custom behavior. Use hooks when you need custom logic without throwing away Tamework’s higher-level systems.

## Internal Hook Bridges
### Interaction hook bridge
`TwInteractionConfig` can emit:
- `TriggerNpcHook`

That writes hook data into the runtime so a matching `TameworkHook` instruction flow can consume it.

Use this when:
- requirements and prompt behavior fit `TwInteractionConfig`
- the actual side effect is too custom for built-in effects

### Command hook bridge
`TwCommandItemConfig` can emit:
- `TriggerHook` command steps

Use this when a radial command should dispatch into custom instruction behavior instead of only doing a built-in state or movement change.

## Recommended Hook Pattern
1. Keep selection, targeting, prompts, and common validation in the config family.
2. Emit a stable hook id from the config.
3. Consume that hook in a role or template with `TameworkHook`.
4. Keep the hook consumer narrowly scoped to the custom behavior you actually need.

This keeps the public config readable while still allowing mod-specific logic.

## When Not to Use a Hook
Do not use a hook just because you can.

Stay in the config layer when:
- a built-in interaction preset already covers the behavior
- the effect is a simple state change, role swap, item change, sound, particles, or mount
- the command can be expressed with built-in steps

Use a hook only when the behavior would otherwise force you back into a large bespoke instruction tree.

## Optional Integrations
### DynamicTooltipsLib
Relevant family:
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)

What it enables:
- additive or replace-style tooltip output for filled spawners
- Tamework tooltip lines such as captured name and role

Fallback behavior:
- if the library is missing, the spawner still works and the tooltip bridge is simply absent

### SimpleClaims
Relevant family:
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)

What it enables:
- claim-aware breeding limits
- claim-aware damage protection for tamed NPCs

Fallback behavior:
- if SimpleClaims is not present or the feature is disabled, Tamework uses normal non-claim behavior

### Bundled Asset Sets
Relevant family:
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)

Use `AssetSets` when you want to enable optional bundled assets such as:
- feed trough support
- tranquilizer-related items

## Design Rules for Safe Bridges
- Keep stable ids. Hook ids and config ids are API.
- Prefer optional integrations that fail closed and leave the base feature usable.
- Do not bury core validation inside the hook if it can live in the config layer.
- Put bridge-specific tuning in the family that owns it instead of inventing side-channel params.

## Debugging Bridges
- Use `/tw debughook` to inspect hook emit and consume flow.
- Use `/tw debugprompt` when interaction prompts look wrong before a hook even fires.
- Use the linked panel and item feedback to confirm command dispatch before debugging the downstream hook consumer.

## Related Pages
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)



---
title: "Hooks, Bridges, and Optional Integrations"
order: 11
published: true
draft: false
---
# Hooks, Bridges, and Optional Integrations

Parent: [Optional Integrations](/mod/alecs-tamework/optional-integrations) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

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
### NameplateBuilder
What it enables:
- overrides NameplateBuilder's built-in `entity-name` segment for NPCs that have a `TameworkNpcNameComponent`
- shows a Tamework custom pet name through the player's existing `Entity Name` chain entry instead of creating a second pet-name segment
- registers optional Tamework NPC blocks for `Happiness`, `Hunger`, `Thirst`, `Tranquilizer`, and `Traits` so players can add companion progression data to their chains
- `Happiness`, `Hunger`, and `Thirst` support compact shortened-label formats such as `Hap 80%` and `Hun 50/100`
- the `Tranquilizer` block supports `Stacks + Time`, `Stacks`, and `Time` formats; stacks are derived from the peak duration reached during the active debuff
- the `Traits` block supports shortened or full labels with `Raw Value` and linked-panel-style `Relative %` formats

Fallback behavior:
- if NameplateBuilder is missing, Tamework naming still works normally and no external nameplate override is registered

### Claim providers
Relevant family:
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)

What it enables:
- QuestLines Claims `1.3.1`: claim-aware owner assignment, placement, restore, relocation, and breeding admissions
- SimpleClaims `>=1.0.38 <1.1.0`: the same population admissions plus SimpleClaims-native tamed-target damage policy

Provider selection:

- `Off` disables claim population checks.
- An explicit `QuestLinesClaims` or `SimpleClaims` selection is never substituted.
- `Auto` probes QuestLines Claims first and probes SimpleClaims only when QuestLines Claims is absent or disabled.
- An installed but not-ready, incompatible, or broken QuestLines Claims is not treated as absent. Active population admissions fail closed instead of falling through to SimpleClaims.
- Provider and settings changes are picked up by the next operation. A reservation already in flight keeps the provider generation and settings revision with which it was prepared.

Activation and failure behavior:

| Master integration | Relevant population rule | Damage protection | Result |
| --- | --- | --- | --- |
| Off | Any | Any | Neither population nor damage probes a provider. Owner limits still work. |
| On | No positive cap, and not a breeding `BreedingRequiresClaim` check | Off | No claim-provider work. |
| On | Positive claim cap, or breeding with `BreedingRequiresClaim` | Any | Population resolves the selected provider and fails closed on an unavailable/invalid result. |
| On | Any | On | Eligible live tamed targets use SimpleClaims damage policy; lookup/integration errors fail open. |

The legacy setting/config name remains `SimpleClaimsEnabled`, but it is the master switch for both claim providers. QuestLines Claims does not provide damage protection.

Claim limits are admission caps, not movement walls. Tamework gates explicit tame/set-owner, owned spawn/release, coop release, recall/teleport, revive, lost recovery, and breeding placements. Natural movement remains allowed; if it makes a claim over-cap, later explicit admissions are denied until occupancy falls.

Owned `ACTIVE` and durably `UNLOADED` companions occupy claims. `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, and `LOST` companions keep their owner slot but do not occupy a claim. SimpleClaims claim extent is resolved within the requested world, so a party's chunks in another world do not inflate the current claim.

### SimpleClaims damage policy

`ProtectTamedFromNonMembers` enables SimpleClaims' native tamed-damage decision for eligible live tamed NPCs; it is not a simple membership-only check. Native evaluation preserves full-world protection, administrator access, owner/member permissions, direct player allies, allied parties using the attacker's resolved party ID, SimpleClaims' native tamed-damage permission, and the claim party's outsider setting.

- Owner-specific Tamework protections run first.
- A live tamed target is eligible even if it is a legacy/unowned tame.
- An owned but not tamed NPC skips claim damage policy.
- Public `evaluateDamage` requires the live target for this eligibility decision; dormant profiles return `UNAVAILABLE`/`live-target-required` instead of guessing from saved state.
- Optional-integration errors fail open so a broken claim bridge cannot make companions invulnerable.

`SimpleClaims.Damage.AllowDamagePermissionKey` is a Hytale server permission checked before native SimpleClaims policy. For one compatibility release, Tamework also recognizes the previous raw SimpleClaims claim-party permission lookup (attacker player UUID + configured key), logs a throttled deprecation warning when it grants access, and will remove that compatibility path in the next major release. SimpleClaims' own native tamed-damage permission remains separate.

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
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)




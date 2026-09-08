---
title: "Husbandry Outcomes API Reference"
order: 13
published: true
draft: false
---
# Husbandry Outcomes API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Stable API Contract (`2.0.0`)**
> This reference tracks the current `husbandryOutcomes()` contract in
> `TameworkApi`.

Capability: `HUSBANDRY_OUTCOMES`

Development addition: `HUSBANDRY_CARE_BONUSES` advertises conditional happiness
bonuses. Check it before requiring the eight-argument outcome contract.
`HUSBANDRY_BREEDING_GENETICS` is a development addition for the ten-argument
contract and `BREEDING_GENETICS` outcome.

## Entry Point

`TameworkApi.husbandryOutcomes() -> HusbandryOutcomeApi`

Check the capability and `available()` before registration. Older or degraded
API compositions return an unavailable facade. That facade resolves identity
values and does not retain a provider.

## Provider Lifecycle

- `register(HusbandryOutcomeProvider)` accepts one active provider.
- A second active provider causes `IllegalStateException`.
- Keep the returned `AutoCloseable` and close it when the provider plugin
  stops. Closing it is idempotent and unregisters only that provider.
- Registration and removal are thread-safe.
- Resolution is synchronous on the Tamework action thread. Keep provider work
  fast, read-only, and safe for the calling thread.

## Resolution Contract

The provider receives an immutable `HusbandryOutcomeContext` with:

- `kind`: `NEEDS_DECAY`, `HAPPINESS_DISPOSITION`, `HAPPINESS_CARE`, `HARVEST_YIELD`,
  `CULL_YIELD`, `BREEDING_COOLDOWN`, or `BREEDING_GENETICS`;
- `ownerId` and `companionId`, when known;
- `roleId` and `profileId`, when known;
- a detached `groupIds` set; and
- `productId` for product actions, when known.

The provider returns `HusbandryOutcomeModifiers`:

- `needsDecayMultiplier`, clamped to `0.25` through `1.0`;
- `happinessDispositionMultiplier`, clamped to `1.0` through `2.0`;
- `bonusOutputChance`, clamped to `0.0` through `1.0`;
- `tripleOutputChance`, clamped to `0.0` through `1.0`; and
- `breedingCooldownMultiplier`, clamped to `0.25` through `1.0`;
- `happinessHungerBonus`, `happinessThirstBonus`, `happinessPopulationBonus`, each clamped to `0.0..100.0`.
- `breedingInheritanceChanceBonus` and `harmfulMutationRerollChance`, each clamped to `0.0..1.0`.

The identity result is `(1.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)`.
The five- and eight-argument constructors remain supported, with zero new bonuses. Tamework uses identity when no
provider is active, the provider returns `null`, the provider throws, or any
returned field is not finite.

Tamework applies the needs-decay, happiness-disposition, output, and breeding
cooldown modifiers to their matching husbandry actions. Output modifiers apply
to harvest and cull results. Tamework applies the breeding multiplier to parent
cooldowns only. Tamework rolls `tripleOutputChance` only after
`bonusOutputChance` succeeds. A successful triple roll adds two output batches
instead of one.

`HAPPINESS_CARE` resolves once per mood calculation. Each returned bonus applies
only to its corresponding selected hunger, thirst or population band when that
band has `CareBonus: true`. It grants nothing in other bands or with no provider.
Flat disposition does not consume the legacy happiness-disposition multiplier.

`BREEDING_GENETICS` resolves from the parent supplying the child's inherited
owner. Unowned offspring receive identity. The inheritance bonus is added to
the configured base chance before multiplying by each trait's inheritance weight.
On a harmful mutation, the reroll chance permits one extra mutation roll and
keeps the better of the two rolls. `TwTraitConfig.Traits[].MutationPreference`
must define the preferred direction; `NONE` preserves ordinary mutation behavior.
Zero bonuses preserve the existing seeded roll sequence.

## Authority Boundary

Providers calculate modifiers only. They must not change inventories, ECS
components, world state, breeding state, or random state. Tamework performs
all chance rolls and all game-state changes. A provider must return identity
values when the context does not belong to its supported profession or rule.

## Related Pages

- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)

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

Capabilities: `HUSBANDRY_OUTCOMES`; require `HUSBANDRY_TOOL_CONTEXT` before
using captured-tool fields or the expected-yield, recovery, authorization, and
wear modifiers below. Require `HUSBANDRY_TOOL_BONUSES` before returning output
conversions or chain-harvest chances.

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
- `ownerId`, `actorId`, and `companionId`, when known. `actorId` is the
  player who started the action; authorization must use it rather than the
  companion owner;
- `roleId` and `profileId`, when known;
- a detached `groupIds` set; and
- `productId` for each resolved product action, when known; and
- `tool`, a detached `HusbandryToolContext` captured when a tool-gated interaction
  began. It is `null` for automatic, container, and other harvests that do not
  use a husbandry tool.
  It supplies the original item ID, quantity, durability, maximum durability,
  and copied BSON metadata. Providers can decode their own metadata without
  receiving a mutable live `ItemStack`.

The provider returns `HusbandryOutcomeModifiers`:

- `needsDecayMultiplier`, clamped to `0.25` through `1.0`;
- `happinessDispositionMultiplier`, clamped to `1.0` through `2.0`;
- `bonusOutputChance`, clamped to `0.0` through `1.0`;
- `tripleOutputChance`, clamped to `0.0` through `1.0`; and
- `breedingCooldownMultiplier`, clamped to `0.25` through `1.0`;
- `happinessHungerBonus`, `happinessThirstBonus`, `happinessPopulationBonus`, each clamped to `0.0..100.0`.
- `breedingInheritanceChanceBonus` and `harmfulMutationRerollChance`, each clamped to `0.0..1.0`.
- `yieldBonus`, clamped to `-1.0..10.0`, is an additive per-product bonus.
- `harvestRecoverySpeedBonus`, clamped to `-0.75..1.0`, is an additive speed
  term for the next renewable-harvest interval.
- `toolAuthorized` denies the action when false; denied actions emit no output
  and consume no tool wear.
- `toolWearMultiplier`, clamped to `0.1..1.0`, scales one actual successful
  tool use after authorization.
- `chainHarvestChance`, clamped to `0.0..1.0`, may start one additional nearby
  owned, ready manual shear. It never chains again, and the second animal uses
  its own cooldown, output action, and tool wear.
- `outputConversion` is an optional `HusbandryOutputConversion(inputItemId,
  outputItemId, inputQuantity, outputQuantity, chance)`. Valid quantities are
  `1..64` and chance is `0..1`. The conversion runs after yield resolution,
  removes input only from successful complete batches, and ignores invalid
  conversions.

`HusbandryOutcomeModifiers.identity()` supplies neutral numeric values and
allows tool use. Legacy constructors remain supported with neutral new fields.
Tamework uses identity when no provider is active. A registered provider that
returns `null` or throws denies a captured tool action; no-tool contexts retain
neutral fallback behavior. A non-finite modifier also denies tool authorization
and otherwise uses neutral numeric values.

Tamework applies the needs-decay, happiness-disposition, output, and breeding
cooldown modifiers to their matching husbandry actions. Output modifiers apply
to harvest and cull results. For `HUSBANDRY_TOOL_CONTEXT`, Tamework resolves
each existing product independently as `floor(base * max(0, 1 + sum(bonuses)))`
plus one fractional Bernoulli roll. It never creates a product that the base
drop did not resolve. Legacy `bonusOutputChance` and `tripleOutputChance` are
converted to their historical gated-roll expectation, where expected extra
copies are `bonusOutputChance * (1 + tripleOutputChance)`. New providers must
return zero for those fields and use `yieldBonus`.
Standard manual shear and item-cull actions resolve their actual product batch
before deferred completion. That accepted batch survives actor disconnects and
later tool changes. A matching moved hotbar tool receives wear; unrelated
automatic drops cannot consume a pending manual batch or wear its tool.
Harvest and cull activity receipts include the immutable captured `tool` and
`actorId` when a manual tool action supplied them, so subscribers do not need
to inspect the player's current inventory after delayed output completes.
Tamework applies the breeding multiplier to parent cooldowns only. Renewable
harvest converts the legacy duration multiplier to a speed contribution, adds
the new recovery-speed bonuses, then divides the base duration once by
`clamp(totalSpeed, .25, 2)`. This preserves legacy neutral behavior and keeps
the interval between one half and four times the base duration.

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

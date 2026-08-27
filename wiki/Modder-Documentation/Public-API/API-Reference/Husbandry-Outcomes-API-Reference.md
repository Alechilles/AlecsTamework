---
title: "Husbandry Outcomes API Reference"
order: 13
published: true
draft: false
---
# Husbandry Outcomes API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Stable API Contract (`1.0.0`)**
> This reference tracks the current `husbandryOutcomes()` contract in
> `TameworkApi`.

Capability: `HUSBANDRY_OUTCOMES`

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

- `kind`: `CARE_RESTORATION`, `PRODUCT_BONUS`, or `BREEDING_COOLDOWN`;
- `ownerId` and `companionId`, when known;
- `roleId` and `profileId`, when known;
- a detached `groupIds` set; and
- `productId` for product actions, when known.

The provider returns `HusbandryOutcomeModifiers`:

- `careRestorationMultiplier`, clamped to `1.0` through `2.0`;
- `productBonusChance`, clamped to `0.0` through `1.0`;
- `doubleBonusChance`, clamped to `0.0` through `1.0`; and
- `breedingCooldownMultiplier`, clamped to `0.25` through `1.0`.

The identity result is `(1.0, 0.0, 0.0, 1.0)`. Tamework uses it when no
provider is active, the provider returns `null`, the provider throws, or any
returned field is not finite.

`doubleBonusChance` is an independent roll after `productBonusChance`
succeeds. It changes one bonus copy into two bonus copies. Tamework applies
the breeding multiplier to parent cooldowns only.

## Authority Boundary

Providers calculate modifiers only. They must not change inventories, ECS
components, world state, breeding state, or random state. Tamework performs
all chance rolls and all game-state changes. A provider must return identity
values when the context does not belong to its supported profession or rule.

## Related Pages

- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [API Bootstrap and Capability Checks](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)

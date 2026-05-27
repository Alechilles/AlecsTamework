---
title: "Policies API Reference"
order: 7
published: true
draft: false
---
# Policies API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.6.0`)**
> This reference tracks the current `policies()` contract in `TameworkApi`.

Capability: `POLICY`

## Entry Point
`TameworkApi.policies() -> PolicyApi`

## Methods
- `Optional<OwnershipPolicyView> getOwnershipByProfileId(String profileId)`
- `Optional<OwnershipPolicyView> getOwnershipByNpcUuid(UUID npcUuid)`
- `boolean isOwner(String profileId, UUID playerUuid)`
- `ClaimAccessDecisionView evaluateClaimAccess(String profileId, UUID playerUuid)`
- `DamagePolicyDecisionView evaluateDamage(String profileId, UUID attackerPlayerUuid)`
- `PopulationCapDecisionView evaluatePopulationCap(UUID ownerUuid)`

## Decision DTOs
- `OwnershipPolicyView`: owner/tame/coop state + effective protection flags.
- `ClaimAccessDecisionView`: claim availability, allow/deny result, status, reason, and position context.
- `DamagePolicyDecisionView`: effective damage allow/deny status with ownership + optional claim decision.
- `PopulationCapDecisionView`: current cap, count, and remaining headroom.

## Notes
- Claim/damage checks are context-sensitive and can return `UNAVAILABLE`/fail-open statuses when claim systems are unavailable.
- Use this API instead of duplicating ownership/claim policy logic in downstream mods.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Enforce Ownership before Custom Command or Effect Recipe](/mod/alecs-tamework/enforce-ownership-before-custom-command-or-effect-recipe)
- [Check Population Cap before Spawning or Taming Recipe](/mod/alecs-tamework/check-population-cap-before-spawning-or-taming-recipe)



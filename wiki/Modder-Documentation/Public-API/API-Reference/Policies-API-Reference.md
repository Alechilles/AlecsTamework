---
title: "Policies API Reference"
order: 6
published: true
draft: false
---
# Policies API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `POLICY`

Entry point: `TameworkApi.policies()`.

## Methods

- `getOwnershipByProfileId(profileId)`
- `getOwnershipByNpcUuid(npcUuid)`
- `isOwner(profileId, playerUuid)`
- `evaluateClaimAccess(profileId, playerUuid)`
- `evaluateDamage(profileId, attackerPlayerUuid)`
- `evaluatePopulationCap(ownerUuid)`
- `evaluatePopulationCap(requestV2)`
- `populationAdmissions()`

## Owner cap

The legacy `evaluatePopulationCap(ownerUuid)` remains a compatibility view.
`evaluatePopulationCap(requestV2)` reads the durable canonical owner count for
an explicit global/per-world scope. Both are informational preflights.

Use `populationAdmissions()` when a custom gameplay mutation must bind a
positive acquisition to durable capacity. Its try/claim-for-apply/commit/cancel
protocol prevents another concurrent mutation from consuming the same slot.
Do not treat a read-only preflight as a reservation.

## SimpleClaims

Claim access, direct breeding limits, and tamed-NPC damage use the SimpleClaims
bridge. Damage integration errors fail open. QuestLines Claims is not a
supported policy provider.

`evaluateClaimAccess` and `evaluateDamage` return explicit unavailable,
skipped, and fail-open states. Honor the returned `allowed` value as the
decision and use `status`/`reason` to explain why a policy did or did not run;
do not infer the decision from availability alone.

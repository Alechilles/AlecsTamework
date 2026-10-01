---
title: "Policies API Reference"
order: 6
published: true
draft: false
---
# Policies API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> `populationAdmissions()` and its reservation tokens were removed. Tamework
> checks every limit itself in the step that changes a companion.

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
- `admissionProviders()`

## Owner cap

`evaluatePopulationCap(ownerUuid)` remains as a compatibility view.

`evaluatePopulationCap(requestV2)` counts every companion the owner has in the
requested scope, loaded or not. The request names no role, so population-group
limits and admission providers are not part of the answer. `pendingCount` is
`0` when the count is known.

Both calls are informational. They reserve nothing. The binding check runs when
the companion record changes, so a later change by the same owner can still be
refused. There is no public way to reserve capacity in 3.0.0.

Admin tamed spawns of managed roles go through the same limits as every other
way of getting a companion. There is no admin bypass.

## Admission providers

`admissionProviders()` returns the registry for external admission policy. See
[Admission Providers API Reference](/mod/alecs-tamework/admission-providers-api-reference).

## SimpleClaims

Claim access, direct breeding limits, and tamed-NPC damage use the SimpleClaims
bridge. Damage integration errors fail open. QuestLines Claims is not a
supported policy provider.

`evaluateClaimAccess` and `evaluateDamage` return explicit unavailable,
skipped, and fail-open states. Honor the returned `allowed` value as the
decision and use `status`/`reason` to explain why a policy did or did not run;
do not infer the decision from availability alone.

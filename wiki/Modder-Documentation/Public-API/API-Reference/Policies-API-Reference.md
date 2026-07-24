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

## Owner cap

`evaluatePopulationCap` is a read-only preflight for the simple live owner cap.
The count is based on loaded owned NPCs. It is not a durable reservation, so
another live ownership change may alter the result before a later mutation.

## SimpleClaims

Claim access, direct breeding limits, and tamed-NPC damage use the SimpleClaims
bridge. Damage integration errors fail open. QuestLines Claims is not a
supported policy provider.

`evaluateClaimAccess` and `evaluateDamage` return explicit unavailable,
skipped, and fail-open states. Honor the returned `allowed` value as the
decision and use `status`/`reason` to explain why a policy did or did not run;
do not infer the decision from availability alone.

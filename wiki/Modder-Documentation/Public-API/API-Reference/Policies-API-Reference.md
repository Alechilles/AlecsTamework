---
title: "Policies API Reference"
order: 7
published: true
draft: false
---
# Policies API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.7.0`)**
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
- `PopulationCapDecisionView evaluatePopulationCap(UUID ownerUuid)` (deprecated in `0.7.0`)
- `OwnerPopulationCapDecisionViewV2 evaluatePopulationCap(OwnerPopulationCapRequestV2 request)`
- `PopulationAdmissionApi populationAdmissions()`

## Decision DTOs
- `OwnershipPolicyView`: owner/tame/coop state + effective protection flags.
- `ClaimAccessDecisionView`: claim availability, allow/deny result, status, reason, and position context.
- `DamagePolicyDecisionView`: effective damage allow/deny status with ownership + optional claim decision.
- `PopulationCapDecisionView`: legacy owner-only cap view. It is authoritative only for `GLOBAL`; in `PER_WORLD` it returns denied, `currentCount=-1`, zero headroom, and `owner-cap-world-context-required`.
- `OwnerPopulationCapRequestV2`: owner UUID, optional world name, and exact requested slots for informational preflight.
- `OwnerPopulationCapDecisionViewV2`: scope/readiness, authoritative flag, committed and pending counts, and remaining headroom. Unknown counts use `-1`, never a misleading zero.
- `PopulationAdmissionApi`: mutation-bound owner-and-claim admission. See [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference).

## Notes
- `evaluateClaimAccess` is a raw, live SimpleClaims relationship/access diagnostic retained for compatibility; it is not the runtime damage policy switch.
- For a live target, `evaluateDamage` uses the same owner-policy resolver as runtime combat: live owner component first, command-link owner second, and persisted NPC-name owner last, followed by the role-effective ownership protection settings. A dormant target uses persisted profile policy only for owner-specific protection. It cannot prove live tamed eligibility, so its claim detail returns `UNAVAILABLE` with `live-target-required` instead of guessing. The master/protection toggles and shared SimpleClaims policy are identical in both entry points.
- Active owner/claim population rules fail closed when their index, persistence, provider, or lookup is not authoritative. SimpleClaims damage lookup/integration errors fail open.
- Plain cap evaluation is informational and cannot reserve capacity. Use the admission API before any mutation that creates, restores, transfers, or explicitly places a companion.
- Use this API instead of duplicating ownership/claim policy logic in downstream mods.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Enforce Ownership before Custom Command or Effect Recipe](/mod/alecs-tamework/enforce-ownership-before-custom-command-or-effect-recipe)
- [Check Population Cap before Spawning or Taming Recipe](/mod/alecs-tamework/check-population-cap-before-spawning-or-taming-recipe)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)



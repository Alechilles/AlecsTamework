---
title: "Population Admission API Reference"
order: 8
published: true
draft: false
---
# Population Admission API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> This API reserves owner and destination-claim capacity for a specific mutation. It is intentionally more explicit than the legacy owner-only cap query.

Capability: `POLICY`

## Entry Point

```java
PopulationAdmissionApi admissions = api.policies().populationAdmissions();
```

## Why Preflight Alone Is Not Enough

`evaluatePopulationCap(OwnerPopulationCapRequestV2)` is useful for UI and early feedback, but another operation can consume its displayed headroom before a mutation starts. It also evaluates only the owner cap; owner/world context alone cannot describe a claim-aware transition.

Use `PopulationAdmissionApi` for gameplay mutations. It reserves both applicable owner and claim capacity, records a durable operation journal, revalidates immediately before the world mutation, and commits or cancels the exact reservation afterward.

## Lifecycle

1. `tryAdmit(request)` asynchronously prepares a durable reservation.
2. If the result is `RESERVED`, keep its opaque `PopulationAdmissionToken`.
3. On the correct world thread, immediately before mutation, call `claimForApply(token)`.
4. Mutate only when that result is `APPLYING`.
5. After the live mutation is confirmed, call asynchronous `commit(token)`.
6. If the mutation does not happen or fails, call asynchronous `cancel(token)`.

Every reserved token must end in `commit` or `cancel`. Do not persist, fabricate, decode, or reuse token fields yourself. Expired/unclaimed reservations are closed by bounded cleanup, and integrations may invoke `cleanupExpired()` during their own maintenance.

## Methods

- `CompletionStage<PopulationAdmissionDecision> tryAdmit(PopulationAdmissionRequest request)`
- `CompletionStage<PopulationAdmissionDecision> tryAdmitV2(PopulationAdmissionRequestV2 request)`
- `CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(PopulationBatchAdmissionRequest request)`
- `PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token)`
- `CompletionStage<PopulationAdmissionDecision> commit(PopulationAdmissionToken token)`
- `CompletionStage<PopulationAdmissionDecision> cancel(PopulationAdmissionToken token)`
- `CompletionStage<Integer> cleanupExpired()`

Preparation, commit, cancellation, and cleanup may perform durable SQLite work. Never block a Hytale world thread waiting for their stages.

`tryAdmitV2` adds an exact target role and ownership world so Tamework can
resolve every applicable population group. It requires
`POPULATION_GROUPS`; the API 0.8-compatible default denies rather than silently
bypassing group capacity. Callers never supply their own group list.

## `PopulationAdmissionRequest`

Each request describes one canonical or provisional companion profile and exactly one slot:

- `identity`: canonical profile ID, provisional profile ID, or idempotency key.
- `currentNpcUuid`: current live identity when the operation requires an existing profile.
- `expectedProfileRevision`: committed revision, or `NEW_PROFILE_REVISION` (`-1`) for a new profile.
- `oldOwnerUuid` / `newOwnerUuid`: compare-and-transition owner context.
- `source` / `destination`: exact `PopulationAdmissionLocation(worldName, chunkX, chunkZ)` context.
- `operation`: `NEW_OWNERSHIP`, `OWNER_TRANSFER`, `OWNER_CLEAR`, `RESTORE`, `REHOME`, `LIFECYCLE_CHANGE`, `LEGACY_ADOPTION`, `BREEDING`, or `ADMIN_FORCE`.
- `exactSlots`: must be `1`; use a batch for several profiles.
- `forcePolicy`: normally `ENFORCE`. `ADMIN_OVERRIDE` is valid only with `ADMIN_FORCE`; `ENGINE_RELOCATION` is valid only with `REHOME`.
- `targetLifecycle`: `ACTIVE`, `UNLOADED`, `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, `LOST`, `RESTORING`, `UNKNOWN_DORMANT`, or `RELEASED`.

The constructor rejects incomplete or contradictory transitions. For example, a committed profile needs a canonical ID and revision, an active lifecycle needs a destination, transfer owners must differ, and `REHOME` must preserve the owner.

## Identity and Idempotency

The counted unit is one canonical profile, not a live NPC UUID. Historical/replacement UUIDs and repeated retries must resolve to that profile.

- Use the canonical profile ID and expected revision for an existing companion.
- For a new companion, use a stable provisional ID or idempotency key and `NEW_PROFILE_REVISION`.
- Reuse the same identity/idempotency key when retrying the same logical operation.
- Never generate a new key for each callback retry; that describes a new operation.

Duplicate active representations are denied even if owner headroom remains.

## Decisions

`PopulationAdmissionDecision` exposes:

- `status`: `RESERVED`, `APPLYING`, `COMMITTED`, `CANCELED`, `DEGRADED`, `DENIED`, or `UNAVAILABLE`.
- `reason`: diagnostic reason text.
- `token`: present only for `RESERVED`, `APPLYING`, and `COMMITTED`.
- `readiness`: `LOADING`, `RECONCILING`, `READY`, `DEGRADED`, or `UNAVAILABLE`.
- `committedCount` / `pendingCount`: owner counts where known; `-1` means unknown.

`accepted()` is true only for `RESERVED`, `APPLYING`, and `COMMITTED`. Do not treat `DEGRADED` or `UNAVAILABLE` as an allow.

The token carries operation/reservation IDs, monotonic expiry, settings revision, provider-generation token, and readiness. These values bind every stage to the context that prepared it; live settings/plugin changes affect the next operation, not one already in flight.

## Batch Admissions

`PopulationBatchAdmissionRequest` currently accepts ordered `BREEDING` units only. Every unit must have a unique explicit identity and represents exactly one child profile. Maximum batch size is 256.

- `EXACT`: reserve all requested children or none.
- `UP_TO`: reserve the safe ordered prefix and return `RESERVED_PARTIAL` when only part fits.

The batch decision reports requested/admitted counts plus each admitted unit's decision/token. Claim and commit/cancel every admitted unit independently so partial spawn failures release only unused capacity.

## Counting Semantics

Owner slots count every non-null owner across `ACTIVE`, `UNLOADED`, `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, `LOST`, `RESTORING`, and conservatively classified dormant profiles. `PER_WORLD` uses the retained authoritative ownership world, including while dormant. Transfers reserve the destination before releasing the source.

Claim occupancy counts owned `ACTIVE` and durably located `UNLOADED` profiles. `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, and `LOST` do not occupy a claim; restoring them is a `+1` physical admission even at their prior location. Same-location rehydration is zero-delta only for the already-counted `UNLOADED` state.

Claim limits gate explicit placement. Natural movement is observed and counted but never blocked. Movement can create an over-cap claim, which blocks later positive admissions until occupancy falls.

## Failure and Force Policy

- Positive owner or claim admissions fail closed while loading, reconciling, degraded, or using an unavailable/invalid active provider.
- Existing companions are not deleted to satisfy a cap. Reconciliation adopts legacy over-cap profiles and later positive admissions remain blocked.
- `ADMIN_OVERRIDE` is explicit and auditable; do not use it as a normal integration fallback.
- `ENGINE_RELOCATION` is reserved for unavoidable rehoming of an existing profile. It may create an observed over-cap destination without stranding/deleting the companion, then blocks later admissions.

## Related Pages

- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)
- [Check Population Cap before Spawning or Taming Recipe](/mod/alecs-tamework/check-population-cap-before-spawning-or-taming-recipe)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)

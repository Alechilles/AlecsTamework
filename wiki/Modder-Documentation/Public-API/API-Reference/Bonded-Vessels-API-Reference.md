---
title: "Bonded Vessels API Reference"
order: 15
published: true
draft: false
---
# Bonded Vessels API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> Call this API only when `BONDED_VESSELS` is advertised. Otherwise
> `TameworkApi.bondedVessels()` returns a fail-closed unavailable facade.

Tamework 3.0.0 includes the production vessel runtime, but advertises this
capability only after pending-operation recovery and every exact evidence and
mutation authority reports ready. Those authorities cover held inventory,
projection evidence, canonical profile state, unified population admission,
and world projection changes. If any dependency is missing or degraded, the
capability remains absent and this facade fails closed.

Capability: `BONDED_VESSELS`

## Entry point

`TameworkApi.bondedVessels() -> BondedVesselsApi`

The binding, not item metadata, is the durable authority. One binding links one
canonical `profile_id` to a generation-fenced vessel projection.

## Reads

- `getByBindingId(UUID bindingId)`
- `getByProfileId(String profileId)`
- `readiness()`
- `validateProjection(BondedVesselProjectionValidationRequest request)`
- `resolveHeldItemProjection(BondedVesselHeldItemProjectionRequest request)`
- `findOperation(String callerNamespace, String idempotencyKey)`

Projection validation compares binding ID, generation, projection kind, and
fingerprint. Unknown or stale evidence is non-authoritative and must not spawn,
store, repair, release, or replace a companion.

Held-item resolution is the supported way to begin a repair or other
item-driven transition when the plugin starts only with the actor and exact
held stack. The request supplies actor UUID, source item/holder/container/slot,
inventory revision, fingerprint, and required vessel state. Only a `VALID`
result with `authoritative() == true` may seed a transition request. Diagnostic
vessel data attached to `NOT_FOUND`, `NOT_BONDED`, `SOURCE_CHANGED`,
`OWNER_MISMATCH`, `STATE_MISMATCH`, `STALE_GENERATION`, `AMBIGUOUS`,
`QUARANTINED`, or `UNAVAILABLE` does not grant mutation authority.

## Transitions

Supported transition intents are `SUMMON`, `STORE`,
`REPAIR_DEAD_TO_STORED`, and `RELEASE`.

1. Call `prepareTransition(request)` with a stable caller namespace and
   idempotency key, expected generation/profile revision, actor, binding, and
   exact source evidence.
2. On the owning world thread, call `claimForApply(token)` immediately before
   the mutation.
3. Call asynchronous `commit(token)` after the authoritative apply step, or
   `cancel(token)` when the mutation did not begin.
4. After restart or loss of a process-local token, call
   `resumeTransition(originalRequest)` and revalidate the same source evidence.

Do not persist or synthesize `BondedVesselTransitionToken` values. Durable
operation identity is the caller namespace plus idempotency key.

## Exact source evidence

`BondedVesselTransitionContext` carries the source item ID, holder evidence ID,
container path, slot, inventory revision, item fingerprint, and transition-
specific NPC/destination context. A moved, replaced, copied, or stale item
fails closed.

## Operation states and compensation

`findOperation(...)` exposes restart-visible states:

- `PREPARED`
- `APPLYING`
- `APPLIED`
- `COMMITTED`
- `CANCELED`
- `TERMINAL_DENIED`
- `QUARANTINED`

`APPLIED` is not a denial and is not safe to compensate; Tamework may still
need to close the source projection. Query/resume the same operation. Only a
proven `TERMINAL_DENIED` before apply can authorize a caller-owned refund.
Unavailable, not-found, timeout, or quarantine results never authorize a new
idempotency key or speculative compensation.

## Threading

Preparation, resume, commit, cancel, and operation lookup may perform durable
work. Do not block a Hytale world thread waiting on their completion stages.
Live ECS/world mutation remains on the owning world thread.

## Related pages

- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)

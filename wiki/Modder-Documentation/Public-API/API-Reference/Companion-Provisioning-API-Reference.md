---
title: "Companion Provisioning API Reference"
order: 17
published: true
draft: false
---
# Companion Provisioning API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> Call this API only when `COMPANION_PROVISIONING` is advertised. Otherwise
> `TameworkApi.companionProvisioning()` returns the unavailable facade.

Tamework 3.0.0 includes the production provisioning coordinator and advertises
this capability only after population and provisioning recovery, dormant
profile creation, and the requested active-projection path are authoritative.
If any required authority is unavailable, the capability remains absent and
this facade fails closed.

Capability: `COMPANION_PROVISIONING`

## Entry point

`TameworkApi.companionProvisioning() -> CompanionProvisioningApi`

This authority creates exactly one generic canonical companion profile for an
idempotent caller operation. It is intended for rituals, quests, grants, and
other integrations that create a companion without capturing an existing NPC.

## Methods

- `getByProfileId(String profileId)`
- `getByOrigin(String callerNamespace, String idempotencyKey)`
- `provision(CompanionProvisioningRequest request)`
- `transition(ProvisionedCompanionTransitionRequest request)`
- `findOperation(String callerNamespace, String idempotencyKey)`

## Provision request

The request includes caller namespace, stable idempotency key, optional
correlation ID, owner UUID, role ID, ownership world, optional display/home
data, and an expected policy revision. `CURRENT_POLICY_REVISION` (`-1`) resolves
the current revision once; retry does not re-resolve policy.

Initial disposition is either:

- `PROVISIONED_DORMANT`: creates the canonical profile without a live
  destination; or
- `ACTIVE`: also requests a destination projection through normal population
  admission.

Role-based population groups are resolved by Tamework. Callers do not supply a
group override.

## Partial success

Dormant profile creation commits before optional active projection. If
projection fails afterward, the result is `PARTIAL_DORMANT` with
`FAILED_RECOVERABLE`. The profile is real and consumes its owned capacity.
Retry the same caller namespace/idempotency key to resume it; never allocate a
replacement profile.

Accepted result statuses include `PROVISIONED_ACTIVE`,
`PROVISIONED_DORMANT`, `PARTIAL_DORMANT`, `ALREADY_PROVISIONED`,
`TRANSITIONED`, and `ALREADY_TRANSITIONED`.

## Existing-profile transitions

`ProvisionedCompanionTransitionRequest` supports `ACTIVATE`,
`REVIVE_DORMANT`, and `REVIVE_ACTIVE`. Requests compare the expected profile
revision and run through normal population/group/claim admission.

## Durable operation lookup

`findOperation(...)` reports nonterminal, terminal, and partial states:

- `PREPARING`, `PREPARED`, `APPLYING`
- `DORMANT_COMMITTED`, `PROJECTING`
- `COMMITTED`, `PARTIAL_DORMANT`
- `CANCELED`, `TERMINAL_DENIED`, `QUARANTINED`

Unknown/unavailable operation state never authorizes a second idempotency key.

## Events

- `CompanionProvisionedEvent`
- `ProvisionedCompanionDeathRecordedEvent`
- `ProvisionedCompanionRevivedEvent`

These events are post-commit notifications for canonical state. A listener
must be idempotent by operation ID and must not block the emitting thread.

## Related pages

- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)

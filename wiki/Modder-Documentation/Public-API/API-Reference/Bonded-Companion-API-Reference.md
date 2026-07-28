---
title: "Bonded Companion API Reference"
order: 19
published: true
draft: false
---
# Bonded Companion API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `BONDED_COMPANIONS`

Entry point: `TameworkApi.bondedCompanions()`.

`BondedCompanionApi` is the dedicated authority for durable companion profiles
whose world NPCs are temporary projections. It is intentionally separate from
generic command-family rosters, timed summoning, companion provisioning,
profile data, paid command revival, population groups, and replacement
persistence.

Use this surface only for a feature designed around the bonded lease model. It
does not replace permanent world animals, coops, ordinary linked companions, or
generic command rosters.

## Capability and availability

Check both the advertised capability and the surface's own availability:

```java
if (!api.getCapabilities().contains(TameworkApiCapability.BONDED_COMPANIONS)) {
    return;
}

BondedCompanionApi bonded = api.bondedCompanions();
BondedCompanionAvailability availability = bonded.availability();
if (!availability.available()) {
    // Show or log availability.reason(); do not take a player resource.
    return;
}
```

The fallback is non-null and returns explicit `UNAVAILABLE` results. Bonded
availability reflects the bonded authority and database readiness; it is not
inferred from generic replacement-persistence readiness. World and placement
context are validated by the individual operations, which may return
`WORLD_UNAVAILABLE` while the bonded API itself remains available.

## Lifecycle and identity

A bonded profile has exactly three public states:

- `STORED`: durable snapshot exists and there is no active projection;
- `ACTIVE`: one exact live projection is associated with a lease; or
- `DEAD`: a confirmed death was recorded and paid revival is required.

Dismissal, expiration, logout, world transfer, missing projection recovery, and
duplicate cleanup resolve to `STORED`. They never create `UNLOADED`, `LOST`, or
`CAPTURED` bonded states. Only positively confirmed death creates `DEAD`.
Successful revival transitions `DEAD` to `STORED`; it does not summon.

The stable profile ID is the only durable companion identity exposed to
callers. A live NPC UUID is optional lease evidence and must never be persisted
as the caller's roster key or UI-card identity.

## Methods

- `availability()` returns bonded-only readiness and a stable denial reason.
- `list(ownerUuid, rosterId)` returns the owner's profile-first roster view.
- `findCapture(ownerUuid, rosterId, sourceNpcUuid)` returns restart-safe proof
  of one successful source capture when that proof exists.
- `provision(request)` creates one deterministic stored profile.
- `summon(request)` creates one exact leased projection.
- `store(request)` snapshots and retires one exact active projection.
- `quoteRevive(request)` returns the current ordered revive recipe and observed
  owned quantities.
- `revive(request)` commits a revision-fenced, paid `DEAD -> STORED`
  transition.
- `getExtensionData(key)` reads one owner/profile/namespace document.
- `compareAndSetExtensionData(update)` performs a revision-fenced JSON update.
- `subscribe(listener)` emits compact bonded profile-change events.

All query and mutation methods return a `CompletableFuture` containing a
`BondedCompanionResult<T>`.

## Results

Every result has a stable code, an optional value, and a reason for every
non-success result. Current codes are:

- `SUCCESS`
- `UNAVAILABLE`
- `NOT_FOUND`
- `NOT_OWNER`
- `INVALID_STATE`
- `REVISION_CONFLICT`
- `POLICY_DENIED`
- `WORLD_UNAVAILABLE`
- `VALIDATION_FAILED`
- `INTERNAL_FAILURE`

Do not treat an exceptional completion, null value, or absent reason as a
domain result. Do not retry an uncertain mutation with a new idempotency key.

## Profile view

`BondedCompanionProfileView` includes:

- profile, owner, roster, family, and role identity;
- display name, species, and gender;
- optimistic revision and one of the three bonded states;
- policy-derived `summonAvailable`, `storeAvailable`, and `reviveAvailable`;
- complete snapshot presentation data;
- an active lease only while the state is `ACTIVE`;
- summon/revive timing and the current revive quote when available.

Build UI cards from this durable view first. A matching live projection may
refresh volatile data, but it must not be required to show the companion's
name, species, gender, health, attributes, extension details, or action buttons.

## Provisioning

`BondedCompanionProvisionRequest` contains caller namespace, stable
idempotency key, owner, roster, optional explicit family, role, display fields,
and initial presentation data. Supplying a family selects that exact policy.
When it is omitted, the role must resolve to exactly one family within the
roster; ambiguous or missing role-to-family resolution fails closed.

Provisioning always creates a `STORED` profile. It does not create a generic
dormant profile, command-family membership, population-group row, timed lease,
or live projection. Use a separate `summon` request when a projection is
actually wanted.

## Summon and store

`BondedCompanionActionRequest` contains caller namespace, idempotency key,
owner, roster, stable profile ID, expected profile revision, optional world
key, and optional `BondedCompanionActionContext`.

Summon requires a world key plus a matching world-qualified
`BondedCompanionPlacement`. Tamework rechecks owner, roster, family policy,
state, expected revision, cooldown, family active capacity, role, snapshot, and
world context before creating the projection. A session duration of `0` creates
an unlimited lease; otherwise the lease carries a signed expiry timestamp.

Store requires the active lease's world context. It snapshots the projection
before retirement, merges available live state without deleting unavailable
optional state, clears the lease, starts the configured summon cooldown, and
returns the updated `STORED` profile.

## Revival and payment context

Revive policy is an ordered AND recipe: every configured item line is required.
`quoteRevive` is read-only. The quote includes the policy revision, cost lines,
owned/required quantities, and affordability.

`revive` accepts a `BondedCompanionReviveRequest` containing the action and the
quoted policy revision. The action context must supply the live inventory
authority used to reserve the complete recipe atomically. Tamework's linked
panel supplies this context for normal gameplay.

The operation commits the entire recipe once or charges nothing. A rejected
mutation refunds its exact escrow, successful persistence consumes it, and an
ambiguous interrupted settlement remains contained for recovery. Revival
returns the same profile to `STORED`; callers must issue a later summon.

## Extension data

`BondedCompanionExtensionDataKey` is owner-, profile-, and namespace-qualified.
`BondedCompanionExtensionDataUpdate` adds caller namespace, idempotency key,
JSON payload, and expected revision. Use
`BondedCompanionExtensionDataUpdate.MISSING_REVISION` only to create a document
that must not already exist.

Extension updates cannot cross owners or profiles. On
`REVISION_CONFLICT`, reload the document, merge only integration-owned fields,
and retry with a stable operation identity appropriate to that new revision.
Do not use generic `ProfileDataApi` for bonded-profile extension data.

## Events

`BondedCompanionChangedEvent` contains profile ID, owner, roster, old state,
new state, revision, and reason. It deliberately omits the full snapshot. Call
`list` when a consumer needs the current complete view, and always close the
subscription handle.

Successful captures also publish `BondedCompanionCaptureResolvedEvent` through
`TameworkApi.events()`. Live event delivery is not a historical cursor; use
`findCapture` for restart-safe capture proof.

## Persistence and diagnostics boundary

Bonded data is stored in the universe-scoped
`Tamework/Data/bonded-companions.sqlite` database. Its schema, operations,
cleanup intents, leases, and readiness are isolated from generic replacement
persistence. Integrations must not open this file or depend on its tables.

`/tw debugdb status`, `/tw debugdb detail`, and `/tw debugdb export` include
aggregate bonded readiness and counts. The export member is
`bonded-companions.json` and is restricted to readiness, schema version, state
counts, lease count, pending bounded-cleanup count, and a fixed failure
category. It does not expose owners, profile IDs, NPC UUIDs, snapshots, or
extension payloads.

## Related pages

- [TwBondedCompanionRosterConfig Reference](/mod/alecs-tamework/twbondedcompanionrosterconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
- [Timed Summoning API Reference](/mod/alecs-tamework/timed-summoning-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [Paid Command Revival API Reference](/mod/alecs-tamework/paid-command-revival-api-reference)

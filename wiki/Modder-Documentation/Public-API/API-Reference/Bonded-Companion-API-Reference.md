---
title: "Bonded Companion API Reference"
order: 19
published: true
draft: false
---
# Bonded Companion API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> Bonded companions are ordinary companion records with a bonded flag and a
> roster. They no longer use a separate database.

Capability: `BONDED_COMPANIONS`

Entry point: `TameworkApi.bondedCompanions()`.

`BondedCompanionApi` manages companions that live in a player's bonded roster
and appear in the world only while summoned. Use it for a feature built around
that model. It does not replace permanent world animals, coops, or ordinary
command-linked companions.

## Capability and availability

Check the capability and the surface's own availability:

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

The fallback is non-null and returns `UNAVAILABLE` results.

The bonded runtime module depends on generic companion persistence. A server
that turns generic persistence off also turns bonded companions off. There is
no bonded-only mode.

## Threading

Every method returns at once and may be called from any thread. A future
completes on the thread that finishes the work, which is usually the companion
writer thread and not a world thread. A continuation must not block and must
not read or change entities, components, or worlds. Hop to the owning world
with `world.execute(...)` first.

## States

A bonded profile has three public states:

- `STORED`: in the roster with no body in a world;
- `ACTIVE`: summoned, with one body and a lease; or
- `DEAD`: a confirmed death was recorded.

A companion whose body vanished without a confirmed death is listed as
`STORED` and can be summoned again. Dismissal, session expiry, owner logout,
and owner world change store the companion. An owner's death does not store
it.

The stable profile ID is the only durable identity. A live NPC UUID is lease
evidence and must not be saved as a roster key or UI card identity.

A companion whose role matches no family, or more than one family, of its
roster is still listed. Its `familyId` is `tamework:unresolved`, every action
is unavailable, and it counts toward no family limit.

## Methods

- `availability()` returns readiness and a reason when unavailable.
- `list(ownerUuid, rosterId)` returns the owner's companions in that roster,
  ordered by profile ID.
- `findCapture(ownerUuid, rosterId, sourceNpcUuid)` returns the stored proof
  of one capture.
- `provision(request)` adds one stored companion with no body.
- `summon(request)` brings a stored companion into the world.
- `store(request)` snapshots and removes an active companion.
- `abandon(request)` removes a companion for good. There is no recovery.
- `quoteRevive(request)` returns the revive price and remaining cooldown.
- `revive(request)` charges the price and brings a dead companion back.
- `updateTalents(request)` buys a talent or resets talents.
- `getExtensionData(key)` reads one caller namespace's value.
- `compareAndSetExtensionData(update)` writes it when the revision matches.
- `subscribe(listener)` delivers `BondedCompanionChangedEvent`.

All query and mutation methods return a `CompletableFuture` holding a
`BondedCompanionResult<T>`.

## Results

Every result has a code, an optional value, and a reason for every
non-success result. Codes:

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

### Reason strings

Reason strings are kept from API 2.x because integrations and Tamework's own
panel match on them. Current reasons:

| Reason | Code | Meaning |
| --- | --- | --- |
| `bonded-companion-authority-unavailable` | `UNAVAILABLE` | The bonded runtime is not active. |
| `bonded-companion-authority-closed` | `UNAVAILABLE` | Tamework is shutting down. |
| `bonded-talent-updates-unavailable` | `UNAVAILABLE` | Talent changes are not wired in this runtime. |
| `bonded-profile-not-found` | `NOT_FOUND` | No such companion in that roster. |
| `bonded-capture-evidence-not-found` | `NOT_FOUND` | No capture proof for that source NPC. |
| `bonded-transition-not_owner` | `NOT_OWNER` | The companion belongs to another player. |
| `bonded-transition-invalid_state` | `INVALID_STATE` | The action does not fit the current state. |
| `bonded-summon-already-live` | `INVALID_STATE` | Summon of an active companion. |
| `bonded-transition-revision_conflict` | `REVISION_CONFLICT` | `expectedRevision` is stale on summon, store, or revive. |
| `bonded-profile-revision-conflict` | `REVISION_CONFLICT` | `expectedRevision` is stale on abandon or a talent change. |
| `bonded-store-not-committed` | `REVISION_CONFLICT` | A newer change replaced the store. |
| `bonded-revive-quote-stale` | `REVISION_CONFLICT` | The roster policy changed after the quote. |
| `bonded-extension-revision-conflict` | `REVISION_CONFLICT` | The extension revision is stale. |
| `bonded-transition-role_not_allowed` | `POLICY_DENIED` | The role resolves to no single family, or not to the requested family. |
| `bonded-transition-feature_disabled` | `POLICY_DENIED` | The family has this feature turned off. |
| `bonded-transition-cooldown_active` | `POLICY_DENIED` | The summon or revive cooldown has not passed. |
| `bonded-transition-active_capacity_reached` | `POLICY_DENIED` | The family's `MaximumActive` is reached, or a population group limit refused the summon or revive. |
| `bonded-transition-owned_capacity_reached` | `POLICY_DENIED` | The family's `MaximumOwned` or an owned population limit is reached. |
| `bonded-policy-denied` | `POLICY_DENIED` | Another policy refused, for example an admission provider. |
| `bonded-revive-payment-unavailable` | `POLICY_DENIED` | The revive has a price and the request has no inventory context. |
| `bonded-revive-payment-insufficient` | `POLICY_DENIED` | The price could not be taken. Nothing was charged. |
| `bonded-placement-context-required` | `WORLD_UNAVAILABLE` | No placement, or a placement in another world than the request names. |
| `bonded-projection-placement-unavailable` | `WORLD_UNAVAILABLE` | The body could not be added to the world. |
| `bonded-talent-body-unavailable` | `WORLD_UNAVAILABLE` | A talent change on an active companion whose body is not loaded. |
| `bonded-request-invalid` | `VALIDATION_FAILED` | A reserved or invalid namespace, invalid JSON, or an idempotency key already used for another owner or roster. |
| `bonded-talents-disabled` | `VALIDATION_FAILED` | The companion has no talent tree. |
| `bonded-level-data-unavailable` | `VALIDATION_FAILED` | The companion has no level data yet, for example it was never summoned. |
| `bonded-talent-purchase-rejected`, `bonded-talent-reset-rejected` | `VALIDATION_FAILED` | The tree, level, or points do not allow the change. |
| `bonded-snapshot-invalid` | `INTERNAL_FAILURE` | The stored snapshot is missing or unreadable. |
| `bonded-operation-failed` | `INTERNAL_FAILURE` | The save failed or the operation threw. The change was undone where possible. |

Do not treat an exceptional completion, a null value, or an absent reason as a
domain result.

## Profile view

`BondedCompanionProfileView` includes:

- profile, owner, roster, family, and role identity;
- display name (`species` and `gender` are null in 3.0.0);
- `revision` and one of the three states;
- `summonAvailable`, `storeAvailable`, and `reviveAvailable`;
- presentation data;
- a lease only while the state is `ACTIVE`; and
- the summon cooldown end and, for a dead companion, the revive quote.

`revision` is the record generation. Pass it as `expectedRevision`. It grows
when the companion changes state. A talent change does not change it.
Extension data has its own revision per value.

The lease token is the generation as text. The lease expiry is `0` for an
unlimited session.

`reviveAvailable` also needs a free active place in the family, because a
revived companion comes back active.

Presentation data in `list` comes from the last saved summary: role, level,
XP, health, happiness, hunger, thirst, and talent points when the companion has
them. A provisioned companion that was never summoned has none of these.

## Provisioning

`BondedCompanionProvisionRequest` contains caller namespace, idempotency key,
owner, roster, role, optional family, and display fields. When the family is
omitted the role must resolve to exactly one family of the roster. An
ambiguous or unknown role is refused. The family is derived from the role and
the roster config on every read. It is not stored.

`provision` adds a `STORED` companion with no body and no snapshot. The future
completes after the owner's file is written. The caller namespace follows the
extension namespace rules below.

- The caller namespace and idempotency key identify the request. A repeat
  returns the companion the first request made, for as long as it exists.
  After that companion is abandoned, the same request makes a new one.
- The family's `MaximumOwned` and the ordinary owner and population-group
  limits are checked in the step that adds the record.
- A request with no display name uses the species as the name.
- The request's gender and presentation data are not stored.
- When the save fails the companion is withdrawn and the request can be
  repeated.

### First summon

The first summon of a provisioned companion builds the body from its role,
then stamps and snapshots it. Every later summon uses the snapshot. When no
body is added the record goes back as it was and can be summoned again.

## Capture into storage

A spawner item whose success disposition is `STORE_BONDED_COMPANION` stores the
captured NPC directly in the capturing player's roster as `STORED`. No item is
given. The capturing player always owns the result. One source item is spent
before the save and given back when the save does not go through.

The captured companion starts with the family's summon cooldown.

The capture proof is kept on the companion's record. `findCapture` finds it for
as long as the companion exists and not after it was abandoned.

## Summon and store

`BondedCompanionActionRequest` contains caller namespace, idempotency key,
owner, roster, profile ID, expected revision, optional world key, and optional
`BondedCompanionActionContext`.

Summon needs a world key and a `BondedCompanionPlacement` in that same world.
Tamework checks owner, roster, state, revision, family policy, cooldown, and
the family's active limit. The active limit is checked again in the step that
saves the change, so two summons at once cannot both pass it. The companion
must also pass the ordinary population limits and any admission provider.

An active companion with a loaded body is refused as
`bonded-summon-already-live`. An active companion whose body is not loaded
(its chunk is unloaded, or a server stop left it without one) can be summoned
again. It already holds its active place.

The session length comes from the family's `SessionDurationSeconds` with the
companion's talent modifiers. `0` is unlimited. When the session ends the
companion is stored.

Store snapshots the body, removes it, and starts the family's
`SummonCooldownSeconds`.

## Death and revival

A confirmed death moves the companion to `DEAD` and starts the family's
`ReviveCooldownSeconds`.

`quoteRevive` is read-only. It returns the policy revision, the cost lines, the
remaining cooldown, and owned quantities when the request carries an inventory
context. A companion that is not dead, or whose family has revival off, gets a
disabled quote with no costs.

`revive` takes a `BondedCompanionReviveRequest` with the action and the quoted
policy revision. It needs a placement, like summon.

1. The whole price is charged through the context's inventory.
2. The companion is restored at the placement as `ACTIVE`, with a session
   timer. It takes an active place in its family.
3. When the restore does not bring the companion back, the charge is refunded.

A family with no price revives for free. Tamework's linked panel supplies the
inventory context for normal play.

This differs from API 2.x, where a revive returned the companion to `STORED`.

## Extension data

`BondedCompanionExtensionDataKey` names an owner, a profile, and a namespace.
Each namespace keeps one JSON value per companion.

- The namespace may not contain `/`.
- `tamework` and `Alechilles:Tamework` are reserved, in any letter case.
- The payload must be valid JSON of at most 1,048,576 characters. It is stored
  as sent.
- Use `BondedCompanionExtensionDataUpdate.MISSING_REVISION` (`-1`) to create a
  value that must not exist yet.
- The first value has revision `0`. Each write adds one.
- A repeat of a request whose value is already the current one succeeds.
- `SUCCESS` means the value is in the companion store and visible to reads.
  Do not assume it is on disk at that moment. The writer saves it on its next
  flush.

On `REVISION_CONFLICT`, read the value again, merge your own fields, and retry
with the new revision.

The value goes away when the companion is abandoned.

## Events

`BondedCompanionChangedEvent` contains profile ID, owner, roster, old state,
new state, revision, and reason. It has no snapshot. Call `list` when you need
the full view, and always close the subscription handle.

Reasons: `provisioned`, `summoned`, `revived`, `stored`, `lost`, `died`,
`abandoned`, `old_age`, `released`, `updated`, and `talents-updated`.

- A companion captured into storage is reported as `stored` with no old state.
- `lost` reports a vanished body. The state stays `STORED`.
- For `abandoned`, `old_age`, and `released` the new state repeats the old
  state. The companion is gone.
- Position refreshes of a live body are not reported.

Listeners run after the change is applied, on the thread that made it, with no
index lock held. That thread is often a world thread, so a listener must not
block. There is no order guarantee between changes made on different threads.
A change that is undone is followed by an event for the compensating change.

A capture into storage also publishes `BondedCompanionCaptureResolvedEvent`
and `CaptureAttemptResolvedEvent` through `TameworkApi.events()` once the
capture is saved, even when the body vanished before it could be removed.
Events are live notifications and are not replayed. Use `findCapture` after a
restart.

## Storage

Bonded companions are stored with every other companion in the companion
store. Integrations must not read or write those files.

## Related pages

- [TwBondedCompanionRosterConfig Reference](/mod/alecs-tamework/twbondedcompanionrosterconfig-reference)
- [TwCommandItemConfig Reference](/mod/alecs-tamework/twcommanditemconfig-reference)
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)
- [Admission Providers API Reference](/mod/alecs-tamework/admission-providers-api-reference)

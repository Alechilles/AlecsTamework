---
title: "Profiles API Reference"
order: 3
published: true
draft: false
---
# Profiles API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> Profiles are read from the companion store. Snapshot reads return a small
> description, not the full body snapshot.

Capability: `PROFILES`

## Entry Point
`TameworkApi.profiles() -> NpcProfilesApi`

## Methods
- `Optional<String> resolveProfileId(UUID npcUuid)`
- `Optional<NpcProfileView> getByProfileId(String profileId)`
- `Optional<NpcProfileView> getByNpcUuid(UUID npcUuid)`
- `Optional<String> getActiveSnapshot(String profileId, String snapshotType)`
- `Set<String> listActiveSnapshotTypes(String profileId)`

## `NpcProfileView`
- `profileId`
- `currentNpcUuid`
- `ownerUuid`
- `ownerName`
- `roleId`
- `displayName`
- `customName`
- `tamed`
- `coopId`
- `coopSlot`
- `toolIds`
- `activeSnapshotTypes`
- `lastUpdatedAtMs`

## Notes
- Values are detached immutable snapshots (`record` + defensive copies).
- Every read is synchronous and safe from any thread.
- A profile ID is a UUID as text. A blank or unparsable ID, and a released
  companion, read as "no profile".
- Prefer `profileId` for long-lived references; NPC UUIDs can change.
- `tamed` is true for every companion that has an owner and is not released.
- `coopId` is set only while the companion is housed. It is the coop's
  position as `world:x:y:z`, not an asset ID.

## Active snapshots

A companion has at most one active snapshot type. It comes from where the
companion is:

| Location | Snapshot type |
| --- | --- |
| In a capture item | `capture` |
| Dead | `death` |
| Lost | `lost` |
| Anywhere else | none |

`getActiveSnapshot(profileId, type)` returns a small JSON description built
from the companion's record: `snapshotType`, `profileId`, `roleId`,
`createdAtMs`, `updatedAtMs`, and, when known, `ownerUuid`, `displayName`,
`customName`, `cause`, `diedAtMs`, and `reviveAvailableAtMs`. The full body
snapshot is not exposed. Do not build a second lifecycle state from it.

## Saved owner trait pages

`getOwnedTraitSnapshots(UUID ownerUuid, int offset, int limit)` returns
`CompletionStage<Optional<List<OwnedTraitSnapshot>>>`. The built-in implementation
accepts a non-null owner, offset >= 0, and limit 1..64. It reads saved state without
loading NPCs. Rows are sorted by profile ID and include captured, stored, housed, lost, and
live companions, but exclude released and dead companions. This list is not an admission-capacity count.

An empty optional means unavailable; a present empty list means no matching rows.
Each immutable row exposes `profileId`, `roleId`, `displayName`, `traitConfigId`,
`traits`, `traitDataAvailable`, and `snapshotCreatedAtMs`. Missing or invalid trait
data remains explicitly unavailable. Values describe the latest decodable saved
full-state snapshot, not necessarily current live values.

The method has a default unavailable implementation for older API implementers.
In 3.0.0 the stage is already complete when it is returned. Still treat it as
asynchronous: return to the original world and revalidate the player/page
before changing UI. Pagination
can shift when ownership changes; it is not a durable cursor.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Read Saved Home Position and Show a Waypoint Recipe](/mod/alecs-tamework/read-saved-home-position-and-show-a-waypoint-recipe)
- [Build Companion Inspector UI Card Recipe](/mod/alecs-tamework/build-companion-inspector-ui-card-recipe)



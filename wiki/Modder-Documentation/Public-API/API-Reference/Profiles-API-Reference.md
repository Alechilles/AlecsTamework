---
title: "Profiles API Reference"
order: 3
published: true
draft: false
---
# Profiles API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Stable API Contract (`1.0.0`)**
> This reference tracks the current `profiles()` contract in `TameworkApi`.

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
- Prefer `profileId` for long-lived references; UUIDs can remap.
- `getActiveSnapshot(...)` returns raw JSON payload text for the active snapshot type.
- Snapshot names and payloads are data views, not a substitute for Tamework's
  canonical lifecycle decisions. Do not create a parallel lifecycle state from
  them.

## Unreleased: saved owner trait pages

`getOwnedTraitSnapshots(UUID ownerUuid, int offset, int limit)` returns
`CompletionStage<Optional<List<OwnedTraitSnapshot>>>`. The built-in implementation
accepts a non-null owner, offset >= 0, and limit 1..64. It reads saved state without
loading NPCs. Rows are sorted by profile ID and include captured/stored companions,
but exclude released/dead companions. This list is not an admission-capacity count.

An empty optional means unavailable; a present empty list means no matching rows.
Each immutable row exposes `profileId`, `roleId`, `displayName`, `traitConfigId`,
`traits`, `traitDataAvailable`, and `snapshotCreatedAtMs`. Missing or invalid trait
data remains explicitly unavailable. Values describe the latest decodable saved
full-state snapshot, not necessarily current live values.

The method has a default unavailable implementation for older API implementers.
Existing methods are unchanged. Completion may run off the world thread: return
to the original world and revalidate the player/page before changing UI. Pagination
can shift when ownership changes; it is not a durable cursor.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Read Saved Home Position and Show a Waypoint Recipe](/mod/alecs-tamework/read-saved-home-position-and-show-a-waypoint-recipe)
- [Build Companion Inspector UI Card Recipe](/mod/alecs-tamework/build-companion-inspector-ui-card-recipe)



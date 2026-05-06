---
title: "Profiles API Reference"
order: 3
published: true
draft: false
---
# Profiles API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.5.0`)**
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

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Read Saved Home Position and Show a Waypoint Recipe](/mod/alecs-tamework/read-saved-home-position-and-show-a-waypoint-recipe)
- [Build Companion Inspector UI Card Recipe](/mod/alecs-tamework/build-companion-inspector-ui-card-recipe)



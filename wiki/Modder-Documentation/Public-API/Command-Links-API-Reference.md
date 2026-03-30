---
title: "Command Links API Reference"
order: 5
published: true
draft: false
---
# Command Links API Reference

Parent: [Public API Index](/mod/alecs-tamework/public-api-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

> **Experimental API Contract (`0.4.0`)**
> This reference tracks the current `commandLinks()` contract in `TameworkApi`.

Capability: `COMMAND_LINKS`

## Entry Point
`TameworkApi.commandLinks() -> CommandLinksApi`

## Methods
- `Optional<CommandLinkView> getByProfileId(String profileId)`
- `Optional<CommandLinkView> getByNpcUuid(UUID npcUuid)`
- `Set<String> listLinkedToolIds(String profileId)`
- `Optional<Vector3View> getHomePosition(String profileId)`
- `boolean hasHomePosition(String profileId)`

## `CommandLinkView`
- `profileId`
- `currentNpcUuid`
- `ownerUuid`
- `toolIds`
- `hasHomePosition`
- `homePosition`
- `lastKnownPosition`
- `activeSnapshotTypes`
- `lastUpdatedAtMs`

## Home Position Resolution
Tamework resolves `homePosition` in this order:
1. Live NPC command-links component.
2. In-memory linked-state snapshot cache.
3. Active persisted snapshot payload (`capture`, `death`, `lost`).

## Notes
- Values are detached immutable snapshots (`record` + defensive copies).
- `listLinkedToolIds(...)` returns an empty set when the profile is not found.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Read Saved Home Position Recipe](/mod/alecs-tamework/read-saved-home-position-recipe)


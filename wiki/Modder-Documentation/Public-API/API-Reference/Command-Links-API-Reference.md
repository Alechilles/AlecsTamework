---
title: "Command Links API Reference"
order: 5
published: true
draft: false
---
# Command Links API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> The home position is no longer saved with the companion's record.

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
`homePosition` comes from the live NPC's command-links component, or from the
last state Tamework saw while the body was loaded in this server session.

The companion's saved record holds no home position. `getHomePosition(...)`
and `hasHomePosition(...)` are reliable only while the body is loaded. A
companion that is unloaded, stored, captured, dead, or lost can report no
home.

`lastKnownPosition` falls back to the position saved on the record.

## Notes
- Values are detached immutable snapshots (`record` + defensive copies).
- `listLinkedToolIds(...)` returns an empty set when the profile is not found.
- Command links are a read model over the profile and the live body. They are
  not a second lifecycle or persistence authority.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Read Saved Home Position and Show a Waypoint Recipe](/mod/alecs-tamework/read-saved-home-position-and-show-a-waypoint-recipe)
- [Check Command Link State before Running Feature Recipe](/mod/alecs-tamework/check-command-link-state-before-running-feature-recipe)



---
title: "Progression API Reference"
order: 6
published: true
draft: false
---
# Progression API Reference

Parent: [Public API Index](/mod/alecs-tamework/public-api-index) | [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index)

> **Experimental API Contract (`0.4.0`)**
> This reference tracks the current `progression()` contract in `TameworkApi`.

Capabilities: `PROGRESSION`, `PROGRESSION_MUTATIONS`

## Entry Point
`TameworkApi.progression() -> ProgressionApi`

## Read Methods
- `Optional<ProgressionView> getByProfileId(String profileId)`
- `Optional<ProgressionView> getByNpcUuid(UUID npcUuid)`

## Mutation Methods
- `setHappiness(profileId|npcUuid, value)`
- `applyHappinessDelta(profileId|npcUuid, delta)`
- `setNeeds(profileId|npcUuid, hunger, thirst)`
- `setBreedingReady(profileId|npcUuid, ready)`
- `rerollTraits(profileId|npcUuid)`
- `setTraits(profileId|npcUuid, traitValues)`
- `refreshLifeStage(profileId|npcUuid)`
- `setStoredAttachments(profileId|npcUuid, attachmentSelections)`
- `syncStoredAttachments(profileId|npcUuid)`

## `ProgressionMutationResult`
- `status`: `APPLIED`, `NOT_FOUND`, `NOT_LOADED`, `INVALID_ARGUMENT`, `UNSUPPORTED`, `ERROR`
- `message`: compact result detail
- `progression`: detached post-mutation snapshot when available

## `ProgressionView` Subviews
- `happiness`
- `needs`
- `breeding`
- `lifeStage`
- `traits`
- `attachments`

Each subview is optional and only present when the target NPC has that system active.

## Notes
- Reads and mutations target live loaded NPC state.
- Use `profileId` when you need stable targeting across UUID remaps.
- Treat `UNSUPPORTED` and `NOT_LOADED` as expected runtime states, not fatal errors.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Progression Mutation Status Handling Recipe](/mod/alecs-tamework/progression-mutation-status-handling-recipe)


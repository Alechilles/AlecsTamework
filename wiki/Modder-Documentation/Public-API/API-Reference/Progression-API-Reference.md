---
title: "Progression API Reference"
order: 6
published: true
draft: false
---
# Progression API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.7.0`)**
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
- `leveling`
- `talents`
- `traits`
- `attachments`

Each subview is optional and only present when the target NPC has that system active.

## Notes
- Reads and mutations target live loaded NPC state.
- Use `profileId` when you need stable targeting across UUID remaps.
- Treat `UNSUPPORTED` and `NOT_LOADED` as expected runtime states, not fatal errors.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Increase Mob Happiness from Custom Interaction Recipe](/mod/alecs-tamework/increase-mob-happiness-from-custom-interaction-recipe)
- [Decrease Mob Happiness from Negative Event Recipe](/mod/alecs-tamework/decrease-mob-happiness-from-negative-event-recipe)
- [Set Hunger and Thirst from Custom Feeding Recipe](/mod/alecs-tamework/set-hunger-and-thirst-from-custom-feeding-recipe)
- [Force Breeding Ready from Custom Ritual Recipe](/mod/alecs-tamework/force-breeding-ready-from-custom-ritual-recipe)
- [Reroll Traits and Show Values Recipe](/mod/alecs-tamework/reroll-traits-and-show-values-recipe)
- [Apply Attachment Preset from Custom UI Recipe](/mod/alecs-tamework/apply-attachment-preset-from-custom-ui-recipe)



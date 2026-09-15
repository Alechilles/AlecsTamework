---
title: "Progression API Reference"
order: 6
published: true
draft: false
---
# Progression API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Stable API Contract (`1.0.0`)**
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
- With the unreleased timed-happiness correction, `applyHappinessDelta` changes
  underlying mood without removing active timed effects. Display limits can hide
  the change until an effect expires; ordinary environmental convergence still applies.
- `setHappiness` explicitly rebases happiness and clears existing timed effects
  on reconciliation. Method signatures, capabilities, and mutation statuses are unchanged.

## Unreleased: happiness explanation

`HappinessView.presentation()` exposes current/min/max/base/target values, active
effects, inactive configured effects, and whether food effects are exclusive.
Effects contain an ID, label, signed value, and kind. Values use the same evaluated
inputs and disposition adjustment as happiness calculation. The previous
nine-argument `HappinessView` constructor remains available and supplies an empty
presentation. Existing accessors are unchanged; consumers that inspect record
components or serialized shape must account for the added component.

The default companion tooltip shows active labels in white, positive/negative
values in green/red, and inactive effects in gray under **All effects**. Food
parameter matches resolved only at consumption time are not enumerated as inactive
items. Saved/unloaded values remain last-known data.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [Increase Mob Happiness from Custom Interaction Recipe](/mod/alecs-tamework/increase-mob-happiness-from-custom-interaction-recipe)
- [Decrease Mob Happiness from Negative Event Recipe](/mod/alecs-tamework/decrease-mob-happiness-from-negative-event-recipe)
- [Set Hunger and Thirst from Custom Feeding Recipe](/mod/alecs-tamework/set-hunger-and-thirst-from-custom-feeding-recipe)
- [Force Breeding Ready from Custom Ritual Recipe](/mod/alecs-tamework/force-breeding-ready-from-custom-ritual-recipe)
- [Reroll Traits and Show Values Recipe](/mod/alecs-tamework/reroll-traits-and-show-values-recipe)
- [Apply Attachment Preset from Custom UI Recipe](/mod/alecs-tamework/apply-attachment-preset-from-custom-ui-recipe)



## Productive trait read data (development)

`TraitValueView` retains `id`, `value`, `effectKey` and its original three-argument
constructor. The development view also supplies `defaultValue`, `breedingMin`,
`breedingMax`, `meritDirection`, `signedMerit`, and `outputYieldBonus`.

- `meritDirection` is +1 for higher-is-better, -1 for lower-is-better, or 0 for
  an unknown/unscored effect. Appetite and thirst decay use -1.
- `signedMerit` normalizes the stored value around the definition default toward
  its corresponding breeding endpoint, then clamps to [-1,1] and applies the
  useful direction. It does not mutate the stored value.
- `outputYieldBonus` is the authoritative Size meat/hide delta, bounded to
  [-0.25,0.25]; it is zero for other traits. Size changes no food cost, fleece,
  egg, or milk output. This field is not an animal star rating.

Missing definitions return neutral display data. Existing integrations may keep
using the original constructor and accessors; their new fields are neutral.
The pure `TraitModifierService.resolveSizeMeatHideYieldBonus(definition, value)`
helper and the component-based reward resolver use the same formula as this view.

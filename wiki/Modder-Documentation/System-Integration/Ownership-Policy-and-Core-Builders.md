---
title: "Ownership Policy and Core Builders"
order: 9
published: true
draft: false
---
# Ownership Policy and Core Builders

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Use this page when you need the shared building blocks rather than one specific feature family.

## Core NPC action builders
- `TameworkInteract`
- `TameworkInteractPrompt`
- `TameworkCaptureOwner`
- `TameworkCaptureStranger`
- `TameworkCaptureWild`
- `TameworkDenyCaptureUntamed`
- `TameworkDenyInteract`
- `TameworkSetOwner`
- `TameworkSetTamed`
- `TameworkNeedsResourceConsume`
- `TameworkHarvestDrop`
- `TameworkHarvestAlarm`
- `TameworkDebugMessage`

`TameworkHarvestAlarm` sets the `Harvest_Ready` alarm from the role's `HarvestTimeout` builder parameter and applies the companion's `HarvestCooldownMultiplier` passive effect before scheduling the alarm.

## Core sensors and filters
- `TameworkIsOwner`
- `TameworkHasOwner`
- `TameworkIsTamed`
- `TameworkLifeStage`
- `TameworkHook`
- `TameworkEffectActive`
- `TameworkNeedBelow`
- `TameworkNeedsResourceTarget`
- `TameworkAttitudeFromTargetSlot`
- `TameworkAttackedTargetSlotRecently`

## Core runtime components
- `TameworkOwnerComponent`
- `TameworkTamedComponent`
- `TameworkHookComponent`
- `TameworkNpcNameComponent`
- `TameworkCommandLinksComponent`
- `TameworkHappinessComponent`
- `TameworkNeedsComponent`
- `TameworkBreedingComponent`
- `TameworkTraitsComponent`
- `TameworkAttachmentsComponent`
- `TameworkLifeStageComponent`

## Ownership guidance
- Global defaults belong in `TwGlobalConfig`
- Ownership requirement defaults live under `TwGlobalConfig.OwnershipRequirements`
- Role-specific ownership and command protection belong in `TwCompanionConfig`
- Item systems should not re-implement their own ownership rules unless the item config explicitly needs a stricter or looser override

`TameworkSetOwner` and Tame check durable owner-population admission before
assigning a non-null owner. The cap counts canonical owned profiles in the
configured global/per-world scope and reserves positive capacity within the
same shared operation. It remains independent from SimpleClaims
claim-placement policy.

`TameworkOwnerComponent` is authoritative. Clearing or transferring canonical ownership invalidates
the prior command-link authority, while retained name metadata follows the new canonical owner.

## Related Pages
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)




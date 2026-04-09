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
- `TameworkDebugMessage`

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

## Related Pages
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)




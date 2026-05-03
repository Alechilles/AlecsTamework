---
title: "Ownership, Damage, and Progression Internals"
order: 11
published: true
draft: false
---
# Ownership, Damage, and Progression Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Core components
- `TameworkOwnerComponent`
- `TameworkTamedComponent`
- `TameworkHappinessComponent`
- `TameworkNeedsComponent`
- `TameworkBreedingComponent`
- `TameworkTraitsComponent`
- `TameworkAttachmentsComponent`
- `TameworkLifeStageComponent`

## Key systems
- `OwnerDamageFilterSystem`
- `DamageTargetMemorySystem`
- `TraitDamageModifierSystem`
- `CompanionProgressionBootstrapOnLoadSystem`
- `CompanionNeedsSystem`
- `CompanionPassiveBreedingSystem`
- `CompanionTraitBootstrapOnLoadSystem`
- `CompanionTraitStatSyncSystem`
- `CompanionLifeStageResumeOnLoadSystem`
- `CompanionAttachmentSyncSystem`

## Mounted and nameplate safety
- `NpcMountedNameplateVisibilitySystem` ticks over mounted NPCs and cached hidden-name state so names are hidden while an NPC has an active mount owner and restored after dismount, even when mount component add/remove callbacks are missed.
- `MountedInteractableSafetySystem`
- `MountedNpcTeleportSafetySystem`
- `MountedOwnerReferenceSanitySystem`

## Design advice
- Keep ownership checks in the shared protection and policy layers rather than duplicating them in each feature runtime
- Keep progression bootstrap and long-tick systems separate from one-shot item interactions

## Related Pages
- [Optimized Interaction Pipeline Internals](/mod/alecs-tamework/optimized-interaction-pipeline-internals)
- [Spawner Runtime Internals](/mod/alecs-tamework/spawner-runtime-internals)




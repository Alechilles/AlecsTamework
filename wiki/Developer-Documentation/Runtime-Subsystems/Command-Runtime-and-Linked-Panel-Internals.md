---
title: "Command Runtime and Linked Panel Internals"
order: 9
published: true
draft: false
---
# Command Runtime and Linked Panel Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Main orchestrator
`CommandItemFeatureHandler`

## Major service clusters
- Resolution and recipient selection: `CommandResolutionService`, `CommandRecipientService`
- Link persistence and mutation: `CommandLinkedNpcRecordStore`, `CommandLinkMutationService`, `CommandLinkPolicyService`
- Command execution: `CommandStepExecutionService`, `CommandMenuMoveService`
- Panel entry assembly and preferences: `CommandLinkedPanelEntryService`, `CommandPanelEntrySourceService`, `CommandPanelPreferenceService`
- Group flows: `CommandGroupService`, `CommandGroupAssignPageService`, `CommandGroupManagerPageService`
- Relocation and recovery: `CommandRelocationDispatchService`, `CommandNpcRelocationService`, `CommandRespawnService`, `CommandLostRecoveryService`, `CommandLinkedNpcDeathService`, `CommandLinkedNpcLostService`

## UI layer
- `TameworkCommandSelectionPage`
- `TameworkCommandGroupManagerPage`
- `LinkedNpcPanelCardBinder`
- `LinkedNpcPanelStatusTextService`
- `LinkedNpcTraitIndicatorBinder`

## Persistence model
Command tools persist linked NPC metadata, group metadata, panel preferences, and active or inactive state directly on the item, while deeper recovery flows use the shared persistence runtime.

When canonical identity is known, a command record also carries the stable profile id. Entity UUIDs are replaceable aliases: historical UUIDs resolve back to the same profile before relocation, lost marking, recovery, or spawn decisions. The facade deduplicates by profile and consults only trusted managed-coop indexes; ambiguous legacy identity or rebuilding indexes fail closed.

## Important runtime seams
- Nearby and linked modes are separate entry sources
- `LOST` is not just a label; it is part of the recovery model
- Dead companion flows depend on persisted snapshots and companion policy
- Managed-coop housing is a profile state, not evidence that the companion vanished. Recovery must not spawn while any alias is live, housed, captured, dead, or already replaced.

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)




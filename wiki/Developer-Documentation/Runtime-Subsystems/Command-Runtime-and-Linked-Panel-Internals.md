---
title: "Command Runtime and Linked Panel Internals"
order: 9
published: true
draft: false
---
# Command Runtime and Linked Panel Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems-index) | [Developer Documentation](/mod/alecs-tamework/developer-documentation-index)

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

## Important runtime seams
- Nearby and linked modes are separate entry sources
- `LOST` is not just a label; it is part of the recovery model
- Dead companion flows depend on persisted snapshots and companion policy

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)



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
- Panel entry assembly and preferences: `CommandLinkedPanelEntryService`, `CommandLinkedPanelUnloadedNameService`, `CommandPanelEntrySourceService`, `CommandPanelPreferenceService`
- Active-NPC indicators: `CommandActiveNpcHighlightSystem`,
  `CommandActiveNpcHighlightDisplayTracker`, `CommandActiveNpcHighlightEmitter`
- Group flows: `CommandGroupService`, `CommandGroupAssignPageService`, `CommandGroupManagerPageService`
- Relocation: `CommandRelocationDispatchService`, `CommandNpcRelocationService`,
  `CommandRelocationRetryCoordinator`
- Canonical status and restoration: `CommandPersistenceView`,
  `CommandNpcProfileActionResolver`, `CommandCompanionRestorationService`

## UI layer
- `TameworkCommandSelectionPage`
- `TameworkCommandGroupManagerPage`
- `LinkedNpcPanelCardBinder`
- `LinkedNpcPanelStatusTextService`
- `LinkedNpcTraitIndicatorBinder`

## Persistence model
Legacy command tools persist their link list, group metadata, panel
preferences, and active or inactive selection on item metadata. A link record
may also carry the stable profile ID so an old entity UUID can be canonicalized.
Owner/command-family rosters instead persist membership and summon state in the
replacement store; command items are interfaces to that durable roster rather
than its authority.

The replacement profile projection is the authority for lifecycle status,
canonical name, and restorable state. Entity UUIDs are replaceable aliases:
historical UUIDs resolve back to the same profile before relocation,
restoration, or spawn decisions.

## Important runtime seams
- Nearby and linked modes are separate entry sources
- The canonical lifecycle alone determines active, unloaded, captured, cooped,
  roster-stored, provisioned-dormant, dead, Lost, released, or unresolved
  status. Command-item display caches cannot override it.
- Death and Lost restoration require the matching canonical lifecycle plus its
  persisted snapshot and companion policy.
- Ordinary unloaded presentation resolves the latest live state snapshot, then
  durable profile metadata, before the older display name cached on the command
  item.
- Legacy item-metadata link restoration remains free. Dead and Lost
  owner/command-family roster entries use the exact server-authoritative paid
  revival quote. Neither path may create a second live alias.
- Relocation retry exhaustion removes the pending relocation and reports a
  warning. It does not create `LOST`; only positive destructive-removal
  evidence can author that lifecycle.
- Active-NPC indicators are sent only to the controlling player. Each loaded
  target is emitted once per stable tool and roster state. A roster, color,
  setting, or equipped-tool change cancels the old indicator set before the
  current set is sent. This subsystem registers only on Update 6 because
  Update 5 cannot cancel persistent particle systems.

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)




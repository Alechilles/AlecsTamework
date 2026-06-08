# Command Items (TwCommandItemConfig)

## Overview
Command items let players link companion NPCs to a tool and dispatch commands at runtime.
The system is asset-driven around:
- `TwCommandItemConfig`
- `TameworkCommand` item interaction
- `CommandItemFeatureHandler` orchestration

## Runtime Architecture (Contributor View)
Command runtime is split to keep the orchestrator thin:
- Orchestrator: `CommandItemFeatureHandler`
- Resolution/selection: `CommandResolutionService`, `CommandRecipientService`
- Link persistence/mutation: `CommandLinkedNpcRecordStore`, `CommandLinkMutationService`
- Step execution + move/home behavior: `CommandStepExecutionService`, `CommandMenuMoveService`
- Off-screen relocation + revive: `CommandRelocationDispatchService`, `CommandNpcRelocationService`, `CommandRespawnService`, `CommandLinkedNpcDeathService`
- Panel entry assembly/filter/sort: `CommandLinkedPanelEntryService`, `CommandPanelEntrySourceService`, `CommandPanelPreferenceService`
- Group metadata + group manager actions: `CommandGroupService`, `CommandGroupManagerPageService`
- Player feedback: `CommandFeedbackService`

## Asset and item wiring
- Assets: `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- Item interaction: `TameworkCommand`

Typical item wiring:
```json
"Interactions": {
  "Primary": {
    "Interactions": [
      { "Type": "TameworkCommand" }
    ]
  },
  "Secondary": {
    "Interactions": [
      { "Type": "TameworkCommand", "CommandId": "OpenSelectionMenu" }
    ]
  }
}
```

Overrides:
- `OpenSelectionMenu`
- `CycleSelection`

## Recipient selection and linking
`TwCommandItemConfig` recipient controls:
- `MembershipMode`: `LinkedOnly`, `OwnerScope`, `MasterTarget`, `LinkedOrMasterTarget`
- `RequireOwner`
- `RequireTamed`
- `AllowedRoles` (`AllowAll`, `Allowlist`, `Denylist`)
- `Radius`
- `MaxTargets`
- `MaxActive` (`0` = unlimited active links)
- `RequireLineOfSight`
- `LinkEnabled`
- `LinkUseTogglesMembership`

Link metadata includes:
- NPC uuid
- last-known position
- optional home position
- cached display/name key/role
- active/inactive flag
- optional `groupId`

Inactive linked rows stay visible in the panel, can still use per-row actions, and are excluded from bulk dispatch.

## Command list and steps
Each `CommandList` entry supports:
- `Id`
- `DisplayName`
- `Default`
- `Feedback`
- `ModeMapping`
- `Steps`

`DisplayName`, `Feedback.HudMessage`, and `Feedback.ChatMessage` may be raw text or `server.lang` keys. Prefer keys such as `tamework.commands.follow.name` for built-in packs and downstream mods that plan to support multiple languages.

Step types:
- `SetState`
- `SetTarget`
- `ClearTarget`
- `ClearCombat`
- `MoveToPosition`
- `StoreHome`
- `TriggerHook`

Per-step controls:
- `FailurePolicy` (`Continue`, `AbortCommandForNpc`, `AbortAll`)
- `Optional`

## Command radial + linked panel UX
Selection UI:
- `Common/UI/Custom/TameworkCommandRadialMenu.ui`
- `TameworkCommandSelectionPage`

Linked panel supports:
- Mode toggle: `LinkedMode` / `NearbyMode`
- Nearby radius controls in nearby mode
- Sort: `Default`, `Name`, `Species`, `Group`
- Filter: `None`, `Name`, `Species`, `Group`
- Filter text input for active filter mode
- Active/inactive row toggles
- Group active selector: `All`, `None`, or one configured group
- Breeding enable/disable row toggles (default: disabled)
- Group assignment overlay per row
- Group manager flow (create/rename/recolor/delete)
- Status lanes for loaded/unloaded/dead/lost companions
- Per-row actions: `Locate`, `Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive` (when enabled/ready), plus nearby-only `Release`/`Cull` behind confirm flow
- Breeding cooldown ring/status and progression vitals/trait indicators

## Move/home/recall and off-screen relocation
Loaded flow:
- `SetHome` stores per-NPC home data.
- `ReturnHome` can use path + deferred teleport behavior.
- `Recall` can force-relocate distant companions near the player before follow resumes.
- `/tw settings` can disable recall/return-home teleporting. When disabled, Recall is hidden from the linked panel and command wheel, loaded companions still receive normal move/home command hooks, and unloaded or distant forced relocation is skipped; use `Locate` to open a copyable current or last recorded world-position page.

Unloaded flow:
- Relocation commands enqueue pending relocations by NPC uuid.
- Source/destination chunks are requested asynchronously.
- Retries run on bounded interval/time windows.
- On-load relocation retries run via `CommandNpcRelocationOnLoadSystem`.

Lost flow:
- If relocation retry windows are exhausted, a linked companion can transition to `LOST`.
- `Recall`/`Return Home` are blocked while `LOST`.
- `Revive`/`Respawn` can perform strict recovery (replacement spawn + stale-original suppression mapping).

Dead companions:
- Death snapshots persist across relog/restart.
- `Revive` enablement is controlled by `/tw settings`; cooldowns and placement tuning remain in `TwCompanionConfig.Command`.

## Global tuning
`TwGlobalConfig.Command` remains the shared relocation infrastructure location:
- `RelocationRetryIntervalMs`
- `RelocationMaxWaitMs`
- `RelocationMaxRetryAttempts`
- `LinkedPanelRequireUnlinkConfirm`

Role-scoped behavior tuning belongs in `TwCompanionConfig.Command`:
- `ReturnHomeTeleportDistance`
- `ReturnHomePathDistanceBeforeTeleport`
- `ReturnHomeTeleportDelayMs`
- `RecallSafeSpawnDistance`
- `RecallForceRelocateDistance`
- `Travel.CrossWorldRecallEnabled`
- `Travel.OnTransferFailure` (`QueueForRecall`, `MarkLost`, `Ignore`)
- `Travel.FollowMasterOnWorldChange`
- `Travel.FollowMasterOnWorldChangeStateFilter`
- `DeadRespawnCooldownMs` / `DeadRespawnCooldownMins`
- `DeadRespawnFollowRetryDelayMs`
- `DeadRespawnDistanceClose/Near/Mid/Far`
- `PlacementMinRelativeY`
- `PlacementMaxRelativeY`

## Feedback notes
Command feedback sounds are delivered as local 2D sound for the using player and in-world 3D sound for nearby others.

## Reloading
`/tw reloadconfig` reloads command item assets along with spawner and naming assets.

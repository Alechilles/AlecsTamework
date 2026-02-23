# Command Items (TwCommandItemConfig)

### Note: Command items are a beta feature. Changes to core systems are likely. Expect breaking changes if you implement this in your mod currently.

## Overview
Command items let players link companion NPCs to a tool and issue commands at runtime.
The system is asset-driven and built around:
- `TwCommandItemConfig` assets
- `TameworkCommand` item interaction
- `CommandItemFeatureHandler` orchestration
- Optional NPC instruction bridge `Component_Tamework_Instruction_Command_Move`

Core behaviors:
- Link/unlink NPCs per tool item.
- Select a command (cycle or radial menu).
- Execute command steps on matching recipients.
- Queue relocation-style commands for unloaded linked NPCs and apply when chunks/entities are available.

## Runtime Architecture (Contributor View)
Command runtime is intentionally split so one class does not own all logic:
- Orchestrator: `CommandItemFeatureHandler`
- Selection/resolution: `CommandResolutionService`, `CommandRecipientService`
- Link persistence/mutation: `CommandLinkedNpcRecordStore`, `CommandLinkMutationService`
- Step execution + move/home behavior: `CommandStepExecutionService`, `CommandMenuMoveService`
- Off-screen relocation + revive: `CommandRelocationDispatchService`, `CommandNpcRelocationService`, `CommandRespawnService`, `CommandLinkedNpcDeathService`
- Panel view-model assembly: `CommandLinkedPanelEntryService`
- Player feedback: `CommandFeedbackService`

This split is important for maintainability and testability; add new logic to the matching service domain instead of growing the orchestrator.

## Asset and Item Wiring
- Command config assets:
  `<ModRoot>/Server/Tamework/Items/Commands/*.json`
- Item interaction:
  `TameworkCommand`

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

Notes:
- Primary use usually executes the selected command (and can toggle linking when targeting an NPC).
- Secondary use can open the radial selection menu (`OpenSelectionMenu`).
- `CommandId: CycleSelection` is also supported for non-UI cycling.

## Recipient Selection
`TwCommandItemConfig` controls recipient membership with `MembershipMode`:
- `LinkedOnly`
- `OwnerScope`
- `MasterTarget`
- `LinkedOrMasterTarget`

Additional filters:
- `RequireOwner`
- `RequireTamed`
- `AllowedRoles` (`AllowAll`, `Allowlist`, `Denylist`)
- `Radius`
- `MaxTargets`
- `RequireLineOfSight`

Linking state is stored in:
- NPC component: `TameworkCommandLinksComponent` (owner/tool links plus optional per-NPC home position)
- Tool metadata: linked NPC UUIDs plus last-known positions (and mirrored home data for unloaded relocation)

## Command List and Steps
Each command entry in `CommandList` defines:
- `Id`
- `DisplayName`
- `Default`
- `Feedback`
- `ModeMapping` (optional fallback when no steps are defined)
- `Steps`

Supported step types:
- `SetState`
- `SetTarget` (`CrosshairTarget`, `LastAttackTarget`, `OwnerPlayer`, `StoredTarget`)
- `ClearTarget`
- `ClearCombat`
- `MoveToPosition` (`RaycastHit`, `OwnerPosition`, `StoredHome`)
- `StoreHome` (`RaycastHit`, `OwnerPosition`)
- `TriggerHook`

Per-step control:
- `FailurePolicy`: `Continue`, `AbortCommandForNpc`, `AbortAll`
- `Optional`: true/false

## Radial Selection UI
Selection page:
- UI file: `Common/UI/Custom/TameworkCommandRadialMenu.ui`
- Page class: `TameworkCommandSelectionPage`

Behavior:
- Secondary use with `OpenSelectionMenu` opens the wheel.
- Clicking a slice sets the selected command id in tool metadata.
- The page supports up to 8 visible command buttons.

Linked companions side panel (shown with the radial menu):
- Displays linked NPCs in a dynamic scroll list (not fixed rows).
- Shows loaded/unloaded/dead row status and health snapshots when available.
- Supports per-row `Recall`, `Set Home`, `Return Home`, and `Unlink` actions.
- Shows `Revive` on dead companions when dead respawn is enabled and cooldown is ready.
- Refreshes row status/health/cooldowns in-place once per second while open.
- Includes an empty-state prompt when no companions are linked.
- Optional unlink safety confirmation can require a second click.

## Move/Home/Recall and Off-Screen Relocation
Command relocation supports both loaded and unloaded NPCs.

Loaded flow:
- `SetHome` stores home per linked NPC (not per command tool), so each NPC can return to a different location.
- `ReturnHome` can use hybrid behavior:
  - path a short visible segment
  - deferred teleport to stored home
- `Recall` can force-relocate very distant loaded NPCs near the player before normal follow resumes.

Unloaded flow:
- Relocation-style commands queue a pending relocation by NPC UUID.
- Source/destination chunks are requested asynchronously.
- Retries run on a bounded interval/time window.
- Pending relocations are also retried on NPC add/remove events.
- Unloaded identities shown in the linked panel use fallback priority: `Display Name > Name Key > Role ID`.

Global tuning values come from `TwGlobalConfig.Command`:
- `ReturnHomeTeleportDistance`
- `ReturnHomePathDistanceBeforeTeleport`
- `ReturnHomeTeleportDelayMs`
- `RecallSafeSpawnDistance`
- `RecallForceRelocateDistance`
- `RelocationRetryIntervalMs`
- `RelocationMaxWaitMs`
- `RelocationMaxRetryAttempts`
- `DeadRespawnEnabled`
- `DeadRespawnCooldownMs`
- `DeadRespawnFollowRetryDelayMs`
- `DeadRespawnDistanceClose`
- `DeadRespawnDistanceNear`
- `DeadRespawnDistanceMid`
- `DeadRespawnDistanceFar`
- `PlacementMinRelativeY`
- `PlacementMaxRelativeY`
- `LinkedPanelRequireUnlinkConfirm`

## Hook Bridge for Movement
`MoveToPosition` emits hooks with ids like:
- `Tamework.Command.MoveToPosition.RaycastHit`
- `Tamework.Command.MoveToPosition.StoredHome`

`Component_Tamework_Instruction_Command_Move` can consume these hooks and run actual seek behavior.
`TameworkHook` now exposes target-position params (`HookHasTargetPosition`, `HookTargetX`, `HookTargetY`, `HookTargetZ`) so instruction logic can read movement destinations directly.

## Reloading
`/tw reloadconfig` reloads command item assets along with spawner and naming item assets.

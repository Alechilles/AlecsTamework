# Command Items (TwCommandItemConfig)

## Overview
Command items let players link companion NPCs to a tool and issue commands at runtime.
The system is asset-driven and built around:
- `TwCommandItemConfig` assets
- `TameworkCommand` item interaction
- `CommandItemFeatureHandler` runtime dispatch
- Optional NPC instruction bridge `Component_Tamework_Instruction_Command_Move`

Core behaviors:
- Link/unlink NPCs per tool item.
- Select a command (cycle or radial menu).
- Execute command steps on matching recipients.
- Queue relocation-style commands for unloaded linked NPCs and apply when chunks/entities are available.

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
- NPC component: `TameworkCommandLinksComponent`
- Tool metadata: linked NPC UUIDs plus last-known positions

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

## Move/Home/Recall and Off-Screen Relocation
Command relocation supports both loaded and unloaded NPCs.

Loaded flow:
- `ReturnHome` can use hybrid behavior:
  - path a short visible segment
  - deferred teleport to stored home
- `Recall` can force-relocate very distant loaded NPCs near the player before normal follow resumes.

Unloaded flow:
- Relocation-style commands queue a pending relocation by NPC UUID.
- Source/destination chunks are requested asynchronously.
- Retries run on a bounded interval/time window.
- Pending relocations are also retried on NPC add/remove events.

Global tuning values come from `TwGlobalConfig`:
- `CommandReturnHomeTeleportDistance`
- `CommandReturnHomePathDistanceBeforeTeleport`
- `CommandReturnHomeTeleportDelayMs`
- `CommandRecallSafeSpawnDistance`
- `CommandRecallForceRelocateDistance`
- `CommandRelocationRetryIntervalMs`
- `CommandRelocationMaxWaitMs`
- `CommandRelocationMaxRetryAttempts`

## Hook Bridge for Movement
`MoveToPosition` emits hooks with ids like:
- `Tamework.Command.MoveToPosition.RaycastHit`
- `Tamework.Command.MoveToPosition.StoredHome`

`Component_Tamework_Instruction_Command_Move` can consume these hooks and run actual seek behavior.
`TameworkHook` now exposes target-position params (`HookHasTargetPosition`, `HookTargetX`, `HookTargetY`, `HookTargetZ`) so instruction logic can read movement destinations directly.

## Reloading
`/tw reloadconfig` reloads command item assets along with spawner and naming item assets.

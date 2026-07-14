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
- stable profile id when the companion has entered canonical persistence
- last-known position
- optional home position
- cached display/name key/role
- active/inactive flag
- optional `groupId`

Inactive linked rows stay visible in the panel, can still use per-row actions, and are excluded from bulk dispatch.

Entity UUIDs are projection aliases, not the companion's durable identity. When a stable profile is known, command records and recovery flows resolve historical UUIDs through that profile and deduplicate by profile. Unresolved legacy records continue to fall back to UUID until they can be bound safely; ambiguous bindings fail closed instead of spawning a replacement.

For online players, command-item copies in the hotbar, storage, and backpack are lazily canonicalized when the player enters a world and whenever a linked command item moves through those inventory compartments. Offline inventories are not rewritten directly; their records remain safe through profile-first resolution and are normalized on the next load or use.

When a player tames a supported NPC, Tamework attempts to auto-link the new companion to a matching command item in that player's inventory. Players now receive explicit feedback for both outcomes:
- linked: the notification names the animal and command item that was linked.
- not linked: the notification names the animal, applicable command item, and crafting bench type.

When a linked companion is placed in a compatible handheld capture item, its linked-panel row remains available and reports `CAPTURED` as soon as capture commits, even while the retired source projection finishes despawning and including capture configurations that clear live ownership. Spawning the companion restores its command links and remaps the panel record to the new projection UUID.

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
- Target HUD: `Common/UI/Custom/TameworkCommandTargetHud.ui`

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
- Status lanes for loaded/unloaded/dead/lost companions; ordinary unloaded rows keep the latest custom display name from the live snapshot or durable profile, including across restart
- Per-row actions: `Locate`, `Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive` (when enabled/ready), plus nearby-only `Release`/`Cull` behind confirm flow
- Breeding and harvest cooldown ring/status indicators, plus progression vitals/trait indicators
- Attempting-recall countdown text for unloaded companions while relocation is still retrying

Command target HUD:
- Appears while the player holds any registered command item and looks directly at a supported NPC within 6 units.
- Uses the same loaded-NPC status snapshot as the linked panel for display name, health, happiness, hunger, thirst, level, traits, harvest cooldown, and breeding cooldown.
- Adds compact target-only rows for favorite food, other compatible foods on tamed NPCs, attachment selections, and required tranquilizer stacks for tame interactions that require tranquilizer setup.
- Clears automatically when the player looks away, switches away from command items, or targets an unsupported NPC.

## Move/home/recall and off-screen relocation
Loaded flow:
- `SetHome` stores per-NPC home data.
- `ReturnHome` can use path + deferred teleport behavior.
- `Recall` can force-relocate distant companions near the player before follow resumes.
- `/tw settings` can disable recall/return-home teleporting. When disabled, Recall is hidden from the linked panel and command wheel, loaded companions still receive normal move/home command hooks, and unloaded or distant forced relocation is skipped; use `Locate` to open a copyable current or last recorded world-position page.
- A linked panel can remain open across a world or generated-instance transfer. Its Recall and Return Home actions resolve the player's current entity/store from the stable player reference at click time, rather than reusing the source-world entity reference captured when the panel opened.
- Per-row movement actions validate and repair only the selected companion's canonical profile metadata. An unrelated damaged link on the same command item does not make a healthy selected companion unavailable.

Unloaded flow:
- Relocation commands enqueue pending relocations by NPC uuid.
- Source/destination chunks are requested asynchronously.
- Every loaded source or destination chunk is retained for the lifetime of the pending relocation and released on success, timeout, replacement, cancellation, or shutdown.
- The source NPC is resolved after its chunk loads and its canonical population state reconciles; transient admission revision or claim-profile conflicts retry within the same bounded request.
- Repeated clicks for the same command reuse that pending request, even if the player moved, while a command targeting another world or state remains distinct.
- Retries run on bounded interval/time windows, and one click is sufficient while the attempting-recall status is shown.
- On-load relocation resumes after `CommandNpcRelocationOnLoadSystem` yields to population reconciliation.

Lost flow:
- If relocation retry windows are exhausted, a linked companion can transition to `LOST`; the shipped default wait budget is 10 seconds.
- If an external destructive command removes a linked companion with Hytale's `REMOVE` reason, Tamework preserves the final complete state and transitions it directly to `LOST`; it does not reinterpret destructive removal as a normal chunk unload.
- `Recall`/`Return Home` are blocked while `LOST`.
- `Revive`/`Respawn` can perform strict recovery (replacement spawn + stale-original suppression mapping).

Managed-coop flow:
- A linked companion housed by an enabled/configured managed coop stays attached to the same stable profile even when a later released projection has a different entity UUID.
- Command status reads use the trusted managed-coop resident/lifecycle indexes. While those indexes are rebuilding or an import conflict is unresolved, recovery does not guess that the companion is missing.

Dead companions:
- Death snapshots persist across relog/restart.
- `Revive` enablement is controlled by `/tw settings`; cooldowns and placement tuning remain in `TwCompanionConfig.Command`.

## Global tuning
`TwGlobalConfig.Command` remains the shared relocation infrastructure location:
- `RelocationRetryIntervalMs`
- `RelocationMaxWaitMs` (default: `10000`)
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

Generated portal instances are delete-on-remove worlds. If a linked companion remains inside when
the instance closes, Tamework publishes its complete last-live state to Lost recovery during the
world-removal event. This happens before Hytale deletes the instance chunks, so a later Recall uses
the strict recovery flow instead of leaving an Active/Unloaded row pointing at a nonexistent world.
Permanent worlds are not reclassified by this rule.
- `DeadRespawnCooldownMs` / `DeadRespawnCooldownMins`
- `DeadRespawnFollowRetryDelayMs`
- `DeadRespawnDistanceClose/Near/Mid/Far`
- `PlacementMinRelativeY`
- `PlacementMaxRelativeY`

## Feedback notes
Command feedback sounds are delivered as local 2D sound for the using player and in-world 3D sound for nearby others.

## Reloading
`/tw reloadconfig` reloads command item assets along with spawner and naming assets.

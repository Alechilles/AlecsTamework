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
- Item link metadata/mutation: `CommandLinkedNpcRecordStore`, `CommandLinkMutationService`
- Canonical status and identity: `CommandPersistenceView`, `CommandNpcIdentityService`, `CommandNpcProfileActionResolver`
- Step execution + move/home behavior: `CommandStepExecutionService`, `CommandMenuMoveService`
- Off-screen relocation + restoration: `CommandRelocationDispatchService`, `CommandNpcRelocationService`, `CommandCompanionRestorationService`
- Live/dormant snapshot assembly: `CommandLiveNpcSnapshotFactory`, `CommandLinkedNpcStateSnapshotService`
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

### Bundled example and acquisition boundary

Tamework ships `Tamework_Command_Whistle_Example` with the
`TwCommandExample` config as a development/reference item. The config includes
the HyDragon-relevant `Follow`, `Hold`, `Recall`, and `AttackTarget` commands,
but the item has no bundled recipe or other polished player-acquisition path.
In a production pack it is available only when an operator or development
workflow gives the item directly.

Downstream mods should ship their own named item, command config, localization,
icon, and recipe/acquisition flow. Do not present the example whistle as a
player-ready Tamework reward or silently depend on players finding it.

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
- fallback display/name key/role stored on the item
- active/inactive flag
- optional `groupId`

Inactive linked rows stay visible in the panel, can still use per-row actions, and are excluded from bulk dispatch.

Entity UUIDs are projection aliases, not the companion's durable identity. When a stable profile is known, command records and recovery flows resolve historical UUIDs through that profile and deduplicate by profile. Unresolved legacy records continue to fall back to UUID until they can be bound safely; ambiguous bindings fail closed instead of spawning a replacement.

For online players, command-item copies in the hotbar, storage, and backpack are lazily canonicalized when the player enters a world and whenever a linked command item moves through those inventory compartments. Offline inventories are not rewritten directly; their records remain safe through profile-first resolution and are normalized on the next load or use.

When a player tames a supported NPC, Tamework attempts to auto-link the new companion to a matching command item in that player's inventory. Players now receive explicit feedback for both outcomes:
- linked: the notification names the animal and command item that was linked.
- not linked: the notification names the animal, applicable command item, and crafting bench type.

When a linked companion is placed in a compatible handheld capture item, its
linked-panel row remains available and reports `CAPTURED` as soon as capture
commits, including when capture clears live ownership. Releasing the companion
restores its command links and remaps the panel record to the new live entity
UUID without changing the stable profile.

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
- Status lanes for loaded, unloaded, captured, cooped, roster-stored,
  provisioned-dormant, dead, and Lost companions; ordinary unloaded rows keep
  the latest custom display name from the live snapshot or durable profile,
  including across restart
- Per-row actions: `Locate`, `Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive` (when enabled/ready), plus nearby-only `Release`/`Cull` behind confirm flow
- Owner/command-family roster rows additionally show their authoritative
  timed-summon state, remaining duration or cooldown, active population count
  and limit, with `Summon` and `Dismiss` actions where valid.
- Dead and Lost owner/command-family roster rows use the server-authoritative
  paid revival quote. The confirmation lists every exact cost component and
  owned/required quantity; the replacement paid-revival API performs the
  mutation. Legacy item-metadata links retain their existing free restoration
  behavior.
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
- Successful loaded Hold, Recall, and Return Home commands publish the state actually applied to the NPC into linked-item metadata. Cross-world following also rechecks the live source NPC against the configured state filter, so stored item metadata alone cannot authorize travel.

Unloaded flow:
- Relocation commands enqueue pending relocations by NPC uuid.
- Source/destination chunks are requested asynchronously.
- Every loaded source or destination chunk is retained for the lifetime of the pending relocation and released on success, timeout, replacement, cancellation, or shutdown.
- The source NPC is resolved after its chunk loads and checked against its
  canonical profile and current alias before any move is applied.
- Repeated clicks for the same command reuse that pending request, even if the player moved, while a command targeting another world or state remains distinct.
- Retries run on bounded interval/time windows, and one click is sufficient while the attempting-recall status is shown.
- A persistence preflight denial reports its status-specific availability
  message and leaves the last canonical state intact.
- On-load relocation resumes through `CommandNpcRelocationOnLoadSystem` after
  the saved source entity becomes available.
- A relocation that attempted its physical move but remains temporarily
  unobservable stays `UNLOADED`, not `LOST`. Observing the destination
  projection restores normal loaded status. A failed or exhausted transfer
  stops retrying without manufacturing a durable lifecycle transition.
- One migration-only exception applies to a public `v2.16.1` companion whose
  released coop row retained a complete owner-bound snapshot. The importer
  normalizes that snapshot to the released current alias. If the companion was
  absent during its first startup reconciliation and an explicit Recall later
  exhausts every lookup before any physical move, Tamework consumes that exact
  one-use artifact and changes the entry to `LOST`, where normal Revive is
  available. Later lifecycle revisions, malformed or ownerless snapshots,
  Return Home, and unconfirmed physical transfers cannot use this recovery.

Lost flow:
- If relocation retry windows are exhausted, the request stops and may retain
  process-local presentation detail; timeout alone does not author durable
  `LOST`. The shipped default wait budget is 10 seconds.
- A durable Lost transition requires positive evidence. An external destructive
  command using Hytale's `REMOVE` reason qualifies; ordinary unload, absence,
  and timeout observations do not.
- The bounded public-import recovery artifact described above is also positive
  evidence, but only for the exact initial imported-unloaded lineage and only
  after a clean explicit Recall exhaustion.
- A delete-on-remove world can also provide terminal Lost evidence while the
  companion's complete live state is still available.
- `Recall`/`Return Home` are blocked while `LOST`.
- `Revive`/`Respawn` uses the canonical paid-revival path for an
  owner/command-family roster row and the canonical free restoration path for
  a legacy item-metadata link. Both use the exact saved snapshot, and a
  successful restoration rotates the live alias without creating a second
  profile.

Configured-coop flow:

- A configured coop captures a live linked companion directly into its
  canonical coop slot and releases it through the same persistence authority.
- The released live NPC may have a different entity UUID but remains attached
  to the same stable profile and command links.
- A supported managed-coop interaction can move an eligible canonical filled
  spawner directly into a coop slot through the canonical coop-capture
  operation. Other uses retain the captured-spawner release operation.

Dead companions:

- Saved death is positive dormant evidence and its snapshot persists across
  relog/restart.
- `Revive` uses the same roster-scoped paid or legacy free distinction as Lost
  restoration. Enablement is controlled by `/tw settings`; placement and
  exact revival-cost tuning remain in `TwCompanionConfig.Command`.

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
- `Travel.FollowMasterOnWorldChange` (disabled by default; explicit Recall remains available)
- `Travel.FollowMasterOnWorldChangeStateFilter`

Automatic world-change following is disabled in Tamework's shipped companion defaults. A role-specific
config may deliberately opt in; configured followers are then selected after the player entity is
installed in the destination world's entity store, and the source NPC's live state must still pass
`FollowMasterOnWorldChangeStateFilter` when the relocation is prepared. Explicit Recall remains
available across worlds when `CrossWorldRecallEnabled` is enabled.

`Travel.OnTransferFailure: MarkLost` is retained as a legacy config spelling
for stopping the failed transfer path. It does not authorize `LOST` from a
timeout or missing observation; the canonical lifecycle still requires
positive destructive-removal evidence.

Generated portal instances are delete-on-remove worlds. If a linked companion remains inside when
the instance closes, Tamework marks it during the world-removal event and publishes its complete
last-live state to Lost recovery when that world removes the NPC. Publication runs after the live
identity is withdrawn but before the shutdown observer clears the snapshot, so a later Recall uses
the strict recovery flow instead of leaving an Active/Unloaded row pointing at a nonexistent world.
Permanent worlds are not reclassified by this rule.
- `DeadRespawnCooldownMs` / `DeadRespawnCooldownMins`
- `DeadRespawnFollowRetryDelayMs`
- `DeadRespawnDistanceClose/Near/Mid/Far`
- `PlacementMinRelativeY`
- `PlacementMaxRelativeY`

The role-scoped `TwCompanionConfig` owns the respawn cooldown duration when it
exists. The linked panel projects the exact saved death deadline, shows the
remaining cooldown, and keeps Revive unavailable until that same deadline
passes. `TwGlobalConfig` supplies cooldown timing only when no enabled
role-scoped companion config exists.

## Feedback notes
Command feedback sounds are delivered as local 2D sound for the using player and in-world 3D sound for nearby others.

## Reloading
`/tw reloadconfig` reloads command item assets along with spawner and naming assets.

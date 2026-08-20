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
- Bonded roster/profile entry source: `BondedCompanionPanelRecordSource`,
  `BondedCompanionPanelEntrySourceService`
- Bonded presentation/actions: `BondedCompanionPanelFeaturePresentationSource`,
  `BondedCompanionPanelActionService`, `BondedCompanionPanelActionRouter`

The bonded collaborators are a delegated subsystem. They do not add bonded
state branches to generic command-roster persistence or use item metadata as
canonical roster storage.

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
  },
  "Ability1": {
    "Interactions": [ { "Type": "TameworkCommandHotswap", "Slot": "Q" } ]
  },
  "Ability2": {
    "Interactions": [ { "Type": "TameworkCommandHotswap", "Slot": "E" } ]
  },
  "Ability3": {
    "Interactions": [ { "Type": "TameworkCommandHotswap", "Slot": "R" } ]
  }
}
```

Overrides:
- `OpenSelectionMenu`
- `CycleSelection`

Command flutes must define all three ability interactions above. The command-menu
hotswap assignment is stored per item stack, but the item asset owns the physical
Q/E/R ability bindings.

When a command flute is equipped, its left-click command, right-click Command
Menu action, plus assigned Q/E/R slots, appear in the lower-right ability HUD.
The left-click slot switches to Link while the player is aiming at an NPC that
can be linked to that flute.
Each slot uses the shared vanilla-aligned frame and key badge; an unassigned
Q/E/R slot is hidden. Set a command entry's optional `Icon` to a texture path
when it needs custom HUD artwork. Standard command IDs use Tamework's bundled
command glyphs when `Icon` is omitted.

Set `ShowInRadial: false` on a command entry to offer it through the hotswap
selectors without consuming one of the radial menu's eight slots.

Generic command rosters also offer a `Cycle Group` hotswap action. Assign it to
Q, E, or R to cycle `All Companions`, then each non-empty named group in its
saved display order, and back to `All Companions`. The flute HUD shows the
current recipient scope above its ability icons: a named group uses its saved
color, while All, Custom Selection, and No Active Companions use neutral,
gold, and subdued-gray states respectively. This action is not offered by
bonded-companion rosters, whose active profiles are stored outside generic
command-group metadata.

### Optional example and acquisition boundary

The optional `Alec's Tamework! Examples` pack includes
`Tamework_Command_Whistle_Example` and the `TwCommandExample` config as
development/reference assets. The config includes
the HyDragon-relevant `Follow`, `Hold`, `Recall`, and `AttackTarget` commands,
but the item has no recipe or other polished player-acquisition path. Enable
the example pack only when an operator or development workflow needs the item
directly.

Downstream mods should ship their own named item, command config, localization,
icon, and recipe/acquisition flow. Do not present the example whistle as a
player-ready Tamework reward or silently depend on players finding it.

## Roster storage modes

`RosterStorage` selects the authority behind the panel:

- `ItemMetadata`: legacy/default links stored on the particular item;
- `OwnerCommandFamily`: the owner's durable generic command-family roster; or
- `BondedCompanions`: the separate bonded profile-and-lease authority.

A bonded command config must declare an existing namespaced `BondedRosterId`.
It must not declare `CommandFamilyId` or
`ProjectRosterToItemMetadata`; those belong to the generic family-roster path.
Several bonded policy assets may contribute different families to the same
roster ID, so one item can display all of them in one panel while each profile
retains its family-specific capacity, timer, cooldown, revive recipe, and
feature switches.

The bonded item is an access and command surface, not companion storage. Its
cards are keyed by stable bonded profile IDs. Copies of the same access item do
not fork profiles, leases, or state.

## Recipient selection and linking

Command items using `RosterStorage: BondedCompanions` have a separate recipient
authority. A command resolves only `ACTIVE` profiles owned by the player in the
configured `BondedRosterId` whose current-world live UUID, profile ID, and lease
token exactly match the NPC's bonded projection marker. Stored, dead, expired,
other-world, duplicate, or stale projections are not command recipients.

Bonded commands never create generic NPC links, reconcile or project linked rows
onto the item, or queue generic unloaded/cross-world relocation. Summon, dismiss,
and revive remain profile-keyed panel actions. Normal commands still operate on
an exact loaded projection, and live command state such as a stored home position
travels with the bonded full snapshot. These rules do not change recipient or
link behavior for `ItemMetadata` and `OwnerCommandFamily` command items.

Bonded profiles expose exactly three panel states:

- `STORED`: show Summon only when policy, cooldown, capacity, and current world
  context permit it;
- `ACTIVE`: show Dismiss/Store and dispatch normal commands only to the exact
  current projection; and
- `DEAD`: show the complete paid-revive quote when revival is enabled.

Revive returns the card to `STORED`; it never automatically summons. Logout,
world transfer, expiry, missing-projection recovery, and duplicate cleanup also
converge to `STORED`. Bonded cards never display generic `UNLOADED`, `LOST`,
`CAPTURED`, `COOPED`, or `ROSTER_STORED` aliases.

Every bonded summon gives the new live projection full health. Captured or
stored health can describe the durable card while the companion is stored, but
it does not reduce health on the next live summon.

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
- Optional per-tool active highlights. While the command tool is equipped,
  loaded active NPCs show a controller-only indicator above their heads in
  their group color.
  Ungrouped NPCs use neutral gold. This setting starts disabled and applies
  only to generic item-metadata rosters.
- Group active selector: `All`, `None`, or one configured group
- Breeding enable/disable row toggles (default: disabled)
- Group assignment overlay per row
- Group manager flow (create/rename/recolor/delete)
- Status lanes for loaded, unloaded, captured, cooped, roster-stored,
  provisioned-dormant, dead, and Lost companions; ordinary unloaded rows keep
  the latest custom display name from the live snapshot or durable profile,
  including across restart
- Per-row actions: `Locate`, `Recall`, `Set Home`, `Return Home`, `Unlink`, `Revive` (when enabled/ready), plus nearby-only `Release`/`Cull` behind confirm flow
- Loaded normal linked rows whose role enables `FlightToggle` show the same
  ground/flight icon button as bonded roster cards. The action is available
  only while the live controller is recognized and the row remains linked to
  the currently authorized command item.
- Owner/command-family roster rows additionally show their authoritative
  timed-summon state, remaining duration or cooldown, active population count
  and limit, with `Summon` and `Dismiss` actions where valid.
- Dead and Lost owner/command-family roster rows use the server-authoritative
  paid revival quote. The confirmation lists every exact cost component and
  owned/required quantity; the replacement paid-revival API performs the
  mutation. Legacy item-metadata links retain their existing free restoration
  behavior.
- Bonded roster rows use their own profile-first view. They show complete
  durable details immediately after capture, summon, store, revive, and relog;
  a live projection is optional enrichment, not the source of the card.
- Bonded rows use the dedicated final companion card: state accents distinguish
  `IN WORLD`, `STORED`, `DEAD`, and revive-ready states; health is always
  shown from the durable snapshot; a thin XP strip sits above the health bar
  without changing the card layout; and happiness, hunger, and thirst appear
  only when that saved role state actually has the corresponding component.
  Active bonded rows project current level, XP, and available talent points from
  their exact live companion, coalesced to avoid interrupting panel controls.
  Stored and dead rows continue to use their durable progression snapshot. The
  talent shortcut and XP strip open the existing talent page while the companion
  is active; their shared tooltip splits each modifier into its total, level,
  talent, and trait contributions.
  Dead cards retain their complete compact revive-cost list, including
  owned/required quantities, while the existing confirmation overlay remains
  the payment authority.
- A bonded roster may set `SummonAuraEffectId` to an optional `EntityEffect`.
  Tamework applies it only after a newly created projection is confirmed; it is
  cosmetic and never changes the durable summon result.
- The red unlink control is a two-click permanent abandonment confirmation.
  It deletes the complete bonded profile and its retained extensions. An active
  companion is only deleted after its exact live projection has been removed in
  the current world; if that removal cannot be confirmed, the profile remains
  intact.
- Bonded revival quotes every configured cost line and reserves the complete
  recipe atomically. A successful revive produces a stored card and no live
  projection.
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
- Destination chunks and exact cubic source entity sections are requested
  asynchronously. Source probes do not generate a missing section.
- Every loaded source or destination chunk is retained for the lifetime of the pending relocation and released on success, timeout, replacement, cancellation, or shutdown.
- The source NPC is resolved after its chunk loads and checked against its
  canonical profile and current alias before any move is applied.
- Canonical roster entries retain the world-qualified home as a durable source
  hint, so restart does not make Recall request home coordinates in the
  destination world.
- Repeated clicks for the same command reuse that pending request, even if the player moved, while a command targeting another world or state remains distinct.
- Retries run on bounded interval/time windows, and one click is sufficient while the attempting-recall status is shown.
- A persistence preflight denial reports its status-specific availability
  message and leaves the last canonical state intact.
- On-load relocation resumes through `CommandNpcRelocationOnLoadSystem` after
  the saved source entity becomes available.
- A relocation that attempted its physical move but remains temporarily
  unobservable stays `UNLOADED`, not `LOST`. Observing the destination
  projection restores normal loaded status.
- If an explicit Recall exhausts every read-only source probe before any
  physical move, Tamework creates a fenced Lost snapshot from the current
  durable profile and retires the missing alias. The entry then offers normal
  Respawn. Role, owner, name, tame state, home, and command links are retained;
  live-only state that was never persisted uses normal defaults.
- When available, a public `v2.16.1` recovery snapshot is preferred because it
  retains more complete state. The importer
  released coop row retained a complete owner-bound snapshot. The importer
  normalizes that snapshot to the released current alias. If the companion was
  absent during its first startup reconciliation and an explicit Recall later
  exhausts every lookup before any physical move, Tamework consumes that exact
  one-use artifact and changes the entry to `LOST`, where normal Revive is
  available. Later lifecycle revisions, malformed or ownerless snapshots,
  Return Home, and unconfirmed physical transfers cannot use this recovery.
- An older replacement database can contain profiles quarantined by stale
  capture, death, Lost, or coop flags from the public database. On startup,
  Tamework repairs each unchanged profile in place when the original public
  database still matches the committed import fingerprint and the corrected
  evidence has one unique newest complete state. The importer leaves that
  source file untouched; it is read as evidence and is not restored over the
  current database. No world or database rollback is required. Changed
  profiles, missing or changed source evidence, tied timestamps, and incomplete
  evidence remain quarantined.

Lost flow:
- A background timeout alone does not author durable `LOST`. A clean explicit
  Recall exhaustion can authorize the fenced repair described above. The
  shipped default wait budget is 10 seconds.
- A durable Lost transition requires positive evidence. An external destructive
  command using Hytale's `REMOVE` reason qualifies; ordinary unload, absence,
  and timeout observations do not.
- A destructive removal that races startup reconciliation can also author Lost
  from the exact current alias while its full live state is still available.
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
`/tw reloadconfig` reloads command item assets along with spawner and naming
assets. Bonded roster policies and their dependent bonded command configs are
accepted as one coherent generation. An invalid or missing `BondedRosterId`
rejects the new generation instead of partially swapping roster or command
lookups.

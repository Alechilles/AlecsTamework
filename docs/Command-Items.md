# Command Items (TwCommandItemConfig)

## Overview
Command items let players manage owned companion NPCs through a tool and dispatch commands at runtime.
Ordinary `ItemMetadata` flutes show all of the owner's companions automatically;
the item keeps only that flute's selected command recipients. Legacy link records remain
readable and are migrated as players use the item.
The system is asset-driven around:
- `TwCommandItemConfig`
- `TameworkCommand` item interaction
- `CommandItemFeatureHandler` orchestration

## Companion portraits

Normal and bonded roster panels resolve portraits from `TwDynamicIconConfig`
assets using the companion's role and current or saved attachment selections.
The lookup is independent of spawner items. See the
[dynamic icon reference](../wiki/Modder-Documentation/Config-Reference/TwDynamicIconConfig-Reference.md)
for authoring and precedence.

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

The standard menu presents LMB, Q, E, and R assignments in a compact sidebar beside
the companion list. Ordinary `ItemMetadata` flutes open an owned-companion panel;
all owned companions appear automatically, so a player does not need to link each
animal to every flute. The selected command recipients remain specific to the
physical flute, which lets players keep separate working sets on separate items.
Clicking an owned NPC with the flute toggles that NPC's selection for the current
flute. The same selection button is available on the card. A selected companion
appears before unselected companions, and browsing, searching, or changing status
tabs never changes the command recipients.

The panel has `In World`, `Stored`, `Lost / Dead`, and `All` status tabs, each showing
the number of companions matching the current search and nearby filter. The selected
tab uses a green highlight. `Nearby only`
is an additional view filter. The search field matches the companion name, species,
or group as one literal text search. Sort choices still keep selected companions first.
The held item's role and command support are checked separately, so an owned companion
can remain selected while being unavailable to a specialized flute; the panel explains
that restriction instead of changing the selection.


### Saved views on each flute

The view picker sits beside the status tabs. **All companions** clears the filters;
**Selected on this flute** shows that flute's current recipients. **+ Filter** opens a
small editor where you choose species, groups, or selected-only filtering. Choose
species or groups in its dropdown and select **Save filter** to apply that filter
to the current view. The dropdown shows the selected names when closed. Multiple
choices within one filter match any chosen value; different filters narrow the
list together. The filter-chip row appears only while extra filters are applied.
Click a chip to edit it or its red X to remove it.

The toolbar save icon flashes cream and amber with a gold outline when the current view
differs from its saved version. Hover over it to see the unsaved-changes hint.
The icon saves those changes to the selected view.
For a new view, it asks for a name and saves the current status, nearby setting,
search, sort, and extra filters together. The edit icon opens the name field and
Delete view action. Each flute holds up to 16 views and reopens to its selected
saved view. Deleting the current view returns to All companions. Filters remain
rules, so newly owned animals that match appear automatically. An unavailable
species or group stays in the filter until removed.

When a named saved view is selected, the flute's item name adds that view name
and its tooltip lists the saved filters below the normal description. Unsaved
changes in the menu do not change the item tooltip until the view is saved.

Changing a view does not change command recipients. **Select all matching** replaces
that flute's selection with eligible matches across every page, up to the flute's
command limit. The chip row reports selected animals outside the current view.

The fixed footer always shows the matching range and total. Page controls appear
only when there are multiple pages; Previous and Next appear only when available.
Filters and sorting apply before pagination. Page navigation is temporary; opening
the flute or changing its view starts at the first page.

Changing LMB updates the selected primary command and keeps the menu open.
The original primary-command eligibility rules still apply.

Group shortcuts below the command assignments activate all companions, none,
or a named group. The selected shortcut is highlighted, and the list scrolls
when needed. The menu title includes the current mode and roster count.

The standard menu displays 50 companion cards per page by default. Server owners
can change **Command panel cards per page** in `/tw settings` to 1–100. Previous
and Next switch pages without changing command selection. Search, status tabs,
and sorting apply to the complete roster. Ordinary flute menus calculate detailed
status and tooltips only for the current page.

For ordinary command items, groups belong to the player and are shared by compatible
flutes. A companion may belong to several groups or none. The group dropdown on each
card is a native multi-select control: toggle memberships inline and close it without
opening another page. The dropdown border shows the group's color. Left-clicking a group
shortcut replaces the current flute's selection with that group. Right-clicking adds
its members while keeping existing selections, including individually selected animals.
Groups whose eligible members are all selected are highlighted. Neither action changes
group memberships. `Add group` opens group creation and `Clear selection` removes all
selected recipients from that flute. Legacy single-group item metadata remains readable
and is imported into the owner group state.

Set `ShowInRadial: false` on a command entry to offer it through the hotswap
selectors without consuming one of the primary selector's eight slots.

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

- `ItemMetadata`: per-item selection records, with the owned companion list resolved
  from the player's profiles;
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
travels with the bonded full snapshot. These rules do not change recipient behavior
for `OwnerCommandFamily` command items. For ordinary `ItemMetadata` flutes, ownership
discovers the panel rows and the item's active records determine which rows receive
a command.

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
- legacy optional `groupId`; current ordinary groups are player-owned and may contain
  multiple memberships per companion

Inactive linked rows stay visible in the panel, can still use per-row actions, and are
excluded from bulk dispatch. For an ordinary `ItemMetadata` flute, an inactive row is
the same companion still owned by the player but deselected on that flute.

Entity UUIDs are projection aliases, not the companion's durable identity. When a stable profile is known, command records and recovery flows resolve historical UUIDs through that profile and deduplicate by profile. Unresolved legacy records continue to fall back to UUID until they can be bound safely; ambiguous bindings fail closed instead of spawning a replacement.

For online players, command-item copies in the hotbar, storage, and backpack are lazily canonicalized when the player enters a world and whenever a linked command item moves through those inventory compartments. Offline inventories are not rewritten directly; their records remain safe through profile-first resolution and are normalized on the next load or use.

When a player tames a supported NPC, ordinary `ItemMetadata` flutes discover it through
the owned roster and do not need a separate link. Left-click selection adds the NPC to
that flute's per-item recipients after the normal owner, tame, role, and capacity checks.
Owner/command-family and bonded rosters retain their own membership and lease rules.

When a linked companion is placed in a compatible handheld capture item, its
linked-panel row remains available and reports `CAPTURED` as soon as capture
commits, including when capture clears live ownership. Releasing it as the
same owner restores its command links and remaps the panel record to the new
live entity UUID without changing the stable profile. If another player
releases the item and acquires ownership, the successful release removes the
former owner's command links. Their card disappears on the next panel refresh
and stays removed if the new owner captures the companion again. Trading the
item alone, or a failed release, does not remove the captured card. Items
without saved evidence of the cleared owner retain their existing links.

### Owned panel mode

The generic panel now uses one owned-companion list. It reads the existing profile
projection by owner and supplements it with loaded owned NPCs, including animals with
no item records. It does not apply the flute's role filter or a radius limit when
building the list; compatibility is shown on each card and checked when a selection
or command is applied. Saved unloaded and other-world animals remain visible.
`In World`, `Stored`, `Lost / Dead`, and `All` tabs filter that list, with selected
companions first in every tab. `Nearby only` and the unified name/species/group search
only change what is shown. None of these browsing controls changes the flute's
recipients.

Each physical `ItemMetadata` flute has its own selected set. Selection is independent
of the shared player groups, so an animal can be selected without a group, in several
groups, or in no group. Recall, Locate, and Revive/Recover also work for unlinked
generic profiles after a fresh ownership read, with the existing lifecycle and
recovery rules. Home and permanent ownership-release actions retain their existing
link/storage requirements. Captured and cooped animals must leave their storage
lifecycle first.
Managed command-roster companions remain read-only through generic command items;
their roster controls own removal.
Terminal removal checks the target profile's authority and pending claims rather
than requesting owner capacity. An owner-level quarantine caused by another animal
does not block this cleanup; the target's own quarantine or unfinished operation
still does. Existing saved owner-scoped removals retain their original scopes on replay.

Owned discovery runs inside the existing open-panel refresh on the owning world
thread. Each pass reads immutable profile data and scans the current world's
loaded NPCs once; it does not load other worlds or chunks. It uses the existing
mutation/progression refresh signals and 30-second safety refresh, which covers
ownership and load changes without a dedicated discovery event. There is no new
scheduler, background world access, or persistent cache; closing the page ends
its refresh lifecycle.

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

### Custom Java command-menu composition

An effective `TwCommandItemConfig` can select one namespaced `UiRendererId`
and an ordered `UiContributors` list. The renderer plugin owns the layout and
client UI assets. Contributor plugins supply isolated page data, row data,
server actions, and optional custom flows.

The controller can use a different layout and update one selector at a time.
Tamework still supplies the full detached snapshot, row and section change
hints, opaque built-in and contributor action handles, current-world action
validation, page cleanup, and standard-menu fallback. Host updates are partial
(`clear=false`), so one card indicator can change without a page rebuild.

A required contributor that is missing, incompatible, or failed causes
standard-menu fallback. An optional contributor can fail or unregister while
the custom page continues with an unavailable status for that namespace.
Selecting a renderer affects only command configs that name its exact ID.

Renderers can reproduce the built-in group manager and generic or bonded
talent pages. Contributors can also define server-authoritative actions and
custom `OPEN`, `REPLACE`, `UPDATE`, and `CLOSE` flows. Tamework validates
targets, ownership, registration generations, revisions, input, confirmation,
and costs. The client cannot supply gameplay authority.

Client UI files live below `Common/UI/Custom`. Runtime append paths are
relative to that directory. See the durable wiki reference and recipe for
registration, capability, bounds, diagnostics, and cleanup details.

The standard animal panel also supports optional contributor summaries and
portrait stars without a custom renderer. See the
[standard-panel decoration contract](../wiki/Modder-Documentation/Public-API/API-Reference/Command-UI-Provider-API-Reference.md#optional-decorations-in-the-standard-panel)
for keys, detached traits, and layout limits.

### Custom Java command-HUD composition

For an inspection tool that should show the animal target HUD without the
command-control strip, set `HotswapHudEnabled` to `false` in its command-item
config. The default is `true`; omitted values inherit from the parent. This
does not change item interactions. Disable `LinkEnabled` and use an empty
`CommandList` when the tool should not offer commands.

The target HUD and equipped-tool hotswap strip are independent presentation
surfaces. A Java plugin can register a renderer for either surface through
`TameworkApi.commandHud()`. Command-item config selects them independently:

```json
{
  "TargetHudRendererId": "runeteria:husbandry_target",
  "TargetHudContributors": [
    { "Id": "runeteria:husbandry", "Required": true }
  ],
  "HotswapHudRendererId": "runeteria:husbandry_hotswap",
  "HotswapHudContributors": [
    { "Id": "runeteria:husbandry", "Required": false }
  ]
}
```

Each renderer owns its `.ui` layout. A path passed to
`UICommandBuilder.append(...)` is relative to `Common/UI/Custom`; for example,
`Common/UI/Custom/Rune_UI/HusbandryTarget.ui` is appended as
`Rune_UI/HusbandryTarget.ui`. Omitting or invalidating one renderer selects the
standard HUD for that surface only.

Target renderers receive an immutable `CommandTargetHudSnapshot` with target
identity, vitals, cooldowns, food, attachments, tame requirements,
progression, traits, and owner display data. Hotswap renderers receive an
immutable `CommandHotswapHudSnapshot` with `primary`, `secondary`, `q`, `e`,
`r`, and `groupStatus`. Both views also expose isolated contributor data.

The host always sends the complete current view, but it uses `clear=false` for
updates. Renderers can update one component, such as a card indicator, by
using the focused change hint. Target hints identify `IDENTITY`, `VITALS`,
`COOLDOWNS`, `FOOD`, `ATTACHMENTS`, `TAME_REQUIREMENTS`, `PROGRESSION`,
`TRAITS`, `OWNER`, or `CONTRIBUTIONS`. Hotswap hints identify `PRIMARY`,
`SECONDARY`, `Q`, `E`, `R`, or `groupStatus`. Contributor paths are local to
the contributor namespace and are bounded to 256 paths; overflow requests a
full contributor refresh.

See [Command HUD Renderer and Contributor API Reference](/mod/alecs-tamework/command-hud-renderer-and-contributor-api-reference)
for lifecycle, fallback, and registration details.

Linked panel supports:
- Owned companion tabs: `In World`, `Stored`, `Lost / Dead`, and `All`
- Selected companions first in every tab; selected state is per physical
  `ItemMetadata` flute
- `Nearby only` view filter and one literal search field matching name, species,
  or group
- Sort: `Default`, `Name`, `Species`, `Group` (selected companions stay first)
- Active/inactive row toggles, with LMB on an owned NPC toggling the current flute's
  selection
- Optional per-tool active highlights. While the command tool is equipped,
  loaded active NPCs show a continuous controller-only indicator above their
  heads in their group color. Ungrouped NPCs use neutral gold. The indicator
  uses an invisible helper mounted above the NPC for smooth movement. The
  helper is removed while a player rides that NPC, then returns after the rider
  dismounts. It is also removed when the setting is disabled or the equipped
  command tool changes.
  This setting starts disabled and applies only to generic item-metadata
  rosters on Update 6. Update 5 does not run the indicator system because it
  lacks the required model-particle cleanup support.
- Group shortcuts in the sidebar: `All`, `None`, or one configured group. A group
  shortcut selects its members for the current flute.
- Shared player groups with multi-membership, native inline multi-select card dropdowns,
  and bottom `Add group` / `Clear selection` actions
- Breeding enable/disable row toggles (default: disabled)
- Group assignment dropdown per row, with a group-colored border
- Group manager flow (create/rename/recolor/delete)
- Status lanes for loaded, unloaded, captured, cooped, roster-stored,
  provisioned-dormant, dead, and Lost companions; ordinary unloaded rows keep
  the latest custom display name from the live snapshot or durable profile,
  including across restart. Saved health, needs, traits, progression, and applicable
  breeding/harvest indicators remain visible with muted last-known values.
  Missing saved timing is marked unknown; live-only actions remain unavailable.
- Per-row actions: `Locate`, `Recall`, `Set Home`, `Return Home`, and `Revive`/`Recover` (when enabled/ready). In the generic companion tabs, the red X opens `Release`, plus `Cull` for a loaded, living animal. The selection toggle leaves an animal out of commands without releasing it. Release replaces Abandon and permanently clears ownership; captured and cooped animals must leave storage first.
- Action icons reuse two normal/hover frame textures and sixteen separate glyphs. Flight, shoulder, and breeding toggles show their current state. Larger cards keep passive traits separate and display needs plus applicable cooldowns as horizontal meters.
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
- Matches the charcoal command menu with percentage meters for happiness, hunger, and thirst, and time/readiness bars for breeding and harvesting. Only applicable sections appear. Appearance attributes remain text pairs such as `Coat: Brown`, below the food rows.
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
- `Locate` also works for captured and cooped animals in Linked and Owned modes.
  It shows recorded coop coordinates or the observed capture item's holder: a player,
  standard storage block, or dropped item. Offline/unloaded holders are marked last seen.
  Unknown item locations do not imply death or destruction. No periodic inventory or world scan runs.
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
`/tw config reload` reloads command item assets along with spawner and naming
assets. Bonded roster policies and their dependent bonded command configs are
accepted as one coherent generation. An invalid or missing `BondedRosterId`
rejects the new generation instead of partially swapping roster or command
lookups.

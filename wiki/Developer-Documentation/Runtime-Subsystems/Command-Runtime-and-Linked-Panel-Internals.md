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
- Ordinary companion browsing and player-owned grouping: `CompanionPanelChrome`,
  `CommandCompanionPreferences`, `CommandCompanionGroups`,
  `TameworkCompanionGroupsComponent`
- Active-NPC indicators: `CommandActiveNpcHighlightSystem`,
  `CommandActiveNpcHighlightDisplayTracker`, `CommandActiveNpcHighlightEmitter`
- Group flows: `CommandGroupService`, `CommandGroupAssignPageService`, `CommandGroupManagerPageService`
- Relocation: `CommandRelocationDispatchService`, `CommandNpcRelocationService`,
  `CommandRelocationRetryCoordinator`
- Canonical status and restoration: `CommandPersistenceView`,
  `CommandNpcProfileActionResolver`, `CommandCompanionRestorationService`

## UI layer

Generic item-linked cards resolve membership from the canonical profile's tool
links; unresolved legacy records remain visible. Owned and command-family
roster views retain their own membership rules. A captured item that records
cleared ownership and its former owner revokes those tool links when a
different player successfully releases it with owner assignment enabled.
Release clears both the restored NPC's tool IDs and the durable links in the
existing release transaction. Capture and failed release leave the links
intact. The normal panel refresh reads the published membership, so stale item
records cannot restore a card after transfer or a later recapture.

- `TameworkCommandSelectionPage`
- `TameworkCommandGroupManagerPage`
- `LinkedNpcPanelCardBinder`
- `LinkedNpcPanelStatusTextService`
- `LinkedNpcTraitIndicatorBinder`

## Companion guide

The standard command page owns a `TameworkCompanionGuide` overlay for both
ordinary and bonded panels. Its `guide:` events only change local presentation;
they do not enter companion action routing. While the guide is visible, the
page ignores underlying command and assignment events. Closing it hides the
overlay and keeps the mounted panel and its session intact.

Guide examples reuse the production linked and bonded card UI assets with
detached sample entries and visual binders. They bind no companion actions;
only guide navigation emits events. They do not create NPCs,
resolve live companions, issue commands, or save state. The guide has no
executor, listener, or persistence owner; its lifetime is the command page's.

All guide copy, including sample labels and page navigation, uses
`tamework.ui.guide.*` language keys, alongside the real cards' shared language
keys. The guide is translated into all six supported languages. The UI
document contains no literal guide text.

## Persistence model
Ordinary `ItemMetadata` command tools persist their per-item selection records and
panel preferences on the physical item. The owned panel discovers all owned
companions from the player's profile projection, then uses those records only to
decide which rows are selected for that flute. A selected row is eligible for
dispatch only after the held item's owner, tame, role, command, and capacity checks.
Legacy link records remain readable and preserve their existing active flags during
migration; a record may also carry the stable profile ID so an old entity UUID can
be canonicalized.
Player-owned group definitions and multi-membership assignments live in
`TameworkCompanionGroupsComponent`, shared by compatible ordinary flutes. Group
selection is a one-time mutation of the current flute's per-item records; browsing
the group or changing its memberships does not become a new persistence authority.
Owner/command-family rosters instead persist membership and summon state in the
replacement store; command items are interfaces to that durable roster rather
than its authority.

The replacement profile projection is the authority for lifecycle status,
canonical name, and restorable state. Entity UUIDs are replaceable aliases:
historical UUIDs resolve back to the same profile before relocation,
restoration, or spawn decisions.

Offline command cards read saved full-state snapshots and exact entity checkpoints
through the existing persistence queries. A bounded read-only cache retains at most
256 profiles and admits at most 16 reads at once. Profile updates invalidate cached
values; unchanged results expire after one minute and unavailable results retry after
ten seconds. Completion signals refresh subscribed owner menus through the existing
world-thread dispatcher. The command feature handler closes the cache and subscriptions
at shutdown. Saved card values never authorize a live action or mutate persistence.

## Important runtime seams
- Ordinary `ItemMetadata` panels use one owned entry source, then apply the status
  tabs (`In World`, `Stored`, `Lost / Dead`, `All`), `Nearby only`, and literal
  name/species/group search as view-only filters.
- Selected rows sort before unselected rows for every supported sort.
- Nearby and legacy linked modes remain separate entry sources where those modes are
  still exposed.
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
- Explicit Recall can continue automatically after checkpoint recovery loads
  the source entity. Recovery logs identify that retry; the drop warning alone
  does not mean Recall has finished. Linked and Owned cards retain their recall
  countdown alongside inline location details while relocation is pending.
- Selection and command execution apply the command tool's role and command policy.
  A role or command restriction produces a localized explanation; ownership alone
  does not make a companion eligible for every command tool.
- Active-NPC indicators are sent only to the controlling player. Each loaded
  target gets one invisible, non-persistent helper entity mounted above its
  model bounds. Its mount component is added after spawning so Hytale registers
  it as a passenger and detaches it immediately when the parent is removed;
  this safety cleanup does not wait for the roster sweep. The helper receives
  one persistent particle emission. A roster,
  color, setting, equipped-tool, player, or NPC lifecycle change removes the
  helper and its particle before a replacement is created. A rider mount also
  removes the helper before Hytale changes the NPC mount graph. Reconciliation
  suppresses the helper during the mount lifecycle and creates it again after
  dismount. Native mount snapshots resolve the saved role behind `Empty_Role`,
  so the linked panel keeps the companion's real name and species. The bounded
  roster pass also syncs each helper's server tracking position; a large parent
  jump recreates it. This subsystem registers only on Update 6 because Update
  5 cannot safely clear the effect.

## Captured-animal Locate

Generic Linked and Owned cards display location details inline for captured,
cooped, and unloaded companions. The existing saved-panel cache carries immutable
coop slots and capture identities from its asynchronous profile read. Normal
card refreshes read the advisory item index; they do not dispatch holder
verification or scan inventories. Inline item locations remain advisory
observations, but omit timestamps. A page-owned copy toggle switches labeled axes
to a selectable raw coordinate tuple without using the clipboard API.
Card assembly reads the viewer's current transform on the owning world thread
and adds rounded horizontal direction offsets only for an exact world-name match,
before instance names are shortened for display. The existing card refresh updates
these offsets; no additional scan or timer is used. Coordinate selection temporarily
replaces the distance line with its copy hint.
Sightings retain optional item asset IDs and container block IDs,
resolved to localized names when displayed. Older cache entries remain readable
with generic item and container labels.

`CommandLinkedNpcLocateService` reads the canonical profile on demand. Coop addresses
come from its `CoopSlotKey`; capture sightings must match the profile and current
capture snapshot ID. Sightings cannot change ownership, lifecycle, or recovery state.
Owned-mode authorization permits stored states only for Locate.

The `items.locate` observers scan a player's built-in inventory sections once on
load and a standard `ItemContainerBlock` once on load. Afterwards they inspect the
affected transaction's metadata and rescan only a holder whose capture items changed.
Same-holder notifications are coalesced, with at most 2,048 pending refreshes; excess
notifications can leave a sighting unknown or stale until another event or Locate.
Dropped items use add/remove events, with fresh coordinates read only on Locate.
No periodic ECS, player, inventory, or chunk discovery scan runs.

The index retains at most 8,192 captures and uses reverse holder membership for
updates. A dirty snapshot is written off-thread at most once a minute to
`cache/captured-item-locations.json` beneath the runtime data directory. This is a
disposable advisory cache, not a persistence authority. Restarted sightings are stale;
corruption or eviction loses only location hints. Shutdown removes container listeners,
stops the saver, and clears the in-memory index. Ordinary item changes do not write
the cache. Locate admits one pending request per viewer, up to 256 total, with a
five-second deadline, and verifies one recorded holder without loading its chunk.

Legacy capture items without a receipt, custom nonstandard storage, and direct
third-party item mutations that bypass engine events may remain untracked. Standard
player, block-container, and dropped-item observations are made on the owning world
thread; deferred work carries only stable IDs and immutable sightings.

## Related Pages
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
- [Command and Debug Internals](/mod/alecs-tamework/command-and-debug-internals)




## Companion portraits

`TwDynamicIconConfig.resolveIcon(roleId, attachments)` provides shared companion
icons to filled spawners and normal/bonded panel presentation. Loaded rows use
current model attachments; offline and stored rows use saved appearance data.
No spawner registry or capture item is needed to resolve a portrait.

`CommandNpcPortraitAssets` creates icon-only display items from enabled dynamic
icon assets during core-owned asset callbacks. Asset changes invalidate role
lookup and register new icon paths; existing display aliases remain until
shutdown. Panel binding reuses the item renderer and existing PNGs. No entity
access, periodic scan, or new executor is added by dynamic icon resolution.

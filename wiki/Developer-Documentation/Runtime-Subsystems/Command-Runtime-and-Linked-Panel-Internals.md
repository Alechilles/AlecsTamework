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

## Update 7 rune inputs

Update 7 reserves `Ability2` and `Ability3` for rune casts. While a Tamework
input item is selected, `RuneInputRuntime` binds those two slots on the player's
`Interactions` component, which the engine resolves before the rune slots. The
player's runes are never moved. The bindings are removed when the item is
switched, and an entry another owner already holds is left alone. A rune lease
saved by a build before 5.2.1 only hands its runes back.

The bound root forks the held item's own `Ability2` or `Ability3` root with a
hotbar context, so command metadata and assignments still belong to the
physical tool. It checks the binding and selected item again before dispatch. A
late cast after a switch cannot invoke the old tool's action.

An item qualifies when it has `Ability2` or `Ability3` roots and is either
listed in a `TwCommandItemConfig` or carries `Tags.Family: ["TameworkInput"]`.
It also needs a `Weapon` definition, because the Update 7 client only sends E
and R with a weapon in hand. The example whistle and HyDragon's Dragon Horn add
the tag and weapon definition through Patchwork patches that apply only when
the native Update 7 rune assets exist, preserving the items' tool
classification on older servers. The Flightmaster's Talisman declares both in
its base item. Keep the existing E/R roots for Update 6.

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
- Status and restoration: `CommandPersistenceView` (reads the companion index
  through `CompanionQueries`), `CommandNpcProfileActionResolver`,
  `CommandCompanionRestorationService`

## UI layer

Generic item-linked cards resolve membership from the tool IDs on the
companion's index record; unresolved legacy records remain visible. Owned and
command-family roster views keep their own membership rules. The owned panel
lists the companions the index records for the viewer
(`CommandOwnedPanelRecordSource`), so a card follows the record's current owner
when a captured companion changes hands. Releasing a companion to the wild
clears its tool links. Capture and a failed release leave them intact. The
panel refresh reads the index, so stale item records cannot restore a card after
a transfer or a later recapture. Bonded companions never appear in ordinary
panels, and command-family roster members are read-only there.

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
companions from the companion index, then uses the flute's records only to
decide which rows are selected for that flute. A selected row is eligible for
dispatch only after the held item's owner, tame, role, command, and capacity checks.
Named view definitions live in the player's saved `TameworkCompanionViewsComponent`,
shared by ordinary command flutes. The selected view ID and current draft remain in
each flute's `Tamework.Command.CompanionViews` item metadata. These are presentation
settings only. Opening the ordinary menu restores that flute's selected preset;
items without view metadata keep their previous preferences. A missing named view
falls back to All companions. Saving, renaming, or deleting a shared view refreshes
other carried flutes' tooltips without changing their selections or drafts;
deleting the selected view resets the active flute to All companions.
The paged source retains unfiltered summaries for options/counts, evaluates all view criteria
before slicing, and hydrates details only for the selected page. Select all matching resolves
fresh owned entries, revalidates each candidate, and applies the flute's existing capacity.
No generic or bonded lifecycle storage is added.

Legacy link records remain readable and keep their existing active flags; a record
may also carry the stable profile ID. A link that names an old body UUID from a
3.x or 4.x world resolves to its companion through `legacy-aliases.json`.
Live owned companions awaiting profile discovery also read their selected state
from the current flute's records, so newly tamed animals show selection immediately.
Managed admin-spawned companions use the ordinary command path and appear in owned
panels like any other companion.
Player-owned group definitions and multi-membership assignments live in
`TameworkCompanionGroupsComponent`, shared by compatible ordinary flutes. Group
selection is a one-time mutation of the current flute's per-item records; browsing
the group or changing its memberships does not become a new persistence authority.
Owner/command-family rosters keep membership and summon state on the companion
records (`STORED` with reason `ROSTER`, plus the roster ID and slot); command
items are interfaces to that roster rather than its authority.

The companion index record is the authority for location, name, and restorable
state. An entity UUID is only the current body's ID: a UUID the record no longer
names resolves back to the same profile before relocation, restoration, or spawn
decisions.

Cards for unloaded companions read the record's in-memory presentation summary
(health, needs, happiness, breeding and harvest timers, level, XP, traits, life
stage). They read no files, decode no snapshot, and need no cache or refresh
signal. Loaded companions read their live components. Saved card values never
authorize a live action or change a record.

## Important runtime seams
- Standard command pages own a world-thread-local pagination state. The server setting
  `commandPanel.cardsPerPage` defaults to 50 and is bounded to 1–100. Ordinary flute
  sources filter and sort detached roster summaries before hydrating only the selected
  window. Status/nearby tabs and group selection use the complete summary roster.
  Care sorts read scalar values across that roster; other sorts do not request off-page
  saved-card snapshots. Background refreshes keep the same page, and shrinking results
  clamp the page index. External renderers and configured public UI contributors
  retain their full snapshot contract. A standard menu with such contributors
  paginates rendering, but still assembles their complete public snapshot.
- Bonded and owner-family standard panels paginate their existing read models after
  view filtering. The pre-detail hydration limit described above applies to ordinary
  owned-companion menus.
- Ordinary paged cards without managed features keep their controls mounted across
  page changes. The page holds at most the largest page size used during that open
  menu (bounded to 100), hides surplus slots on shorter pages, and releases the UI
  when the menu closes. Each slot binds its actions once and reads the displayed
  companion UUID from `CardTarget.Value` at click time. The server rejects off-page
  targets before invoking the existing action handler, which still checks current
  authority. It does not remap an old click to the new occupant of a slot. Managed,
  bonded, and public-contributor pages retain their existing binding behavior.
- Loaded card assembly reads trait components and configuration once to derive both
  trait indicators and the public trait view. This reuse lasts only for the current
  card build, so later component or configuration changes are read normally. Hidden
  location controls receive no child updates until visible, and ordinary level
  buttons no longer create or update unused ring widgets.
- Each ordinary panel refresh shares one read of the owner's index records, one
  managed-profile snapshot, and one decoded flute-record list across owned/captured records, row identities,
  and protected controls. Canonical tool membership determines selection without building
  the linked cards first. These display snapshots live only for the current refresh;
  command actions still resolve fresh authority.
- Live owned discovery uses the existing owner/world index, including newly tamed NPCs
  whose record was just created. The menu resolves those UUIDs on the viewer's world thread
  and rechecks current ownership and generic-target eligibility. Nearby mode uses the
  engine spatial index with the same exact distance and role checks. Neither path scans
  every loaded world entity on each menu refresh. Entity/owner events maintain the owner
  index, remove empty buckets, and plugin shutdown clears it.
- Happiness population queries reuse the existing five-second spatial snapshot and
  resolve species-family membership once per distinct role inside the exact query radius.
  Distant roles do not invoke family resolution. Each card shares its happiness result
  between the meter and explanation. The querying animal's breeding config, config
  reload invalidation, and snapshot lifetime are unchanged.
- Saved-card extension queries read one companion's namespace directly from its
  index record. They do not scan other companions' extensions.
- Countdown-only wakes update timer presentation from the current page snapshot. State
  mutations, progression polling, safety wakes, and timer expiration still request fresh
  data. Countdown presentation never enables an action or changes a record.
- Standard panel refreshes reuse their entry snapshot for group-selection controls.
  Text-filtered cards and unfiltered selection rows come from one entry build, so
  searching the list does not change the group-selection summary. Row decoration
  resolves owned profile identities in one batch per build instead of copying and
  scanning the server's profile map for every card. This lookup is display-only;
  action handlers still resolve current ownership at execution time.
- Ordinary `ItemMetadata` panels use one owned entry source, then apply the status
  tabs (`In World`, `Stored`, `Lost / Dead`, `All`), `Nearby only`, and literal
  name/species/group search as view-only filters.
- Selected rows sort before unselected rows for every supported sort.
- Nearby and legacy linked modes remain separate entry sources where those modes are
  still exposed.
- The record's location kind (`LocationKind`: `LIVE`, `ITEM`, `COOP`,
  `STORED`, `DEAD`, `LOST`, `RELEASED`) alone determines the card status; a
  `LIVE` record shows as loaded or unloaded from the runtime loaded-body map.
  Command-item display caches cannot override it.
- `CommandCompanionRestorationService` backs **Revive** (`DEAD`) and
  **Recover** (`LOST`, or `LIVE` with no loaded body). Both restore from the
  saved snapshot through `RestoreFlow`, which raises the generation, so a stale
  body or item copy is refused afterwards. A revive with an item cost takes the
  exact items first and refunds them on any result other than restored.
- Ordinary unloaded presentation reads the record's summary and display name
  before the older display name cached on the command item.
- Recall follows `RecallRoute`: a `LIVE` companion in the player's world is
  moved (its chunk is loaded first when needed); one in another world is
  restored near the player from its snapshot. Other locations refuse recall and
  use their own action. A same-world relocation that runs out before the body
  appears is restored near the player from its snapshot
  (`CompanionRestoreRecallSink`). Linked and Owned cards keep their recall
  countdown alongside inline location details while relocation is pending.
- An imported 3.x/4.x companion that has not been seen since the import is not
  recalled while its body is unloaded. While the saved-chunk locate pass runs,
  its card shows the "being located" status and Recall and Recover are refused.
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

`CommandLinkedNpcLocateService` reads the companion record on demand. Coop addresses
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
- [Companion Store and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
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

Portrait binding uses an immutable icon-to-item index shared across pages. Item
load and removal callbacks refresh the index, and shutdown clears it. The index
stores only icon paths and item IDs, preserves the first registered match, and
avoids scanning the entire item registry for every card on a page change.

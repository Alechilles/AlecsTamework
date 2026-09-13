---
title: "Linked Panel Guide"
order: 5
published: true
draft: false
---
# Linked Panel Guide

Parent: [Companion Controls](/mod/alecs-tamework/companion-controls) | [Player Guides](/mod/alecs-tamework/player-guides)

The linked panel is the side panel that appears with Tamework command tools. It is the main place for inspecting and managing individual companions.

![Tamework UI Showcase](https://wiki.hytalemodding.dev/storage/mods/019d3092-1857-713f-86a6-60f15c4e0a9e/files/9d39db03-a1d0-4805-8ded-d1a84d8278e8.jpg)

## What the panel shows
Use **? Help** at the top of the command menu to open the **Companion Guide**.
Choose a topic on the left to learn about cards, commands and groups, care,
breeding, traits and talents, capture and coops, finding and recovery, bonded
companions, travel and flight, or world utilities. The guide also covers taming,
ownership, linking, and naming for new players.

Example cards use sample values and cannot issue commands or change your
companions. Features, controls, and requirements depend on the animal pack and
server settings. Use the guide's Back or close button to return to the existing
panel without changing its filters or selection.

- Linked companions for the current tool
- Active and inactive status
- Loaded, unloaded, captured, housed in a coop, roster-stored,
  provisioned-dormant, dead, or `LOST` state
- Name, species or role label, and often health or cooldown indicators. A custom companion name remains visible after the companion unloads or the world restarts.
- Group membership when the tool uses groups
- Trait or progression indicators when the mod exposes them
- In some mods, happiness details including current and target trend, plus active impulse modifiers
- A thin red mark on the happiness meter shows the NPC's configured breeding happiness requirement. Hover over the breeding toggle to see the required happiness on the next line.

The panel derives captured, coop, roster-stored, provisioned-dormant, dead, and
`LOST` status from one saved companion lifecycle. Item metadata and an expired
recall timer do not override that status.

## Animal images

Regular NPC cards reuse capture-image rules for current or saved appearance.
Tamework registers hidden display-item aliases for these existing images so the
panel can show them without captured-item metadata or duplicate textures.
These are static images, not live model previews; available variants depend on
the animal pack. Bonded-roster cards use the same capture-image rules for their
saved appearance.

## Bonded roster layout

Bonded rosters have one toolbar with **All**, **Active**, **Stored**, and **Dead**
tabs, name search, and default/name/species sorting. The left column contains
command assignments. Active-capacity information comes from the roster policy;
when several capacity groups apply, hover over the capacity label for details.

Each card keeps the companion's name across the header, with its portrait,
health, traits, level, and talent controls below. These temporary summons do not
show happiness, hunger, or thirst meters. Stored health is muted, and dead
companions retain an empty health bar. The right side shows summon state and the
available **Summon**, **Dismiss**, or **Revive** action with a matching icon.
Finite summon sessions and cooldowns show their remaining time above a progress
bar. Clicking the card's X opens permanent deletion confirmation; **Cancel**
returns to the normal card without deleting the companion.

## Live bonded progression

When a bonded companion is active, its row shows its current level, XP, and
available talent points from that exact live companion. These updates are
grouped so the panel stays responsive while you use its controls. Stored and
dead companions continue to show their saved progression snapshot.

## Saved talent points

You can open the talent tree and spend or reset points for your dead and `LOST`
companions when Tamework has a complete saved restoration snapshot. Changes are
saved immediately and carry through revival or recovery. In Owned mode, an item
link is not required. Normal talent requirements still apply.

Ordinary unloaded companions must load before you can spend their points. Older
records without a complete saved talent snapshot cannot use offline spending.

## Panel modes
- `LinkedMode` shows companions linked to the current tool.
- `NearbyMode` shows nearby eligible companions, usually for quick local management.
- `OwnedMode` shows all your owned animals, including saved animals in unloaded chunks
  and other worlds, regardless of item links or the current tool's species filter.
  It has no radius limit. Unlinked animals that are off-screen show their saved
  status. Use their removal controls and choose **Release** to permanently clear
  ownership and free ownership-limit slots, even in another world or unloaded chunk.
  Captured animals and coop occupants must be released from storage first.
  Managed roster companions use their roster's removal controls.
  Animals awaiting recovery stay protected, but do not prevent releasing your other animals.
  Linking and culling require the animal to be loaded in your world. Recall,
  Locate, and Revive/Recover do not require an item link in Owned mode; the animal's
  state, cooldowns, and recovery rules still apply. Home and link settings require a link.
  Bonded-companion tools keep their separate roster controls.

## Finding captured animals

In Linked and Owned modes, captured, cooped, and unloaded animals show location
details directly on their cards, replacing the unused status and cooldown area.
Coop occupants show recorded world and block coordinates; unloaded animals show
their last known location. Loaded animals keep the **Locate** action.

Capture sightings name the item and storage container when known, for example
**Soul Lantern in Wooden Chest**. Player-held items name the carrier; dropped
items show their recorded position. Older sightings use generic labels until
the item or container is observed again.

The Locate window hides unused sections and shrinks to fit. Capture items in a
player's inventory show the holder without empty world or coordinate fields.
Container and dropped-item results include their location and retain last-seen
details when the holder is unloaded.

Inline cards show **World:** and labeled **X**, **Y**, and **Z** coordinates,
without an observation timestamp. Click the copy icon to switch to a selectable
text field containing the plain coordinate tuple; click it again to restore the
labels. Cards do not load distant chunks or check inventories as they refresh.
The Locate action verifies the recorded holder when available. **Unknown** means
no usable item sighting is available; it does not mean the
animal died or the item was destroyed. Older capture items without a capture receipt
and storage provided by other mods may have no known location.

Item sightings survive normal restarts, but are only hints until verified again.
The tracker uses load and item-change events, with no recurring world or inventory
scans. It keeps a bounded cache, so older sightings can expire from the cache.

## Sorting and filtering
- Sort modes include default order, name, species, group, happiness, hunger, and thirst.
- Care sorts show the lowest percentage first, including inactive companions. Unknown
  values sort last. Unloaded companions use their last-known saved values.
- Filter modes can include none, name, species, or group.
- Some tools let you type filter text while the panel is open.

## Active vs inactive
- Active companions stay part of normal bulk command dispatch.
- Inactive companions remain linked to the tool but are excluded from bulk commands.
- Inactive rows can still appear in the panel so you can manage them individually.
- The group selector can quickly set all linked companions active, set all inactive, or activate one group while deactivating the rest.
- Generic linked panels include a `Highlight Active` setting. It starts off.
  When enabled, loaded active companions show an indicator above their heads
  while you hold that command tool. Only you see the indicator. Its color matches
  each companion's group; ungrouped companions use neutral gold. The indicator
  is hidden while someone rides the companion and returns after dismount.

## Per-row actions
- `Recall`
- `Set Home`
- `Return Home`
- `Unlink`
- `Revive`, a restoration action for dead or `LOST` companions when the
  companion policy and death cooldown allow it. Roster-backed companions can
  show a confirmation with exact item costs; legacy item-linked flows may be
  free.
- The red X opens `Release` and `Unlink` in Linked, Nearby, and Owned modes. `Cull` also appears for loaded, living animals. Unlink is dimmed when the animal has no item link.
- `Release` replaces Abandon and permanently frees the ownership slot, including when the animal is off-screen. Captured animals and coop occupants must leave storage first.
- Action buttons share normal and hovered frames. Flight, shoulder, and breeding icons show the current mode.

## Special statuses
- `Unloaded` means the companion is not currently loaded near you, but the tool still knows about it.
- `Captured` means the companion is stored in its filled capture item. Release
  that item normally or use a supported managed-coop item intake; recall and
  return-home do not replace it.
- `In Coop` means the companion is housed in a configured coop. Release it
  through that coop.
- `Attempting recall` means the tool is retrying relocation for an unloaded
  companion. The timer shows only the remaining retry window. When it ends,
  the attempt stops without inventing a new `LOST` state from timeout or
  absence.
- `Dead` means Tamework saved a confirmed death state. `Revive` becomes
  available when restoration is enabled and the configured cooldown ends.
- `LOST` means Tamework saved a restorable state after confirmed destructive
  removal or world-deletion evidence. It is not inferred solely because the
  companion is off-screen, absent, or took too long to recall.

## Group tools
- Some tools support assigning a companion to a group.
- Group tabs and a group manager let you create, rename, recolor, or delete groups.
- Group sorting and filtering are especially useful when one tool manages many companions.
- These groups organize the command UI. They do not change companion storage
  or owner limits.

## Practical tips
- If a companion is dead or `LOST`, use `Revive` when it becomes available
  instead of repeatedly using recall. Review the exact cost confirmation when
  one is configured.
- If a row says `Attempting recall`, let the current attempt finish before
  trying again. An expired countdown is not proof that the companion is lost.
- If a row says `Captured` or `In Coop`, use the matching filled-item or coop
  release interaction.
- If the row stays inactive, check whether you intentionally toggled it off for bulk commands.
- If nearby actions appear only sometimes, move closer and confirm the creature is loaded and owned by you.

## Related Pages
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Naming, Capture, and Command Items](/mod/alecs-tamework/naming-capture-and-command-items)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)

> [Screenshot Placeholder: Linked panel showing active, unloaded, captured,
> coop, roster-stored, provisioned-dormant, dead, and Lost rows]



Compact cards use separate areas for passive traits and captioned actions. The level sits
above the health bar. Happiness, hunger, thirst, and applicable breeding and harvest
cooldowns share a flat status row; unknown off-screen cooldowns are not shown.

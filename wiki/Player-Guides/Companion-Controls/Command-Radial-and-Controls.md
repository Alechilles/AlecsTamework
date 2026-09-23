---
title: "Command Radial and Controls"
order: 6
published: true
draft: false
---
# Command Radial and Controls

Parent: [Companion Controls](/mod/alecs-tamework/companion-controls) | [Player Guides](/mod/alecs-tamework/player-guides)

Tamework command tools usually use a two-part control scheme: one input for using the selected command and one input for opening the command selection UI.

## Default input pattern
- Left-click or primary input uses the currently selected command.
- Right-click or secondary input opens the command menu.
- For ordinary owned-companion flutes, the same tool can select or deselect the
  targeted NPC with its primary input. This changes only that flute's command set.

The standard menu uses a compact LMB/Q/E/R assignment sidebar beside the
companion panel. Choose the command for each input from its dropdown. Changing
the left-click assignment keeps the menu open; it does not issue the command.

## Typical command types
- Follow
- Hold
- Idle
- Defend
- Aggressive
- Move To Ping
- Set Home
- Return Home
- Recall
- Attack Target

The exact list depends on the command item config and on the mod using Tamework.

## What a command can do
- Change NPC state
- Set or clear combat targets
- Store a home position
- Return the NPC to its home
- Move the NPC to a targeted position
- Trigger a custom hook that hands off to other NPC behavior

## Companion panel selection

The panel lists owned companions automatically for ordinary `ItemMetadata` flutes.
Selected companions appear first, and each physical flute remembers its own selected
set. The status tabs are **In World**, **Stored**, **Lost / Dead**, and **All**. Use
**Nearby only** or the unified name/species/group search to narrow what is shown;
these browsing controls never change command recipients. Click a group to select its
members, or toggle individual animals afterward. Shared player groups support multiple
memberships and are edited inline through the card's native multi-select control.


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

When you select a named saved view, the flute's item name includes the view name
and its tooltip lists the saved filters. Changes you have not saved in the menu
do not appear in the item tooltip yet.

Changing a view does not change command recipients. **Select all matching** replaces
that flute's selection with eligible matches across every page, up to the flute's
command limit. The chip row reports selected animals outside the current view.

The fixed footer always shows the matching range and total. Page controls appear
only when there are multiple pages; Previous and Next appear only when available.
Filters and sorting apply before pagination. Page navigation is temporary; opening
the flute or changing its view starts at the first page.

## Nearby vs off-screen behavior
- If the companion is already loaded, commands often apply immediately.
- If it is far away or unloaded, Tamework can queue relocation and retry it over time.
- Very distant or cross-world recovery may use stricter travel rules configured by the mod.
- A retry window ending stops that relocation attempt. It does not by itself
  mark the companion `LOST`.

## Why a command might fail
- The NPC is not selected on that flute.
- The NPC is unsupported by the held item's role or command set.
- Ownership or tame checks fail.
- The relevant movement or hook wiring is missing in the mod.
- The companion is dead, `LOST`, captured in a filled item, or housed in a
  configured coop. The companion panel shows which saved state currently blocks
  live commands.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Ownership, Taming, and Interaction Basics](/mod/alecs-tamework/ownership-taming-and-interaction-basics)
- [Coops, Feed Troughs, and Shared Systems](/mod/alecs-tamework/coops-feed-troughs-and-shared-systems)

> [Screenshot Placeholder: Unified command assignments above the companion panel]




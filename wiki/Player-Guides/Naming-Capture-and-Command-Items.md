---
title: "Naming, Capture, and Command Items"
order: 7
published: true
draft: false
---
# Naming, Capture, and Command Items

Parent: [Player Guides Index](/mod/alecs-tamework/player-guides-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

Tamework-powered mods often use three reusable item families: naming items, spawner or capture items, and command items.

## Naming items
- Open a text input page or fallback text entry flow
- Usually require the creature to be tamed and owned
- May restrict renaming, allowed characters, or name length
- Usually store the chosen name so it survives reloads and respawns

## Capture and spawner items
- Capture an NPC into an item and later restore that same NPC from the item
- Can preserve name, role, attachment choices, tame state, owner state, and progression data
- May optionally clear ownership on capture and reassign ownership on spawn
- Can have different empty and filled item variants

## Command items
- Link specific companions to a tool
- Store command selection and linked companion metadata on the item itself
- Open the radial menu and linked panel for deeper management
- Can limit how many linked companions stay active at once

## Tooltips and icons
- Some spawner items show captured `Name` and `Role` lines in the tooltip.
- Some capture items can swap icons based on the captured NPC's role or attachment set.
- Trait icons or status indicators may also appear in linked panel rows when the mod enables them.

## Player expectations
- If an item works on one creature but not another, that is usually a role filter from the mod's config.
- If spawning or naming fails, it is usually because of ownership, tame, cooldown, or allowed-role rules.
- If a command item looks empty, it may simply have no linked companions yet.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)

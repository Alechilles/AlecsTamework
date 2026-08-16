---
title: "Naming, Capture, and Command Items"
order: 7
published: true
draft: false
---
# Naming, Capture, and Command Items

Parent: [Companion Controls](/mod/alecs-tamework/companion-controls) | [Player Guides](/mod/alecs-tamework/player-guides)

Tamework-powered mods often use three reusable item families: naming items, spawner or capture items, and command items.

## Naming items
- Open a text input page or fallback text entry flow
- Usually require the creature to be tamed and owned
- May restrict renaming, allowed characters, or name length
- Usually store the chosen name so it survives reloads and respawns

## Capture and spawner items
- Capture an NPC into a filled item and later release that same companion from
  the item
- Can preserve name, role, attachment choices, tame state, owner state, and progression data
- May optionally clear ownership on capture and reassign ownership on spawn
- Can have different empty and filled item variants
- A successful capture and release move one saved companion between its live
  and filled-item states; they do not create a second copy
- Normal capture items preserve current health instead of healing the
  companion. An NPC that is already dead or at zero health cannot be captured.

## Command items
- Link specific companions to a tool
- Legacy tools store command selection, links, and display preferences on the
  item itself. Owner/command-family tools read durable roster membership from
  the world instead of treating the item as roster authority.
- Open the radial menu and linked panel for deeper management
- Can limit how many linked companions stay active at once
- Follow the companion's stable profile across capture, coop housing, release, recall, and recovery even when the live entity UUID changes
- Read captured, coop, roster-stored, provisioned-dormant, dead, and `LOST`
  status from the companion's saved lifecycle rather than deciding those states
  from stale item metadata

Tamework's bundled example command whistle is a development/reference item and
has no recipe. Servers may give it directly for testing, while production mods
are expected to provide their own player-facing command item and acquisition
method.

## Tooltips and icons
- Some spawner items show captured `Name` and `Role` lines in the tooltip.
- Some capture items can swap icons based on the captured NPC's role or attachment set.
- Trait icons or status indicators may also appear in linked panel rows when the mod enables them.

## Player expectations
- If an item works on one creature but not another, that is usually a role filter from the mod's config.
- If spawning or naming fails, it is usually because of ownership, tame, cooldown, or allowed-role rules.
- If a command item looks empty, it may simply have no linked companions yet.
- A companion shown as housed in a configured coop is not missing. Release it
  through that coop instead of trying to create a replacement.
- A supported managed-coop interaction can place an eligible canonical filled
  capture item directly into an available coop slot. Other filled items still
  use their normal release interaction.
- If a v2.16.1 filled item became stranded after upgrading to Tamework
  3.0.0-3.0.2, use that exact item again after the fixed build reports
  `MUTATION_READY`. Tamework can recover its preserved imported capture state
  directly; you do not need to rerun migration. Keep a complete save backup
  before upgrading.
- Restoring a command-linked dead or `LOST` companion may be free or may
  require the exact item recipe shown by the confirmation. The configured
  policy or cooldown can still delay or disable the action.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




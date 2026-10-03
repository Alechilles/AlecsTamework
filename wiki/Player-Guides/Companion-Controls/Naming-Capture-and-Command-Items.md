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
- Keep the companion's owner while it is in the item. Your server's captured
  companion ownership setting decides whether the owner changes when the item
  changes hands (see "Player expectations" below)
- Can have different empty and filled item variants
- A successful capture and release move one saved companion between the world
  and the item; they do not create a second copy. A copied or duplicated filled
  item cannot release the companion a second time: the extra copy turns into an
  empty capture item with a message when it is used
- Normal capture items preserve current health instead of healing the
  companion. An NPC that is already dead or at zero health cannot be captured.

## Command items
- Ordinary `ItemMetadata` flutes show all owned companions automatically.
  You do not need to link an animal to each flute.
- Each physical flute stores its own selected companions. Left-click an owned NPC
  while holding that flute, or use the card selection button, to select or deselect it.
  Separate flutes can keep separate working sets.
- Owner/command-family tools read their roster from the server's saved companion
  data, so every matching item shows the same roster. Bonded tools keep their
  separate roster and summon controls.
- Open the radial menu and linked panel for deeper management
- Can limit how many selected companions stay active at once
- Follow the companion's stable profile across capture, coop housing, release, recall, and recovery even when the live entity UUID changes
- Read captured, coop, stored, dead, and `LOST` status from the companion's
  saved record rather than from stale item metadata

The optional `Alec's Tamework! Examples` pack contains a development/reference
command whistle and has no recipe. Enable the pack and give the item directly
for testing. Production mods are expected to provide their own player-facing
command item and acquisition method.

## Tooltips and icons
- Some spawner items show captured `Name` and `Role` lines in the tooltip.
- Some capture items can swap icons based on the captured NPC's role or attachment set.
- Trait icons or status indicators may also appear in linked panel rows when the mod enables them.

## Player expectations
- If an item works on one creature but not another, that is usually a role filter from the mod's config.
- If spawning or naming fails, it is usually because of ownership, tame, cooldown, or allowed-role rules.
- If a command item looks empty, check its status tab, `Nearby only` filter, and search
  text. A newly owned companion appears automatically even when it is not selected.
- The title reports this flute's selected and displayed totals. Status-tab counts
  follow the active `Nearby only` setting and search text.
- A flute's selected set is independent of the shared player groups. Companions can
  belong to several groups or none; left-clicking a group selects its members for the
  current flute without changing memberships. Right-click adds a group while keeping
  the animals already selected.
- A captured companion keeps its owner while it is in a capture item, and the
  item's tooltip shows an **Owner:** line. Your server chooses how the owner
  changes:
  - **Follows the item** (the default): whoever takes the filled item into
    their inventory becomes the owner, if their companion limits allow it. The
    owner line updates.
  - **Owner only**: other players cannot pick up, take or release the item.
  - **Changes on release**: the owner stays the same while the companion is in
    the item; whoever releases it becomes the owner.

  When the owner changes, the companion leaves the former owner's panel.
- A captured companion still counts toward its owner's "companions owned"
  limit. If you are at your limit, you may be unable to pick up another
  player's filled capture item; a message tells you why, and the item stays
  where it is.
- If a filled capture item despawns on the ground or falls out of the world,
  its companion becomes `LOST` and you can **Recover** it from the companion
  panel. If the item vanished some other way and the companion still shows as
  captured, use **Recall** or **Forget** on its card; see the
  [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide).
- A companion shown as housed in a configured coop is not missing. Release it
  through that coop instead of trying to create a replacement.
- A supported managed-coop interaction can place an eligible filled capture
  item directly into an available coop slot. Other filled items still use their
  normal release interaction.
- Capture items filled on an older Tamework version still work after the update
  to 5.0. Each one releases its companion once.
- **Revive** brings back a dead companion and may require the exact item recipe
  shown by the confirmation. **Recover** brings back a `LOST` companion and is
  free. The configured policy or cooldown can still delay or disable either
  action.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




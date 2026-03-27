---
title: "Command Radial and Controls"
order: 6
published: true
draft: false
---
# Command Radial and Controls

Parent: [Player Guides Index](/mod/alecs-tamework/player-guides-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

Tamework command tools usually use a two-part control scheme: one input for using the selected command and one input for opening the command selection UI.

## Default input pattern
- Left-click or primary input uses the currently selected command.
- Right-click or secondary input opens the command radial or selection menu.
- Some mods also let the same tool link or unlink companions with its primary input.

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

## Nearby vs off-screen behavior
- If the companion is already loaded, commands often apply immediately.
- If it is far away or unloaded, Tamework can queue relocation and retry it over time.
- Very distant or cross-world recovery may use stricter travel rules configured by the mod.

## Why a command might fail
- The NPC is not linked.
- The NPC is inactive on that tool.
- Ownership or tame checks fail.
- The relevant movement or hook wiring is missing in the mod.
- The NPC is dead or marked `LOST`.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Ownership, Taming, and Interaction Basics](/mod/alecs-tamework/ownership-taming-and-interaction-basics)
- [Coops, Feed Troughs, and Shared Systems](/mod/alecs-tamework/coops-feed-troughs-and-shared-systems)

> [Screenshot Placeholder: Command radial menu with one command highlighted]

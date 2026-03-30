---
title: "Ownership, Taming, and Interaction Basics"
order: 4
published: true
draft: false
---
# Ownership, Taming, and Interaction Basics

Parent: [Getting Started](/mod/alecs-tamework/getting-started) | [Player Guides](/mod/alecs-tamework/player-guides)

Many Tamework-powered creatures use three core ideas: whether the NPC is tamed, whether it has an owner, and which interaction is currently valid.

## Tamed vs untamed
- Untamed creatures usually accept only a small set of interactions, such as a tame interaction or a restricted feed interaction.
- Tamed creatures unlock owner-only features, command tools, naming, and progression systems more often.
- Some mods use role swaps after taming, so the creature may visibly change behavior or nameplate state once tamed.

## Ownership
- Ownership is usually tied to the player who tamed, spawned, or was assigned to the NPC.
- Many interactions require both tame status and ownership.
- Some mods allow limited interaction with unowned or other-player creatures, but those rules are mod-specific.

## Context-sensitive interactions
- Tamework can show different prompts depending on the current situation.
- The same NPC might show a tame prompt while wild, a feed prompt while hungry, a mount prompt while crouching, or a mode-cycle prompt when it is already yours.
- If a mod uses the optimized interaction flow, the active prompt usually reflects the first valid interaction in that NPC's configured interaction list.

## Why interactions sometimes do nothing
- You are not the owner.
- The creature is not tamed yet.
- The wrong item is in hand.
- The creature is on cooldown.
- A required context is missing, such as crouching, harvest readiness, or low health.

## Good player habits
- Read the prompt before clicking.
- Try the same interaction with an empty hand and with the intended item.
- If a command or name action fails, verify that the creature is actually yours.
- Check the linked panel if the creature is controlled by a command item.

## Related Pages
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




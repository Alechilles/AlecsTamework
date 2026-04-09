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
- Linked companions for the current tool
- Active and inactive status
- Loaded, unloaded, dead, or lost state
- Name, species or role label, and often health or cooldown indicators
- Group membership when the tool uses groups
- Trait or progression indicators when the mod exposes them
- In some mods, happiness details including current and target trend, plus active impulse modifiers

## Panel modes
- `LinkedMode` shows companions linked to the current tool.
- `NearbyMode` shows nearby eligible companions, usually for quick local management.

## Sorting and filtering
- Sort modes can include default order, name, species, or group.
- Filter modes can include none, name, species, or group.
- Some tools let you type filter text while the panel is open.

## Active vs inactive
- Active companions stay part of normal bulk command dispatch.
- Inactive companions remain linked to the tool but are excluded from bulk commands.
- Inactive rows can still appear in the panel so you can manage them individually.

## Per-row actions
- `Recall`
- `Set Home`
- `Return Home`
- `Unlink`
- `Revive` when the command item and companion policy allow it
- Nearby-only `Release` and `Cull` when the mod exposes those actions

## Special statuses
- `Unloaded` means the companion is not currently loaded near you, but the tool still knows about it.
- `Dead` means the tool has a persisted death snapshot and may allow revive after policy and cooldown checks.
- `LOST` means the tool could not safely complete relocation or travel recovery. In that state, normal recall and return-home actions are usually blocked.

## Group tools
- Some tools support assigning a companion to a group.
- Group tabs and a group manager let you create, rename, recolor, or delete groups.
- Group sorting and filtering are especially useful when one tool manages many companions.

## Practical tips
- If a companion is `LOST`, look for revive or recovery behavior rather than repeatedly using recall.
- If the row stays inactive, check whether you intentionally toggled it off for bulk commands.
- If nearby actions appear only sometimes, move closer and confirm the creature is loaded and owned by you.

## Related Pages
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Naming, Capture, and Command Items](/mod/alecs-tamework/naming-capture-and-command-items)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)

> [Screenshot Placeholder: Linked panel showing active, inactive, dead, and lost rows]



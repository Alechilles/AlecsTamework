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
- Loaded, unloaded, captured, housed in a coop, roster-stored,
  provisioned-dormant, dead, or `LOST` state
- Name, species or role label, and often health or cooldown indicators. A custom companion name remains visible after the companion unloads or the world restarts.
- Group membership when the tool uses groups
- Trait or progression indicators when the mod exposes them
- In some mods, happiness details including current and target trend, plus active impulse modifiers

The panel derives captured, coop, roster-stored, provisioned-dormant, dead, and
`LOST` status from one saved companion lifecycle. Item metadata and an expired
recall timer do not override that status.

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
- The group selector can quickly set all linked companions active, set all inactive, or activate one group while deactivating the rest.

## Per-row actions
- `Recall`
- `Set Home`
- `Return Home`
- `Unlink`
- `Revive`, a restoration action for dead or `LOST` companions when the
  companion policy and death cooldown allow it. Roster-backed companions can
  show a confirmation with exact item costs; legacy item-linked flows may be
  free.
- Nearby-only `Release` and `Cull` when the mod exposes those actions

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



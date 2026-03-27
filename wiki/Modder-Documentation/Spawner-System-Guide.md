---
title: "Spawner System Guide"
order: 6
published: true
draft: false
---
# Spawner System Guide

Parent: [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

Spawner items are the Tamework item family that captures an NPC into an item and restores that same NPC later.

## Runtime pieces
- Config asset: `TwSpawnerConfig`
- Item interaction: `TameworkSpawn`
- Main orchestrator: `SpawnerFeatureHandler`

## Typical setup
1. Create an empty and, optionally, a filled item
2. Bind the config through `EmptyItemId`
3. Add `TameworkSpawn` to the item's interaction block
4. Restrict compatible roles through `AllowedRoles`
5. Tune capture and spawn policy sections

## What the system preserves
- Role and attachment choices
- Tamework name data
- Tamed and owner state when configured
- Happiness, needs, breeding, traits, life stage, and other stored progression metadata

## Important design choices
- Whether ownership is cleared on capture
- Whether ownership is re-assigned on spawn
- Whether the item is owner-restricted
- Whether you want tooltip lines for captured `Name` and `Role`
- Whether icon overrides should reflect captured role or attachments

## Tooling support
- `scripts/tools/generate_spawner_icon_overrides.py` can help generate `IconOverridesByRole`
- DynamicTooltipsLib integration can surface captured-spawner metadata in tooltips

## Reloading
Spawner configs participate in `/tw reloadconfig`.

## Related Pages
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)

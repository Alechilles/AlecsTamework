---
title: "Spawner System Guide"
order: 6
published: true
draft: false
---
# Spawner System Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

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
- The captured health of a living NPC
- Happiness, needs, breeding, traits, life stage, and other stored progression metadata

Normal capture items do not heal an NPC. Tamework rejects capture if the NPC
already has a death state or has reached zero health, because releasing that
terminal snapshot would make the NPC die at once.

Capture and release save the companion record first and change the world
second. Configured capture and spawn particles and sounds play only after that
save completes. A failed save leaves the NPC or the item as it was and plays no
success feedback.

## Important design choices
- Who owns a captured companion while it is in an item. The server's captured
  companion ownership mode decides this (`FOLLOWS_ITEM`, `OWNER_ONLY` or
  `CHANGES_ON_RELEASE`). The old `Capture.ClearsOwner`, `Spawn.AssignsOwner`,
  `Spawn.OwnerRestricted` and `Spawn.RequireOwner` fields still load but are
  ignored. See
  [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference).
- Whether you want additive or replacement captured-spawner item descriptions
- Whether icon overrides should reflect captured role or attachments

## Tooling support
- [Spawner Icon Generation](/mod/alecs-tamework/spawner-icon-generation) covers the Blockbench wizard, jobs JSON renderer, Python generator, and batch manifest workflow.
- `scripts/tools/generate_spawner_icon_overrides.py` generates shared `TwDynamicIconConfig` assets for capture items and both command panels.
- Captured-spawner names and detail lines are written into base Hytale item display metadata.

## Reloading
Spawner configs participate in `/tw config reload`.

## Related Pages
- [TwSpawnerConfig Reference](/mod/alecs-tamework/twspawnerconfig-reference)
- [Spawner Icon Generation](/mod/alecs-tamework/spawner-icon-generation)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)




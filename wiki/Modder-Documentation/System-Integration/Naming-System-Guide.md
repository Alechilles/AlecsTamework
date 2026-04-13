---
title: "Naming System Guide"
order: 7
published: true
draft: false
---
# Naming System Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Naming items let you attach a reusable naming flow to any item without building a custom UI or validation pipeline from scratch.

## Runtime pieces
- Config asset: `TwNameItemConfig`
- Item interaction: `TameworkNameNpc`
- Main orchestrator: `NamingFeatureHandler`

## Typical setup
1. Create an item
2. Add `TameworkNameNpc` in the item's interaction block
3. Create a matching `TwNameItemConfig`
4. Restrict roles through `AllowedRoles`
5. Tune naming policy such as owner checks, rename policy, character rules, cooldown, and consume behavior
6. (Optional) set `Naming.RandomNamesId` to a `TwNamesConfig` pool id so the UI randomize button can suggest names

## Random name pools
- `TwNamesConfig` assets live under `Server/Tamework/Names/*.json`
- `TwNameItemConfig.Naming.RandomNamesId` points at a pool id in that family
- If no pool is configured or it fails to resolve, naming still works and only manual entry is used
- Use parent fallback in `TwNamesConfig` for shared regional pools with per-pack overrides

## Validation model
- The system can require tame state
- The system can require ownership, while optionally allowing unowned NPCs through a narrow escape hatch
- Submission is re-validated on the server after the naming UI is opened

## Persistence behavior
- Tamework names are stored on the NPC
- Spawner capture preserves those names and restores them on spawn
- Existing display names can be replaced or preserved depending on the config

## Related Pages
- [TwNameItemConfig Reference](/mod/alecs-tamework/twnameitemconfig-reference)
- [TwNamesConfig Reference](/mod/alecs-tamework/twnamesconfig-reference)
- [Ownership Policy and Core Builders](/mod/alecs-tamework/ownership-policy-and-core-builders)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)




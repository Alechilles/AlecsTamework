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
- [Ownership Policy and Core Builders](/mod/alecs-tamework/ownership-policy-and-core-builders)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)




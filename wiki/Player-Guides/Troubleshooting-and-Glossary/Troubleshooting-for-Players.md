---
title: "Troubleshooting for Players"
order: 10
published: true
draft: false
---
# Troubleshooting for Players

Parent: [Troubleshooting and Glossary](/mod/alecs-tamework/troubleshooting-and-glossary) | [Player Guides](/mod/alecs-tamework/player-guides)

Use this page when a Tamework-powered feature seems inconsistent and the other mod's own wiki does not explain it.

## Common problems
### I cannot issue commands
- Make sure the creature is linked to the tool.
- Confirm the creature is tamed and owned by you.
- Open the radial menu and verify you selected a command.
- Check the linked panel in case the row is inactive, captured, in a coop,
  dead, or `LOST`.

### Recall or return-home is not working
- The companion may be unloaded and still waiting on relocation.
- The mod may require special travel rules for cross-world recovery.
- Dead and `LOST` companions use the free `Revive` action rather than normal
  recall.
- A failed relocation keeps the last canonical state instead of deleting the
  companion or guessing that it is `LOST`.
- A recall countdown ending stops that attempt. Off-screen absence and timeout
  alone cannot create `LOST`.

### Naming does nothing
- The item may only work on owned or tamed creatures.
- The target role may be outside the naming item's allowed-role list.
- The name may violate the mod's length or character rules.

### Capture or spawn fails
- The item may not allow that role.
- The NPC may need to be tamed or owned first.
- The item may be on cooldown or out of range.
- Filled items release through their normal spawner interaction. The filled
  item should remain available if release cannot safely complete.
- Configured coops accept eligible live creatures, not filled capture items.
  Release the item first, then use the coop intake.

### Taming or ownership says the owner limit was reached

- The limit counts loaded NPCs currently owned by that player.
- Move away from or release loaded owned companions, or ask the administrator
  to review the configured limit.

### Breeding says a claim limit was reached

SimpleClaims may require the pair to be in a claim and may limit breeding NPCs
per chunk or across the claim.

### Claim protection behaves differently from simple membership

SimpleClaims damage protection follows that plugin's native full-world, administrator/member, ally, party-ally, and outsider rules. A claim-integration error allows damage rather than making a companion permanently invulnerable. Owner-specific Tamework protections still apply first.

### Progression behavior seems wrong
- Hunger, thirst, happiness, adulthood, and breeding can all gate each other.
- A creature may be too young, unhappy, on cooldown, or missing the required conditions for breeding.

## When to check the other mod's wiki
- You need recipes or item acquisition details.
- You need a species-specific taming rule.
- You need balance numbers for a specific creature.

## When to report a bug
- The prompt says an action should work but nothing happens repeatedly.
- A creature stays permanently `LOST` or `Dead` with no documented recovery path.
- A companion becomes `LOST` merely because a recall countdown ended or it
  was temporarily unloaded.
- Progression data vanishes after a normal capture, release, or reload flow.
- A failed filled-item release consumes the filled item.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Naming, Capture, and Command Items](/mod/alecs-tamework/naming-capture-and-command-items)
- [Player Glossary](/mod/alecs-tamework/player-glossary)
- [Troubleshooting and diagnostics for server owners](/mod/alecs-tamework/tamework-settings-ui-and-persistence)




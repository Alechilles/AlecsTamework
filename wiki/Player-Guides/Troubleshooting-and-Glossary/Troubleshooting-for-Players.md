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
- The companion may be unloaded and still being brought to you. Let the
  `Attempting recall` countdown finish. If the animal does not turn up, the
  companion reappears next to you from its saved state.
- The mod may not allow recall across worlds for that companion.
- Dead companions use `Revive` and `LOST` companions use `Recover`, not
  recall. The Revive confirmation shows any configured item cost before you
  approve it. Recover is free.
- Right after a server update from an older Tamework version, a card can show
  `Being located after the update`. Recall and Recover are refused until the
  companion is found. Wait; you do not need to walk to it.
- A recall that times out never deletes the companion and never makes it
  `LOST`.

### A captured companion's item is gone
- If the filled item despawned on the ground or fell out of the world, the
  companion is `LOST`. Use `Recover` on its card.
- If the card still says `Captured`, use `Recall` on the card to get the
  companion back next to you, or `Forget` (the red X) to give it up and free
  its slot.
- A copied filled item does not give you a second companion. After the first
  release, the other copy becomes an empty capture item.

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
- You may be unable to pick up another player's filled capture item when you
  are at your companion limit, or when the server binds captured companions to
  their owner.
- A supported managed-coop interaction can accept an eligible filled
  capture item directly. If that intake is not configured or the item is
  ineligible, release it normally and use live-creature intake.

### Taming or summoning says a companion limit was reached

Servers can set two limits per player, each with its own message:

- **Companions owned** counts every companion you own in the configured global
  or per-world scope: out in the world, stored, in capture items, in coops,
  dead and `LOST`. To free a slot, release a companion, or use `Forget` on a
  captured companion whose item is gone.
- **Companions out in the world** counts only companions that have an animal
  in a world, loaded or not. To free a slot, store or capture one, or put one
  in a coop. Moving away from an animal does not free a slot.

Ask the administrator to review the limits in `/tw settings` if they seem
wrong.

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
- A creature stays permanently `LOST` or `Dead` and `Recover` or `Revive` never
  becomes available.
- A companion becomes `LOST` merely because a recall countdown ended or it
  was temporarily unloaded.
- Progression data vanishes after a normal capture, release, or reload flow.
- A failed filled-item release consumes the filled item.
- A card keeps showing `Being located after the update` long after the server
  console reports that the search finished.

When you report a problem, tell the server admin the companion's name and what
its card shows. Admins should include the server log, and after a failed update
the import report; see
[World Migration for Server Admins](/mod/alecs-tamework/world-migration-for-server-admins).

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Naming, Capture, and Command Items](/mod/alecs-tamework/naming-capture-and-command-items)
- [Player Glossary](/mod/alecs-tamework/player-glossary)
- [Troubleshooting and diagnostics for server owners](/mod/alecs-tamework/tamework-settings-ui-and-persistence)




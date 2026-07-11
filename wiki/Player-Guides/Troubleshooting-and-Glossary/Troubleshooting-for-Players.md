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
- Check the linked panel in case the row is inactive, dead, or lost.

### Recall or return-home is not working
- The companion may be unloaded and still waiting on relocation.
- The mod may require special travel rules for cross-world recovery.
- `LOST` companions usually need a stronger recovery path than normal recall.
- A deliberate recall/teleport into another world or claim can be denied when that destination's owner or claim admission limit is full. The companion stays at its source/last recoverable state rather than being deleted.

### Naming does nothing
- The item may only work on owned or tamed creatures.
- The target role may be outside the naming item's allowed-role list.
- The name may violate the mod's length or character rules.

### Capture or spawn fails
- The item may not allow that role.
- The NPC may need to be tamed or owned first.
- The item may be on cooldown or out of range.
- A captured companion still counts toward its owner's companion limit even though it no longer occupies a physical claim.
- Releasing a captured, cooped, dead, or lost companion must reserve room in the destination claim. If denied, the source item/record should remain available; do not repeatedly duplicate or split the item stack.

### Taming, ownership, or breeding says a population limit was reached

- The per-player number means **owned companions**, not only companions currently loaded beside you. Unloaded, captured, cooped, dead-but-revivable, lost, restoring, and dormant owned companions still count.
- In per-world mode, a stored companion keeps the world where its ownership is recorded.
- Claim limits count physically active and durably unloaded owned companions. Captured/cooped/dead/lost companions re-enter claim occupancy only when restored.
- Natural movement into a full claim is allowed. It may leave that claim over-cap and block later tame, spawn, recall, revive, release, or breeding admissions until companions move out.
- Existing companions are not deleted just because an upgraded save is already over a newly configured cap.

### Population is temporarily unavailable

After a server upgrade, Tamework may be reconciling companion profiles, saved worlds, offline player inventories, and captured items inside containers. New positive owner/claim admissions fail safely during that work instead of assuming the count is zero. Ask the server administrator to check population diagnostics if the message persists; repeatedly retrying or copying a filled item will not bypass it.

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
- Progression data vanishes after a normal capture, release, or reload flow.
- A denied captured/coop/revive/lost restore consumes the source item or record.
- The server stays in population reconciliation/degraded state after an administrator has checked the logs and completed-save coverage.

## Related Pages
- [Linked Panel Guide](/mod/alecs-tamework/linked-panel-guide)
- [Naming, Capture, and Command Items](/mod/alecs-tamework/naming-capture-and-command-items)
- [Player Glossary](/mod/alecs-tamework/player-glossary)
- [Troubleshooting and diagnostics for server owners](/mod/alecs-tamework/tamework-settings-ui-and-persistence)




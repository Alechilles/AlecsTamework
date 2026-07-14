---
title: "Coops, Feed Troughs, and Shared Systems"
order: 9
published: true
draft: false
---
# Coops, Feed Troughs, and Shared Systems

Parent: [Systems](/mod/alecs-tamework/systems) | [Player Guides](/mod/alecs-tamework/player-guides)

Some Tamework-powered mods use framework systems beyond basic taming and command tools. Two common examples are managed coops and feed trough support.

## Managed coops
- A mod can register certain coop ids with Tamework so coop behavior is framework-managed.
- That usually means residents can be captured, released, and restored with continuity across coop cycles.
- Tamework can keep a resident ledger so the same creature state is preserved instead of being treated like a brand-new spawn every time.
- Only explicitly enabled/configured coops are managed this way. Other coops keep ordinary vanilla behavior.
- A companion keeps one stable profile even if its temporary entity UUID changes after release, so command links and progression still refer to the same creature.
- When an established vanilla coop becomes managed, old residents are audited and imported without spawning replacement copies. If Tamework cannot prove an exact match, it pauses that import for review instead of guessing.
- On startup, Tamework removes an older managed record only when it can prove the companion no longer belongs to that coop and no coop change is still in progress. Uncertain records remain paused instead of being deleted or released speculatively.
- Capturing a breeding parent into a managed coop cancels that parent's pending litter.

## Feed trough support
- Needs systems can consume trough resources rather than only hand-fed resources.
- Feed trough water support can use staged water states and bucket refill mappings.
- Mods may show different trough visuals depending on remaining food or water state.

## Shared utility systems
- Optional tooltip integration for spawner items
- Travel and relocation recovery for off-screen companions
- Lost-companion recovery and stale-original suppression when a respawn replacement is needed
- Per-role command travel rules for recalls and world transfer behavior

## Why this matters to players
- A creature may keep its identity and progression even when moved through another structure or system.
- Feeding and hydration can come from world objects rather than direct interaction only.
- Recovery behavior can look stricter or safer than a simple teleport because Tamework is trying to preserve continuity.
- An unresolved coop import may temporarily refuse new intake or release. That is a safety stop designed to prevent duplicate residents.

## Related Pages
- [Happiness, Needs, Breeding, and Traits](/mod/alecs-tamework/happiness-needs-breeding-and-traits)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




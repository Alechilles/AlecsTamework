---
title: "Coops, Feed Troughs, and Shared Systems"
order: 9
published: true
draft: false
---
# Coops, Feed Troughs, and Shared Systems

Parent: [Systems](/mod/alecs-tamework/systems) | [Player Guides](/mod/alecs-tamework/player-guides)

Some Tamework-powered mods use framework systems beyond basic taming and
command tools. Two common examples are configured coops and feed trough
support.

## Configured coops

- A mod can configure particular coop IDs for Tamework companion behavior.
- An eligible live creature can be captured into a coop and later released as
  a live creature with its saved state restored.
- A companion keeps one stable profile even if its temporary entity UUID
  changes after release, so command links and progression continue to refer to
  the same creature.
- Only explicitly enabled coops use this path. Other coops keep their ordinary
  behavior.
- Coop intake accepts an eligible currently live creature. Supported
  interactions can also move an eligible canonical filled capture item
  directly into an available slot while retiring the exact item.

## Feed trough support
- Needs systems can consume trough resources rather than only hand-fed resources.
- Feed trough water support can use staged water states and bucket refill mappings.
- Mods may show different trough visuals depending on remaining food or water state.

## Shared utility systems
- Optional tooltip integration for spawner items
- Travel and relocation recovery for off-screen companions
- Configurable free or item-cost restoration for positively recorded dead or
  `LOST` companions, with stale-original suppression when a replacement is
  needed
- Per-role command travel rules for recalls and world transfer behavior

## Why this matters to players

- A creature may keep its identity and progression even when moved through another structure or system.
- Feeding and hydration can come from world objects rather than direct interaction only.
- Recovery behavior can look stricter or safer than a simple teleport because Tamework is trying to preserve continuity.

## Related Pages
- [Happiness, Needs, Breeding, and Traits](/mod/alecs-tamework/happiness-needs-breeding-and-traits)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




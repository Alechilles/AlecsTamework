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
- Coop intake accepts an eligible creature that is out in the world. Supported
  interactions can also move an eligible filled capture item directly into an
  available slot; the item is used up.
- A companion in a coop still counts toward its owner's "companions owned"
  limit. It does not count as out in the world.

### Breaking a coop

Breaking a configured coop releases every resident beside the block, the same
way a vanilla coop does. Your companions come out alive with their saved state,
still owned by you. Produce already in the coop drops as usual. If a
companion cannot be released, it keeps showing as housed in the coop; ask a
server admin, who can restore it for you.

### When coops produce

Coop production follows the vanilla coop:

- Residents produce once a day, in the morning, at the moment the coop lets them
  out to roam.
- The clock is the world's game time, not real time and not the time you were
  online.
- Each resident's clock starts when it enters the coop. It yields one batch for
  each production interval (at least one game day) that has started since then.
  A resident that entered the evening before produces one batch the next morning.
- If the coop's chunk was not loaded for several days, residents catch up when
  it loads again, up to a cap.
- Produce that does not fit in the coop's storage is lost, so empty the coop
  regularly.
- Nothing carries over between stays. A resident that leaves and comes back
  starts a new clock.

## Feed trough support
- Needs systems can consume trough resources rather than only hand-fed resources.
- Feed trough water support can use staged water states and bucket refill mappings.
- Mods may show different trough visuals depending on remaining food or water state.

## Shared utility systems
- Optional tooltip integration for spawner items
- Travel and relocation recovery for off-screen companions
- Revive for dead companions (free or with an item cost) and free Recover for
  `LOST` companions. When a companion is brought back, any old copy of its body
  or capture item stops working
- Per-role command travel rules for recalls and world transfer behavior

## Why this matters to players

- A creature may keep its identity and progression even when moved through another structure or system.
- Feeding and hydration can come from world objects rather than direct interaction only.
- Recovery behavior can look stricter or safer than a simple teleport because Tamework is trying to preserve continuity.

## Related Pages
- [Happiness, Needs, Breeding, and Traits](/mod/alecs-tamework/happiness-needs-breeding-and-traits)
- [Command Radial and Controls](/mod/alecs-tamework/command-radial-and-controls)
- [Troubleshooting for Players](/mod/alecs-tamework/troubleshooting-for-players)




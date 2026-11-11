---
title: "Sharing Your Animals"
order: 10
published: true
draft: false
---
# Sharing Your Animals

Parent: [Systems](/mod/alecs-tamework/systems) | [Player Guides](/mod/alecs-tamework/player-guides)

You can let friends feed, ride or manage your tamed animals while you stay their only owner. Sharing never moves an
animal to another player, and your population limits still count your animals the same way.

## What sharing covers

- A share covers all of your animals at once. You can keep single animals to yourself (see "Private animals"
  below).
- Bonded companions are never shared.
- You choose a level for each player. A higher level includes everything the lower ones allow.
- The server can turn sharing off or lower the highest level an owner may give (see "Server settings" below).

## Levels

| Level | What the player can do |
| --- | --- |
| Caretaker | Feed, water, pet and heal your animals, and see their status. Tamework's attack and defend behaviors never target them, and they cannot hurt your animals. |
| Handler | Everything a Caretaker can do, plus: harvest (milk, wool and so on), mount, shoulder ride, toggle flight, command the animal with their own command item, lead it, recall it, set and return its home, breed it, and use custom interactions such as fitting equipment. |
| Manager | Everything a Handler can do, plus: rename, cull, and put the animal in a coop or take it out. |

A Manager counts as the owner for the server's damage settings. If the server lets owners hurt their own animals,
a Manager can too, and a Manager's killing blow counts as a cull where yours would. Caretakers and Handlers can
never hurt a shared animal.

## Share with a player

The player must be online when you share with them or change their level.

1. Run `/tw share add <player> <caretaker|handler|manager>`.
2. Read the reply. It explains what the level allows and what sharing means for hunger (see "Hunger, aging and
   production" below).
3. Run the command again with `confirm` at the end, for example `/tw share add Steve handler confirm`.

The player gets a message that you shared your animals with them. To change their level later, run
`/tw share add <player> <level>` again. A change does not ask you to confirm.

You can share with up to 20 players by name.

## Share with a group

Some mods offer groups, such as your guild. A group gives nothing until you turn it on, and it then covers
everyone in the group, including players who join it later.

1. Run `/tw share list` to see the groups you can turn on and their ids.
2. Run `/tw share group <groupId> <caretaker|handler|manager>`, read the reply, then run it again with `confirm` at
   the end.
3. Run `/tw share group <groupId> off` to turn the group off.

If a player is both named and in a group you share with, they get the higher of the two levels.

## Stop sharing

- `/tw share remove <player>` stops sharing with a player. It works while that player is offline.
- `/tw share group <groupId> off` stops sharing with a group.

## See who you share with

`/tw share list` shows:

- the players you share with, and their levels;
- the groups you turned on, and the groups you can still turn on;
- how many of your animals are private; and
- the players who share their animals with you, and your level on them.

TODO(main agent): panels and sharing page

## Private animals

Look at one of your animals and run `/tw share private on`. No player or group you share with can use it then.
Run `/tw share private off` to share it again. Only the owner can do this. Bonded companions are always private
and cannot be changed.

## Hunger, aging and production

**When you share, your animals stop waiting for you alone.** On many servers, an animal's hunger, aging and harvest
timers pause for a grace time after its owner logs off. A shared animal instead acts as if you were online while you
or anyone you share with is online:

- it gets hungry and thirsty;
- it keeps aging; and
- its harvest and breeding timers keep running.

If nobody feeds it, it can starve, even while you are offline. Share only with players you trust to look after your
animals, and mark an animal private if it should wait for you.

Private animals and bonded companions follow you alone, as before. When neither you nor anyone you share with is
online, the server's usual owner-offline rules apply, including the grace time. A server set to ignore owner presence
is not affected by any of this. See
[Happiness, Needs, Breeding, and Traits](/mod/alecs-tamework/happiness-needs-breeding-and-traits).

## Leave someone's share

If another player shares their animals with you by name and you no longer want that, run `/tw share leave <player>`.
You cannot leave a share you have through a group, because group membership belongs to the group. If you are both
named and in a group, leaving removes only the named share.

## What stays with the owner

Only the owner can:

- capture the animal into an item or release it;
- pay for a revive;
- choose talents;
- transfer the animal; and
- share it, or mark it private.

The owner also stays the owner for population limits and anything else that counts whose animal it is.

## Server settings

Admins find two settings in the Ownership section of `/tw settings`:

- **Sharing Enabled** (on by default). When it is off, only owners have access. Saved shares are kept and apply
  again when sharing is turned back on.
- **Highest Sharing Level** (Manager by default). Owners cannot share above it, and a share saved at a higher level
  counts as this level.

See [Tamework Settings UI and Persistence](/mod/alecs-tamework/tamework-settings-ui-and-persistence) for details.

## Related Pages
- [Ownership, Taming, and Interaction Basics](/mod/alecs-tamework/ownership-taming-and-interaction-basics)
- [Happiness, Needs, Breeding, and Traits](/mod/alecs-tamework/happiness-needs-breeding-and-traits)
- [Coops, Feed Troughs, and Shared Systems](/mod/alecs-tamework/coops-feed-troughs-and-shared-systems)

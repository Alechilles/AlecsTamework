---
title: "Shared Access API Reference"
order: 27
published: true
draft: false
---
# Shared Access API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference)

`TameworkApi.sharedAccess()` returns a `SharedAccessApi` (package
`com.alechilles.alecstamework.api.access`). Check the `SHARED_ACCESS`
capability and `available()` before using it. Older or degraded
implementations return an unavailable facade: the owner holds `OWNER` on their
own companions, everyone else holds `NONE`, every change returns
`UNAVAILABLE`, and `registerGroup` throws `IllegalStateException`.

An owner can share their companions with other players and with groups that
other mods register. A companion still has exactly one owner. Sharing does not
change the owner, population caps, admission, owner files, breeding inheritance
or who a capture item belongs to. This API only answers "what may this player
do with that companion" and stores the owner's choices. Players manage the same
grants with `/tw share`; see
[Sharing Your Animals](/mod/alecs-tamework/sharing-your-animals).

Every method is safe from any thread and returns at once. Reads are in-memory
lookups. Changes are saved in the background to
`universe/Tamework/Data/shared-access.json`.

## Levels

`AccessLevel` is ordered. Each level includes everything the levels before it
allow.

| Level | Meaning |
| --- | --- |
| `NONE` | No access. The player is treated like any stranger. |
| `CARETAKER` | Grantable. |
| `HANDLER` | Grantable. |
| `MANAGER` | Grantable. |
| `OWNER` | The owner alone. Never granted. |

`atLeast(other)`, `isGrantable()` (true for the three middle levels) and
`lowerOf(a, b)` help compare levels.

## Actions

`AccessAction` names what a player wants to do. `minimumLevel()` is the lowest
level that may do it, and `allowedAt(level)` tests a level.

| Action | Minimum level | Covers |
| --- | --- | --- |
| `CARE` | `CARETAKER` | Feed, water, pet, heal, and read the companion's status. |
| `HARVEST` | `HANDLER` | Take a harvest such as milk or wool. |
| `MOUNT` | `HANDLER` | Mount, shoulder ride, or toggle flight. |
| `COMMAND` | `HANDLER` | Link a command item, give commands, lead a follow, recall, and set or return home. |
| `BREED` | `HANDLER` | Start breeding. |
| `CUSTOM_INTERACTION` | `HANDLER` | Config-defined custom interactions, such as fitting or removing equipment. |
| `VIEW_IN_PANELS` | `HANDLER` | See the companion in companion panels. |
| `RENAME` | `MANAGER` | Rename. |
| `CULL` | `MANAGER` | Cull by interaction, panel action or kill. |
| `COOP` | `MANAGER` | Put the companion in a coop or take it out. |
| `OWNER_ONLY` | `OWNER` | Capture into an item, release, pay for a revive, transfer, and change sharing. |

Talents are owner-only as well.

## Reading access

- `level(ownerUuid, actorUuid, profileId)`: the level the actor holds on a
  companion of that owner.
  - `OWNER` when the two ids are equal, even while sharing is off.
  - `NONE` when either id is null, or when the server has sharing turned off.
  - With a `profileId`, a private or bonded companion answers `NONE` for
    everyone but the owner. A null `profileId` asks about the owner's
    companions in general.
  - Otherwise the higher of the actor's named grant and every group grant the
    actor is a member of, capped at `maxLevel()`.
- `levelForProfile(profileId, actorUuid)`: the same, with the owner read from
  the companion index. `NONE` for an unknown or unowned profile.
- `allows(ownerUuid, actorUuid, profileId, action)`: `action.allowedAt(level(...))`.
- `grants(ownerUuid)`: everything the owner shares with, as `AccessGrantView`
  records, named players first, then groups. A player grant has `playerUuid`
  and the `playerName` saved when it was made; a group grant has `groupId`
  (`isGroup()` is true). `level` is the level as granted, before
  `maxLevel()` is applied.
- `isPrivate(ownerUuid, profileId)`: true when the owner marked the companion
  private, or it is bonded and so never shared.
- `ownersSharingWith(actorUuid)`: owners whose grants give the actor some
  access right now. The private flag is per companion, so still check `level`
  for each companion.
- `enabled()` and `maxLevel()`: the server's **Sharing Enabled** and **Highest
  Sharing Level** settings. Grants are kept while sharing is off.

## Changing grants

| Method | Does |
| --- | --- |
| `grantPlayer(ownerUuid, playerUuid, playerName, level)` | Shares the owner's companions with a named player, or changes that player's level. `playerName` may be null; the saved name is kept then. |
| `revokePlayer(ownerUuid, playerUuid)` | Removes a named player's grant. A player leaving someone's share is the same call. |
| `setGroup(ownerUuid, groupId, level)` | Turns a group share on at `level`, or off when `level` is null. |
| `setPrivate(ownerUuid, profileId, isPrivate)` | Marks one companion private, so no grant applies to it, or shares it again. |

Each returns an `AccessChangeResult`:

| Result | Meaning |
| --- | --- |
| `CHANGED` | The change was made and queued for saving. |
| `UNCHANGED` | The request matched what was already saved, including removing a grant that does not exist. |
| `SHARING_DISABLED` | The server has sharing turned off. Only `grantPlayer` and `setGroup` with a level are refused; removing a grant, turning a group off and the private flag still work. |
| `SELF` | An owner cannot share with themselves. |
| `LEVEL_NOT_ALLOWED` | The level is not grantable, or is above `maxLevel()`. |
| `TOO_MANY_PLAYERS` | The owner already shares with 20 named players. Changing an existing player's level is still allowed. |
| `UNKNOWN_GROUP` | No provider is registered for the group id. Turning a group off does not need a provider. |
| `UNAVAILABLE` | Shared access is not available in this runtime. |

The API makes the change at once. Unlike `/tw share`, it never asks for a
confirmation, so ask your player first when you build your own sharing flow.

## Group providers

A group is a set of players an owner can choose to share with, such as the
owner's guild or friends list. Registering a provider grants nothing: each
owner turns the group on and picks a level.

```java
AutoCloseable handle = api.sharedAccess().registerGroup(provider);
```

- `registerGroup` returns an idempotent `AutoCloseable`. Close it when your
  mod stops.
- A second provider with the same id throws `IllegalStateException`. A blank
  id throws `IllegalArgumentException`.
- Owners' saved choices for a group stay while it is unregistered and apply
  again when it returns. While it is gone it grants nothing.

`AccessGroupProvider` has three methods:

- `id()`: a stable, namespaced id such as `runeteria:guild`. It is saved in
  owners' grants and typed by players in `/tw share group <groupId>`.
- `displayNameKey()`: the full translation key of the group's name as players
  see it, for example `server.examplemod.share.guild` for an entry in a
  `server.lang` file.
- `membership(ownerUuid, actorUuid)`: whether the actor is in the owner's
  group. The two ids are never equal. Return `MEMBER`, `NOT_MEMBER` or
  `UNKNOWN`.

Threading contract for `membership`:

- Tamework calls it often, from world threads and from a background thread,
  and also for owners who are offline.
- Answer from memory: no file, database or network access, and no waiting.
- Answer `UNKNOWN` when your data is not loaded. Tamework treats `UNKNOWN`, and
  any exception, as no access.
- You do not need to report membership changes. Access checks call
  `membership` each time, and Tamework rechecks who is online for shared
  groups every 10 seconds.

```java
public final class GuildAccessGroup implements AccessGroupProvider {
    private final GuildCache guilds; // your own in-memory cache, filled by your loader

    public GuildAccessGroup(GuildCache guilds) {
        this.guilds = guilds;
    }

    @Override
    public String id() {
        return "examplemod:guild";
    }

    @Override
    public String displayNameKey() {
        return "server.examplemod.share.guild";
    }

    @Override
    public AccessGroupMembership membership(UUID ownerUuid, UUID actorUuid) {
        if (!guilds.isLoaded()) {
            return AccessGroupMembership.UNKNOWN;
        }
        return guilds.sameGuild(ownerUuid, actorUuid)
                ? AccessGroupMembership.MEMBER
                : AccessGroupMembership.NOT_MEMBER;
    }
}

// At startup
if (api.getCapabilities().contains(TameworkApiCapability.SHARED_ACCESS)
        && api.sharedAccess().available()) {
    guildHandle = api.sharedAccess().registerGroup(new GuildAccessGroup(guildCache));
}

// At shutdown
if (guildHandle != null) {
    guildHandle.close();
}
```

### Built-in groups

Tamework registers its own provider for these mods when they are installed, so
their authors and server owners need no code:

| Group id | Mod | Member when |
| --- | --- | --- |
| `simpleclaims:party` | SimpleClaims | The actor is in the owner's party. |
| `partypro:party` | PartyPro | The two players are in the same party. |
| `questlinesclaims:members` | QuestLines Claims | The actor is a member of a claim the owner owns as a player, and not in its `untrusted` group. |
| `orbisguard:members` | OrbisGuard | The actor is a member or owner of a region the owner owns, in a world OrbisGuard has loaded. |

Tamework reads these mods by reflection and does not need them to load. If a
mod's API changes, its group answers `UNKNOWN` (no access) and Tamework logs
one warning naming the group. Allies in these mods are not members. If one of
these mods registers the same group id itself, its own provider is used and
Tamework's is skipped.

## Presence and progression

A shared companion's needs, aging and readiness timers follow its owner or
anyone the owner shares with: while any of them is online, the companion counts
as having its owner online. A private or bonded companion follows its owner
alone, and so does every companion while sharing is off. When none of them is
online, the server's owner-offline policy applies as usual.

## Activity actor

`ManagedActivityView.actorId` names the player who performed an active action,
who may be a player the owner shares with:

- a feed: the player who fed;
- a harvest, with or without a tool: the player who harvested;
- a cull by interaction or panel action: the player who culled;
- a kill counted as a cull: the killer.

`ownerId`, the owner/companion participant and the care credit
(`CareCreditOutcomeView`) stay with the owner. Passive results, which no player
performed, have a null `actorId` and belong to the owner.

## Policies

- `PolicyApi.isOwner(profileId, playerUuid)` keeps its strict meaning: true for
  the owner only. Use `level` or `allows` to ask about anyone else.
- `PolicyApi.evaluateDamage(profileId, attackerPlayerUuid)` reads the
  attacker's access first, the same way the live damage filter does:
  - `invulnerableIfOwned` and `blockAllPlayerDamageIfOwned` apply to every
    player, as before.
  - A `MANAGER` is treated like the owner: `blockOwnerDamage` applies to them
    and `blockNonOwnerPlayerDamage` does not.
  - A `CARETAKER` or `HANDLER` is always denied, with status
    `DENIED_OWNER_PROTECTION` and reason `block-shared-access-damage`.
  - A player without access follows the non-owner settings.
  - Claim rules are checked after these.

See [Policies API Reference](/mod/alecs-tamework/policies-api-reference).

## NPC builders

The `TameworkHasAccess` sensor and entity filter match the owner and players
with shared access at an optional `MinLevel`. `TameworkIsOwner` still matches
the owner only. See
[Ownership Policy and Core Builders](/mod/alecs-tamework/ownership-policy-and-core-builders).

## Server networks

When a network mod registers a `NetworkSettingsStore`, grants are the same on
every server of the network. All owners' grants are one document under the
key `shared-access`, and servers merge it by owner. Each server also keeps its
own copy in `shared-access.json`, which still applies while the store is down.
Without a store, each server keeps its own grants. See
[Network Settings API Reference](/mod/alecs-tamework/network-settings-api-reference)
for when servers see a change and how conflicts resolve.

Group providers are not synced. Register the same provider on every server so
group grants apply everywhere.

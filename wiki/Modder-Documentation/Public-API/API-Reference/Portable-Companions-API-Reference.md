---
title: "Portable Companions API Reference"
order: 25
published: true
draft: false
---
# Portable Companions API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference)

Part of API `3.0.0`: `TameworkApi.portableCompanions()`. Check
`PORTABLE_COMPANION_STORE` and `available()` before registering. Older or
degraded implementations return an unavailable facade.

This API is for server networks: several servers, each with its own world, that
share one database and move players between them. A network mod installs one
`PortableCompanionStore`. Without one, every server keeps its companions to
itself, as before.

## What follows the owner

With a store installed, a companion follows its owner to every server while it
is owned and in a capture item, stored (roster, bonded, timed, provisioned),
dead or lost. The owner can release, summon, revive or recover it on any server.
Companions in a world (out in the world or in a coop) and released tombstones
stay on their server, but they still count toward the owner's limits on every
server: owned, deployed, population groups and admission provider domains.

Growth pauses while a companion is out of a world, on any server: a capture,
store, death or loss snapshot records the owner's progression clock, and the
restore resumes from the destination server's clock.

## Registering

`register(PortableCompanionStore)` accepts one store and returns an idempotent
`AutoCloseable`. Close it when the network mod stops. A second store throws
`IllegalStateException`. Once registered, every owned companion this server
holds is written to the store in the background.

## The store

Tamework calls every method on its own `tamework-portable-store` thread, one
call at a time, never on a world thread. Calls may block on I/O. A call that
throws is retried with backoff from 1 to 30 seconds, and Tamework keeps working
from its local files meanwhile.

- `holderId()`: this server's id, unique in the network and the same across
  restarts. Tamework stamps it on every entry it writes. If it changes, the
  server counts its own animals twice until the old rows are renamed.
- `load(owner, profileIds)`: every stored entry whose owner is `owner`, plus the
  entries of `profileIds` whoever owns them now. `owner` may be null.
- `save(entries)`: store each entry that `replaces` the stored entry of its
  profile, or has none, replacing it whole. Compare and write each entry in one
  atomic step, because other servers write too. Skip the others without an
  error.

## Entries

`PortableCompanionEntry` carries the profile id, owner, generation, version
(the record's wall-clock update time), `portable`, location kind, role, holder,
and the record and snapshot as JSON text. Tamework writes and reads the JSON;
keep it as text. A portable entry carries its snapshot; a world-bound entry
carries the record only.

`supersedes(other)` is the ordering: a higher generation, or the same
generation and a later version. `replaces(stored)` is the store's rule: it
supersedes the stored entry, or it has the same `holder`. The second part lets a
server undo a commit it reverted after writing it.

## When an owner joins

Tamework reads the owner's entries when they connect. A newer portable entry
replaces the local copy. A local portable copy that another server now holds in
its world becomes a released tombstone with cause `NETWORK_ELSEWHERE`: it never
counts, and any stale body or item of it on this server is removed or emptied.

Before a portable companion is restored, Tamework reads that profile again. A
capture item whose companion this server has not seen yet is never emptied: the
player sees "This companion is still arriving from another server" and can try
again. When the store cannot be read and this server has not read the owner's
entries since they joined, the restore waits the same way, so two servers never
restore one companion.

## Where companions can come out

Admins choose this per server in `/tw settings`, under Where Companions Can
Come Out: everywhere except the listed places, or only in them. A place is a
world name, a server id (the store's `holderId`) or `server/world`, so one
`tamework-deploy-places.json` can be shared by every server of a network. For
example, list your hub servers to keep animals out of them while players still
carry and trade lanterns there. Without a store, only world names match.

## Limits

- Durability is best effort, like the rest of the store. A crash right after a
  change can lose it.
- Counts of companions held on other servers refresh when the owner joins.
- Deploy the same Tamework build to every server of the network.

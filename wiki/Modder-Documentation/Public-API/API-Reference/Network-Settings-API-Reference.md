---
title: "Network Settings API Reference"
order: 26
published: true
draft: false
---
# Network Settings API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference)

`TameworkApi.networkSettings()`. Check `NETWORK_SETTINGS_STORE` and
`available()` before registering. Older or degraded implementations return an
unavailable facade.

This API is for server networks: several servers that share one database. A
network mod installs one `NetworkSettingsStore`. With it, the settings an admin
publishes on one server apply on every server, and each server may still
override single settings. Without one, every server keeps its own settings, as
before. It is separate from the
[Portable Companions API](/mod/alecs-tamework/portable-companions-api-reference);
a network may install either or both.

## What is shared

Everything `/tw settings` saves on a server, except the welcome announcement
switch: the main settings, the Companion Inspect settings, the default HUD
position, where companions can come out, the name filter and mod settings.
Player settings are not part of it.

Each setting resolves on its own: this server's override, then the network's
value, then the built-in default. Lists (places, filter words) override as a
whole. See
[Tamework Settings UI and Persistence](/mod/alecs-tamework/tamework-settings-ui-and-persistence)
for what admins see.

## Registering

`register(NetworkSettingsStore)` accepts one store and returns an idempotent
`AutoCloseable`. Close it when the network mod stops. A second store throws
`IllegalStateException`. Once registered, Tamework reads the network's settings
in the background.

## The store

The store holds opaque JSON documents by key. Tamework writes and reads the
JSON; keep it as text. Tamework uses two keys today:
`NetworkSettingsStore.SERVER_KEY` (`"server"`) for the settings and
`"shared-access"` for shared access grants (see below). Keys that start with
`player/` are reserved, so accept any key.

Tamework calls both methods on its own `tamework-network-settings` thread, one
call at a time, never on a world thread. Calls may block on I/O. A call that
throws is tried again at the next check, and Tamework keeps using the last
document it read.

- `load(key)`: the stored `NetworkSettingsDocument` (`json`, `revision`), or
  null when none was saved yet. A revision is greater than zero and higher
  after every save of the same key.
- `save(key, json, expectedRevision)`: store `json` only if the stored revision
  still equals `expectedRevision` (`0` means no document is stored), and give
  it a higher revision. Return false, and write nothing, when the revision
  differs. Compare and write in one atomic step, because other servers write
  too. With SQL, `UPDATE ... SET json = ?, revision = revision + 1 WHERE key = ?
  AND revision = ?` does it.

## When servers see a change

Tamework reads the server document when the store is registered and then once
a minute. Call `notifyChanged(key)` when you learn that another server saved,
for example from a pub/sub message, and Tamework reads it at once. The call is
safe from any thread and returns immediately.

A server that reads a network document for the first time keeps its own values
only where they differ from both the network's value and the built-in default.
Each value that changes is logged at `INFO`.

## Shared access grants

A registered store also carries the grants owners make when they share their
tamed animals with other players. All owners' grants are one document under
the key `shared-access`. There is nothing more to register: a store that
accepts any key already carries it. Without a store, each server keeps its
grants in its own `shared-access.json`.

Tamework reads this document when the store is registered and then once a
minute, and saves it when an owner changes a grant on this server. Call
`notifyChanged("shared-access")` when you learn that another server saved it,
and the grant applies on this server at once. Without the call it applies
within a minute.

- Servers merge by owner. When two servers save at the same moment, the second
  save is refused, reads the first one's document and saves again on top of
  it, so both owners' changes are kept. If both changed the same owner, the
  later save wins for that owner.
- A server that joins with grants of its own adds them to the network's. After
  that it takes the network's grants, so an owner who stopped sharing while
  the server was down stays that way.
- Each server keeps a copy in its own file, and the grants in it still apply
  when the store is down. A grant changed while the store is down is saved to
  the network at the next check that succeeds. If the server restarts before
  that, the change is lost: the server takes the network's grants for that
  owner when it reads the store again.
- **Leave network** on the settings page concerns settings only. Grants keep
  following the store while one is registered.

## Limits

- The last document a server read is saved in
  `universe/Tamework/Settings/tamework-network-settings.json` and still applies
  when the store is down or not installed.
- Two admins publishing at the same moment: the second is refused and sees the
  first one's settings.
- Deploy the same Tamework build to every server of the network.

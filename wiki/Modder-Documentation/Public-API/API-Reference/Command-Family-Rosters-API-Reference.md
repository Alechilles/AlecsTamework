---
title: "Command Family Rosters API Reference"
order: 15
published: true
draft: false
---
# Command Family Rosters API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `COMMAND_FAMILY_ROSTERS`

Entry point: `TameworkApi.commandFamilyRosters()`.

## Methods

- `get(ownerUuid, commandFamilyId)`
- `getMembership(ownerUuid, commandFamilyId, profileId)`
- `upsert(request)`
- `remove(request)`

The roster is durable owner/family/profile authority. Membership uses stable
slots and survives replacement of an access item. Equivalent items may project
the roster to item metadata for presentation, but that cache is not authority.

Use stable, namespaced command-family IDs and caller idempotency keys. An
existing membership retains its slot on equivalent upsert; candidate slots are
used only for a new member. Results are asynchronous and report `APPLIED`,
`IDEMPOTENT`, `CONFLICT`, `NOT_FOUND`, `UNAVAILABLE`, or `FAILED` without
requiring direct persistence access.

Roster membership does not itself prove a live projection. Read canonical
lifecycle and, where enabled, timed-summon state.

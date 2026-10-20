---
title: "Population Groups API Reference"
order: 14
published: true
draft: false
---
# Population Groups API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> `getReconciliationStatus()` was removed. Counts come straight from the
> companion store, so there is no reconciliation state to report.

Capabilities: `POPULATION_GROUPS`, `DURABLE_POPULATION_GROUP_COUNTS`, and
`DURABLE_DEPLOYABLE_POPULATION_COUNTS`.

`LOADED_POPULATION_GROUP_COUNTS` is not advertised in 3.0.0.

Entry point: `TameworkApi.populationGroups()`.

## Methods

- `getDefinition(groupId)`
- `resolveForRole(roleId)`
- `getCounts(ownerUuid, groupId, ownershipWorldName)`
- `getDurableOwnedCount(ownerUuid, groupIds)`
- `getDurableDeployableCount(ownerUuid, groupIds)`
- `getLoadedOwnedCount(ownerUuid, groupIds)`

Definitions and counts are detached read-only views. Every read is synchronous
and safe from any thread. A role can resolve to more than one group, and every
group may limit owned and active counts.

## Location kinds

A companion is in exactly one place:

| Kind | Meaning |
| --- | --- |
| `LIVE` | It has a body in a world. The body may be in an unloaded chunk. |
| `ITEM` | It is held in a capture item. |
| `COOP` | It is housed in a coop. |
| `STORED` | It is stored in a roster. |
| `DEAD` | It died and may be revivable. |
| `LOST` | Its body vanished without a confirmed death. |
| `RELEASED` | It was released or culled. It no longer counts. |

## Counts

`getDurableOwnedCount` counts every companion of the owner whose role is in
one of the groups, in every kind except `RELEASED`.

`getDurableDeployableCount` counts `LIVE` companions only. Captured, housed,
stored, dead, lost, and released companions do not count. This is narrower
than in 2.x, where unloaded and lost companions also counted.

Both count each companion once, however many of the groups its role belongs
to. Both return an empty result when one of the groups is unknown. Do not
treat empty as zero.

`getCounts` returns one group's view:

- `committedOwned` is the owned count and `committedActive` is the `LIVE`
  count.
- `pendingOwned`, `pendingActive`, and `classificationRevision` are always `0`.
- `maxOwned` and `maxActive` come from the group config. `0` means no limit.
- A per-world group counts a companion in the world it is in, or the world it
  was tamed in when it has no body. A per-world group asked without a world
  returns empty.

`getLoadedOwnedCount` returns empty in 3.0.0.

## Counts are not reservations

A count read reserves nothing. `policies().populationAdmissions()` and its
tokens were removed. Tamework checks the owner cap, the group limits, and any
admission provider's domain limits itself, in the step that changes the
companion. A custom feature that grants a companion through Tamework (tame,
capture, spawn, bonded provision) gets that check for free and must handle a
refusal.

To add your own policy to that check, register an
[admission provider](/mod/alecs-tamework/admission-providers-api-reference).

The compatibility fallback returns empty definitions and counts. Check the
capability for every action and fail closed before player cost or live
mutation.

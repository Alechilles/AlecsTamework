---
title: "Population Groups API Reference"
order: 14
published: true
draft: false
---
# Population Groups API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capabilities: `POPULATION_GROUPS`, `DURABLE_POPULATION_GROUP_COUNTS`, and
`LOADED_POPULATION_GROUP_COUNTS`.

Entry point: `TameworkApi.populationGroups()`.

Development capability: `DURABLE_DEPLOYABLE_POPULATION_COUNTS` adds world-animal
counts without treating captured animals as active.

## Methods

- `getDefinition(groupId)`
- `resolveForRole(roleId)`
- `getCounts(ownerUuid, groupId, ownershipWorldName)`
- `getDurableOwnedCount(ownerUuid, groupIds)`
- `getDurableDeployableCount(ownerUuid, groupIds)`
- `getLoadedOwnedCount(ownerUuid, groupIds)`
- `getReconciliationStatus()`

Definitions and counts are detached read-only views. A role can resolve to
multiple groups, and every group may constrain owned and active counts.

Use `getDurableOwnedCount` for owned-capacity UI and denial checks. It includes
active, unloaded, dead, lost, captured, and coop profiles, and excludes only
released profiles. It returns an empty result when projection authority or a
requested group is unavailable.

`getDurableDeployableCount` includes `ACTIVE`, `UNLOADED`, `LOST`, and `UNRESOLVED`.
It excludes captured, stored, coop, released and dead profiles. It uses the same
lifecycle classification as named deployable-domain admission. It is a projected
committed-profile count, not a reservation or a count of pending births. Missing
groups or unavailable projections return an empty result; do not treat that as zero.

`getLoadedOwnedCount` is a process-local live count. Use it only for features
that explicitly need loaded NPCs. Do not use it to enforce owned capacity.

For a custom capacity-increasing mutation, use
`api.policies().populationAdmissions().tryAdmitV2(request)` so owner and all
matching group reservations are acquired atomically. Complete the token with
claim/commit or cancel; a count read is not a reservation.

The compatibility fallback returns empty definitions/counts and an unavailable
reconciliation view. Check the capability for every action and fail closed
before player cost or live mutation.

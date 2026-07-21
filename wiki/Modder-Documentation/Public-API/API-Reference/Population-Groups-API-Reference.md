---
title: "Population Groups API Reference"
order: 16
published: true
draft: false
---
# Population Groups API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.9.0`)**
> Group-aware reads and admissions are authoritative only when
> `POPULATION_GROUPS` is advertised.

Tamework 3.0.0 installs group admission on the canonical owner-population
coordinator after group-operation recovery and reconciliation succeed. It
advertises this capability only while that installation remains authoritative;
definitions, DTOs, or schema-v8 tables alone never authorize group-aware
admission. A role-changing integration must use an admitted canonical mutation
path; API 0.9 does not expose an independent role-transformation authority.

Capability: `POPULATION_GROUPS`

## Entry points

- `TameworkApi.policies().populationGroups() -> PopulationGroupApi`
- `TameworkApi.policies().populationAdmissions().tryAdmitV2(...)`

`PopulationGroupApi` is read-only. It exposes definitions, role membership,
per-owner committed/pending counts, and reconciliation health. It deliberately
has no direct increment/decrement method.

`tryAdmitV2` accepts the ordinary mutation-bound
`PopulationAdmissionRequest` plus a target role and authoritative ownership
world. Tamework resolves group membership from the role; callers cannot supply
or bypass a group set.

## Read methods

- `getDefinition(String groupId)`
- `resolveForRole(String roleId)`
- `getCounts(UUID ownerUuid, String groupId, String ownershipWorldName)`
- `getReconciliationStatus()`

Counts separate committed and pending owned/active units. A limit of `0` means
unlimited. `GLOBAL` groups ignore world buckets; `PER_WORLD` groups require an
ownership world.

## Admission lifecycle

Group-aware admissions use the same durable prepare/claim/commit/cancel
lifecycle documented by the Population Admission API. Positive admission
requires group reconciliation to be ready. An API 0.8 implementation inherits
a fail-closed `tryAdmitV2` default and cannot silently bypass group limits.

Existing over-limit companions remain represented. Tamework blocks later
positive admissions until counts fall within policy; it does not delete or
release companions to force compliance.

## Lifecycle counting

Owned limits count canonical non-released profiles. Active limits count active
or active-equivalent projections, including pending/restoring work that has
reserved capacity. The exact lifecycle classifier is Tamework-owned and uses
canonical profile/journal evidence, not a downstream entity scan.

## Events

- `PopulationGroupMembershipChangedEvent`
- `PopulationGroupLimitChangedEvent`

Events are immutable post-commit notifications. They do not authorize a
mutation and are not cancelable preflight hooks.

## Related pages

- [TwPopulationGroupConfig Reference](/mod/alecs-tamework/twpopulationgroupconfig-reference)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)

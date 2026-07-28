---
title: "TwPopulationGroupConfig Reference"
order: 16
published: true
draft: false
---
# TwPopulationGroupConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls

`TwPopulationGroupConfig` classifies exact canonical role IDs into a stable,
namespaced logical group and sets atomic per-owner owned/active limits.
Multiple groups may match one role; admission must satisfy every matching
group.

## Asset Location and Resolution

- Location: `<ModRoot>/Server/Tamework/PopulationGroups/*.json`
- Scope: logical group with exact role membership
- `GroupId`: stable, namespaced, and case-sensitive
- Duplicate `GroupId`: highest `Priority` wins; asset-ID ordering breaks ties
- Reload: normal asset loaded/removed events, not `/tw reloadconfig`

## Fields

- `Enabled`: disabled assets are inert.
- `Priority`: winner priority for duplicate logical group IDs.
- `GroupId`: namespaced identity such as `hydragon:full_dragons`.
- `RoleIds`: nonempty exact canonical role list. An explicit array replaces
  the inherited list.
- `Limits.MaxOwnedPerOwner`: maximum canonical owned profiles; `0` is
  unlimited.
- `Limits.MaxActivePerOwner`: maximum active profiles; `0` is unlimited.
- `Limits.Scope`: `Global` or `PerWorld`.

Owned limits include canonical profiles across active, unloaded, captured,
cooped, roster-stored, provisioned-dormant, dead, and Lost states. Active
limits apply to active projections. Positive admission reserves all affected
groups atomically, so a companion cannot commit into only part of its
classification.

## Example

```json
{
  "Enabled": true,
  "Priority": 100,
  "GroupId": "hydragon:full_dragons",
  "RoleIds": [
    "HyDragon_Dragon_Fire",
    "HyDragon_Dragon_Ice"
  ],
  "Limits": {
    "MaxOwnedPerOwner": 6,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

## Inheritance and Safety

- Omitted top-level fields inherit from the parent.
- An explicit `Limits` object inherits missing nested fields.
- An explicit `RoleIds` array replaces the parent array.
- Blank/non-namespaced group IDs, empty role lists, negative limits, duplicate
  role IDs, or unknown scope values reject the candidate.
- A failed rebuild retains the last valid compiled population-group index.

## Related Pages

- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [TwCompanionConfig Reference](/mod/alecs-tamework/twcompanionconfig-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)

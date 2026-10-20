---
title: "TwPopulationGroupConfig Reference"
order: 16
published: true
draft: false
---
# TwPopulationGroupConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

## What It Controls

`TwPopulationGroupConfig` classifies exact role IDs into a stable, namespaced
logical group and sets per-owner owned and active limits.
Multiple groups may match one role; admission must satisfy every matching
group.

## Asset Location and Resolution

- Location: `<ModRoot>/Server/Tamework/PopulationGroups/*.json`
- Scope: logical group with exact role membership
- `GroupId`: stable, namespaced, and case-sensitive
- Duplicate `GroupId`: highest `Priority` wins; asset-ID ordering breaks ties
- Reload: normal asset loaded/removed events, not `/tw config reload`

## Fields

- `Enabled`: disabled assets are inert.
- `Priority`: winner priority for duplicate logical group IDs.
- `GroupId`: namespaced identity such as `hydragon:full_dragons`.
- `RoleIds`: nonempty exact role list. An explicit array replaces the
  inherited list.
- `Limits.MaxOwnedPerOwner`: maximum owned companions in the group; `0` is
  unlimited.
- `Limits.MaxActivePerOwner`: maximum companions of the group out in the
  world; `0` is unlimited.
- `Limits.Scope`: `Global` or `PerWorld`.

Counts come straight from the companion index; group membership is read from
the role list at check time, so a config change applies at once. Owned limits
count every owned companion of the group: out in the world (loaded or not),
stored in a roster or bonded storage, provisioned, in a capture item, in a coop,
dead, and lost. Active limits count only companions out in the world, loaded or
not. An imported companion whose body has not been seen since the update does
not count as active yet. Released companions count toward neither.

Every matching group is checked in the same locked step that changes the
companion record, so a companion is never admitted into only part of its
groups. A change that adds the companion to no new count always passes, even
for an owner already over a lowered limit.

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

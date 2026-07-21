---
title: "TwPopulationGroupConfig Reference"
order: 22
published: true
draft: false
---
# TwPopulationGroupConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

> **Tamework 3.0.0 / experimental API 0.9**
> Group-aware mutation is authoritative only when `POPULATION_GROUPS` is
> advertised. An indexed definition alone is not permission to bypass the
> fail-closed admission API.

## What it controls

`TwPopulationGroupConfig` assigns several exact role IDs to one logical group
and applies per-owner owned/active limits to that group. A profile can belong to
more than one group; Tamework resolves every applicable group from the role and
enforces all of them atomically.

## Asset location and resolution

- Location: `<ModRoot>/Server/Tamework/PopulationGroups/*.json`
- Logical identity: namespaced, case-sensitive `GroupId`
- Duplicate `GroupId` winner: higher `Priority`, then case-insensitive asset ID,
  then case-sensitive asset ID
- Reload: normal asset loaded/removed events rebuild the compiled index

If a rebuild fails, Tamework retains the last valid compiled index.

## Fields

| Field | Default | Meaning |
| --- | --- | --- |
| `Enabled` | `true` | Disabled assets are excluded. |
| `Priority` | `0` | Winner precedence for duplicate logical group IDs. |
| `GroupId` | required | Stable namespaced group identity, such as `hydragon:full_dragons`. |
| `RoleIds` | `[]` | Exact canonical roles. An enabled empty array is invalid. |
| `Limits` | defaults below | Per-owner owned/active policy. |

`Limits` fields:

| Field | Default | Meaning |
| --- | --- | --- |
| `MaxOwnedPerOwner` | `0` | Maximum owned canonical profiles; `0` is unlimited. |
| `MaxActivePerOwner` | `0` | Maximum active/active-equivalent profiles; `0` is unlimited. |
| `Scope` | `Global` | `Global` or `PerWorld` owner bucket. |

## Inheritance

- Omitted top-level values inherit from the parent.
- An explicit `Limits` object overrides authored nested fields and inherits its
  missing nested fields.
- Explicit `RoleIds` replaces the parent array; it never appends or unions.

## Example

```json
{
  "Enabled": true,
  "Priority": 100,
  "GroupId": "hydragon:full_dragons",
  "RoleIds": [
    "NordicDrake_Tamed",
    "Hydra_Tamed",
    "RockDrakeT1_Tamed",
    "RockDrakeT2_Tamed",
    "RockDrakeT3_Tamed"
  ],
  "Limits": {
    "MaxOwnedPerOwner": 0,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

A Soul Bond-exclusive companion can use a separate group:

```json
{
  "GroupId": "hydragon:soulbound_mini",
  "RoleIds": [
    "Miniwyvern_Tamed"
  ],
  "Limits": {
    "MaxOwnedPerOwner": 1,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

Use the exact role IDs shipped by the downstream content pack; the examples
show policy shape and do not create those roles in Tamework.

## Counting and failure behavior

Counts are canonical-profile based, not live-entity scans. Pending admissions
reserve capacity. Existing over-limit companions remain represented and block
later positive admissions; Tamework does not delete or release them to fit a
new limit.

Group membership and count evidence are schema-v8 persistence authority.
Unknown classification, reconciliation, stale revisions, and unavailable
storage fail positive admission closed.

## Related pages

- [Population Groups API Reference](/mod/alecs-tamework/population-groups-api-reference)
- [Population Admission API Reference](/mod/alecs-tamework/population-admission-api-reference)
- [Companion Provisioning API Reference](/mod/alecs-tamework/companion-provisioning-api-reference)
- [HyDragon Integration Guide](/mod/alecs-tamework/hydragon-integration-guide)

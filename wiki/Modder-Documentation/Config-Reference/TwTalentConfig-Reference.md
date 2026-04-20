---
title: "TwTalentConfig Reference"
order: 19
published: true
draft: false
---
# TwTalentConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwTalentConfig` defines the passive talent tree available to a companion role. It controls node costs, minimum levels, prerequisites, and the passive effect multipliers granted by purchased nodes.

## Path
`Server/Tamework/Talents/*.json`

## Resolution
- Role-scoped by `RoleIds`
- Higher `Priority` wins when multiple enabled configs match the same role

## Inheritance
- Omitted top-level keys inherit from the parent
- Explicit arrays replace the parent value

## Top-Level Fields
- `Enabled`
- `Priority`
- `RoleIds`
- `Talents`

## `Talents[]`
- `Id`: unique talent ID
- `DisplayName`: player-facing node name
- `Description`: player-facing node description
- `IconPath`: optional icon asset path for the talent page
- `Tier`: sorting tier used by the talent UI
- `Branch`: optional label for grouping or future tree presentation
- `PointCost`: talent-point cost to purchase the node
- `MinLevel`: minimum companion level required
- `RequiresTalentIds[]`: prerequisite talent IDs that must already be purchased
- `Effects[]`: passive effect multipliers granted by the node

## `Effects[]`
- `EffectKey`: shared progression effect key like `MaxHealthMultiplier`, `MoveSpeedMultiplier`, or `DamageDealtMultiplier`
- `Multiplier`: multiplier applied once the node is purchased

## Runtime Notes
- Talents are passive-only in the current implementation
- Talent spending is only available while the companion is loaded
- Available points come from the active `TwLevelingConfig`

## Related Pages
- [TwLevelingConfig Reference](/mod/alecs-tamework/twlevelingconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)

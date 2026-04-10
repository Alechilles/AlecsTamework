---
title: "TwLevelingConfig Reference"
order: 18
published: true
draft: false
---
# TwLevelingConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwLevelingConfig` controls companion XP gain, the level curve, level-based passive stat growth, and how many talent points each level grants.

## Path
`Server/Tamework/Leveling/*.json`

## Resolution
- Role-scoped by `RoleIds`
- Higher `Priority` wins when multiple enabled configs match the same role

## Inheritance
- Omitted top-level object sections inherit from the parent
- Explicit object sections inherit missing nested keys from the parent
- Explicit arrays replace the parent value

## Top-Level Fields
- `Enabled`
- `Priority`
- `RoleIds`
- `Levels`
- `XpSources`
- `StatGrowth`
- `TalentPoints`

## `Levels`
- `MaxLevel`: hard cap for level progression
- `BaseXp`: XP needed for the first level-up
- `GrowthFactor`: multiplier applied to each successive level-up requirement

## `XpSources`
- `Feed.FlatXp`: flat XP for successful feed interactions
- `Harvest.FlatXp`: flat XP for successful harvest drops
- `Breeding.FlatXp`: flat XP awarded to each parent when breeding produces offspring
- `Combat.DamageDealtXpPerPoint`: XP per point of final damage dealt
- `Combat.DamageTakenXpPerPoint`: XP per point of final damage taken
- `Combat.MinimumDamageEvent`: minimum final damage before the event awards combat XP
- `Combat.AwardVsPlayers`: allow combat XP against or from players
- `Combat.AwardVsOwnedAllies`: allow combat XP between entities with the same owner

## `StatGrowth`
- `Effects[]`: array of shared effect-key multipliers applied by level
- `EffectKey`: shared progression effect key like `MaxHealthMultiplier` or `DamageDealtMultiplier`
- `PerLevel`: additive growth above level 1

## `TalentPoints`
- `PointsPerLevel`: talent points granted per level-up after level 1

## Related Pages
- [TwTalentConfig Reference](/mod/alecs-tamework/twtalentconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)

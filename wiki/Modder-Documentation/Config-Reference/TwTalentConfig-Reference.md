---
title: "TwTalentConfig Reference"
order: 19
published: true
draft: false
---
# TwTalentConfig Reference

Parent: [Config Reference](/mod/alecs-tamework/config-reference) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

`TwTalentConfig` defines the passive talent tree available to a companion role. It controls branch/tier presentation, node costs, minimum levels, prerequisites, and the passive effect multipliers granted by purchased nodes.

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
- `Tier`: tree row used by the talent UI
- `Branch`: tree branch/column label used by the talent UI
- `PointCost`: talent-point cost to purchase the node
- `MinLevel`: minimum companion level required
- `RequiresTalentIds[]`: prerequisite talent IDs that must already be purchased
- `Effects[]`: passive effect multipliers granted by the node

## `Effects[]`
- `EffectKey`: shared progression effect key like `MaxHealthMultiplier`, `MoveSpeedMultiplier`, or `DamageDealtMultiplier`
- `Multiplier`: multiplier applied once the node is purchased

Common runtime effect keys:
- `MaxHealthMultiplier`
- `MoveSpeedMultiplier`
- `DamageDealtMultiplier`
- `HarvestDoubleDropChanceMultiplier`
- `HappinessGainMultiplier`
- `BreedCooldownMultiplier`
- `FertilityMultiplier`
- `NeedsDecayMultiplier`: multiplies hunger and thirst decay; values below `1.0` slow decay
- `ReviveCooldownMultiplier`: multiplies companion revive cooldown
- `TraitMutationChanceMultiplier`: multiplies trait mutation chance during inheritance; final chance is clamped to `0.0..1.0`
- `HarvestCooldownMultiplier`: multiplies harvest reset cooldown when harvest interactions use `TameworkHarvestAlarm`
- `AvatarFlightVigourCapacityMultiplier`: multiplies avatar-flight Vigour capacity; neutral `1.0`, clamped to `1.0..1.35`, so values above `1.0` increase capacity
- `AvatarFlightVigourRechargeRateMultiplier`: multiplies avatar-flight Vigour recharge rate; neutral `1.0`, clamped to `1.0..1.35`, so values above `1.0` recharge faster
- `AvatarFlightForwardBoostCostMultiplier`: multiplies avatar-flight forward-boost Vigour cost; neutral `1.0`, clamped to `0.70..1.0`, so values below `1.0` reduce cost
- `AvatarFlightForwardBoostImpulseMultiplier`: multiplies avatar-flight forward-boost impulse; neutral `1.0`, clamped to `1.0..1.25`, so values above `1.0` increase impulse
- `AvatarFlightGlideSinkMultiplier`: multiplies avatar-flight passive glide sink; neutral `1.0`, clamped to `0.70..1.0`, so values below `1.0` reduce sink
- `AvatarFlightClimbLiftMultiplier`: multiplies avatar-flight climb lift; neutral `1.0`, clamped to `1.0..1.25`, so values above `1.0` increase lift

## Runtime Notes
- Talents are passive-only in the current implementation
- The in-game talent page renders a scrollable branch/tier tree. Nodes are grouped by `Branch`, ordered by `Tier`, and connected with `RequiresTalentIds[]` prerequisite links within or across branches.
- Talent spending is only available while the companion is loaded
- Spent talent points can be reset from the in-game talents page, which clears purchased nodes and makes the earned points available to spend again
- Available points come from the active `TwLevelingConfig`
- Avatar-flight effects resolve from the valid parked source companion during an avatar-flight session. Missing or invalid sessions use neutral `1.0` tuning, and each runtime clamp above still applies after effect resolution.

## Tree Authoring Guidance
- Keep node names short; the tree node shows the name, point cost, and state, while the side panel shows the longer description and effect summary.
- Use `Branch` for broad specialization columns such as `Care`, `Breeding`, `Recovery`, `Combat`, or `Mobility`.
- Use `Tier` to keep early choices near the top and deeper choices lower in the tree. Multiple nodes can share the same branch and tier; the UI stacks them in that branch.
- Use `RequiresTalentIds[]` for actual dependency lines. If a later node should feel visually connected, point it at the earlier node explicitly.
- Do not add layout coordinates to configs for v1. Oversized trees extend the canvas vertically and the player scrolls the talent viewport.

## Related Pages
- [TwLevelingConfig Reference](/mod/alecs-tamework/twlevelingconfig-reference)
- [Progression Systems Guide](/mod/alecs-tamework/progression-systems-guide)

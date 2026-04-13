---
title: "Naming Runtime Internals"
order: 8
published: true
draft: false
---
# Naming Runtime Internals

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

## Main orchestrator
`NamingFeatureHandler`

## Service split
- `NamingNpcInfoService`: gathers target NPC state and validates ownership or tame requirements
- `NamingEffectService`: applies naming effects, item consumption, cooldowns, particles, and sound
- `TameworkNameInputPage`: player-facing naming UI page

## Important persistence behavior
- Tamework names live in `TameworkNpcNameComponent`
- `NpcNamePersistenceSystem` restores names on load
- Spawner capture paths preserve and restore name state

## Maintenance advice
- Keep UI concerns out of server-side validation
- Re-validate ownership and tame state at submit time even if the UI opened successfully

## Related Pages
- [Spawner Runtime Internals](/mod/alecs-tamework/spawner-runtime-internals)
- [Ownership, Damage, and Progression Internals](/mod/alecs-tamework/ownership-damage-and-progression-internals)




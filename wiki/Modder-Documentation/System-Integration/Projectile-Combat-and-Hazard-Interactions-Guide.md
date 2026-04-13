---
title: "Projectile Combat and Hazard Interactions Guide"
order: 12
published: true
draft: false
---
# Projectile Combat and Hazard Interactions Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

Use this guide when you want combat-oriented custom interactions without building a full bespoke Java action pipeline.

## Primary Runtime Piece
- Interaction entry type: `TameworkLaunchProjectile` inside `TwInteractionConfig.Interactions[]`

This interaction supports direct shots, high-angle lob shots, source-centered random barrages, impact effects, and optional lingering hazard zones.

## Typical Authoring Flow
1. Add a `Custom` interaction entry with `Type: "TameworkLaunchProjectile"`.
2. Set `ProjectileId` to your projectile asset.
3. Pick targeting strategy (`Target`, `TargetSlot`, or random-around-source radii).
4. Set `TrajectoryMode` (`HIGH_ANGLE` or `DIRECT`).
5. Add optional spread (`YawSpreadDegrees`, `PitchSpreadDegrees`).
6. Add optional `ImpactEffect` and/or `LingeringHazard` blocks.
7. Gate execution through `Requires` (owner/tame/state/context) and add feedback in `Effects` as needed.

## Targeting Models
### Entity target
- Uses `Target` (`USER`, `OWNER`, `TARGET`) and optional `TargetSlot` override.
- Best for focused attacks or support casts.

### Source-centered random target
- Uses `RandomAroundSourceMinRadius` + `RandomAroundSourceMaxRadius`.
- Optional `RandomAroundSourceVerticalOffset` shifts the sampled landing plane.
- Best for area denial and bombardment-style behaviors.

When random-around-source radii are configured, they override entity-target resolution.

## Trajectory Modes
- `HIGH_ANGLE`: solves a ballistic arc using projectile velocity and gravity.
- `DIRECT`: points directly at the solved target instead of high-lob arc behavior.

Use `FailIfNoSolution: true` for strict behavior. Use `false` when you prefer graceful fallback rather than hard interaction failure.

## Impact and Lingering Extensions
### `ImpactEffect`
Applies an entity effect in radius at projectile impact/removal location.

### `LingeringHazard`
Creates a hidden pulse-damage zone at projectile impact/removal location.

Good uses:
- chilled/frozen zones
- poison gas zones
- denial rings around objective points

## Design Guidance for Future Combat Features
- Keep combat behavior config-driven in `TwInteractionConfig` where possible.
- Use stable interaction IDs and parameter names for cross-mod consistency.
- Keep requirements and prompts in config; reserve hooks/Java for behavior that cannot be expressed by existing fields.
- For new mechanics, follow the `TameworkLaunchProjectile` pattern: base interaction + optional nested behavior blocks.

## Troubleshooting
- Verify projectile id exists and is loadable.
- Verify target resolution is valid (slot target, owner/user/target, or random radius setup).
- Use `/tw debugprompt` to ensure interaction gating and prompt behavior are correct.
- Use `/tw debughook` only if this projectile entry is chained with hook behavior.

## Related Pages
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)

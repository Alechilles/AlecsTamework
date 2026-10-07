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

This interaction supports direct shots, high-angle lob shots, source-centered random barrages, landing markers, impact effects, hatch spawns, and optional lingering hazard zones.

## Typical Authoring Flow
1. Add a `Custom` interaction entry with `Type: "TameworkLaunchProjectile"`.
2. Set `ProjectileId` to your projectile asset.
3. Pick targeting strategy (`Target`, `TargetSlot`, or random-around-source radii).
4. Set `TrajectoryMode` (`HighAngle` or `Direct`).
5. Add optional spread (`YawSpreadDegrees`, `PitchSpreadDegrees`).
6. Add optional `ImpactEffect` and/or `LingeringHazard` blocks.
7. Gate execution through `Requires` (owner/tame/state/context) and add feedback in `Effects` as needed.

## Targeting Models
### Entity target
- Uses `Target` (`USER`, `OWNER`, `TARGET`) and optional `TargetSlot` override.
- Best for focused attacks or support casts.
- Add `TargetGroundOffset: 0.05` to aim just above the target's feet instead of its eye height. This samples the feet at launch time and does not project airborne targets onto terrain.

### Source-centered random target
- Uses `RandomAroundSourceMinRadius` + `RandomAroundSourceMaxRadius`.
- Optional `RandomAroundSourceVerticalOffset` shifts the sampled landing plane.
- Best for area denial and bombardment-style behaviors.

When random-around-source radii are configured, they override entity-target resolution.

## Trajectory Modes
- `HighAngle`: solves a ballistic arc using projectile velocity and gravity.
- `Direct`: points directly at the solved target instead of high-lob arc behavior.

Use `FailIfNoSolution: true` for strict behavior. Use `false` when you prefer graceful fallback rather than hard interaction failure.

## Impact and Lingering Extensions
### Landing marker and hatch spawn

`LandingMarkerParticleSystemId` emits one particle at the same frozen position used by the trajectory solver after a successful launch. Configure the particle's lifetime to cover the flight. Use zero spread and zero native projectile shot offsets when the marker must match the landing point.

`ImpactSpawnNpcRole` spawns one configured NPC role at the projectile's final position on normal removal. This also includes lifetime expiry, so an egg can hatch without a collision. World unload does not hatch saved projectiles. The role owns the spawned NPC's chase, lifetime, and despawn behavior.

Loaded `TameworkLaunchProjectile` interaction assets activate projectile support at startup, including when tranquilizer asset sets and capture support are disabled.

For example, with your own projectile, marker particle system, and NPC role assets:

```json
{
  "Type": "TameworkLaunchProjectile",
  "ProjectileId": "Example_Egg",
  "TargetSlot": "CAETargetSlot",
  "TrajectoryMode": "HighAngle",
  "TargetGroundOffset": 0.05,
  "LandingMarkerParticleSystemId": "Example_Egg_Marker",
  "ImpactSpawnNpcRole": "Example_Egg_Minion"
}
```

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
- Use `/tw debug log prompt` to ensure interaction gating and prompt behavior are correct.
- Use `/tw debug log hook` only if this projectile entry is chained with hook behavior.

## Related Pages
- [TwInteractionConfig Reference](/mod/alecs-tamework/twinteractionconfig-reference)
- [Interaction Paths and Role Wiring](/mod/alecs-tamework/interaction-paths-and-role-wiring)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)

# Avatar Flight Launch VFX Implementation Plan

## Goal

Build the first AvatarFlight wind-pressure particle effects for:

- grounded launch charge;
- canceled or rejected launch release;
- successful partial, mid, and full launch release.

This plan implements the launch portion of
`docs/superpowers/specs/2026-07-07-avatar-flight-wind-vfx-design.md` without changing flight physics.
Flap, forward boost, airbrake, fast-flight trails, and sound playback remain follow-up work.

## Implementation Status

Implemented on 2026-07-09:

- five launch particle systems and eight reusable spawners;
- inherited `Vfx` config settings with configurable cadence, scale, systems, and tier thresholds;
- grounded origin capture, airborne preservation, cancel routing, and partial/mid/full release routing;
- warn-once missing-system handling through `ParticleUtil` spatial broadcasts;
- config, math, service, and asset-budget regression tests;
- Hytale Workshop schema validation for all thirteen authored assets with zero warnings.

In-game `/particle spawn` and multiplayer owner/observer art review remain manual validation steps.

Live diagnostics confirmed that a structural copy of vanilla `MagicHit_Flash` rendered from the
custom Tamework pack while the launch spawners did not. The server reported successful emissions
without particle validation warnings, and neither fresh short IDs, disabled soft particles, nor
nonzero initial opacity made the accumulated authored payloads render. Those payloads were removed
rather than tuned further. Each production `TwLaunch*` spawner is now rebuilt as a clean structural
copy of a known-working Hytale 0.5.6 spawner: `Stick_Slam_Shockwave_Small` for rings,
`Magic_Hit_Smoke` for dust/puffs, `Drop_Epic_Vortex` for wind wisps, and `Weapon_Frost_Mist` for the
release column. The temporary gold-flash probe and base ice shockwave remain removed. Visual
restyling should proceed incrementally after this clean baseline is confirmed in game.

The clean baseline rendered successfully in game. `TwLaunchChargeRing` is now runtime-confirmed as
one pale-cyan ring that contracts from `2.2` to `0.28` scale over `0.28s`. Incremental art tuning has
moved to `TwLaunchChargeWisps`, which retains the working `Drop_Epic_Vortex` field structure while
replacing its purple expanding vortex with up to three pale white-blue arcs that tighten over
`0.38s`. The wisp pass still needs in-game confirmation. Remaining charge, cancel, and release
spawners stay on their visible vanilla baselines until each is tuned and verified independently.

## Confirmed Hytale Particle Behavior

Research target: Hytale release `0.5.6`.

### Asset structure

- `ParticleSystem` assets live under `Server/Particles` and compose one or more
  `ParticleSpawnerGroup` entries.
- A system group references a global spawner ID and can override position, rotation, fixed rotation,
  spawn rate, group lifespan, start delay, wave delay, total spawners, max concurrency, initial
  velocity, emit offset, and attractors.
- `ParticleSpawner` assets define the actual particle shape, emission, lifetime, velocity, attractors,
  material, collision, and animation frames.
- Particle animation frame keys are normalized integer percentages from `0` through `100`.
- Particle systems and spawners support `Parent` inheritance, but the launch prototype should use
  explicit assets where that keeps the effect easier to inspect and tune.
- Particle and spawner IDs are global filename stems. Every new asset therefore uses the
  `Tamework_AvatarFlight_` prefix.

Authoritative engine sources:

- `com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem`
- `com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawnerGroup`
- `com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner`
- `com.hypixel.hytale.server.core.asset.type.particle.config.Particle`
- `com.hypixel.hytale.server.core.asset.type.particle.config.ParticleAttractor`

### Runtime spawning

`ParticleUtil.spawnParticleEffect(...)` sends a `SpawnParticleSystem` packet to nearby clients. The
packet can carry world position, yaw/pitch/roll, whole-system scale, fallback color, and a maximum
duration. The default nearby-player radius is `75` blocks. Hytale's implementation uses
`writeNoCache`, so repeated short charge pulses are independent spawn events.

For AvatarFlight launch effects:

- use world-space particle systems through `ParticleUtil`;
- include the transformed player in the recipient set by not passing that entity as `sourceRef`;
- use yaw-only rotation and keep pitch/roll at zero;
- use an explicit short maximum duration as a defensive bound;
- do not use `SpawnModelParticles` for the launch prototype.

Model particles support target nodes, position/rotation offsets, scale, color, and
`DetachedFromModel`, as confirmed by `ModelParticle` and vanilla `ActionSpawnParticles`. They are
appropriate for later wingtip trails and flap effects, but ground rings and dust should remain at
the launch origin instead of following the airborne model.

### Preview workflow

Hytale ships `ParticleSpawnCommand` and `ParticleSpawnPage`. `/particle spawn` can list registered
systems, preview them near the player, rotate the preview, and spawn the selected system using the
player transform. Use this before wiring the assets into AvatarFlight.

## Chosen Technical Direction

Use bounded world-space pulse systems for every launch effect.

This avoids a persistent-particle stop protocol, model-node differences, and orphaned effects. The
charge ramp comes from repeated short pulses. Pulse cadence and whole-system scale interpolate
continuously as charge increases, while each particle asset remains static and independently
previewable.

The first prototype reuses base-game particle textures:

| Purpose | Base texture | Reason |
| --- | --- | --- |
| Pressure rings | `Particles/Textures/Basic/Ring2.png` | Broken, soft white ring already used by expanding shockwaves. |
| Curved wind accent | `Particles/Textures/Circles/Portal_Wind.png` | Physical-looking circular wind stroke when kept pale and low-opacity. |
| Air column | `Particles/Textures/Smoke/Smoke_Mist.png` | Directional four-frame mist suitable for stretched upward airflow. |
| Dust | `Particles/Textures/Smoke/Smoke_Smooth2.png` | Soft four-frame smoke suitable for restrained earth-toned dust. |

Do not add custom Common textures until the first in-game art review proves these base textures
cannot produce the intended physical wind language.

## Asset Layout

Place systems and spawners under:

```text
src/main/resources/Server/Particles/Tamework/AvatarFlight/Launch/
src/main/resources/Server/Particles/Tamework/AvatarFlight/Launch/Spawners/
```

### Particle systems

| System ID | Role | Maximum life |
| --- | --- | --- |
| `Tamework_AvatarFlight_Launch_Charge_Pulse` | One short inward pressure pulse during charge. | `0.50s` |
| `Tamework_AvatarFlight_Launch_Cancel` | Tiny broken ring and dissipating puff. | `0.40s` |
| `Tamework_AvatarFlight_Launch_Release_Partial` | Minimum valid launch release. | `0.85s` |
| `Tamework_AvatarFlight_Launch_Release_Mid` | Medium charged launch release. | `1.00s` |
| `Tamework_AvatarFlight_Launch_Release_Full` | Fully charged launch release. | `1.15s` |

All systems should use `IsImportant: false`, a bounded radius, and a cull distance no larger than
the server send radius.

### Spawners

| Spawner ID | Composition target |
| --- | --- |
| `TwLaunchChargeRing` | One flat `Ring2` particle contracting from wide/faint to tight/transparent over roughly `0.35s`. |
| `TwLaunchChargeWisps` | Two or three pale `Portal_Wind` accents tightening toward the origin. |
| `TwLaunchChargeDust` | Three to five low earth-toned puffs pulled inward with a Y-axis radial attractor and a small tangent acceleration. |
| `TwLaunchCancelRing` | One small broken ring that appears briefly and collapses/fades. |
| `TwLaunchCancelPuff` | Two or three small grey-brown puffs with weak outward velocity. |
| `TwLaunchReleaseRing` | One flat ring expanding rapidly from near-zero scale and fading by `0.30s`. |
| `TwLaunchReleaseColumn` | Pale mist particles accelerated upward and stretched along velocity. |
| `TwLaunchReleaseDust` | Low radial dust pushed outward with short life and strong damping. |

### System composition by release tier

Reuse the same release spawners in all three systems. Increase intensity through explicit group
entries, start delays, and runtime scale instead of duplicating spawner definitions.

| Tier | Ring groups | Column target | Dust target | Runtime scale |
| --- | --- | --- | --- | --- |
| Partial | One ring | About 5-6 particles | About 5-6 particles | `0.75` |
| Mid | One ring | About 7-9 particles | About 8-10 particles | `1.00` |
| Full | Two readable rings separated by `0.06-0.10s` | About 10-12 particles | About 12-14 particles | `1.20` |

Avoid `Distortion` and additive glow in the first prototype. Prefer `Erosion` for rings and
`BlendLinear` or restrained `Erosion` for mist/dust. Wind color should stay near white or pale blue;
dust should stay neutral grey-brown.

## Spawner Tuning Targets

### Charge ring

- `SpawnBurst: true`, exactly one particle.
- `ParticleLifeSpan`: approximately `0.30-0.40s`.
- `ParticleRotationInfluence: None`.
- `ParticleRotateWithSpawner: true`.
- `ScaleRatioConstraint: OneToOne`.
- Initial X rotation: `90deg` so the ring lies on the ground plane.
- Animate scale from roughly `2.5-3.0` down to `0.30-0.40`.
- Opacity should peak around `0.50` and return to zero.

### Charge dust

- Keep emission within a shallow X/Z area around the footpoint; Y variance should stay near ground.
- Use a radial attractor with axis `(0, 1, 0)` and negative radial acceleration to pull inward.
- Add modest radial tangent acceleration so dust circles instead of moving in a perfectly straight
  line.
- Keep life below the parent system lifetime, use strong damping, and cap each pulse at five dust
  particles. The parent lifetime must cover each group's start delay plus its maximum particle life.

Vanilla evidence for inward motion comes from charged-weapon spawners such as
`Sword_Charging_Sparks`, which combine negative radial acceleration/impulse with tangent
acceleration. Vanilla `Stick_Slam_Shockwave_Small` provides the flat expanding `Ring2` pattern used
as the release-ring baseline.

### Release ring

- `SpawnBurst: true`, exactly one particle per group entry.
- `ParticleLifeSpan`: approximately `0.25-0.30s`.
- Start near zero scale, expand rapidly, and fade completely.
- Use a horizontal `Ring2` texture with `SoftParticles` disabled so terrain depth does not erase it.
- Keep the ring translucent enough that terrain remains visible.

### Release column

- Use `Smoke_Mist.png` with the correct frame size for its four-frame strip.
- Use `BillboardVelocity` or `Velocity` rotation influence.
- Apply positive Y linear impulse/acceleration instead of relying on uncertain pitch sign.
- Stretch particles vertically, fade quickly, and avoid an opaque smoke plume.
- The column begins at the stored ground origin and should not follow the launched avatar.

### Release dust

- Use `Smoke_Smooth2.png` with its four-frame sheet dimensions.
- Emit in a shallow X/Z area with low Y variance.
- Use outward direction/impulse with damping; keep vertical lift small.
- Partial release uses the fewest puffs; full release should still remain below fourteen.

## Config Surface

Add one external settings class, `AvatarFlightVfxSettings`, and expose it from
`TwAvatarFlightConfig` as the `Vfx` object section. Keep the first launch fields flat inside that
section so existing one-level nested inheritance remains predictable.

Suggested default fields:

```json
"Vfx": {
  "Enabled": true,
  "GroundOffsetY": 0.05,
  "MaxDurationSeconds": 1.25,
  "LaunchChargeEnabled": true,
  "LaunchChargeParticleSystem": "Tamework_AvatarFlight_Launch_Charge_Pulse",
  "LaunchChargeEarlyIntervalMs": 600,
  "LaunchChargeFullIntervalMs": 150,
  "LaunchChargeMinScale": 0.65,
  "LaunchChargeMaxScale": 1.15,
  "LaunchCancelEnabled": true,
  "LaunchCancelParticleSystem": "Tamework_AvatarFlight_Launch_Cancel",
  "LaunchCancelScale": 0.70,
  "LaunchReleaseEnabled": true,
  "LaunchReleasePartialParticleSystem": "Tamework_AvatarFlight_Launch_Release_Partial",
  "LaunchReleaseMidParticleSystem": "Tamework_AvatarFlight_Launch_Release_Mid",
  "LaunchReleaseFullParticleSystem": "Tamework_AvatarFlight_Launch_Release_Full",
  "LaunchReleasePartialScale": 0.75,
  "LaunchReleaseMidScale": 1.00,
  "LaunchReleaseFullScale": 1.20,
  "LaunchReleaseMidThreshold": 0.45,
  "LaunchReleaseFullThreshold": 0.80
}
```

Rules:

- defaults preserve existing behavior when old configs omit `Vfx`;
- omitted `Vfx` inherits the parent section;
- an explicit `Vfx` object overrides explicit nested keys and inherits missing nested keys;
- particle IDs should use late `ParticleSystem` asset validation where compatible with optional
  config loading;
- runtime lookup must still fail gracefully and warn once per missing particle-system ID;
- disabled effects do not emit and do not log missing assets;
- thresholds clamp to `[0, 1]`, with full threshold never below mid threshold;
- intervals clamp to a safe positive minimum, and full-charge cadence cannot be slower than early
  cadence;
- max duration and scales must be finite and positive.

`TwAvatarFlightConfig.java` is already above the repository's `1000`-line refactor threshold. The
implementation must reduce that class in the same change, preferably by extracting its codec
construction/inheritance plumbing while preserving public nested settings type names. Do not add
the VFX codec and fallback logic directly to the existing monolith without that extraction.

## Runtime Architecture

Add focused collaborators under `com.alechilles.alecstamework.avatarflight`:

- `AvatarFlightLaunchVfxMath`: pure charge progress, cadence, scale, and release-tier selection.
- `AvatarFlightParticleEmitter`: the small Hytale adapter that resolves nearby viewers and calls
  `ParticleUtil` with world position, yaw-only rotation, scale, and max duration.
- `AvatarFlightLaunchVfxService`: coordinates current input, controller output, stored origin, and
  emission decisions.

`AvatarFlightMovementSystem` should own one service instance and make one small delegation after
`AvatarFlightController.update(...)`. Do not put cadence, asset selection, viewer resolution, or
particle packet code directly into the movement system.

### Runtime state

Store launch VFX state on `AvatarFlightComponent`, alongside its existing animation presentation
state:

- whether a valid launch VFX origin has been captured;
- launch VFX origin X/Y/Z;
- captured yaw;
- next charge-pulse timestamp.

Update the stored origin to the current transform footpoint on each grounded charging tick. This
lets the cue follow limited grounded movement. Once the avatar leaves the ground, stop charge pulses
but preserve the last grounded origin. A later release uses that stored origin, so the ground ring
does not appear in midair.

No separate persistent effect handle is required. Clear the stored origin and next-pulse time after
release, cancel, AvatarFlight disable, or launch-state reset.

### Trigger timeline

1. **Charge begins**
   - Condition: launch is enabled, VFX is enabled, input is charging, and input reports grounded.
   - Capture/update origin and yaw.
   - Allow an immediate faint pulse for responsive feedback.

2. **Charge continues**
   - Progress for VFX cadence is `clamp(heldMs / Launch.MaxChargeMs, 0, 1)`.
   - Interval is linear interpolation from `600ms` to `150ms` by default.
   - Scale is linear interpolation from `0.85` to `1.50` by default.
   - Schedule from the actual emission time; never emit more than one pulse per server tick.

3. **Charge leaves the ground while still held**
   - Stop new charge pulses.
   - Keep the last grounded origin because current launch behavior can still accept that release.

4. **Release is consumed**
   - The movement input mapper already exposes the consumed hold duration to the controller input.
   - If `output.launchApplied()` is true, select and emit one release tier.
   - If a release was consumed but no launch applied, emit the small cancel/fizzle effect. This
     covers releases below minimum as well as rejected releases such as insufficient Vigour.
   - A zero-millisecond press may omit the fizzle; it is too short to require visual feedback.

5. **Release tier selection**
   - Use `AvatarFlightLaunchCurve.charge(...)`, not raw hold/max progress.
   - Partial: charge `< 0.45`.
   - Mid: charge `>= 0.45` and `< 0.80`.
   - Full: charge `>= 0.80`.

6. **Cleanup**
   - Clear origin and pulse timing immediately after any release outcome.
   - Short asset lifespan plus packet max duration handles visual cleanup client-side.

## Performance Budget

Default target per charging avatar:

- maximum pulse frequency: about `6.67` systems/second at full charge;
- target particles per pulse: no more than `7`;
- target generated particles: no more than about `47/second`;
- pulse particle life: no more than `0.55s`;
- expected pulse concurrency: below about `28` particles;
- full release: below `30` particles with no particle living longer than `1.15s`.

Use `IsImportant: false`, conservative bounding radii, and no per-tick allocations in cadence math.
Viewer collection must use Hytale's thread-local spatial result list. Do not scan
`Universe.getPlayers()`.

## Error Handling

- Missing config or particle assets never affect launch movement.
- Warn once per missing system ID and config ID, then skip that effect.
- Do not log per pulse unless AvatarFlight debug logging is enabled.
- Invalid scale, duration, interval, or threshold values clamp to safe defaults.
- If no stored grounded origin exists on release, fall back to the current transform footpoint.
- If the transform or spatial player resource is missing, skip VFX for that tick.

## Tests

### Pure math

- charge progress clamps at `0` and `1`;
- cadence interpolates from early to full values;
- scale interpolates from minimum to maximum;
- invalid intervals/scales fall back safely;
- tier boundaries are exact at `0.45` and `0.80`;
- release tier uses `AvatarFlightLaunchCurve.charge`, including the configured exponent.

### Service behavior

- first grounded charge tick emits once and schedules the next pulse;
- pulses do not repeat before the scheduled time;
- full-charge hold stays capped at the minimum interval and maximum scale;
- airborne hold emits no new pulse but preserves the stored origin;
- below-minimum release emits one cancel effect;
- successful release emits exactly one selected tier;
- rejected valid release emits one cancel/fizzle effect;
- release uses the stored ground origin;
- disable/reset clears launch VFX state;
- missing assets warn once and never block movement;
- owner and nearby observers are both recipients.

### Config and assets

- omitted `Vfx` inherits fully;
- explicit `Vfx` inherits missing nested fields and overrides explicit fields;
- default particle-system IDs resolve;
- every system `SpawnerId` resolves to one authored spawner;
- every texture path resolves in the release assets;
- each JSON asset validates as `ParticleSystem` or `ParticleSpawner` through Hytale Workshop;
- run the standard architecture/thread-safety guards and `./mvnw test` after implementation.

## In-Game Validation Matrix

1. Open `/particle spawn` and preview all five systems independently.
2. Hold launch for less than the minimum, then release: one tiny fizzle only.
3. Release at the minimum: partial ring/column/dust.
4. Release around mid charge: mid composition.
5. Release at full charge: two clean rings, stronger column, bounded dust.
6. Hold beyond full charge: cadence and intensity remain capped with no runaway concurrency.
7. Leave the ground while holding, then release: no midair charge pulses and release remains at the
   last grounded origin.
8. Attempt a launch without enough Vigour: movement does not launch and only the fizzle plays.
9. Observe as both owner and a second nearby player.
10. Test a small vanilla flyer and a large dragon profile; tune config scale rather than duplicating
    Java behavior.
11. Disable each launch VFX toggle and the top-level VFX toggle.
12. Reload relevant assets/config and confirm missing/removed assets degrade gracefully.

## Implementation Order

1. Extract `TwAvatarFlightConfig` codec/inheritance responsibility enough to reduce the class below
   the refactor threshold.
2. Add `AvatarFlightVfxSettings`, codec docs, inheritance, defaults, and config tests.
3. Author and validate the eight spawners.
4. Compose and preview the five particle systems with `/particle spawn`.
5. Add pure launch VFX math and tests.
6. Add the emitter and service, then delegate from `AvatarFlightMovementSystem`.
7. Add component state and service tests.
8. Run full tests and the in-game validation matrix.

## Evidence Summary

- Indexed engine/API source: Hytale Workshop release `0.5.6`.
- Runtime packet path: `ParticleUtil` -> `SpawnParticleSystem`.
- Model-bound alternative reviewed: `ModelParticle`, `SpawnModelParticles`, and vanilla
  `ActionSpawnParticles`.
- Vanilla asset baselines inspected from the installed release package:
  - `_Test/Sticks/Stick_Slam_Ground_Small.particlesystem`;
  - `_Test/Sticks/Spawners/Stick_Slam_Shockwave_Small.particlespawner`;
  - `Combat/Sword/Charged/Sword_Charging.particlesystem`;
  - `Combat/Sword/Charged/Spawners/Sword_Charging_Circles.particlespawner`;
  - `Combat/Sword/Charged/Spawners/Sword_Charging_Sparks.particlespawner`.
- Downstream composition reference inspected, but not depended on:
  `HyDragon/Server/Particles/NPC/RockDrake/RockDrake_Stomp_Ground_Hit.particlesystem`.

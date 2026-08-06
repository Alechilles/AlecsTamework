# Flying Waypoint Preflight Design

## Goal

Make autonomous `TameworkFlyingOrbit` movement consistently avoid choosing blocked stable waypoints while preserving its existing continuous local obstacle steering.

## Scope

- Generalize the existing wander-destination clearance selection into shared waypoint-preflight behavior.
- Keep `WANDER_TARGET` on the shared behavior without changing its observable selection policy.
- Add waypoint preflight to `PASS_THROUGH_TARGET` so an aerial pass chooses a clear viable lane before committing to its captured destination.
- Leave `APPROACH`, `ORBIT`, `CYCLE`, and `FACE_TARGET` without waypoint sampling. These modes do not own a stable waypoint and continue to use the existing live obstacle fan.
- Preserve the existing rule that obstacle avoidance is disabled while rider-controlled or when `AvoidObstacles` is false.

## Design

The body motion will use one clearance-based selector for stable waypoint candidates. Candidate evaluation remains bounded by `FlyingObstacleAvoidance.MAX_PROBES_PER_UPDATE` through `probeWaypoint`.

Wander behavior will continue generating up to three random points around the target. It selects the first fully clear route, otherwise the route with the greatest partial clearance.

Pass-through behavior will generate a small deterministic set of valid destinations beyond the captured target: the direct attack lane first, followed by alternate lanes offset laterally and/or vertically from that lane. It will select the first fully clear route, otherwise the route with the greatest partial clearance. The selected destination remains captured for the duration of the pass, and the existing continuous avoidance fan may still adjust steering en route.

The pass-through attack must still travel beyond the target rather than stopping beside or in front of it. Alternate candidates therefore preserve the configured pass-through distance and stay close enough to the direct lane to retain the strafing-run behavior.

## Failure and Fallback Behavior

- If avoidance is unavailable, pass-through uses the existing direct destination.
- If no candidate is fully clear, use the candidate with the greatest measured partial clearance.
- If candidate probing cannot produce a selection, fall back to the direct destination.
- No new configuration fields or builder IDs are introduced.

## Verification

Add focused production-behavior tests proving that pass-through candidate selection prefers a clear alternate lane and falls back to the greatest partial clearance. Retain the existing destination geometry tests and run the Tamework Java test task.

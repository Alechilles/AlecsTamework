---
title: "Flight Formation Guide"
order: 12
published: true
draft: false
---
# Flight Formation Guide

Parent: [System Integration](/mod/alecs-tamework/system-integration)

`Component_Tamework_Instruction_Flight_Formation` lets autonomous flying NPCs travel in a formation around their native flock leader. It uses the existing flock and does not create one or command birds to take off.

Use `TameworkFormationFly` as the template's flight controller builder, retaining its existing speed, altitude and turn settings, and pass `"Formation": { "Compute": "FlightFormation" }`. This thin adapter keeps the native controller's `Fly` identity, so existing takeoff, landing and `MotionController: Fly` sensors still work. It provides the exact speed scale at the current pitch, which differs from the controller's maximum climb speed. With formations disabled it preserves native settings; with them enabled it lowers `MinAirSpeed` to at most `0.1` so birds can accelerate and slow down into their positions. It also caps the native flight turn rate at 90 degrees/second, retaining any lower configured limit. This cap applies throughout flight, including landing and recovery; native obstacle prediction uses the same turn radius. Cruise instructions should request less than full speed to leave catch-up room.

## Role parameters

| Parameter | Default | Meaning |
| --- | --- | --- |
| `FlightFormation` | `None` | `None` disables formation, `Loose` uses a broad evolving group, `Cluster` uses a compact evolving volume, `Boid` uses local neighbor steering, and `Chevron` places followers along two trailing arms. Values are case-sensitive. |
| `FlightFormationSpacing` | `3` | Position spacing in blocks; must be positive. For a chevron, each row advances this distance both sideways and behind the leader. |
| `FlightFormationTightness` | `0.6` | Position correction strength, greater than zero and at most one. Lower values allow more drift. |

Expose these parameters on the consuming template, then pass them to the shared instruction:

```json
{
  "Reference": "Component_Tamework_Instruction_Flight_Formation",
  "Modify": {
    "FlightFormation": { "Compute": "FlightFormation" },
    "FlightFormationSpacing": { "Compute": "FlightFormationSpacing" },
    "FlightFormationTightness": { "Compute": "FlightFormationTightness" }
  }
}
```

A species role can then set `"FlightFormation": "Chevron"` or `"FlightFormation": "Loose"` in its `Modify` map. Leave `None` as the template default to preserve existing species behavior.

## Placement and behavior

Place the instruction in ambient airborne travel, after escape, landing and obstacle recovery, and before ordinary flock catch-up or wandering. Keep the existing wander instruction afterward. A chevron's outer slots can extend beyond a normal flock leash radius, so legacy catch-up movement must not override formation steering.

`TameworkFlightFormationReady` accepts the same `Formation` selector and returns false for `None`. It checks that the follower uses `TameworkFormationFly`, that it and its current NPC leader are airborne under Fly controllers, and that neither is rider-controlled. Missing or dissolved flocks and the leader itself do not select the instruction. The sensor is necessary because a body motion returning false does not select the next instruction automatically.

Formation positions turn with a smoothed leader travel heading. Leader-relative offsets also ease toward their new positions at a bounded speed, softening wide formation turns and membership changes without delaying straight-line travel. Followers match the leader's measured velocity and apply bounded position correction. Active followers reserve flexible slots. Beneficial pair exchanges let mixed-up animals trade positions, with a cooldown to avoid shuffling; native membership order no longer assigns their positions. See [flexible slot assignment](Companion-Follow-Formation.md#flexible-slot-assignment) for thresholds and bounded runtime cost. Loose groups spread horizontally with continuous height variation and slow independent slot motion. Cluster fills a more compact three-dimensional volume. Chevron remains level with the leader. Existing NPC separation remains active.

Followers also match 85% of their smoothed slot's movement relative to the leader. This adds turn movement before a large position error develops, while leaving a little lag for normal position correction. Straight-line cruising is unchanged. This is follower steering, not a rigid group transform: the leader still chooses its own course, and speed limits or obstacles can stretch the formation.

The body motion `TameworkFlightFormation` accepts `Formation`, `Spacing`, `Tightness`, and `RelativeSpeed`. `RelativeSpeed` defaults to `0.8` and caps the additional position correction as a fraction of the follower's speed limit at its current pitch; total requested speed, including slot movement, is capped at that limit. It is not a speed multiplier for the leader. A follower cannot catch a leader that is already moving at the follower's maximum speed.

Obstacle probes use Tamework's existing autonomous flight avoidance. Formation is a preferred position, not a guarantee of exact spacing around terrain. Keep normal landing and command behavior outside this instruction. AH uses it for wild cruising and tamed airborne idle. Wild followers also retain formation slots while the leader descends; the existing touchdown landing instruction takes priority after the leader lands. This avoids switching the airborne group to shared-point catch-up during descent.

## Runtime and verification

Movement runs in the current NPC steering callback on the owning world. Slot formations use a store-scoped assignment pool containing IDs and coordinate snapshots. Native member-list lookup is only a fallback when identity is unavailable. Cluster and Loose use constant-cost target math with no neighbor query or global entity scan; Boid has its separate query cost described below. Position and heading samples belong to the body motion and reset when it activates, deactivates or loses/replaces its leader.

Automated tests cover formation layout, heading reversal, speed correction and leader eligibility. Actual flight appearance, flock turns, terrain avoidance and coordinated landing still need in-game verification and species tuning.

## Experimental Boid formation

Set `FlightFormation` to `Boid` to replace assigned slots with local separation, heading alignment, and cohesion while retaining the native leader's wandering route. `FlightFormationSpacing` controls preferred separation (default 3 blocks); neighbors are sampled within four times that distance. `FlightFormationTightness` controls correction strength.

Each follower uses the nearest 12 eligible airborne members of its own flock. Neighbor sampling runs every 0.2 seconds with a staggered refresh phase, and steering is smoothed each tick. Sampling uses the engine spatial index, but still examines all candidates returned within the radius; the 12-neighbor limit does not guarantee constant query cost in dense groups. All entity reads stay on the world thread, and temporary neighbor references are cleared after sampling. Existing obstacle avoidance remains active. No performance improvement over slot formations is claimed.

Animal Husbandry includes `Pigeon_Boid`, a copy of the pigeon role referencing the aerial template directly, that changes the formation and restricts flock membership to `Pigeon_Boid` for side-by-side testing. It is not added to natural spawn tables. Taming still changes it into the ordinary tamed pigeon role.

## Evolving Cluster and Loose layouts

Cluster separates its angular and height sequences so large flocks occupy a volume instead of tracing a folded surface. Loose removes regular height bands and perturbs its spiral layout. Both use bounded, slow slot-specific deformation driven by a shared monotonic clock. A slot therefore keeps its motion when birds exchange it or join at different times. No per-bird neighbor search is involved.

Accepted Cluster/Loose slot trades start a smooth approach from the follower's current location. The shared allocator still requires at least 20% less total travel and a saving of at least half the spacing (minimum 0.5 blocks), with a two-second cooldown per bird. It checks at most 128 candidate pairs per pool every 0.5 seconds. Round-robin pairing spreads those checks across members instead of exhausting the first member's partners first. Large flocks are sampled over time; finding the best possible swap is not guaranteed.

Occupied-slot lookup avoids repeated member scans when a large flock first joins. Steady movement remains constant work per follower, plus the bounded pair checks and periodic member-expiry maintenance. Chevron and Boid retain their existing placement algorithms.

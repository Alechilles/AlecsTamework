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
| `FlightFormation` | `None` | `None` disables formation, `Loose` uses a compact group with slow position drift, and `Chevron` places followers along two trailing arms. Values are case-sensitive. |
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

Formation positions turn with a smoothed leader travel heading. Leader-relative offsets also ease toward their new positions at a bounded speed, softening wide formation turns and membership changes without delaying straight-line travel. Followers match the leader's measured velocity and apply bounded position correction. Membership order determines follower positions; joins, departures, or a replacement leader may rearrange them. Loose groups spread in two horizontal dimensions, alternate modest heights above and below the leader, and add small, slow drift. At spacing 3, the six repeating height levels range from 0.6 to 1.2 blocks above or below the leader, with up to 0.24 blocks of vertical drift. Chevron remains level with the leader. Existing NPC separation remains active.

Followers also match 85% of their smoothed slot's movement relative to the leader. This adds turn movement before a large position error develops, while leaving a little lag for normal position correction. Straight-line cruising is unchanged. This is follower steering, not a rigid group transform: the leader still chooses its own course, and speed limits or obstacles can stretch the formation.

The body motion `TameworkFlightFormation` accepts `Formation`, `Spacing`, `Tightness`, and `RelativeSpeed`. `RelativeSpeed` defaults to `0.8` and caps the additional position correction as a fraction of the follower's speed limit at its current pitch; total requested speed, including slot movement, is capped at that limit. It is not a speed multiplier for the leader. A follower cannot catch a leader that is already moving at the follower's maximum speed.

Obstacle probes use Tamework's existing autonomous flight avoidance. Formation is a preferred position, not a guarantee of exact spacing around terrain. Keep normal landing and command behavior outside this instruction. AH uses it for wild cruising and tamed airborne idle; wild landing intent switches back to the existing descent and landing behavior.

## Runtime and verification

Movement runs in the current NPC steering callback on the owning world. Each active follower reads its native flock's member list to find its position. No global entity scan, background task, persistence store or shared cache is added. Position and heading samples belong to the body motion and reset when it activates, deactivates or loses/replaces its leader.

Automated tests cover formation layout, heading reversal, speed correction and leader eligibility. Actual flight appearance, flock turns, terrain avoidance and coordinated landing still need in-game verification and species tuning.

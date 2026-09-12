# Ground formation

For tamed animals following a player, see [Companion follow formation](Companion-Follow-Formation.md).

`TameworkGroundFormation` is a body motion for native walking flocks.
It reuses the bird formation's loose horizontal layout, giving each member a
separate slot behind and beside its native flock leader. The native Walk
controller handles terrain height and collision.

```json
{
  "Type": "TameworkGroundFormation",
  "Spacing": 5,
  "Tightness": 0.35,
  "RelativeSpeed": 0.25
}
```

`Spacing` must be positive. `Tightness` and `RelativeSpeed` must be greater
than zero and at most one. Relative speed limits additional catch-up speed;
the follower also matches its leader's movement.

Select this motion for walking animals during the herd travel phase. It does not choose destinations or control travel/rest timing. Animal
Husbandry uses native timers and a short-lived flock beacon for those phases;
threat responses remain higher priority.

Set `"Lead": true` for the native leader. It holds its starting heading until
this motion deactivates, using `RelativeSpeed` as its walking speed. Followers
allow slot error up to 30% of spacing and limit lateral corrections to 20
degrees while their leader moves. Steering turns at up to 45 degrees per second.

Both modes check at most three short (3 block) terrain probes every quarter
second. A direction must clear nearly the full probe distance; short movement
ending at a wall or edge triggers a detour instead of being treated as clear.
Obstacle turns align within two degrees before walking to avoid cutting corners.
When blocked, they try 15- and 30-degree detours before larger recovery turns,
restarting with shallow turns after the route clears. Successful detours are held
for two seconds, then kept if the original route is still blocked. Lack of physical progress also triggers
recovery, even when terrain probes report a clear route. Large turns happen in
place. This is local steering, not long-distance pathfinding around fences or
mountains. No world scan, scheduler, or saved herd state is added.

Leaders can optionally set `HomeRange` (blocks, default `0` disables it). When
starting a journey at or beyond that horizontal distance from their native leash
point, they choose a heading toward home. That heading remains fixed for the
journey, with the same local obstacle recovery. Keep the leader's leash point
anchored; resetting it after travel would move the home range. This uses one
component read at journey start and introduces no new saved state or scans.
Animal Husbandry starts inward journeys at 200 blocks and retains its native
300-block return-home fallback. Herd rest uses short native wander steps so
animals graze where they stopped instead of seeking their original leash point.
Existing herds use their current leash as home; this does not recover an older
spawn location or impose strict biome boundaries.

`SlotTolerance` sets allowed drift in blocks before slot correction begins.
Its default, zero, uses 30% of `Spacing`. Animal Husbandry uses `Tightness: 0.15`
and `SlotTolerance: 2.5` for relaxed ground herds. Followers filter leader
velocity with a 1.5-second response time before using it for slot orientation
and forward movement. Brief dodges have less influence than sustained turns.
This filtering applies only to ground formation.


## Flexible slots

Active herd followers can exchange slots when doing so materially reduces travel.
Assignments have a cooldown and comparisons run at a bounded half-second cadence.
The herd's shape and leader movement are unchanged. See
[flexible slot assignment](Companion-Follow-Formation.md#flexible-slot-assignment).

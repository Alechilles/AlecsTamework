# Ground formation

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

Both modes check at most three short (1.5 block) terrain probes every quarter
second. When blocked, they try local detours and hold a clear detour for two
seconds before returning toward travel. Lack of physical progress also triggers
recovery, even when terrain probes report a clear route. Large turns happen in
place. This is local steering, not long-distance pathfinding around fences or
mountains. No world scan, scheduler, or saved herd state is added.

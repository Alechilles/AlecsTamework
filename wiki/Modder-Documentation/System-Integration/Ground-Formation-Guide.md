# Ground formation

`TameworkGroundFormation` is a follower body motion for native walking flocks.
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

Select this motion only for a walking follower during its leader's travel
phase. It does not choose destinations or control travel/rest timing. Animal
Husbandry uses native timers and a short-lived flock beacon for those phases;
threat responses remain higher priority.

Ground probes cover only a short distance ahead, at a limited cadence. A
blocked follower can briefly relax its slot toward the leader, or stop when
neither direction is walkable. This is local steering, not long-distance
pathfinding around fences or mountains. No world scan, scheduler, or saved
herd state is added.

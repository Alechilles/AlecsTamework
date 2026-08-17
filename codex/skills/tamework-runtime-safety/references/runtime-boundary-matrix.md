# Runtime Boundary Matrix

| Work | Allowed location | Data crossing boundary |
| --- | --- | --- |
| Entity/component read | Current world thread | None |
| ECS mutation in system | System callback through `CommandBuffer` | None |
| Deferred world mutation | `world.execute(...)` | Stable IDs and immutable values |
| HUD or player effect | Current player/world context | Stable IDs resolved at execution |
| Pure calculation | Async only when useful | Immutable snapshot or primitives |
| Durable I/O | Owned bounded executor | Immutable request and explicit result |
| Retry/backoff | Owned scheduler | Stable operation identity, not live refs |
| Cache | Owning service | Stable key and immutable value |

## Performance Review

Record:

- trigger and cadence;
- entities or players scanned per pass;
- bounded batch size and continuation state;
- dirty/event signal that can replace polling;
- allocations and parsing in the hot section;
- idle, active, failure, and recovery backoff;
- cache invalidation and maximum size;
- shutdown and world-unload cleanup.

## Stop Conditions

Reject a proposed optimization when it:

- caches a live `Player` or component across ticks or threads;
- calls `PlayerRef.getComponent(Player)` from a tick or async path;
- scans `Universe.getPlayers()` to remap live runtime state;
- mutates a store directly from a runtime `*System` class;
- moves HUD, world, entity, or component access to a common-pool future;
- reduces cadence without measuring the work it multiplies;
- creates scheduled work with no cancellation or shutdown owner.

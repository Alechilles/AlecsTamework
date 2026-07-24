# ADR 0002: Canonical Companion Lifecycle Vocabulary

- Status: Accepted and implemented
- Date: 2026-07-23

## Decision

The canonical lifecycle is the sole durable authority for a companion's owner,
owner-world key, lifecycle state, location, revision, and active operation
fence.

Canonical states and their only valid location kinds are:

| State | Location |
| --- | --- |
| `ACTIVE` | `LIVE_ENTITY` |
| `UNLOADED` | `NONE` |
| `CAPTURED` | `CAPTURE_ITEM` |
| `COOP` | `COOP_SLOT` |
| `DEAD_REVIVABLE` | `NONE` |
| `LOST` | `NONE` |
| `RELEASED` | `NONE` |
| `UNRESOLVED` | `UNRESOLVED` |

`RESTORING`, `STORING`, and other in-progress states are operation phases, not lifecycle states.
Quarantine is control-plane state attached to the affected scope; it does not replace otherwise
valid lifecycle truth.

Locations carry these fields:

- `LIVE_ENTITY` requires both a stable entity locator and world key.
- `CAPTURE_ITEM` and `COOP_SLOT` require one normalized key.
- `NONE` and `UNRESOLVED` carry no key.

`ownerWorldKey` is independent of physical location. It preserves the
normalized per-world bucket for an owned profile across capture, coop, death,
lost, and unload transitions. An unowned profile cannot carry the field. It is
lifecycle evidence used to keep released per-world live-cap decisions aligned;
it is not a durable count, reservation, population-group membership, or
claim-cap authority. The released owner cap counts currently loaded owned NPCs
through its process-local live index.

Revisions are non-negative and begin at zero. Every transition compares an expected revision and
advances it exactly once.

## Consequences

- A profile can answer “where is it?” from one row.
- Feature detail explains a lifecycle state but cannot independently declare it.
- Owner-world evidence remains attached to canonical lifecycle without creating
  a separate owner-population subsystem.
- Invalid state/location combinations fail before reaching an adapter and are also constrained by
  schema checks.
- Scoped quarantine preserves the last readable lifecycle rather than introducing a competing
  `QUARANTINED` lifecycle.

# ADR 0002: Canonical Companion Lifecycle Vocabulary

- Status: Accepted
- Date: 2026-07-23

## Decision

`companion_lifecycle` is the sole durable authority for a companion's owner, owner-world capacity
bucket, lifecycle state, location, revision, and active operation fence.

Canonical states and their only valid location kinds are:

| State | Location |
| --- | --- |
| `ACTIVE` | `LIVE_ENTITY` |
| `UNLOADED` | `NONE` |
| `CAPTURED` | `CAPTURE_ITEM` |
| `COOP` | `COOP_SLOT` |
| `DEAD_REVIVABLE` | `NONE` |
| `LOST` | `NONE` |
| `ROSTER_STORED` | `COMMAND_ROSTER` |
| `PROVISIONED_DORMANT` | `PROVISIONING` |
| `RELEASED` | `NONE` |
| `UNRESOLVED` | `UNRESOLVED` |

`RESTORING`, `STORING`, and other in-progress states are operation phases, not lifecycle states.
Quarantine is control-plane state attached to the affected scope; it does not replace otherwise
valid lifecycle truth.

Locations carry these fields:

- `LIVE_ENTITY` requires both a stable entity locator and world key.
- `CAPTURE_ITEM`, `COOP_SLOT`, `COMMAND_ROSTER`, and `PROVISIONING` require one normalized key.
- `NONE` and `UNRESOLVED` carry no key.

`ownerWorldKey` is independent of physical location. Owned profiles retain this authoritative
per-world capacity bucket while captured, cooped, dead, lost, roster-stored, or provisioned.
An admitted rehome changes it through the same lifecycle revision path. Unowned profiles cannot
carry an owner-world bucket.

Revisions are non-negative and begin at zero. Every transition compares an expected revision and
advances it exactly once.

## Consequences

- A profile can answer “where is it?” from one row.
- Feature detail explains a lifecycle state but cannot independently declare it.
- Invalid state/location combinations fail before reaching an adapter and are also constrained by
  schema checks.
- Scoped quarantine preserves the last readable lifecycle rather than introducing a competing
  `QUARANTINED` lifecycle.

---
title: "Diagnostics API Reference"
order: 11
published: true
draft: false
---
# Diagnostics API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **API `3.0.0`**
> The view keeps its 2.x accessor names and adds four fields for the companion
> file store. `getPersistenceResilience()`,
> `queryPersistenceAvailability(...)`, and `findPersistenceIncident(...)` were
> removed with the `PERSISTENCE_RESILIENCE` capability.

Capability: `DIAGNOSTICS`

`TameworkApi.diagnostics()` exposes a read-only diagnostic view for
integrations and support tools.

Diagnostics change nothing. They read memory only, so they are safe from any
thread. Keep them off hot tick paths.

## `getPersistenceDiagnostics()`

Returns `PersistenceDiagnosticsView`.

### Fields kept from 2.x

The names are unchanged because tools read them by reflection. Their meaning
on the file store is:

| Field | Meaning in 3.0.0 |
| --- | --- |
| `databasePath` | The companion folder. |
| `totalBytes` | The folder's size. It is measured off the world thread and cached, so it can be up to about 30 seconds old. |
| `sqliteBytes`, `walBytes`, `shmBytes` | Always `0`. There is no SQLite file. |
| `queueMetrics.queueDepth` | Owner files waiting to be written plus snapshots waiting to be written. |
| `queueMetrics.lastFailureReason` | The writer's current failure, or null. |
| Other `queueMetrics` fields | Always `0`. |
| `health.status` | `HEALTHY` while writes succeed, `DEGRADED` while the writer has a failure. |
| `health.reason` | The writer's current failure, or null. |
| `health.lastFailureAtMs` | Always `0`. |

### Fields added in 3.0.0

| Field | Meaning |
| --- | --- |
| `recordsByLocation` | `Map<String, Long>` of companion records per location kind: `LIVE`, `ITEM`, `COOP`, `STORED`, `DEAD`, `LOST`, `RELEASED`. A kind with no record is present with `0`. |
| `lastFlushAtMs` | Wall-clock time of the last successful write. `0` before the first one. |
| `lastFailure` | The writer's current failure, or null while writes succeed. |
| `unreadableRecords` | Records that could not be decoded when the store was loaded. |

The seven-argument constructor from 2.x still exists. It fills the new fields
with an empty map, `0`, null, and `0`.

## `getPopulationDiagnostics()`

Returns `PopulationDiagnosticsView.unavailable()` in 3.0.0. Read counts through
[Population Groups](/mod/alecs-tamework/population-groups-api-reference)
instead.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)

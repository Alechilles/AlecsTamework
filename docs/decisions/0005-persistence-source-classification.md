# ADR 0005: Persistence Source Classification

- Status: Accepted and implemented
- Date: 2026-07-23

## Decision

Classification is read-only and completes before any replacement target is created.

The exhaustive outcomes are:

| Classification | Action |
| --- | --- |
| `NO_SOURCE` | Create an empty replacement schema. |
| `PUBLIC_V2` | Import into a temporary replacement target. |
| `PUBLIC_V3` | Import into a temporary replacement target. |
| `PUBLIC_V4` | Import into a temporary replacement target. |
| `LEGACY_DAT` | Import the immutable released-data bundle into a temporary replacement target. |
| `REPLACEMENT_V1` | Verify and open the existing target. |
| `DEVELOPMENT_V5_TO_V9` | Refuse without modifying source or creating target. |
| `MALFORMED` | Refuse without writes. |
| `AMBIGUOUS` | Refuse without writes. |

Classification inspects migration rows, required tables and columns, known July-only tables,
integrity results, and a consistent source snapshot including WAL state.

The public SQLite compatibility boundary is schema v2-v4 from the June 30 release lineage. The
older released `.dat` bundle is a separately fixture-gated input and does not expand that SQLite
boundary. Schema v5-v9 is tester-only and has no importer. Testers restore a public backup or make
a new world.

The importer never mutates, renames, checkpoints, or deletes `tamework.sqlite`. It builds
`tamework-state.sqlite.importing.<id>`, verifies it, and only then atomically publishes
`tamework-state.sqlite`. Existing legacy and replacement databases are never merged automatically.

## Consequences

- There is one new schema lineage rather than a tenth migration layered onto July internals.
- Refusal is safe and actionable instead of a best-effort destructive conversion.
- Source and target hashes make import restartable and auditable.
- The old database remains available for downgrade or manual recovery.

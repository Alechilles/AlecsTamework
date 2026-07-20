# Persistence Resilience Performance Budgets

These budgets are release gates for the schema-v7 persistence resilience system. They protect the
world thread from diagnostic, telemetry, and recovery work while keeping scoped admission independent
of the total number of unrelated incidents.

## Automated numeric gates

`PersistenceResiliencePerformanceGateTest` runs on the same Java runtime as the main Maven suite:

- 250,000 healthy mutation-availability decisions with 10,000 unrelated active scope fences must
  complete within 4,000 ms after warmup. This is a deliberately loose cross-machine ceiling of 16
  microseconds per decision; the lookup contract remains exact and process-local.
- Reloading and publishing 10,000 durable active quarantines during resilience-runtime construction
  must complete within 5,000 ms. The fixture setup itself is outside the measured interval.

The test prints actual elapsed values into its Surefire report. A release evidence run records the
suite result from the exact source commit.

## Structural bounds

- Persistence writes are grouped into at most 256 tasks per batch. SQLite contention retries at most
  three times with bounded 20 ms incremental backoff.
- Global and scoped recovery use exponential backoff capped at 300 seconds and never poll per tick.
- The local incident journal has a 1,024-event bounded queue, rotates at 10 MiB, retains at most five
  files, and counts dropped diagnostics instead of blocking canonical work.
- A support bundle spends at most 10 seconds collecting evidence and contains at most 4 MiB of
  uncompressed evidence members plus its reserved manifest. Timeouts produce a valid partial bundle.
- Availability reads use immutable/in-memory coverage state, an exact concurrent scope map, and the
  locally persisted circuit view. No telemetry or diagnostic file/network I/O occurs in admission.
- Hosted persistence correlation columns and lookup routes are index-backed and covered by the
  telemetry platform migration/repository tests.

## Live rehearsal budgets

The exact candidate's copied-world rehearsal records both the existing-world baseline and schema-v7
candidate values. The candidate must satisfy all of the following:

- no player-visible login warm-up or time-based admission delay;
- no unsolicited login recall;
- persistence initialization adds no more than 2 seconds or 20 percent, whichever is greater, to the
  same copied world's baseline server-ready time;
- with 1,000 linked profiles and 100 managed coops, persistence resilience adds no more than 0.25 ms
  to steady-state world-tick p95 compared with the same copied baseline;
- diagnostic export and telemetry upload remain off the world thread and do not create a tick over
  the server's existing long-tick threshold.

The live numbers are recorded in the rehearsal evidence rather than asserted by wall-clock unit tests.
Failure requires investigation or an explicit design review; it is not waived by increasing a unit-test
timeout.

## Backup boundary

Performance collection never copies or archives a Hytale world. Hytale/server operators own whole-save
backups. Tamework creates only its verified SQLite snapshot when an actual pre-v7 database migration
requires one.

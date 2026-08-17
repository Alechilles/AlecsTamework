---
name: tamework-runtime-safety
description: Use when changing a Tamework system, tick callback, ECS component access or write, world-thread dispatch, async callback, delayed task, executor, scan, cache, cadence, backoff, retry, HUD refresh loop, diagnostics loop, allocation-heavy hot path, or shutdown lifecycle. Also use for runtime lag fixes that propose moving game state off-thread.
---

# Tamework Runtime Safety

Improve runtime cost without moving thread-affine state across its ownership
boundary.

## Classify the Work

1. Read `references/runtime-boundary-matrix.md`.
2. Read `docs/agents/guardrails.md` for current executable checks.
3. Map the path from scheduler or event to world/store access, pure
   calculation, ECS writes, UI/effects, retries, and shutdown.
4. Confirm the loaded artifact with
   `docs/agents/runtime-vs-source-checklist.md` when the report is from a live
   server.
5. Measure or identify the actual expensive stage before changing cadence or
   thread placement.

## Preserve Runtime Boundaries

- Resolve `Player`, entities, components, and live capabilities from the
  current world/store on the world thread. Cache stable IDs or immutable view
  data, not live component instances.
- In system callbacks, write through `CommandBuffer`. If none is available,
  queue work to `world.execute(...)`, pass stable IDs, and resolve live refs in
  that callback.
- Async work may perform bounded I/O or pure computation on immutable data. It
  must not read or mutate live entities, components, stores, players, HUDs, or
  worlds.
- Treat client events and cached snapshots as requests or presentation data,
  not current authority.
- Every delayed task, executor, cache, retry, and listener needs an ownership
  scope, cancellation or closure path, and bounded failure behavior.

## Optimize Deliberately

- Prefer dirty signals, indexes, bounded batches, cache keys, and adaptive
  backoff over frequent global scans.
- Separate discovery cadence, target-refresh cadence, and presentation cadence.
  Do not reduce all intervals to improve one visible response.
- Avoid streams, reflection, repeated parsing, collection copies, and object
  construction in tick paths when a simple loop or cached immutable value is
  clear.
- Keep logs out of per-tick paths unless debug-gated and throttled.
- Do not expand architecture-guard allowlists to make a shortcut pass.

## Route Related Work

- Use the owning domain skill as well: command runtime, progression, avatar
  flight, persistence, or API evolution.
- Use `$tamework-test-authoring` only for a behavior regression or a documented
  high-impact static safety invariant that cannot be exercised reliably.

## Verify

1. Run the exact grep and architecture tests from `docs/agents/guardrails.md`.
2. Run focused behavior and cadence tests, then
   `bash ../gradlew -p .. :alecstamework:test`.
3. Compare allocations, scan count, batch size, or elapsed time using the same
   workload when performance is the goal.
4. Launch a live server only when live verification is needed. Check the port,
   record the exact process tree, and stop only the process tree started for
   the check.
5. Report thread ownership, IDs carried across boundaries, write mechanism,
   cadence/backoff, shutdown path, guard results, and performance evidence.

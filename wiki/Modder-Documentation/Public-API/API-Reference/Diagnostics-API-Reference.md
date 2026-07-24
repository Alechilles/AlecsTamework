---
title: "Diagnostics API Reference"
order: 11
published: true
draft: false
---
# Diagnostics API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Capability: `DIAGNOSTICS`

`TameworkApi.diagnostics()` exposes the retained read-only diagnostic view for
integrations and support tooling.

Diagnostics do not mutate profiles, reserve population capacity, retry
operations, clear failures, or replace normal feature results. Use them off hot
tick paths and treat unavailable fields as unavailable rather than as zero.

`getPersistenceDiagnostics()` returns `PersistenceDiagnosticsView`:

- `databasePath`, `sqliteBytes`, `walBytes`, `shmBytes`, and `totalBytes`;
- bounded writer metrics under `queueMetrics`; and
- `health.status`, `health.reason`, and `health.lastFailureAtMs`.

Health status is one of `HEALTHY`, `READ_ONLY`, `STARTING`, `DRAINING`, or
`CLOSED`. Queue fields are a compatibility-shaped summary of the replacement
writer, not access to an internal work queue.

This API view is intentionally smaller than the operator-only `/tw debugdb
detail` output.

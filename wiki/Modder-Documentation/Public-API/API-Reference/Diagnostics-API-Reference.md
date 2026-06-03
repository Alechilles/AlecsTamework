---
title: "Diagnostics API Reference"
order: 12
published: true
draft: false
---
# Diagnostics API Reference

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

> **Experimental API Contract (`0.6.0`)**
> This reference tracks the current `diagnostics()` contract in `TameworkApi`.

Capability: `DIAGNOSTICS`

## Entry Point
`TameworkApi.diagnostics() -> DiagnosticsApi`

## Methods
- `PersistenceDiagnosticsView getPersistenceDiagnostics()`

## `PersistenceDiagnosticsView`
- `databasePath`
- `sqliteBytes`
- `walBytes`
- `shmBytes`
- `totalBytes`
- `queueMetrics`
- `health`

`queueMetrics` fields:
- `queueDepth`
- `lastBatchSize`
- `maxBatchSize`
- `batchesProcessed`
- `operationsProcessed`
- `retryAttempts`
- `failedBatches`
- `averageBatchSize`
- `averageWriteMs`
- `lastBatchWriteMs`
- `lastFailureReason`
- `lastFailureAtMs`

`health` fields:
- `status`
- `reason`
- `lastFailureAtMs`

## Notes
- Intended for tooling/admin diagnostics, not gameplay rules.
- Snapshot values are point-in-time and may change rapidly while writes are active.

## Related Pages
- [Public API Overview](/mod/alecs-tamework/public-api-overview)
- [In-Game API Self-Test Smoke Recipe](/mod/alecs-tamework/in-game-api-self-test-smoke-recipe)



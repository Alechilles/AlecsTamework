# ADR 0003: Shared Persistence Operation Protocol

- Status: Accepted and implemented
- Date: 2026-07-23

## Decision

Every persistence-affecting use case uses one operation envelope and the same phase graph:

```text
PREPARED -> LIVE_APPLYING | DURABLE | FAILED
LIVE_APPLYING -> DURABLE | RETRYABLE | COMPENSATING | UNKNOWN
DURABLE -> PUBLISHED | RETRYABLE
RETRYABLE -> LIVE_APPLYING | DURABLE | COMPENSATING | FAILED
COMPENSATING -> COMPENSATED | RETRYABLE | UNKNOWN
UNKNOWN -> DURABLE | COMPENSATING | FAILED
```

`PUBLISHED`, `COMPENSATED`, and `FAILED` are terminal. Feature-specific progress belongs in a
versioned operation payload or detail table; features cannot add shared phases.

Each envelope has one typed operation ID, registered kind, idempotency key, and complete scope
set. Reconciliation generation belongs to canonical lifecycle evidence, not the operation
envelope; generation zero remains valid historical evidence.

One accepted operation executes as one transaction. The transaction declares all participants
up front and commits canonical state, required domain detail, durable operation evidence, and
projection events together. Unrelated queue entries are never combined into a transaction.

Failures around commit have three distinct meanings:

- known rollback: retry only when the transaction definition says replay is safe;
- known commit: return committed;
- unknown outcome: run the operation's exact readback contract before retry or compensation.

No filesystem, network, ECS, inventory, or projection callback runs inside a database transaction.

## Consequences

- Profile/alias mutation, population/groups, roster/timed summoning,
  provisioning, capture, coop, positive-evidence dormancy, free/paid
  restoration, and profile-extension mutation share recovery mechanics.
- Legacy command items read canonical projections. Owner/command-family items
  use the shared roster operation family and canonical restoration machinery;
  neither owns item-local recovery infrastructure.
- Operation IDs and scope keys replace string labels and JSON context as transaction identity.
- A feature registry can prove every operation kind has recovery, containment, readiness, and
  shutdown ownership.
- Performance optimizations must preserve one-operation transaction identity; there is no implicit
  batch rollback coupling.

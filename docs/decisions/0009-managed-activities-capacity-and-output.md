# ADR 0009: Managed Activities, Capacity, and Durable Output

- Status: Accepted
- Date: 2026-08-20

## Context

Managed animal activities need durable admission reservations and output
claims. They must use the replacement persistence authority. They must not
create a second writer, operation engine, outbox, or projection checkpoint
system.

The replacement schema therefore needs a versioned extension that remains
compatible with the existing v1 database. The extension is generic. Tamework
owns persistence and lifecycle evidence. Rune_Professions owns player XP and
profession progression.

## Decision

Schema version 2 adds exactly three managed tables:

- population_domain_reservation stores provider-aware, weighted admission
  reservations alongside the existing owner and population-group
  reservations.
- companion_output_claim stores one durable output claim per profile and
  output key. The claim owns its item rows and cascades from the profile.
- companion_output_claim_item stores positive ready quantities for items in a
  claim.

It adds exactly two named indexes:

- idx_population_domain_reservation_scope
- idx_companion_output_claim_owner

The complete v2 schema has 32 managed tables and 18 named managed indexes.
The existing operation envelope, shared operation engine, projection outbox,
and consumer checkpoints remain the only cross-cutting authorities.

Fresh targets contain one version-2 history row. A v1 target is upgraded in
one transaction after a consistent sibling backup is created with SQLite
VACUUM INTO. The upgrade preserves the valid version-1 history row and adds
one version-2 row. If DDL fails, the transaction rolls back and the source
remains verifiable as v1. V2 verification requires the version-2 row and
allows at most one valid version-1 predecessor.

Tamework 3.2.x already shipped a smaller routed-read schema under version 2.
The upgrade accepts only its exact schema definitions and released hash. It
creates and verifies a sibling backup, then adds the managed tables and
indexes in one transaction. Unknown version-2 definitions still fail closed.

Unknown tables, indexes, triggers, views, and definition changes fail closed.
The v2 manager owns fresh creation, v1 upgrade, verification, and latest
startup wiring. Read-only activation uses the v2 gateway.

Managed activity and durable output feature descriptors are added by later
tasks. Their final descriptor ceiling is 16. Their operation definitions are
also added by later tasks; the final operation-definition ceiling is 23.
This schema task does not register feature descriptors or operation kinds.

## Consequences

Existing v1 saves gain a recoverable, auditable upgrade boundary. The sibling
backup remains available for operator recovery. The runtime opens only a
verified v2 target after startup. Existing v1 classification remains distinct
from v2 classification so rollback diagnostics retain the original lineage.

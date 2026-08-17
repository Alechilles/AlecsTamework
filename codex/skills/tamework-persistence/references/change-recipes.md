# Persistence Change Recipes

Choose one primary recipe. Add another recipe only when the change crosses a
real public boundary.

## Diagnose Runtime State

1. Use `docs/agents/runtime-vs-source-checklist.md` to identify the loaded jar,
   database, save, and staged assets.
2. Identify the owning database and operation ID, profile ID, or bonded profile
   ID.
3. For replacement persistence, capture operation phase, outbox position,
   incident, quarantine, circuit, and startup readiness from `/tw debugdb`.
4. For bonded persistence, capture profile state, exact lease, operation probe,
   cleanup intent, and readiness from bonded diagnostics.
5. Classify the failure before editing: live effect, durable commit,
   publication, recovery, projection, schema authority, or stale runtime.

## Add or Change a Replacement Read

1. Identify the canonical table or an existing projection.
2. Expose the read through the owning store, query facade, and public facade.
3. Preserve `Found`, `Absent`, and `Failed`; do not turn decode or storage
   failure into absence.
4. State the consistency and staleness contract.
5. Test the consumer-visible result and failure behavior.

## Add a Replacement Durable Mutation

Use an existing operation family when its authority and scope match.

1. Define an immutable request, outcome, versioned payload codec, stable
   operation kind, idempotency key, and complete scopes.
2. Register the definition in `PublicPersistenceFeatureRegistry`.
3. Implement durable work only through
   `SqlitePersistenceTransactionContext` authorities.
4. Commit canonical state, operation evidence, required feature detail, and at
   least one projection event in one transaction.
5. Compose the adapter in `SqlitePublicOperationSet` and expose it through
   `PublicPersistenceOperations` or the owning facade.
6. Register recovery, containment, readiness, diagnostics, and shutdown
   ownership through the shared registry.

Use a current sibling operation as the pattern. Paid revival is a useful
pattern for external inventory effects and compensation. Capture is a useful
pattern for variants and shared participants. Profile extension is a useful
pattern for a database-only mutation.

## Add a Replacement Mutation With a Live Effect

Apply the durable-mutation recipe plus these steps:

1. Freeze the complete request and positive evidence on the owning world
   thread.
2. Persist `LIVE_APPLYING` before the external effect.
3. Run ECS, inventory, world, filesystem, and network work through a narrow live
   boundary outside the transaction.
4. Make the live result idempotent or retain exact receipt evidence.
5. Define exact unknown-outcome readback. If positive proof is unavailable,
   contain the operation instead of replaying it.
6. Publish from the outbox after commit. Listener failure cannot roll back
   canonical state.

## Change Replacement Schema or Import

1. Read ADRs 0005 and 0006 and inspect `SqliteSchemaV1Manager`.
2. Preserve the public v2-v4 and legacy `.dat` import boundaries unless a new
   ADR changes them.
3. Keep classification read-only and complete before target creation.
4. Import into an owned temporary target, verify it, and publish atomically.
5. Preserve UUIDs, zero and negative timestamps, source files, WAL evidence,
   hashes, and refusal behavior.
6. Update the exact schema authority and relevant fixture-based behavior tests.
   Do not add raw SQL text or table-count presence tests.

## Change Bonded-Companion Persistence

1. Trace the action from `BondedCompanionApi` through
   `BondedCompanionApiFacade` to the bonded store and world projection layer.
2. Preserve the dedicated database and the `STORED`, `ACTIVE`, and `DEAD`
   lifecycle contract.
3. Fence live projections with exact leases. A live NPC UUID is lease evidence,
   not durable profile identity.
4. Converge non-death exits to `STORED`. Require positive death evidence for
   `DEAD`.
5. Preserve complete snapshot state and do not treat an unavailable optional
   component as deletion.
6. Reuse the bonded operation, cleanup, payment, and projection-durability
   services. Do not move the feature into replacement persistence for reuse.

## Change a Small Settings or Data-Path Store

Keep the change in the focused store. Do not introduce the companion operation
protocol when the data is not companion state. Preserve atomic file behavior,
path ownership, graceful read failure, and current caller semantics.

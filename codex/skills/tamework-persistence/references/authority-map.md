# Persistence Authority Map

Use this map before broad source searches. Verify all paths and names against
the current commit.

## Authority Selection

| Evidence in the request | Owning authority | Start with |
| --- | --- | --- |
| Generic profiles, aliases, lifecycle, snapshots, capture, coop, roster, timed summon, provisioning, restoration, population, extension data | Replacement persistence in `tamework-state.sqlite` | `docs/decisions/0001-persistence-replacement-boundaries.md`, `docs/decisions/0007-persistence-core-implementation.md` |
| Disposable projections for bonded dragons, Miniwyverns, bonded leases, bonded revive, bonded capture, bonded extension data | Dedicated bonded authority in `bonded-companions.sqlite` | `docs/Required-Persistence-Feature-Inventory.md` and external lesson `2026-07-25-bonded-companion-lease-boundary.md` |
| Public v2-v4 SQLite, legacy `.dat`, v5-v9 refusal, import reports, source classification | Replacement importer | `docs/decisions/0005-persistence-source-classification.md`, `docs/decisions/0006-public-persistence-import-policy.md` |
| Tamework settings, announcements, or path resolution without companion state | Focused settings/data-path stores | `src/main/java/com/alechilles/alecstamework/persistence/Tamework*Store.java`, `TameworkDataPath*.java` |

Do not select an authority from a class name alone. Trace the public API, data
path, and composition owner.

## Replacement Persistence

Canonical scope:

- Database: `tamework-state.sqlite`.
- Composition: `TameworkPersistenceComposition` and
  `TameworkPersistenceAuthors`.
- Public mutation facade:
  `persistence/runtime/PublicPersistenceOperations.java`.
- Operation catalog:
  `persistence/runtime/PublicPersistenceFeatureRegistry.java`.
- Adapter composition:
  `persistence/adapter/sqlite/SqlitePublicOperationSet.java`.
- Transaction authorities:
  `persistence/adapter/sqlite/SqlitePersistenceTransactionContext.java`.
- Shared engine, recovery, and publication:
  `SqliteOperationEngine`, `SqlitePublicRecoveryRegistry`,
  `SqliteOperationPublisher`, and `SqlitePublicProjectionSet`.

Read ADR 0002 for canonical lifecycle, ADR 0003 for the operation phase graph,
and ADR 0008 plus `docs/Required-Persistence-Feature-Inventory.md` before adding
an operation kind, descriptor, or table.

The replacement system owns one canonical lifecycle and one shared operation,
recovery, projection, containment, readiness, and shutdown model. Feature
detail can add participants and versioned evidence. It cannot copy those
cross-cutting authorities.

## Bonded-Companion Persistence

Canonical scope:

- Database: `bonded-companions.sqlite`.
- Public API: `TameworkApi.bondedCompanions()` and
  `api/BondedCompanionApi.java`.
- Composition and facade:
  `persistence/bonded/BondedCompanionPersistenceRuntime.java` and
  `BondedCompanionApiFacade.java`.
- Domain lifecycle:
  `companion/bonded/BondedCompanionState.java` and
  `BondedCompanionTransitionService.java`.
- Storage and schema:
  `persistence/bonded/BondedCompanionStore.java`,
  `BondedCompanionSchemaManager.java`, and
  `adapter/sqlite/SqliteBondedCompanion*.java`.
- World projection and lease behavior:
  `companion/bonded/BondedCompanionLocalProjectionLifecycle.java`,
  `BondedCompanionProjectionService.java`, and related runtime systems.

Bonded persistence has its own three-state lifecycle, lease, operation,
cleanup, readiness, and durability contracts. Do not register bonded profiles,
leases, or operations in the generic replacement database. Do not apply the
replacement outbox or feature registry unless an accepted design changes this
boundary.

## Architecture Evidence

Use this order when deciding what the architecture must permit:

1. Accepted ADRs and architecture guards define the intended boundary.
2. Current production code and behavior tests show whether the implementation
   conforms. Treat conflicting implementation as drift, not a new decision.
3. Current developer documentation and inventory explain the accepted design.
4. Old commits and remembered behavior provide characterization evidence only.

## Runtime Evidence

Use this order when deciding what the game actually loaded or did:

1. Exact loaded jar or directory, active database and WAL state, logs, and live
   diagnostics.
2. The packaged or staged artifact that supplied that runtime.
3. Current source, ADRs, and documentation used for comparison.
4. Legacy runtime copies and old commits used for diagnosis only.

Do not let current source override evidence from a stale loaded artifact. Do
not let a stale artifact redefine the intended architecture.

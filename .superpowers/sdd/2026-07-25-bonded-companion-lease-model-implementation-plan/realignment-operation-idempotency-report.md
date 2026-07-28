# Realignment Task B: Operation Idempotency Report

## Status

Implementation and focused verification are complete and ready for scoped
re-review. The implementation range is `0b30decf..280b9017`; its report
baseline is `490f59a6`:

- `9158bf9e` - `Fix: narrow bonded operation idempotency`
- `280b9017` - `Fix: bind bonded operations to exact leases`
- `490f59a6` - `Docs: report bonded operation idempotency`

Task A panel, UI, locale, and progress work remained untouched and unstaged.

## Behavior matrix

| Action | Durable fence | Matching duplicate | Changed same-key request |
| --- | --- | --- | --- |
| Capture | Terminal `CAPTURE` row in the capture transaction | Returns the committed profile/evidence and retries only its exact cleanup | `IDEMPOTENCY_CONFLICT` |
| Provision | Terminal `PROVISION` row in the create transaction | Returns the committed stored profile without rerunning policy or creation | `IDEMPOTENCY_CONFLICT` |
| Store | Terminal `STORE` row in the snapshot/profile/lease/cleanup transaction; request hash includes owner, roster, profile, revision, lease token, live NPC UUID, and world | Probes before any world read, returns the committed profile, and performs no second snapshot read, cleanup insertion, lease deletion, or revision | `IDEMPOTENCY_CONFLICT`, including a different exact lease or terminal world mismatch |
| Revive | Terminal `REVIVE` row tied to the exact revision and escrow receipt | Returns the committed/rejected typed result and settles only that receipt | `IDEMPOTENCY_CONFLICT` |
| Summon | Exact profile lease only; no operation row | Runtime `PENDING` returns `bonded-summon-in-progress`; `LIVE` returns `bonded-summon-already-live`; neither enters spawn or replay | A new summon is admitted only after the profile is `STORED` with no lease |
| Startup residual | Database-only `PENDING` lease settlement | Every residual `PENDING` lease becomes `STORED` with exact cleanup; `LIVE` survives | Runtime/local reconciliation cannot demote `PENDING` |

The operation claim, mutation, typed result, and terminal state share one SQLite
transaction. A rollback commits no claim. The schema accepts only terminal
`SUCCEEDED`/`REJECTED` rows and the action vocabulary `CAPTURE`, `PROVISION`,
`STORE`, and `REVIVE`.

## Changed files

Runtime and domain coordination:

- `src/main/java/com/alechilles/alecstamework/Tamework.java`
- `src/main/java/com/alechilles/alecstamework/TameworkBondedCompanionComposition.java`
- `src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionProfile.java`
- `src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionProjectionService.java`
- `src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionSpawnFailureHandler.java`
- `src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionTransitionService.java`
- `src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionWorldLifecycleObserver.java`
- `src/main/java/com/alechilles/alecstamework/items/HytaleBondedCompanionPaymentRecovery.java`

SQLite authority:

- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionCleanupReplay.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionDatabase.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionExplicitStore.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionOperationClaims.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionOperationExecutor.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionProjectionDurability.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionRetentionStore.java`
- `src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionStartupSettlement.java`

Persistence contracts and helpers:

- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionCoreApiOperations.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionOperation.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionOperationProbe.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionPaymentRecoveryService.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionReviveOperationService.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionReviveQuoteSupport.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionSchemaCatalog.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionSchemaManager.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionStore.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionStoreOperationFactory.java`
- `src/main/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionStoredRowValidator.java`
- `src/main/resources/persistence/bonded/v8.sql`

Focused tests:

- `src/test/java/com/alechilles/alecstamework/persistence/adapter/sqlite/SqliteBondedCompanionStoreTest.java`
- `src/test/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionRecoveryTest.java`
- `src/test/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionRecoveryTestFixtures.java`
- `src/test/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionStateMachineTest.java`
- `src/test/java/com/alechilles/alecstamework/items/HytaleBondedCompanionEscrowInventoryTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionApiFacadeCoherentListTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionCaptureSourceMigrationTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionCompositionTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionOperationProbeSqliteTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionPaymentRecoverySqliteTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionProjectionDurabilityTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionReviveOperationServiceTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionSchemaAuthorityTamperTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionSchemaManagerTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionStorePublicBoundaryTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionStoreReviewFixTest.java`
- `src/test/java/com/alechilles/alecstamework/persistence/bonded/BondedCompanionSummonLeaseFenceTest.java`

## Removed files and authorities

- Removed the duplicate in-memory `BondedCompanionOperationFactory`,
  `BondedCompanionOperationLedger`, and `BondedCompanionOperationReceipt`.
- Removed runtime player-add payment replay via
  `BondedCompanionPaymentRecoverySystem` and its architecture test.
- Removed `SqliteBondedCompanionLegacyPaymentStore` and
  `BondedCompanionLegacyPaymentSettlementGroup`.
- Removed public/adapter operation-backed `SUMMON` and `CLEANUP` workflows,
  committed operation `PENDING`, resume/replay branches, owner-wide terminal
  operation scans, and legacy payment-group settlement.
- Removed generic/local runtime settlement of a `PENDING` summon lease. Startup
  database settlement is the sole residual-pending authority.

## Verification

Focused GREEN command:

```text
./mvnw -Dtest=BondedCompanionOperationProbeSqliteTest,BondedCompanionProjectionDurabilityTest,BondedCompanionRecoveryTest,BondedCompanionCompositionTest,BondedCompanionSummonLeaseFenceTest,BondedCompanionReviveOperationServiceTest,BondedCompanionStateMachineTest,BondedCompanionSchemaManagerTest,BondedCompanionCaptureCompletionTest,BondedCompanionProvisionedHealthTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Result: `BUILD SUCCESS`; 113 tests, 0 failures, 0 errors, 0 skipped.

Scoped review follow-up command:

```text
./mvnw -Dtest=SqliteBondedCompanionStoreTest,BondedCompanionSchemaManagerTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Result: `BUILD SUCCESS`; 31 tests, 0 failures, 0 errors, 0 skipped. The
follow-up replaced the last low-level retention fixture's removed
`SUMMON`/`PENDING`/`FAILED` vocabulary with valid terminal action rows and a
valid typed result envelope while preserving replay, conflict, and bounded
pruning assertions.

The first review-regression run produced the intended RED signal in one stale
test that still called runtime `reconcileStored(PENDING)`. It was corrected to
exercise `settleResidualLeases` as the only restart settlement, after which the
same command passed.

Additional gates:

- `git diff --check`: clean (line-ending warnings only).
- ECS/thread-affinity source scan from `AGENTS.md`: no matches.
- Removed operation replay vocabulary scan: no matches.
- Class ceilings after extraction: projection service 492 lines, core API
  operations 496 lines, SQLite projection durability 471 lines.
- Post-test process check: no Maven or Surefire process remained. The existing
  IntelliJ and Hytale server processes were not started or modified by this task.
- `scripts/tools/check-agent-docs.ps1` was attempted with PowerShell 7 against
  the canonical repository root; it reported that the pre-existing generated
  agent index is stale. This task did not regenerate or stage unrelated agent
  documentation.

## Known gaps

- Per the task brief, the full Maven suite, packaging, installation, live-game
  validation, release preparation, and publication were not run.
- The repository-wide agent-doc check remains red because the canonical
  `docs/agents/generated-index.md` was already stale; it is outside this
  operation-only change set.
- Fresh-world realignment is authoritative. Historical terminal STORE rows that
  lack the v2 exact lease metadata fail closed instead of replaying through the
  absent-lease STORE probe.
- No generic operation worker, cross-world replay, owner-wide payment scan, or
  legacy migration authority was retained.

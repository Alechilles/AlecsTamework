package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupClassificationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupCountEvidenceRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationGroupRepository;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerPopulationAdmissionCoordinatorTest {
    @TempDir
    Path tempDir;

    @Test
    void newAcquisitionIsPreparedBeforeApplyAndCommitsOneCanonicalRevision() throws Exception {
        try (Harness harness = harness("new.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            OwnerPopulationAdmissionPlan plan = newPlan(profileId, npcUuid, ownerUuid, 1, 10L);

            OwnerPopulationPreparationResult preparation =
                    harness.coordinator.prepareAsync(plan).get(2, TimeUnit.SECONDS);

            assertTrue(preparation.allowed());
            assertEquals(1L, harness.index.counts(ownerUuid, "default").globalPending());
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
            assertTrue(harness.coordinator.claimForApply(prepared, 10L, ClaimProviderGeneration.NONE));
            OwnerPopulationCommitResult committed =
                    harness.coordinator.commitAsync(prepared).get(2, TimeUnit.SECONDS);

            assertTrue(committed.committed());
            assertEquals(1L, harness.index.counts(ownerUuid, "default").globalCommitted());
            OwnerPopulationEntry indexed = harness.index.entry(profileId).orElseThrow();
            assertEquals(1L, indexed.revision());
            CompanionPopulationStateRecord durable = harness.repository.loadAllStates().getFirst();
            assertEquals(ownerUuid, durable.ownerUuid());
            assertEquals(1L, durable.revision());
            assertEquals("COMMITTED", operationState(harness.connections, prepared.operationId()));
        }
    }

    /**
     * Regression: source cleanup cannot close before its replacement is durable, but the group
     * classification must still be applied in the same transaction as the owner projection.
     */
    @Test
    void groupCompositePreservesSourceFinalizationPendingSemantics() throws Exception {
        try (Harness harness = harness("group-source-finalization.sqlite")) {
            PopulationGroupRegistry installedRegistry = new PopulationGroupRegistry();
            assertTrue(installedRegistry.replace(List.of(), 1L).applied());
            harness.coordinator().installPopulationGroups(new PopulationGroupOwnerAdmissionExtension(
                    harness.coordinator(), installedRegistry, harness.groups(),
                    new NpcProfileRepository(harness.connections(), harness.queue())));
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            OwnerPopulationAdmissionPlan plan = withSourceFinalization(
                    newPlan(profileId, npcUuid, ownerUuid, 1, 12L), npcUuid);
            String groupOperationId = "groups-" + UUID.randomUUID();
            PopulationGroupOperationRecord groupOperation = new PopulationGroupOperationRecord(
                    groupOperationId, null, profileId, "NEW_OWNERSHIP",
                    PopulationGroupOperationRecord.State.PREPARED, 0L, 12L,
                    null, ownerUuid, null, "miniwyvern", List.of(), List.of("soul_bond"),
                    null, CompanionLifecycleState.ACTIVE.name(), null, "default", null,
                    "PREPARING", 100L, 100L, 0L);
            PopulationGroupRepository.ReservationEvidence evidence =
                    new PopulationGroupRepository.ReservationEvidence(
                            ownerUuid, "soul_bond",
                            PopulationGroupCountEvidenceRecord.ScopeKind.GLOBAL,
                            null, 1, 1, 1, 1, 12L);

            OwnerPopulationPreparationResult preparation = harness.coordinator()
                    .groupCompositeCoordinator()
                    .preparePopulationGroupsAsync(
                            plan, harness.groups(), groupOperation, List.of(evidence))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(preparation.allowed());
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
            assertTrue(harness.coordinator().groupCompositeCoordinator().claimForApply(
                    prepared, 12L, ClaimProviderGeneration.NONE));
            PopulationGroupClassificationRecord classification =
                    new PopulationGroupClassificationRecord(
                            profileId, "miniwyvern", List.of("soul_bond"), 12L,
                            PopulationGroupClassificationRecord.Status.RESOLVED,
                            "test", 100L, 110L);

            OwnerPopulationCommitResult result = harness.coordinator()
                    .groupCompositeCoordinator()
                    .commitPopulationGroupsAsync(
                            prepared, harness.groups(), groupOperationId,
                            new PopulationGroupRepository.ClassificationMutation(
                                    null, classification), 110L)
                    .get(2, TimeUnit.SECONDS);

            assertEquals(OwnerPopulationCommitResult.Status.SOURCE_FINALIZATION_PENDING,
                    result.status());
            assertTrue(result.committed());
            assertTrue(result.sourceFinalizationPending());
            assertEquals(PreparedOwnerPopulationAdmission.State.SOURCE_FINALIZATION_PENDING,
                    prepared.state());
            assertEquals("APPLIED", operationState(
                    harness.connections(), prepared.operationId()));
            assertEquals(PopulationGroupOperationRecord.State.APPLIED,
                    harness.groups().findOperation(groupOperationId).state());
            assertEquals(List.of("soul_bond"),
                    harness.groups().findClassification(profileId).groupIds());
            assertEquals(1L, harness.index().counts(ownerUuid, "default").globalCommitted());
            assertTrue(harness.health().isHealthy());
        }
    }

    @Test
    void settingsChangeBeforeApplyCancelsWithoutChangingCommittedCounts() throws Exception {
        try (Harness harness = harness("settings-change.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(UUID.randomUUID().toString(), npcUuid, ownerUuid, 1, 4L)
            ).get(2, TimeUnit.SECONDS);

            assertFalse(harness.coordinator.claimForApply(
                    preparation.preparedAdmission(),
                    5L,
                    ClaimProviderGeneration.NONE
            ));
            assertTrue(harness.queue.awaitIdle(2_000L));

            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalCommitted());
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            assertEquals(
                    "FAILED",
                    operationState(harness.connections, preparation.preparedAdmission().operationId())
            );
        }
    }

    @Test
    void explicitCancelClosesJournalAndPreservesRecoverableBaseline() throws Exception {
        try (Harness harness = harness("cancel.sqlite")) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(UUID.randomUUID().toString(), npcUuid, ownerUuid, 1, 1L)
            ).get(2, TimeUnit.SECONDS);

            assertTrue(harness.coordinator.cancelAsync(
                    preparation.preparedAdmission(),
                    "world-mutation-rejected"
            ).get(2, TimeUnit.SECONDS));

            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            CompanionPopulationStateRecord durable = harness.repository.loadAllStates().getFirst();
            assertNull(durable.ownerUuid());
            assertEquals(0L, durable.revision());
            assertEquals(
                    "FAILED",
                    operationState(harness.connections, preparation.preparedAdmission().operationId())
            );
        }
    }

    @Test
    void compensationIsDurableBeforeReservationReleaseAndBothStagesAreIdempotent() throws Exception {
        try (Harness harness = harness("compensating.sqlite")) {
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            ownerUuid,
                            1,
                            6L
                    )
            ).get(2, TimeUnit.SECONDS);
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
            assertTrue(harness.coordinator.claimForApply(
                    prepared, 6L, ClaimProviderGeneration.NONE
            ));

            CompletableFuture<Boolean> firstStart = harness.coordinator.beginCompensationAsync(
                    prepared, "live-write-failed"
            );
            CompletableFuture<Boolean> retryStart = harness.coordinator.beginCompensationAsync(
                    prepared, "retry"
            );

            assertSame(firstStart, retryStart);
            assertTrue(firstStart.get(2, TimeUnit.SECONDS));
            assertEquals("COMPENSATING", operationState(harness.connections, prepared.operationId()));
            assertEquals(1L, harness.index.counts(ownerUuid, "default").globalPending());

            CompletableFuture<Boolean> firstClose = harness.coordinator.completeCompensationAsync(
                    prepared, "live-state-restored"
            );
            CompletableFuture<Boolean> retryClose = harness.coordinator.completeCompensationAsync(
                    prepared, "retry"
            );

            assertSame(firstClose, retryClose);
            assertTrue(firstClose.get(2, TimeUnit.SECONDS));
            assertEquals("FAILED", operationState(harness.connections, prepared.operationId()));
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            assertEquals(0, harness.index.pendingReservationCount());
        }
    }

    /** Regression: repeated outer owner/claim cleanup must share one durable owner cancellation. */
    @Test
    void ownerCancellationIsIdempotentAndSharesOneJournalClose() throws Exception {
        try (Harness harness = harness("cancel-idempotent.sqlite")) {
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            ownerUuid,
                            1,
                            3L
                    )
            ).get(2, TimeUnit.SECONDS);
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();

            CompletableFuture<Boolean> first = harness.coordinator.cancelAsync(
                    prepared,
                    "first-cancel"
            );
            CompletableFuture<Boolean> retry = harness.coordinator.cancelAsync(
                    prepared,
                    "retry-must-not-close-again"
            );

            assertSame(first, retry);
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(retry.get(2, TimeUnit.SECONDS));
            assertEquals(0, harness.index.pendingReservationCount());
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            assertEquals("FAILED", operationState(harness.connections, prepared.operationId()));
        }
    }

    @Test
    void persistenceDegradedFailsClosedWithoutLeakingReservation() throws Exception {
        try (Harness harness = harness("degraded-before.sqlite")) {
            harness.health.markDegraded("test");
            UUID ownerUuid = UUID.randomUUID();

            OwnerPopulationPreparationResult result = harness.coordinator.prepareAsync(
                    newPlan(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            ownerUuid,
                            1,
                            1L
                    )
            ).get(2, TimeUnit.SECONDS);

            assertFalse(result.allowed());
            assertEquals("test", result.reason());
            assertEquals(PersistenceMutationAvailabilityStatus.GLOBAL_READ_ONLY,
                    result.decision().persistenceAvailability().status());
            assertEquals(0, harness.index.pendingReservationCount());
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            assertTrue(harness.repository.loadAllStates().isEmpty());
        }
    }

    /** Regression: storage can degrade after PREPARED but before the world mutation is claimed. */
    @Test
    void persistenceDegradedAfterPreparationFailsClosedBeforeApply() throws Exception {
        try (Harness harness = harness("degraded-before-apply.sqlite")) {
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            ownerUuid,
                            1,
                            7L
                    )
            ).get(2, TimeUnit.SECONDS);
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
            assertTrue(preparation.allowed());
            assertEquals(1L, harness.index.counts(ownerUuid, "default").globalPending());

            harness.health.markDegraded("injected-before-apply");

            assertFalse(harness.coordinator.claimForApply(
                    prepared,
                    7L,
                    ClaimProviderGeneration.NONE
            ));
            assertFalse(harness.coordinator.cancelAsync(
                    prepared,
                    "await-pre-apply-health-cancel"
            ).get(2, TimeUnit.SECONDS));

            assertEquals(0, harness.index.pendingReservationCount());
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalCommitted());
            assertEquals(0L, harness.index.counts(ownerUuid, "default").globalPending());
            assertEquals(OwnerPopulationReadiness.DEGRADED, harness.index.readiness());
            // The unhealthy write queue cannot close the durable journal. Leaving APPLYING is
            // intentional: startup recovery must reconcile it instead of treating it as settled.
            assertEquals("APPLYING", operationState(harness.connections, prepared.operationId()));
        }
    }

    @Test
    void finalDurabilityFailureKeepsConservativeIndexAndNonterminalJournal() throws Exception {
        Harness harness = harness("degraded-final.sqlite");
        try {
            UUID ownerUuid = UUID.randomUUID();
            OwnerPopulationPreparationResult preparation = harness.coordinator.prepareAsync(
                    newPlan(
                            UUID.randomUUID().toString(),
                            UUID.randomUUID(),
                            ownerUuid,
                            1,
                            2L
                    )
            ).get(2, TimeUnit.SECONDS);
            PreparedOwnerPopulationAdmission prepared = preparation.preparedAdmission();
            assertTrue(harness.coordinator.claimForApply(prepared, 2L, ClaimProviderGeneration.NONE));
            harness.queue.close();

            OwnerPopulationCommitResult result =
                    harness.coordinator.commitAsync(prepared).get(2, TimeUnit.SECONDS);

            assertEquals(OwnerPopulationCommitResult.Status.PERSISTENCE_DEGRADED, result.status());
            assertFalse(harness.health.isHealthy());
            assertEquals(1L, harness.index.counts(ownerUuid, "default").globalCommitted());
            assertEquals(List.of("APPLYING"), nonterminalStates(harness.connections));
        } finally {
            harness.close();
        }
    }

    @Test
    void concurrentPreparationsReserveOnlyAvailableHeadroom() throws Exception {
        try (Harness harness = harness("concurrent.sqlite")) {
            UUID ownerUuid = UUID.randomUUID();
            List<java.util.concurrent.CompletableFuture<OwnerPopulationPreparationResult>> futures =
                    java.util.stream.IntStream.range(0, 8)
                            .mapToObj(index -> harness.coordinator.prepareAsync(newPlan(
                                    UUID.randomUUID().toString(),
                                    UUID.randomUUID(),
                                    ownerUuid,
                                    3,
                                    1L
                            )))
                            .toList();
            List<OwnerPopulationPreparationResult> results = new java.util.ArrayList<>();
            for (java.util.concurrent.CompletableFuture<OwnerPopulationPreparationResult> future : futures) {
                results.add(future.get(3, TimeUnit.SECONDS));
            }

            assertEquals(3L, results.stream().filter(OwnerPopulationPreparationResult::allowed).count());
            assertEquals(3L, harness.index.counts(ownerUuid, "default").globalPending());
            for (OwnerPopulationPreparationResult result : results) {
                if (result.allowed()) {
                    assertTrue(harness.coordinator.cancelAsync(
                            result.preparedAdmission(),
                            "test-cleanup"
                    ).get(2, TimeUnit.SECONDS));
                }
            }
            assertEquals(0, harness.index.pendingReservationCount());
        }
    }

    private Harness harness(String filename) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(filename));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceHealthService health = new PersistenceHealthService();
        PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null);
        CompanionPopulationRepository repository = new CompanionPopulationRepository(connections, queue);
        PopulationGroupRepository groups = new PopulationGroupRepository(connections, queue);
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        index.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
        return new Harness(
                connections,
                queue,
                health,
                repository,
                groups,
                index,
                new OwnerPopulationAdmissionCoordinator(index, repository, health)
        );
    }

    private static OwnerPopulationAdmissionPlan withSourceFinalization(
            OwnerPopulationAdmissionPlan plan, UUID sourceNpcUuid) {
        return new OwnerPopulationAdmissionPlan(
                plan.transition(), plan.baselineState(), plan.finalNpcUuid(),
                plan.finalPhysicalWorldName(), plan.finalPhysicalChunkX(),
                plan.finalPhysicalChunkZ(), plan.source(), plan.oldStateJson(),
                plan.newStateJson(), CompanionSpawnSourceFinalizationContext.extensionJson(
                        CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM,
                        "source-finalization-test", sourceNpcUuid, UUID.randomUUID(), 0,
                        "before", "after"),
                plan.settingsRevision(), plan.providerGeneration());
    }

    private static OwnerPopulationAdmissionPlan newPlan(String profileId,
                                                        UUID npcUuid,
                                                        UUID ownerUuid,
                                                        int limit,
                                                        long settingsRevision) {
        long now = System.currentTimeMillis();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                profileId,
                npcUuid,
                null,
                "default",
                "default",
                CompanionLifecycleState.ACTIVE.name(),
                "default",
                0,
                0,
                0L,
                "baseline",
                now,
                now
        );
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                ownerUuid,
                "default",
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.NEW_OWNERSHIP,
                OwnerPopulationLimitScope.GLOBAL,
                limit,
                false
        );
        return new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                npcUuid,
                "default",
                0,
                0,
                "test",
                "{\"owner\":null}",
                "{\"owner\":\"" + ownerUuid + "\"}",
                "{\"world\":\"default\"}",
                settingsRevision,
                ClaimProviderGeneration.NONE
        );
    }

    private static String operationState(SqliteConnectionManager connections, UUID operationId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM companion_population_operations WHERE operation_id = ?"
             )) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static List<String> nonterminalStates(SqliteConnectionManager connections) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT state FROM companion_population_operations
                     WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                     ORDER BY state
                     """
             );
             ResultSet resultSet = statement.executeQuery()) {
            List<String> states = new java.util.ArrayList<>();
            while (resultSet.next()) {
                states.add(resultSet.getString(1));
            }
            return List.copyOf(states);
        }
    }

    private record Harness(SqliteConnectionManager connections,
                           PersistenceWriteQueue queue,
                           PersistenceHealthService health,
                           CompanionPopulationRepository repository,
                           PopulationGroupRepository groups,
                           OwnerPopulationIndex index,
                           OwnerPopulationAdmissionCoordinator coordinator) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}

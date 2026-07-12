package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionReservation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end SQLite proof for re-admitting an exact RETRYABLE breeding child. */
class BreedingPopulationRetryIntegrationTest {
    private static final String WORLD = "default";
    private static final int CHUNK_X = 4;
    private static final int CHUNK_Z = 7;
    private static final String ATTEMPT = "breeding-retry-integration";
    private static final String CHILD_KEY = "child-0";
    private static final List<String> PARENTS = List.of("parent-a", "parent-b");

    @TempDir
    Path tempDir;

    @Test
    void retryableBirthReusesRevisionZeroBaselineAndCommitsOneExactChild() throws Exception {
        try (Harness harness = new Harness(tempDir.resolve("breeding-retry.sqlite"))) {
            UUID ownerId = UUID.randomUUID();
            BreedingPopulationAdmissionRequest request = request(ownerId);
            String expectedProfileId = BreedingAdmissionIdentity.profileId(ATTEMPT, CHILD_KEY);
            UUID expectedNpcUuid = BreedingAdmissionIdentity.npcUuid(ATTEMPT, CHILD_KEY);

            Runtime firstRuntime = harness.restart();
            BreedingPopulationPreparationResult firstPreparation = firstRuntime.service()
                    .prepareAsync(request).get(4, TimeUnit.SECONDS);
            assertTrue(firstPreparation.allowed(), firstPreparation.reason());
            PreparedBreedingPopulationBatch firstBatch = firstPreparation.preparedBatch();
            assertNotNull(firstBatch);
            String retryableOperationId = operationId(firstBatch);

            CompanionPopulationStateRecord preparedBaseline = onlyState(harness.repository);
            assertEquals(expectedProfileId, preparedBaseline.profileId());
            assertEquals(expectedNpcUuid, preparedBaseline.currentNpcUuid());
            assertEquals(0L, preparedBaseline.revision());
            assertEquals("breeding_prepared", preparedBaseline.source());

            PersistenceWriteQueue.WriteOutcome<Boolean> retryable = harness.repository
                    .advanceOperationAsync(
                            retryableOperationId,
                            CompanionPopulationOperationRecord.State.APPLYING,
                            CompanionPopulationOperationRecord.State.RETRYABLE,
                            "breeding-child-projection-absent"
                    ).completion().get(4, TimeUnit.SECONDS);
            assertTrue(retryable.isCommitted());
            assertEquals(Boolean.TRUE, retryable.value());
            assertEquals(
                    CompanionPopulationOperationRecord.State.RETRYABLE,
                    operation(harness.repository, retryableOperationId).state()
            );

            Runtime restarted = harness.restart();
            BreedingPopulationReplayState loadedReplay = restarted.service()
                    .replayState(ATTEMPT);
            assertTrue(loadedReplay.usable(), loadedReplay.reason());
            assertEquals(Set.of(CHILD_KEY), loadedReplay.pendingChildKeys());
            assertEquals(expectedNpcUuid, restarted.identities()
                    .currentNpcUuid(expectedProfileId).orElseThrow());
            assertEquals(0L, restarted.owners().entry(expectedProfileId)
                    .orElseThrow().revision());
            assertEquals(0L, restarted.claims().entry(expectedProfileId)
                    .orElseThrow().revision());

            BreedingPopulationPreparationResult retryPreparation = restarted.service()
                    .prepareAsync(request).get(4, TimeUnit.SECONDS);
            assertTrue(retryPreparation.allowed(), retryPreparation.reason());
            PreparedBreedingPopulationBatch retryBatch = retryPreparation.preparedBatch();
            assertNotNull(retryBatch);
            assertEquals(1, retryBatch.admittedCount());
            assertEquals(expectedProfileId, retryBatch.child(0).profileId());
            assertEquals(expectedNpcUuid, retryBatch.child(0).plannedNpcUuid());

            PreparedCompanionPopulationAdmission retryAdmission = retryBatch.populationBatch()
                    .admission(0);
            OwnerPopulationAdmissionPlan retryPlan = retryAdmission.ownerAdmission().plan();
            String committedOperationId = retryAdmission.ownerAdmission().operationId().toString();
            assertNotEquals(retryableOperationId, committedOperationId);
            assertEquals(0L, retryPlan.transition().expectedRevision());
            assertEquals(0L, retryPlan.baselineState().revision());
            assertEquals("breeding_retry", retryPlan.source());
            assertEquals(0L, operation(harness.repository, committedOperationId)
                    .expectedRevision());
            assertEquals(0L, onlyState(harness.repository).revision());
            assertEquals(Set.of(CHILD_KEY), restarted.service()
                    .replayState(ATTEMPT).pendingChildKeys());

            assertTrue(restarted.service().claimForSpawn(retryBatch, 0));
            assertEquals(PreparedOwnerPopulationAdmission.State.APPLYING,
                    retryAdmission.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.APPLYING,
                    retryAdmission.claimReservation().state());

            CompanionPopulationCommitResult commit = restarted.service()
                    .commitAsync(retryBatch, 0).get(4, TimeUnit.SECONDS);
            assertTrue(commit.committed(), commit.reason());
            assertEquals(PreparedOwnerPopulationAdmission.State.COMMITTED,
                    retryAdmission.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.COMMITTED,
                    retryAdmission.claimReservation().state());

            CompanionPopulationStateRecord committedState = onlyState(harness.repository);
            assertEquals(expectedProfileId, committedState.profileId());
            assertEquals(expectedNpcUuid, committedState.currentNpcUuid());
            assertEquals(ownerId, committedState.ownerUuid());
            assertEquals(1L, committedState.revision());
            assertEquals("breeding_retry", committedState.source());
            assertEquals(1L, restarted.owners().entry(expectedProfileId)
                    .orElseThrow().revision());
            assertEquals(1L, restarted.claims().entry(expectedProfileId)
                    .orElseThrow().revision());

            BreedingPopulationReplayState terminalReplay = restarted.service()
                    .replayState(ATTEMPT);
            assertTrue(terminalReplay.pendingChildKeys().isEmpty());
            assertEquals(Set.of(CHILD_KEY), terminalReplay.committedChildKeys());

            List<CompanionPopulationOperationRecord> operations =
                    harness.repository.loadBreedingOperations();
            assertEquals(2, operations.size());
            assertEquals(1L, operations.stream()
                    .filter(row -> row.state()
                            == CompanionPopulationOperationRecord.State.RETRYABLE)
                    .count());
            assertEquals(1L, operations.stream()
                    .filter(row -> row.state()
                            == CompanionPopulationOperationRecord.State.COMMITTED)
                    .count());
            assertEquals(
                    CompanionPopulationOperationRecord.State.COMMITTED,
                    operation(harness.repository, committedOperationId).state()
            );
        }
    }

    private static BreedingPopulationAdmissionRequest request(UUID ownerId) {
        BreedingBirthPlanSnapshot plan = new BreedingBirthPlanSnapshot(
                1.0,
                1.0,
                1.0,
                1,
                List.of(new BreedingBirthPlanSnapshot.PlannedChild(
                        CHILD_KEY,
                        "role-baby",
                        0,
                        "role-adult",
                        null,
                        false,
                        null,
                        null,
                        ownerId,
                        "Retry Owner",
                        "retry-integration"
                ))
        );
        return new BreedingPopulationAdmissionRequest(
                WORLD,
                CHUNK_X,
                CHUNK_Z,
                List.of(new BreedingPopulationAdmissionRequest.PlannedChild(
                        CHILD_KEY, ownerId, "Retry Owner"
                )),
                1,
                true,
                ATTEMPT,
                plan,
                PARENTS
        );
    }

    private static String operationId(PreparedBreedingPopulationBatch batch) {
        return batch.populationBatch().admission(0).ownerAdmission()
                .operationId().toString();
    }

    private static CompanionPopulationStateRecord onlyState(
            CompanionPopulationRepository repository
    ) throws Exception {
        List<CompanionPopulationStateRecord> states = repository.loadAllStates();
        assertEquals(1, states.size());
        return states.getFirst();
    }

    private static CompanionPopulationOperationRecord operation(
            CompanionPopulationRepository repository,
            String operationId
    ) throws Exception {
        return repository.loadBreedingOperations().stream()
                .filter(row -> operationId.equals(row.operationId()))
                .findFirst()
                .orElseThrow();
    }

    private static ClaimProviderProbe absentProbe(
            ClaimIntegrationProvider provider,
            String providerId
    ) {
        return new ClaimProviderProbe() {
            @Override
            public ClaimIntegrationProvider provider() {
                return provider;
            }

            @Override
            public ClaimProviderProbeResult probe() {
                return ClaimProviderProbeResult.unavailable(
                        provider,
                        providerId,
                        ClaimProviderState.ABSENT,
                        null,
                        "not installed in retry integration test",
                        ClaimProviderGeneration.NONE
                );
            }
        };
    }

    private static final class Harness implements AutoCloseable {
        private final SqliteConnectionManager connections;
        private final PersistenceHealthService health = new PersistenceHealthService();
        private final PersistenceWriteQueue queue;
        private final CompanionPopulationRepository repository;
        private final CompanionPopulationCoverageRepository coverage;
        private final ClaimProviderRegistry providers;

        private Harness(Path database) throws Exception {
            connections = new SqliteConnectionManager(database);
            try (Connection connection = connections.openConnection()) {
                connection.setAutoCommit(false);
                new SqliteSchemaMigrator().migrate(connection);
                connection.commit();
            }
            queue = new PersistenceWriteQueue(connections, health, null);
            repository = new CompanionPopulationRepository(connections, queue);
            coverage = new CompanionPopulationCoverageRepository(connections, queue);
            providers = new ClaimProviderRegistry(
                    absentProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims"),
                    absentProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims")
            );
            seedReadyCoverage();
        }

        private Runtime restart() {
            OwnerPopulationIndex owners = new OwnerPopulationIndex();
            ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
            CompanionIdentityResolver identities = new CompanionIdentityResolver();
            CompanionPopulationBootstrapService.BootstrapResult bootstrap =
                    new CompanionPopulationBootstrapService(
                            repository,
                            coverage,
                            new CompanionIdentityRepository(connections),
                            health,
                            owners,
                            identities,
                            claims
                    ).load();
            assertEquals(OwnerPopulationReadiness.READY, bootstrap.globalReadiness());
            assertEquals(OwnerPopulationReadiness.READY, bootstrap.perWorldReadiness());

            BreedingReplayJournalLoader replayLoader = new BreedingReplayJournalLoader(
                    repository, health
            );
            replayLoader.refresh();
            ClaimAdmissionService claimService = new ClaimAdmissionService(claims);
            OwnerPopulationAdmissionCoordinator ownerCoordinator =
                    new OwnerPopulationAdmissionCoordinator(owners, repository, health);
            CompanionPopulationAdmissionCoordinator combined =
                    new CompanionPopulationAdmissionCoordinator(ownerCoordinator, claimService);
            BreedingPopulationAdmissionService service = new BreedingPopulationAdmissionService(
                    new CompanionPopulationBatchAdmissionCoordinator(combined),
                    owners,
                    claims,
                    providers,
                    new OwnerComponentMutationService(ownerCoordinator),
                    identities,
                    new ClaimLookupMetrics(),
                    replayLoader.replayService()
            );
            return new Runtime(service, owners, claims, identities);
        }

        private void seedReadyCoverage() throws Exception {
            long now = System.currentTimeMillis();
            for (CompanionPopulationCoverageRecord.Dimension dimension
                    : CompanionPopulationCoverageRecord.Dimension.values()) {
                CompanionPopulationCoverageRecord record =
                        new CompanionPopulationCoverageRecord(
                                "retry-test:" + dimension.name().toLowerCase(),
                                dimension,
                                dimension == CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER
                                        ? WORLD : null,
                                "retry-test-generation",
                                CompanionPopulationCoverageRecord.State.READY,
                                null,
                                1L,
                                1L,
                                now,
                                now,
                                now,
                                null
                        );
                PersistenceWriteQueue.WriteOutcome<Void> outcome = coverage.upsertAsync(record)
                        .completion().get(4, TimeUnit.SECONDS);
                assertTrue(outcome.isCommitted());
            }
        }

        @Override
        public void close() {
            providers.close();
            queue.close();
        }
    }

    private record Runtime(
            BreedingPopulationAdmissionService service,
            OwnerPopulationIndex owners,
            ClaimOccupancyIndex claims,
            CompanionIdentityResolver identities
    ) {
    }
}

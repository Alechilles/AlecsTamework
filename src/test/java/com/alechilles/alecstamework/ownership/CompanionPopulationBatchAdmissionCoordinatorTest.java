package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for atomic exact and stable-prefix combined population batches. */
class CompanionPopulationBatchAdmissionCoordinatorTest {
    private static final String WORLD = "default";
    private static final ClaimChunkCoordinate DESTINATION = new ClaimChunkCoordinate(WORLD, 0, 0);
    private static final long SETTINGS_REVISION = 9L;

    @TempDir
    Path tempDir;

    @Test
    void upToBatchRetainsOneExactCapacityLimitedPrefix() throws Exception {
        try (Harness harness = harness("up-to.sqlite")) {
            UUID ownerId = UUID.randomUUID();
            CompanionPopulationBatchPreparationResult result = harness.batch.prepareAsync(
                    units(ownerId, 4, 3, harness.policy),
                    new ClaimLookupSession(harness.policy),
                    CompanionPopulationBatchMode.UP_TO
            ).get(4, TimeUnit.SECONDS);

            assertTrue(result.allowed());
            assertEquals(4, result.requestedCount());
            assertEquals(3, result.admittedCount());
            assertEquals("companion-population-batch-clamped", result.reason());
            assertEquals("owner-cap-reached", result.limitingDecision().reason());
            assertEquals(3L, harness.ownerIndex.counts(ownerId, WORLD).globalPending());
            assertEquals(3, harness.claimService.pendingReservationCount());

            assertEquals(3, harness.batch.cancelRemainingAsync(
                    result.preparedBatch(),
                    "test-cleanup"
            ).get(4, TimeUnit.SECONDS));
            assertEquals(0L, harness.ownerIndex.counts(ownerId, WORLD).globalPending());
            assertEquals(0, harness.claimService.pendingReservationCount());
        }
    }

    @Test
    void exactBatchRollsBackEveryProvisionalUnitWhenHeadroomIsShort() throws Exception {
        try (Harness harness = harness("exact-rollback.sqlite")) {
            UUID ownerId = UUID.randomUUID();
            CompanionPopulationBatchPreparationResult result = harness.batch.prepareAsync(
                    units(ownerId, 4, 3, harness.policy),
                    new ClaimLookupSession(harness.policy),
                    CompanionPopulationBatchMode.EXACT
            ).get(4, TimeUnit.SECONDS);

            assertFalse(result.allowed());
            assertEquals(0, result.admittedCount());
            assertEquals("owner-cap-reached", result.reason());
            assertEquals(0L, harness.ownerIndex.counts(ownerId, WORLD).globalPending());
            assertEquals(0, harness.claimService.pendingReservationCount());
        }
    }

    @Test
    void simultaneousExactBatchesNeverExposeOrKeepAPartialWinner() throws Exception {
        try (Harness harness = harness("concurrent-exact.sqlite");
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            UUID ownerId = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            List<CompanionPopulationAdmissionUnit> firstUnits = units(ownerId, 2, 3, harness.policy);
            List<CompanionPopulationAdmissionUnit> secondUnits = units(ownerId, 2, 3, harness.policy);
            CompletableFuture<CompanionPopulationBatchPreparationResult> first =
                    CompletableFuture.supplyAsync(() -> prepareAfterLatch(harness, firstUnits, start), executor);
            CompletableFuture<CompanionPopulationBatchPreparationResult> second =
                    CompletableFuture.supplyAsync(() -> prepareAfterLatch(harness, secondUnits, start), executor);

            start.countDown();
            CompanionPopulationBatchPreparationResult firstResult = first.get(6, TimeUnit.SECONDS);
            CompanionPopulationBatchPreparationResult secondResult = second.get(6, TimeUnit.SECONDS);

            long winners = List.of(firstResult, secondResult).stream()
                    .filter(CompanionPopulationBatchPreparationResult::allowed)
                    .count();
            assertEquals(1L, winners);
            CompanionPopulationBatchPreparationResult winner = firstResult.allowed() ? firstResult : secondResult;
            CompanionPopulationBatchPreparationResult loser = firstResult.allowed() ? secondResult : firstResult;
            assertEquals(2, winner.admittedCount());
            assertFalse(loser.allowed());
            assertEquals(0, loser.admittedCount());
            assertEquals(2L, harness.ownerIndex.counts(ownerId, WORLD).globalPending());
            assertEquals(2, harness.claimService.pendingReservationCount());

            assertEquals(2, harness.batch.cancelRemainingAsync(
                    winner.preparedBatch(),
                    "test-cleanup"
            ).get(4, TimeUnit.SECONDS));
        }
    }

    private static CompanionPopulationBatchPreparationResult prepareAfterLatch(
            Harness harness,
            List<CompanionPopulationAdmissionUnit> units,
            CountDownLatch start
    ) {
        try {
            assertTrue(start.await(2, TimeUnit.SECONDS));
            return harness.batch.prepareAsync(
                    units,
                    new ClaimLookupSession(harness.policy),
                    CompanionPopulationBatchMode.EXACT
            ).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
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
        OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
        ownerIndex.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        claimIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.READY);
        ClaimAdmissionService claimService = new ClaimAdmissionService(claimIndex);
        CompanionPopulationAdmissionCoordinator combined = new CompanionPopulationAdmissionCoordinator(
                new OwnerPopulationAdmissionCoordinator(ownerIndex, repository, health),
                claimService
        );
        ClaimPolicyContext policy = offPolicy();
        return new Harness(
                queue,
                ownerIndex,
                claimService,
                new CompanionPopulationBatchAdmissionCoordinator(combined),
                policy
        );
    }

    private static List<CompanionPopulationAdmissionUnit> units(
            UUID ownerId,
            int count,
            int ownerLimit,
            ClaimPolicyContext policy
    ) {
        List<CompanionPopulationAdmissionUnit> units = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String profileId = UUID.randomUUID().toString();
            UUID npcId = UUID.randomUUID();
            ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(
                    null,
                    new ClaimOccupancyEntry(
                            profileId,
                            ownerId,
                            CompanionLifecycleState.ACTIVE,
                            DESTINATION,
                            1L
                    )
            );
            units.add(new CompanionPopulationAdmissionUnit(
                    ownerPlan(profileId, npcId, ownerId, ownerLimit),
                    new ClaimAdmissionRequest(
                            ClaimAdmissionOperation.BREED,
                            List.of(claimTransition),
                            DESTINATION,
                            policy,
                            0,
                            0,
                            false,
                            false,
                            OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
                    )
            ));
        }
        return List.copyOf(units);
    }

    private static OwnerPopulationAdmissionPlan ownerPlan(
            String profileId,
            UUID npcId,
            UUID ownerId,
            int ownerLimit
    ) {
        long now = System.currentTimeMillis();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                profileId,
                npcId,
                null,
                WORLD,
                WORLD,
                CompanionLifecycleState.ACTIVE.name(),
                WORLD,
                DESTINATION.chunkX(),
                DESTINATION.chunkZ(),
                0L,
                "breeding_test",
                now,
                now
        );
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                ownerId,
                WORLD,
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.BREEDING,
                OwnerPopulationLimitScope.GLOBAL,
                ownerLimit,
                false
        );
        return new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                npcId,
                WORLD,
                DESTINATION.chunkX(),
                DESTINATION.chunkZ(),
                "breeding_test",
                "{\"ownerUuid\":null}",
                "{\"ownerUuid\":\"" + ownerId + "\"}",
                "{\"world\":\"" + WORLD + "\"}",
                SETTINGS_REVISION,
                ClaimProviderGeneration.NONE
        );
    }

    private static ClaimPolicyContext offPolicy() {
        return new ClaimPolicyContext(
                "Off",
                ClaimIntegrationProvider.OFF,
                ClaimIntegrationProvider.OFF,
                "off",
                ClaimProviderState.OFF,
                Set.of(),
                null,
                "claim-population-disabled",
                ClaimProviderGeneration.NONE,
                SETTINGS_REVISION,
                null
        );
    }

    private record Harness(
            PersistenceWriteQueue queue,
            OwnerPopulationIndex ownerIndex,
            ClaimAdmissionService claimService,
            CompanionPopulationBatchAdmissionCoordinator batch,
            ClaimPolicyContext policy
    ) implements AutoCloseable {
        private Harness {
            assertNotNull(queue);
        }

        @Override
        public void close() {
            queue.close();
        }
    }
}

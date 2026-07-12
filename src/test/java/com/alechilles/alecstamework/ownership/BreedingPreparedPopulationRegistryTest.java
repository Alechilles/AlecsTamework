package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionReservation;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbe;
import com.alechilles.alecstamework.integration.claims.ClaimProviderProbeResult;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for exact terminal ownership of prepared breeding population units. */
class BreedingPreparedPopulationRegistryTest {
    private static final String WORLD = "default";

    @TempDir
    Path tempDir;

    @Test
    void sameAttemptAndChildrenWithAnotherBatchIdConflictsWithoutOwningCandidate() throws Exception {
        try (Harness harness = harness("install-conflict.sqlite")) {
            PreparedBreedingPopulationBatch installed = harness.prepare(2);
            PreparedBreedingPopulationBatch candidate = withAnotherBatchId(installed);
            BreedingPreparedPopulationRegistry registry = new BreedingPreparedPopulationRegistry();
            Object scope = new Object();
            UUID jobId = UUID.randomUUID();

            assertEquals(
                    BreedingPreparedPopulationRegistry.InstallStatus.INSTALLED,
                    registry.install(scope, jobId, harness.service, installed)
            );
            assertEquals(
                    BreedingPreparedPopulationRegistry.InstallStatus.CONFLICT,
                    registry.install(scope, jobId, harness.service, candidate)
            );
            assertTrue(registry.ownsCapability(scope, jobId, harness.service, installed));
            assertFalse(registry.ownsCapability(scope, jobId, harness.service, candidate));

            registry.cancelRemaining(jobId, "test-cleanup");
            awaitCanceled(installed);
        }
    }

    @Test
    void applyingCancellationStartsOneUnderlyingCancellationAndReachesCanceled() throws Exception {
        try (Harness harness = harness("applying-cancel.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(1);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;
            PreparedCompanionPopulationAdmission unit = batch.populationBatch().admission(0);

            assertEquals(
                    Set.of(batch.child(0).childKey()),
                    harness.service.replayState(batch.attemptKey()).pendingChildKeys()
            );
            assertTrue(registry.claimForSpawn(jobId, 0));
            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.APPLYING),
                    registry.states(jobId)
            );
            assertNull(unit.ownerAdmission().cancellationCompletion());

            registry.cancelRemaining(jobId, "definite-pre-add-failure");
            CompletableFuture<Boolean> cancellation =
                    unit.ownerAdmission().cancellationCompletion();
            assertNotNull(cancellation);

            registry.cancelRemaining(jobId, "duplicate-cleanup-must-be-a-no-op");
            assertSame(cancellation, unit.ownerAdmission().cancellationCompletion());
            assertTrue(cancellation.get(4, TimeUnit.SECONDS));
            awaitState(registry, jobId, 0, BreedingPreparedPopulationRegistry.UnitState.CANCELED);
            assertEquals(PreparedOwnerPopulationAdmission.State.CANCELED,
                    unit.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.CANCELED,
                    unit.claimReservation().state());
            assertTrue(
                    harness.service.replayState(batch.attemptKey()).pendingChildKeys().isEmpty()
            );
        }
    }

    @Test
    void unsuccessfulCancellationRetainsTheUnitAsAmbiguous() throws Exception {
        try (Harness harness = harness("cancel-ambiguous.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(1);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;

            assertTrue(registry.claimForSpawn(jobId, 0));
            assertTrue(harness.service.commitAsync(batch, 0)
                    .get(4, TimeUnit.SECONDS).committed());

            registry.cancelRemaining(jobId, "stale-runtime-cleanup");

            awaitState(registry, jobId, 0,
                    BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS);
            assertFalse(registry.claimForSpawn(jobId, 0));
            assertTrue(harness.service.replayState(batch.attemptKey())
                    .pendingChildKeys().isEmpty());
        }
    }

    @Test
    void materializedUnitIsNeverCanceledByRemainingOrScopeCleanup() throws Exception {
        try (Harness harness = harness("materialized-cleanup.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(1);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;
            PreparedCompanionPopulationAdmission unit = batch.populationBatch().admission(0);

            assertTrue(registry.claimForSpawn(jobId, 0));
            assertTrue(registry.markMaterialized(jobId, 0));
            registry.cancelRemaining(jobId, "job-finally");

            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.MATERIALIZED),
                    registry.states(jobId)
            );
            assertNull(unit.ownerAdmission().cancellationCompletion());
            assertEquals(PreparedOwnerPopulationAdmission.State.APPLYING,
                    unit.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.APPLYING,
                    unit.claimReservation().state());

            registry.clearScope(harness.scope, "world-unload");

            assertTrue(registry.states(jobId).isEmpty());
            assertNull(unit.ownerAdmission().cancellationCompletion());
            assertEquals(PreparedOwnerPopulationAdmission.State.APPLYING,
                    unit.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.APPLYING,
                    unit.claimReservation().state());

            assertTrue(harness.service.commitAsync(batch, 0)
                    .get(4, TimeUnit.SECONDS).committed());
        }
    }

    @Test
    void commitTransitionsMaterializedUnitToCommitted() throws Exception {
        try (Harness harness = harness("materialized-commit.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(1);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;

            assertTrue(registry.claimForSpawn(jobId, 0));
            assertTrue(registry.markMaterialized(jobId, 0));
            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.MATERIALIZED),
                    registry.states(jobId)
            );

            CompanionPopulationCommitResult result = registry.commitSpawn(jobId, 0)
                    .get(4, TimeUnit.SECONDS);

            assertTrue(result.committed());
            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.COMMITTED),
                    registry.states(jobId)
            );
        }
    }

    @Test
    void ambiguousPostAddUnitIsNeitherRetriedNorCanceled() throws Exception {
        try (Harness harness = harness("ambiguous-retention.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(1);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;
            PreparedCompanionPopulationAdmission unit = batch.populationBatch().admission(0);

            assertTrue(registry.claimForSpawn(jobId, 0));
            registry.retainAmbiguous(jobId, 0, "post-add-outcome-unknown");
            assertEquals(
                    Set.of(batch.child(0).childKey()),
                    harness.service.replayState(batch.attemptKey()).pendingChildKeys()
            );

            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS),
                    registry.states(jobId)
            );
            assertFalse(registry.claimForSpawn(jobId, 0));
            registry.cancelRemaining(jobId, "job-finally");
            assertEquals(
                    List.of(BreedingPreparedPopulationRegistry.UnitState.AMBIGUOUS),
                    registry.states(jobId)
            );
            assertNull(unit.ownerAdmission().cancellationCompletion());
            assertEquals(
                    Set.of(batch.child(0).childKey()),
                    harness.service.replayState(batch.attemptKey()).pendingChildKeys()
            );

            registry.clearScope(harness.scope, "world-unload");

            assertNull(unit.ownerAdmission().cancellationCompletion());
            assertEquals(PreparedOwnerPopulationAdmission.State.APPLYING,
                    unit.ownerAdmission().state());
            assertEquals(ClaimAdmissionReservation.State.APPLYING,
                    unit.claimReservation().state());
            assertEquals(
                    Set.of(batch.child(0).childKey()),
                    harness.service.replayState(batch.attemptKey()).pendingChildKeys()
            );
        }
    }

    @Test
    void retainedChildrenKeepExactOriginalIndexesForActiveOrdinals() throws Exception {
        try (Harness harness = harness("active-ordinal.sqlite")) {
            PreparedBreedingPopulationBatch batch = harness.prepare(4);
            BreedingPreparedPopulationRegistry registry = installedRegistry(harness, batch);
            UUID jobId = harness.jobId;
            String second = batch.child(1).childKey();
            String fourth = batch.child(3).childKey();

            registry.retainOnly(
                    jobId,
                    List.of(fourth, second),
                    "spawn-time-nearby-cap-shrink"
            );

            assertEquals(1, registry.unitIndexForActiveOrdinal(jobId, 0));
            assertEquals(3, registry.unitIndexForActiveOrdinal(jobId, 1));
            assertEquals(-1, registry.unitIndexForActiveOrdinal(jobId, 2));
            awaitCanceled(batch, 0, 2);
            awaitState(registry, jobId, 0,
                    BreedingPreparedPopulationRegistry.UnitState.CANCELED);
            awaitState(registry, jobId, 2,
                    BreedingPreparedPopulationRegistry.UnitState.CANCELED);
            assertEquals(
                    List.of(
                            BreedingPreparedPopulationRegistry.UnitState.CANCELED,
                            BreedingPreparedPopulationRegistry.UnitState.RESERVED,
                            BreedingPreparedPopulationRegistry.UnitState.CANCELED,
                            BreedingPreparedPopulationRegistry.UnitState.RESERVED
                    ),
                    registry.states(jobId)
            );

            registry.cancelRemaining(jobId, "test-cleanup");
            awaitCanceled(batch, 1, 3);
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
        OwnerPopulationAdmissionCoordinator ownerCoordinator =
                new OwnerPopulationAdmissionCoordinator(ownerIndex, repository, health);
        CompanionPopulationAdmissionCoordinator combined =
                new CompanionPopulationAdmissionCoordinator(ownerCoordinator, claimService);
        ClaimProviderRegistry providers = new ClaimProviderRegistry(
                absentProbe(ClaimIntegrationProvider.QUESTLINES_CLAIMS, "questlines-claims"),
                absentProbe(ClaimIntegrationProvider.SIMPLE_CLAIMS, "simpleclaims")
        );
        BreedingPopulationAdmissionService service = new BreedingPopulationAdmissionService(
                new CompanionPopulationBatchAdmissionCoordinator(combined),
                ownerIndex,
                claimIndex,
                providers,
                new OwnerComponentMutationService(ownerCoordinator),
                new CompanionIdentityResolver()
        );
        return new Harness(queue, providers, service);
    }

    private static BreedingPreparedPopulationRegistry installedRegistry(
            Harness harness,
            PreparedBreedingPopulationBatch batch
    ) {
        BreedingPreparedPopulationRegistry registry = new BreedingPreparedPopulationRegistry();
        assertEquals(
                BreedingPreparedPopulationRegistry.InstallStatus.INSTALLED,
                registry.install(harness.scope, harness.jobId, harness.service, batch)
        );
        return registry;
    }

    private static PreparedBreedingPopulationBatch withAnotherBatchId(
            PreparedBreedingPopulationBatch source
    ) {
        PreparedCompanionPopulationBatch population = new PreparedCompanionPopulationBatch(
                UUID.randomUUID(),
                source.populationBatch().requestedCount(),
                source.populationBatch().admissions()
        );
        return new PreparedBreedingPopulationBatch(
                source.requestedCount(),
                source.attemptKey(),
                source.birthPlan(),
                population,
                source.children()
        );
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
                        "not installed in registry tests",
                        ClaimProviderGeneration.NONE
                );
            }
        };
    }

    private static void awaitCanceled(PreparedBreedingPopulationBatch batch, int... indexes)
            throws Exception {
        for (int index : indexes) {
            CompletableFuture<Boolean> cancellation = batch.populationBatch().admission(index)
                    .ownerAdmission().cancellationCompletion();
            assertNotNull(cancellation);
            assertTrue(cancellation.get(4, TimeUnit.SECONDS));
        }
    }

    private static void awaitCanceled(PreparedBreedingPopulationBatch batch) throws Exception {
        int[] indexes = new int[batch.admittedCount()];
        for (int index = 0; index < indexes.length; index++) {
            indexes[index] = index;
        }
        awaitCanceled(batch, indexes);
    }

    private static void awaitState(
            BreedingPreparedPopulationRegistry registry,
            UUID jobId,
            int unitIndex,
            BreedingPreparedPopulationRegistry.UnitState expected
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<BreedingPreparedPopulationRegistry.UnitState> states = registry.states(jobId);
            if (unitIndex < states.size() && states.get(unitIndex) == expected) {
                return;
            }
            Thread.onSpinWait();
        }
        assertEquals(expected, registry.states(jobId).get(unitIndex));
    }

    private static final class Harness implements AutoCloseable {
        private final PersistenceWriteQueue queue;
        private final ClaimProviderRegistry providers;
        private final BreedingPopulationAdmissionService service;
        private final Object scope = new Object();
        private final UUID jobId = UUID.randomUUID();

        private Harness(
                PersistenceWriteQueue queue,
                ClaimProviderRegistry providers,
                BreedingPopulationAdmissionService service
        ) {
            this.queue = queue;
            this.providers = providers;
            this.service = service;
        }

        private PreparedBreedingPopulationBatch prepare(int count) throws Exception {
            UUID ownerId = UUID.randomUUID();
            String attempt = "breeding-registry:" + UUID.randomUUID();
            List<BreedingBirthPlanSnapshot.PlannedChild> durableChildren = new ArrayList<>();
            List<BreedingPopulationAdmissionRequest.PlannedChild> requestedChildren =
                    new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String childKey = "child-" + index;
                durableChildren.add(new BreedingBirthPlanSnapshot.PlannedChild(
                        childKey,
                        "role-baby",
                        index,
                        "role-adult",
                        null,
                        false,
                        null,
                        null,
                        ownerId,
                        "Registry Owner",
                        "registry-test"
                ));
                requestedChildren.add(new BreedingPopulationAdmissionRequest.PlannedChild(
                        childKey,
                        ownerId,
                        "Registry Owner"
                ));
            }
            BreedingBirthPlanSnapshot plan = new BreedingBirthPlanSnapshot(
                    1.0,
                    1.0,
                    count,
                    count,
                    durableChildren
            );
            BreedingPopulationAdmissionRequest request = new BreedingPopulationAdmissionRequest(
                    WORLD,
                    0,
                    0,
                    requestedChildren,
                    count,
                    true,
                    attempt,
                    plan,
                    List.of("parent-a", "parent-b")
            );
            BreedingPopulationPreparationResult result = service.prepareAsync(request)
                    .get(4, TimeUnit.SECONDS);
            assertTrue(result.allowed(), result.reason());
            assertEquals(count, result.admittedCount());
            assertNotNull(result.preparedBatch());
            return result.preparedBatch();
        }

        @Override
        public void close() {
            providers.close();
            queue.close();
        }
    }
}

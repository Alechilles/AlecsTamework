package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that a post-live partial commit never frees owner capacity. */
class CompanionPopulationCommitFailureTest {
    private static final String WORLD = "default";
    private static final long SETTINGS_REVISION = 31L;

    @TempDir
    Path tempDir;

    @Test
    void claimCommitFailureStillCommitsOwnerAndDegradesBothAuthorities() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("partial.sqlite"));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceHealthService health = new PersistenceHealthService();
        try (PersistenceWriteQueue queue = new PersistenceWriteQueue(connections, health, null)) {
            OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
            ownerIndex.replaceCommittedEntries(List.of(), OwnerPopulationReadiness.READY);
            ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
            claimIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.READY);
            ClaimAdmissionService claimService = new ClaimAdmissionService(claimIndex);
            CompanionPopulationAdmissionCoordinator coordinator =
                    new CompanionPopulationAdmissionCoordinator(
                            new OwnerPopulationAdmissionCoordinator(
                                    ownerIndex,
                                    new CompanionPopulationRepository(connections, queue),
                                    health
                            ),
                            claimService
                    );
            ClaimPolicyContext policy = offPolicy();
            String profileId = UUID.randomUUID().toString();
            UUID npcId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            CompanionPopulationPreparationResult result = coordinator.prepareAsync(
                    ownerPlan(profileId, npcId, ownerId),
                    claimRequest(profileId, ownerId, policy),
                    new ClaimLookupSession(policy)
            ).get(4, TimeUnit.SECONDS);

            assertTrue(result.allowed());
            PreparedCompanionPopulationAdmission prepared = result.preparedAdmission();
            assertTrue(coordinator.claimForApply(
                    prepared,
                    SETTINGS_REVISION,
                    new ClaimLookupSession(policy)
            ));
            assertTrue(claimService.cancel(prepared.claimReservation()));

            CompanionPopulationCommitResult commit = coordinator.commitAsync(prepared)
                    .get(4, TimeUnit.SECONDS);

            assertFalse(commit.committed());
            assertEquals("companion-claim-index-commit-failed", commit.reason());
            assertTrue(ownerIndex.entry(profileId).isPresent());
            assertEquals(ownerId, ownerIndex.entry(profileId).orElseThrow().ownerId());
            assertEquals(OwnerPopulationReadiness.DEGRADED, ownerIndex.readiness());
            assertEquals(ClaimOccupancyReadiness.DEGRADED, claimIndex.readiness());
            assertFalse(health.isHealthy());
        }
    }

    private static OwnerPopulationAdmissionPlan ownerPlan(String profileId,
                                                           UUID npcId,
                                                           UUID ownerId) {
        long now = System.currentTimeMillis();
        CompanionPopulationStateRecord baseline = new CompanionPopulationStateRecord(
                profileId, npcId, null, WORLD, WORLD, CompanionLifecycleState.ACTIVE.name(),
                WORLD, 0, 0, 0L, "partial_commit_test", now, now
        );
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                ownerId,
                WORLD,
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.NEW_OWNERSHIP,
                OwnerPopulationLimitScope.GLOBAL,
                10,
                false
        );
        return new OwnerPopulationAdmissionPlan(
                transition, baseline, npcId, WORLD, 0, 0, "partial_commit_test",
                "{\"ownerUuid\":null}",
                "{\"ownerUuid\":\"" + ownerId + "\"}",
                "{\"world\":\"" + WORLD + "\"}",
                SETTINGS_REVISION,
                ClaimProviderGeneration.NONE
        );
    }

    private static ClaimAdmissionRequest claimRequest(String profileId,
                                                       UUID ownerId,
                                                       ClaimPolicyContext policy) {
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate(WORLD, 0, 0);
        ClaimOccupancyTransition transition = new ClaimOccupancyTransition(
                null,
                new ClaimOccupancyEntry(
                        profileId, ownerId, CompanionLifecycleState.ACTIVE, destination, 1L
                )
        );
        return new ClaimAdmissionRequest(
                ClaimAdmissionOperation.TAME,
                List.of(transition),
                destination,
                policy,
                0,
                0,
                false,
                false,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
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
}

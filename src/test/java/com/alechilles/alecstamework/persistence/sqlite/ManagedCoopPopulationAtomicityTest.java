package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationReleaseCommitRequest;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.ReleaseRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies population journals and schema-v5 managed-coop state share one SQLite commit. */
class ManagedCoopPopulationAtomicityTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("default", 10, 20, 30);
    private static final String COOP_ID = "coop_chicken";
    private static final String ROLE_ID = "mob_chicken";

    @TempDir
    Path tempDir;

    @Test
    void captureCommitsPopulationResidentLifecycleAndJournalTogether() throws Exception {
        try (Harness harness = harness("capture.sqlite")) {
            UUID sourceUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            CaptureRequest capture = capture(profileId, sourceUuid, 0, 0L, 100L);

            prepareApplying(
                    harness,
                    "population-capture",
                    baseline(profileId, sourceUuid, ownerUuid, "ACTIVE", 0L),
                    ManagedCoopPopulationMutationContext.captureExtensionJson(capture)
            );

            PopulationPersistenceTransition.Result result = await(harness.population().commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "population-capture",
                            profileId,
                            0L,
                            ProfileOwnerMutation.unchanged(),
                            sourceUuid,
                            "default",
                            "COOP",
                            null,
                            null,
                            null,
                            "coop_capture"
                    )
            ));

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED, result.status());
            CompanionPopulationStateRecord population = harness.population().loadAllStates().getFirst();
            assertEquals("COOP", population.lifecycleState());
            assertEquals(1L, population.revision());
            assertNull(population.physicalWorldName());
            ResidentRecord resident = harness.residents().loadById(capture.residentId());
            assertNotNull(resident);
            assertEquals(ResidentState.HOUSED, resident.state());
            assertEquals(sourceUuid, resident.sourceNpcUuid());
            OperationRecord operation = harness.lifecycle().load(capture.operationId());
            assertNotNull(operation);
            assertEquals(OperationState.SLOT_COMMITTED, operation.state());
            assertEquals(1L, operation.generation());
            assertEquals("COMMITTED", populationOperationState(
                    harness.connections(), "population-capture"));
        }
    }

    @Test
    void journalFailureRollsBackPopulationAndBothManagedCoopRows() throws Exception {
        try (Harness harness = harness("capture-rollback.sqlite")) {
            UUID sourceUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            CaptureRequest capture = capture(profileId, sourceUuid, 0, 0L, 100L);
            prepareApplying(
                    harness,
                    "population-capture",
                    baseline(profileId, sourceUuid, null, "ACTIVE", 0L),
                    ManagedCoopPopulationMutationContext.captureExtensionJson(capture)
            );
            try (Connection connection = harness.connections().openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_population_commit
                        BEFORE UPDATE OF state ON companion_population_operations
                        WHEN NEW.operation_id = 'population-capture' AND NEW.state = 'COMMITTED'
                        BEGIN
                            SELECT RAISE(ABORT, 'simulated journal failure');
                        END
                        """);
            }

            PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome =
                    harness.population().commitAsync(new PopulationPersistenceTransition.Commit(
                            "population-capture",
                            profileId,
                            0L,
                            ProfileOwnerMutation.unchanged(),
                            sourceUuid,
                            "default",
                            "COOP",
                            null,
                            null,
                            null,
                            "coop_capture"
                    )).completion().get(3, TimeUnit.SECONDS);

            assertFalse(outcome.isCommitted());
            CompanionPopulationStateRecord population = harness.population().loadAllStates().getFirst();
            assertEquals("ACTIVE", population.lifecycleState());
            assertEquals(0L, population.revision());
            assertNull(harness.residents().loadById(capture.residentId()));
            assertNull(harness.lifecycle().load(capture.operationId()));
            assertEquals("APPLYING", populationOperationState(
                    harness.connections(), "population-capture"));
        }
    }

    @Test
    void staleCaptureSlotRollsBackPopulationTransition() throws Exception {
        try (Harness harness = harness("capture-conflict.sqlite")) {
            UUID sourceUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            CaptureRequest capture = capture(profileId, sourceUuid, 0, 0L, 100L);
            prepareApplying(
                    harness,
                    "population-capture",
                    baseline(profileId, sourceUuid, null, "ACTIVE", 0L),
                    ManagedCoopPopulationMutationContext.captureExtensionJson(capture)
            );

            UUID competingUuid = UUID.randomUUID();
            String competingProfile = UUID.randomUUID().toString();
            await(harness.population().prepareAsync(prepare(
                    "competing-profile",
                    baseline(competingProfile, competingUuid, null, "ACTIVE", 0L),
                    "{\"test\":true}"
            )));
            ManagedCoopResidentRepository.MutationResult occupied = await(
                    harness.residents().claimHoused(new ManagedCoopResidentRepository.HousedResidentClaim(
                            "competing-resident",
                            AUTHORITY,
                            COOP_ID,
                            0,
                            competingProfile,
                            ROLE_ID,
                            competingUuid,
                            "{\"version\":1}",
                            "competing-hash",
                            1,
                            90L
                    ))
            );
            assertTrue(occupied.succeeded());

            PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome =
                    harness.population().commitAsync(new PopulationPersistenceTransition.Commit(
                            "population-capture",
                            profileId,
                            0L,
                            ProfileOwnerMutation.unchanged(),
                            sourceUuid,
                            "default",
                            "COOP",
                            null,
                            null,
                            null,
                            "coop_capture"
                    )).completion().get(3, TimeUnit.SECONDS);

            assertFalse(outcome.isCommitted());
            CompanionPopulationStateRecord original = harness.population().loadAllStates().stream()
                    .filter(row -> row.profileId().equals(profileId))
                    .findFirst()
                    .orElseThrow();
            assertEquals("ACTIVE", original.lifecycleState());
            assertEquals(0L, original.revision());
            assertNull(harness.lifecycle().load(capture.operationId()));
            assertEquals("competing-resident", harness.residents()
                    .loadActiveSlot(AUTHORITY, 0).residentId());
        }
    }

    @Test
    void releaseCommitsExactProjectionPopulationResidentAndBothJournalsTogether() throws Exception {
        try (Harness harness = harness("release.sqlite")) {
            UUID sourceUuid = UUID.randomUUID();
            UUID targetUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            CaptureRequest capture = capture(profileId, sourceUuid, 0, 0L, 100L);
            prepareApplying(
                    harness,
                    "population-capture",
                    baseline(profileId, sourceUuid, ownerUuid, "ACTIVE", 0L),
                    ManagedCoopPopulationMutationContext.captureExtensionJson(capture)
            );
            assertTrue(await(harness.population().commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "population-capture", profileId, 0L,
                            ProfileOwnerMutation.unchanged(), sourceUuid,
                            "default", "COOP", null, null, null, "coop_capture"
                    ))).isSuccess());
            MutationResult retireRequested = await(harness.lifecycle().requestCaptureSourceRetirement(
                    capture.operationId(), 1L, 110L
            ));
            assertTrue(retireRequested.succeeded());
            assertTrue(await(harness.lifecycle().completeCapture(
                    capture.operationId(), retireRequested.operation().generation(), 120L
            )).succeeded());

            ResidentRecord housed = harness.residents().loadById(capture.residentId());
            ReleaseRequest release = new ReleaseRequest(
                    "managed-release",
                    housed.residentId(),
                    AUTHORITY,
                    COOP_ID,
                    0,
                    profileId,
                    targetUuid,
                    housed.snapshotHash(),
                    housed.generation(),
                    200L
            );
            MutationResult preparedRelease = await(harness.lifecycle().prepareRelease(release));
            assertTrue(preparedRelease.succeeded());
            MutationResult spawnClaim = await(harness.lifecycle().claimReleaseSpawn(
                    release.operationId(), preparedRelease.operation().generation(), 210L
            ));
            assertEquals(OperationState.SPAWN_CLAIMED, spawnClaim.operation().state());

            CompanionPopulationStateRecord cooped = harness.population().loadAllStates().getFirst();
            PopulationReleaseCommitRequest releaseCommit = new PopulationReleaseCommitRequest(
                    release.operationId(),
                    release.residentId(),
                    release.authorityKey(),
                    release.coopId(),
                    release.residentSlot(),
                    release.profileId(),
                    targetUuid,
                    targetUuid,
                    release.snapshotHash(),
                    release.expectedResidentGeneration(),
                    spawnClaim.operation().generation(),
                    220L
            );
            prepareApplying(
                    harness,
                    "population-release",
                    cooped,
                    ManagedCoopPopulationMutationContext.releaseExtensionJson(releaseCommit)
            );

            PopulationPersistenceTransition.Result committedRelease = await(
                    harness.population().commitAsync(new PopulationPersistenceTransition.Commit(
                            "population-release", profileId, cooped.revision(),
                            ProfileOwnerMutation.unchanged(), targetUuid,
                            "default", "ACTIVE", "default", 1, 2, "coop_release"
                    ))
            );

            assertEquals(PopulationPersistenceTransition.ResultStatus.COMMITTED,
                    committedRelease.status());
            CompanionPopulationStateRecord active = harness.population().loadAllStates().getFirst();
            assertEquals(targetUuid, active.currentNpcUuid());
            assertEquals("ACTIVE", active.lifecycleState());
            assertEquals(2L, active.revision());
            ResidentRecord deployed = harness.residents().loadById(capture.residentId());
            assertEquals(ResidentState.DEPLOYED, deployed.state());
            assertEquals(targetUuid, deployed.deployedNpcUuid());
            OperationRecord finalized = harness.lifecycle().load(release.operationId());
            assertEquals(OperationState.FINALIZED, finalized.state());
            assertFalse(finalized.active());
            assertEquals("COMMITTED", populationOperationState(
                    harness.connections(), "population-release"));

            PopulationPersistenceTransition.Result retry = await(
                    harness.population().commitAsync(new PopulationPersistenceTransition.Commit(
                            "population-release", profileId, cooped.revision(),
                            ProfileOwnerMutation.unchanged(), targetUuid,
                            "default", "ACTIVE", "default", 1, 2, "coop_release"
                    ))
            );
            assertEquals(PopulationPersistenceTransition.ResultStatus.IDEMPOTENT, retry.status());
            assertEquals(targetUuid, harness.residents().loadById(capture.residentId()).deployedNpcUuid());
        }
    }

    @Test
    void releaseJournalFailureRollsBackPopulationResidentAndLifecycleTogether() throws Exception {
        try (Harness harness = harness("release-rollback.sqlite")) {
            UUID sourceUuid = UUID.randomUUID();
            UUID targetUuid = UUID.randomUUID();
            String profileId = UUID.randomUUID().toString();
            CaptureRequest capture = capture(profileId, sourceUuid, 0, 0L, 100L);
            prepareApplying(
                    harness,
                    "population-capture",
                    baseline(profileId, sourceUuid, null, "ACTIVE", 0L),
                    ManagedCoopPopulationMutationContext.captureExtensionJson(capture)
            );
            assertTrue(await(harness.population().commitAsync(
                    new PopulationPersistenceTransition.Commit(
                            "population-capture", profileId, 0L,
                            ProfileOwnerMutation.unchanged(), sourceUuid,
                            "default", "COOP", null, null, null, "coop_capture"
                    ))).isSuccess());
            MutationResult retire = await(harness.lifecycle().requestCaptureSourceRetirement(
                    capture.operationId(), 1L, 110L
            ));
            assertTrue(retire.succeeded());
            assertTrue(await(harness.lifecycle().completeCapture(
                    capture.operationId(), retire.operation().generation(), 120L
            )).succeeded());

            ResidentRecord housed = harness.residents().loadById(capture.residentId());
            String populationOperationId = "population-release-reservation";
            ReleaseRequest release = new ReleaseRequest(
                    "population-release-rollback", housed.residentId(), AUTHORITY, COOP_ID, 0,
                    profileId, targetUuid, housed.snapshotHash(), housed.generation(), 200L
            );
            MutationResult prepared = await(harness.lifecycle().prepareRelease(release));
            assertTrue(prepared.succeeded());
            assertFalse(release.operationId().equals(populationOperationId));
            MutationResult claimed = await(harness.lifecycle().claimReleaseSpawn(
                    release.operationId(), prepared.operation().generation(), 210L
            ));
            assertEquals(OperationState.SPAWN_CLAIMED, claimed.operation().state());

            CompanionPopulationStateRecord cooped = harness.population().loadAllStates().getFirst();
            PopulationReleaseCommitRequest releaseCommit = new PopulationReleaseCommitRequest(
                    release.operationId(), release.residentId(), release.authorityKey(),
                    release.coopId(), release.residentSlot(), release.profileId(), targetUuid,
                    targetUuid, release.snapshotHash(), release.expectedResidentGeneration(),
                    claimed.operation().generation(), 220L
            );
            prepareApplying(
                    harness,
                    populationOperationId,
                    cooped,
                    ManagedCoopPopulationMutationContext.releaseExtensionJson(releaseCommit)
            );
            MutationResult unsafeRollback = await(
                    harness.lifecycle().failReleaseBeforeProjection(
                            release.operationId(), claimed.operation().generation(),
                            "retry-validation-failed", 215L
                    )
            );
            assertFalse(unsafeRollback.succeeded());
            assertEquals("release_population_operation_may_be_in_flight",
                    unsafeRollback.detail());
            assertEquals(ResidentState.RELEASING,
                    harness.residents().loadById(capture.residentId()).state());
            assertEquals(OperationState.SPAWN_CLAIMED,
                    harness.lifecycle().load(release.operationId()).state());
            try (Connection connection = harness.connections().openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_release_population_commit
                        BEFORE UPDATE OF state ON companion_population_operations
                        WHEN NEW.operation_id = '%s'
                          AND NEW.state = 'COMMITTED'
                        BEGIN
                            SELECT RAISE(ABORT, 'simulated release journal failure');
                        END
                        """.formatted(populationOperationId));
            }

            PersistenceWriteQueue.WriteOutcome<PopulationPersistenceTransition.Result> outcome =
                    harness.population().commitAsync(new PopulationPersistenceTransition.Commit(
                            populationOperationId, profileId, cooped.revision(),
                            ProfileOwnerMutation.unchanged(), targetUuid,
                            "default", "ACTIVE", "default", 1, 2, "coop_release"
                    )).completion().get(3, TimeUnit.SECONDS);

            assertFalse(outcome.isCommitted());
            CompanionPopulationStateRecord unchanged = harness.population().loadAllStates().getFirst();
            assertEquals("COOP", unchanged.lifecycleState());
            assertEquals(sourceUuid, unchanged.currentNpcUuid());
            assertEquals(cooped.revision(), unchanged.revision());
            ResidentRecord releasing = harness.residents().loadById(capture.residentId());
            assertEquals(ResidentState.RELEASING, releasing.state());
            assertNull(releasing.deployedNpcUuid());
            OperationRecord lifecycle = harness.lifecycle().load(release.operationId());
            assertEquals(OperationState.SPAWN_CLAIMED, lifecycle.state());
            assertTrue(lifecycle.active());
            assertEquals("APPLYING", populationOperationState(
                    harness.connections(), populationOperationId));
        }
    }

    private Harness harness(String fileName) throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve(fileName));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null
        );
        ManagedCoopResidentRepository residents = new ManagedCoopResidentRepository(
                connections, queue
        );
        CoopLifecycleOperationRepository lifecycle = new CoopLifecycleOperationRepository(
                connections, queue, residents
        );
        CompanionPopulationRepository population = new CompanionPopulationRepository(
                connections, queue, lifecycle
        );
        ManagedCoopResidentRepository.MutationResult authority = await(
                residents.registerAuthority(
                        AUTHORITY, COOP_ID, AuthorityState.TWORK_MANAGED, 1L
                )
        );
        assertTrue(authority.succeeded());
        return new Harness(connections, queue, residents, lifecycle, population);
    }

    private static CaptureRequest capture(String profileId,
                                          UUID sourceUuid,
                                          int slot,
                                          long expectedResidentGeneration,
                                          long nowMs) {
        String snapshot = ("{\"version\":\"1\",\"npcUuid\":\"%s\","
                + "\"coopId\":\"%s\",\"roleId\":\"%s\",\"residentSlot\":%d}")
                .formatted(sourceUuid, COOP_ID, ROLE_ID, slot);
        String snapshotHash = ManagedCoopCaptureClaimValidator.snapshotSha256(snapshot);
        String residentId = ManagedCoopCaptureClaimValidator.residentId(profileId);
        CaptureRequest provisional = new CaptureRequest(
                "pending", residentId, AUTHORITY, COOP_ID, slot, profileId, ROLE_ID,
                sourceUuid, snapshot, snapshotHash, 1, expectedResidentGeneration, nowMs
        );
        return new CaptureRequest(
                ManagedCoopCaptureClaimValidator.operationId(provisional),
                provisional.residentId(),
                provisional.authorityKey(),
                provisional.coopId(),
                provisional.residentSlot(),
                provisional.profileId(),
                provisional.roleId(),
                provisional.sourceNpcUuid(),
                provisional.snapshotJson(),
                provisional.snapshotHash(),
                provisional.snapshotVersion(),
                provisional.expectedResidentGeneration(),
                provisional.nowMs()
        );
    }

    private static void prepareApplying(Harness harness,
                                        String operationId,
                                        CompanionPopulationStateRecord baseline,
                                        String context) throws Exception {
        assertTrue(await(harness.population().prepareAsync(
                prepare(operationId, baseline, context)
        )).isSuccess());
        assertTrue(await(harness.population().advanceOperationAsync(
                operationId,
                CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.APPLYING,
                null
        )));
    }

    private static PopulationPersistenceTransition.Prepare prepare(
            String operationId,
            CompanionPopulationStateRecord baseline,
            String context) {
        long now = System.currentTimeMillis();
        return new PopulationPersistenceTransition.Prepare(
                new CompanionPopulationOperationRecord(
                        operationId,
                        baseline.profileId(),
                        "LIFECYCLE_CHANGE",
                        CompanionPopulationOperationRecord.State.PREPARED,
                        baseline.revision(),
                        "{\"state\":\"old\"}",
                        "{\"state\":\"new\"}",
                        context,
                        now,
                        now,
                        0L,
                        null
                ),
                baseline
        );
    }

    private static CompanionPopulationStateRecord baseline(
            String profileId,
            UUID currentUuid,
            UUID ownerUuid,
            String lifecycle,
            long revision) {
        long now = System.currentTimeMillis();
        boolean physical = "ACTIVE".equals(lifecycle) || "UNLOADED".equals(lifecycle);
        return new CompanionPopulationStateRecord(
                profileId,
                currentUuid,
                ownerUuid,
                "default",
                ownerUuid == null ? null : "default",
                lifecycle,
                physical ? "default" : null,
                physical ? 0 : null,
                physical ? 0 : null,
                revision,
                "test",
                now,
                now
        );
    }

    private static String populationOperationState(
            SqliteConnectionManager connections,
            String operationId) throws Exception {
        try (Connection connection = connections.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM companion_population_operations WHERE operation_id = ?"
             )) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static <T> T await(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        assertTrue(submission.accepted());
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(
                3, TimeUnit.SECONDS
        );
        if (outcome.status() != PersistenceWriteQueue.WriteStatus.COMMITTED) {
            throw new AssertionError(outcome.failureReason(), outcome.failure());
        }
        return outcome.value();
    }

    private record Harness(SqliteConnectionManager connections,
                           PersistenceWriteQueue queue,
                           ManagedCoopResidentRepository residents,
                           CoopLifecycleOperationRepository lifecycle,
                           CompanionPopulationRepository population) implements AutoCloseable {
        @Override
        public void close() {
            queue.close();
        }
    }
}

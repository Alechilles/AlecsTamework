package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.groups.PopulationGroupLifecycleClassifier;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for lease checkpointing and the slot-safe STORING boundary. */
class CommandTimedSummonRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void storageKeepsActiveCapacityUntilRosterStoredCommit() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("timed-storage.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String family = "test:dragon_horn";
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.ACTIVE.name(), "world", 1L);
            insertMembership(harness, owner, family, profile);
            CommandTimedSummonRepository repository =
                    new CommandTimedSummonRepository(harness.connections, harness.queue);
            CommandTimedSummonPolicySnapshot policy =
                    new CommandTimedSummonPolicySnapshot(1_000L, 500L, true, new long[] { 500L, 100L });
            CommandTimedSummonSessionRecord active = new CommandTimedSummonSessionRecord(
                    owner, family, profile, 1L, CommandTimedSummonSessionRecord.State.ACTIVE,
                    "session-1", 1_000L, 0L, "Dragon", 4L, policy, Set.of(), 100L,
                    null, 100L, 100L);
            assertEquals(CommandTimedSummonRepository.Status.CREATED,
                    HydragonPersistenceTestHarness.await(repository.createSessionAsync(active)).status());

            CommandTimedSummonRepository.MutationResult checkpoint = HydragonPersistenceTestHarness.await(
                    repository.checkpointAsync(new CommandTimedSummonRepository.CheckpointMutation(
                            owner, family, profile, 1L, "session-1", 450L, Set.of(500L), 650L)));
            assertEquals(CommandTimedSummonRepository.Status.CHECKPOINTED, checkpoint.status());
            assertEquals(450L, checkpoint.session().summonRemainingMs());
            assertEquals(Set.of(500L), checkpoint.session().emittedWarningThresholdsMs());

            CommandTimedSummonOperationRecord operation = operation(
                    owner, family, profile, checkpoint.session(), "store-1", "store-key-1",
                    CommandTimedSummonOperationRecord.Kind.STORE,
                    CommandTimedSummonSessionRecord.State.ROSTER_STORED);
            assertEquals(CommandTimedSummonRepository.Status.PREPARED,
                    HydragonPersistenceTestHarness.await(repository.prepareAsync(operation)).status());
            CommandTimedSummonRepository.MutationResult claimed = HydragonPersistenceTestHarness.await(
                    repository.claimAsync(new CommandTimedSummonRepository.ClaimMutation(
                            "store-1", "session-1", 400L, "Dragon", 4L, policy, 700L)));
            assertEquals(CommandTimedSummonSessionRecord.State.STORING, claimed.session().state());
            assertTrue(claimed.session().state().occupiesActiveCapacity());
            assertTrue(PopulationGroupLifecycleClassifier.consumesActive(CompanionLifecycleState.STORING));

            CommandTimedSummonRepository.MutationResult committed = HydragonPersistenceTestHarness.await(
                    repository.commitAsync(new CommandTimedSummonRepository.CommitMutation(
                            "store-1", CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                            1_200L, "lease-expired", 700L)));
            assertEquals(CommandTimedSummonRepository.Status.COMMITTED, committed.status());
            assertEquals(CommandTimedSummonSessionRecord.State.ROSTER_STORED, committed.session().state());
            assertFalse(committed.session().state().occupiesActiveCapacity());
            assertNull(committed.session().summonSessionId());
            assertEquals(1_200L, committed.session().resummonCooldownUntilMs());

            CommandTimedSummonRepository.MutationResult replay = HydragonPersistenceTestHarness.await(
                    repository.commitAsync(new CommandTimedSummonRepository.CommitMutation(
                            "store-1", CommandTimedSummonSessionRecord.State.ROSTER_STORED,
                            1_200L, "lease-expired", 700L)));
            assertEquals(CommandTimedSummonRepository.Status.IDEMPOTENT, replay.status());
        }
    }

    @Test
    void unloadedLeaseContinuesToExpireWithoutReplenishment() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("timed-unloaded.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String family = "test:dragon_horn";
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.ACTIVE.name(), "world", 1L);
            insertMembership(harness, owner, family, profile);
            CommandTimedSummonRepository repository =
                    new CommandTimedSummonRepository(harness.connections, harness.queue);
            CommandTimedSummonPolicySnapshot policy =
                    new CommandTimedSummonPolicySnapshot(1_000L, 0L, true, new long[0]);
            HydragonPersistenceTestHarness.await(repository.createSessionAsync(
                    new CommandTimedSummonSessionRecord(
                            owner, family, profile, 1L, CommandTimedSummonSessionRecord.State.ACTIVE,
                            "session-2", 1_000L, 0L, null, null, policy, Set.of(), 100L,
                            null, 100L, 100L)));

            CommandTimedSummonRepository.MutationResult unloaded = HydragonPersistenceTestHarness.await(
                    repository.setProjectionAvailabilityAsync(
                            new CommandTimedSummonRepository.ProjectionAvailabilityMutation(
                                    owner, family, profile, 1L, "session-2",
                                    CommandTimedSummonSessionRecord.State.UNLOADED, 400L)));
            assertEquals(CommandTimedSummonSessionRecord.State.UNLOADED, unloaded.session().state());
            assertEquals(700L, unloaded.session().summonRemainingMs());
            assertTrue(unloaded.session().state().occupiesActiveCapacity());

            CommandTimedSummonRepository.MutationResult expired = HydragonPersistenceTestHarness.await(
                    repository.checkpointAsync(new CommandTimedSummonRepository.CheckpointMutation(
                            owner, family, profile, unloaded.session().rowRevision(), "session-2",
                            0L, Set.of(), 1_100L)));
            assertEquals(0L, expired.session().summonRemainingMs());
            assertEquals(CommandTimedSummonSessionRecord.State.UNLOADED, expired.session().state());
        }
    }

    private static CommandTimedSummonOperationRecord operation(
            UUID owner, String family, String profile, CommandTimedSummonSessionRecord session,
            String operationId, String key, CommandTimedSummonOperationRecord.Kind kind,
            CommandTimedSummonSessionRecord.State resultState) {
        return new CommandTimedSummonOperationRecord(
                operationId, "test", key, owner, family, profile, kind,
                CommandTimedSummonOperationRecord.OperationState.PREPARED, session.state(),
                session.rowRevision(), 1L, "population-op", UUID.randomUUID(), null,
                session.summonSessionId(), resultState, null, 650L, 650L, 0L);
    }

    private static void insertMembership(HydragonPersistenceTestHarness harness,
                                         UUID owner, String family, String profile) throws Exception {
        try (Connection connection = harness.connections.openConnection();
             PreparedStatement roster = connection.prepareStatement("""
                     INSERT INTO command_family_rosters
                         (owner_uuid, command_family_id, row_revision, created_at_ms, updated_at_ms)
                     VALUES (?, ?, 1, 1, 1)
                     """);
             PreparedStatement membership = connection.prepareStatement("""
                     INSERT INTO command_family_roster_memberships
                         (owner_uuid, command_family_id, profile_id, active, created_at_ms, updated_at_ms)
                     VALUES (?, ?, ?, 1, 1, 1)
                     """)) {
            roster.setString(1, owner.toString());
            roster.setString(2, family);
            roster.executeUpdate();
            membership.setString(1, owner.toString());
            membership.setString(2, family);
            membership.setString(3, profile);
            membership.executeUpdate();
        }
    }
}

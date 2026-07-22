package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaidCommandRevivalRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void freezesMultiItemCostAndReplaysNamespacedIdempotency() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("paid-revival.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.DEAD_REVIVABLE.name(), "world", 7L);
            PaidCommandRevivalRepository repository =
                    new PaidCommandRevivalRepository(harness.connections, harness.queue);
            PaidCommandRevivalRecord requested = operation(owner, profile);

            var prepared = HydragonPersistenceTestHarness.await(repository.prepareAsync(requested));
            assertEquals(PaidCommandRevivalRepository.Status.APPLIED, prepared.status());
            assertEquals(List.of(
                    new ItemCostComponentView("Life_Essence", 2),
                    new ItemCostComponentView("Gold_Bar", 7)), prepared.operation().exactCost());

            PaidCommandRevivalRecord replay = repository.findByIdempotency("test", "revive-1");
            assertNotNull(replay);
            assertEquals(requested.operationId(), replay.operationId());
            assertEquals(PaidCommandRevivalRepository.Status.IDEMPOTENT,
                    HydragonPersistenceTestHarness.await(repository.prepareAsync(requested)).status());
        }
    }

    @Test
    void persistsSplitStackReservationsAndExactRefundClaim() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("paid-refund.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.DEAD_REVIVABLE.name(), "world", 7L);
            PaidCommandRevivalRepository repository =
                    new PaidCommandRevivalRepository(harness.connections, harness.queue);
            PaidCommandRevivalRecord operation = operation(owner, profile);
            HydragonPersistenceTestHarness.await(repository.prepareAsync(operation));

            List<PaidCommandRevivalRecord.Reservation> reservations = List.of(
                    reservation(0, 0, "backpack", 1, 1, "stack-a"),
                    reservation(0, 1, "storage", 2, 1, "stack-b"),
                    reservation(1, 0, "hotbar", 3, 7, "stack-c"));
            var reserved = HydragonPersistenceTestHarness.await(
                    repository.reserveAsync(operation.operationId(), reservations, 20L));
            assertEquals(PaidCommandRevivalRecord.State.RESERVED, reserved.operation().state());
            assertEquals(reservations, reserved.operation().reservations());

            var consumed = HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.RESERVED,
                    PaidCommandRevivalRecord.State.COST_CONSUMED, null, 30L));
            var applying = HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.COST_CONSUMED,
                    PaidCommandRevivalRecord.State.APPLYING, null, 40L));
            assertEquals(PaidCommandRevivalRecord.State.APPLYING, applying.operation().state());
            var refund = HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.APPLYING,
                    PaidCommandRevivalRecord.State.REFUND_REQUIRED, "projection-failed", 50L));
            assertEquals(PaidCommandRevivalRecord.State.REFUND_REQUIRED, refund.operation().state());
            assertEquals(3, refund.operation().reservations().size());
            assertEquals(PaidCommandRevivalRecord.ReservationState.REFUND_REQUIRED,
                    refund.operation().reservations().get(0).state());
            assertEquals(PaidCommandRevivalRecord.State.COST_CONSUMED, consumed.operation().state());
            assertEquals(PaidCommandRevivalRepository.RefundDeliveryStatus.PENDING,
                    repository.findRefundDeliveryStatus(operation.operationId()));
            assertEquals(PaidCommandRevivalRepository.RefundDeliveryStatus.STARTED,
                    HydragonPersistenceTestHarness.await(repository.beginRefundDeliveryAsync(
                            operation.operationId(), 60L)));
            assertEquals(PaidCommandRevivalRepository.RefundDeliveryStatus.DELIVERING,
                    repository.findRefundDeliveryStatus(operation.operationId()));
        }
    }

    @Test
    void atomicallyCommitsProjectionLeaseDeathRosterAndPaidOperation() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("paid-atomic-apply.sqlite"))) {
            UUID owner = UUID.randomUUID();
            UUID projection = UUID.randomUUID();
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.DEAD_REVIVABLE.name(), "world", 7L);
            seedRevivalSource(harness, owner, profile, projection, "DEAD_REVIVABLE");
            PaidCommandRevivalRepository repository =
                    new PaidCommandRevivalRepository(harness.connections, harness.queue);
            CommandTimedSummonPolicySnapshot policy = new CommandTimedSummonPolicySnapshot(
                    60_000L, 5_000L, true, new long[]{10_000L});
            PaidCommandRevivalRecord requested = operation(owner, profile, projection);
            PaidCommandRevivalApplyCommit.TimedLease lease =
                    new PaidCommandRevivalApplyCommit.TimedLease(
                            "revival-session:" + requested.operationId(), 60_000L,
                            "TestDragon", 4L, policy);
            PaidCommandRevivalRecord applying = prepareApplying(repository,
                    requested, lease);
            PaidCommandRevivalApplyCommit commit = new PaidCommandRevivalApplyCommit(
                    applying.operationId(), owner, "test:horn", profile, projection, 3L,
                    lease, 100L);

            PaidCommandRevivalRepository.MutationResult result =
                    HydragonPersistenceTestHarness.await(repository.commitAppliedAsync(commit));
            assertEquals(PaidCommandRevivalRepository.Status.APPLIED, result.status());
            assertEquals(PaidCommandRevivalRecord.State.SUCCEEDED, result.operation().state());
            assertEquals(PaidCommandRevivalRepository.Status.IDEMPOTENT,
                    HydragonPersistenceTestHarness.await(repository.commitAppliedAsync(commit)).status());
            PaidCommandRevivalApplyCommit changedPlan = new PaidCommandRevivalApplyCommit(
                    applying.operationId(), owner, "test:horn", profile, projection, 3L,
                    new PaidCommandRevivalApplyCommit.TimedLease(
                            "different-session", 60_000L, "TestDragon", 4L, policy), 101L);
            PaidCommandRevivalRepository.MutationResult rejectedReplay =
                    HydragonPersistenceTestHarness.await(repository.commitAppliedAsync(changedPlan));
            assertEquals(PaidCommandRevivalRepository.Status.CONFLICT, rejectedReplay.status());
            assertEquals("apply-plan-changed", rejectedReplay.reason());

            try (Connection connection = harness.connections.openConnection()) {
                assertEquals(0L, scalar(connection, """
                        SELECT COUNT(*) FROM npc_snapshots
                        WHERE profile_id = ? AND snapshot_type = 'death' AND is_active = 1
                        """, profile));
                assertEquals("ACTIVE", text(connection, """
                        SELECT command_state FROM command_family_roster_memberships
                        WHERE owner_uuid = ? AND command_family_id = 'test:horn' AND profile_id = ?
                        """, owner.toString(), profile));
                assertEquals("ACTIVE", text(connection, """
                        SELECT summon_state FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = 'test:horn' AND profile_id = ?
                        """, owner.toString(), profile));
                assertEquals("revival-session:" + applying.operationId(), text(connection, """
                        SELECT summon_session_id FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = 'test:horn' AND profile_id = ?
                        """, owner.toString(), profile));
                assertEquals(0L, scalar(connection,
                        "SELECT death_active FROM profile_states WHERE profile_id = ?", profile));
            }
        }
    }

    @Test
    void failedAtomicApplyRollsBackEveryPositiveWriteAndCanRetry() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("paid-atomic-rollback.sqlite"))) {
            UUID owner = UUID.randomUUID();
            UUID projection = UUID.randomUUID();
            String profile = harness.insertProfile(
                    owner, "TestDragon", CompanionLifecycleState.DEAD_REVIVABLE.name(), "world", 7L);
            // ACTIVE is deliberately not a valid revival source; the apply must commit nothing.
            seedRevivalSource(harness, owner, profile, projection, "ACTIVE");
            PaidCommandRevivalRepository repository =
                    new PaidCommandRevivalRepository(harness.connections, harness.queue);
            PaidCommandRevivalRecord requested = operation(owner, profile, projection);
            PaidCommandRevivalApplyCommit.TimedLease lease =
                    new PaidCommandRevivalApplyCommit.TimedLease(
                            "revival-session:" + requested.operationId(), 60_000L,
                            null, null, new CommandTimedSummonPolicySnapshot(
                            60_000L, 0L, true, new long[0]));
            PaidCommandRevivalRecord applying = prepareApplying(repository,
                    requested, lease);
            PaidCommandRevivalApplyCommit commit = new PaidCommandRevivalApplyCommit(
                    applying.operationId(), owner, "test:horn", profile, projection, 3L,
                    lease, 100L);

            PaidCommandRevivalRepository.MutationResult denied =
                    HydragonPersistenceTestHarness.await(repository.commitAppliedAsync(commit));
            assertEquals(PaidCommandRevivalRepository.Status.CONFLICT, denied.status());
            assertEquals("roster-revival-source-changed", denied.reason());
            assertEquals(PaidCommandRevivalRecord.State.APPLYING,
                    repository.find(applying.operationId()).state());
            try (Connection connection = harness.connections.openConnection()) {
                assertEquals(1L, scalar(connection, """
                        SELECT COUNT(*) FROM npc_snapshots
                        WHERE profile_id = ? AND snapshot_type = 'death' AND is_active = 1
                        """, profile));
                assertEquals("DEAD_REVIVABLE", text(connection, """
                        SELECT summon_state FROM command_timed_summon_sessions
                        WHERE owner_uuid = ? AND command_family_id = 'test:horn' AND profile_id = ?
                        """, owner.toString(), profile));
            }

            try (Connection connection = harness.connections.openConnection();
                 PreparedStatement update = connection.prepareStatement("""
                         UPDATE command_family_roster_memberships SET command_state = 'DEAD_REVIVABLE'
                         WHERE owner_uuid = ? AND command_family_id = 'test:horn' AND profile_id = ?
                         """)) {
                update.setString(1, owner.toString());
                update.setString(2, profile);
                update.executeUpdate();
            }
            assertEquals(PaidCommandRevivalRepository.Status.APPLIED,
                    HydragonPersistenceTestHarness.await(repository.commitAppliedAsync(commit)).status());
        }
    }

    private static PaidCommandRevivalRecord prepareApplying(PaidCommandRevivalRepository repository,
                                                             PaidCommandRevivalRecord operation,
                                                             PaidCommandRevivalApplyCommit.TimedLease lease)
            throws Exception {
        HydragonPersistenceTestHarness.await(repository.prepareAsync(operation));
        assertEquals(PaidCommandRevivalRepository.Status.APPLIED,
                HydragonPersistenceTestHarness.await(repository.recordActivationAsync(
                        operation.operationId(), "population-op", "placement-hash",
                        UUID.fromString(operation.reviveProjectionOperationId()), lease, 15L)).status());
        List<PaidCommandRevivalRecord.Reservation> reservations = List.of(
                reservation(0, 0, "backpack", 1, 2, "stack-a"),
                reservation(1, 0, "hotbar", 3, 7, "stack-b"));
        HydragonPersistenceTestHarness.await(
                repository.reserveAsync(operation.operationId(), reservations, 20L));
        HydragonPersistenceTestHarness.await(repository.transitionAsync(
                operation.operationId(), PaidCommandRevivalRecord.State.RESERVED,
                PaidCommandRevivalRecord.State.COST_CONSUMED, null, 30L));
        return HydragonPersistenceTestHarness.await(repository.transitionAsync(
                operation.operationId(), PaidCommandRevivalRecord.State.COST_CONSUMED,
                PaidCommandRevivalRecord.State.APPLYING, null, 40L)).operation();
    }

    private static void seedRevivalSource(HydragonPersistenceTestHarness harness, UUID owner,
                                          String profile, UUID projection, String rosterState)
            throws Exception {
        try (Connection connection = harness.connections.openConnection()) {
            try (PreparedStatement profileUpdate = connection.prepareStatement("""
                    UPDATE npc_profiles SET current_npc_uuid = ? WHERE profile_id = ?
                    """)) {
                profileUpdate.setString(1, projection.toString());
                profileUpdate.setString(2, profile);
                profileUpdate.executeUpdate();
            }
            try (PreparedStatement population = connection.prepareStatement("""
                    UPDATE companion_population_state
                    SET lifecycle_state = 'ACTIVE', revision = 8, physical_world_name = 'world',
                        physical_chunk_x = 1, physical_chunk_z = 2
                    WHERE profile_id = ?
                    """)) {
                population.setString(1, profile);
                population.executeUpdate();
            }
            try (PreparedStatement roster = connection.prepareStatement("""
                    INSERT INTO command_family_rosters(
                        owner_uuid, command_family_id, row_revision, created_at_ms, updated_at_ms)
                    VALUES (?, 'test:horn', 1, 1, 1)
                    """)) {
                roster.setString(1, owner.toString());
                roster.executeUpdate();
            }
            try (PreparedStatement membership = connection.prepareStatement("""
                    INSERT INTO command_family_roster_memberships(
                        owner_uuid, command_family_id, profile_id, role_id, profile_revision,
                        command_state, active_for_bulk_commands, created_at_ms, updated_at_ms)
                    VALUES (?, 'test:horn', ?, 'TestDragon', 7, ?, 1, 1, 1)
                    """)) {
                membership.setString(1, owner.toString());
                membership.setString(2, profile);
                membership.setString(3, rosterState);
                membership.executeUpdate();
            }
            try (PreparedStatement snapshot = connection.prepareStatement("""
                    INSERT INTO npc_snapshots(
                        profile_id, snapshot_type, snapshot_version, payload_json, is_active, created_at_ms)
                    VALUES (?, 'death', 3, '{}', 1, 1)
                    """)) {
                snapshot.setString(1, profile);
                snapshot.executeUpdate();
            }
            try (PreparedStatement states = connection.prepareStatement("""
                    INSERT INTO profile_states(
                        profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms)
                    VALUES (?, 0, 1, 0, 0, NULL, 1)
                    """)) {
                states.setString(1, profile);
                states.executeUpdate();
            }
            try (PreparedStatement timed = connection.prepareStatement("""
                    INSERT INTO command_timed_summon_sessions(
                        owner_uuid, command_family_id, profile_id, row_revision, summon_state,
                        summon_session_id, summon_remaining_ms, resummon_cooldown_until_ms,
                        summon_policy_json, warning_receipts_json, summon_last_checkpoint_at_ms,
                        active_operation_id, created_at_ms, updated_at_ms)
                    VALUES (?, 'test:horn', ?, 1, 'DEAD_REVIVABLE', NULL, NULL, 0,
                        '{}', '[]', NULL, NULL, 1, 1)
                    """)) {
                timed.setString(1, owner.toString());
                timed.setString(2, profile);
                timed.executeUpdate();
            }
        }
    }

    private static long scalar(Connection connection, String sql, String... arguments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setString(index + 1, arguments[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("Expected scalar row");
                return result.getLong(1);
            }
        }
    }

    private static String text(Connection connection, String sql, String... arguments) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setString(index + 1, arguments[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("Expected text row");
                return result.getString(1);
            }
        }
    }

    private static PaidCommandRevivalRecord operation(UUID owner, String profile) {
        return operation(owner, profile, UUID.randomUUID());
    }

    private static PaidCommandRevivalRecord operation(UUID owner, String profile, UUID projection) {
        return new PaidCommandRevivalRecord(
                UUID.randomUUID(), "test", "revive-1", owner, profile, "test:horn",
                "TestDragon", "TestDragon", "config-hash", 3L, 7L,
                "population-op", "placement-hash", projection.toString(),
                PaidCommandRevivalRecord.State.PREPARED,
                List.of(new ItemCostComponentView("Life_Essence", 2),
                        new ItemCostComponentView("Gold_Bar", 7)),
                List.of(), null, 10L, 10L, null);
    }

    private static PaidCommandRevivalRecord.Reservation reservation(
            int cost, int stack, String compartment, int slot, int quantity, String fingerprint) {
        return new PaidCommandRevivalRecord.Reservation(cost, stack, compartment, slot, quantity,
                fingerprint, 1L, PaidCommandRevivalRecord.ReservationState.HELD);
    }
}

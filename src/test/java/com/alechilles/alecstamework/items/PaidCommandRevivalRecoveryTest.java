package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.api.PaidCommandRevivalCostQuoteView;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuote;
import com.alechilles.alecstamework.api.PaidCommandRevivedEvent;
import com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRecord;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRepository;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaidCommandRevivalRecoveryTest {
    @TempDir Path tempDir;

    @Test
    void startupCancelsPreparedCheckpointWithoutPayment() throws Exception {
        try (Fixture fixture = fixture("prepared")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.PREPARED);

            fixture.await(fixture.coordinator.recoverStartup(16));

            assertEquals(PaidCommandRevivalRecord.State.CANCELED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(0, fixture.authority.consumeCalls);
            assertEquals(0, fixture.authority.cancelActivationCalls);
        }
    }

    @Test
    void ownerJoinResumesHeldReservedCheckpointExactlyOnce() throws Exception {
        try (Fixture fixture = fixture("reserved")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.RESERVED);
            fixture.authority.receiptEvidence = CommandReviveInventoryPaymentService.ReceiptEvidence.HELD;

            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));
            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));

            assertEquals(PaidCommandRevivalRecord.State.SUCCEEDED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(1, fixture.authority.consumeCalls);
            assertEquals(1, fixture.authority.applyCalls);
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void costConsumedCheckpointAppliesWithoutChargingAgain() throws Exception {
        try (Fixture fixture = fixture("cost-consumed")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.COST_CONSUMED);

            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));

            assertEquals(PaidCommandRevivalRecord.State.SUCCEEDED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(0, fixture.authority.consumeCalls);
            assertEquals(1, fixture.authority.applyCalls);
        }
    }

    @Test
    void applyingCheckpointUsesProjectionProofWithoutSpawningTwice() throws Exception {
        try (Fixture fixture = fixture("applying")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.APPLYING);
            fixture.authority.projectionEvidence = PaidCommandRevivalCoordinator.ProjectionEvidence.REVIVED;

            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));

            assertEquals(PaidCommandRevivalRecord.State.SUCCEEDED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(0, fixture.authority.applyCalls);
            assertEquals(1, fixture.events.size());
        }
    }

    @Test
    void refundRequiredCheckpointDeliversFrozenRecipeOnce() throws Exception {
        try (Fixture fixture = fixture("refund")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.REFUND_REQUIRED);

            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));
            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));

            assertEquals(PaidCommandRevivalRecord.State.REFUNDED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(1, fixture.authority.refundCalls);
            assertEquals(operation.exactCost(), fixture.authority.lastRefundCost);
        }
    }

    @Test
    void quarantinedCheckpointOnlyCommitsWithExactProjectionProof() throws Exception {
        try (Fixture fixture = fixture("quarantined")) {
            PaidCommandRevivalRecord operation = fixture.persist(PaidCommandRevivalRecord.State.QUARANTINED);
            fixture.authority.projectionEvidence = PaidCommandRevivalCoordinator.ProjectionEvidence.AMBIGUOUS;
            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));
            assertEquals(PaidCommandRevivalRecord.State.QUARANTINED,
                    fixture.repository.find(operation.operationId()).state());

            fixture.authority.projectionEvidence = PaidCommandRevivalCoordinator.ProjectionEvidence.REVIVED;
            fixture.await(fixture.coordinator.recoverOwner(fixture.owner, 16));

            assertEquals(PaidCommandRevivalRecord.State.SUCCEEDED,
                    fixture.repository.find(operation.operationId()).state());
            assertEquals(0, fixture.authority.applyCalls);
        }
    }

    private Fixture fixture(String name) throws Exception {
        return new Fixture(tempDir.resolve(name + ".sqlite"));
    }

    private static final class Fixture implements AutoCloseable {
        private final HydragonPersistenceTestHarness harness;
        private final UUID owner = UUID.randomUUID();
        private final String profile;
        private final PaidCommandRevivalRepository repository;
        private final FakeAuthority authority = new FakeAuthority();
        private final List<PaidCommandRevivedEvent> events = new ArrayList<>();
        private final PaidCommandRevivalCoordinator coordinator;

        private Fixture(Path database) throws Exception {
            harness = new HydragonPersistenceTestHarness(database);
            profile = harness.insertProfile(owner, "TestDragon",
                    CompanionLifecycleState.DEAD_REVIVABLE.name(), "world", 7L);
            repository = new PaidCommandRevivalRepository(harness.connections, harness.queue);
            seedDeadRevivalAuthority();
            authority.resolved = resolved(profile);
            authority.projectionProof = this::proveLiveProjection;
            coordinator = new PaidCommandRevivalCoordinator(
                    repository, harness.reads, authority, Clock.systemUTC(), events::add);
        }

        private PaidCommandRevivalRecord persist(PaidCommandRevivalRecord.State target) throws Exception {
            PaidCommandRevivalRecord operation = operation(owner, profile, target.name());
            HydragonPersistenceTestHarness.await(repository.prepareAsync(operation));
            if (target == PaidCommandRevivalRecord.State.PREPARED) return operation;
            HydragonPersistenceTestHarness.await(repository.recordActivationAsync(
                    operation.operationId(), "population-op", "placement-hash",
                    projectionUuid(profile), null, 15L));
            HydragonPersistenceTestHarness.await(repository.reserveAsync(
                    operation.operationId(), reservations(), 20L));
            if (target == PaidCommandRevivalRecord.State.RESERVED) return operation;
            HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.RESERVED,
                    PaidCommandRevivalRecord.State.COST_CONSUMED, null, 30L));
            if (target == PaidCommandRevivalRecord.State.COST_CONSUMED) return operation;
            HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.COST_CONSUMED,
                    PaidCommandRevivalRecord.State.APPLYING, null, 40L));
            if (target == PaidCommandRevivalRecord.State.APPLYING) return operation;
            HydragonPersistenceTestHarness.await(repository.transitionAsync(
                    operation.operationId(), PaidCommandRevivalRecord.State.APPLYING, target,
                    "restart-checkpoint", 50L));
            return operation;
        }

        private void seedDeadRevivalAuthority() throws Exception {
            try (Connection connection = harness.connections.openConnection()) {
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
                        VALUES (?, 'test:horn', ?, 'TestDragon', 7, 'DEAD_REVIVABLE', 1, 1, 1)
                        """)) {
                    membership.setString(1, owner.toString());
                    membership.setString(2, profile);
                    membership.executeUpdate();
                }
                try (PreparedStatement death = connection.prepareStatement("""
                        INSERT INTO npc_snapshots(
                            profile_id, snapshot_type, snapshot_version, payload_json,
                            is_active, created_at_ms)
                        VALUES (?, 'death', 3, '{}', 1, 1)
                        """)) {
                    death.setString(1, profile);
                    death.executeUpdate();
                }
                try (PreparedStatement state = connection.prepareStatement("""
                        INSERT INTO profile_states(
                            profile_id, capture_active, death_active, lost_active,
                            in_coop, coop_key, updated_at_ms)
                        VALUES (?, 0, 1, 0, 0, NULL, 1)
                        """)) {
                    state.setString(1, profile);
                    state.executeUpdate();
                }
            }
        }

        private void proveLiveProjection() {
            try (Connection connection = harness.connections.openConnection()) {
                try (PreparedStatement current = connection.prepareStatement("""
                        UPDATE npc_profiles SET current_npc_uuid = ?, updated_at_ms = 60
                        WHERE profile_id = ?
                        """)) {
                    current.setString(1, projectionUuid(profile).toString());
                    current.setString(2, profile);
                    current.executeUpdate();
                }
                try (PreparedStatement population = connection.prepareStatement("""
                        UPDATE companion_population_state
                        SET lifecycle_state = 'ACTIVE', revision = 8,
                            physical_world_name = 'world', physical_chunk_x = 1,
                            physical_chunk_z = 2, updated_at_ms = 60
                        WHERE profile_id = ?
                        """)) {
                    population.setString(1, profile);
                    population.executeUpdate();
                }
            } catch (Exception failure) {
                throw new AssertionError("Could not seed deterministic projection proof", failure);
            }
        }

        private <T> T await(CompletionStage<T> stage) {
            return stage.toCompletableFuture().join();
        }

        @Override
        public void close() throws Exception {
            harness.close();
        }
    }

    private static final class FakeAuthority implements PaidCommandRevivalCoordinator.Authority {
        private PaidCommandRevivalCoordinator.ResolvedRevival resolved;
        private CommandReviveInventoryPaymentService.ReceiptEvidence receiptEvidence =
                CommandReviveInventoryPaymentService.ReceiptEvidence.HELD;
        private PaidCommandRevivalCoordinator.ProjectionEvidence projectionEvidence =
                PaidCommandRevivalCoordinator.ProjectionEvidence.DEAD;
        private Runnable projectionProof = () -> { };
        private int consumeCalls;
        private int applyCalls;
        private int refundCalls;
        private int cancelActivationCalls;
        private List<ItemCostComponentView> lastRefundCost = List.of();

        @Override
        public CompletionStage<PaidCommandRevivalCoordinator.ResolvedRevival> resolve(
                UUID ownerUuid, String profileId, String commandFamilyId, boolean forCommit) {
            return CompletableFuture.completedFuture(resolved);
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.PlanResult> plan(
                PaidCommandRevivalCoordinator.ResolvedRevival resolved, UUID operationId) {
            return CompletableFuture.completedFuture(new CommandReviveInventoryPaymentService.PlanResult(
                    CommandReviveInventoryPaymentService.Status.READY, reservations(), null, 0));
        }

        @Override
        public CompletionStage<PaidCommandRevivalCoordinator.ActivationPreparation> prepareActivation(
                PaidCommandRevivalCoordinator.ResolvedRevival resolved, UUID operationId) {
            return CompletableFuture.completedFuture(new PaidCommandRevivalCoordinator.ActivationPreparation(
                    true, null, operationId.toString(), projectionUuid(resolved.profileId()), new Object()));
        }

        @Override
        public CompletionStage<Boolean> cancelActivation(
                PaidCommandRevivalRecord operation, Object runtimeHandle) {
            cancelActivationCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> consume(
                PaidCommandRevivalCoordinator.ResolvedRevival resolved, UUID operationId,
                List<ItemCostComponentView> exactCost,
                List<PaidCommandRevivalRecord.Reservation> reservations) {
            consumeCalls++;
            return completed(CommandReviveInventoryPaymentService.Status.CONSUMED);
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> hold(
                PaidCommandRevivalCoordinator.ResolvedRevival resolved, UUID operationId,
                List<PaidCommandRevivalRecord.Reservation> reservations) {
            return completed(CommandReviveInventoryPaymentService.Status.READY);
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.ReceiptEvidence> inspectReservation(
                PaidCommandRevivalRecord operation) {
            return CompletableFuture.completedFuture(receiptEvidence);
        }

        @Override
        public CompletionStage<Boolean> release(PaidCommandRevivalRecord operation) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<PaidCommandRevivalCoordinator.ApplyOutcome> apply(
                PaidCommandRevivalCoordinator.ResolvedRevival resolved,
                PaidCommandRevivalRecord operation,
                PaidCommandRevivalCoordinator.ActivationPreparation activation) {
            applyCalls++;
            projectionProof.run();
            return CompletableFuture.completedFuture(PaidCommandRevivalCoordinator.ApplyOutcome.projected(
                    projectionUuid(operation.profileId())));
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> refund(
                PaidCommandRevivalRecord operation) {
            refundCalls++;
            lastRefundCost = operation.exactCost();
            return completed(CommandReviveInventoryPaymentService.Status.REFUNDED);
        }

        @Override
        public CompletionStage<CommandReviveInventoryPaymentService.RefundEvidence> inspectRefundDelivery(
                PaidCommandRevivalRecord operation) {
            return CompletableFuture.completedFuture(
                    CommandReviveInventoryPaymentService.RefundEvidence.DELIVERED);
        }

        @Override
        public CompletionStage<Boolean> clearRefundReceipt(PaidCommandRevivalRecord operation) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<PaidCommandRevivalCoordinator.ProjectionEvidence> inspectProjection(
                PaidCommandRevivalRecord operation) {
            if (projectionEvidence == PaidCommandRevivalCoordinator.ProjectionEvidence.REVIVED) {
                projectionProof.run();
            }
            return CompletableFuture.completedFuture(projectionEvidence);
        }

        private static CompletionStage<CommandReviveInventoryPaymentService.ConsumeResult> completed(
                CommandReviveInventoryPaymentService.Status status) {
            return CompletableFuture.completedFuture(
                    new CommandReviveInventoryPaymentService.ConsumeResult(status, null));
        }
    }

    private static PaidCommandRevivalCoordinator.ResolvedRevival resolved(String profile) {
        return new PaidCommandRevivalCoordinator.ResolvedRevival(
                profile, "TestDragon", "TestDragon", "config-hash", 3L, 7L,
                PaidCommandRevivalQuote.Status.READY, 0L,
                List.of(new PaidCommandRevivalCostQuoteView(
                                "Life_Essence", 2, 99, "Life Essence", "Life_Essence"),
                        new PaidCommandRevivalCostQuoteView(
                                "Gold_Bar", 7, 99, "Gold Bar", "Gold_Bar")),
                null, null, null, null, null, null);
    }

    private static PaidCommandRevivalRecord operation(UUID owner, String profile, String key) {
        UUID operationId = UUID.randomUUID();
        String populationOperationId = "PREPARED".equals(key)
                ? "pending:" + operationId : "population-op";
        return new PaidCommandRevivalRecord(
                operationId, "test", key, owner, profile, "test:horn",
                "TestDragon", "TestDragon", "config-hash", 3L, 7L,
                populationOperationId, "placement-hash", projectionUuid(profile).toString(),
                PaidCommandRevivalRecord.State.PREPARED, costs(), List.of(), null, 10L, 10L, null);
    }

    private static UUID projectionUuid(String profile) {
        return UUID.nameUUIDFromBytes(("paid-test:" + profile)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<ItemCostComponentView> costs() {
        return List.of(new ItemCostComponentView("Life_Essence", 2),
                new ItemCostComponentView("Gold_Bar", 7));
    }

    private static List<PaidCommandRevivalRecord.Reservation> reservations() {
        return List.of(
                new PaidCommandRevivalRecord.Reservation(
                        0, 0, "backpack", 1, 2, "stack-a", 3L,
                        PaidCommandRevivalRecord.ReservationState.HELD),
                new PaidCommandRevivalRecord.Reservation(
                        1, 0, "storage", 2, 7, "stack-b", 3L,
                        PaidCommandRevivalRecord.ReservationState.HELD));
    }
}

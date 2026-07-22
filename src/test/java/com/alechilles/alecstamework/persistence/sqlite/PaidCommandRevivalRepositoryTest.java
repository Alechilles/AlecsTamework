package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.nio.file.Path;
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

    private static PaidCommandRevivalRecord operation(UUID owner, String profile) {
        return new PaidCommandRevivalRecord(
                UUID.randomUUID(), "test", "revive-1", owner, profile, "test:horn",
                "TestDragon", "TestDragon", "config-hash", 3L, 7L,
                "population-op", "placement-hash", "projection-op",
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

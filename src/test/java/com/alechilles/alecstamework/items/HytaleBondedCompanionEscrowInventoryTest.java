package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.items.components
        .TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionOperationProbe;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionLegacyPaymentSettlementGroup;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPayload;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPaymentRecoveryService;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionRecord;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStore;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStoreResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier.SaveResult;
import com.alechilles.alecstamework.persistence.runtime.player
        .InventoryOperationReceipt;
import com.alechilles.alecstamework.persistence.runtime.player
        .TameworkInventoryOperationReceiptsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Crash-window recovery tests for the production bonded-payment gateway. */
class HytaleBondedCompanionEscrowInventoryTest {
    private static final UUID OWNER = UUID.fromString(
            "77000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString(
            "77000000-0000-0000-0000-000000000002");
    private static final String ITEM = "Ingredient_Life_Essence";
    private static final String OTHER = "Ingredient_Concurrent";

    @Test
    void reservesEveryOrderedRecipeLineInOneDurableEscrow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSourceSlot((short) 0, ITEM, 2);
            fixture.setSourceSlot((short) 1, OTHER, 4);
            List<BondedCompanionReviveCost> costs = List.of(
                    new BondedCompanionReviveCost(ITEM, 2),
                    new BondedCompanionReviveCost(OTHER, 4));

            BondedCompanionActionContext.ChargeReceipt receipt = fixture.inventory
                    .consumeExactAsync(operation(900), costs)
                    .toCompletableFuture().join();

            assertNotNull(receipt);
            assertEquals(costs, fixture.escrow().costs());
            assertTrue(fixture.escrow().hasExactReservedCharge());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertEquals(0, fixture.sourceQuantity(OTHER));
        }
    }

    @Test
    void singletonAvailabilityAndReceiptFailClosedForReservedMultiLineEscrow()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSourceSlot((short) 0, ITEM, 2);
            fixture.setSourceSlot((short) 1, OTHER, 4);
            List<BondedCompanionReviveCost> costs = List.of(
                    new BondedCompanionReviveCost(ITEM, 2),
                    new BondedCompanionReviveCost(OTHER, 4));
            String operation = operation(902);
            fixture.inventory.consumeExactAsync(operation, costs)
                    .toCompletableFuture().join();

            assertEquals(0, fixture.inventory.availableQuantity(
                    operation, ITEM, 2));
            assertTrue(fixture.inventory.findCharge(operation, ITEM, 2)
                    .quarantined());
        }
    }

    @Test
    void insufficientSecondRecipeLineRestoresTheFirstWithoutAReceipt()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSourceSlot((short) 0, ITEM, 2);
            fixture.setSourceSlot((short) 1, OTHER, 3);
            List<BondedCompanionReviveCost> costs = List.of(
                    new BondedCompanionReviveCost(ITEM, 2),
                    new BondedCompanionReviveCost(OTHER, 4));

            BondedCompanionActionContext.ChargeReceipt receipt = fixture.inventory
                    .consumeExactAsync(operation(901), costs)
                    .toCompletableFuture().join();

            assertNull(receipt);
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertEquals(3, fixture.sourceQuantity(OTHER));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void committedEscrowSavedBeforeRemovalIsReleasedOnSuccessfulReplay()
            throws Exception {
        assertTerminalReplayRemoves(
                TameworkBondedReviveEscrowComponent.Phase.COMMITTED, true);
    }

    @Test
    void refundedEscrowSavedBeforeRemovalIsReleasedOnRejectedReplay()
            throws Exception {
        assertTerminalReplayRemoves(
                TameworkBondedReviveEscrowComponent.Phase.REFUNDED, false);
    }

    @Test
    void differentOperationGarbageCollectsEitherTerminalPhaseBeforeReserving()
            throws Exception {
        for (TameworkBondedReviveEscrowComponent.Phase phase : List.of(
                TameworkBondedReviveEscrowComponent.Phase.COMMITTED,
                TameworkBondedReviveEscrowComponent.Phase.REFUNDED)) {
            try (Fixture fixture = new Fixture()) {
                fixture.installTerminal(operation(1), phase);
                fixture.setSource(ITEM, 2);

                BondedCompanionActionContext.ChargeReceipt next =
                        fixture.inventory.consumeExactAsync(
                                operation(2), ITEM, 2)
                                .toCompletableFuture().join();

                assertNotNull(next);
                assertEquals(operation(2), next.operationId());
                assertEquals(0, fixture.sourceQuantity(ITEM));
                assertEquals(2, fixture.escrow().reservedQuantity());
                assertTrue(next.completeAsync().toCompletableFuture().join());
                assertNull(fixture.escrow());
            }
        }
    }

    @Test
    void failedRemovalSaveReinstallsTombstoneAndReloadDoesNotRefundTwice()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(3);
            BondedCompanionActionContext.ChargeReceipt charged = fixture.inventory
                    .consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();
            fixture.durability.enqueue(SaveResult.success());
            fixture.durability.enqueue(SaveResult.success());
            fixture.durability.enqueue(SaveResult.success());
            fixture.durability.enqueue(SaveResult.retryable(null));

            assertFalse(charged.refundAsync().toCompletableFuture().join());
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertEquals(TameworkBondedReviveEscrowComponent.Phase.REFUNDED,
                    fixture.escrow().phase());
            TameworkBondedReviveEscrowComponent durable =
                    fixture.durability.persistedEscrow.clone();

            fixture.store.put(fixture.actor, fixture.escrowType, durable);
            BondedCompanionActionContext.ChargeReceipt replay =
                    fixture.inventory.findCharge(operation);
            assertTrue(replay.refundAsync().toCompletableFuture().join());

            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void playerAddReconcilesReservedEscrowAfterCommittedCrashAndRelog()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(31);
            fixture.inventory.consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();
            TameworkBondedReviveEscrowComponent persisted =
                    fixture.durability.persistedEscrow.clone();
            fixture.store.put(fixture.actor, fixture.escrowType, persisted);
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.APPLIED, false, 31);
            HytaleBondedCompanionPaymentRecovery recovery = recovery(
                    fixture, terminal);

            recovery.onPlayerAdded(fixture.world, OWNER);

            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
            assertEquals(1, terminal.acknowledgments);
            assertEquals(Boolean.TRUE, terminal.acknowledgedApplied);
        }
    }

    @Test
    void playerAddRefundsReservedEscrowAfterRejectedCrashAndRelog()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(32);
            fixture.inventory.consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();
            TameworkBondedReviveEscrowComponent persisted =
                    fixture.durability.persistedEscrow.clone();
            fixture.store.put(fixture.actor, fixture.escrowType, persisted);
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.REJECTED, false, 32);

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
            assertEquals(1, terminal.acknowledgments);
            assertEquals(Boolean.FALSE, terminal.acknowledgedApplied);
        }
    }

    @Test
    void playerAddResumesPersistedRefundWithoutAnyDatabaseClaim()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 1);
            String operation = operation(321);
            TameworkBondedReviveEscrowComponent refunding =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            refunding.getInventory().setItemStackForSlot(
                    (short) 0, itemStack(ITEM, 1));
            refunding.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.REFUNDING);
            fixture.store.put(fixture.actor, fixture.escrowType, refunding);
            RecoveryStore missing = new RecoveryStore(
                    RecoveryResult.MISSING, false, 321);

            assertTrue(fixture.inventory.findCharge(operation)
                    .compensationPending());

            recovery(fixture, missing).onPlayerAdded(fixture.world, OWNER);

            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
            assertEquals(0, missing.revives);
            assertEquals(0, missing.acknowledgments);
        }
    }

    @Test
    void playerAddValidatesAndRemovesCommittedEscrowAfterRestart()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(322);
            fixture.installTerminal(operation,
                    TameworkBondedReviveEscrowComponent.Phase.COMMITTED);
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.APPLIED, false, 322);

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertNull(fixture.escrow());
            assertEquals(1, terminal.acknowledgments);
            assertEquals(Boolean.TRUE, terminal.acknowledgedApplied);
        }
    }

    @Test
    void terminalRejectionResumesRefundingEscrowAfterRestart()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(323);
            fixture.inventory.consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();
            TameworkBondedReviveEscrowComponent refunding =
                    fixture.durability.persistedEscrow.clone();
            refunding.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.REFUNDING);
            fixture.store.put(fixture.actor, fixture.escrowType, refunding);
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.REJECTED, false, 323);

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
            assertEquals(1, terminal.acknowledgments);
            assertEquals(Boolean.FALSE, terminal.acknowledgedApplied);
        }
    }

    @Test
    void missingOrPendingClaimResumesFromReloadedExactReservedEscrow()
            throws Exception {
        for (RecoveryResult result : List.of(
                RecoveryResult.MISSING, RecoveryResult.PENDING)) {
            try (Fixture fixture = new Fixture()) {
                fixture.setSource(ITEM, 2);
                fixture.inventory.consumeExactAsync(operation(33), ITEM, 2)
                        .toCompletableFuture().join();
                fixture.store.put(fixture.actor, fixture.escrowType,
                        fixture.durability.persistedEscrow.clone());
                RecoveryStore terminal = new RecoveryStore(result, false, 33);

                recovery(fixture, terminal).onPlayerAdded(
                        fixture.world, OWNER);

                assertEquals(0, fixture.sourceQuantity(ITEM));
                assertNull(fixture.escrow());
                assertEquals(1, terminal.revives);
                assertEquals(1, terminal.acknowledgments);
            }
        }
    }

    @Test
    void failedPostRemovalAckIsRecoveredAfterRelogWithoutAnotherSettlement()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            fixture.inventory.consumeExactAsync(operation(34), ITEM, 2)
                    .toCompletableFuture().join();
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.APPLIED, true, 34);
            terminal.failNextAcknowledgment();

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertNull(fixture.escrow());
            assertEquals(1, terminal.acknowledgments);
            assertEquals(0, fixture.sourceQuantity(ITEM));

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertEquals(2, terminal.acknowledgments);
            assertEquals(Boolean.TRUE, terminal.acknowledgedApplied);
        }
    }

    @Test
    void absentNewEscrowReconcilesLegacyMarkerBeforeAcknowledging()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.put(fixture.actor, fixture.legacyType,
                    new TameworkInventoryOperationReceiptsComponent()
                            .withReceipt(legacyReceipt(
                                    operation(35), ITEM, 2, ":pending")));
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.APPLIED, true, 35);

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertTrue(fixture.store.getComponent(
                    fixture.actor, fixture.legacyType).receipts().isEmpty());
            assertEquals(1, terminal.acknowledgments);
        }
    }

    @Test
    void rejectedLegacyPendingIsQuarantinedWithoutDeletingEvidence()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            InventoryOperationReceipt pending = legacyReceipt(
                    operation(36), ITEM, 2, ":pending");
            fixture.store.put(fixture.actor, fixture.legacyType,
                    new TameworkInventoryOperationReceiptsComponent()
                            .withReceipt(pending));
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.REJECTED, true, 36);

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);

            assertNotNull(fixture.store.getComponent(
                    fixture.actor, fixture.legacyType)
                    .receiptFor(pending.receiptKey()));
            assertEquals(0, terminal.acknowledgments);
            assertEquals(1, terminal.quarantines);
        }
    }

    @Test
    void queuedRecoveryReResolvesThePlayerAndRetriesAfterDisappearance()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            fixture.inventory.consumeExactAsync(operation(37), ITEM, 2)
                    .toCompletableFuture().join();
            RecoveryStore terminal = new RecoveryStore(
                    RecoveryResult.APPLIED, false, 37);
            fixture.world.deferExecution();

            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);
            assertEquals(0, terminal.lookups);
            assertNotNull(fixture.escrow());

            fixture.world.hideActor();
            fixture.world.drainExecution();
            assertEquals(0, terminal.lookups);
            assertNotNull(fixture.escrow());

            fixture.world.showActor(fixture.actor);
            recovery(fixture, terminal).onPlayerAdded(fixture.world, OWNER);
            fixture.world.drainExecution();
            assertEquals(1, terminal.lookups);
            assertNull(fixture.escrow());
        }
    }

    @Test
    void malformedOrForeignOwnerEscrowNeverReachesTheStore() throws Exception {
        String foreign = BondedCompanionPaymentOperationId.create(
                "test:panel", "revive:38", OTHER_OWNER,
                "hydragon:dragons", "profile-7", 38L);
        for (String unsafe : List.of("malformed-payment-id", foreign)) {
            try (Fixture fixture = new Fixture()) {
                fixture.setSource(ITEM, 2);
                fixture.inventory.consumeExactAsync(unsafe, ITEM, 2)
                        .toCompletableFuture().join();
                RecoveryStore terminal = new RecoveryStore(
                        RecoveryResult.APPLIED, false, 38);

                recovery(fixture, terminal).onPlayerAdded(
                        fixture.world, OWNER);

                assertEquals(0, terminal.lookups);
                assertEquals(0, terminal.acknowledgments);
                assertEquals(0, fixture.sourceQuantity(ITEM));
                assertEquals(2, fixture.escrow().reservedQuantity());
            }
        }
    }

    @Test
    void failedPrepareSaveRetainsOneReservationAndOperationAwareQuote()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(4);
            fixture.durability.enqueue(SaveResult.retryable(null));

            BondedCompanionActionContext.ChargeReceipt failed = fixture.inventory
                    .consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();

            assertTrue(failed.quarantined());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertEquals(2, fixture.escrow().reservedQuantity());
            assertEquals(2, fixture.inventory.availableQuantity(
                    operation, ITEM, 2));
            assertEquals(0, fixture.inventory.availableQuantity(
                    operation(5), ITEM, 2));

            BondedCompanionActionContext.ChargeReceipt retry = fixture.inventory
                    .consumeExactAsync(operation, ITEM, 2)
                    .toCompletableFuture().join();
            assertFalse(retry.quarantined());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertEquals(2, fixture.escrow().reservedQuantity());
        }
    }

    @Test
    void sameItemAndUnrelatedConcurrentMutationsAreNeverRefundEvidence()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            CompletableFuture<SaveResult> prepareSave =
                    fixture.durability.deferNext();
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> pending =
                    fixture.inventory.consumeExactAsync(
                            operation(6), ITEM, 2);
            assertFalse(pending.toCompletableFuture().isDone());
            assertEquals(0, fixture.sourceQuantity(ITEM));

            fixture.setSource(ITEM, 5);
            fixture.setSourceSlot((short) 1, OTHER, 1);
            prepareSave.complete(SaveResult.success());
            BondedCompanionActionContext.ChargeReceipt charged =
                    pending.toCompletableFuture().join();

            assertTrue(charged.refundAsync().toCompletableFuture().join());
            assertEquals(7, fixture.sourceQuantity(ITEM));
            assertEquals(1, fixture.sourceQuantity(OTHER));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void overlappingSameOperationReservationsShareOneChargeAndBothComplete()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(7);
            CompletableFuture<SaveResult> firstSave =
                    fixture.durability.deferNext();
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> first =
                    fixture.inventory.consumeExactAsync(operation, ITEM, 2);

            CompletionStage<BondedCompanionActionContext.ChargeReceipt>
                    replayStage = fixture.inventory.consumeExactAsync(
                    operation, ITEM, 2);
            assertFalse(replayStage.toCompletableFuture().isDone());
            firstSave.complete(SaveResult.success());
            BondedCompanionActionContext.ChargeReceipt fresh =
                    first.toCompletableFuture().join();
            BondedCompanionActionContext.ChargeReceipt replay =
                    replayStage.toCompletableFuture().join();

            assertFalse(fresh.replayed());
            assertTrue(replay.replayed());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertEquals(2, fixture.escrow().reservedQuantity());
            assertTrue(fresh.completeAsync().toCompletableFuture().join());
            assertTrue(replay.completeAsync().toCompletableFuture().join());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void terminalConsumeAndNextReservationShareOneActorSaveQueue()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 3);
            String firstOperation = operation(71);
            BondedCompanionActionContext.ChargeReceipt charged =
                    fixture.inventory.consumeExactAsync(
                            firstOperation, ITEM, 2)
                            .toCompletableFuture().join();
            CompletableFuture<SaveResult> committedSave =
                    fixture.durability.deferNext();
            CompletableFuture<SaveResult> removedSave =
                    fixture.durability.deferNext();
            HytaleBondedCompanionEscrowInventory second =
                    fixture.newInventory(new DirectTransfer());

            CompletionStage<Boolean> settlement = charged.completeAsync();
            CompletionStage<Boolean> duplicate = second
                    .findCharge(firstOperation).completeAsync();
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> next =
                    second.consumeExactAsync(operation(72), ITEM, 1);
            int savesBeforeFirstFenceCompleted = fixture.durability.saveCalls;

            // Complete the later queued barrier first. A production-shaped
            // implementation must not have invoked it yet.
            removedSave.complete(SaveResult.success());
            committedSave.complete(SaveResult.success());

            assertTrue(settlement.toCompletableFuture().join());
            assertTrue(duplicate.toCompletableFuture().join());
            BondedCompanionActionContext.ChargeReceipt nextCharge =
                    next.toCompletableFuture().join();
            assertNotNull(nextCharge);
            assertEquals(2, savesBeforeFirstFenceCompleted,
                    "separate action contexts must not overlap actor saves");
            assertEquals(operation(72), fixture.escrow().operationId());
            assertEquals(0, fixture.sourceQuantity(ITEM));
        }
    }

    @Test
    void canonicalV2PaymentNeverConsumesFlattenedLegacyEvidence()
            throws Exception {
        for (String suffix : List.of(":pending", ":compensated")) {
            try (Fixture fixture = new Fixture()) {
                fixture.setSource(ITEM, 2);
                String canonical = operation(73);
                InventoryOperationReceipt legacy = legacyReceipt(
                        canonical, ITEM, 2, suffix);
                fixture.store.put(fixture.actor, fixture.legacyType,
                        new TameworkInventoryOperationReceiptsComponent()
                                .withReceipt(legacy));

                assertEquals(2, fixture.inventory.availableQuantity(
                        canonical, ITEM, 2));
                assertNull(fixture.inventory.findCharge(canonical));
                BondedCompanionActionContext.ChargeReceipt charged =
                        fixture.inventory.consumeExactAsync(
                                canonical, ITEM, 2)
                                .toCompletableFuture().join();

                assertNotNull(charged);
                assertFalse(charged.historicalPaymentMarker());
                assertNotNull(fixture.store.getComponent(
                        fixture.actor, fixture.legacyType)
                        .receiptFor(legacy.receiptKey()));
                assertTrue(charged.refundAsync().toCompletableFuture().join());
                assertNotNull(fixture.store.getComponent(
                        fixture.actor, fixture.legacyType)
                        .receiptFor(legacy.receiptKey()));
            }
        }
    }

    @Test
    void terminalRecoveryReadWaitsForTheRemovalSaveFence()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            String operation = operation(74);
            BondedCompanionActionContext.ChargeReceipt charged =
                    fixture.inventory.consumeExactAsync(operation, ITEM, 2)
                            .toCompletableFuture().join();
            CompletableFuture<SaveResult> committedSave =
                    fixture.durability.deferNext();
            CompletableFuture<SaveResult> removedSave =
                    fixture.durability.deferNext();
            HytaleBondedCompanionEscrowInventory second =
                    fixture.newInventory(new DirectTransfer());

            CompletionStage<Boolean> settlement = charged.completeAsync();
            committedSave.complete(SaveResult.success());
            assertNull(fixture.escrow());
            CompletionStage<BondedCompanionActionContext.ChargeReceipt> read =
                    second.findChargeAsync(operation);

            assertFalse(read.toCompletableFuture().isDone(),
                    "absence is not durable until the removal save completes");
            removedSave.complete(SaveResult.success());

            assertTrue(settlement.toCompletableFuture().join());
            assertNull(read.toCompletableFuture().join());
        }
    }

    @Test
    void actorMutationQueueFailsClosedAtItsBoundedCapacity()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.setSource(ITEM, 2);
            CompletableFuture<SaveResult> firstSave =
                    fixture.durability.deferNext();
            List<CompletionStage<BondedCompanionActionContext.ChargeReceipt>>
                    accepted = new ArrayList<>();
            for (int ordinal = 1; ordinal <= 64; ordinal++) {
                accepted.add(fixture.inventory.consumeExactAsync(
                        operation(4000 + ordinal), ITEM, 2));
            }

            CompletionStage<BondedCompanionActionContext.ChargeReceipt>
                    overflow = fixture.inventory.consumeExactAsync(
                    operation(4065), ITEM, 2);

            assertTrue(overflow.toCompletableFuture().isDone());
            assertTrue(overflow.toCompletableFuture().join().quarantined());
            firstSave.complete(SaveResult.success());
            for (CompletionStage<
                    BondedCompanionActionContext.ChargeReceipt> stage
                    : accepted) {
                assertNotNull(stage.toCompletableFuture().join());
            }
        }
    }

    @Test
    void fullSharedReceiptComponentDoesNotLimitFortyOneEscrowCycles()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.store.put(fixture.actor, fixture.legacyType,
                    unrelatedReceipts(32));
            fixture.setSource(ITEM, 2);

            for (int index = 0; index < 40; index++) {
                BondedCompanionActionContext.ChargeReceipt charged =
                        fixture.inventory.consumeExactAsync(
                                operation(100 + index), ITEM, 2)
                                .toCompletableFuture().join();
                assertNotNull(charged);
                assertTrue(charged.refundAsync().toCompletableFuture().join());
                assertEquals(2, fixture.sourceQuantity(ITEM));
                assertNull(fixture.escrow());
            }

            BondedCompanionActionContext.ChargeReceipt fortyFirst =
                    fixture.inventory.consumeExactAsync(
                            operation(141), ITEM, 2)
                            .toCompletableFuture().join();
            assertTrue(fortyFirst.completeAsync().toCompletableFuture().join());
            assertEquals(0, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
            assertEquals(32, fixture.store.getComponent(
                    fixture.actor, fixture.legacyType).receipts().size());
        }
    }

    @Test
    void realLegacyBarePendingNeverDebitsOrRefundsZeroPartialOrFullState()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            TameworkInventoryOperationReceiptsComponent legacy =
                    new TameworkInventoryOperationReceiptsComponent();
            for (int available = 0; available <= 2; available++) {
                legacy = legacy.withReceipt(legacyPending(
                        historicalOperation(200 + available), ITEM, 2));
            }
            fixture.store.put(fixture.actor, fixture.legacyType, legacy);

            for (int available = 0; available <= 2; available++) {
                fixture.setSource(ITEM, available);
                String operation = historicalOperation(200 + available);

                assertEquals(0, fixture.inventory.availableQuantity(
                        operation, ITEM, 2));
                BondedCompanionActionContext.ChargeReceipt receipt =
                        fixture.inventory.consumeExactAsync(
                                operation, ITEM, 2)
                                .toCompletableFuture().join();

                assertTrue(receipt.quarantined());
                assertFalse(receipt.refundAsync().toCompletableFuture().join());
                assertEquals(available, fixture.sourceQuantity(ITEM));
                assertNull(fixture.escrow());
                assertNotNull(fixture.store.getComponent(
                        fixture.actor, fixture.legacyType).receiptFor(
                        legacyPending(operation, ITEM, 2).receiptKey()));
            }
        }
    }

    @Test
    void policyIndependentLegacyRecoveryReleasesOnlyTerminalSafeEvidence()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String pendingOperation = historicalOperation(250);
            fixture.store.put(fixture.actor, fixture.legacyType,
                    new TameworkInventoryOperationReceiptsComponent()
                            .withReceipt(legacyReceipt(
                                    pendingOperation, ITEM, 2, ":pending")));

            BondedCompanionActionContext.ChargeReceipt successRecovery =
                    fixture.inventory.findCharge(pendingOperation);
            assertTrue(successRecovery.quarantined());
            assertFalse(successRecovery.terminalRejectionCleanupSafe());
            assertTrue(successRecovery.completeAsync()
                    .toCompletableFuture().join());

            String compensatedOperation = historicalOperation(251);
            fixture.store.put(fixture.actor, fixture.legacyType,
                    new TameworkInventoryOperationReceiptsComponent()
                            .withReceipt(legacyReceipt(
                                    compensatedOperation, ITEM, 2,
                                    ":compensated")));
            BondedCompanionActionContext.ChargeReceipt rejectedRecovery =
                    fixture.inventory.findCharge(compensatedOperation);
            assertTrue(rejectedRecovery.quarantined());
            assertTrue(rejectedRecovery.terminalRejectionCleanupSafe());
            assertTrue(rejectedRecovery.completeAsync()
                    .toCompletableFuture().join());
            assertTrue(fixture.store.getComponent(
                    fixture.actor, fixture.legacyType).receipts().isEmpty());
        }
    }

    @Test
    void unsafeQuarantineNeverReportsTerminalCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(300);
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            escrow.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.QUARANTINED);
            fixture.store.put(fixture.actor, fixture.escrowType, escrow);

            BondedCompanionActionContext.ChargeReceipt receipt =
                    fixture.inventory.findCharge(operation);

            assertTrue(receipt.quarantined());
            assertFalse(receipt.completeAsync().toCompletableFuture().join());
            assertNotNull(fixture.escrow());
        }
    }

    @Test
    void exactMetadataSurvivesWhileOnlyReservedEscrowProvesMissingClaim()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(3001);
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            escrow.getInventory().setItemStackForSlot(
                    (short) 0, itemStack(ITEM, 2));
            escrow.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.RESERVED);
            fixture.store.put(fixture.actor, fixture.escrowType, escrow);

            BondedCompanionActionContext.ChargeReceipt reserved =
                    fixture.inventory.findCharge(operation);
            assertEquals(ITEM, reserved.itemId());
            assertEquals(2, reserved.quantity());
            assertTrue(reserved.preparedClaimProof());

            escrow.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.REFUNDING);
            BondedCompanionActionContext.ChargeReceipt refunding =
                    fixture.inventory.findCharge(operation);
            assertEquals(ITEM, refunding.itemId());
            assertEquals(2, refunding.quantity());
            assertFalse(refunding.preparedClaimProof());
            assertTrue(refunding.compensationPending());

            escrow.getInventory().clear();
            for (TameworkBondedReviveEscrowComponent.Phase terminal
                    : List.of(
                    TameworkBondedReviveEscrowComponent.Phase.REFUNDED,
                    TameworkBondedReviveEscrowComponent.Phase.COMMITTED)) {
                escrow.setPhase(terminal);
                BondedCompanionActionContext.ChargeReceipt receipt =
                        fixture.inventory.findCharge(operation);
                assertEquals(ITEM, receipt.itemId());
                assertEquals(2, receipt.quantity());
                assertFalse(receipt.preparedClaimProof());
            }
        }
    }

    @Test
    void partialInsufficientRefundPersistsProgressAndResumesRemainingSlot()
            throws Exception {
        try (Fixture fixture = new Fixture(new OneSlotRestoreTransfer())) {
            String operation = operation(301);
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 3, -1L);
            escrow.getInventory().setItemStackForSlot(
                    (short) 0, itemStack(ITEM, 1));
            escrow.getInventory().setItemStackForSlot(
                    (short) 1, itemStack(ITEM, 1));
            fixture.store.put(fixture.actor, fixture.escrowType, escrow);

            BondedCompanionActionContext.ChargeReceipt insufficient =
                    fixture.inventory.consumeExactAsync(operation, ITEM, 3)
                            .toCompletableFuture().join();

            assertNull(insufficient);
            assertEquals(1, fixture.sourceQuantity(ITEM));
            assertEquals(1, fixture.escrow().reservedQuantity());
            assertEquals(TameworkBondedReviveEscrowComponent.Phase.REFUNDING,
                    fixture.escrow().phase());
            assertEquals(TameworkBondedReviveEscrowComponent.Phase.REFUNDING,
                    fixture.durability.persistedEscrow.phase());
            assertEquals(1,
                    fixture.durability.persistedEscrow.reservedQuantity());

            fixture.store.put(fixture.actor, fixture.escrowType,
                    fixture.durability.persistedEscrow.clone());

            BondedCompanionActionContext.ChargeReceipt replay =
                    fixture.inventory.findCharge(operation);
            assertNotNull(replay);
            assertFalse(replay.quarantined());
            assertTrue(replay.refundAsync().toCompletableFuture().join());

            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void refundSavesEachReturnedSlotBeforeAdvancingToTheNext()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(302);
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            escrow.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.RESERVED);
            escrow.getInventory().setItemStackForSlot(
                    (short) 0, itemStack(ITEM, 1));
            escrow.getInventory().setItemStackForSlot(
                    (short) 1, itemStack(ITEM, 1));
            fixture.store.put(fixture.actor, fixture.escrowType, escrow);
            fixture.durability.enqueue(SaveResult.success());
            CompletableFuture<SaveResult> firstSlotSave =
                    fixture.durability.deferNext();

            CompletionStage<Boolean> refund = fixture.inventory
                    .findCharge(operation).refundAsync();
            CompletionStage<Boolean> duplicate = fixture.inventory
                    .findCharge(operation).refundAsync();

            assertEquals(1, fixture.sourceQuantity(ITEM));
            assertEquals(1, fixture.escrow().reservedQuantity());
            assertFalse(refund.toCompletableFuture().isDone());
            assertFalse(duplicate.toCompletableFuture().isDone());

            firstSlotSave.complete(SaveResult.success());

            assertTrue(refund.toCompletableFuture().join());
            assertTrue(duplicate.toCompletableFuture().join());
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
        }
    }

    @Test
    void separateProductionInventoryInstancesShareOneActorRefundFlight()
            throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(303);
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            escrow.setPhase(
                    TameworkBondedReviveEscrowComponent.Phase.RESERVED);
            escrow.getInventory().setItemStackForSlot(
                    (short) 0, itemStack(ITEM, 1));
            escrow.getInventory().setItemStackForSlot(
                    (short) 1, itemStack(ITEM, 1));
            fixture.store.put(fixture.actor, fixture.escrowType, escrow);
            fixture.durability.enqueue(SaveResult.success());
            CompletableFuture<SaveResult> firstSlotSave =
                    fixture.durability.deferNext();
            HytaleBondedCompanionEscrowInventory second =
                    fixture.newInventory(new DirectTransfer());

            CompletionStage<Boolean> first = fixture.inventory
                    .findCharge(operation).refundAsync();
            CompletionStage<Boolean> duplicate = second
                    .findCharge(operation).refundAsync();

            assertEquals(1, fixture.sourceQuantity(ITEM));
            assertEquals(1, fixture.escrow().reservedQuantity());
            assertFalse(first.toCompletableFuture().isDone());
            assertFalse(duplicate.toCompletableFuture().isDone());

            firstSlotSave.complete(SaveResult.success());

            assertTrue(first.toCompletableFuture().join());
            assertTrue(duplicate.toCompletableFuture().join());
            assertEquals(2, fixture.sourceQuantity(ITEM));
            assertNull(fixture.escrow());
        }
    }

    private void assertTerminalReplayRemoves(
            TameworkBondedReviveEscrowComponent.Phase phase,
            boolean committed
    ) throws Exception {
        try (Fixture fixture = new Fixture()) {
            String operation = operation(0);
            fixture.installTerminal(operation, phase);

            BondedCompanionActionContext.ChargeReceipt receipt =
                    fixture.inventory.findCharge(operation);

            assertFalse(receipt.quarantined());
            boolean removed = (committed
                    ? receipt.completeAsync() : receipt.refundAsync())
                    .toCompletableFuture().join();
            assertTrue(removed);
            assertNull(fixture.escrow());
            assertEquals(2, fixture.durability.saveCalls);
        }
    }

    private static String operation(int ordinal) {
        return BondedCompanionPaymentOperationId.create(
                "test:panel", "revive:" + ordinal, OWNER,
                "hydragon:dragons", "profile-7", ordinal);
    }

    private static String historicalOperation(int ordinal) {
        return BondedCompanionPaymentOperationId.legacyOperationKey(
                operation(ordinal));
    }

    private static BondedCompanionOperationProbe probe(int ordinal) {
        return new BondedCompanionOperationProbe(
                "test:panel", "revive:" + ordinal, OWNER,
                "hydragon:dragons", "profile-7",
                com.alechilles.alecstamework.persistence.bonded
                        .BondedCompanionOperation.Type.REVIVE);
    }

    private static HytaleBondedCompanionPaymentRecovery recovery(
            Fixture fixture,
            RecoveryStore terminal
    ) {
        return new HytaleBondedCompanionPaymentRecovery(
                new BondedCompanionPaymentRecoveryService(
                        terminal.store, () -> -5_000L),
                () -> fixture.escrowType,
                () -> fixture.legacyType,
                (world, store, owner, worldKey, escrowType, receiptType) ->
                        fixture.newInventory(new DirectTransfer()));
    }

    private static InventoryOperationReceipt legacyPending(
            String operation, String itemId, int quantity) {
        return legacyReceipt(operation, itemId, quantity, ":pending");
    }

    private static InventoryOperationReceipt legacyReceipt(
            String operation, String itemId, int quantity, String suffix) {
        String legacy = BondedCompanionPaymentOperationId
                .legacyOperationKey(operation);
        OperationId operationId = new OperationId(UUID.nameUUIDFromBytes(
                ("bonded-revive\0" + legacy)
                        .getBytes(StandardCharsets.UTF_8)));
        return new InventoryOperationReceipt(
                "bonded-revive:" + operationId + suffix,
                operationId, new OperationKind("bonded_revive"),
                Sha256Hash.ofUtf8(legacy + "\0" + itemId + "\0" + quantity),
                0L);
    }

    private static TameworkInventoryOperationReceiptsComponent
            unrelatedReceipts(int count) {
        TameworkInventoryOperationReceiptsComponent receipts =
                new TameworkInventoryOperationReceiptsComponent();
        for (int index = 0; index < count; index++) {
            receipts = receipts.withReceipt(new InventoryOperationReceipt(
                    "unrelated-" + index,
                    new OperationId(new UUID(0L, index + 1L)),
                    new OperationKind("unrelated"),
                    Sha256Hash.ofUtf8("unrelated-" + index), index));
        }
        return receipts;
    }

    private static ItemStack itemStack(String itemId, int quantity)
            throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        set(stack, "itemId", itemId);
        set(stack, "quantity", quantity);
        set(stack, "durability", 0D);
        set(stack, "maxDurability", 0D);
        set(stack, "metadata", new BsonDocument());
        return stack;
    }

    private static void set(ItemStack stack, String name, Object value)
            throws Exception {
        Field field = ItemStack.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(stack, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class Fixture implements AutoCloseable {
        private final TestWorld world;
        private final TestEntityComponentStore store;
        private final Ref<EntityStore> actor;
        private final ComponentType<EntityStore,
                TameworkBondedReviveEscrowComponent> escrowType;
        private final ComponentType<EntityStore,
                TameworkInventoryOperationReceiptsComponent> legacyType;
        private final InventoryComponent.Storage source =
                new InventoryComponent.Storage((short) 64);
        private final CombinedItemContainer combined =
                new CombinedItemContainer(source.getInventory());
        private final RecordingDurability durability;
        private final HytaleBondedCompanionEscrowInventory inventory;

        private Fixture() throws Exception {
            this(new DirectTransfer());
        }

        private Fixture(BondedCompanionEscrowTransfer transfer)
                throws Exception {
            world = (TestWorld) unsafe().allocateInstance(TestWorld.class);
            store = new TestEntityComponentStore(new TestEntityStore(world));
            actor = store.createReference();
            world.actor = actor;
            escrowType = store.getRegistry().registerComponent(
                    TameworkBondedReviveEscrowComponent.class,
                    TameworkBondedReviveEscrowComponent::new);
            legacyType = store.getRegistry().registerComponent(
                    TameworkInventoryOperationReceiptsComponent.class,
                    TameworkInventoryOperationReceiptsComponent::new);
            durability = new RecordingDurability(this::escrow);
            inventory = new HytaleBondedCompanionEscrowInventory(
                    world, store, OWNER, "world-a", escrowType, legacyType,
                    durability, () -> combined, transfer);
        }

        private void installTerminal(
                String operation,
                TameworkBondedReviveEscrowComponent.Phase phase) {
            TameworkBondedReviveEscrowComponent escrow =
                    TameworkBondedReviveEscrowComponent.create(
                            (short) 2, operation, ITEM, 2, -1L);
            escrow.setPhase(phase);
            store.put(actor, escrowType, escrow);
        }

        private void setSource(String itemId, int quantity) throws Exception {
            source.getInventory().clear();
            if (quantity > 0) setSourceSlot((short) 0, itemId, quantity);
        }

        private void setSourceSlot(short slot, String itemId, int quantity)
                throws Exception {
            source.getInventory().setItemStackForSlot(
                    slot, itemStack(itemId, quantity));
        }

        private int sourceQuantity(String itemId) {
            int quantity = 0;
            for (short slot = 0; slot < combined.getCapacity(); slot++) {
                ItemStack stack = combined.getItemStack(slot);
                if (!ItemStack.isEmpty(stack)
                        && itemId.equals(stack.getItemId())) {
                    quantity += stack.getQuantity();
                }
            }
            return quantity;
        }

        private TameworkBondedReviveEscrowComponent escrow() {
            return store.getComponent(actor, escrowType);
        }

        private HytaleBondedCompanionEscrowInventory newInventory(
                BondedCompanionEscrowTransfer transfer) {
            return new HytaleBondedCompanionEscrowInventory(
                    world, store, OWNER, "world-a", escrowType, legacyType,
                    durability, () -> combined, transfer);
        }

        @Override public void close() {
            store.close();
        }
    }

    private static final class RecordingDurability
            implements BondedCompanionEscrowDurability {
        private final Supplier<TameworkBondedReviveEscrowComponent> escrow;
        private final Queue<CompletableFuture<SaveResult>> outcomes =
                new ArrayDeque<>();
        private int saveCalls;
        private TameworkBondedReviveEscrowComponent persistedEscrow;

        private RecordingDurability(
                Supplier<TameworkBondedReviveEscrowComponent> escrow) {
            this.escrow = escrow;
        }

        private void enqueue(SaveResult result) {
            outcomes.add(CompletableFuture.completedFuture(result));
        }

        private CompletableFuture<SaveResult> deferNext() {
            CompletableFuture<SaveResult> pending = new CompletableFuture<>();
            outcomes.add(pending);
            return pending;
        }

        @Override
        public CompletionStage<SaveResult> saveActor() {
            saveCalls++;
            CompletableFuture<SaveResult> outcome = outcomes.poll();
            if (outcome == null) {
                outcome = CompletableFuture.completedFuture(
                        SaveResult.success());
            }
            return outcome.thenApply(result -> {
                if (result.saved()) {
                    TameworkBondedReviveEscrowComponent current = escrow.get();
                    persistedEscrow = current == null ? null : current.clone();
                }
                return result;
            });
        }

        @Override
        public <T> CompletionStage<T> resumeOnWorldThread(
                Supplier<CompletionStage<T>> continuation,
                Supplier<T> unavailable) {
            return continuation.get();
        }
    }

    private enum RecoveryResult { APPLIED, REJECTED, MISSING, PENDING }

    private static final class RecoveryStore {
        private final RecoveryResult result;
        private final boolean awaiting;
        private final int ordinal;
        private final Queue<Boolean> acknowledgmentResults =
                new ArrayDeque<>();
        private int lookups;
        private int acknowledgments;
        private int quarantines;
        private int revives;
        private Boolean acknowledgedApplied;
        private final BondedCompanionStore store;

        private RecoveryStore(
                RecoveryResult result, boolean awaiting, int ordinal) {
            this.result = result;
            this.awaiting = awaiting;
            this.ordinal = ordinal;
            this.store = (BondedCompanionStore) Proxy.newProxyInstance(
                        BondedCompanionStore.class.getClassLoader(),
                        new Class<?>[]{BondedCompanionStore.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "findProfileOperationByIdentity" -> {
                                lookups++;
                                yield find();
                            }
                            case "markProfileOperationPaymentSettled" -> {
                                acknowledgments++;
                                acknowledgedApplied = (Boolean) arguments[1];
                                yield acknowledgmentResults.isEmpty()
                                        || acknowledgmentResults.remove();
                            }
                            case "reviveProfile" -> {
                                revives++;
                                yield switch (result) {
                                    case APPLIED -> terminal().asReplay();
                                    case REJECTED ->
                                            new BondedCompanionStoreResult<>(
                                                    BondedCompanionStoreResult
                                                            .Code.INVALID_STATE,
                                                    null,
                                                    "profile-is-not-dead",
                                                    true);
                                    case MISSING, PENDING -> terminal();
                                };
                            }
                            case "listAwaitingProfilePaymentSettlements" ->
                                    awaiting ? List.of(probe(ordinal))
                                            : List.<BondedCompanionOperationProbe>of();
                            case "listAwaitingLegacyPaymentSettlementGroups" ->
                                    awaiting ? List.of(
                                            legacyGroup(probe(ordinal)))
                                            : List.<BondedCompanionLegacyPaymentSettlementGroup>of();
                            case "quarantineLegacyPaymentSettlementGroup" -> {
                                quarantines++;
                                yield 1;
                            }
                            case "toString" -> "RecoveryStore";
                            default -> throw new AssertionError(
                                    "Unexpected store call: "
                                            + method.getName());
                        });
        }

        private void failNextAcknowledgment() {
            acknowledgmentResults.add(false);
        }

        private BondedCompanionLegacyPaymentSettlementGroup legacyGroup(
                BondedCompanionOperationProbe probe) {
            return new BondedCompanionLegacyPaymentSettlementGroup(
                    BondedCompanionPaymentOperationId.legacyOperationKey(
                            probe.callerNamespace(), probe.idempotencyKey()),
                    List.of(probe));
        }

        private Optional<BondedCompanionStoreResult<
                BondedCompanionRecord.Profile>> find() {
            return switch (result) {
                case APPLIED -> Optional.of(terminal().asReplay());
                case REJECTED -> Optional.of(new BondedCompanionStoreResult<>(
                        BondedCompanionStoreResult.Code.INVALID_STATE,
                        null, "profile-is-not-dead", true));
                case MISSING -> Optional.empty();
                case PENDING -> Optional.of(new BondedCompanionStoreResult<>(
                        BondedCompanionStoreResult.Code.CONFLICT,
                        null, "operation-still-pending", false));
            };
        }

        private BondedCompanionStoreResult<BondedCompanionRecord.Profile>
                terminal() {
            BondedCompanionRecord.Profile profile =
                    new BondedCompanionRecord.Profile(
                            "profile-7", OWNER, "hydragon:dragons",
                            "hydragon:dragon", "role:test",
                            BondedCompanionState.STORED, 32L,
                            BondedCompanionPayload.of(new byte[]{1}),
                            -10L, -5L, Map.of(), "Nimbus", "Miniwyvern",
                            "Female", null, 0L, 1L, null, null);
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.APPLIED,
                    profile, null, false);
        }
    }

    private static final class DirectTransfer
            implements BondedCompanionEscrowTransfer {
        @Override
        public int availableQuantity(
                CombinedItemContainer source, String itemId) {
            int total = 0;
            for (short slot = 0; slot < source.getCapacity(); slot++) {
                ItemStack stack = source.getItemStack(slot);
                if (!ItemStack.isEmpty(stack)
                        && itemId.equals(stack.getItemId())) {
                    total += stack.getQuantity();
                }
            }
            return total;
        }

        @Override
        public void reserveRemaining(
                CombinedItemContainer source,
                TameworkBondedReviveEscrowComponent escrow,
                int remaining) {
            reserveRemaining(source, escrow, escrow.itemId(), remaining);
        }

        @Override
        public void reserveRemaining(
                CombinedItemContainer source,
                TameworkBondedReviveEscrowComponent escrow,
                String itemId,
                int remaining) {
            int movedTotal = 0;
            try {
                for (short slot = 0;
                     slot < source.getCapacity() && remaining > 0; slot++) {
                    ItemStack stack = source.getItemStack(slot);
                    if (ItemStack.isEmpty(stack)
                            || !itemId.equals(stack.getItemId())) continue;
                    int moved = Math.min(remaining, stack.getQuantity());
                    int left = stack.getQuantity() - moved;
                    source.setItemStackForSlot(slot, left == 0
                            ? ItemStack.EMPTY
                            : itemStack(stack.getItemId(), left));
                    movedTotal += moved;
                    remaining -= moved;
                }
                int reserved = escrow.reservedQuantity(itemId);
                if (movedTotal > 0) {
                    for (short slot = 0;
                         slot < escrow.getInventory().getCapacity(); slot++) {
                        if (ItemStack.isEmpty(escrow.getInventory()
                                .getItemStack(slot))) {
                            escrow.getInventory().setItemStackForSlot(slot,
                                    itemStack(itemId, reserved + movedTotal));
                            break;
                        }
                    }
                }
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public RestoreResult restoreNext(
                CombinedItemContainer source,
                TameworkBondedReviveEscrowComponent escrow) {
            if (escrow.reservedQuantity() < 0) return RestoreResult.INVALID;
            try {
                for (short escrowSlot = 0;
                     escrowSlot < escrow.getInventory().getCapacity();
                     escrowSlot++) {
                    ItemStack reserved = escrow.getInventory()
                            .getItemStack(escrowSlot);
                    if (ItemStack.isEmpty(reserved)) continue;
                    short destination = -1;
                    int existing = 0;
                    for (short slot = 0; slot < source.getCapacity(); slot++) {
                        ItemStack stack = source.getItemStack(slot);
                        if (!ItemStack.isEmpty(stack)
                                && reserved.getItemId().equals(stack.getItemId())) {
                            destination = slot;
                            existing = stack.getQuantity();
                            break;
                        }
                        if (destination < 0 && ItemStack.isEmpty(stack)) {
                            destination = slot;
                        }
                    }
                    if (destination < 0) return RestoreResult.BLOCKED;
                    source.setItemStackForSlot(destination, itemStack(
                            reserved.getItemId(), existing + reserved.getQuantity()));
                    escrow.getInventory().setItemStackForSlot(
                            escrowSlot, ItemStack.EMPTY);
                    return RestoreResult.MOVED;
                }
                return escrow.reservedQuantity() == 0
                        ? RestoreResult.COMPLETE : RestoreResult.INVALID;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class OneSlotRestoreTransfer
            implements BondedCompanionEscrowTransfer {
        private final DirectTransfer delegate = new DirectTransfer();
        private int restoreCalls;

        @Override
        public int availableQuantity(
                CombinedItemContainer source, String itemId) {
            return delegate.availableQuantity(source, itemId);
        }

        @Override
        public void reserveRemaining(
                CombinedItemContainer source,
                TameworkBondedReviveEscrowComponent escrow,
                int remaining) {
            delegate.reserveRemaining(source, escrow, remaining);
        }

        @Override
        public RestoreResult restoreNext(
                CombinedItemContainer source,
                TameworkBondedReviveEscrowComponent escrow) {
            restoreCalls++;
            return restoreCalls == 2 ? RestoreResult.BLOCKED
                    : delegate.restoreNext(source, escrow);
        }
    }

    private static final class TestEntityStore extends EntityStore {
        private TestEntityStore(World world) { super(world); }
    }

    private static final class TestWorld extends World {
        private Ref<EntityStore> actor;
        private Queue<Runnable> commands;

        private TestWorld() throws java.io.IOException {
            super("unused", Path.of("."),
                    new com.hypixel.hytale.server.core.universe.world
                            .WorldConfig());
        }

        @Override public String getName() { return "world-a"; }
        @Override public Ref<EntityStore> getEntityRef(UUID uuid) {
            return OWNER.equals(uuid) ? actor : null;
        }
        @Override public void execute(Runnable command) {
            if (commands == null) command.run();
            else commands.add(command);
        }

        private void deferExecution() {
            commands = new ArrayDeque<>();
        }

        private void drainExecution() {
            Runnable command;
            while ((command = commands.poll()) != null) command.run();
        }

        private void hideActor() {
            actor = null;
        }

        private void showActor(Ref<EntityStore> reference) {
            actor = reference;
        }
    }
}

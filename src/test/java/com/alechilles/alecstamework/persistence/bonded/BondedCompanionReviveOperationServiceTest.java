package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionActionRequest;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionResult;
import com.alechilles.alecstamework.api.BondedCompanionResultCode;
import com.alechilles.alecstamework.api.BondedCompanionReviveRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionOperationLedger;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicy;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionProfile;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionTransitionService;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import com.hypixel.hytale.codec.ExtraInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.IntSupplier;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Crash/retry proofs for the extracted bonded-revival payment coordinator. */
class BondedCompanionReviveOperationServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "76000000-0000-0000-0000-000000000001");
    private static final String ROSTER = "hydragon:dragons";
    private static final String PROFILE = "profile-1";
    private static final String ROLE = "Bonded_Miniwyvern_Storm";

    @Test
    void fortyTerminalRejectionsRefundExactlyOnceWithoutReceiptCapacity() throws Exception {
        StoreDouble store = new StoreDouble(false);
        ServiceHarness harness = harness(store);
        PaymentInventory inventory = new PaymentInventory(2);

        for (int index = 0; index < 40; index++) {
            BondedCompanionResult<BondedCompanionProfileView> result =
                    harness.service.revive(request(
                            "rejected-" + index, inventory,
                            harness.rosters.snapshot().revision()))
                            .toCompletableFuture().join();
            assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                    result.code());
            assertEquals(2, inventory.availableQuantity(
                    "Ingredient_Life_Essence"));
            assertEquals(0, inventory.activeCharges());
        }

        assertEquals(40, inventory.chargeApplications);
        assertEquals(40, inventory.refundApplications);

        harness.service.revive(request(
                "rejected-39", inventory,
                harness.rosters.snapshot().revision()))
                .toCompletableFuture().join();
        assertEquals(40, inventory.chargeApplications);
        assertEquals(40, inventory.refundApplications);
    }

    @Test
    void storageFailureRetainsOneEscrowAndRetryNeverChargesOrRefundsAgain()
            throws Exception {
        StoreDouble store = new StoreDouble(true);
        ServiceHarness harness = harness(store);
        PaymentInventory inventory = new PaymentInventory(2);
        BondedCompanionReviveRequest request = request(
                "storage-retry", inventory,
                harness.rosters.snapshot().revision());

        BondedCompanionResult<BondedCompanionProfileView> first =
                harness.service.revive(request).toCompletableFuture().join();
        BondedCompanionResult<BondedCompanionProfileView> retry =
                harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, first.code());
        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, retry.code());
        assertEquals(1, inventory.chargeApplications);
        assertEquals(0, inventory.refundApplications);
        assertEquals(1, inventory.activeCharges());
        assertEquals(0, inventory.availableQuantity(
                "Ingredient_Life_Essence"));
    }

    @Test
    void terminalRejectionRefundsCanonicalEscrowBeforeMutableRequestGates()
            throws Exception {
        StoreDouble store = new StoreDouble(false);
        ServiceHarness harness = harness(store);
        PaymentInventory inventory = new PaymentInventory(2);
        long quotedRevision = harness.rosters.snapshot().revision();
        BondedCompanionReviveRequest request = request(
                "terminal-before-gates", inventory, quotedRevision);
        BondedCompanionActionRequest action = request.action();
        String paymentOperationId = BondedCompanionPaymentOperationId.create(
                action.callerNamespace(), action.idempotencyKey(),
                action.ownerUuid(), action.rosterId(), action.profileId(),
                action.expectedRevision());

        inventory.consumeExact(
                paymentOperationId, "Ingredient_Life_Essence", 2);
        store.seedTerminalRejection(action);
        harness.support.removeProfile();
        harness.rosters.replace(List.of(), quotedRevision + 1L);

        BondedCompanionResult<BondedCompanionProfileView> recovered =
                harness.service.revive(request).toCompletableFuture().join();
        BondedCompanionResult<BondedCompanionProfileView> retried =
                harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                recovered.code());
        assertEquals("test-revision-conflict", recovered.reason());
        assertEquals(BondedCompanionResultCode.REVISION_CONFLICT,
                retried.code());
        assertEquals("test-revision-conflict", retried.reason());
        assertEquals(1, inventory.chargeApplications);
        assertEquals(1, inventory.refundApplications);
        assertEquals(0, inventory.activeCharges());
        assertEquals(2, inventory.availableQuantity(
                "Ingredient_Life_Essence"));
    }

    @Test
    void sameOperationReplayCanNeverRefundTheWinningSharedCharge()
            throws Exception {
        StoreDouble store = new StoreDouble(false, true);
        ServiceHarness harness = harness(store);
        RacingPaymentInventory inventory = new RacingPaymentInventory(2);
        BondedCompanionReviveRequest request = request(
                "same-operation-race", inventory,
                harness.rosters.snapshot().revision());

        CompletionStage<BondedCompanionResult<BondedCompanionProfileView>> first =
                harness.service.revive(request);
        CompletionStage<BondedCompanionResult<BondedCompanionProfileView>> replay =
                harness.service.revive(request);
        inventory.replaySettlementEntered.join();

        inventory.releaseFreshReceipt();
        BondedCompanionResult<BondedCompanionProfileView> firstResult =
                first.toCompletableFuture().join();
        inventory.finishReplaySettlement();
        BondedCompanionResult<BondedCompanionProfileView> replayResult =
                replay.toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.SUCCESS, firstResult.code());
        assertEquals(BondedCompanionResultCode.SUCCESS, replayResult.code());
        assertEquals(1, inventory.chargeApplications);
        assertEquals(0, inventory.refundApplications);
        assertEquals(0, inventory.availableQuantity(
                "Ingredient_Life_Essence"));
        assertEquals(0, inventory.activeCharges());
    }

    @Test
    void nullRecoveryStageFailsClosedWithoutAcknowledgingTerminalProof()
            throws Exception {
        StoreDouble store = new StoreDouble(false);
        ServiceHarness harness = harness(store);
        BondedCompanionActionContext.Inventory inventory =
                new BondedCompanionActionContext.Inventory() {
                    @Override public int availableQuantity(String itemId) {
                        return 0;
                    }

                    @Override public CompletionStage<
                            BondedCompanionActionContext.ChargeReceipt>
                            findChargeAsync(String operationId) {
                        return null;
                    }

                    @Override public BondedCompanionActionContext.ChargeReceipt
                            consumeExact(String operationId, String itemId,
                                         int quantity) {
                        return null;
                    }
                };
        BondedCompanionReviveRequest request = request(
                "null-recovery-stage", inventory,
                harness.rosters.snapshot().revision());
        store.seedTerminalRejection(request.action());

        BondedCompanionResult<BondedCompanionProfileView> result =
                harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE,
                result.code());
        assertEquals(0, store.acknowledgments);
    }

    @Test
    void directTerminalRetryRejectsMismatchedEscrowRequestHash()
            throws Exception {
        StoreDouble store = new StoreDouble(false);
        ServiceHarness harness = harness(store);
        PaymentInventory inventory = new PaymentInventory(2);
        BondedCompanionReviveRequest request = request(
                "direct-hash-mismatch", inventory,
                harness.rosters.snapshot().revision());
        BondedCompanionActionRequest action = request.action();
        String paymentOperationId = BondedCompanionPaymentOperationId.create(
                action.callerNamespace(), action.idempotencyKey(),
                action.ownerUuid(), action.rosterId(), action.profileId(),
                action.expectedRevision());
        inventory.consumeExact(
                paymentOperationId, "Ingredient_Life_Essence", 2);
        store.seedTerminalRejection(
                action, BondedCompanionRevivePaymentProof.requestHash(
                        "Ingredient_Wrong_Essence", 2));

        BondedCompanionResult<BondedCompanionProfileView> result =
                harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, result.code());
        assertEquals("bonded-revive-payment-quarantined", result.reason());
        assertEquals(0, inventory.refundApplications);
        assertEquals(1, inventory.activeCharges());
        assertEquals(0, store.acknowledgments);
    }

    @Test
    void freshCommitPublishesBeforeSettlementAndRetryNeverPublishesTwice()
            throws Exception {
        StoreDouble store = new StoreDouble(false, true);
        ServiceHarness harness = harness(store);
        FlakySettlementInventory inventory = new FlakySettlementInventory(
                () -> harness.support.publications);
        BondedCompanionReviveRequest request = request(
                "publish-before-settlement", inventory,
                harness.rosters.snapshot().revision());

        BondedCompanionResult<BondedCompanionProfileView> first =
                harness.service.revive(request).toCompletableFuture().join();
        harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, first.code());
        assertEquals(1, inventory.publicationsAtFirstSettlement);
        assertEquals(2, inventory.settlementAttempts);
        assertEquals(1, harness.support.publications);
        assertEquals(1, store.acknowledgments);
        assertNull(inventory.active);
    }

    @Test
    void canonicalTerminalNeverReleasesAFlattenedHistoricalMarker()
            throws Exception {
        StoreDouble store = new StoreDouble(false);
        ServiceHarness harness = harness(store);
        HistoricalMarkerInventory inventory = new HistoricalMarkerInventory();
        BondedCompanionReviveRequest request = request(
                "canonical-legacy-collision", inventory,
                harness.rosters.snapshot().revision());
        store.seedTerminalRejection(request.action());

        BondedCompanionResult<BondedCompanionProfileView> result =
                harness.service.revive(request).toCompletableFuture().join();

        assertEquals(BondedCompanionResultCode.INTERNAL_FAILURE, result.code());
        assertEquals("bonded-revive-payment-quarantined", result.reason());
        assertEquals(0, inventory.markerRemovals);
        assertEquals(0, store.acknowledgments);
    }

    private ServiceHarness harness(StoreDouble storeDouble) throws Exception {
        BondedCompanionRosterRegistry rosters = registry();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        BondedCompanionTransitionService transitions =
                new BondedCompanionTransitionService(policies);
        BondedCompanionSnapshot snapshot = snapshot();
        BondedCompanionRecord.Profile profile = new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, "hydragon:dragon", ROLE,
                BondedCompanionState.DEAD, 4L,
                BondedCompanionPayload.of(new byte[]{1}),
                1L, 2L, Map.of(), "Nimbus", "Miniwyvern", "Female",
                1L, 0L, 0L, null, null);
        BondedCompanionProfile domain = new BondedCompanionProfile(
                PROFILE, OWNER, ROSTER, "hydragon:dragon", ROLE,
                BondedCompanionState.DEAD, 4L, snapshot, null,
                0L, 1L, 0L, BondedCompanionOperationLedger.empty());
        Support support = new Support(profile, domain, snapshot,
                rosters.snapshot().revision());
        BondedCompanionReviveOperationService service =
                new BondedCompanionReviveOperationService(
                        storeDouble.store, rosters, policies, transitions,
                        () -> 5_000L, support);
        return new ServiceHarness(service, rosters, support);
    }

    private BondedCompanionReviveRequest request(
            String key,
            BondedCompanionActionContext.Inventory inventory,
            long quoteRevision
    ) {
        BondedCompanionActionRequest action = new BondedCompanionActionRequest(
                "test-panel", key, OWNER, ROSTER, PROFILE, 4L,
                "world-a", new BondedCompanionActionContext(null, inventory));
        return new BondedCompanionReviveRequest(action, quoteRevision);
    }

    private BondedCompanionRosterRegistry registry() throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "hydragon:dragons",
                                  "FamilyId": "hydragon:dragon",
                                  "AllowedRoles": ["Bonded_Miniwyvern_Storm"],
                                  "MaximumOwned": 64,
                                  "MaximumActive": 1,
                                  "SessionDurationSeconds": 600,
                                  "SummonCooldownSeconds": 0,
                                  "RevivePrice": {
                                    "ItemId": "Ingredient_Life_Essence",
                                    "Quantity": 2
                                  },
                                  "Features": {
                                    "Capture": true,
                                    "Provision": true,
                                    "Summon": true,
                                    "Dismiss": true,
                                    "Revive": true
                                  }
                                }
                                """), new ExtraInfo());
        Field id = config.getClass().getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "HydragonDragons");
        BondedCompanionRosterRegistry registry =
                new BondedCompanionRosterRegistry();
        registry.replace(List.of(config), 1L);
        return registry;
    }

    private BondedCompanionSnapshot snapshot() {
        return BondedCompanionSnapshot.of(new CoopResidentStateSnapshot(
                UUID.fromString("76000000-0000-0000-0000-000000000002"),
                null, -1, ROLE, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 1L), Map.of());
    }

    private record ServiceHarness(
            BondedCompanionReviveOperationService service,
            BondedCompanionRosterRegistry rosters,
            Support support) {
    }

    private static final class StoreDouble {
        private final Map<String, BondedCompanionStoreResult<
                BondedCompanionRecord.Profile>> terminals = new HashMap<>();
        private final Map<String, String> requestHashes = new HashMap<>();
        private final boolean failStorage;
        private final boolean succeed;
        private int acknowledgments;
        private final BondedCompanionStore store;

        private StoreDouble(boolean failStorage) {
            this(failStorage, false);
        }

        private StoreDouble(boolean failStorage, boolean succeed) {
            this.failStorage = failStorage;
            this.succeed = succeed;
            store = (BondedCompanionStore) Proxy.newProxyInstance(
                    BondedCompanionStore.class.getClassLoader(),
                    new Class<?>[]{BondedCompanionStore.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "findProfileOperationByIdentity" -> Optional.ofNullable(
                                terminals.get(key(
                                        (BondedCompanionOperationProbe) arguments[0])))
                                .map(BondedCompanionStoreResult::asReplay);
                        case "reviveProfile" -> revive(
                                (BondedCompanionOperation) arguments[0]);
                        case "markProfileOperationPaymentSettled" -> {
                            acknowledgments++;
                            yield true;
                        }
                        case "listAwaitingProfilePaymentSettlements" ->
                                List.of();
                        case "toString" -> "BondedCompanionStoreDouble";
                        default -> throw new AssertionError(
                                "Unexpected store call: " + method.getName());
                    });
        }

        private synchronized BondedCompanionStoreResult<
                BondedCompanionRecord.Profile>
                revive(BondedCompanionOperation operation) {
            if (failStorage) {
                return new BondedCompanionStoreResult<>(
                        BondedCompanionStoreResult.Code.STORAGE_FAILURE,
                        null, "test-storage-failure", false);
            }
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> existing =
                    terminals.get(key(operation));
            if (existing != null) {
                if (!operation.requestHash().equals(
                        requestHashes.get(key(operation)))) {
                    return new BondedCompanionStoreResult<>(
                            BondedCompanionStoreResult.Code
                                    .IDEMPOTENCY_CONFLICT,
                            null, "test-request-hash-conflict", false);
                }
                return existing.asReplay();
            }
            BondedCompanionStoreResult<BondedCompanionRecord.Profile> result =
                    succeed ? terminalSuccess() : terminalRejection();
            terminals.put(key(operation), result);
            requestHashes.put(key(operation), operation.requestHash());
            return result;
        }

        private void seedTerminalRejection(
                BondedCompanionActionRequest action) {
            seedTerminalRejection(action,
                    BondedCompanionRevivePaymentProof.requestHash(
                            "Ingredient_Life_Essence", 2));
        }

        private void seedTerminalRejection(
                BondedCompanionActionRequest action,
                String requestHash) {
            terminals.put(action.callerNamespace() + ":"
                    + action.idempotencyKey(), terminalRejection());
            requestHashes.put(action.callerNamespace() + ":"
                    + action.idempotencyKey(), requestHash);
        }

        private BondedCompanionStoreResult<BondedCompanionRecord.Profile>
                terminalRejection() {
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                    null, "test-revision-conflict", false);
        }

        private BondedCompanionStoreResult<BondedCompanionRecord.Profile>
                terminalSuccess() {
            BondedCompanionRecord.Profile stored =
                    new BondedCompanionRecord.Profile(
                            PROFILE, OWNER, ROSTER, "hydragon:dragon", ROLE,
                            BondedCompanionState.STORED, 5L,
                            BondedCompanionPayload.of(new byte[]{1}),
                            1L, 2L, Map.of(), "Nimbus", "Miniwyvern",
                            "Female", null, 0L, 1L, null, null);
            return new BondedCompanionStoreResult<>(
                    BondedCompanionStoreResult.Code.APPLIED,
                    stored, null, false);
        }

        private String key(BondedCompanionOperation operation) {
            return operation.callerNamespace() + ":"
                    + operation.idempotencyKey();
        }

        private String key(BondedCompanionOperationProbe operation) {
            return operation.callerNamespace() + ":"
                    + operation.idempotencyKey();
        }
    }

    private static final class PaymentInventory
            implements BondedCompanionActionContext.Inventory {
        private final Map<String, Receipt> active = new HashMap<>();
        private int quantity;
        private int chargeApplications;
        private int refundApplications;

        private PaymentInventory(int quantity) {
            this.quantity = quantity;
        }

        @Override public int availableQuantity(String itemId) {
            return quantity;
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt findCharge(
                String operationId) {
            Receipt receipt = active.get(operationId);
            return receipt == null ? null : receipt.replay();
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt findCharge(
                String operationId, String itemId, int quantity) {
            Receipt receipt = active.get(operationId);
            return receipt == null ? null : receipt.replay();
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            Receipt existing = active.get(operationId);
            if (existing != null) return existing.replay();
            if (this.quantity < quantity) return null;
            this.quantity -= quantity;
            chargeApplications++;
            Receipt receipt = new Receipt(
                    operationId, itemId, quantity, false);
            active.put(operationId, receipt);
            return receipt;
        }

        private int activeCharges() {
            return active.size();
        }

        private final class Receipt
                implements BondedCompanionActionContext.ChargeReceipt {
            private final String operationId;
            private final String itemId;
            private final int charged;
            private final boolean replayed;

            private Receipt(
                    String operationId, String itemId, int charged,
                    boolean replayed) {
                this.operationId = operationId;
                this.itemId = itemId;
                this.charged = charged;
                this.replayed = replayed;
            }

            private Receipt replay() {
                return new Receipt(operationId, itemId, charged, true);
            }

            @Override public String operationId() { return operationId; }
            @Override public String itemId() { return itemId; }
            @Override public int quantity() { return charged; }
            @Override public boolean replayed() { return replayed; }

            @Override
            public boolean refund() {
                if (active.remove(operationId) == null) return true;
                quantity += charged;
                refundApplications++;
                return true;
            }
        }
    }

    private static final class RacingPaymentInventory
            implements BondedCompanionActionContext.Inventory {
        private final CompletableFuture<
                BondedCompanionActionContext.ChargeReceipt> freshReceipt =
                new CompletableFuture<>();
        private final CompletableFuture<Void> replaySettlementEntered =
                new CompletableFuture<>();
        private final CompletableFuture<Boolean> replaySettlement =
                new CompletableFuture<>();
        private int quantity;
        private int chargeApplications;
        private int refundApplications;
        private boolean active;
        private String operationId;

        private RacingPaymentInventory(int quantity) {
            this.quantity = quantity;
        }

        @Override public synchronized int availableQuantity(String itemId) {
            return quantity;
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            return null;
        }

        @Override
        public synchronized CompletionStage<
                BondedCompanionActionContext.ChargeReceipt> consumeExactAsync(
                String operationId, String itemId, int quantity) {
            if (!active) {
                this.operationId = operationId;
                this.quantity -= quantity;
                chargeApplications++;
                active = true;
                return freshReceipt;
            }
            return CompletableFuture.completedFuture(receipt(true));
        }

        private void releaseFreshReceipt() {
            freshReceipt.complete(receipt(false));
        }

        private void finishReplaySettlement() {
            replaySettlement.complete(true);
        }

        private synchronized int activeCharges() {
            return active ? 1 : 0;
        }

        private BondedCompanionActionContext.ChargeReceipt receipt(
                boolean replayed) {
            return new BondedCompanionActionContext.ChargeReceipt() {
                @Override public String operationId() {
                    return RacingPaymentInventory.this.operationId;
                }

                @Override public String itemId() {
                    return "Ingredient_Life_Essence";
                }
                @Override public int quantity() { return 2; }
                @Override public boolean replayed() { return replayed; }
                @Override public boolean refund() { return refundCharge(); }
                @Override public boolean complete() { return false; }

                @Override
                public CompletionStage<Boolean> completeAsync() {
                    if (replayed) {
                        replaySettlementEntered.complete(null);
                        return replaySettlement;
                    }
                    return CompletableFuture.completedFuture(completeCharge());
                }
            };
        }

        private synchronized boolean refundCharge() {
            if (!active) return true;
            active = false;
            quantity += 2;
            refundApplications++;
            return true;
        }

        private synchronized boolean completeCharge() {
            active = false;
            return true;
        }
    }

    private static final class FlakySettlementInventory
            implements BondedCompanionActionContext.Inventory {
        private final IntSupplier publicationCount;
        private Receipt active;
        private int settlementAttempts;
        private int publicationsAtFirstSettlement = -1;

        private FlakySettlementInventory(IntSupplier publicationCount) {
            this.publicationCount = publicationCount;
        }

        @Override public int availableQuantity(String itemId) { return 2; }

        @Override
        public BondedCompanionActionContext.ChargeReceipt findCharge(
                String operationId) {
            return active == null ? null : active.replay();
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            if (active == null) {
                active = new Receipt(operationId, itemId, quantity, false);
            }
            return active;
        }

        private final class Receipt
                implements BondedCompanionActionContext.ChargeReceipt {
            private final String operationId;
            private final String itemId;
            private final int quantity;
            private final boolean replayed;

            private Receipt(String operationId, String itemId, int quantity,
                            boolean replayed) {
                this.operationId = operationId;
                this.itemId = itemId;
                this.quantity = quantity;
                this.replayed = replayed;
            }

            private Receipt replay() {
                return new Receipt(operationId, itemId, quantity, true);
            }

            @Override public String operationId() { return operationId; }
            @Override public String itemId() { return itemId; }
            @Override public int quantity() { return quantity; }
            @Override public boolean replayed() { return replayed; }
            @Override public boolean refund() { return false; }
            @Override public boolean complete() { return false; }

            @Override
            public CompletionStage<Boolean> completeAsync() {
                settlementAttempts++;
                if (settlementAttempts == 1) {
                    publicationsAtFirstSettlement = publicationCount.getAsInt();
                    return CompletableFuture.completedFuture(false);
                }
                active = null;
                return CompletableFuture.completedFuture(true);
            }
        }
    }

    private static final class HistoricalMarkerInventory
            implements BondedCompanionActionContext.Inventory {
        private int markerRemovals;

        @Override public int availableQuantity(String itemId) { return 0; }

        @Override
        public BondedCompanionActionContext.ChargeReceipt findCharge(
                String operationId) {
            return new BondedCompanionActionContext.ChargeReceipt() {
                @Override public String operationId() { return operationId; }
                @Override public boolean replayed() { return true; }
                @Override public boolean historicalPaymentMarker() {
                    return true;
                }
                @Override public boolean quarantined() { return true; }
                @Override public boolean terminalRejectionCleanupSafe() {
                    return true;
                }
                @Override public boolean refund() { return false; }
                @Override public boolean complete() {
                    markerRemovals++;
                    return true;
                }
            };
        }

        @Override
        public BondedCompanionActionContext.ChargeReceipt consumeExact(
                String operationId, String itemId, int quantity) {
            throw new AssertionError("Terminal recovery must not charge");
        }
    }

    private static final class Support
            implements BondedCompanionReviveOperationService.Support {
        private BondedCompanionRecord.Profile profile;
        private final BondedCompanionProfile domain;
        private final BondedCompanionSnapshot snapshot;
        private final long policyRevision;
        private int publications;

        private Support(
                BondedCompanionRecord.Profile profile,
                BondedCompanionProfile domain,
                BondedCompanionSnapshot snapshot,
                long policyRevision) {
            this.profile = profile;
            this.domain = domain;
            this.snapshot = snapshot;
            this.policyRevision = policyRevision;
        }

        private void removeProfile() {
            profile = null;
        }

        @Override public BondedCompanionRecord.Profile profile(
                BondedCompanionActionRequest action) { return profile; }
        @Override public BondedCompanionSnapshot decode(
                BondedCompanionRecord.Profile profile) { return snapshot; }
        @Override public BondedCompanionProfile domain(
                BondedCompanionRecord.Profile profile,
                BondedCompanionSnapshot snapshot) { return domain; }
        @Override public BondedCompanionTransitionService.MutationRequest mutation(
                BondedCompanionActionRequest action, long now) {
            return new BondedCompanionTransitionService.MutationRequest(
                    action.callerNamespace() + ":" + action.idempotencyKey(),
                    action.ownerUuid(), action.expectedRevision(),
                    policyRevision, now);
        }
        @Override public BondedCompanionOperation operation(
                BondedCompanionActionRequest action,
                BondedCompanionPolicy.RevivePrice price, long now) {
            return new BondedCompanionOperation(
                    action.callerNamespace(), action.idempotencyKey(),
                    BondedCompanionRevivePaymentProof.requestHash(
                            price.itemId(), price.quantity()),
                    action.ownerUuid(), action.rosterId(),
                    action.profileId(), BondedCompanionOperation.Type.REVIVE,
                    now, now + 10_000L);
        }
        @Override public long cooldownRemaining(long until, long now) { return 0; }
        @Override public BondedCompanionResult<BondedCompanionProfileView> success(
                BondedCompanionRecord.Profile profile) {
            return new BondedCompanionResult<>(
                    BondedCompanionResultCode.SUCCESS, null, null);
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> storedResult(
                BondedCompanionStoreResult<BondedCompanionRecord.Profile> result) {
            return storeFailure(result);
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> failure(
                BondedCompanionResultCode code, String reason) {
            return new BondedCompanionResult<>(code, null, reason);
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> notFound() {
            return failure(BondedCompanionResultCode.NOT_FOUND, "not-found");
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> policyDenied() {
            return failure(BondedCompanionResultCode.POLICY_DENIED, "policy");
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> internal(
                String reason) {
            return failure(BondedCompanionResultCode.INTERNAL_FAILURE, reason);
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView>
                transitionFailure(
                        BondedCompanionTransitionService.ResultCode code) {
            return failure(BondedCompanionResultCode.INVALID_STATE,
                    code.name());
        }
        @Override public BondedCompanionResult<BondedCompanionProfileView> storeFailure(
                BondedCompanionStoreResult<?> result) {
            BondedCompanionResultCode code = result.code()
                    == BondedCompanionStoreResult.Code.STORAGE_FAILURE
                    ? BondedCompanionResultCode.INTERNAL_FAILURE
                    : BondedCompanionResultCode.REVISION_CONFLICT;
            return failure(code, result.reason());
        }
        @Override public void publishRevived(
                BondedCompanionRecord.Profile profile) { publications++; }
    }
}

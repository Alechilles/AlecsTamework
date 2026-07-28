package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart integration coverage for terminal SQLite/payment reconciliation. */
class BondedCompanionPaymentRecoverySqliteTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final String CALLER = "test:panel";
    private static final String KEY = "revive:restart";
    private static final String ITEM = "Ingredient_Life_Essence";

    @TempDir
    Path tempDir;

    @Test
    void committedReviveRecoversAfterRestartWhenProfileIsAlreadyStored()
            throws Exception {
        Path database = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore beforeRestart =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                beforeRestart.createProfile(operation(
                                "provision", BondedCompanionOperation.Type.PROVISION,
                                "a"), profile()).code());
        markDead(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                beforeRestart.reviveProfile(
                        BondedCompanionRevivePaymentProof.operation(
                                CALLER, KEY, OWNER, "roster-a", "profile-a",
                                ITEM, 2, -8_000L, Long.MAX_VALUE),
                        1L, -8_000L).code());

        BondedCompanionStore afterRestart =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionState.STORED,
                afterRestart.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(Long.MAX_VALUE, expiry(database, CALLER, KEY));
        AtomicInteger completions = new AtomicInteger();
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, KEY, OWNER, "roster-a", "profile-a", 1L);
        BondedCompanionPaymentRecoveryService recovery =
                new BondedCompanionPaymentRecoveryService(
                        afterRestart, () -> -7_000L);

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                        .RETRY_REQUIRED,
                recovery.recover(BondedCompanionPaymentOperationId
                                        .parse(paymentId).orElseThrow(),
                                nullStageInventory())
                        .toCompletableFuture().join());
        assertEquals(Long.MAX_VALUE, expiry(database, CALLER, KEY));

        BondedCompanionPaymentRecoveryService.Outcome outcome =
                recovery.recover(BondedCompanionPaymentOperationId
                                .parse(paymentId).orElseThrow(),
                        inventory(paymentId, completions))
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                .SETTLED_COMMITTED, outcome);
        assertEquals(1, completions.get());
        assertNotEquals(Long.MAX_VALUE, expiry(database, CALLER, KEY));
    }

    @Test
    void savedEscrowBeforeFirstClaimIsClaimedAfterRestartWithoutPolicy()
            throws Exception {
        Path database = tempDir.resolve("prepared-revive.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore beforeRestart =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                beforeRestart.createProfile(operation(
                                "prepared-profile", BondedCompanionOperation.Type.PROVISION,
                                "c"), profile()).code());
        markDead(database);
        String key = "revive:prepared-crash";
        BondedCompanionOperationProbe probe = new BondedCompanionOperationProbe(
                CALLER, key, OWNER, "roster-a", "profile-a",
                BondedCompanionOperation.Type.REVIVE, 1L);
        assertTrue(beforeRestart.findProfileOperationByIdentity(probe).isEmpty());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE bonded_companion_profile
                    SET policy_json = '{"revive":"disabled"}'
                    WHERE profile_id = 'profile-a'
                    """));
        }

        BondedCompanionStore afterRestart =
                new SqliteBondedCompanionDatabase(database);
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, key, OWNER, "roster-a", "profile-a", 1L);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        AtomicInteger refunds = new AtomicInteger();
        BondedCompanionPaymentRecoveryService.Outcome outcome =
                new BondedCompanionPaymentRecoveryService(
                        afterRestart, () -> -7_000L,
                        ignored -> publications.incrementAndGet())
                        .recover(BondedCompanionPaymentOperationId
                                        .parse(paymentId).orElseThrow(),
                                preparedInventory(
                                        paymentId, completions, refunds))
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                .SETTLED_COMMITTED, outcome);
        assertEquals(BondedCompanionState.STORED,
                afterRestart.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(1, completions.get());
        assertEquals(0, refunds.get());
        assertEquals(1, publications.get());
        assertNotEquals(Long.MAX_VALUE, expiry(database, CALLER, key));
    }

    @Test
    void freshRecoveryPublishesBeforeSettlementAndNeverPublishesReplay()
            throws Exception {
        Path database = tempDir.resolve("recovery-event-once.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(
                        "event-profile", BondedCompanionOperation.Type.PROVISION,
                        "2"), profile()).code());
        markDead(database);
        String key = "revive:event-once";
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, key, OWNER, "roster-a", "profile-a", 1L);
        AtomicInteger settlements = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        BondedCompanionActionContext.Inventory inventory =
                flakyPreparedInventory(paymentId, settlements);
        BondedCompanionPaymentRecoveryService recovery =
                new BondedCompanionPaymentRecoveryService(
                        store, () -> -7_000L,
                        ignored -> publications.incrementAndGet());

        var first = recovery.recover(BondedCompanionPaymentOperationId
                        .parse(paymentId).orElseThrow(), inventory)
                .toCompletableFuture().join();
        var retry = recovery.recover(BondedCompanionPaymentOperationId
                        .parse(paymentId).orElseThrow(), inventory)
                .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                .RETRY_REQUIRED, first);
        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                .SETTLED_COMMITTED, retry);
        assertEquals(2, settlements.get());
        assertEquals(1, publications.get());
    }

    @Test
    void terminalRequestHashMismatchQuarantinesWithoutSettlingEscrow()
            throws Exception {
        Path database = tempDir.resolve("terminal-hash-mismatch.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation(
                        "terminal-hash-profile",
                        BondedCompanionOperation.Type.PROVISION,
                        "1"), profile()).code());
        markDead(database);
        String key = "revive:terminal-hash-mismatch";
        BondedCompanionOperation committed =
                BondedCompanionRevivePaymentProof.operation(
                        CALLER, key, OWNER, "roster-a", "profile-a",
                        ITEM, 2, -8_000L, Long.MAX_VALUE);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.reviveProfile(committed, 1L, -8_000L).code());
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, key, OWNER, "roster-a", "profile-a", 1L);
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger refunds = new AtomicInteger();

        BondedCompanionPaymentRecoveryService.Outcome outcome =
                new BondedCompanionPaymentRecoveryService(store, () -> -7_000L)
                        .recover(BondedCompanionPaymentOperationId.parse(
                                        paymentId).orElseThrow(),
                                preparedInventory(paymentId,
                                        "Ingredient_Wrong_Essence", 2,
                                        completions, refunds))
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome.QUARANTINED,
                outcome);
        assertEquals(0, completions.get());
        assertEquals(0, refunds.get());
        assertEquals(Long.MAX_VALUE, expiry(database, CALLER, key));
    }

    @Test
    void reorderedFrozenRecipeQuarantinePreventsRecoveryMutation()
            throws Exception {
        Path database = tempDir.resolve("reordered-frozen-recipe.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation("reordered", 
                        BondedCompanionOperation.Type.PROVISION, "a"),
                        profile()).code());
        markDead(database);
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, "revive:reordered", OWNER, "roster-a", "profile-a", 1L);
        AtomicInteger publications = new AtomicInteger();
        BondedCompanionActionContext.Inventory inventory =
                quarantinedInventory(paymentId);

        BondedCompanionPaymentRecoveryService.Outcome outcome =
                new BondedCompanionPaymentRecoveryService(store, () -> -7_000L,
                        ignored -> publications.incrementAndGet())
                        .recover(BondedCompanionPaymentOperationId.parse(paymentId)
                                .orElseThrow(), inventory)
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome.QUARANTINED,
                outcome);
        assertEquals(BondedCompanionState.DEAD,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(0, publications.get());
    }

    private BondedCompanionActionContext.Inventory quarantinedInventory(
            String paymentId) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }
            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return new BondedCompanionActionContext.ChargeReceipt() {
                    @Override public String operationId() { return paymentId; }
                    @Override public boolean quarantined() { return true; }
                    @Override public boolean refund() { return false; }
                };
            }
            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId, int quantity) {
                throw new AssertionError("recovery must not charge");
            }
        };
    }

    @Test
    void reorderedFrozenRecipeQuarantinePreventsRecoveryMutation()
            throws Exception {
        Path database = tempDir.resolve("reordered-frozen-recipe.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        BondedCompanionStore store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation("reordered", 
                        BondedCompanionOperation.Type.PROVISION, "a"),
                        profile()).code());
        markDead(database);
        String paymentId = BondedCompanionPaymentOperationId.create(
                CALLER, "revive:reordered", OWNER, "roster-a", "profile-a", 1L);
        AtomicInteger publications = new AtomicInteger();
        BondedCompanionActionContext.Inventory inventory =
                quarantinedInventory(paymentId);

        BondedCompanionPaymentRecoveryService.Outcome outcome =
                new BondedCompanionPaymentRecoveryService(store, () -> -7_000L,
                        ignored -> publications.incrementAndGet())
                        .recover(BondedCompanionPaymentOperationId.parse(paymentId)
                                .orElseThrow(), inventory)
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome.QUARANTINED,
                outcome);
        assertEquals(BondedCompanionState.DEAD,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(0, publications.get());
    }

    private BondedCompanionActionContext.Inventory quarantinedInventory(
            String paymentId) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }
            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return new BondedCompanionActionContext.ChargeReceipt() {
                    @Override public String operationId() { return paymentId; }
                    @Override public boolean quarantined() { return true; }
                    @Override public boolean refund() { return false; }
                };
            }
            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId, int quantity) {
                throw new AssertionError("recovery must not charge");
            }
        };
    }

    private BondedCompanionActionContext.Inventory nullStageInventory() {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }

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
    }

    private BondedCompanionActionContext.Inventory inventory(
            String paymentId,
            AtomicInteger completions
    ) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) {
                return 0;
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return new BondedCompanionActionContext.ChargeReceipt() {
                    @Override public String operationId() {
                        return paymentId;
                    }

                    @Override public String itemId() { return ITEM; }

                    @Override public int quantity() { return 2; }

                    @Override public boolean refund() {
                        throw new AssertionError("Committed proof must not refund");
                    }

                    @Override public boolean complete() {
                        completions.incrementAndGet();
                        return true;
                    }
                };
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId,
                                 int quantity) {
                throw new AssertionError("Recovery must not charge again");
            }
        };
    }

    private BondedCompanionActionContext.Inventory preparedInventory(
            String paymentId,
            AtomicInteger completions,
            AtomicInteger refunds
    ) {
        return preparedInventory(
                paymentId, ITEM, 2, completions, refunds);
    }

    private BondedCompanionActionContext.Inventory preparedInventory(
            String paymentId,
            String itemId,
            int quantity,
            AtomicInteger completions,
            AtomicInteger refunds
    ) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return new BondedCompanionActionContext.ChargeReceipt() {
                    @Override public String operationId() { return paymentId; }
                    @Override public String itemId() { return itemId; }
                    @Override public int quantity() { return quantity; }
                    @Override public boolean refund() {
                        refunds.incrementAndGet();
                        return true;
                    }
                    @Override public boolean complete() {
                        completions.incrementAndGet();
                        return true;
                    }
                };
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId,
                                 int quantity) {
                throw new AssertionError("Recovery must not charge again");
            }
        };
    }

    private BondedCompanionActionContext.Inventory flakyPreparedInventory(
            String paymentId,
            AtomicInteger settlements
    ) {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    findCharge(String operationId) {
                return receipt();
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId,
                                 int quantity) {
                return receipt();
            }

            private BondedCompanionActionContext.ChargeReceipt receipt() {
                return new BondedCompanionActionContext.ChargeReceipt() {
                    @Override public String operationId() { return paymentId; }
                    @Override public String itemId() { return ITEM; }
                    @Override public int quantity() { return 2; }
                    @Override public boolean refund() { return false; }
                    @Override public boolean complete() {
                        return settlements.incrementAndGet() > 1;
                    }
                };
            }
        };
    }

    private void markDead(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE bonded_companion_profile
                    SET state = 'DEAD', revision = 1, died_at_ms = -9000
                    WHERE profile_id = 'profile-a'
                    """));
        }
    }

    private long expiry(Path database, String caller, String key)
            throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT expires_at_ms FROM bonded_companion_operation
                     WHERE caller_namespace = ? AND idempotency_key = ?
                     """)) {
            statement.setString(1, caller);
            statement.setString(2, key);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                return row.getLong(1);
            }
        }
    }

    private BondedCompanionOperation operation(
            String key,
            BondedCompanionOperation.Type type,
            String hashCharacter
    ) {
        return new BondedCompanionOperation(
                CALLER, key, hashCharacter.repeat(64), OWNER,
                "roster-a", "profile-a", type, -10_000L, 10_000L);
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                "profile-a", OWNER, "roster-a", "family:wolf",
                "role:companion", BondedCompanionState.STORED, 0L,
                snapshotPayload(),
                -10_000L, -10_000L, Map.of(), "Wolf", "Wolf", "Female",
                null, 0L, 0L, null, null);
    }

    private BondedCompanionPayload snapshotPayload() {
        BondedCompanionSnapshot snapshot = BondedCompanionSnapshot.of(
                new CoopResidentStateSnapshot(
                        UUID.fromString(
                                "20000000-0000-0000-0000-000000000001"),
                        null, -1, "role:companion", null,
                        new TameworkOwnerComponent(OWNER, null), null, null,
                        null, null, null, null, null, null, null, null,
                        100.0D, 100.0D, 100.0D, -10_000L),
                Map.of());
        return BondedCompanionPayload.of(
                new BondedCompanionSnapshotCodec().encode(snapshot)
                        .getBytes(StandardCharsets.UTF_8));
    }
}

package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.operation
        .BondedCompanionPaymentOperationId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restart integration coverage for terminal SQLite/payment reconciliation. */
class BondedCompanionPaymentRecoverySqliteTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final String CALLER = "test:panel";
    private static final String KEY = "revive:restart";

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
                beforeRestart.reviveProfile(operation(
                                KEY, BondedCompanionOperation.Type.REVIVE, "b"),
                        1L, -8_000L).code());

        BondedCompanionStore afterRestart =
                new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionState.STORED,
                afterRestart.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
        assertEquals(1, afterRestart.listAwaitingProfilePaymentSettlements(
                OWNER, 8).size());
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
        assertEquals(1, afterRestart.listAwaitingProfilePaymentSettlements(
                OWNER, 8).size());

        BondedCompanionPaymentRecoveryService.Outcome outcome =
                recovery.recover(BondedCompanionPaymentOperationId
                                .parse(paymentId).orElseThrow(),
                        inventory(paymentId, completions))
                        .toCompletableFuture().join();

        assertEquals(BondedCompanionPaymentRecoveryService.Outcome
                .SETTLED_COMMITTED, outcome);
        assertEquals(1, completions.get());
        assertTrue(afterRestart.listAwaitingProfilePaymentSettlements(
                OWNER, 8).isEmpty());
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
                BondedCompanionPayload.of(
                        "full-snapshot".getBytes(StandardCharsets.UTF_8)),
                -10_000L, -10_000L, Map.of(), "Wolf", "Wolf", "Female",
                null, 0L, 0L, null, null);
    }
}

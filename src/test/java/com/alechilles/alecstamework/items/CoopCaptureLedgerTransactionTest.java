package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies coop ledger capture is acknowledged and exactly compensatable. */
class CoopCaptureLedgerTransactionTest {
    private final CommandLinkedNpcCoopService.CoopSlotContext context =
            CommandLinkedNpcCoopService.CoopSlotContext.of("world", "coop", 1, 2, 3, 0);
    private final UUID npcUuid = UUID.randomUUID();

    @Test
    void silentCaptureNoOpIsDeniedAndPreviousSlotIsRestored() {
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous = releasedSnapshot();
        FakeLedger ledger = new FakeLedger(previous, Mode.NO_OP);
        CoopCaptureLedgerTransaction.Preparation result = transaction(ledger).prepare(request());

        assertFalse(result.prepared());
        assertEquals("coop-capture-ledger-not-acknowledged", result.reason());
        assertSame(previous, ledger.current);
        assertEquals(1, ledger.restoreCount);
    }

    @Test
    void exceptionAfterMutationRestoresExactPreviousSlot() {
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous = releasedSnapshot();
        FakeLedger ledger = new FakeLedger(previous, Mode.THROW_AFTER_MUTATION);
        CoopCaptureLedgerTransaction.Preparation result = transaction(ledger).prepare(request());

        assertFalse(result.prepared());
        assertEquals("coop-capture-ledger-prepare-failed", result.reason());
        assertSame(previous, ledger.current);
        assertEquals(1, ledger.restoreCount);
    }

    @Test
    void ownerWriteFailureCompensatesPreparedCaptureOnce() {
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous = releasedSnapshot();
        FakeLedger ledger = new FakeLedger(previous, Mode.SUCCESS);
        CoopCaptureLedgerTransaction transaction = transaction(ledger);
        CoopCaptureLedgerTransaction.Preparation result = transaction.prepare(request());

        assertTrue(result.prepared());
        assertEquals(npcUuid, ledger.current.housedNpcUuid());
        assertTrue(transaction.compensate(result.token()));
        assertSame(previous, ledger.current);
        assertFalse(transaction.compensate(result.token()));
    }

    private CoopCaptureLedgerTransaction transaction(FakeLedger ledger) {
        return new CoopCaptureLedgerTransaction(ledger);
    }

    private CoopCaptureLedgerTransaction.CaptureRequest request() {
        return new CoopCaptureLedgerTransaction.CaptureRequest(
                npcUuid, "chicken", context, null, null, null, null
        );
    }

    private CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot releasedSnapshot() {
        return new CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot(
                null,
                UUID.randomUUID(),
                null,
                new String[0],
                "chicken",
                null,
                10L,
                20L,
                null
        );
    }

    private enum Mode {
        SUCCESS,
        NO_OP,
        THROW_AFTER_MUTATION
    }

    private final class FakeLedger implements CoopCaptureLedgerTransaction.LedgerAdapter {
        private CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current;
        private final Mode mode;
        private int restoreCount;

        private FakeLedger(CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current, Mode mode) {
            this.current = current;
            this.mode = mode;
        }

        @Override
        public CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot snapshot(
                CommandLinkedNpcCoopService.CoopSlotContext ignored
        ) {
            return current;
        }

        @Override
        public void capture(CoopCaptureLedgerTransaction.CaptureRequest request) {
            if (mode == Mode.NO_OP) {
                return;
            }
            current = new CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot(
                    request.npcUuid(), null, request.ownerId(), request.toolIds(), request.roleId(),
                    request.displayName(), 30L, 0L, request.stateSnapshot()
            );
            if (mode == Mode.THROW_AFTER_MUTATION) {
                throw new IllegalStateException("injected capture failure");
            }
        }

        @Override
        public void restore(
                CommandLinkedNpcCoopService.CoopSlotContext ignored,
                CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous
        ) {
            current = previous;
            restoreCount++;
        }
    }
}

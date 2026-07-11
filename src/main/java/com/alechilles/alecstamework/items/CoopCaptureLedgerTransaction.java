package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Prepares and compensates one acknowledged coop-ledger capture mutation. */
final class CoopCaptureLedgerTransaction {
    private final LedgerAdapter ledger;

    CoopCaptureLedgerTransaction(@Nonnull LedgerAdapter ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Nonnull
    Preparation prepare(@Nonnull CaptureRequest request) {
        Objects.requireNonNull(request, "request");
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous = ledger.snapshot(request.context());
        try {
            ledger.capture(request);
            CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot current = ledger.snapshot(request.context());
            if (current == null || !request.npcUuid().equals(current.housedNpcUuid())) {
                ledger.restore(request.context(), previous);
                return Preparation.denied("coop-capture-ledger-not-acknowledged");
            }
            return Preparation.prepared(new Token(request.context(), previous));
        } catch (RuntimeException | LinkageError failure) {
            try {
                ledger.restore(request.context(), previous);
            } catch (RuntimeException | LinkageError compensationFailure) {
                failure.addSuppressed(compensationFailure);
                return Preparation.denied("coop-capture-ledger-compensation-failed");
            }
            return Preparation.denied("coop-capture-ledger-prepare-failed");
        }
    }

    boolean compensate(@Nullable Token token) {
        if (token == null || !token.open.compareAndSet(true, false)) {
            return false;
        }
        ledger.restore(token.context, token.previous);
        return true;
    }

    void complete(@Nullable Token token) {
        if (token != null) {
            token.open.set(false);
        }
    }

    interface LedgerAdapter {
        @Nullable
        CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot snapshot(
                @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context
        );

        void capture(@Nonnull CaptureRequest request);

        void restore(
                @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context,
                @Nullable CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous
        );
    }

    record CaptureRequest(
            @Nonnull UUID npcUuid,
            @Nonnull String roleId,
            @Nonnull CommandLinkedNpcCoopService.CoopSlotContext context,
            @Nullable UUID ownerId,
            @Nullable String[] toolIds,
            @Nullable String displayName,
            @Nullable CoopResidentStateSnapshotService.CoopResidentStateSnapshot stateSnapshot
    ) {
        CaptureRequest {
            Objects.requireNonNull(npcUuid, "npcUuid");
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(context, "context");
            toolIds = toolIds == null ? null : toolIds.clone();
        }

        @Override
        public String[] toolIds() {
            return toolIds == null ? null : toolIds.clone();
        }
    }

    record Preparation(boolean prepared, @Nullable Token token, @Nonnull String reason) {
        static Preparation prepared(@Nonnull Token token) {
            return new Preparation(true, token, "coop-capture-ledger-prepared");
        }

        static Preparation denied(@Nonnull String reason) {
            return new Preparation(false, null, reason);
        }
    }

    static final class Token {
        private final CommandLinkedNpcCoopService.CoopSlotContext context;
        private final CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Token(CommandLinkedNpcCoopService.CoopSlotContext context,
                      @Nullable CommandLinkedNpcCoopService.CoopLedgerSlotSnapshot previous) {
            this.context = context;
            this.previous = previous;
        }
    }
}

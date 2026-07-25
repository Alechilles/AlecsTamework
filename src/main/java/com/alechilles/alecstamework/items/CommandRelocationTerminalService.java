package com.alechilles.alecstamework.items;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reports dropped relocations and consumes eligible import recovery evidence.
 */
final class CommandRelocationTerminalService {
    private final CommandRelocationDropReporter reporter;
    private final ImportedRecallRecoverySink importedRecallRecovery;
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final BiPredicate<UUID, PendingRelocation> remover;
    private final java.util.function.BiConsumer<Level, String> diagnostics;

    CommandRelocationTerminalService(
            @Nullable HytaleLogger logger,
            @Nonnull ImportedRecallRecoverySink importedRecallRecovery,
            @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
            @Nonnull BiPredicate<UUID, PendingRelocation> remover,
            @Nonnull java.util.function.BiConsumer<Level, String> diagnostics
    ) {
        this.reporter = new CommandRelocationDropReporter(logger, diagnostics);
        this.importedRecallRecovery = Objects.requireNonNull(
                importedRecallRecovery,
                "importedRecallRecovery"
        );
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.remover = Objects.requireNonNull(remover, "remover");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void cancel(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending != null && remover.test(npcUuid, pending)) {
            pending.markCrossWorldTransferFinished();
            diagnostics.accept(
                    Level.INFO,
                    "Cancelled pending relocation for npc=" + npcUuid
            );
        }
    }

    void finish(
            UUID npcUuid,
            PendingRelocation pending,
            long droppedAtMs,
            boolean cleanRetryExhaustion
    ) {
        if (npcUuid == null
                || pending == null
                || !remover.test(npcUuid, pending)) {
            return;
        }
        reporter.report(pending, droppedAtMs);
        if (cleanRetryExhaustion
                && pending.explicitRecall
                && pending.ownerUuid != null
                && !pending.physicalMutationAttempted()) {
            importedRecallRecovery.recover(
                    new ImportedRecallRecoverySink.RecallFailure(
                            npcUuid,
                            pending.ownerUuid,
                            pending.queuedAtMs,
                            droppedAtMs
                    )
            );
        }
    }
}

package com.alechilles.alecstamework.items;

import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
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
    private final Consumer<PendingRelocation> retryHandler;

    CommandRelocationTerminalService(
            @Nullable HytaleLogger logger,
            @Nonnull ImportedRecallRecoverySink importedRecallRecovery,
            @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
            @Nonnull BiPredicate<UUID, PendingRelocation> remover,
            @Nonnull java.util.function.BiConsumer<Level, String> diagnostics,
            @Nonnull Consumer<PendingRelocation> retryHandler
    ) {
        this.reporter = new CommandRelocationDropReporter(logger, diagnostics);
        this.importedRecallRecovery = Objects.requireNonNull(
                importedRecallRecovery,
                "importedRecallRecovery"
        );
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.remover = Objects.requireNonNull(remover, "remover");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.retryHandler = Objects.requireNonNull(
                retryHandler, "retryHandler"
        );
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
            recover(npcUuid, pending, droppedAtMs);
        }
    }

    private void recover(
            UUID npcUuid,
            PendingRelocation pending,
            long droppedAtMs
    ) {
        try {
            importedRecallRecovery.recover(
                    new ImportedRecallRecoverySink.RecallFailure(
                            npcUuid,
                            pending.ownerUuid,
                            pending.queuedAtMs,
                            droppedAtMs,
                            pending.destinationWorldName,
                            new ImportedRecallRecoverySink.RecallDestination(
                                    pending.destinationWorldName,
                                    pending.destination.x,
                                    pending.destination.y,
                                    pending.destination.z
                            ),
                            pending.completedSourceSections()
                    )
            ).whenComplete((outcome, problem) -> {
                if (problem == null && outcome
                        == ImportedRecallRecoverySink.RecoveryOutcome
                        .RETRY_REQUIRED) {
                    retryHandler.accept(pending);
                }
            });
        } catch (RuntimeException rejected) {
            diagnostics.accept(
                    Level.WARNING,
                    "Recall recovery dispatch failed for npc=" + npcUuid
                            + ", reason="
                            + rejected.getClass().getSimpleName()
            );
        }
    }
}

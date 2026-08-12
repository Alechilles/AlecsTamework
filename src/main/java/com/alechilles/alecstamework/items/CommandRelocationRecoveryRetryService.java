package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/** Starts the second relocation window after stale active reconciliation. */
final class CommandRelocationRecoveryRetryService {
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final CommandRelocationWorldAccess worldAccess;
    private final CommandRelocationQueueCoordinator queueCoordinator;
    private final BiConsumer<Level, String> diagnostics;

    CommandRelocationRecoveryRetryService(
            Map<UUID, PendingRelocation> pendingByNpc,
            CommandRelocationWorldAccess worldAccess,
            CommandRelocationQueueCoordinator queueCoordinator,
            BiConsumer<Level, String> diagnostics
    ) {
        this.pendingByNpc = Objects.requireNonNull(
                pendingByNpc, "pendingByNpc"
        );
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
        this.queueCoordinator = Objects.requireNonNull(
                queueCoordinator, "queueCoordinator"
        );
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void retry(PendingRelocation pending) {
        if (pending == null || newerRelocationExists(pending)) {
            return;
        }
        World world = worldAccess.resolveLoadedWorld(
                pending.destinationWorldName
        );
        if (world == null) {
            diagnostics.accept(
                    Level.WARNING,
                    "Unable to start second recall window for npc="
                            + pending.npcUuid
                            + ": destination world is not loaded"
            );
            return;
        }
        worldAccess.execute(
                world,
                () -> queue(world, pending),
                () -> diagnostics.accept(
                        Level.WARNING,
                        "Unable to dispatch second recall window for npc="
                                + pending.npcUuid
                )
        );
    }

    private void queue(World world, PendingRelocation pending) {
        if (newerRelocationExists(pending)) {
            return;
        }
        queueCoordinator.queue(
                world,
                pending.npcUuid,
                pending.destination,
                pending.ownerUuid,
                pending.assignOwnerAsMasterTarget,
                pending.clearLockedTarget,
                pending.state,
                pending.subState,
                0L,
                pending.sourceHintPosition,
                pending.alternateSourceHintPosition,
                pending.allowCrossWorldTransfer,
                pending.onTransferFailure,
                null,
                true
        );
    }

    private boolean newerRelocationExists(PendingRelocation pending) {
        if (!pendingByNpc.containsKey(pending.npcUuid)) {
            return false;
        }
        diagnostics.accept(
                Level.INFO,
                "Skipped stale second recall window because a newer "
                        + "relocation is pending for npc=" + pending.npcUuid
        );
        return true;
    }
}

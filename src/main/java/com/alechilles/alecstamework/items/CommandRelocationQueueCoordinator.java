package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Builds, coalesces, and schedules relocation requests.
 *
 * <p>This keeps queue ownership separate from the relocation orchestrator's
 * live-entity and cross-world transfer work.</p>
 */
final class CommandRelocationQueueCoordinator {
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final Map<UUID, Vector3d> lastKnownByNpc;
    private final Map<UUID, World> knownWorldByNpc;
    private final CommandRelocationChunkRequestService chunkRequests;
    private final CommandRelocationApplyScheduler applyScheduler;
    private final DropHandler dropHandler;
    private final BiConsumer<Level, String> diagnostics;

    CommandRelocationQueueCoordinator(
            @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
            @Nonnull Map<UUID, Vector3d> lastKnownByNpc,
            @Nonnull Map<UUID, World> knownWorldByNpc,
            @Nonnull CommandRelocationChunkRequestService chunkRequests,
            @Nonnull CommandRelocationApplyScheduler applyScheduler,
            @Nonnull DropHandler dropHandler,
            @Nonnull BiConsumer<Level, String> diagnostics
    ) {
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.lastKnownByNpc = Objects.requireNonNull(lastKnownByNpc, "lastKnownByNpc");
        this.knownWorldByNpc = Objects.requireNonNull(knownWorldByNpc, "knownWorldByNpc");
        this.chunkRequests = Objects.requireNonNull(chunkRequests, "chunkRequests");
        this.applyScheduler = Objects.requireNonNull(applyScheduler, "applyScheduler");
        this.dropHandler = Objects.requireNonNull(dropHandler, "dropHandler");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    void queue(
            World world,
            UUID npcUuid,
            Vector3d destination,
            @Nullable UUID ownerUuid,
            boolean assignOwnerAsMasterTarget,
            boolean clearLockedTarget,
            @Nullable String state,
            @Nullable String subState,
            long delayMs,
            @Nullable Vector3d sourceHintPosition,
            @Nullable Vector3d alternateSourceHintPosition,
            boolean allowCrossWorldTransfer,
            @Nullable TwCompanionConfig.TransferFailurePolicy onTransferFailure,
            @Nullable String[] requiredStateFilter,
            boolean explicitRecall
    ) {
        if (world == null || npcUuid == null || destination == null) {
            return;
        }
        long queuedAtMs = System.currentTimeMillis();
        PendingRelocation pending = new PendingRelocation(
                npcUuid,
                new Vector3d(destination),
                world.getName(),
                copy(sourceHintPosition),
                copy(alternateSourceHintPosition),
                ownerUuid,
                assignOwnerAsMasterTarget,
                clearLockedTarget,
                state,
                subState,
                queuedAtMs + Math.max(0L, delayMs),
                queuedAtMs,
                allowCrossWorldTransfer,
                onTransferFailure,
                requiredStateFilter,
                explicitRecall
        );
        rememberSourceHint(npcUuid, sourceHintPosition, alternateSourceHintPosition);
        PendingRelocation current = pendingByNpc.get(npcUuid);
        if (current != null && (current.hasSameCommandIntent(pending)
                || current.physicalMutationAttempted())) {
            requestAndSchedule(world, npcUuid, current, 0L);
            return;
        }
        chunkRequests.open(pending);
        PendingRelocation replaced = pendingByNpc.put(npcUuid, pending);
        releaseReplaced(npcUuid, replaced);
        logQueued(world, pending, requiredStateFilter);
        requestAndSchedule(world, npcUuid, pending, delayMs);
    }

    private void rememberSourceHint(
            UUID npcUuid,
            @Nullable Vector3d sourceHintPosition,
            @Nullable Vector3d alternateSourceHintPosition
    ) {
        Vector3d source = sourceHintPosition != null
                ? sourceHintPosition : alternateSourceHintPosition;
        if (source != null) {
            // A persisted hint must not replace a newer live observation.
            lastKnownByNpc.putIfAbsent(npcUuid, new Vector3d(source));
        }
    }

    private void releaseReplaced(
            UUID npcUuid,
            @Nullable PendingRelocation replaced
    ) {
        if (replaced == null) {
            return;
        }
        chunkRequests.release(replaced);
        replaced.markCrossWorldTransferFinished();
        if (replaced.physicalMutationAttempted()) {
            dropHandler.drop(
                    knownWorldByNpc.get(npcUuid),
                    npcUuid,
                    replaced,
                    System.currentTimeMillis()
            );
        }
    }

    private void requestAndSchedule(
            World world,
            UUID npcUuid,
            PendingRelocation pending,
            long delayMs
    ) {
        chunkRequests.requestDestinationAndSource(world, pending);
        applyScheduler.schedule(world, npcUuid, Math.max(0L, delayMs));
    }

    private void logQueued(
            World world,
            PendingRelocation pending,
            @Nullable String[] requiredStateFilter
    ) {
        if (!pending.allowCrossWorldTransfer
                && (requiredStateFilter == null
                || requiredStateFilter.length == 0)) {
            return;
        }
        diagnostics.accept(
                Level.INFO,
                "Queued relocation npc="
                        + pending.npcUuid
                        + ", destinationWorld="
                        + world.getName()
                        + ", allowCrossWorldTransfer="
                        + pending.allowCrossWorldTransfer
                        + ", onTransferFailure="
                        + pending.onTransferFailure
                        + ", requiredStateFilter="
                        + describeStateFilter(requiredStateFilter)
        );
    }

    @Nullable
    private static Vector3d copy(@Nullable Vector3d value) {
        return value == null ? null : new Vector3d(value);
    }

    private static String describeStateFilter(@Nullable String[] stateFilter) {
        return stateFilter == null || stateFilter.length == 0
                ? "[]" : java.util.Arrays.toString(stateFilter);
    }

    @FunctionalInterface
    interface DropHandler {
        void drop(
                @Nullable World world,
                @Nonnull UUID npcUuid,
                @Nonnull PendingRelocation pending,
                long droppedAtMs
        );
    }
}

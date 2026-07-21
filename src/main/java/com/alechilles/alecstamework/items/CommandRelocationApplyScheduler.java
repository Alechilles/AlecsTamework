package com.alechilles.alecstamework.items;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/** Owns coalesced delayed dispatch for queued relocation apply attempts. */
final class CommandRelocationApplyScheduler {
    private final Map<UUID, PendingRelocation> pendingByNpc;
    private final CommandRelocationWorldAccess worldAccess;
    private final ApplyAction applyAction;
    private final DispatchFailureHandler failureHandler;

    CommandRelocationApplyScheduler(
            @Nonnull Map<UUID, PendingRelocation> pendingByNpc,
            @Nonnull CommandRelocationWorldAccess worldAccess,
            @Nonnull ApplyAction applyAction,
            @Nonnull DispatchFailureHandler failureHandler
    ) {
        this.pendingByNpc = Objects.requireNonNull(pendingByNpc, "pendingByNpc");
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
        this.applyAction = Objects.requireNonNull(applyAction, "applyAction");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void schedule(World world, UUID npcUuid, long delayMs) {
        if (world == null || npcUuid == null) {
            return;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        long dueAtMs = System.currentTimeMillis() + safeDelayMs;
        if (!pending.reserveScheduledApply(dueAtMs)) {
            return;
        }
        Runnable dispatch = () -> worldAccess.execute(
                world,
                () -> applyIfScheduled(world, npcUuid, pending, dueAtMs),
                () -> failureHandler.onRejected(world, npcUuid, pending)
        );
        try {
            CompletableFuture.runAsync(
                    dispatch,
                    CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
            );
        } catch (RuntimeException | LinkageError exception) {
            dispatch.run();
        }
    }

    private void applyIfScheduled(World world,
                                  UUID npcUuid,
                                  PendingRelocation pending,
                                  long dueAtMs) {
        if (pendingByNpc.get(npcUuid) != pending || !pending.consumeScheduledApply(dueAtMs)) {
            return;
        }
        applyAction.apply(world, npcUuid);
    }

    @FunctionalInterface
    interface ApplyAction {
        void apply(@Nonnull World world, @Nonnull UUID npcUuid);
    }

    @FunctionalInterface
    interface DispatchFailureHandler {
        void onRejected(
                @Nonnull World world,
                @Nonnull UUID npcUuid,
                @Nonnull PendingRelocation pending
        );
    }
}

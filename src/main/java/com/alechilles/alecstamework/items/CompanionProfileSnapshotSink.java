package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceDrainResult;
import com.alechilles.alecstamework.items.persistence.maintenance.MaintenanceMetricsSnapshot;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Persistence-neutral boundary for publishing one observed live profile state.
 *
 * <p>The sink owns translation to the selected persistence engine. The
 * snapshot and exact world key are frozen on the world thread; snapshot
 * capture itself remains an ECS-only concern.</p>
 */
@FunctionalInterface
public interface CompanionProfileSnapshotSink {
    CompletionStage<Void> publish(
            @Nonnull CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            @Nonnull String worldKey
    );

    /** Waits for work accepted for one observed NPC at call time. */
    @Nonnull
    default CompletionStage<Void> flush(@Nonnull UUID npcUuid) {
        return CompletableFuture.completedFuture(null);
    }

    /** Returns passive bounded-admission evidence. */
    @Nonnull
    default MaintenanceMetricsSnapshot metrics() {
        return new MaintenanceMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Stops admission and drains retained profile work. */
    @Nonnull
    default MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        return new MaintenanceDrainResult(true, 0, 0, 0);
    }

    /** No-op sink for tests and runtimes that intentionally disable publication. */
    static CompanionProfileSnapshotSink ignore() {
        return (snapshot, worldKey) ->
                CompletableFuture.completedFuture(null);
    }
}

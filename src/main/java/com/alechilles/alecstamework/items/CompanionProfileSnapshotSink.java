package com.alechilles.alecstamework.items;

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

    /** No-op sink for tests and runtimes that intentionally disable publication. */
    static CompanionProfileSnapshotSink ignore() {
        return (snapshot, worldKey) ->
                CompletableFuture.completedFuture(null);
    }
}

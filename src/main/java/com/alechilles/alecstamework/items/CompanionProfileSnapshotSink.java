package com.alechilles.alecstamework.items;

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
    void publish(
            @Nonnull CommandLinkedNpcStateSnapshotService.LiveLinkedNpcSnapshot snapshot,
            @Nonnull String worldKey
    );

    /** No-op sink for tests and runtimes that intentionally disable publication. */
    static CompanionProfileSnapshotSink ignore() {
        return (snapshot, worldKey) -> {
        };
    }
}

package com.alechilles.alecstamework.items;

import javax.annotation.Nonnull;

/**
 * Persistence-neutral boundary for publishing one observed live profile state.
 *
 * <p>The sink owns translation to the selected persistence engine. Snapshot
 * capture itself remains an ECS-only concern.</p>
 */
@FunctionalInterface
public interface CompanionProfileSnapshotSink {
    void publish(
            @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot
    );

    /** No-op sink for tests and runtimes that intentionally disable publication. */
    static CompanionProfileSnapshotSink ignore() {
        return snapshot -> {
        };
    }
}

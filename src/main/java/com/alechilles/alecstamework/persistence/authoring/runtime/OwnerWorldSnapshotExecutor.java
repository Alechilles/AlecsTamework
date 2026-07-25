package com.alechilles.alecstamework.persistence.authoring.runtime;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Dispatches one read-only owner snapshot to the owner's current world thread. */
@FunctionalInterface
interface OwnerWorldSnapshotExecutor {
    @Nonnull
    <T> CompletionStage<T> read(
            @Nonnull UUID ownerUuid,
            @Nullable String expectedWorldKey,
            @Nonnull WorldSnapshotRead<T> read
    );

    /** World-thread-only callback whose result must not retain live Hytale state. */
    @FunctionalInterface
    interface WorldSnapshotRead<T> {
        @Nullable
        T read(@Nonnull HytaleOwnerWorldAccess access);
    }
}

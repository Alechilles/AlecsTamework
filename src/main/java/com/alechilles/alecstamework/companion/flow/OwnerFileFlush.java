package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Writes the owner files a committed record change touches, for {@link CaptureFlow} and {@link RestoreFlow}. */
final class OwnerFileFlush {
    private OwnerFileFlush() {
    }

    /**
     * Writes the new owner's file first, then the old one's when the owner changed. Either failure,
     * also one thrown before a future exists, fails the returned future so the caller undoes the commit.
     *
     * @param before the record before the change, or null for a new record
     */
    @Nonnull
    static CompletableFuture<Void> flushOwners(@Nonnull Function<UUID, CompletableFuture<Void>> flushOwner,
                                               @Nullable CompanionRecord before, @Nonnull CompanionRecord after) {
        CompletableFuture<Void> first;
        try {
            first = flushOwner.apply(after.ownerUuid());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (before == null || Objects.equals(before.ownerUuid(), after.ownerUuid())) {
            return first;
        }
        return first.thenCompose(v -> flushOwner.apply(before.ownerUuid()));
    }
}

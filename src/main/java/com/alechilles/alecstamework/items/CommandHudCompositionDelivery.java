package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Delivers lifecycle-validated updates and defers cleanup until delivery ends. */
final class CommandHudCompositionDelivery<U> {
    private final Object lock;
    private final LongPredicate current;
    private final Consumer<U> publisher;
    private final Consumer<String> cleanup;
    private int publishing;
    private boolean cleanupRequested;
    private boolean cleanupCompleted;
    @Nullable
    private String cleanupReason;

    CommandHudCompositionDelivery(
            @Nonnull Object lock,
            @Nonnull LongPredicate current,
            @Nonnull Consumer<U> publisher,
            @Nonnull Consumer<String> cleanup
    ) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.current = Objects.requireNonNull(current, "current");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    void publish(@Nonnull U update, long version) {
        synchronized (lock) {
            if (!current.test(version)) return;
            publishing++;
        }
        try {
            publisher.accept(update);
        } catch (RuntimeException | LinkageError ignored) {
            // A renderer failure must not escape the command-item tick.
        } finally {
            finishPublishing();
        }
    }

    void requestCleanup(@Nullable String reason) {
        boolean closeNow = false;
        String effectiveReason = reason;
        synchronized (lock) {
            cleanupRequested = true;
            if (cleanupReason == null && reason != null) cleanupReason = reason;
            if (cleanupReason != null) effectiveReason = cleanupReason;
            if (publishing == 0 && !cleanupCompleted) {
                cleanupCompleted = true;
                closeNow = true;
            }
        }
        if (closeNow) cleanup.accept(effectiveReason);
    }

    private void finishPublishing() {
        String reason = null;
        boolean closeNow = false;
        synchronized (lock) {
            publishing = Math.max(0, publishing - 1);
            if (publishing == 0 && cleanupRequested && !cleanupCompleted) {
                cleanupCompleted = true;
                reason = cleanupReason;
                closeNow = true;
            }
        }
        if (closeNow) cleanup.accept(reason);
    }
}

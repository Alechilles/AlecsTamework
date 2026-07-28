package com.alechilles.alecstamework.persistence.control;

import javax.annotation.Nonnull;

/** Closes persistence resources in declared order while retaining every failure. */
public final class OrderedPersistenceCloser {
    private OrderedPersistenceCloser() {
    }

    /** Closes every participant and throws one failure with later failures suppressed. */
    public static void closeAll(@Nonnull CloseParticipant... participants) {
        if (participants == null) {
            throw new IllegalArgumentException(
                    "Persistence close participants are required"
            );
        }
        RuntimeException failure = null;
        for (CloseParticipant participant : participants) {
            if (participant == null) {
                failure = merge(
                        failure,
                        new IllegalArgumentException(
                                "Persistence close participant is required"
                        )
                );
                continue;
            }
            try {
                participant.close();
            } catch (Exception closeFailure) {
                failure = merge(failure, closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException merge(
            RuntimeException existing,
            Exception next
    ) {
        if (existing == null) {
            return new IllegalStateException(
                    "persistence_shutdown_failed",
                    next
            );
        }
        existing.addSuppressed(next);
        return existing;
    }

    /** One close action that may report a checked or unchecked failure. */
    @FunctionalInterface
    public interface CloseParticipant {
        void close() throws Exception;
    }
}

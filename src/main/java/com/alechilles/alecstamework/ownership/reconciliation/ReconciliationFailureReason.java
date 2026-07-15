package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;

/** Builds bounded, single-line reconciliation failure details while retaining causal context. */
final class ReconciliationFailureReason {
    private static final int MAX_LENGTH = 480;

    private ReconciliationFailureReason() {
    }

    @Nonnull
    static String describe(@Nonnull Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "throwable");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        StringBuilder reason = new StringBuilder();
        Set<Throwable> visited = new HashSet<>();
        while (current != null && visited.add(current) && reason.length() < MAX_LENGTH) {
            if (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            if (!reason.isEmpty()) {
                reason.append(" <- ");
            }
            reason.append(current.getClass().getSimpleName());
            String message = sanitize(current.getMessage());
            if (!message.isEmpty()) {
                reason.append('(').append(message).append(')');
            }
            current = current.getCause();
        }
        return reason.length() <= MAX_LENGTH
                ? reason.toString()
                : reason.substring(0, MAX_LENGTH);
    }

    @Nonnull
    private static String sanitize(String message) {
        return message == null ? "" : message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }
}

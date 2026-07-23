package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;

/**
 * Storage-neutral repository failure whose cause remains available to the kernel classifier.
 */
public final class PersistenceStoreException extends RuntimeException {
    private final String operation;

    public PersistenceStoreException(@Nonnull String operation, @Nonnull Throwable cause) {
        super(requireOperation(operation), requireCause(cause));
        this.operation = operation.trim();
    }

    /** Returns the stable repository operation that failed. */
    @Nonnull
    public String operation() {
        return operation;
    }

    private static String requireOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Store operation is required");
        }
        return operation.trim();
    }

    private static Throwable requireCause(Throwable cause) {
        if (cause == null) {
            throw new IllegalArgumentException("Store failure cause is required");
        }
        return cause;
    }
}

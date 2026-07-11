package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;

/**
 * Enforces that a changed canonical command record is durably accepted by inventory first.
 */
final class CommandCanonicalRecordCommitGate {
    /** Returns true only when no write is needed or the requested inventory write succeeds. */
    boolean commitBeforeAction(boolean identityChanged, @Nonnull BooleanSupplier inventoryWrite) {
        Objects.requireNonNull(inventoryWrite, "inventoryWrite");
        return !identityChanged || inventoryWrite.getAsBoolean();
    }
}

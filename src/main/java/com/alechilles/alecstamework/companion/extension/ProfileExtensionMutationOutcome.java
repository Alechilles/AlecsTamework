package com.alechilles.alecstamework.companion.extension;

import com.google.gson.JsonParser;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Durable domain outcome encoded in the operation outbox.
 *
 * <p>Domain denials are published outcomes, not transport failures. This makes replay return the
 * same answer without rerunning a canonical mutation.</p>
 */
public record ProfileExtensionMutationOutcome(
        @Nonnull Status status,
        @Nonnull ProfileExtensionKey key,
        long revision,
        @Nullable String jsonPayload,
        long updatedAtMs
) {
    public ProfileExtensionMutationOutcome {
        if (status == null || key == null || revision < 0) {
            throw new IllegalArgumentException("Complete extension mutation outcome is required");
        }
        if (status == Status.APPLIED) {
            if (jsonPayload == null) {
                throw new IllegalArgumentException("Applied extension outcome requires JSON");
            }
            jsonPayload = JsonParser.parseString(jsonPayload).toString();
        } else if (jsonPayload != null) {
            throw new IllegalArgumentException("Only an applied extension outcome carries JSON");
        }
    }

    /** Stable domain outcomes; every value can be replayed from durable outbox evidence. */
    public enum Status {
        APPLIED,
        DELETED,
        UNCHANGED,
        REVISION_MISMATCH,
        PROFILE_NOT_FOUND
    }
}

package com.alechilles.alecstamework.companion.extension;

import com.google.gson.JsonParser;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable payload for one idempotent extension-data mutation.
 *
 * <p>A null expected revision selects the latest durable revision. An explicit revision provides
 * compare-and-set semantics. Signed timestamps are valid game-world times.</p>
 */
public record ProfileExtensionMutation(
        @Nonnull ProfileExtensionKey key,
        @Nonnull ProfileExtensionMutationAction action,
        @Nullable Long expectedRevision,
        @Nullable String jsonPayload,
        long requestedAtMs
) {
    public ProfileExtensionMutation {
        if (key == null || action == null) {
            throw new IllegalArgumentException("Extension mutation key and action are required");
        }
        if (expectedRevision != null
                && (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "Expected extension revision must be below Long.MAX_VALUE"
            );
        }
        if (action == ProfileExtensionMutationAction.PUT) {
            if (jsonPayload == null) {
                throw new IllegalArgumentException("Extension put JSON is required");
            }
            jsonPayload = JsonParser.parseString(jsonPayload).toString();
        } else if (jsonPayload != null) {
            throw new IllegalArgumentException("Extension delete cannot carry JSON");
        }
    }
}

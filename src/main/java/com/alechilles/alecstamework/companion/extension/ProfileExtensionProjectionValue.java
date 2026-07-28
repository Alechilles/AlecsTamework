package com.alechilles.alecstamework.companion.extension;

import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Immutable non-authoritative value exposed by the extension projection. */
public record ProfileExtensionProjectionValue(
        @Nonnull ProfileExtensionKey key,
        long revision,
        @Nonnull String jsonPayload,
        long updatedAtMs
) {
    public ProfileExtensionProjectionValue {
        if (key == null || revision <= 0 || jsonPayload == null) {
            throw new IllegalArgumentException(
                    "Complete extension projection value is required"
            );
        }
        jsonPayload = JsonParser.parseString(jsonPayload).toString();
    }

    /** Builds one projected value from validated canonical data. */
    @Nonnull
    public static ProfileExtensionProjectionValue from(
            @Nonnull ProfileExtensionData data
    ) {
        if (data == null || data.deleted()) {
            throw new IllegalArgumentException(
                    "Active extension data is required"
            );
        }
        return new ProfileExtensionProjectionValue(
                data.key(),
                data.revision(),
                data.jsonPayload(),
                data.updatedAtMs()
        );
    }
}

package com.alechilles.alecstamework.companion.identity;

import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Immutable association between a companion profile and a durable command/tool identity.
 */
public record CompanionToolLink(@Nonnull ProfileId profileId,
                                @Nonnull UUID toolId,
                                @Nonnull String linkType,
                                long createdAtMs,
                                long updatedAtMs) {
    public CompanionToolLink {
        if (profileId == null || toolId == null) {
            throw new IllegalArgumentException("Profile and tool IDs are required");
        }
        if (linkType == null || linkType.isBlank()) {
            throw new IllegalArgumentException("Tool link type is required");
        }
        linkType = linkType.trim();
    }
}

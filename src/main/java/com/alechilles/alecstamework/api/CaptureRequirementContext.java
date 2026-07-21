package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Engine-neutral, immutable facts exposed to a custom capture requirement. */
public record CaptureRequirementContext(@Nonnull UUID attemptId,
                                        @Nonnull CaptureRequirementPhase phase,
                                        @Nonnull UUID actorUuid,
                                        @Nonnull UUID targetNpcUuid,
                                        @Nullable String profileId,
                                        @Nonnull String roleId,
                                        @Nonnull String worldName,
                                        @Nonnull String sourceItemId,
                                        double healthFraction,
                                        long expectedProfileRevision) {
    public static final long UNKNOWN_PROFILE_REVISION = -1L;

    public CaptureRequirementContext {
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        phase = Objects.requireNonNull(phase, "phase");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        targetNpcUuid = Objects.requireNonNull(targetNpcUuid, "targetNpcUuid");
        roleId = requireText(roleId, "roleId");
        worldName = requireText(worldName, "worldName");
        sourceItemId = requireText(sourceItemId, "sourceItemId");
        profileId = normalizeBlank(profileId);
        if (!Double.isFinite(healthFraction) || healthFraction < 0.0D || healthFraction > 1.0D) {
            throw new IllegalArgumentException("Health fraction must be finite and between zero and one.");
        }
        if (expectedProfileRevision < UNKNOWN_PROFILE_REVISION) {
            throw new IllegalArgumentException("Profile revision must be -1 or non-negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeBlank(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

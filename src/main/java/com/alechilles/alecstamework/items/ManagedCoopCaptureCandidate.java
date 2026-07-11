package com.alechilles.alecstamework.items;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable NPC evidence that is safe to retain outside an ECS chunk callback. */
public record ManagedCoopCaptureCandidate(@Nonnull UUID npcUuid,
                                          @Nonnull String roleId,
                                          double x,
                                          double y,
                                          double z,
                                          @Nullable UUID ownerUuid,
                                          @Nullable String displayName,
                                          @Nonnull String[] toolIds,
                                          @Nullable String stableProfileId,
                                          boolean tamed) {
    public ManagedCoopCaptureCandidate {
        Objects.requireNonNull(npcUuid, "npcUuid");
        if (roleId == null || roleId.isBlank()) {
            throw new IllegalArgumentException("roleId must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("candidate position must be finite");
        }
        roleId = roleId.trim().toLowerCase(Locale.ROOT);
        displayName = normalizeOptional(displayName);
        stableProfileId = normalizeOptional(stableProfileId);
        toolIds = toolIds == null ? new String[0] : toolIds.clone();
    }

    @Override
    public String[] toolIds() {
        return toolIds.clone();
    }

    @Nonnull
    public ManagedCoopCaptureRuntimeAdapter.Candidate runtimeCandidate() {
        return new ManagedCoopCaptureRuntimeAdapter.Candidate(
                npcUuid, roleId, ownerUuid, displayName, toolIds, stableProfileId);
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

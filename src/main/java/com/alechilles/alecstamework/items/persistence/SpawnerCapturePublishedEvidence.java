package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.Vector3View;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable released-event facts captured before the live NPC is retired. */
public record SpawnerCapturePublishedEvidence(
        @Nonnull UUID npcUuid,
        @Nullable UUID ownerUuid,
        @Nonnull Set<String> toolIds,
        @Nullable String roleId,
        @Nullable String displayName,
        @Nullable Vector3View homePosition,
        long capturedAtMs
) {
    public SpawnerCapturePublishedEvidence {
        if (npcUuid == null || toolIds == null) {
            throw new IllegalArgumentException(
                    "Complete capture event evidence is required"
            );
        }
        toolIds = Set.copyOf(toolIds);
        roleId = normalize(roleId);
        displayName = normalize(displayName);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

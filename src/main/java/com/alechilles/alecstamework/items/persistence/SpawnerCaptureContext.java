package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Post-freeze capture context containing no Hytale ECS or inventory objects. */
record SpawnerCaptureContext(
        @Nonnull String intentKey,
        @Nonnull UUID actorUuid,
        @Nonnull String worldKey,
        int sourceSlot,
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias sourceAlias,
        @Nullable OwnerId liveOwnerId,
        @Nullable OwnerId resultingOwnerId,
        @Nullable String roleId
) {
    SpawnerCaptureContext {
        intentKey = requireText(intentKey, "intentKey");
        actorUuid = Objects.requireNonNull(actorUuid, "actorUuid");
        worldKey = requireText(worldKey, "worldKey");
        if (sourceSlot < 0 || profileId == null || sourceAlias == null) {
            throw new IllegalArgumentException(
                    "Complete frozen capture context is required"
            );
        }
        roleId = roleId == null || roleId.isBlank()
                ? null
                : roleId.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}

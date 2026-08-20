package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves restored active companions through the event-maintained live profile index. */
final class CommandActiveNpcHighlightTargetResolver {
    private final LoadedNpcIdentityIndex identities;

    CommandActiveNpcHighlightTargetResolver(@Nonnull LoadedNpcIdentityIndex identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Nullable
    UUID resolve(@Nonnull UUID recordedNpcUuid,
                 @Nullable String profileId,
                 @Nonnull LoadedTargetProbe loadedTargetProbe) {
        if (loadedTargetProbe.isLoaded(recordedNpcUuid)) {
            return recordedNpcUuid;
        }
        UUID currentNpcUuid = identities.uniqueNpcUuidForRecord(profileId, recordedNpcUuid);
        return currentNpcUuid != null && loadedTargetProbe.isLoaded(currentNpcUuid)
                ? currentNpcUuid
                : null;
    }

    @FunctionalInterface
    interface LoadedTargetProbe {
        boolean isLoaded(@Nonnull UUID npcUuid);
    }
}

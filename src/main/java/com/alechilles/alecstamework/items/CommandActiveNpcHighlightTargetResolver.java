package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the highlight target of an active command record: its recorded NPC while loaded. */
final class CommandActiveNpcHighlightTargetResolver {
    private final LoadedNpcIdentityIndex identities;

    CommandActiveNpcHighlightTargetResolver(@Nonnull LoadedNpcIdentityIndex identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Nullable
    UUID resolve(@Nonnull UUID recordedNpcUuid,
                 @Nullable String profileId,
                 @Nonnull LoadedTargetProbe loadedTargetProbe) {
        return loadedTargetProbe.isLoaded(recordedNpcUuid) ? recordedNpcUuid : null;
    }

    @FunctionalInterface
    interface LoadedTargetProbe {
        boolean isLoaded(@Nonnull UUID npcUuid);
    }
}

package com.alechilles.alecstamework.api;

import java.util.EnumSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record NpcProfileChangedEvent(@Nonnull String profileId,
                                     @Nonnull EnumSet<ProfileChangeType> changeTypes,
                                     @Nullable NpcProfileView before,
                                     @Nullable NpcProfileView after,
                                     long emittedAtMs) implements TameworkEvent {
    public NpcProfileChangedEvent {
        changeTypes = changeTypes.clone();
    }
}


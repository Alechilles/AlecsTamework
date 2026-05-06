package com.alechilles.alecstamework.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runtime context passed to custom trait effect handlers during trait resync.
 */
public record TraitEffectContext(
        @Nonnull String effectKey,
        double value,
        @Nonnull List<TraitEffectContribution> contributions,
        @Nonnull Ref<EntityStore> npcRef,
        @Nonnull Store<EntityStore> store,
        @Nullable UUID npcUuid,
        @Nullable String profileId,
        @Nullable String roleId,
        @Nullable String traitConfigId
) {
    public TraitEffectContext {
        contributions = List.copyOf(contributions);
    }
}

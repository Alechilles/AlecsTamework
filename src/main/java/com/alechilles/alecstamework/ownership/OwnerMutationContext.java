package com.alechilles.alecstamework.ownership;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Current world-thread entity context resolved immediately before an admitted owner mutation. */
public record OwnerMutationContext(
        @Nonnull Ref<EntityStore> npcRef,
        @Nonnull Store<EntityStore> store,
        @Nonnull UUID npcUuid
) {
    public OwnerMutationContext {
        Objects.requireNonNull(npcRef, "npcRef");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(npcUuid, "npcUuid");
    }
}

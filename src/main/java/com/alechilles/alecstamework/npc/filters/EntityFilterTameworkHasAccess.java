package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkHasAccess;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/**
 * Entity filter that matches when the candidate entity is the NPC's owner or a player who holds
 * shared access to it at the configured level or higher. An unowned NPC never matches.
 */
public final class EntityFilterTameworkHasAccess extends TameworkEntityFilterBase {
    public static final String TYPE = "TameworkHasAccess";

    private final AccessLevel minLevel;

    public EntityFilterTameworkHasAccess(@Nonnull BuilderEntityFilterTameworkHasAccess builder,
                                         @Nonnull BuilderSupport support) {
        this.minLevel = builder.getMinLevel(support);
    }

    @Override
    public boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Ref<EntityStore> targetRef,
                                 @Nonnull Role role,
                                 @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, PlayerRef> playerRefType = PlayerRef.getComponentType();
        if (playerRefType == null) {
            return false;
        }
        PlayerRef playerRef = store.getComponent(targetRef, playerRefType);
        return playerRef != null
                && CompanionAccess.level(ref, store, playerRef.getUuid()).atLeast(minLevel);
    }

    @Override
    public int cost() {
        return 0;
    }
}

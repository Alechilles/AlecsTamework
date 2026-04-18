package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkIsOwner;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Entity filter that matches when the candidate entity is the NPC's owner. */
public final class EntityFilterTameworkIsOwner extends EntityFilterBase {
    public static final String TYPE = "TameworkIsOwner";

    @Nullable
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerType;
    @Nullable
    private final ComponentType<EntityStore, PlayerRef> playerRefType;

    public EntityFilterTameworkIsOwner(@Nonnull BuilderEntityFilterTameworkIsOwner builder,
                                       @Nonnull BuilderSupport support) {
        this.ownerType = TameworkOwnerComponent.getComponentType();
        this.playerRefType = PlayerRef.getComponentType();
    }

    @Override
    public boolean matchesEntity(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull Ref<EntityStore> targetRef,
                                 @Nonnull Role role,
                                 @Nonnull Store<EntityStore> store) {
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(ref, store);
        if (ownerUuid == null) {
            return false;
        }
        ComponentType<EntityStore, PlayerRef> resolvedPlayerRefType = playerRefType != null
                ? playerRefType
                : PlayerRef.getComponentType();
        if (resolvedPlayerRefType == null) {
            return false;
        }
        PlayerRef playerRef = store.getComponent(targetRef, resolvedPlayerRefType);
        UUID targetUuid = playerRef != null ? playerRef.getUuid() : null;
        return ownerUuid.equals(targetUuid);
    }

    @Override
    public int cost() {
        return 0;
    }

    @Nullable
    private UUID resolveOwnerUuid(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> resolvedOwnerType = ownerType != null
                ? ownerType
                : TameworkOwnerComponent.getComponentType();
        if (resolvedOwnerType == null) {
            return null;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, resolvedOwnerType);
        return owner != null ? owner.getOwnerId() : null;
    }
}

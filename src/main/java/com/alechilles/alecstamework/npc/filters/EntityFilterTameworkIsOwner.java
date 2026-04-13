package com.alechilles.alecstamework.npc.filters;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkIsOwner;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.corecomponents.EntityFilterBase;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Entity filter that matches when the candidate entity is the NPC's owner. */
public final class EntityFilterTameworkIsOwner extends EntityFilterBase {
    public static final String TYPE = "TameworkIsOwner";

    public EntityFilterTameworkIsOwner(@Nonnull BuilderEntityFilterTameworkIsOwner builder,
                                       @Nonnull BuilderSupport support) {
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
        Player player = store.getComponent(targetRef, Player.getComponentType());
        UUID targetUuid = player != null ? player.getUuid() : null;
        return ownerUuid.equals(targetUuid);
    }

    @Override
    public int cost() {
        return 0;
    }

    private UUID resolveOwnerUuid(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return null;
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        return owner != null ? owner.getOwnerId() : null;
    }
}

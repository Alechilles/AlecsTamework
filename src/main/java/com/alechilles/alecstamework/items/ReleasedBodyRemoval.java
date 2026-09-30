package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Removes the body of a companion whose record is already released. The index decided the
 * release, so removal checks only that the NPC with that uuid is still there; ownership and
 * generic-tool authority are not checked again.
 */
public final class ReleasedBodyRemoval {
    private ReleasedBodyRemoval() {
    }

    /**
     * Schedules removal on the body's world thread and re-resolves the entity by {@code npcUuid}
     * there. Only the uuid crosses threads. When the world is gone, the fence removes the body
     * when it next loads.
     */
    public static void removeOnBodyWorld(@Nonnull Ref<EntityStore> body, @Nonnull UUID npcUuid) {
        Store<EntityStore> bodyStore = body.getStore();
        World world = bodyStore == null || bodyStore.getExternalData() == null
                ? null : bodyStore.getExternalData().getWorld();
        if (world == null || !world.isAlive()) {
            return;
        }
        world.execute(() -> {
            Store<EntityStore> store = world.getEntityStore() == null ? null : world.getEntityStore().getStore();
            Ref<EntityStore> ref = store == null ? null : world.getEntityRef(npcUuid);
            if (ref != null && ref.isValid() && store.getComponent(ref, NPCEntity.getComponentType()) != null) {
                removeNow(ref, store);
            }
        });
    }

    /**
     * Clears owner, tamed and links, then removes the entity. Call on the entity's world thread,
     * outside an ECS system callback.
     */
    static void removeNow(@Nonnull Ref<EntityStore> npcRef, @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType == null ? null : store.getComponent(npcRef, ownerType);
        if (owner != null && (owner.getOwnerId() != null || owner.getOwnerName() != null)) {
            owner.setOwnerId(null);
            owner.setOwnerName(null);
            store.putComponent(npcRef, ownerType, owner);
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        TameworkTamedComponent tamed = tamedType == null ? null : store.getComponent(npcRef, tamedType);
        if (tamed != null && tamed.isTamed()) {
            tamed.setTamed(false);
            store.putComponent(npcRef, tamedType, tamed);
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (linksType != null && store.getComponent(npcRef, linksType) != null) {
            // Match Cull: terminal removal must not be observed as a lost linked companion.
            store.removeComponent(npcRef, linksType);
        }
        // NPC despawn timers cannot finish while the entity is frozen or non-ticking.
        store.removeEntity(npcRef, RemoveReason.REMOVE);
    }
}

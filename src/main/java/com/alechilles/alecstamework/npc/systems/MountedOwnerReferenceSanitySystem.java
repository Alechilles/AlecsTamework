package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Scrubs stale mount owner refs when mounted NPCs are loaded into a store.
 * This prevents cross-store owner refs from crashing vanilla mount cleanup paths.
 */
public final class MountedOwnerReferenceSanitySystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, NPCMountComponent> mountType;
    private final ComponentType<EntityStore, Player> playerType;
    private final ComponentType<EntityStore, Interactable> interactableType;

    public MountedOwnerReferenceSanitySystem(ComponentType<EntityStore, NPCEntity> npcType,
                                             ComponentType<EntityStore, NPCMountComponent> mountType,
                                             ComponentType<EntityStore, Player> playerType,
                                             ComponentType<EntityStore, Interactable> interactableType) {
        this.npcType = npcType;
        this.mountType = mountType;
        this.playerType = playerType;
        this.interactableType = interactableType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || mountType == null) {
            return;
        }
        if (safeGetComponent(store, reference, npcType) == null) {
            return;
        }
        NPCMountComponent mountComponent = safeGetComponent(store, reference, mountType);
        if (mountComponent == null) {
            return;
        }
        if (isOwnerReferenceValidForStore(mountComponent, store)) {
            return;
        }
        mountComponent.setOwnerPlayerRef(null);
        if (interactableType != null) {
            commandBuffer.ensureComponent(reference, interactableType);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op. We only sanitize owner refs immediately on add/load.
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null || mountType == null) {
            return Query.any();
        }
        return Query.and(npcType, mountType);
    }

    private boolean isOwnerReferenceValidForStore(@Nonnull NPCMountComponent mountComponent,
                                                  @Nonnull Store<EntityStore> store) {
        PlayerRef ownerPlayerRef = mountComponent.getOwnerPlayerRef();
        if (ownerPlayerRef == null) {
            return false;
        }
        Ref<EntityStore> ownerRef = ownerPlayerRef.getReference();
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            return false;
        }
        if (playerType == null) {
            return true;
        }
        return safeGetComponent(store, ownerRef, playerType) != null;
    }

    @Nullable
    private <T extends Component<EntityStore>> T safeGetComponent(@Nonnull Store<EntityStore> store,
                                                                   @Nonnull Ref<EntityStore> reference,
                                                                   @Nullable ComponentType<EntityStore, T> componentType) {
        if (componentType == null || !reference.isValid()) {
            return null;
        }
        try {
            return store.getComponent(reference, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return null;
        }
    }
}

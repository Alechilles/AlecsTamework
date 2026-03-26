package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.builtin.mounts.MountPlugin;
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
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Ensures mounted NPC teleports do not carry stale mount ownership across stores/worlds.
 */
public final class MountedNpcTeleportSafetySystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, NPCMountComponent> mountType;
    private final ComponentType<EntityStore, Teleport> teleportType;
    private final ComponentType<EntityStore, Player> playerType;
    private final ComponentType<EntityStore, Interactable> interactableType;

    public MountedNpcTeleportSafetySystem(ComponentType<EntityStore, NPCEntity> npcType,
                                          ComponentType<EntityStore, NPCMountComponent> mountType,
                                          ComponentType<EntityStore, Teleport> teleportType,
                                          ComponentType<EntityStore, Player> playerType,
                                          ComponentType<EntityStore, Interactable> interactableType) {
        this.npcType = npcType;
        this.mountType = mountType;
        this.teleportType = teleportType;
        this.playerType = playerType;
        this.interactableType = interactableType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || mountType == null || teleportType == null) {
            return;
        }
        if (safeGetComponent(store, reference, npcType) == null
                || safeGetComponent(store, reference, teleportType) == null) {
            return;
        }
        NPCMountComponent mountComponent = safeGetComponent(store, reference, mountType);
        if (mountComponent == null) {
            return;
        }

        PlayerRef ownerPlayerRef = mountComponent.getOwnerPlayerRef();
        Ref<EntityStore> ownerRef = ownerPlayerRef != null ? ownerPlayerRef.getReference() : null;
        if (ownerRef == null || !ownerRef.isValid() || ownerRef.getStore() != store) {
            mountComponent.setOwnerPlayerRef(null);
            ensureInteractable(reference, commandBuffer);
            return;
        }

        Player ownerPlayer = safeGetComponent(store, ownerRef, playerType);
        if (ownerPlayer == null || ownerPlayer.getMountEntityId() == 0) {
            mountComponent.setOwnerPlayerRef(null);
            ensureInteractable(reference, commandBuffer);
            return;
        }

        MountPlugin.checkDismountNpc(commandBuffer, ownerRef, ownerPlayer);
        mountComponent.setOwnerPlayerRef(null);
        ensureInteractable(reference, commandBuffer);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op.
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null || mountType == null || teleportType == null) {
            return Query.any();
        }
        return Query.and(npcType, mountType, teleportType);
    }

    private void ensureInteractable(@Nonnull Ref<EntityStore> reference,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (interactableType == null) {
            return;
        }
        commandBuffer.ensureComponent(reference, interactableType);
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

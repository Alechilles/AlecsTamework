package com.alechilles.alecstamework.npc.network;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.systems.MountedRideClientAttachment;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.protocol.packets.interaction.DismountNPC;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.handlers.IPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.SubPacketHandler;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles NPC dismount packets for Tamework rides before vanilla attempts the NPCMountComponent path.
 */
public final class MountedRidePacketHandler implements SubPacketHandler {
    private final IPacketHandler packetHandler;

    public MountedRidePacketHandler(@Nonnull IPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    @Override
    public void registerHandlers() {
        packetHandler.registerHandler(DismountNPC.PACKET_ID, packet -> handle((DismountNPC) packet));
    }

    private void handle(@Nonnull DismountNPC packet) {
        PlayerRef playerRef = packetHandler.getPlayerRef();
        Ref<EntityStore> riderRef = playerRef.getReference();
        if (riderRef == null || !riderRef.isValid()) {
            return;
        }
        Store<EntityStore> store = riderRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> handleOnWorldThread(riderRef, store));
    }

    private void handleOnWorldThread(@Nonnull Ref<EntityStore> riderRef, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(riderRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        Tamework instance = Tamework.getInstance();
        ComponentType<EntityStore, TameworkRideRiderComponent> riderType =
                instance == null ? null : instance.getRideRiderComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> mountType =
                instance == null ? null : instance.getRideMountComponentType();
        TameworkRideRiderComponent rider = riderType == null ? null : store.getComponent(riderRef, riderType);
        Ref<EntityStore> mountRef = rider == null ? null : resolveMountRef(rider, store);
        TameworkRideMountComponent mount = mountRef == null || !mountRef.isValid() || mountType == null
                ? null
                : store.getComponent(mountRef, mountType);
        if (mount != null) {
            mount.setDismountRequested(true);
            store.putComponent(mountRef, mountType, mount);
            MountedRideClientAttachment.detach(store, riderRef);
            PlayerInput playerInput = store.getComponent(riderRef, PlayerInput.getComponentType());
            if (playerInput != null) {
                playerInput.getMovementUpdateQueue().clear();
            }
            return;
        }
        handleVanillaDismount(riderRef, store, player);
    }

    private void handleVanillaDismount(@Nonnull Ref<EntityStore> riderRef,
                                       @Nonnull Store<EntityStore> store,
                                       @Nonnull Player player) {
        MountedComponent mounted = store.getComponent(riderRef, MountedComponent.getComponentType());
        if (mounted == null) {
            MountPlugin.checkDismountNpc(store, riderRef, player);
            return;
        }
        if (mounted.getControllerType() == MountController.BlockMount) {
            store.tryRemoveComponent(riderRef, MountedComponent.getComponentType());
        }
    }

    @Nullable
    private Ref<EntityStore> resolveMountRef(@Nonnull TameworkRideRiderComponent rider,
                                             @Nonnull Store<EntityStore> store) {
        if (rider.getMountUuid().isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(rider.getMountUuid()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.builtin.mounts.MountPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Queues companion travel relocation when a player teleports within the same world.
 */
public final class CommandTeleportArrivalRelocationSystem extends TickingSystem<EntityStore> {
    private static final long SYSTEM_SWEEP_INTERVAL_MS = 75L;
    private static final long TELEPORT_ARRIVAL_DELAY_MS = 350L;

    private final CommandItemFeatureHandler featureHandler;
    private final Set<UUID> queuedPlayers = ConcurrentHashMap.newKeySet();

    private final StoreScopedState<TickState> statesByStore = new StoreScopedState<>(TickState::new);

    public CommandTeleportArrivalRelocationSystem(CommandItemFeatureHandler featureHandler) {
        this.featureHandler = featureHandler;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (featureHandler == null) {
            return;
        }
        TickState tickState = statesByStore.get(store);
        long nowMs = System.currentTimeMillis();
        if (nowMs < tickState.nextSweepAtMs) {
            return;
        }
        tickState.nextSweepAtMs = nowMs + SYSTEM_SWEEP_INTERVAL_MS;

        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        ComponentType<EntityStore, Teleport> teleportType = Teleport.getComponentType();
        if (playerType == null || teleportType == null) {
            return;
        }

        Set<UUID> activeTeleportingPlayers = new HashSet<>();
        store.forEachChunk(
                Query.and(playerType, teleportType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        collectSameWorldTeleportCandidates(
                                chunk,
                                commandBuffer,
                                playerType,
                                teleportType,
                                world,
                                activeTeleportingPlayers
                        )
        );
        queuedPlayers.retainAll(activeTeleportingPlayers);
    }

    private void collectSameWorldTeleportCandidates(ArchetypeChunk<EntityStore> chunk,
                                                    CommandBuffer<EntityStore> commandBuffer,
                                                    ComponentType<EntityStore, Player> playerType,
                                                    ComponentType<EntityStore, Teleport> teleportType,
                                                    World world,
                                                    Set<UUID> activeTeleportingPlayers) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Player player = chunk.getComponent(i, playerType);
            Teleport teleport = chunk.getComponent(i, teleportType);
            UUID playerUuid = player != null ? player.getUuid() : null;
            if (playerUuid == null || teleport == null) {
                continue;
            }
            dismountBeforePortalTransfer(chunk, commandBuffer, i, player);
            World destinationWorld = teleport.getWorld();
            if (destinationWorld != null && !isSameWorld(destinationWorld, world)) {
                continue;
            }
            activeTeleportingPlayers.add(playerUuid);
            if (!queuedPlayers.add(playerUuid)) {
                continue;
            }
            scheduleArrivalQueue(world, playerUuid);
        }
    }

    private void dismountBeforePortalTransfer(ArchetypeChunk<EntityStore> chunk,
                                              CommandBuffer<EntityStore> commandBuffer,
                                              int index,
                                              Player player) {
        if (chunk == null || player == null || player.getMountEntityId() == 0) {
            return;
        }
        Ref<EntityStore> playerRef = chunk.getReferenceTo(index);
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        MountPlugin.checkDismountNpc(commandBuffer, playerRef, player);
    }

    private void scheduleArrivalQueue(World world, UUID playerUuid) {
        if (world == null || playerUuid == null) {
            return;
        }
        CompletableFuture.runAsync(
                () -> world.execute(() -> featureHandler.queueWorldChangeTravelRelocationsForPlayerUuid(world, playerUuid)),
                CompletableFuture.delayedExecutor(TELEPORT_ARRIVAL_DELAY_MS, TimeUnit.MILLISECONDS)
        );
    }

    private static boolean isSameWorld(@Nullable World left, @Nullable World right) {
        if (left == null || right == null) {
            return false;
        }
        if (left == right) {
            return true;
        }
        String leftName = left.getName();
        String rightName = right.getName();
        return leftName != null && leftName.equals(rightName);
    }

    private static final class TickState {
        private long nextSweepAtMs;
    }
}

package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Handles delayed/off-screen NPC relocation requests for command items.
 */
public final class CommandNpcRelocationService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final int CHUNK_SIZE = 32;

    private final ConcurrentHashMap<UUID, PendingRelocation> pendingByNpc = new ConcurrentHashMap<>();

    public void queueRelocation(World world,
                                UUID npcUuid,
                                Vector3d destination,
                                @Nullable UUID ownerUuid,
                                boolean assignOwnerAsMasterTarget,
                                boolean clearLockedTarget,
                                @Nullable String state,
                                @Nullable String subState,
                                long delayMs,
                                @Nullable Vector3d sourceHintPosition) {
        if (world == null || npcUuid == null || destination == null) {
            return;
        }
        long executeAfterMs = System.currentTimeMillis() + Math.max(0L, delayMs);
        PendingRelocation pending = new PendingRelocation(
                npcUuid,
                new Vector3d(destination),
                ownerUuid,
                assignOwnerAsMasterTarget,
                clearLockedTarget,
                state,
                subState,
                executeAfterMs
        );
        pendingByNpc.put(npcUuid, pending);
        requestChunkLoad(world, destination, npcUuid);
        if (sourceHintPosition != null) {
            requestChunkLoad(world, sourceHintPosition, npcUuid);
        }
        scheduleTryApply(world, npcUuid, delayMs);
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return;
        }
        tryApply(world, npc.getUuid());
    }

    public boolean tryApply(World world, UUID npcUuid) {
        if (world == null || npcUuid == null) {
            return false;
        }
        PendingRelocation pending = pendingByNpc.get(npcUuid);
        if (pending == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < pending.executeAfterMs) {
            scheduleTryApply(world, npcUuid, pending.executeAfterMs - now);
            return false;
        }
        Ref<EntityStore> ref = world.getEntityRef(npcUuid);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return false;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        npc.moveTo(ref, pending.destination.x, pending.destination.y, pending.destination.z, store);
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            if (pending.clearLockedTarget) {
                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            }
            if (pending.assignOwnerAsMasterTarget && pending.ownerUuid != null) {
                Ref<EntityStore> ownerRef = world.getEntityRef(pending.ownerUuid);
                if (ownerRef != null && ownerRef.isValid()) {
                    role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, ownerRef);
                }
            }
        }
        if (pending.state != null && !pending.state.isBlank()) {
            applyState(role, ref, store, pending.state, pending.subState);
        }
        pendingByNpc.remove(npcUuid, pending);
        return true;
    }

    private void requestChunkLoad(World world, Vector3d position, UUID npcUuid) {
        if (world == null || position == null || npcUuid == null) {
            return;
        }
        int chunkX = toChunk(position.x);
        int chunkZ = toChunk(position.z);
        world.getChunkAsync(chunkX, chunkZ).thenAccept(chunk -> world.execute(() -> tryApply(world, npcUuid)));
    }

    private void scheduleTryApply(World world, UUID npcUuid, long delayMs) {
        if (world == null || npcUuid == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(
                () -> world.execute(() -> tryApply(world, npcUuid)),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
        );
    }

    private void applyState(Role role,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            String state,
                            @Nullable String subState) {
        if (role == null || role.getStateSupport() == null || state == null || state.isBlank()) {
            return;
        }
        StateSupport support = role.getStateSupport();
        String resolvedSubState = subState;
        if (support.getStateHelper() != null) {
            int stateIndex = support.getStateHelper().getStateIndex(state);
            if (stateIndex == StateSupport.NO_STATE) {
                return;
            }
            if (resolvedSubState == null || resolvedSubState.isBlank()) {
                resolvedSubState = support.getStateHelper().getDefaultSubState();
            } else if (support.getStateHelper().getSubStateIndex(stateIndex, resolvedSubState) == StateSupport.NO_STATE) {
                return;
            }
        }
        support.setState(npcRef, state, resolvedSubState == null ? "" : resolvedSubState, store);
    }

    private int toChunk(double coord) {
        return Math.floorDiv((int) Math.floor(coord), CHUNK_SIZE);
    }

    private static final class PendingRelocation {
        private final UUID npcUuid;
        private final Vector3d destination;
        private final UUID ownerUuid;
        private final boolean assignOwnerAsMasterTarget;
        private final boolean clearLockedTarget;
        private final String state;
        private final String subState;
        private final long executeAfterMs;

        private PendingRelocation(UUID npcUuid,
                                  Vector3d destination,
                                  UUID ownerUuid,
                                  boolean assignOwnerAsMasterTarget,
                                  boolean clearLockedTarget,
                                  String state,
                                  String subState,
                                  long executeAfterMs) {
            this.npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
            this.destination = Objects.requireNonNull(destination, "destination");
            this.ownerUuid = ownerUuid;
            this.assignOwnerAsMasterTarget = assignOwnerAsMasterTarget;
            this.clearLockedTarget = clearLockedTarget;
            this.state = state;
            this.subState = subState;
            this.executeAfterMs = executeAfterMs;
        }
    }
}

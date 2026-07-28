package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Tracks the last observed world, position, and owner of NPCs used by command relocation.
 *
 * <p>This is a process-local routing aid only. It never infers durable lifecycle state from
 * unload, absence, world removal, or retry timeout.</p>
 */
final class CommandRelocationNpcTracker {
    private final Map<UUID, Vector3d> lastKnownByNpc;
    private final Map<UUID, World> knownWorldByNpc;

    CommandRelocationNpcTracker(Map<UUID, Vector3d> lastKnownByNpc,
                                Map<UUID, World> knownWorldByNpc) {
        this.lastKnownByNpc = lastKnownByNpc;
        this.knownWorldByNpc = knownWorldByNpc;
    }

    @Nullable
    TrackedNpc onAdded(@Nullable Ref<EntityStore> reference,
                       @Nullable Store<EntityStore> store) {
        TrackedNpc tracked = resolve(reference, store);
        if (tracked == null) {
            return null;
        }
        remember(tracked);
        return tracked;
    }

    void onRemoved(@Nullable Ref<EntityStore> reference,
                   @Nullable RemoveReason reason,
                   @Nullable Store<EntityStore> store,
                   @Nullable UUID npcUuidHint) {
        if (reason == null) {
            return;
        }
        TrackedNpc tracked = resolve(reference, store);
        UUID npcUuid = tracked != null ? tracked.npcUuid() : npcUuidHint;
        if (npcUuid == null) {
            return;
        }
        if (tracked != null && tracked.position() != null) {
            lastKnownByNpc.put(npcUuid, new Vector3d(tracked.position()));
        }
        World observedWorld = tracked != null ? tracked.world() : resolveWorld(store);
        if (reason == RemoveReason.REMOVE) {
            knownWorldByNpc.remove(npcUuid);
            return;
        }
        if (observedWorld != null) {
            knownWorldByNpc.put(npcUuid, observedWorld);
        }
    }

    private void remember(TrackedNpc tracked) {
        if (tracked.position() != null) {
            lastKnownByNpc.put(tracked.npcUuid(), new Vector3d(tracked.position()));
        }
        if (tracked.world() != null) {
            knownWorldByNpc.put(tracked.npcUuid(), tracked.world());
        }
    }

    @Nullable
    private TrackedNpc resolve(@Nullable Ref<EntityStore> reference,
                               @Nullable Store<EntityStore> store) {
        NPCEntity npc = safeGetComponent(store, reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return null;
        }
        TransformComponent transform = safeGetComponent(
                store, reference, TransformComponent.getComponentType());
        World world = store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld() : null;
        return new TrackedNpc(
                npcUuid,
                transform != null ? new Vector3d(transform.getPosition()) : null,
                world
        );
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        return store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld() : null;
    }

    @Nullable
    private static <T extends Component<EntityStore>> T safeGetComponent(
            @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> reference,
            @Nullable ComponentType<EntityStore, T> componentType) {
        if (store == null || reference == null || !reference.isValid() || componentType == null) {
            return null;
        }
        try {
            return store.getComponent(reference, componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
            return null;
        }
    }

    record TrackedNpc(UUID npcUuid,
                      @Nullable Vector3d position,
                      @Nullable World world) {
    }
}

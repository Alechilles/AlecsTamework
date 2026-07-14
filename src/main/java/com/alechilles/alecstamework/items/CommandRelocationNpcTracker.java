package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Tracks the last observed world, position, and owner of NPCs used by command relocation.
 *
 * <p>Delete-on-remove worlds are terminal: their saved chunks are deleted after the
 * {@code RemoveWorldEvent}. Owned NPCs still mapped there are therefore detached as recovery
 * candidates while their complete in-memory state snapshots are still available.</p>
 */
final class CommandRelocationNpcTracker {
    private final Map<UUID, Vector3d> lastKnownByNpc;
    private final Map<UUID, World> knownWorldByNpc;
    private final ConcurrentHashMap<UUID, UUID> knownOwnerByNpc = new ConcurrentHashMap<>();

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
                   @Nullable Store<EntityStore> store) {
        if (reason == null) {
            return;
        }
        TrackedNpc tracked = resolve(reference, store);
        if (tracked == null) {
            return;
        }
        if (tracked.position() != null) {
            lastKnownByNpc.put(tracked.npcUuid(), new Vector3d(tracked.position()));
        }
        if (reason == RemoveReason.REMOVE) {
            knownWorldByNpc.remove(tracked.npcUuid());
            knownOwnerByNpc.remove(tracked.npcUuid());
            return;
        }
        if (tracked.world() != null) {
            knownWorldByNpc.put(tracked.npcUuid(), tracked.world());
        }
        if (tracked.ownerUuid() != null) {
            knownOwnerByNpc.put(tracked.npcUuid(), tracked.ownerUuid());
        }
    }

    List<WorldRemovalCandidate> detachDeleteOnRemoveWorld(@Nullable World world) {
        if (world == null || world.getWorldConfig() == null
                || !world.getWorldConfig().isDeleteOnRemove()) {
            return List.of();
        }
        List<WorldRemovalCandidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, World> entry : knownWorldByNpc.entrySet()) {
            UUID npcUuid = entry.getKey();
            if (npcUuid == null || entry.getValue() != world
                    || !knownWorldByNpc.remove(npcUuid, world)) {
                continue;
            }
            UUID ownerUuid = knownOwnerByNpc.get(npcUuid);
            if (ownerUuid == null) {
                continue;
            }
            Vector3d lastKnown = lastKnownByNpc.get(npcUuid);
            candidates.add(new WorldRemovalCandidate(
                    npcUuid,
                    ownerUuid,
                    lastKnown != null ? new Vector3d(lastKnown) : null,
                    world.getName()
            ));
        }
        return List.copyOf(candidates);
    }

    private void remember(TrackedNpc tracked) {
        if (tracked.position() != null) {
            lastKnownByNpc.put(tracked.npcUuid(), new Vector3d(tracked.position()));
        }
        if (tracked.world() != null) {
            knownWorldByNpc.put(tracked.npcUuid(), tracked.world());
        }
        if (tracked.ownerUuid() != null) {
            knownOwnerByNpc.put(tracked.npcUuid(), tracked.ownerUuid());
        } else {
            knownOwnerByNpc.remove(tracked.npcUuid());
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
        TameworkOwnerComponent owner = safeGetComponent(
                store, reference, TameworkOwnerComponent.getComponentType());
        TameworkCommandLinksComponent links = safeGetComponent(
                store, reference, TameworkCommandLinksComponent.getComponentType());
        UUID linkedOwnerUuid = resolveLinkedOwner(owner, links);
        World world = store != null && store.getExternalData() != null
                ? store.getExternalData().getWorld() : null;
        return new TrackedNpc(
                npcUuid,
                transform != null ? new Vector3d(transform.getPosition()) : null,
                linkedOwnerUuid,
                world
        );
    }

    @Nullable
    private static UUID resolveLinkedOwner(@Nullable TameworkOwnerComponent owner,
                                           @Nullable TameworkCommandLinksComponent links) {
        if (links == null || links.getToolIds() == null || links.getToolIds().length == 0) {
            return null;
        }
        return owner != null && owner.getOwnerId() != null
                ? owner.getOwnerId() : links.getOwnerId();
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
                      @Nullable UUID ownerUuid,
                      @Nullable World world) {
    }

    record WorldRemovalCandidate(UUID npcUuid,
                                 UUID ownerUuid,
                                 @Nullable Vector3d lastKnownPosition,
                                 @Nullable String worldName) {
    }
}

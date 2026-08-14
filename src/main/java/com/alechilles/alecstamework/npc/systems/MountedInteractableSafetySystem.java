package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Ensures mounted NPCs carry Interactable so vanilla mount transfer/remove flows can safely remove it.
 */
public final class MountedInteractableSafetySystem extends TickingSystem<EntityStore> {
    private static final long SWEEP_INTERVAL_MS = 100L;

    private final StoreSweepCadence sweepCadence =
            new StoreSweepCadence(SWEEP_INTERVAL_MS, System::currentTimeMillis);

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (!sweepCadence.claim(store)) {
            return;
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = resolveMountTypeOrNull();
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, Interactable> interactableType = Interactable.getComponentType();
        if (mountType == null || npcType == null || interactableType == null) {
            return;
        }

        store.forEachChunk(
                Query.and(mountType, npcType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        ensureInteractable(chunk, store, commandBuffer, mountType, interactableType)
        );
    }

    private void ensureInteractable(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                    @Nonnull Store<EntityStore> store,
                                    @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull ComponentType<EntityStore, NPCMountComponent> mountType,
                                    @Nonnull ComponentType<EntityStore, Interactable> interactableType) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> ref = chunk.getReferenceTo(i);
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCMountComponent mountComponent = chunk.getComponent(i, mountType);
            if (mountComponent == null) {
                continue;
            }
            PlayerRef ownerPlayerRef = mountComponent.getOwnerPlayerRef();
            if (ownerPlayerRef == null) {
                continue;
            }
            if (isInvalidOwnerReference(ownerPlayerRef, store)) {
                mountComponent.setOwnerPlayerRef(null);
            }
            if (containsComponent(store, ref, interactableType)) {
                continue;
            }
            commandBuffer.ensureComponent(ref, interactableType);
        }
    }

    private <T extends Component<EntityStore>> boolean containsComponent(@Nonnull Store<EntityStore> store,
                                                                         @Nonnull Ref<EntityStore> ref,
                                                                         @Nonnull ComponentType<EntityStore, T> componentType) {
        try {
            Archetype<EntityStore> archetype = store.getArchetype(ref);
            return archetype != null && archetype.contains(componentType);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean isInvalidOwnerReference(@Nonnull PlayerRef ownerPlayerRef,
                                            @Nonnull Store<EntityStore> store) {
        Ref<EntityStore> ownerRef = ownerPlayerRef.getReference();
        if (ownerRef == null || !ownerRef.isValid()) {
            return true;
        }
        return ownerRef.getStore() != store;
    }

    private ComponentType<EntityStore, NPCMountComponent> resolveMountTypeOrNull() {
        try {
            return NPCMountComponent.getComponentType();
        } catch (Throwable throwable) {
            return null;
        }
    }
}

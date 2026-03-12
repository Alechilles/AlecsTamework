package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Hides mounted NPC nameplates and restores the pre-mount/custom name when dismounted.
 */
public final class NpcMountedNameplateVisibilitySystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;
    private final ComponentType<EntityStore, NPCMountComponent> mountType;
    private final ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplateType;
    private final ComponentType<EntityStore, TameworkNpcNameComponent> customNameType;

    public NpcMountedNameplateVisibilitySystem(ComponentType<EntityStore, NPCEntity> npcType,
                                               ComponentType<EntityStore, NPCMountComponent> mountType,
                                               ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplateType,
                                               ComponentType<EntityStore, TameworkNpcNameComponent> customNameType) {
        this.npcType = npcType;
        this.mountType = mountType;
        this.mountedNameplateType = mountedNameplateType;
        this.customNameType = customNameType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || mountType == null || mountedNameplateType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null || store.getComponent(reference, mountType) == null) {
            return;
        }

        String cached = resolveCachedDisplayName(reference, store);
        String captured = (cached != null && !cached.isBlank()) ? cached : resolveVisibleDisplayName(reference, store);
        commandBuffer.run(bufferStore -> hideMountedNameplate(reference, bufferStore, captured));
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null || mountType == null || mountedNameplateType == null) {
            return;
        }
        boolean hasCachedName = store.getComponent(reference, mountedNameplateType) != null;
        if (!hasCachedName && !hasCustomName(reference, store)) {
            return;
        }
        commandBuffer.run(bufferStore -> restoreNameplateIfDismounted(reference, bufferStore));
    }

    @Override
    public Query<EntityStore> getQuery() {
        if (npcType == null || mountType == null) {
            return Query.any();
        }
        return Query.and(npcType, mountType);
    }

    private void hideMountedNameplate(Ref<EntityStore> reference,
                                      Store<EntityStore> store,
                                      @Nullable String capturedName) {
        if (!isMountedNpc(reference, store)) {
            return;
        }
        if (capturedName != null && !capturedName.isBlank()) {
            store.putComponent(reference, mountedNameplateType, new TameworkMountedNameplateComponent(capturedName));
        }
        EntitySupport.setDisplayName(reference, null, true, store);
    }

    private void restoreNameplateIfDismounted(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || store == null || !reference.isValid() || mountedNameplateType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null || store.getComponent(reference, mountType) != null) {
            return;
        }
        String restoreName = resolveRestoreName(reference, store);
        if (restoreName != null && !restoreName.isBlank()) {
            EntitySupport.setDisplayName(reference, restoreName, true, store);
        } else {
            EntitySupport.setDisplayName(reference, null, true, store);
        }
        store.tryRemoveComponent(reference, mountedNameplateType);
    }

    private boolean isMountedNpc(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null
                || store == null
                || !reference.isValid()
                || npcType == null
                || mountType == null) {
            return false;
        }
        return store.getComponent(reference, npcType) != null && store.getComponent(reference, mountType) != null;
    }

    private boolean hasCustomName(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (customNameType == null) {
            return false;
        }
        TameworkNpcNameComponent custom = store.getComponent(reference, customNameType);
        return custom != null && custom.getName() != null && !custom.getName().isBlank();
    }

    @Nullable
    private String resolveRestoreName(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (customNameType != null) {
            TameworkNpcNameComponent custom = store.getComponent(reference, customNameType);
            if (custom != null && custom.getName() != null && !custom.getName().isBlank()) {
                return custom.getName();
            }
        }
        return resolveCachedDisplayName(reference, store);
    }

    @Nullable
    private String resolveCachedDisplayName(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (mountedNameplateType == null) {
            return null;
        }
        TameworkMountedNameplateComponent cached = store.getComponent(reference, mountedNameplateType);
        if (cached == null || cached.getCachedDisplayName() == null || cached.getCachedDisplayName().isBlank()) {
            return null;
        }
        return cached.getCachedDisplayName();
    }

    @Nullable
    private String resolveVisibleDisplayName(Ref<EntityStore> reference, Store<EntityStore> store) {
        Nameplate nameplate = store.getComponent(reference, Nameplate.getComponentType());
        if (nameplate != null) {
            String text = nameplate.getText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        DisplayNameComponent displayName = store.getComponent(reference, DisplayNameComponent.getComponentType());
        if (displayName != null && displayName.getDisplayName() != null) {
            String ansi = displayName.getDisplayName().getAnsiMessage();
            if (ansi != null && !ansi.isBlank()) {
                return ansi;
            }
        }
        return null;
    }
}

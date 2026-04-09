package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
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
    private final MountedNameplateTextService textService;

    public NpcMountedNameplateVisibilitySystem(ComponentType<EntityStore, NPCEntity> npcType,
                                               ComponentType<EntityStore, NPCMountComponent> mountType,
                                               ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplateType,
                                               ComponentType<EntityStore, TameworkNpcNameComponent> customNameType) {
        this.npcType = npcType;
        this.mountType = mountType;
        this.mountedNameplateType = mountedNameplateType;
        this.customNameType = customNameType;
        this.textService = new MountedNameplateTextService();
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
        hideMountedNameplate(reference, store, commandBuffer, captured);
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
        restoreNameplateIfDismounted(reference, store, commandBuffer);
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
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                      @Nullable String capturedName) {
        if (!isMountedNpc(reference, store)) {
            return;
        }
        boolean hasVisibleName = capturedName != null && !capturedName.isBlank();
        if (!hasVisibleName && !hasCustomName(reference, store)) {
            return;
        }
        if (hasVisibleName) {
            putComponent(
                    reference,
                    commandBuffer,
                    mountedNameplateType,
                    new TameworkMountedNameplateComponent(capturedName)
            );
        }
        setVisibleName(reference, store, commandBuffer, null);
    }

    private void restoreNameplateIfDismounted(Ref<EntityStore> reference,
                                              Store<EntityStore> store,
                                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (reference == null || store == null || !reference.isValid() || mountedNameplateType == null) {
            return;
        }
        if (store.getComponent(reference, npcType) == null || store.getComponent(reference, mountType) != null) {
            return;
        }
        String restoreName = resolveRestoreName(reference, store);
        setVisibleName(reference, store, commandBuffer, restoreName);
        commandBuffer.run(bufferStore -> bufferStore.tryRemoveComponent(reference, mountedNameplateType));
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

    private void setVisibleName(Ref<EntityStore> reference,
                                Store<EntityStore> store,
                                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                @Nullable String displayName) {
        updateDisplayNameComponent(reference, store, commandBuffer, displayName);
        updateNameplateComponent(reference, store, displayName);
    }

    private void updateDisplayNameComponent(Ref<EntityStore> reference,
                                            Store<EntityStore> store,
                                            @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                            @Nullable String displayName) {
        ComponentType<EntityStore, DisplayNameComponent> displayNameType = DisplayNameComponent.getComponentType();
        if (!containsComponent(reference, store, displayNameType)) {
            return;
        }
        putComponent(
                reference,
                commandBuffer,
                displayNameType,
                textService.createDisplayNameComponent(displayName)
        );
    }

    private void updateNameplateComponent(Ref<EntityStore> reference,
                                          Store<EntityStore> store,
                                          @Nullable String displayName) {
        ComponentType<EntityStore, Nameplate> nameplateType = Nameplate.getComponentType();
        if (!containsComponent(reference, store, nameplateType)) {
            return;
        }
        Nameplate nameplate = store.getComponent(reference, nameplateType);
        if (nameplate == null) {
            return;
        }
        if (displayName == null || displayName.isBlank()) {
            textService.hideNameplate(nameplate);
            return;
        }
        textService.restoreNameplate(nameplate, displayName);
    }

    private <T extends Component<EntityStore>> boolean containsComponent(Ref<EntityStore> reference,
                                                                          Store<EntityStore> store,
                                                                          ComponentType<EntityStore, T> componentType) {
        if (reference == null || store == null || componentType == null || !reference.isValid()) {
            return false;
        }
        Archetype<EntityStore> archetype = store.getArchetype(reference);
        return archetype.contains(componentType);
    }

    private <T extends Component<EntityStore>> void putComponent(@Nonnull Ref<EntityStore> reference,
                                                                 @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                                                 @Nonnull ComponentType<EntityStore, T> componentType,
                                                                 @Nonnull T component) {
        commandBuffer.putComponent(reference, componentType, component);
    }
}

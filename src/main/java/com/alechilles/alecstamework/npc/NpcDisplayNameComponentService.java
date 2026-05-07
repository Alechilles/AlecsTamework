package com.alechilles.alecstamework.npc;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentDisplayName;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Reads and writes Update 5 NPC display-name components consistently.
 */
public final class NpcDisplayNameComponentService {
    private NpcDisplayNameComponentService() {
    }

    public static void putPersistentAndRuntimeName(CommandBuffer<EntityStore> commandBuffer,
                                                   Ref<EntityStore> reference,
                                                   String displayName) {
        if (commandBuffer == null || reference == null || displayName == null || displayName.isBlank()) {
            return;
        }
        commandBuffer.putComponent(reference, PersistentDisplayName.getComponentType(), createPersistent(displayName));
        commandBuffer.putComponent(reference, DisplayNameComponent.getComponentType(), createRuntime(displayName));
    }

    public static void putPersistentAndRuntimeName(ComponentAccessor<EntityStore> accessor,
                                                   Ref<EntityStore> reference,
                                                   String displayName) {
        if (accessor == null || reference == null || !reference.isValid()
                || displayName == null || displayName.isBlank()) {
            return;
        }
        accessor.putComponent(reference, PersistentDisplayName.getComponentType(), createPersistent(displayName));
        accessor.putComponent(reference, DisplayNameComponent.getComponentType(), createRuntime(displayName));
    }

    public static PersistentDisplayName createPersistent(@Nullable String displayName) {
        return new PersistentDisplayName(Message.raw(normalize(displayName)));
    }

    public static DisplayNameComponent createRuntime(@Nullable String displayName) {
        return new DisplayNameComponent(Message.raw(normalize(displayName)));
    }

    @Nullable
    public static String resolvePersistentOrRuntimeName(Ref<EntityStore> reference, Store<EntityStore> store) {
        String persistent = resolvePersistentName(reference, store);
        if (persistent != null && !persistent.isBlank()) {
            return persistent;
        }
        return resolveRuntimeName(reference, store);
    }

    @Nullable
    public static String resolvePersistentName(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return null;
        }
        PersistentDisplayName displayName = store.getComponent(reference, PersistentDisplayName.getComponentType());
        return displayName != null ? read(displayName.getDisplayName()) : null;
    }

    @Nullable
    public static String resolveRuntimeName(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return null;
        }
        DisplayNameComponent displayName = store.getComponent(reference, DisplayNameComponent.getComponentType());
        return displayName != null ? read(displayName.getDisplayName()) : null;
    }

    @Nullable
    private static String read(@Nullable Message message) {
        if (message == null) {
            return null;
        }
        String ansi = message.getAnsiMessage();
        return ansi != null && !ansi.isBlank() ? ansi : null;
    }

    private static String normalize(@Nullable String displayName) {
        return displayName != null ? displayName : "";
    }
}

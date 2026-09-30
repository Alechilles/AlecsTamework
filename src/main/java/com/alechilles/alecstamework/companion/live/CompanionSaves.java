package com.alechilles.alecstamework.companion.live;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.Dirty;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Marks a companion body so Hytale saves its chunk section (spec 6.4). Hytale saves entity
 * component changes only when the entity is marked dirty. Call on the owning world thread
 * with the current Store or CommandBuffer.
 */
public final class CompanionSaves {
    private CompanionSaves() {
    }

    /** Returns true when the body had a Dirty component and it was marked. */
    public static boolean markChanged(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return false;
        }
        Dirty dirty = accessor.getComponent(ref, Dirty.getComponentType());
        if (dirty == null) {
            return false;
        }
        dirty.markDirty();
        return true;
    }
}

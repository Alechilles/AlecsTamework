package com.alechilles.alecstamework.api.internal;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Internal bridge used by Tamework runtime systems to execute registered trait effects.
 */
public interface TraitEffectRuntime {
    void applyRegisteredEffects(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store);
}

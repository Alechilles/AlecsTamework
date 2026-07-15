package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Marks the otherwise vanilla corpse timer that Tamework owns as a durability barrier. */
public final class CompanionPermanentDeathHold {
    private CompanionPermanentDeathHold() {
    }

    @Nonnull
    public static DeferredCorpseRemoval create(@Nullable String deathParticles) {
        // Archetype component slots require the exact class registered for the component type.
        // A subclass here corrupts the slot during entity moves such as chunk unloading.
        return new DeferredCorpseRemoval(Double.MAX_VALUE, deathParticles);
    }

    public static boolean isHold(@Nullable DeferredCorpseRemoval component) {
        if (component == null) {
            return false;
        }
        // A cloned vanilla timer expires after this probe; the deliberate Double.MAX_VALUE hold
        // does not. The live component is never mutated.
        DeferredCorpseRemoval probe = (DeferredCorpseRemoval) component.clone();
        probe.tick(Float.MAX_VALUE);
        return !probe.shouldRemove();
    }
}

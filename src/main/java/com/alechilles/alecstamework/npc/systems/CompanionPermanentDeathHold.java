package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Marks the otherwise vanilla corpse timer that Tamework owns as a durability barrier. */
public final class CompanionPermanentDeathHold {
    private CompanionPermanentDeathHold() {
    }

    @Nonnull
    public static DeferredCorpseRemoval create(@Nullable String deathParticles) {
        return new Hold(deathParticles);
    }

    public static boolean isHold(@Nullable DeferredCorpseRemoval component) {
        if (component == null) {
            return false;
        }
        if (component instanceof Hold) {
            return true;
        }
        // Component persistence can restore the registered base type instead of this subclass.
        // A cloned vanilla timer expires after this probe; the deliberate Double.MAX_VALUE hold
        // does not. The live component is never mutated.
        DeferredCorpseRemoval probe = (DeferredCorpseRemoval) component.clone();
        probe.tick(Float.MAX_VALUE);
        return !probe.shouldRemove();
    }

    private static final class Hold extends DeferredCorpseRemoval {
        private Hold(@Nullable String deathParticles) {
            super(Double.MAX_VALUE, deathParticles);
        }

        @Nonnull
        @Override
        public Component<EntityStore> clone() {
            return new Hold(getDeathParticles());
        }
    }
}

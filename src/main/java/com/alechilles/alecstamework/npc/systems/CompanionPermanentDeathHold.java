package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Marks the otherwise vanilla corpse timer that Tamework owns as a durability barrier. */
final class CompanionPermanentDeathHold {
    private CompanionPermanentDeathHold() {
    }

    @Nonnull
    static DeferredCorpseRemoval create(@Nullable String deathParticles) {
        return new Hold(deathParticles);
    }

    static boolean isHold(@Nullable DeferredCorpseRemoval component) {
        return component instanceof Hold;
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

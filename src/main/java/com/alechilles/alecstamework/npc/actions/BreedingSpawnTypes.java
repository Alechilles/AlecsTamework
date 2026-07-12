package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable offspring role selected before admission and spawn. */
record BreedingResolvedSpawnRole(String roleId,
                                 int roleIndex,
                                 String adultRoleId,
                                 @Nullable TwBreedingConfig.Gender gender,
                                 @Nullable TwBreedingConfig.RoleFamily lifecycleFamily) {
}

/** Mutation hook that initializes an admitted offspring holder before it enters the store. */
@FunctionalInterface
interface BreedingSpawnHolderPreparation {
    /** @return null on success, otherwise an admission/mutation failure reason. */
    @Nullable
    String prepare(@Nonnull NPCEntity npc,
                   @Nonnull Holder<EntityStore> holder,
                   @Nonnull Store<EntityStore> store);
}

/** Result of one prepared offspring spawn attempt. */
record BreedingPreparedSpawnResult(@Nullable Pair<Ref<EntityStore>, NPCEntity> spawned,
                                   @Nullable String reason,
                                   boolean preparationFailed,
                                   boolean outcomeAmbiguous) {
    @Nonnull
    static BreedingPreparedSpawnResult failed(@Nonnull String reason) {
        return new BreedingPreparedSpawnResult(null, reason, false, false);
    }

    @Nonnull
    static BreedingPreparedSpawnResult ambiguous(@Nonnull String reason) {
        return new BreedingPreparedSpawnResult(null, reason, false, true);
    }
}

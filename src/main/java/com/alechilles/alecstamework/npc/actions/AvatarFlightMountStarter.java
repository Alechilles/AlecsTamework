package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.avatarflight.AvatarFlightMountLifecycleService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Starts the avatar-flight mount mode after optimized interaction requirements pass. */
final class AvatarFlightMountStarter {
    private final AvatarFlightMountLifecycleService lifecycle = new AvatarFlightMountLifecycleService();

    boolean start(@Nonnull Store<EntityStore> store,
                  @Nonnull Ref<EntityStore> npcRef,
                  @Nonnull Ref<EntityStore> playerRef,
                  @Nonnull Role role,
                  @Nullable String configId) {
        return lifecycle.start(store, npcRef, playerRef, role, configId).ok();
    }
}

package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;

/** Immutable immediate-call context shared by mount-mode handlers. */
record InteractionMountRequest(@Nonnull Ref<EntityStore> npcRef,
                               @Nonnull Ref<EntityStore> playerRef,
                               @Nonnull Role role,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull String configuredMode) {
}

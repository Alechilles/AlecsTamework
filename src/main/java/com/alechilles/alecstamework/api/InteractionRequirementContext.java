package com.alechilles.alecstamework.api;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record InteractionRequirementContext(@Nullable String interactionConfigId,
                                            int interactionIndex,
                                            @Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Role role,
                                            @Nullable InfoProvider infoProvider,
                                            @Nonnull Store<EntityStore> store,
                                            @Nullable Player player,
                                            @Nullable UUID playerUuid,
                                            @Nullable String heldItemId,
                                            boolean promptCheck) {
}

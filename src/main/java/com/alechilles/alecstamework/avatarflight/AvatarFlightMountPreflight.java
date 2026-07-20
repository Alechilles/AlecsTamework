package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Performs mutation-free validation for an NPC-backed avatar-flight mount start. */
public final class AvatarFlightMountPreflight {
    private static final String EMPTY_ROLE_ID = "Empty_Role";

    @Nonnull
    public Result prepare(@Nonnull Store<EntityStore> store,
                          @Nullable Ref<EntityStore> npcRef,
                          @Nullable Ref<EntityStore> playerRef,
                          @Nullable Role role,
                          @Nullable String configId) {
        if (!valid(npcRef) || !valid(playerRef) || role == null) {
            return Result.fail("missing_or_invalid_context");
        }
        if (store.getComponent(npcRef, DeathComponent.getComponentType()) != null
                || store.getComponent(playerRef, DeathComponent.getComponentType()) != null) {
            return Result.fail("dead_participant");
        }
        UUIDComponent npcUuid = store.getComponent(npcRef, UUIDComponent.getComponentType());
        UUIDComponent playerUuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        Player player = store.getComponent(playerRef, Player.getComponentType());
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (missingUuid(npcUuid) || missingUuid(playerUuid) || npc == null || player == null
                || !ready(npcTransform) || !ready(playerTransform)) {
            return Result.fail("missing_required_component");
        }
        if (hasExistingMountState(store, npcRef, playerRef)) {
            return Result.fail("existing_mount_state");
        }
        TwAvatarFlightConfig config = TwAvatarFlightConfig.resolve(configId);
        if (config == null || !config.isEnabled()) {
            return Result.fail("config_disabled_or_missing");
        }
        if (config.getModel().isApplyModel()
                && ModelAsset.getAssetMap().getAsset(config.getModel().getModelId()) == null) {
            return Result.fail("model_missing");
        }
        int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
        int emptyRoleIndex = NPCPlugin.get().getIndex(EMPTY_ROLE_ID);
        if (originalRoleIndex < 0 || emptyRoleIndex < 0) {
            return Result.fail("role_index_missing");
        }
        return Result.ok(new Prepared(
                npcUuid.getUuid().toString(),
                playerUuid.getUuid().toString(),
                config,
                originalRoleIndex,
                emptyRoleIndex
        ));
    }

    public boolean canStart(@Nonnull Store<EntityStore> store,
                            @Nullable Ref<EntityStore> npcRef,
                            @Nullable Ref<EntityStore> playerRef,
                            @Nullable Role role,
                            @Nullable String configId) {
        return prepare(store, npcRef, playerRef, role, configId).ok();
    }

    private static boolean hasExistingMountState(Store<EntityStore> store,
                                                 Ref<EntityStore> npcRef,
                                                 Ref<EntityStore> playerRef) {
        return has(store, playerRef, AvatarFlightMountSessionComponent.getComponentType())
                || has(store, npcRef, AvatarFlightSourceComponent.getComponentType())
                || has(store, playerRef, AvatarFlightComponent.getComponentType())
                || has(store, playerRef, MountedComponent.getComponentType())
                || has(store, npcRef, NPCMountComponent.getComponentType())
                || has(store, playerRef, TameworkRideRiderComponent.getComponentType())
                || has(store, npcRef, TameworkRideMountComponent.getComponentType())
                || has(store, playerRef, TameworkMountedGlideRiderComponent.getComponentType())
                || has(store, npcRef, TameworkMountedGlideComponent.getComponentType());
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> boolean has(
            Store<EntityStore> store, Ref<EntityStore> ref, ComponentType<EntityStore, T> type) {
        return type != null && store.getComponent(ref, type) != null;
    }

    private static boolean valid(Ref<EntityStore> ref) { return ref != null && ref.isValid(); }
    private static boolean missingUuid(UUIDComponent uuid) { return uuid == null || uuid.getUuid() == null; }
    private static boolean ready(TransformComponent transform) {
        return transform != null && transform.getPosition() != null && transform.getRotation() != null;
    }

    public record Prepared(@Nonnull String npcUuid,
                           @Nonnull String playerUuid,
                           @Nonnull TwAvatarFlightConfig config,
                           int originalRoleIndex,
                           int emptyRoleIndex) {
    }

    public record Result(boolean ok, @Nullable Prepared prepared, @Nonnull String reason) {
        static Result ok(Prepared prepared) { return new Result(true, prepared, "ok"); }
        static Result fail(String reason) { return new Result(false, null, reason); }
    }
}

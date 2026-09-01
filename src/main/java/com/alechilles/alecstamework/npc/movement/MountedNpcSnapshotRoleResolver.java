package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSnapshotRoleResolver;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.identity.CanonicalCompanionRolePolicy;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the durable NPC role hidden by temporary mount parking roles. */
public final class MountedNpcSnapshotRoleResolver {
    private MountedNpcSnapshotRoleResolver() {
    }

    /** Resolves avatar-flight and native-mount parking without changing a real live role. */
    @Nonnull
    public static Resolution resolve(@Nullable String liveRoleId,
                                     @Nonnull Ref<EntityStore> npcRef,
                                     @Nonnull Store<EntityStore> store) {
        String liveRole = clean(liveRoleId);
        ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType =
                AvatarFlightSourceComponent.getComponentType();
        AvatarFlightSourceComponent source = sourceType == null
                ? null : store.getComponent(npcRef, sourceType);
        String avatarRole = AvatarFlightSnapshotRoleResolver.resolve(liveRole, source);
        if (!Objects.equals(liveRole, avatarRole)) {
            return new Resolution(avatarRole, true);
        }

        ComponentType<EntityStore, NPCMountComponent> mountType =
                NPCMountComponent.getComponentType();
        NPCMountComponent mount = mountType == null
                ? null : store.getComponent(npcRef, mountType);
        int originalRoleIndex = mount == null ? -1 : mount.getOriginalRoleIndex();
        return resolveNativeParking(
                avatarRole,
                originalRoleIndex,
                MountedNpcSnapshotRoleResolver::registeredRoleId
        );
    }

    @Nonnull
    static Resolution resolveNativeParking(@Nullable String liveRoleId,
                                           int originalRoleIndex,
                                           @Nonnull IntFunction<String> roleLookup) {
        String liveRole = clean(liveRoleId);
        if (!CanonicalCompanionRolePolicy.isTemporaryParkingRole(liveRole)
                || originalRoleIndex < 0) {
            return new Resolution(liveRole, false);
        }
        String originalRole = clean(roleLookup.apply(originalRoleIndex));
        return originalRole.isEmpty()
                ? new Resolution(liveRole, false)
                : new Resolution(originalRole, true);
    }

    @Nullable
    private static String registeredRoleId(int roleIndex) {
        NPCPlugin plugin = NPCPlugin.get();
        return plugin == null ? null : plugin.getName(roleIndex);
    }

    @Nonnull
    private static String clean(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim();
    }

    /** Resolved role and whether the live NPC is using a temporary parking role. */
    public record Resolution(@Nonnull String roleId, boolean temporarilyParked) {
    }
}

package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.companion.identity.CanonicalCompanionRolePolicy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the durable NPC role hidden by Avatar Flight's temporary parking role. */
public final class AvatarFlightSnapshotRoleResolver {
    private AvatarFlightSnapshotRoleResolver() {
    }

    /** Uses the captured source role only while the live NPC is parked in Empty_Role. */
    @Nonnull
    public static String resolve(
            @Nullable String liveRoleId,
            @Nullable AvatarFlightSourceComponent source
    ) {
        String liveRole = clean(liveRoleId);
        if (!CanonicalCompanionRolePolicy.isTemporaryParkingRole(liveRole)
                || source == null) {
            return liveRole;
        }
        String originalRole = clean(source.getOriginalRoleId());
        return originalRole.isEmpty() ? liveRole : originalRole;
    }

    /** Reads the optional source component before resolving a snapshot role. */
    @Nonnull
    public static String resolve(
            @Nullable String liveRoleId,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store
    ) {
        ComponentType<EntityStore, AvatarFlightSourceComponent> sourceType =
                AvatarFlightSourceComponent.getComponentType();
        AvatarFlightSourceComponent source = sourceType == null
                ? null : store.getComponent(npcRef, sourceType);
        return resolve(liveRoleId, source);
    }

    /** Repairs the known persisted parking role without masking real role swaps. */
    @Nonnull
    public static String repairStoredRole(
            @Nullable String storedRoleId,
            @Nullable String profileRoleId
    ) {
        return CanonicalCompanionRolePolicy.repairTemporaryRole(
                storedRoleId, profileRoleId
        );
    }

    @Nonnull
    private static String clean(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim();
    }
}

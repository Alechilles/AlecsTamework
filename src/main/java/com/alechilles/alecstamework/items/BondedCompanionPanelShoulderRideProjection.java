package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionShoulderRideSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Optional;
import javax.annotation.Nullable;

/** Projects whether a live bonded companion can ride the current player's shoulder. */
final class BondedCompanionPanelShoulderRideProjection {
    Optional<Boolean> read(BondedCompanionProfileView profile,
                           @Nullable String currentWorldKey,
                           @Nullable Ref<EntityStore> npcRef,
                           @Nullable Ref<EntityStore> playerRef,
                           @Nullable Store<EntityStore> store) {
        if (profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null || currentWorldKey == null
                || !currentWorldKey.equals(profile.activeLease().worldKey())
                || npcRef == null || !npcRef.isValid()
                || playerRef == null || !playerRef.isValid()
                || store == null || npcRef.getStore() != store
                || playerRef.getStore() != store) {
            return Optional.empty();
        }
        try {
            MountedComponent mounted = MountedComponent.getComponentType() == null
                    ? null : store.getComponent(npcRef,
                    MountedComponent.getComponentType());
            if (mounted != null) {
                boolean marked = TameworkShoulderRideComponent.getComponentType()
                        != null && store.getComponent(npcRef,
                        TameworkShoulderRideComponent.getComponentType()) != null;
                return marked && playerRef.equals(mounted.getMountedToEntity())
                        ? Optional.of(true) : Optional.empty();
            }
            String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
            TwCompanionShoulderRideSettings settings = roleId == null ? null
                    : TwCompanionConfig.resolveEffectiveForRole(roleId)
                    .getShoulderRide();
            if (settings == null || !settings.isConfigured()) {
                return Optional.empty();
            }
            return Optional.of(false);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }
}

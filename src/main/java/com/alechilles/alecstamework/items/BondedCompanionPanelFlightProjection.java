package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** Authoritative gate for transient bonded-card flight presentation. */
final class BondedCompanionPanelFlightProjection {
    private static final LiveResolver HYTALE_RESOLVER = new LiveResolver() {
        @Override
        public NPCEntity npc(Ref<EntityStore> reference,
                             Store<EntityStore> store) {
            return NPCEntity.getComponentType() == null ? null
                    : store.getComponent(reference, NPCEntity.getComponentType());
        }

        @Override
        public String roleId(Ref<EntityStore> reference,
                             Store<EntityStore> store) {
            return CompanionRoleIdResolver.resolveRoleId(reference, store);
        }

        @Override
        public TwCompanionFlightToggleSettings settings(String roleId) {
            return TwCompanionConfig.resolveEffectiveForRole(roleId)
                    .getFlightToggle();
        }
    };

    private final BiFunction<
            NPCEntity,
            TwCompanionFlightToggleSettings,
            Optional<Boolean>> reader;

    BondedCompanionPanelFlightProjection(BondedCompanionFlightModeReader reader) {
        this(reader::read);
    }

    BondedCompanionPanelFlightProjection(BiFunction<
            NPCEntity,
            TwCompanionFlightToggleSettings,
            Optional<Boolean>> reader) {
        this.reader = reader;
    }

    Optional<Boolean> read(BondedCompanionProfileView profile,
                           @Nullable String currentWorldKey,
                           @Nullable Ref<EntityStore> reference,
                           @Nullable Store<EntityStore> store) {
        return read(profile, currentWorldKey, reference, store,
                HYTALE_RESOLVER);
    }

    Optional<Boolean> read(BondedCompanionProfileView profile,
                           @Nullable String currentWorldKey,
                           @Nullable Ref<EntityStore> reference,
                           @Nullable Store<EntityStore> store,
                           @Nullable LiveResolver resolver) {
        if (profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null
                || profile.activeLease().liveNpcUuid() == null
                || currentWorldKey == null
                || !currentWorldKey.equals(profile.activeLease().worldKey())
                || reference == null || !reference.isValid()
                || store == null || reference.getStore() != store
                || resolver == null) {
            return Optional.empty();
        }
        try {
            NPCEntity npc = resolver.npc(reference, store);
            if (npc == null) {
                return Optional.empty();
            }
            String liveRoleId = resolver.roleId(reference, store);
            if (liveRoleId == null || !liveRoleId.equals(profile.roleId())) {
                return Optional.empty();
            }
            TwCompanionFlightToggleSettings settings =
                    resolver.settings(liveRoleId);
            if (settings == null || !settings.isConfigured()) {
                return Optional.empty();
            }
            return reader.apply(npc, settings);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    interface LiveResolver {
        @Nullable
        NPCEntity npc(Ref<EntityStore> reference, Store<EntityStore> store);

        @Nullable
        String roleId(Ref<EntityStore> reference, Store<EntityStore> store);

        @Nullable
        TwCompanionFlightToggleSettings settings(String roleId);
    }
}

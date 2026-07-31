package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nullable;

/** Authoritative gate for transient bonded-card flight presentation. */
final class BondedCompanionPanelFlightProjection {
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
                           @Nullable Store<EntityStore> store,
                           @Nullable NPCEntity npc, @Nullable String liveRoleId,
                           @Nullable TwCompanionFlightToggleSettings settings) {
        if (profile.state() != BondedCompanionStateView.ACTIVE
                || profile.activeLease() == null
                || profile.activeLease().liveNpcUuid() == null
                || currentWorldKey == null
                || !currentWorldKey.equals(profile.activeLease().worldKey())
                || reference == null || !reference.isValid()
                || store == null || reference.getStore() != store
                || npc == null || liveRoleId == null
                || !liveRoleId.equals(profile.roleId())
                || settings == null || !settings.isConfigured()) {
            return Optional.empty();
        }
        return reader.apply(npc, settings);
    }
}

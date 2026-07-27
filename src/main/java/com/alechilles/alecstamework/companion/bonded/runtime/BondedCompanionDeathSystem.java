package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Routes exact bonded projection death evidence into the isolated authority. */
public final class BondedCompanionDeathSystem extends DeathSystems.OnDeathSystem {
    private final TameworkBondedCompanionComposition composition;
    private final ComponentType<EntityStore, TameworkProjectionIdentityComponent>
            markerType;
    private final ComponentType<EntityStore, UUIDComponent> uuidType;
    private final Query<EntityStore> query;

    public BondedCompanionDeathSystem(
            @Nonnull TameworkBondedCompanionComposition composition,
            @Nonnull ComponentType<EntityStore, TameworkProjectionIdentityComponent>
                    markerType,
            @Nonnull ComponentType<EntityStore, UUIDComponent> uuidType
    ) {
        this.composition = Objects.requireNonNull(composition, "composition");
        this.markerType = Objects.requireNonNull(markerType, "markerType");
        this.uuidType = Objects.requireNonNull(uuidType, "uuidType");
        this.query = Query.and(markerType, uuidType);
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        TameworkProjectionIdentityComponent marker =
                store.getComponent(reference, markerType);
        UUIDComponent uuid = store.getComponent(reference, uuidType);
        World world = store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
        if (marker == null || !marker.isBondedCompanion()
                || uuid == null || uuid.getUuid() == null || world == null) {
            return;
        }
        composition.onConfirmedDeath(
                world.getName(), uuid.getUuid(), marker, reference, store);
    }

    @Nonnull @Override public Query<EntityStore> getQuery() {
        return query;
    }
}

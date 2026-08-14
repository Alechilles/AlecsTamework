package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Drives bounded bonded cleanup, retention pruning, and lease expiry. */
public final class BondedCompanionMaintenanceSystem
        extends TickingSystem<EntityStore> {
    private final TameworkBondedCompanionComposition composition;
    private final BondedCompanionMaintenanceCadence cadence =
            new BondedCompanionMaintenanceCadence();

    public BondedCompanionMaintenanceSystem(
            @Nonnull TameworkBondedCompanionComposition composition
    ) {
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        EntityStore external = store.getExternalData();
        com.hypixel.hytale.server.core.universe.world.World world =
                external == null ? null : external.getWorld();
        if (world == null || world.getName() == null) return;
        String worldKey = world.getName();
        long now = System.nanoTime();
        BondedCompanionMaintenanceCadence.WorldClaim worldClaim =
                composition.isWorldMaintenanceKnownIdle(worldKey)
                        ? null : cadence.claimWorld(worldKey, now);
        if (worldClaim != null) {
            TameworkBondedCompanionComposition.WorldMaintenanceResult result =
                    composition.worldMaintenanceTick(worldKey);
            cadence.completeWorld(worldClaim, now,
                    result.requiresFastCadence()
                            || composition.hasRuntimeLeaseActivity(worldKey));
        }
        if (cadence.claimGlobal(now)) {
            composition.maintenanceTick();
        }
    }
}

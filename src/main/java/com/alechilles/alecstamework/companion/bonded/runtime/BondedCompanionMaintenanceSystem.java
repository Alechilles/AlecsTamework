package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;

/** Drives bounded bonded cleanup, retention pruning, and lease expiry. */
public final class BondedCompanionMaintenanceSystem
        extends TickingSystem<EntityStore> {
    private static final long INTERVAL_NANOS = 1_000_000_000L;
    private final TameworkBondedCompanionComposition composition;
    private final ConcurrentMap<String, Long> nextRuns = new ConcurrentHashMap<>();

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
        Long next = nextRuns.compute(worldKey, (ignored, prior) ->
                prior == null || now >= prior ? now + INTERVAL_NANOS : prior);
        if (next != null && next == now + INTERVAL_NANOS) {
            composition.maintenanceTick(worldKey);
        }
    }
}

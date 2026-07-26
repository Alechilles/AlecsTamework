package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/** Drives bounded bonded cleanup and lease expiry without a private executor. */
public final class BondedCompanionMaintenanceSystem
        extends TickingSystem<EntityStore> {
    private static final long INTERVAL_NANOS = 1_000_000_000L;
    private final TameworkBondedCompanionComposition composition;
    private final AtomicLong nextRun = new AtomicLong();

    public BondedCompanionMaintenanceSystem(
            @Nonnull TameworkBondedCompanionComposition composition
    ) {
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        long now = System.nanoTime();
        long next = nextRun.get();
        if (now < next || !nextRun.compareAndSet(next, now + INTERVAL_NANOS)) {
            return;
        }
        composition.maintenanceTick();
    }
}

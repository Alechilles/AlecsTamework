package com.alechilles.alecstamework.npc.systems;

import com.alechilles.beacon.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsBatchRunner;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsDispatchPolicy;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsRuntimeRegistry;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsTaskTiming;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemTypeDependency;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Runs periodic hunger/thirst progression updates for tamed companions.
 */
public final class CompanionNeedsSystem extends TickingSystem<EntityStore> {
    private static final Consumer<TelemetryBreadcrumbContext> SLOW_TASK_RECORDER =
            TameworkTelemetryEvents::recordBreadcrumbIfAvailable;

    private final CompanionNeedsRuntimeRegistry registry;
    private final CompanionNeedsBatchRunner batchRunner;

    public CompanionNeedsSystem(@Nonnull CompanionNeedsRuntimeRegistry registry,
                                @Nonnull CompanionNeedsBatchRunner batchRunner) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.batchRunner = Objects.requireNonNull(batchRunner, "batchRunner");
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(
                new SystemTypeDependency<>(Order.BEFORE, EntityStatsModule.get().getStatModifyingSystemType()),
                new SystemDependency<>(Order.BEFORE, EntityStatsSystems.Changes.class)
        );
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CompanionRuntimeClock.advanceByDeltaSeconds(dt);
        long nowMs = System.currentTimeMillis();
        CompanionNeedsRuntimeRegistry.WorldState state = registry.state(store);
        if (CompanionNeedsDispatchPolicy.decide(state, nowMs)
                != CompanionNeedsDispatchPolicy.Decision.DISPATCH) {
            return;
        }
        World world = store.getExternalData() == null ? null : store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        CompanionNeedsBatchRunner runner = batchRunner;
        CompanionNeedsDispatchPolicy.dispatchIfNeeded(state, nowMs, () -> {
            world.execute(() -> {
                long startedAtNanos = System.nanoTime();
                try {
                    runner.run(world, state, System.currentTimeMillis(), System::nanoTime);
                } finally {
                    state.setDispatchPending(false);
                    CompanionNeedsTaskTiming.record(
                            startedAtNanos,
                            System.nanoTime(),
                            SLOW_TASK_RECORDER
                    );
                }
            });
        });
    }
}

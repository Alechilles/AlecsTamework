package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.progression.NeedsFoodTargetSearchService;
import com.alechilles.alecstamework.npc.progression.NeedsResourceCandidates;
import com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator;
import com.alechilles.alecstamework.npc.progression.NeedsWaterTargetSearchService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs at most one admitted cold needs-resource search per world tick.
 *
 * <p>The coordinator carries only UUIDs and immutable request data across
 * ticks. This system resolves a live waiter reference on the current world
 * thread for the duration of one scanner call and retains no ECS objects.</p>
 */
public final class NeedsResourceSearchSystem extends TickingSystem<EntityStore> {
    private static final NeedsWaterTargetSearchService WATER_SEARCH_SERVICE =
            new NeedsWaterTargetSearchService();
    private static final NeedsFoodTargetSearchService FOOD_SEARCH_SERVICE =
            new NeedsFoodTargetSearchService();

    private final NeedsResourceSearchCoordinator coordinator;
    private final NeedsResourceSearchCoordinator.SearchExecutor searchExecutor;

    /** Creates a worker with the supplied coordinator and scanner dispatcher. */
    public NeedsResourceSearchSystem(
            @Nonnull NeedsResourceSearchCoordinator coordinator,
            @Nonnull NeedsResourceSearchCoordinator.SearchExecutor searchExecutor) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.searchExecutor = Objects.requireNonNull(searchExecutor, "searchExecutor");
    }

    /** Creates the registered worker with the process-wide coordinator. */
    public NeedsResourceSearchSystem(@Nonnull NeedsResourceSearchCoordinator coordinator) {
        this(coordinator, NeedsResourceSearchSystem::searchQueuedRequest);
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        coordinator.processNext(store, System.currentTimeMillis(), searchExecutor);
    }

    @Nullable
    private static NeedsResourceCandidates.Snapshot searchQueuedRequest(
            @Nonnull Store<EntityStore> store,
            @Nonnull NeedsResourceSearchCoordinator.Request request,
            @Nonnull List<UUID> waiterIds) {
        SearchContext context = resolveFirstValidWaiter(store, request, waiterIds);
        if (context == null) {
            return null;
        }
        return switch (request.resourceKind()) {
            case NeedsResourceSearchCoordinator.RESOURCE_KIND_WATER -> WATER_SEARCH_SERVICE.search(
                    store,
                    context.reference(),
                    new NeedsWaterTargetSearchService.WaterRequest(
                            context.originX(),
                            context.originY(),
                            context.originZ(),
                            request.radius(),
                            request.verticalScanRadius(),
                            request.consumeRadius(),
                            NeedsResourceCandidates.MAX_CANDIDATES
                    )
            );
            case NeedsResourceSearchCoordinator.RESOURCE_KIND_FOOD_CONTAINER -> FOOD_SEARCH_SERVICE.search(
                    store,
                    context.reference(),
                    new NeedsFoodTargetSearchService.FoodRequest(
                            context.originX(),
                            context.originY(),
                            context.originZ(),
                            request.radius(),
                            request.verticalScanRadius(),
                            request.consumeRadius(),
                            NeedsResourceCandidates.MAX_CANDIDATES,
                            request.itemIds()
                    )
            );
            default -> null;
        };
    }

    @Nullable
    private static SearchContext resolveFirstValidWaiter(
            @Nonnull Store<EntityStore> store,
            @Nonnull NeedsResourceSearchCoordinator.Request request,
            @Nonnull List<UUID> waiterIds) {
        if (store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return null;
        }
        for (UUID waiterId : waiterIds) {
            if (waiterId == null) {
                continue;
            }
            Ref<EntityStore> reference = world.getEntityRef(waiterId);
            if (reference == null || !reference.isValid() || reference.getStore() != store) {
                continue;
            }
            TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                continue;
            }
            double originX = transform.getPosition().x;
            double originY = transform.getPosition().y;
            double originZ = transform.getPosition().z;
            if (request.isInQueuedArea(originX, originY, originZ)) {
                return new SearchContext(reference, originX, originY, originZ);
            }
        }
        return null;
    }

    private record SearchContext(Ref<EntityStore> reference,
                                 double originX,
                                 double originY,
                                 double originZ) {
    }
}

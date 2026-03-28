package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates and counts owner population caps used by breeding and tame-acquisition gates.
 */
public final class OwnerPopulationCapService {

    private OwnerPopulationCapService() {
    }

    @Nonnull
    public static Decision evaluateAcquisition(@Nullable Store<EntityStore> store, @Nullable UUID ownerId) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        if (globalConfig == null) {
            globalConfig = TwGlobalConfig.defaultConfig();
        }
        return evaluateAcquisition(globalConfig, store, ownerId);
    }

    @Nonnull
    static Decision evaluateAcquisition(@Nullable TwGlobalConfig globalConfig,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable UUID ownerId) {
        if (ownerId == null) {
            return Decision.allowNoOwner();
        }
        TwGlobalConfig resolved = globalConfig == null ? TwGlobalConfig.defaultConfig() : globalConfig;
        int limit = resolved.getPopulationLimitPerPlayerOwnedTotal();
        if (limit <= 0) {
            return Decision.allowDisabled(resolved.getPopulationPerPlayerLimitScope());
        }
        TwGlobalConfig.PerPlayerLimitScope scope = resolved.getPopulationPerPlayerLimitScope();
        int currentCount = countOwnedPopulation(scope, store, ownerId);
        return evaluateResolved(limit, currentCount, scope);
    }

    @Nonnull
    public static Decision evaluateResolved(int perPlayerLimit,
                                            int currentCount,
                                            @Nullable TwGlobalConfig.PerPlayerLimitScope scope) {
        int safeLimit = Math.max(0, perPlayerLimit);
        TwGlobalConfig.PerPlayerLimitScope safeScope = scope == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : scope;
        if (safeLimit <= 0) {
            return Decision.allowDisabled(safeScope);
        }
        int safeCurrent = Math.max(0, currentCount);
        int remaining = safeLimit - safeCurrent;
        if (remaining <= 0) {
            return Decision.denyAtCap(safeLimit, safeCurrent, safeScope);
        }
        return Decision.allowWithCap(safeLimit, safeCurrent, remaining, safeScope);
    }

    public static int countOwnedPopulation(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull UUID ownerId) {
        if (scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL) {
            Universe universe = Universe.get();
            Map<String, World> worldsByName = universe != null ? universe.getWorlds() : null;
            if (worldsByName == null || worldsByName.isEmpty()) {
                PopulationSource fallbackSource = buildPopulationSource(store, ownerId);
                return fallbackSource == null
                        ? 0
                        : countOwnedPopulationAcrossSources(List.of(fallbackSource));
            }
            List<PopulationSource> populationSources = new ArrayList<>(worldsByName.size());
            for (World world : worldsByName.values()) {
                PopulationSource source = buildPopulationSource(world, ownerId);
                if (source != null) {
                    populationSources.add(source);
                }
            }
            return countOwnedPopulationAcrossSources(populationSources);
        }
        PopulationSource localSource = buildPopulationSource(store, ownerId);
        return localSource == null
                ? 0
                : countOwnedPopulationAcrossSources(List.of(localSource));
    }

    static int countOwnedPopulationAcrossSources(@Nonnull Collection<PopulationSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        int total = 0;
        List<CompletableFuture<Integer>> deferredCounts = new ArrayList<>();
        for (PopulationSource source : sources) {
            if (source == null) {
                continue;
            }
            if (source.isInCallingThread()) {
                total += Math.max(0, source.countDirect());
            } else {
                deferredCounts.add(source.countDeferred());
            }
        }
        for (CompletableFuture<Integer> deferredCount : deferredCounts) {
            if (deferredCount == null) {
                continue;
            }
            total += Math.max(0, deferredCount.join());
        }
        return Math.max(0, total);
    }

    interface PopulationSource {
        boolean isInCallingThread();

        int countDirect();

        @Nonnull
        CompletableFuture<Integer> countDeferred();
    }

    @Nullable
    private static PopulationSource buildPopulationSource(@Nullable Store<EntityStore> store, @Nonnull UUID ownerId) {
        if (store == null) {
            return null;
        }
        World world = resolveWorld(store);
        if (world == null) {
            return new PopulationSource() {
                @Override
                public boolean isInCallingThread() {
                    return true;
                }

                @Override
                public int countDirect() {
                    return countOwnedPopulationInStore(store, ownerId);
                }

                @Nonnull
                @Override
                public CompletableFuture<Integer> countDeferred() {
                    return CompletableFuture.completedFuture(countOwnedPopulationInStore(store, ownerId));
                }
            };
        }
        return buildPopulationSource(world, ownerId);
    }

    @Nullable
    private static PopulationSource buildPopulationSource(@Nullable World world, @Nonnull UUID ownerId) {
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> worldStore = world.getEntityStore().getStore();
        if (worldStore == null) {
            return null;
        }
        return new PopulationSource() {
            @Override
            public boolean isInCallingThread() {
                return worldStore.isInThread();
            }

            @Override
            public int countDirect() {
                return countOwnedPopulationInStore(worldStore, ownerId);
            }

            @Nonnull
            @Override
            public CompletableFuture<Integer> countDeferred() {
                if (!world.isAlive() || !worldStore.isAliveInDifferentThread()) {
                    return CompletableFuture.completedFuture(0);
                }
                CompletableFuture<Integer> deferred = new CompletableFuture<>();
                try {
                    world.execute(() -> {
                        try {
                            deferred.complete(countOwnedPopulationInStore(worldStore, ownerId));
                        } catch (Throwable throwable) {
                            deferred.completeExceptionally(throwable);
                        }
                    });
                } catch (Throwable throwable) {
                    deferred.completeExceptionally(throwable);
                }
                return deferred;
            }
        };
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        EntityStore entityStore = store.getExternalData();
        return entityStore != null ? entityStore.getWorld() : null;
    }

    private static int countOwnedPopulationInStore(@Nullable Store<EntityStore> store, @Nonnull UUID ownerId) {
        if (store == null) {
            return 0;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (npcType == null || ownerType == null) {
            return 0;
        }
        int[] count = new int[] {0};
        store.forEachChunk(
                Query.and(npcType, ownerType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        TameworkOwnerComponent owner = chunk.getComponent(i, ownerType);
                        if (owner != null && ownerId.equals(owner.getOwnerId())) {
                            count[0]++;
                        }
                    }
                }
        );
        return Math.max(0, count[0]);
    }

    public record Decision(boolean allowed,
                           boolean capEnabled,
                           int limit,
                           int currentCount,
                           int remainingHeadroom,
                           TwGlobalConfig.PerPlayerLimitScope scope,
                           @Nonnull String reason) {
        @Nonnull
        static Decision allowNoOwner() {
            return new Decision(
                    true,
                    false,
                    0,
                    0,
                    Integer.MAX_VALUE,
                    TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                    "owner-cap-no-owner"
            );
        }

        @Nonnull
        static Decision allowDisabled(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return new Decision(true, false, 0, 0, Integer.MAX_VALUE, scope, "owner-cap-disabled");
        }

        @Nonnull
        static Decision allowWithCap(int limit,
                                     int currentCount,
                                     int remainingHeadroom,
                                     @Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return new Decision(
                    true,
                    true,
                    Math.max(0, limit),
                    Math.max(0, currentCount),
                    Math.max(0, remainingHeadroom),
                    scope,
                    "owner-cap-allow"
            );
        }

        @Nonnull
        static Decision denyAtCap(int limit,
                                  int currentCount,
                                  @Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return new Decision(
                    false,
                    true,
                    Math.max(0, limit),
                    Math.max(0, currentCount),
                    0,
                    scope,
                    "owner-cap-reached"
            );
        }
    }
}

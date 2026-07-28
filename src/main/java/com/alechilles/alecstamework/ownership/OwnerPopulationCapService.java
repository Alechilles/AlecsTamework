package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates the released owner cap against currently loaded NPCs.
 *
 * <p>The live index is process-local and updated by world ECS callbacks. Reads
 * never enter another world thread, block on futures, or create durable
 * reservations.
 */
public final class OwnerPopulationCapService {
    private OwnerPopulationCapService() {
    }

    @Nonnull
    public static Decision evaluateAcquisition(@Nullable Store<EntityStore> store,
                                               @Nullable UUID ownerId) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return evaluateAcquisition(
                globalConfig == null ? TwGlobalConfig.defaultConfig() : globalConfig,
                store,
                ownerId
        );
    }

    @Nonnull
    static Decision evaluateAcquisition(@Nullable TwGlobalConfig globalConfig,
                                        @Nullable Store<EntityStore> store,
                                        @Nullable UUID ownerId) {
        return evaluateAcquisition(
                globalConfig,
                resolveIndex(),
                resolveWorldName(store),
                ownerId
        );
    }

    @Nonnull
    static Decision evaluateAcquisition(
            @Nullable TwGlobalConfig globalConfig,
            @Nullable OwnerPopulationLiveIndex index,
            @Nullable String worldName,
            @Nullable UUID ownerId
    ) {
        if (ownerId == null) {
            return Decision.allowNoOwner();
        }
        TwGlobalConfig resolved = globalConfig == null
                ? TwGlobalConfig.defaultConfig()
                : globalConfig;
        int limit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                resolved.getPopulationLimitPerPlayerOwnedTotal()
        );
        TwGlobalConfig.PerPlayerLimitScope scope =
                TameworkRuntimeSettings.populationPerPlayerLimitScope(
                        resolved.getPopulationPerPlayerLimitScope()
                );
        int current = countOwnedPopulation(index, scope, worldName, ownerId);
        if (limit <= 0) {
            return Decision.allowDisabled(scope, current);
        }
        if (index == null) {
            return Decision.denyUnavailable(
                    limit,
                    scope,
                    "owner-population-live-index-unavailable"
            );
        }
        if (current < 0) {
            return Decision.denyUnavailable(
                    limit,
                    scope,
                    "owner-population-world-context-unavailable"
            );
        }
        return evaluateResolved(limit, current, scope);
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
            return Decision.allowDisabled(safeScope, Math.max(0, currentCount));
        }
        int safeCurrent = Math.max(0, currentCount);
        int remaining = safeLimit - safeCurrent;
        return remaining <= 0
                ? Decision.denyAtCap(safeLimit, safeCurrent, safeScope)
                : Decision.allowWithCap(safeLimit, safeCurrent, remaining, safeScope);
    }

    public static int countOwnedPopulation(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull UUID ownerId) {
        return countOwnedPopulation(
                resolveIndex(),
                scope,
                resolveWorldName(store),
                ownerId
        );
    }

    static int countOwnedPopulation(@Nullable OwnerPopulationLiveIndex index,
                                    @Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                    @Nullable String worldName,
                                    @Nonnull UUID ownerId) {
        return index == null
                ? 0
                : index.count(
                        ownerId,
                        scope,
                        worldName
                );
    }

    @Nullable
    private static OwnerPopulationLiveIndex resolveIndex() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerPopulationLiveIndex();
    }

    @Nullable
    private static String resolveWorldName(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getName() == null || world.getName().isBlank()) {
            return null;
        }
        return world.getName().trim();
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
                    true, false, 0, 0, Integer.MAX_VALUE,
                    TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, "owner-cap-no-owner"
            );
        }

        @Nonnull
        static Decision allowDisabled(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return allowDisabled(scope, 0);
        }

        @Nonnull
        static Decision allowDisabled(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                      int currentCount) {
            return new Decision(
                    true, false, 0, Math.max(0, currentCount), Integer.MAX_VALUE,
                    scope, "owner-cap-disabled"
            );
        }

        @Nonnull
        static Decision allowWithCap(int limit,
                                     int currentCount,
                                     int remainingHeadroom,
                                     @Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return new Decision(
                    true, true, Math.max(0, limit), Math.max(0, currentCount),
                    Math.max(0, remainingHeadroom), scope, "owner-cap-allow"
            );
        }

        @Nonnull
        static Decision denyAtCap(int limit,
                                  int currentCount,
                                  @Nonnull TwGlobalConfig.PerPlayerLimitScope scope) {
            return new Decision(
                    false, true, Math.max(0, limit), Math.max(0, currentCount),
                    0, scope, "owner-cap-reached"
            );
        }

        @Nonnull
        static Decision denyUnavailable(int limit,
                                        @Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                        @Nonnull String reason) {
            return new Decision(
                    false, true, Math.max(0, limit), -1, 0, scope, reason
            );
        }
    }
}

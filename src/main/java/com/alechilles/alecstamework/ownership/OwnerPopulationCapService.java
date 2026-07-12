package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility preflight facade over the authoritative owner population index.
 *
 * <p>This class never scans ECS stores, enumerates worlds, schedules foreign-world work, or waits
 * on a future. Mutation callers must use {@link OwnerPopulationAdmissionCoordinator}; this facade
 * remains informational for legacy callers while they migrate.
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
        if (ownerId == null) {
            return Decision.allowNoOwner();
        }
        TwGlobalConfig resolved = globalConfig == null ? TwGlobalConfig.defaultConfig() : globalConfig;
        int limit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                resolved.getPopulationLimitPerPlayerOwnedTotal()
        );
        TwGlobalConfig.PerPlayerLimitScope configuredScope =
                TameworkRuntimeSettings.populationPerPlayerLimitScope(
                        resolved.getPopulationPerPlayerLimitScope()
                );
        OwnerPopulationLimitScope scope = toIndexScope(configuredScope);
        String worldName = resolveWorldName(store);
        OwnerPopulationIndex index = resolveIndex();
        if (index == null) {
            return limit <= 0
                    ? Decision.allowDisabled(configuredScope)
                    : Decision.denyUnavailable(limit, configuredScope, "owner-population-index-unavailable");
        }

        OwnerPopulationCounts counts = index.counts(ownerId, worldName);
        long committed = scope == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalCommitted()
                : counts.worldCommitted();
        long pending = scope == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalPending()
                : counts.worldPending();
        int current = saturatingInt(committed + pending);
        if (limit <= 0) {
            return Decision.allowDisabled(configuredScope, current);
        }
        if (scope == OwnerPopulationLimitScope.PER_WORLD && worldName == null) {
            return Decision.denyUnavailable(limit, configuredScope, "owner-cap-world-context-required");
        }
        OwnerPopulationReadiness readiness = index.readiness(scope);
        if (!readiness.allowsPositiveCappedAdmissions()) {
            return Decision.denyUnavailable(
                    limit,
                    configuredScope,
                    "owner-population-" + readiness.name().toLowerCase(java.util.Locale.ROOT)
            );
        }
        return evaluateResolved(limit, current, configuredScope);
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

    /**
     * Legacy count read backed only by the index. Unready state returns a conservative sentinel.
     */
    public static int countOwnedPopulation(@Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull UUID ownerId) {
        OwnerPopulationIndex index = resolveIndex();
        OwnerPopulationLimitScope indexScope = toIndexScope(scope);
        String worldName = resolveWorldName(store);
        return countOwnedPopulation(index, indexScope, worldName, ownerId);
    }

    static int countOwnedPopulation(@Nullable OwnerPopulationIndex index,
                                    @Nonnull OwnerPopulationLimitScope scope,
                                    @Nullable String worldName,
                                    @Nonnull UUID ownerId) {
        if (scope == OwnerPopulationLimitScope.PER_WORLD && worldName == null) {
            return Integer.MAX_VALUE;
        }
        if (index == null || !index.readiness(scope).allowsPositiveCappedAdmissions()) {
            return Integer.MAX_VALUE;
        }
        OwnerPopulationCounts counts = index.counts(ownerId, worldName);
        return saturatingInt(scope == OwnerPopulationLimitScope.GLOBAL
                ? counts.globalCommitted() + counts.globalPending()
                : counts.worldCommitted() + counts.worldPending());
    }

    @Nullable
    private static OwnerPopulationIndex resolveIndex() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerPopulationIndex();
    }

    @Nonnull
    private static OwnerPopulationLimitScope toIndexScope(
            @Nullable TwGlobalConfig.PerPlayerLimitScope scope
    ) {
        return scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                ? OwnerPopulationLimitScope.GLOBAL
                : OwnerPopulationLimitScope.PER_WORLD;
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

    private static int saturatingInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
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

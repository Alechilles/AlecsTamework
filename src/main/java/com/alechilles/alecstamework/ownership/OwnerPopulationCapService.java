package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pre-checks the owner cap for the tame, set-owner and spawn sites against the owner's records in
 * the companion index: every owned companion counts, loaded or not. The binding check is
 * {@link com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate} under the index
 * lock; this one only refuses early with a message.
 *
 * <p>Reads are in-memory index reads. They never enter another world thread, block on futures,
 * or create durable reservations.
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
                resolveQueries(),
                resolveWorldName(store),
                ownerId
        );
    }

    @Nonnull
    static Decision evaluateAcquisition(
            @Nullable TwGlobalConfig globalConfig,
            @Nullable CompanionQueries index,
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
                    "owner-population-index-unavailable"
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
                resolveQueries(),
                scope,
                resolveWorldName(store),
                ownerId
        );
    }

    /**
     * Counts the owner's owned companions in scope; per world, a companion counts in the world it
     * is in, else the world it was tamed in. Returns 0 without an index and -1 when a per-world
     * count has no world.
     */
    static int countOwnedPopulation(@Nullable CompanionQueries index,
                                    @Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                                    @Nullable String worldName,
                                    @Nonnull UUID ownerId) {
        if (index == null) {
            return 0;
        }
        boolean global = scope == TwGlobalConfig.PerPlayerLimitScope.GLOBAL;
        if (!global && worldName == null) {
            return -1;
        }
        int count = 0;
        for (CompanionRecord record : index.owned(ownerId)) {
            if (global || CompanionAdmission.scopeWorld(record).equals(worldName)) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    private static CompanionQueries resolveQueries() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getCompanionQueries();
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

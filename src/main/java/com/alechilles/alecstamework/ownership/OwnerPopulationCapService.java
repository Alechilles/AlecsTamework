package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pre-checks the owner cap, and with a role id the population-group caps, for the tame, set-owner
 * and spawn sites against the owner's records in the companion index: every owned companion
 * counts, loaded or not. The binding check is {@link CompanionAdmissionGate#refuse} under the
 * index lock; this one only refuses early with a message, before food is spent or effects play.
 *
 * <p>Reads are in-memory index reads. They never enter another world thread, block on futures,
 * or create durable reservations.
 */
public final class OwnerPopulationCapService {
    /** Reason of a {@link Decision} refused by a population-group cap. */
    public static final String REASON_GROUP_CAP = "owner-group-cap-reached";

    private static volatile CompanionAdmissionGate admissionGate;

    private OwnerPopulationCapService() {
    }

    /**
     * Installs the gate the role-aware pre-check uses, or removes it with null. Without a gate
     * the group caps are not pre-checked; the gate still enforces them under the index lock.
     */
    public static void useAdmissionGate(@Nullable CompanionAdmissionGate gate) {
        admissionGate = gate;
    }

    /**
     * The owner cap and the population-group caps for a new companion of {@code roleId} in the
     * store's world, in one admission pre-check. An owned refusal has reason
     * {@code owner-cap-reached} and a group refusal {@link #REASON_GROUP_CAP}. This check does not
     * count the owner's companions, so {@code currentCount} is -1. Without a gate, owner or role
     * it falls back to {@link #evaluateAcquisition(Store, UUID)}, the owner cap only.
     */
    @Nonnull
    public static Decision evaluateAcquisition(@Nullable Store<EntityStore> store,
                                               @Nullable UUID ownerId,
                                               @Nullable String roleId) {
        CompanionAdmissionGate gate = admissionGate;
        if (gate == null || ownerId == null || roleId == null || roleId.isBlank()) {
            return evaluateAcquisition(store, ownerId);
        }
        return fromPrecheck(gate.precheck(ownerId, roleId, resolveWorldName(store)), gate.rules());
    }

    /** Maps an admission pre-check result to a {@link Decision}; the owner's companions are not counted. */
    @Nonnull
    static Decision fromPrecheck(@Nullable CompanionAdmission.Refusal refusal,
                                 @Nonnull CompanionAdmission.Rules rules) {
        int limit = rules.ownedLimit();
        TwGlobalConfig.PerPlayerLimitScope scope = rules.ownedPerWorld()
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : TwGlobalConfig.PerPlayerLimitScope.GLOBAL;
        if (refusal == null) {
            return limit <= 0
                    ? new Decision(true, false, 0, -1, Integer.MAX_VALUE, scope, "owner-cap-disabled")
                    : new Decision(true, true, limit, -1, 1, scope, "owner-cap-allow");
        }
        return new Decision(false, true, limit, -1, 0, scope,
                refusal == CompanionAdmission.Refusal.OWNED ? "owner-cap-reached" : REASON_GROUP_CAP);
    }

    /**
     * Pre-checks a whole litter (spec 8.11): would adding {@code candidates} to {@code ownerId}'s
     * companions pass the owned limit or a group limit? A refusal has reason
     * {@code owner-cap-reached} or {@link #REASON_GROUP_CAP}, as for {@link #evaluateAcquisition}.
     * Allowed when there is no owner, no candidate, no gate or no index. Like the other pre-checks
     * it is lock-free; each child's tame stamping still re-checks under the index lock.
     */
    @Nonnull
    public static Decision evaluateBatch(@Nullable UUID ownerId, @Nonnull List<CompanionRecord> candidates) {
        CompanionAdmissionGate gate = admissionGate;
        CompanionQueries index = resolveQueries();
        if (gate == null || index == null || ownerId == null || candidates.isEmpty()) {
            return Decision.allowBatch();
        }
        return evaluateBatch(index.owned(ownerId), candidates, gate.rules());
    }

    @Nonnull
    static Decision evaluateBatch(@Nonnull Collection<CompanionRecord> ownerRecords,
                                  @Nonnull List<CompanionRecord> candidates,
                                  @Nonnull CompanionAdmission.Rules rules) {
        CompanionAdmission.Refusal refusal = CompanionAdmission.checkBatch(ownerRecords, candidates, rules);
        if (refusal == null) {
            return Decision.allowBatch();
        }
        TwGlobalConfig.PerPlayerLimitScope scope = rules.ownedPerWorld()
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : TwGlobalConfig.PerPlayerLimitScope.GLOBAL;
        return refusal == CompanionAdmission.Refusal.OWNED
                ? Decision.denyAtCap(rules.ownedLimit(), ownerRecords.size(), scope)
                : new Decision(false, true, rules.ownedLimit(), ownerRecords.size(), 0, scope, REASON_GROUP_CAP);
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
        static Decision allowBatch() {
            return new Decision(
                    true, false, 0, 0, Integer.MAX_VALUE,
                    TwGlobalConfig.PerPlayerLimitScope.PER_WORLD, "owner-batch-allow"
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

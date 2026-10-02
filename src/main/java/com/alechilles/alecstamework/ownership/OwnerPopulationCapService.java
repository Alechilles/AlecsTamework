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
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pre-checks the owner caps, and with a role id the population-group caps, for the tame, set-owner
 * and spawn sites against the owner's records in the companion index. The owned limit counts
 * every owned companion; the deployed limit counts the ones out in the world, loaded or not. A
 * tame or a tamed spawn makes a companion that is both, so with a role id both limits apply. The binding check is {@link CompanionAdmissionGate#admit} under the
 * index lock; this one only refuses early with a message, before food is spent or effects play.
 *
 * <p>Reads are in-memory index reads. They never enter another world thread, block on futures,
 * or create durable reservations. A role managed by an admission provider is checked against the
 * provider's cached decision; while that decision is being fetched the acquisition is refused
 * with the "checking requirements" message.
 */
public final class OwnerPopulationCapService {
    /** Reason of a {@link Decision} refused by the per-player deployed limit. */
    public static final String REASON_DEPLOYED_CAP = "owner-deployed-cap-reached";
    /** Reason of a {@link Decision} refused by a population-group cap. */
    public static final String REASON_GROUP_CAP = "owner-group-cap-reached";
    /** Reason of a {@link Decision} refused by an admission provider or one of its domain limits. */
    public static final String REASON_PROVIDER_DENIED = "owner-provider-denied";
    /** Reason of a {@link Decision} refused because the admission provider gave no decision (yet). */
    public static final String REASON_PROVIDER_UNAVAILABLE = "owner-provider-unavailable";

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
     * The owned and deployed caps and the population-group caps for a new companion of
     * {@code roleId} out in the store's world, in one admission pre-check. An owned refusal has
     * reason {@code owner-cap-reached}, a deployed refusal {@link #REASON_DEPLOYED_CAP} (its
     * {@code limit} is the deployed limit), a group refusal {@link #REASON_GROUP_CAP} and a provider refusal
     * {@link #REASON_PROVIDER_DENIED} or {@link #REASON_PROVIDER_UNAVAILABLE}; a refused decision
     * carries the message key to show. This check does not count the owner's companions, so
     * {@code currentCount} is -1. Without a gate, owner or role it falls back to
     * {@link #evaluateAcquisition(Store, UUID)}, the owned cap only; the gate still enforces the
     * deployed cap under the index lock.
     */
    @Nonnull
    public static Decision evaluateAcquisition(@Nullable Store<EntityStore> store,
                                               @Nullable UUID ownerId,
                                               @Nullable String roleId) {
        CompanionAdmissionGate gate = admissionGate;
        if (gate == null || ownerId == null || roleId == null || roleId.isBlank()) {
            return evaluateAcquisition(store, ownerId);
        }
        String world = resolveWorldName(store);
        return fromPrecheck(gate.precheckDenial(ownerId, roleId, world, world != null), gate.rules());
    }

    /** Maps an admission pre-check result to a {@link Decision}; the owner's companions are not counted. */
    @Nonnull
    static Decision fromPrecheck(@Nullable CompanionAdmissionGate.Denial denial,
                                 @Nonnull CompanionAdmission.Rules rules) {
        int limit = rules.ownedLimit();
        if (denial == null) {
            return limit <= 0
                    ? new Decision(true, false, 0, -1, Integer.MAX_VALUE, scope(rules), "owner-cap-disabled")
                    : new Decision(true, true, limit, -1, 1, scope(rules), "owner-cap-allow");
        }
        return refused(denial, rules, -1);
    }

    /** A refused decision with the reason and message key of {@code denial}. */
    @Nonnull
    static Decision refused(@Nonnull CompanionAdmissionGate.Denial denial, @Nonnull CompanionAdmission.Rules rules,
                            int currentCount) {
        String reason = switch (denial.refusal()) {
            case OWNED -> "owner-cap-reached";
            case DEPLOYED -> REASON_DEPLOYED_CAP;
            case GROUP_OWNED, GROUP_DEPLOYED -> REASON_GROUP_CAP;
            case PROVIDER_DENIED -> REASON_PROVIDER_DENIED;
            case PROVIDER_UNAVAILABLE -> REASON_PROVIDER_UNAVAILABLE;
        };
        int limit = denial.refusal() == CompanionAdmission.Refusal.DEPLOYED
                ? rules.deployedLimit() : rules.ownedLimit();
        return new Decision(false, true, limit, currentCount, 0, scope(rules), reason, denial.messageKey());
    }

    private static TwGlobalConfig.PerPlayerLimitScope scope(CompanionAdmission.Rules rules) {
        return rules.perWorld()
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : TwGlobalConfig.PerPlayerLimitScope.GLOBAL;
    }

    /**
     * Pre-checks a whole litter (spec 8.11): would adding {@code candidates} to {@code ownerId}'s
     * companions pass the owned limit, the deployed limit, a group limit or, for a managed role,
     * the cached provider
     * decision and its domain limits? A refusal has the reasons of {@link #evaluateAcquisition}.
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
        CompanionAdmissionGate.Denial denial = gate.denyBatch(ownerId, candidates);
        return denial == null ? Decision.allowBatch() : refused(denial, gate.rules(), index.owned(ownerId).size());
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
    public static Decision evaluateAcquisition(
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

    /**
     * {@code messageKey} is the translation key to show for a refusal that names one (a provider's
     * own key, a domain limit, or "checking requirements"); null means the message of
     * {@code reason}.
     */
    public record Decision(boolean allowed,
                           boolean capEnabled,
                           int limit,
                           int currentCount,
                           int remainingHeadroom,
                           TwGlobalConfig.PerPlayerLimitScope scope,
                           @Nonnull String reason,
                           @Nullable String messageKey) {
        public Decision(boolean allowed, boolean capEnabled, int limit, int currentCount, int remainingHeadroom,
                        TwGlobalConfig.PerPlayerLimitScope scope, @Nonnull String reason) {
            this(allowed, capEnabled, limit, currentCount, remainingHeadroom, scope, reason, null);
        }

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

package com.alechilles.alecstamework.damage;

import com.alechilles.alecstamework.api.CombatContributionView;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded world-thread state for recent companion defeat contributions. */
public final class CompanionCombatContributionLedger {
    public static final int DEFAULT_MAX_TARGETS = 2_048;
    public static final int DEFAULT_MAX_CONTRIBUTORS = 32;
    public static final long DEFAULT_EXPIRY_MS = 30_000L;

    private final int maxTargets;
    private final long expiryMs;
    private final int maxContributors;
    private final LinkedHashMap<UUID, TargetEntry> targets =
            new LinkedHashMap<>();

    public CompanionCombatContributionLedger() {
        this(DEFAULT_MAX_TARGETS, DEFAULT_EXPIRY_MS,
                DEFAULT_MAX_CONTRIBUTORS);
    }

    CompanionCombatContributionLedger(
            int maxTargets,
            long expiryMs,
            int maxContributors
    ) {
        if (maxTargets < 1 || expiryMs < 1L || maxContributors < 1
                || maxContributors > DEFAULT_MAX_CONTRIBUTORS) {
            throw new IllegalArgumentException(
                    "Combat contribution bounds are invalid");
        }
        this.maxTargets = maxTargets;
        this.expiryMs = expiryMs;
        this.maxContributors = maxContributors;
    }

    /** Records one eligible companion's final positive damage. */
    public void record(
            @Nonnull UUID operationId,
            @Nonnull UUID targetId,
            @Nonnull UUID companionId,
            @Nullable UUID ownerId,
            double finalDamage,
            long occurredAtMs
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(companionId, "companionId");
        if (!Double.isFinite(finalDamage) || finalDamage <= 0.0) {
            return;
        }
        expire(occurredAtMs);
        TargetEntry target = targets.remove(targetId);
        if (target == null) {
            target = new TargetEntry();
        }
        target.record(
                operationId, companionId, ownerId,
                finalDamage, occurredAtMs, maxContributors);
        targets.put(targetId, target);
        while (targets.size() > maxTargets) {
            Iterator<UUID> iterator = targets.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /** Removes and returns recent defeat credit for one confirmed target. */
    @Nonnull
    public Optional<DefeatCredit> remove(
            @Nonnull UUID targetId,
            @Nullable UUID finalCompanionId,
            long occurredAtMs
    ) {
        Objects.requireNonNull(targetId, "targetId");
        expire(occurredAtMs);
        TargetEntry target = targets.remove(targetId);
        return target == null
                ? Optional.empty()
                : Optional.of(target.toCredit(finalCompanionId));
    }

    /** Clears this world's ledger. */
    public void clear() {
        targets.clear();
    }

    int size() {
        return targets.size();
    }

    private void expire(long occurredAtMs) {
        Iterator<Map.Entry<UUID, TargetEntry>> iterator =
                targets.entrySet().iterator();
        while (iterator.hasNext()) {
            TargetEntry target = iterator.next().getValue();
            if (elapsed(occurredAtMs, target.lastOccurredAtMs) >= expiryMs) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private static long elapsed(long current, long previous) {
        if (current <= previous) {
            return 0L;
        }
        try {
            return Math.subtractExact(current, previous);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** Immutable credit removed at confirmed death. */
    public record DefeatCredit(
            @Nonnull UUID operationId,
            @Nullable CombatContributionView finalBlowCredit,
            @Nonnull List<CombatContributionView> contributors,
            @Nullable UUID ownerCredit
    ) {
        public DefeatCredit {
            Objects.requireNonNull(operationId, "operationId");
            contributors = List.copyOf(
                    Objects.requireNonNull(contributors, "contributors"));
        }
    }

    private static final class TargetEntry {
        private final LinkedHashMap<UUID, MutableContribution> contributors =
                new LinkedHashMap<>();
        private UUID operationId;
        private long lastOccurredAtMs;

        private void record(
                UUID nextOperationId,
                UUID companionId,
                UUID ownerId,
                double damage,
                long occurredAtMs,
                int maxContributors
        ) {
            MutableContribution contribution = contributors.remove(companionId);
            if (contribution == null) {
                contribution = new MutableContribution(companionId);
            }
            contribution.ownerId = ownerId;
            contribution.damage += damage;
            contributors.put(companionId, contribution);
            while (contributors.size() > maxContributors) {
                Iterator<UUID> iterator = contributors.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
            operationId = nextOperationId;
            lastOccurredAtMs = occurredAtMs;
        }

        private DefeatCredit toCredit(@Nullable UUID finalCompanionId) {
            ArrayList<CombatContributionView> views =
                    new ArrayList<>(contributors.size());
            CombatContributionView finalBlow = null;
            for (MutableContribution contribution : contributors.values()) {
                CombatContributionView view = contribution.toView();
                views.add(view);
                if (contribution.companionId.equals(finalCompanionId)) {
                    finalBlow = view;
                }
            }
            return new DefeatCredit(
                    operationId,
                    finalBlow,
                    views,
                    finalBlow == null ? null : finalBlow.ownerId());
        }
    }

    private static final class MutableContribution {
        private final UUID companionId;
        private UUID ownerId;
        private double damage;

        private MutableContribution(UUID companionId) {
            this.companionId = companionId;
        }

        private CombatContributionView toView() {
            return new CombatContributionView(
                    companionId, ownerId, damage);
        }
    }
}

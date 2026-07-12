package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationCounts;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Authorizes externally observed owner-component removals and reports conservative runtime
 * adoptions without making warning delivery part of reconciliation correctness.
 */
final class CompanionPopulationObservationPolicy {
    static final long WARNING_INTERVAL_MS = 60_000L;

    private final OwnerPopulationIndex ownerIndex;
    private final PerWorldLimitResolver limitResolver;
    private final LongSupplier currentTimeMillis;
    private final ConcurrentHashMap<String, Long> lastWarningByKey = new ConcurrentHashMap<>();
    private final AtomicLong unavoidablePerWorldOverCapRelocations = new AtomicLong();
    private volatile Consumer<String> warningSink = message -> { };

    CompanionPopulationObservationPolicy(@Nonnull OwnerPopulationIndex ownerIndex) {
        this(ownerIndex, CompanionPopulationObservationPolicy::configuredLimit,
                System::currentTimeMillis);
    }

    CompanionPopulationObservationPolicy(@Nonnull OwnerPopulationIndex ownerIndex,
                                         @Nonnull PerWorldLimitResolver limitResolver,
                                         @Nonnull LongSupplier currentTimeMillis) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.limitResolver = Objects.requireNonNull(limitResolver, "limitResolver");
        this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
    }

    void setWarningSink(@Nullable Consumer<String> warningSink) {
        this.warningSink = warningSink == null ? message -> { } : warningSink;
    }

    @Nonnull
    RemovalDisposition authorizeRemoval(@Nonnull String profileId,
                                        @Nullable UUID removedOwner,
                                        @Nullable OwnerPopulationEntry current) {
        if (ownerIndex.hasApplyingOwnerClearTransition(profileId, removedOwner)) {
            return RemovalDisposition.SUPPRESSED_IN_FLIGHT;
        }
        if (current == null) {
            return RemovalDisposition.CONTINUE;
        }
        if (current.ownerId() == null
                && current.lifecycleState() == CompanionLifecycleState.RELEASED) {
            return RemovalDisposition.AUTHORIZED_RELEASE;
        }
        return current.ownerId() == null
                ? RemovalDisposition.CONTINUE
                : RemovalDisposition.REJECTED_UNJOURNALED_CLEAR;
    }

    @Nullable
    WarningEvent changedEntry(@Nullable OwnerPopulationEntry current,
                              @Nullable UUID observedOwner,
                              @Nullable String observedWorld) {
        if (current == null || (Objects.equals(current.ownerId(), observedOwner)
                && Objects.equals(current.ownershipWorldName(), observedWorld))) {
            return null;
        }
        return new WarningEvent(current, observedOwner, observedWorld);
    }

    @Nonnull
    WarningEvent rejectedRemoval(@Nonnull OwnerPopulationEntry current,
                                 @Nullable String observedWorld) {
        return new WarningEvent(current, null, observedWorld);
    }

    void warn(@Nonnull WarningEvent event) {
        try {
            if (!Objects.equals(event.current().ownerId(), event.observedOwner())) {
                warnDirectOwnerMutation(event);
                return;
            }
            warnUnavoidableOverCapRelocation(event);
        } catch (RuntimeException | LinkageError ignored) {
            // Observability cannot prevent durable observation scheduling or live-state adoption.
        }
    }

    long unavoidablePerWorldOverCapRelocations() {
        return unavoidablePerWorldOverCapRelocations.get();
    }

    private void warnDirectOwnerMutation(WarningEvent event) {
        OwnerPopulationEntry current = event.current();
        warnThrottled(
                "owner:" + current.profileId(),
                "Observed direct owner-component mutation for profile=" + current.profileId()
                        + " oldOwner=" + current.ownerId()
                        + " newOwner=" + event.observedOwner()
                        + "; adopted conservatively without deleting or moving the companion."
        );
    }

    private void warnUnavoidableOverCapRelocation(WarningEvent event) {
        OwnerPopulationEntry current = event.current();
        UUID owner = current.ownerId();
        String sourceWorld = current.ownershipWorldName();
        String destinationWorld = event.observedWorld();
        if (owner == null || sourceWorld == null || destinationWorld == null
                || sourceWorld.equals(destinationWorld)) {
            return;
        }
        PerWorldLimit limit = safeLimit();
        if (!limit.enabled() || limit.limit() <= 0) {
            return;
        }
        OwnerPopulationCounts counts = ownerIndex.counts(owner, destinationWorld);
        long destinationCount = counts.worldCommitted() + counts.worldPending();
        if (destinationCount <= limit.limit()) {
            return;
        }
        unavoidablePerWorldOverCapRelocations.incrementAndGet();
        warnThrottled(
                "world-over-cap:" + owner + ":" + destinationWorld,
                "Unavoidable companion relocation created a per-world owner over-cap condition"
                        + " for owner=" + owner
                        + " world=" + destinationWorld
                        + " profile=" + current.profileId()
                        + " count=" + destinationCount
                        + " limit=" + limit.limit()
                        + "; preserved the companion and blocked later positive admissions."
        );
    }

    @Nonnull
    private PerWorldLimit safeLimit() {
        try {
            PerWorldLimit limit = limitResolver.resolve();
            return limit == null ? PerWorldLimit.DISABLED : limit;
        } catch (RuntimeException | LinkageError ignored) {
            return PerWorldLimit.DISABLED;
        }
    }

    private void warnThrottled(@Nonnull String key, @Nonnull String message) {
        long now = currentTimeMillis.getAsLong();
        Long previous = lastWarningByKey.put(key, now);
        if (previous != null && now - previous < WARNING_INTERVAL_MS) {
            return;
        }
        warningSink.accept(message);
    }

    @Nonnull
    private static PerWorldLimit configuredLimit() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        if (config == null) {
            config = TwGlobalConfig.defaultConfig();
        }
        int limit = TameworkRuntimeSettings.populationLimitPerPlayerOwnedTotal(
                config.getPopulationLimitPerPlayerOwnedTotal()
        );
        TwGlobalConfig.PerPlayerLimitScope scope =
                TameworkRuntimeSettings.populationPerPlayerLimitScope(
                        config.getPopulationPerPlayerLimitScope()
                );
        return new PerWorldLimit(
                scope == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                Math.max(0, limit)
        );
    }

    enum RemovalDisposition {
        CONTINUE,
        SUPPRESSED_IN_FLIGHT,
        AUTHORIZED_RELEASE,
        REJECTED_UNJOURNALED_CLEAR
    }

    record WarningEvent(@Nonnull OwnerPopulationEntry current,
                        @Nullable UUID observedOwner,
                        @Nullable String observedWorld) {
    }

    record PerWorldLimit(boolean enabled, int limit) {
        private static final PerWorldLimit DISABLED = new PerWorldLimit(false, 0);

        PerWorldLimit {
            if (limit < 0) {
                throw new IllegalArgumentException("Per-world limit cannot be negative.");
            }
        }
    }

    @FunctionalInterface
    interface PerWorldLimitResolver {
        @Nonnull PerWorldLimit resolve();
    }
}

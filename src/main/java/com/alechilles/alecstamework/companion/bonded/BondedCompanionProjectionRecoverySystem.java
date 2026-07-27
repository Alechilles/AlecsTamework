package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Bounded maintenance entry point that reconciles leased bonded projections
 * which are missing or duplicated outside explicit lifecycle events.
 */
public final class BondedCompanionProjectionRecoverySystem {
    private final BondedCompanionWorldLifecycleObserver observer;
    private final ActiveLeaseSource leases;
    private final int maximumLeases;

    public BondedCompanionProjectionRecoverySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ActiveLeaseSource leases,
            int maximumLeases
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (maximumLeases < 1) {
            throw new IllegalArgumentException("maximumLeases must be positive");
        }
        this.maximumLeases = maximumLeases;
    }

    /** Reconciles at most the configured number of exact active leases. */
    public int tick(long observedAtMs) {
        List<BondedCompanionProjectionValidator.LeaseExpectation> active =
                List.copyOf(Objects.requireNonNull(
                        leases.activeLeases(maximumLeases), "activeLeases"
                ).stream().filter(lease -> lease.phase()
                        == BondedCompanionProjectionValidator.LeasePhase.LIVE
                ).toList());
        if (active.isEmpty()) return 0;
        observer.onProjectionMissingScan(active, observedAtMs);
        return active.size();
    }

    /** Bounded durable lease read; callers must not enumerate players. */
    @FunctionalInterface
    public interface ActiveLeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        activeLeases(int maximumLeases);
    }
}

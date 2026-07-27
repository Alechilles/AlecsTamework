package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Finite startup recovery cursor for interrupted PENDING summons. The immutable cutoff keeps
 * newly submitted summons out of the pass while each later maintenance tick advances one page.
 */
public final class BondedCompanionStartupPendingRecovery {
    private final BondedCompanionWorldLifecycleObserver observer;
    private final PendingLeaseSource leases;
    private final long startupCutoffMs;
    private final int maximumLeases;
    @Nullable
    private String continuationAfter;
    private boolean exhausted;

    public BondedCompanionStartupPendingRecovery(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull PendingLeaseSource leases,
            long startupCutoffMs,
            int maximumLeases
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.startupCutoffMs = startupCutoffMs;
        if (maximumLeases < 1) {
            throw new IllegalArgumentException("maximumLeases must be positive");
        }
        this.maximumLeases = maximumLeases;
    }

    /** Reconciles one cursor page and returns the number of interrupted leases settled. */
    public int tick(long observedAtMs) {
        if (exhausted) {
            return 0;
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> page = List.copyOf(
                Objects.requireNonNull(leases.pendingLeasesBefore(
                        startupCutoffMs, continuationAfter, maximumLeases
                ), "pendingLeasesBefore")
        );
        if (page.isEmpty()) {
            exhausted = true;
            return 0;
        }
        continuationAfter = page.getLast().profileId();
        observer.onStartupPending(page, observedAtMs);
        return page.size();
    }

    /** Reads only PENDING leases that existed at the startup cutoff. */
    @FunctionalInterface
    public interface PendingLeaseSource {
        @Nonnull
        List<BondedCompanionProjectionValidator.LeaseExpectation> pendingLeasesBefore(
                long startupCutoffMs,
                @Nullable String afterProfileId,
                int maximumLeases
        );
    }
}

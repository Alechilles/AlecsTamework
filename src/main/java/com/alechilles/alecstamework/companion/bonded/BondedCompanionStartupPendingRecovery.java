package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Finite startup recovery cursor for interrupted PENDING summons. Its immutable durable row
 * boundary keeps newly submitted summons out of the pass even when timestamps share a
 * millisecond, while each later maintenance tick advances one page.
 */
public final class BondedCompanionStartupPendingRecovery {
    private final BondedCompanionWorldLifecycleObserver observer;
    private final PendingLeaseSource leases;
    private final long maximumLeaseRowId;
    private final int maximumLeases;
    @Nullable
    private String continuationAfter;
    private final List<BondedCompanionProjectionValidator.LeaseExpectation> deferred =
            new ArrayList<>();
    private boolean sourceExhausted;
    private boolean exhausted;

    public BondedCompanionStartupPendingRecovery(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull PendingLeaseSource leases,
            long maximumLeaseRowId,
            int maximumLeases
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        if (maximumLeaseRowId < 0L) {
            throw new IllegalArgumentException("maximumLeaseRowId must not be negative");
        }
        this.maximumLeaseRowId = maximumLeaseRowId;
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
        PendingPage current = sourceExhausted ? deferredPage() : nextPage();
        if (!current.available()) {
            return 0;
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> page = current.leases();
        if (page.isEmpty()) {
            sourceExhausted = true;
            if (deferred.isEmpty()) {
                exhausted = true;
            }
            return 0;
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> failed =
                observer.onStartupPending(page, observedAtMs);
        if (sourceExhausted) {
            deferred.subList(0, page.size()).clear();
            deferred.addAll(failed);
        } else {
            continuationAfter = current.afterPage();
            deferred.addAll(failed);
        }
        return page.size() - failed.size();
    }

    private PendingPage nextPage() {
        try {
            List<BondedCompanionProjectionValidator.LeaseExpectation> page = List.copyOf(
                    Objects.requireNonNull(leases.pendingLeasesBefore(
                            maximumLeaseRowId,
                            continuationAfter, maximumLeases
                    ), "pendingLeasesBefore")
            );
            String afterPage = page.isEmpty() ? continuationAfter : page.getLast().profileId();
            return new PendingPage(afterPage, page, true);
        } catch (BondedCompanionLeaseEvidenceUnavailableException failure) {
            return new PendingPage(continuationAfter, List.of(), false);
        }
    }

    private PendingPage deferredPage() {
        int end = Math.min(maximumLeases, deferred.size());
        return new PendingPage(null, deferred.subList(0, end), true);
    }

    /** Reads only PENDING leases committed at or below the startup row boundary. */
    @FunctionalInterface
    public interface PendingLeaseSource {
        @Nonnull
        List<BondedCompanionProjectionValidator.LeaseExpectation> pendingLeasesBefore(
                long maximumLeaseRowId,
                @Nullable String afterProfileId,
                int maximumLeases
        );
    }

    private record PendingPage(
            @Nullable String afterPage,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            boolean available
    ) {
        private PendingPage {
            leases = List.copyOf(Objects.requireNonNull(leases, "leases"));
        }
    }
}

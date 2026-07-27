package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nullable;
import javax.annotation.Nonnull;

/**
 * Bounded maintenance entry point that reconciles leased bonded projections
 * which are missing or duplicated outside explicit lifecycle events.
 */
public final class BondedCompanionProjectionRecoverySystem {
    private static final long MAX_PENDING_SCAN_AGE_MS = 10_000L;
    private final BondedCompanionWorldLifecycleObserver observer;
    private final ActiveLeaseSource leases;
    private final PagedLeaseSource pagedLeases;
    private final AsyncScanSource scans;
    private final int maximumLeases;
    private final int maximumObservations;
    private String continuationAfter;
    private PendingScan pending;

    public BondedCompanionProjectionRecoverySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ActiveLeaseSource leases,
            int maximumLeases
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.pagedLeases = null;
        this.scans = null;
        if (maximumLeases < 1) {
            throw new IllegalArgumentException("maximumLeases must be positive");
        }
        this.maximumLeases = maximumLeases;
        this.maximumObservations = maximumLeases;
    }

    /** Creates a recovery pass that only reconciles conclusively scanned expected worlds. */
    public BondedCompanionProjectionRecoverySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull PagedLeaseSource pagedLeases,
            @Nonnull AsyncScanSource scans,
            int maximumLeases,
            int maximumObservations
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = null;
        this.pagedLeases = Objects.requireNonNull(pagedLeases, "pagedLeases");
        this.scans = Objects.requireNonNull(scans, "scans");
        if (maximumLeases < 1 || maximumObservations < 1) {
            throw new IllegalArgumentException("recovery limits must be positive");
        }
        this.maximumLeases = maximumLeases;
        this.maximumObservations = maximumObservations;
    }

    /** Reconciles at most the configured number of exact active leases. */
    public int tick(long observedAtMs) {
        if (pagedLeases != null) return tickConclusive(observedAtMs);
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

    private int tickConclusive(long observedAtMs) {
        if (pending != null) {
            return consumePendingScan(observedAtMs);
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> active = page();
        if (active.isEmpty()) {
            return 0;
        }
        return beginScan(active, observedAtMs);
    }

    private int consumePendingScan(long observedAtMs) {
        PendingScan batch = pending;
        if (batch == null) {
            return 0;
        }
        if (!batch.scan().isDone()) {
            if (observedAtMs - batch.submittedAtMs() >= MAX_PENDING_SCAN_AGE_MS) {
                pending = null;
            }
            return 0;
        }
        ScanResult result = completedResult(batch.scan());
        pending = null;
        if (result == null) {
            return 0;
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> complete = batch.leases()
                .stream().filter(result.conclusivelyScanned()::contains).toList();
        if (complete.isEmpty()) {
            return 0;
        }
        observer.onProjectionMissingScan(complete, result.observed(), observedAtMs);
        return complete.size();
    }

    private int beginScan(
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            long observedAtMs
    ) {
        CompletionStage<ScanResult> stage;
        try {
            stage = scans.scan(leases, maximumObservations);
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
        if (stage == null) {
            return 0;
        }
        try {
            pending = new PendingScan(
                    leases, stage.toCompletableFuture(), observedAtMs
            );
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
        return pending.scan().isDone() ? consumePendingScan(observedAtMs) : 0;
    }

    @Nullable
    private ScanResult completedResult(java.util.concurrent.CompletableFuture<ScanResult> scan) {
        try {
            return scan.getNow(null);
        } catch (CompletionException | java.util.concurrent.CancellationException ignored) {
            return null;
        }
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> page() {
        List<BondedCompanionProjectionValidator.LeaseExpectation> page =
                livePage(continuationAfter);
        if (page.isEmpty() && continuationAfter != null) {
            continuationAfter = null;
            page = livePage(null);
        }
        if (!page.isEmpty()) {
            continuationAfter = page.getLast().profileId();
        }
        return page;
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> livePage(
            @Nullable String afterProfileId
    ) {
        return List.copyOf(Objects.requireNonNull(
                pagedLeases.liveLeasesAfter(afterProfileId, maximumLeases),
                "liveLeasesAfter"
        ));
    }

    /** Bounded durable lease read; callers must not enumerate players. */
    @FunctionalInterface
    public interface ActiveLeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        activeLeases(int maximumLeases);
    }

    /** Cursor-based LIVE lease source that prevents a stable first page from starving later rows. */
    @FunctionalInterface
    public interface PagedLeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
        liveLeasesAfter(@Nullable String afterProfileId, int maximumLeases);
    }

    /** Bounded marker scan evidence; only conclusively scanned leases may be demoted as missing. */
    @FunctionalInterface
    public interface AsyncScanSource {
        @Nonnull java.util.concurrent.CompletionStage<ScanResult> scan(
                @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
                int maximumObservations
        );
    }

    private record PendingScan(
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            java.util.concurrent.CompletableFuture<ScanResult> scan,
            long submittedAtMs
    ) { }

    /** Immutable observations plus exact expected worlds that completed without truncation. */
    public record ScanResult(
            @Nonnull List<BondedCompanionProjectionValidator.Projection> observed,
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
                    conclusivelyScanned
    ) {
        public ScanResult {
            observed = List.copyOf(Objects.requireNonNull(observed, "observed"));
            conclusivelyScanned = List.copyOf(Objects.requireNonNull(
                    conclusivelyScanned, "conclusivelyScanned"));
        }
    }
}

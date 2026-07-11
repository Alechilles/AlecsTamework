package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Value-only population, claim-provider, reservation, and reconciliation diagnostics. */
public record PopulationDiagnosticsView(@Nonnull ReadinessView readiness,
                                        @Nonnull CountView counts,
                                        @Nonnull ReservationMetricsView ownerReservations,
                                        @Nonnull ReservationMetricsView claimReservations,
                                        @Nonnull LookupMetricsView claimLookups,
                                        @Nonnull ReconciliationView reconciliation,
                                        @Nonnull ActiveRulesView activeRules) {
    public static final long UNKNOWN = -1L;

    /** Preserves the original diagnostics constructor for existing API consumers. */
    public PopulationDiagnosticsView(@Nonnull ReadinessView readiness,
                                     @Nonnull CountView counts,
                                     @Nonnull ReservationMetricsView ownerReservations,
                                     @Nonnull ReservationMetricsView claimReservations,
                                     @Nonnull LookupMetricsView claimLookups,
                                     @Nonnull ReconciliationView reconciliation) {
        this(
                readiness,
                counts,
                ownerReservations,
                claimReservations,
                claimLookups,
                reconciliation,
                ActiveRulesView.unknown()
        );
    }

    @Nonnull
    public static PopulationDiagnosticsView unavailable() {
        ReservationMetricsView reservations = new ReservationMetricsView(0L, 0L, 0L, 0L, 0L);
        return new PopulationDiagnosticsView(
                new ReadinessView("UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE"),
                new CountView(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN),
                reservations,
                reservations,
                new LookupMetricsView(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null),
                ReconciliationView.unknown()
        );
    }

    public record ReadinessView(@Nonnull String ownerGlobal,
                                @Nonnull String ownerPerWorld,
                                @Nonnull String claimOccupancy) {
    }

    public record CountView(long trackedProfiles,
                            long committedOwnerProfiles,
                            long pendingOwnerSlots,
                            long committedClaimProfiles,
                            long pendingClaimSlots,
                            long overCapOwnerBuckets,
                            long observedOverCapClaimBuckets) {
    }

    public record ReservationMetricsView(long created,
                                         long committed,
                                         long canceled,
                                         long expired,
                                         long invalidated) {
    }

    public record LookupMetricsView(long sessions,
                                    long requests,
                                    long uniqueChunks,
                                    long providerCalls,
                                    long cacheHits,
                                    long providerStateChanges,
                                    long snapshotCount,
                                    long totalSnapshotNanos,
                                    long lastSnapshotNanos,
                                    long lastProviderCallNanos,
                                    long targetedRefreshCount,
                                    long totalTargetedRefreshNanos,
                                    long lastTargetedRefreshNanos,
                                    @Nullable ProviderContextView provider) {
        /** Preserves the original lookup-metrics constructor for existing API consumers. */
        public LookupMetricsView(long sessions,
                                 long requests,
                                 long uniqueChunks,
                                 long providerCalls,
                                 long cacheHits,
                                 long providerStateChanges,
                                 long snapshotCount,
                                 long totalSnapshotNanos,
                                 long lastSnapshotNanos,
                                 long lastProviderCallNanos,
                                 @Nullable ProviderContextView provider) {
            this(
                    sessions,
                    requests,
                    uniqueChunks,
                    providerCalls,
                    cacheHits,
                    providerStateChanges,
                    snapshotCount,
                    totalSnapshotNanos,
                    lastSnapshotNanos,
                    lastProviderCallNanos,
                    0L,
                    0L,
                    0L,
                    provider
            );
        }
    }

    /** Effective values for the operation represented by this diagnostics snapshot. */
    public record ActiveRulesView(@Nonnull String operation,
                                  int ownerLimit,
                                  @Nonnull String ownerScope,
                                  int claimLimitPerChunk,
                                  int claimLimitTotal,
                                  boolean requireClaim) {
        @Nonnull
        public static ActiveRulesView unknown() {
            return new ActiveRulesView("UNKNOWN", -1, "UNKNOWN", -1, -1, false);
        }
    }

    public record ProviderContextView(@Nullable String requestedProvider,
                                      @Nullable String resolvedProvider,
                                      @Nonnull String providerId,
                                      @Nonnull String state,
                                      @Nullable String reason,
                                      @Nullable String pluginVersion,
                                      @Nonnull String generationToken,
                                      long settingsRevision) {
    }

    public record ReconciliationView(@Nonnull String state,
                                     @Nullable String reason,
                                     long scannedUnits,
                                     long totalUnits,
                                     long profileCount,
                                     long duplicateObservations,
                                     long recoveredOperations,
                                     long canceledOperations,
                                     long startedAtMs,
                                     long completedAtMs) {
        @Nonnull
        public static ReconciliationView unknown() {
            return new ReconciliationView(
                    "UNKNOWN",
                    null,
                    UNKNOWN,
                    UNKNOWN,
                    UNKNOWN,
                    UNKNOWN,
                    UNKNOWN,
                    UNKNOWN,
                    0L,
                    0L
            );
        }
    }
}

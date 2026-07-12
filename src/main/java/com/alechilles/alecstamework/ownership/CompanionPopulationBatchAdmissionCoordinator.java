package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionEvaluation;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancySnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds exact or capacity-clamped batches from the shared combined admission authority.
 *
 * <p>Every unit's claim and owner reservation phase runs under the short mutex owned by
 * {@link CompanionPopulationAdmissionCoordinator}. The mutex is released before any SQLite
 * completion is awaited. Until all preparation futures settle, the individual reservations are
 * conservatively visible as pending capacity but no capability is exposed to the caller. Exact
 * batches roll every unit back on any denial or durability failure; up-to batches expose only one
 * stable prefix and also roll back on non-capacity failures.</p>
 */
public final class CompanionPopulationBatchAdmissionCoordinator {
    private static final int MAX_BATCH_UNITS = 1_024;

    private final CompanionPopulationAdmissionCoordinator coordinator;

    public CompanionPopulationBatchAdmissionCoordinator(
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationBatchPreparationResult> prepareAsync(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull ClaimLookupSession lookupSession,
            @Nonnull CompanionPopulationBatchMode mode
    ) {
        return prepareAsync(units, lookupSession, mode, null);
    }

    /** Reuses a sweep-scoped occupancy snapshot while reservations remain independently atomic. */
    @Nonnull
    public CompletableFuture<CompanionPopulationBatchPreparationResult> prepareAsync(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull ClaimLookupSession lookupSession,
            @Nonnull CompanionPopulationBatchMode mode,
            @Nullable ClaimOccupancySnapshot sharedSnapshot
    ) {
        List<CompanionPopulationAdmissionUnit> safeUnits = validateUnits(units, lookupSession);
        Objects.requireNonNull(mode, "mode");
        List<EvaluatedUnit> evaluations = evaluateUnits(
                safeUnits, lookupSession, sharedSnapshot
        );
        ReservationPhase phase = coordinator.withinReservationBoundary(() ->
                reservePhase(evaluations, mode)
        );
        List<CompletableFuture<CompanionPopulationPreparationResult>> futures = new ArrayList<>();
        for (CompanionPopulationReservationPreparation reservation : phase.reservations()) {
            futures.add(coordinator.prepareReservedAsync(reservation));
        }
        return settlePhase(safeUnits.size(), phase.withFutures(futures), mode);
    }

    public boolean claimForApply(@Nonnull PreparedCompanionPopulationBatch batch,
                                 int unitIndex,
                                 long currentSettingsRevision,
                                 @Nonnull ClaimLookupSession refreshedSession) {
        return coordinator.claimForApply(
                requireAdmission(batch, unitIndex),
                currentSettingsRevision,
                Objects.requireNonNull(refreshedSession, "refreshedSession")
        );
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitAsync(
            @Nonnull PreparedCompanionPopulationBatch batch,
            int unitIndex
    ) {
        return coordinator.commitAsync(requireAdmission(batch, unitIndex));
    }

    @Nonnull
    public CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedCompanionPopulationBatch batch,
            int unitIndex
    ) {
        return coordinator.completeSourceFinalizationAsync(requireAdmission(batch, unitIndex));
    }

    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedCompanionPopulationBatch batch,
                                                   int unitIndex,
                                                   @Nonnull String reason) {
        return coordinator.cancelAsync(requireAdmission(batch, unitIndex), normalizeReason(reason));
    }

    /** Cancels every still-cancelable unit and returns the number actually closed. */
    @Nonnull
    public CompletableFuture<Integer> cancelRemainingAsync(
            @Nonnull PreparedCompanionPopulationBatch batch,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(batch, "batch");
        List<PreparedCompanionPopulationAdmission> admissions = batch.admissions();
        return cancelAdmissions(admissions, normalizeReason(reason));
    }

    /** Fails later positive admissions closed after a live batch identity cannot be represented. */
    public void markReadinessDegraded(@Nonnull String reason) {
        coordinator.markReadinessDegraded(reason);
    }

    @Nonnull
    private ReservationPhase reservePhase(
            @Nonnull List<EvaluatedUnit> units,
            @Nonnull CompanionPopulationBatchMode mode
    ) {
        List<CompanionPopulationReservationPreparation> reservations = new ArrayList<>();
        CompanionPopulationPreparationResult limiting = null;
        for (EvaluatedUnit unit : units) {
            CompanionPopulationReservationPreparation reservation =
                    coordinator.reserveEvaluatedWithinReservationBoundary(
                            unit.unit().ownerPlan(),
                            unit.claimEvaluation()
                    );
            if (reservation.allowed()) {
                reservations.add(reservation);
                continue;
            }
            limiting = new CompanionPopulationPreparationResult(
                    false,
                    reservation.reason(),
                    reservation.ownerReservation() == null
                            ? null
                            : reservation.ownerReservation().decision(),
                    reservation.claimDecision(),
                    null
            );
            if (mode == CompanionPopulationBatchMode.EXACT || !isCapacityDenial(reservation.reason())) {
                break;
            }
            break;
        }
        return new ReservationPhase(List.copyOf(reservations), List.of(), limiting);
    }

    @Nonnull
    private List<EvaluatedUnit> evaluateUnits(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull ClaimLookupSession lookupSession,
            @Nullable ClaimOccupancySnapshot sharedSnapshot
    ) {
        List<EvaluatedUnit> evaluations = new ArrayList<>(units.size());
        for (CompanionPopulationAdmissionUnit unit : units) {
            evaluations.add(new EvaluatedUnit(
                    unit,
                    sharedSnapshot == null
                            ? coordinator.evaluateClaim(unit.claimRequest(), lookupSession)
                            : coordinator.evaluateClaim(
                                    unit.claimRequest(), lookupSession, sharedSnapshot
                            )
            ));
        }
        return List.copyOf(evaluations);
    }

    @Nonnull
    private CompletableFuture<CompanionPopulationBatchPreparationResult> settlePhase(
            int requestedCount,
            @Nonnull ReservationPhase phase,
            @Nonnull CompanionPopulationBatchMode mode
    ) {
        CompletableFuture<?>[] waits = phase.futures().toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).thenCompose(ignored -> {
            List<PreparedCompanionPopulationAdmission> prepared = new ArrayList<>();
            CompanionPopulationPreparationResult asyncFailure = null;
            for (CompletableFuture<CompanionPopulationPreparationResult> future : phase.futures()) {
                CompanionPopulationPreparationResult result = future.join();
                if (result.allowed() && result.preparedAdmission() != null) {
                    prepared.add(result.preparedAdmission());
                } else if (asyncFailure == null) {
                    asyncFailure = result;
                }
            }
            if (asyncFailure != null) {
                CompanionPopulationPreparationResult finalFailure = asyncFailure;
                return cancelAdmissions(prepared, "companion-population-batch-prepare-failed")
                        .thenApply(canceled -> denied(requestedCount, finalFailure));
            }
            CompanionPopulationPreparationResult limiting = phase.limiting();
            boolean capacityClamp = mode == CompanionPopulationBatchMode.UP_TO
                    && limiting != null
                    && isCapacityDenial(limiting.reason())
                    && !prepared.isEmpty();
            if (limiting != null && !capacityClamp) {
                CompanionPopulationPreparationResult finalFailure = limiting;
                return cancelAdmissions(prepared, "companion-population-batch-reservation-denied")
                        .thenApply(canceled -> denied(requestedCount, finalFailure));
            }
            if (prepared.isEmpty()) {
                return CompletableFuture.completedFuture(denied(requestedCount, limiting));
            }
            PreparedCompanionPopulationBatch batch = new PreparedCompanionPopulationBatch(
                    UUID.randomUUID(),
                    requestedCount,
                    prepared
            );
            boolean clamped = prepared.size() < requestedCount;
            return CompletableFuture.completedFuture(new CompanionPopulationBatchPreparationResult(
                    true,
                    clamped ? "companion-population-batch-clamped" : "companion-population-batch-prepared",
                    requestedCount,
                    prepared.size(),
                    clamped ? limiting : null,
                    batch
            ));
        }).exceptionallyCompose(failure ->
                cancelCompletedAdmissions(phase.futures(), "companion-population-batch-exception")
                        .thenApply(canceled -> new CompanionPopulationBatchPreparationResult(
                                false,
                                "companion-population-batch-prepare-failed",
                                requestedCount,
                                0,
                                null,
                                null
                        ))
        );
    }

    @Nonnull
    private static CompanionPopulationBatchPreparationResult denied(
            int requestedCount,
            @Nullable CompanionPopulationPreparationResult failure
    ) {
        return new CompanionPopulationBatchPreparationResult(
                false,
                failure == null ? "companion-population-batch-denied" : failure.reason(),
                requestedCount,
                0,
                failure,
                null
        );
    }

    @Nonnull
    private CompletableFuture<Integer> cancelCompletedAdmissions(
            @Nonnull List<CompletableFuture<CompanionPopulationPreparationResult>> futures,
            @Nonnull String reason
    ) {
        List<PreparedCompanionPopulationAdmission> prepared = new ArrayList<>();
        for (CompletableFuture<CompanionPopulationPreparationResult> future : futures) {
            if (!future.isDone() || future.isCompletedExceptionally()) {
                continue;
            }
            CompanionPopulationPreparationResult result = future.getNow(null);
            if (result != null && result.preparedAdmission() != null) {
                prepared.add(result.preparedAdmission());
            }
        }
        return cancelAdmissions(prepared, reason);
    }

    @Nonnull
    private CompletableFuture<Integer> cancelAdmissions(
            @Nonnull List<PreparedCompanionPopulationAdmission> admissions,
            @Nonnull String reason
    ) {
        List<CompletableFuture<Boolean>> cancellations = new ArrayList<>();
        for (PreparedCompanionPopulationAdmission admission : admissions) {
            cancellations.add(coordinator.cancelAsync(admission, reason));
        }
        CompletableFuture<?>[] waits = cancellations.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).thenApply(ignored -> {
            int canceled = 0;
            for (CompletableFuture<Boolean> cancellation : cancellations) {
                if (Boolean.TRUE.equals(cancellation.getNow(false))) {
                    canceled++;
                }
            }
            return canceled;
        });
    }

    @Nonnull
    private static List<CompanionPopulationAdmissionUnit> validateUnits(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull ClaimLookupSession lookupSession
    ) {
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(lookupSession, "lookupSession");
        if (units.isEmpty() || units.size() > MAX_BATCH_UNITS) {
            throw new IllegalArgumentException("Batch size must be between 1 and " + MAX_BATCH_UNITS + ".");
        }
        List<CompanionPopulationAdmissionUnit> copy = List.copyOf(units);
        for (CompanionPopulationAdmissionUnit unit : copy) {
            Objects.requireNonNull(unit, "units cannot contain null");
            if (!unit.claimRequest().policyContext().equals(lookupSession.context())) {
                throw new IllegalArgumentException("Every batch unit must use the shared lookup policy context.");
            }
            if (unit.ownerPlan().settingsRevision() != lookupSession.context().settingsRevision()
                    || !unit.ownerPlan().providerGeneration().equals(
                            lookupSession.context().providerGeneration()
                    )) {
                throw new IllegalArgumentException("Owner and claim batch contexts must match.");
            }
        }
        return copy;
    }

    @Nonnull
    private static PreparedCompanionPopulationAdmission requireAdmission(
            @Nonnull PreparedCompanionPopulationBatch batch,
            int unitIndex
    ) {
        Objects.requireNonNull(batch, "batch");
        if (unitIndex < 0 || unitIndex >= batch.admittedCount()) {
            throw new IndexOutOfBoundsException("Population batch unit index is outside the admitted prefix.");
        }
        return batch.admission(unitIndex);
    }

    private static boolean isCapacityDenial(@Nullable String reason) {
        return "owner-cap-reached".equals(reason) || "claim-cap-reached".equals(reason);
    }

    @Nonnull
    private static String normalizeReason(@Nullable String reason) {
        return reason == null || reason.isBlank()
                ? "companion-population-batch-canceled"
                : reason.trim();
    }

    private record ReservationPhase(
            @Nonnull List<CompanionPopulationReservationPreparation> reservations,
            @Nonnull List<CompletableFuture<CompanionPopulationPreparationResult>> futures,
            @Nullable CompanionPopulationPreparationResult limiting
    ) {
        @Nonnull
        ReservationPhase withFutures(
                @Nonnull List<CompletableFuture<CompanionPopulationPreparationResult>> preparedFutures
        ) {
            return new ReservationPhase(reservations, List.copyOf(preparedFutures), limiting);
        }
    }

    private record EvaluatedUnit(
            @Nonnull CompanionPopulationAdmissionUnit unit,
            @Nonnull ClaimAdmissionEvaluation claimEvaluation
    ) {
    }
}

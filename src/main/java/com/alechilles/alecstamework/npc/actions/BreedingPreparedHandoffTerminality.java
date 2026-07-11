package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Exactly-once owner for a prepared litter until spawn-loop ownership is accepted. */
final class BreedingPreparedHandoffTerminality {
    private final Cancellation populationCancellation;
    private final Runnable beforeCancellation;
    private final Runnable nearbyRelease;
    private final Runnable pairClose;
    private final Consumer<String> warning;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    BreedingPreparedHandoffTerminality(
            @Nonnull BreedingPopulationAdmissionService populationService,
            @Nonnull PreparedBreedingPopulationBatch populationBatch,
            @Nonnull BreedingNearbyReservationService nearbyService,
            @Nonnull BreedingNearbyReservationService.Reservation nearbyReservation,
            @Nonnull BreedingPairAdmissionRegistry pairRegistry,
            @Nonnull BreedingPairAdmissionRegistry.Token pairToken,
            @Nonnull Runnable beforeCancellation,
            @Nonnull Consumer<String> warning
    ) {
        this(
                reason -> populationService.cancelRemainingAsync(populationBatch, reason),
                beforeCancellation,
                () -> nearbyService.releaseFrom(nearbyReservation, 0),
                () -> pairRegistry.cancel(pairToken),
                warning
        );
    }

    BreedingPreparedHandoffTerminality(@Nonnull Cancellation populationCancellation,
                                       @Nonnull Runnable nearbyRelease,
                                       @Nonnull Runnable pairClose,
                                       @Nonnull Consumer<String> warning) {
        this(populationCancellation, () -> { }, nearbyRelease, pairClose, warning);
    }

    BreedingPreparedHandoffTerminality(@Nonnull Cancellation populationCancellation,
                                       @Nonnull Runnable beforeCancellation,
                                       @Nonnull Runnable nearbyRelease,
                                       @Nonnull Runnable pairClose,
                                       @Nonnull Consumer<String> warning) {
        this.populationCancellation = Objects.requireNonNull(
                populationCancellation, "populationCancellation"
        );
        this.beforeCancellation = Objects.requireNonNull(beforeCancellation, "beforeCancellation");
        this.nearbyRelease = Objects.requireNonNull(nearbyRelease, "nearbyRelease");
        this.pairClose = Objects.requireNonNull(pairClose, "pairClose");
        this.warning = Objects.requireNonNull(warning, "warning");
    }

    /** Independently attempts every cleanup action once, even when an earlier action throws. */
    void cancel(@Nonnull String reason) {
        if (!state.compareAndSet(State.OPEN, State.CANCELED)) {
            return;
        }
        runSafely(beforeCancellation, "parent completion", reason);
        runSafely(() -> populationCancellation.cancel(reason), "population batch", reason);
        runSafely(nearbyRelease, "nearby reservation", reason);
        runSafely(pairClose, "pair token", reason);
    }

    /** Transfers terminal ownership to the per-unit spawn loop exactly once. */
    boolean transferToSpawn() {
        return state.compareAndSet(State.OPEN, State.TRANSFERRED);
    }

    private void runSafely(Runnable action, String resource, String reason) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError failure) {
            try {
                warning.accept("Breeding cleanup failed for " + resource + " reason=" + reason + ".");
            } catch (RuntimeException | LinkageError ignored) {
                // Cleanup isolation must not depend on diagnostics.
            }
        }
    }

    @FunctionalInterface
    interface Cancellation {
        void cancel(@Nonnull String reason);
    }

    private enum State {
        OPEN,
        CANCELED,
        TRANSFERRED
    }
}

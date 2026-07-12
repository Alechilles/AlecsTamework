package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.BreedingPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the exception-safe transition from async litter preparation to world-thread finalization. */
final class BreedingPreparedPairingHandoffService {
    private final BreedingNearbyReservationService nearbyService;
    private final BreedingPairAdmissionRegistry pairRegistry;
    private final Consumer<String> warning;
    private final Consumer<String> info;

    BreedingPreparedPairingHandoffService(
            @Nonnull BreedingNearbyReservationService nearbyService,
            @Nonnull BreedingPairAdmissionRegistry pairRegistry,
            @Nonnull Consumer<String> warning,
            @Nonnull Consumer<String> info
    ) {
        this.nearbyService = Objects.requireNonNull(nearbyService, "nearbyService");
        this.pairRegistry = Objects.requireNonNull(pairRegistry, "pairRegistry");
        this.warning = Objects.requireNonNull(warning, "warning");
        this.info = Objects.requireNonNull(info, "info");
    }

    void dispatch(@Nonnull World world,
                  @Nonnull BreedingPopulationAdmissionService populationService,
                  @Nonnull BreedingPairAdmissionRegistry.Token pairToken,
                  @Nonnull BreedingNearbyReservationService.Reservation nearbyReservation,
                  @Nullable BreedingPopulationPreparationResult result,
                  @Nullable Throwable failure,
                  @Nonnull Runnable abortCompletion,
                  @Nonnull Finalizer finalizer) {
        PreparedBreedingPopulationBatch batch = result == null ? null : result.preparedBatch();
        if (failure != null || result == null || !result.allowed() || batch == null) {
            if (batch == null) {
                runSafely(abortCompletion);
                releaseUnprepared(nearbyReservation, pairToken);
            } else {
                terminality(
                        populationService, batch, nearbyReservation, pairToken, abortCompletion
                )
                        .cancel("breeding-prepare-callback-failed");
            }
            info.accept("Breeding pairing blocked by population admission: reason="
                    + (result == null ? "prepare-failed" : result.reason())
                    + " parentA=" + pairToken.parentA() + " parentB=" + pairToken.parentB() + ".");
            return;
        }
        BreedingPreparedHandoffTerminality terminality = terminality(
                populationService, batch, nearbyReservation, pairToken, abortCompletion
        );
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> {
                    try {
                        finalizer.finalize(batch, terminality);
                    } catch (RuntimeException | LinkageError exception) {
                        terminality.cancel("breeding-world-finalization-failed");
                    }
                },
                () -> terminality.cancel("breeding-world-unavailable")
        );
    }

    void releaseUnprepared(
            @Nonnull BreedingNearbyReservationService.Reservation nearbyReservation,
            @Nonnull BreedingPairAdmissionRegistry.Token pairToken
    ) {
        runSafely(() -> nearbyService.releaseFrom(nearbyReservation, 0));
        runSafely(() -> pairRegistry.cancel(pairToken));
    }

    @Nonnull
    private BreedingPreparedHandoffTerminality terminality(
            BreedingPopulationAdmissionService populationService,
            PreparedBreedingPopulationBatch batch,
            BreedingNearbyReservationService.Reservation nearbyReservation,
            BreedingPairAdmissionRegistry.Token pairToken,
            Runnable abortCompletion
    ) {
        return new BreedingPreparedHandoffTerminality(
                populationService,
                batch,
                nearbyService,
                nearbyReservation,
                pairRegistry,
                pairToken,
                abortCompletion,
                warning
        );
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Both reservation types retain bounded lease fallbacks.
        }
    }

    @FunctionalInterface
    interface Finalizer {
        void finalize(@Nonnull PreparedBreedingPopulationBatch batch,
                      @Nonnull BreedingPreparedHandoffTerminality terminality);
    }
}

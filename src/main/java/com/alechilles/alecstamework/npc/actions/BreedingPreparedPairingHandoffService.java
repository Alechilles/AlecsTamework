package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.alechilles.alecstamework.npc.breeding.BreedingParentIdentity;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionRequest;
import com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.BreedingPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the exception-safe asynchronous handoff from shared population preparation to a job. */
final class BreedingPreparedPairingHandoffService {
    private final TameworkBreedingServices services;
    private final Consumer<String> warning;
    private final Consumer<String> info;

    BreedingPreparedPairingHandoffService(@Nonnull TameworkBreedingServices services,
                                          @Nonnull Consumer<String> warning,
                                          @Nonnull Consumer<String> info) {
        this.services = Objects.requireNonNull(services, "services");
        this.warning = Objects.requireNonNull(warning, "warning");
        this.info = Objects.requireNonNull(info, "info");
    }

    void prepareAndDispatch(
            @Nonnull World world,
            @Nonnull Object storeScope,
            @Nonnull UUID jobId,
            @Nonnull BreedingParentIdentity firstParent,
            @Nonnull BreedingParentIdentity secondParent,
            @Nonnull BreedingPopulationAdmissionService populationService,
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nullable BreedingPopulationAdmissionService.PreparationContext preparationContext,
            @Nonnull Finalizer finalizer) {
        if (!beginPreparation(storeScope, jobId, firstParent, secondParent)) {
            cancelLocal(storeScope, jobId);
            return;
        }
        CompletableFuture<BreedingPopulationPreparationResult> completion;
        try {
            completion = populationService.prepareAsync(request, preparationContext);
        } catch (RuntimeException | LinkageError failure) {
            failPreparation(storeScope, jobId);
            cancelLocal(storeScope, jobId);
            warn("Breeding population preparation could not start", failure);
            return;
        }
        if (completion == null) {
            failPreparation(storeScope, jobId);
            cancelLocal(storeScope, jobId);
            warning.accept("Breeding population preparation returned no completion stage.");
            return;
        }
        completion.whenComplete((result, failure) -> dispatchPrepared(
                world, storeScope, jobId, populationService, result, failure, finalizer
        ));
    }

    private void dispatchPrepared(
            World world,
            Object storeScope,
            UUID jobId,
            BreedingPopulationAdmissionService populationService,
            BreedingPopulationPreparationResult result,
            Throwable failure,
            Finalizer finalizer) {
        PreparedBreedingPopulationBatch batch = result == null ? null : result.preparedBatch();
        if (batch != null) {
            try {
                services.preparedPopulationRegistry().install(
                        storeScope, jobId, populationService, batch
                );
            } catch (RuntimeException | LinkageError registrationFailure) {
                failPreparation(storeScope, jobId);
                cancelPrepared(
                        populationService, batch, "breeding-population-registration-failed"
                );
                cancelLocal(storeScope, jobId);
                warn("Breeding prepared capability registration failed", registrationFailure);
                return;
            }
        }
        if (!finishPreparation(storeScope, jobId)) {
            cancelRegisteredOrCandidate(
                    storeScope,
                    jobId,
                    populationService,
                    batch,
                    "breeding-population-preparation-gate-failed"
            );
            cancelLocal(storeScope, jobId);
            return;
        }
        if (failure != null || result == null || !result.allowed() || batch == null) {
            cancelRegisteredOrCandidate(
                    storeScope,
                    jobId,
                    populationService,
                    batch,
                    "breeding-population-prepare-denied"
            );
            cancelLocal(storeScope, jobId);
            info.accept("Breeding pairing blocked by shared population admission: job="
                    + jobId + " reason=" + reason(result, failure) + ".");
            return;
        }
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> finalizeOnWorld(
                        storeScope, jobId, populationService, batch, finalizer
                ),
                () -> {
                    cancelRegisteredOrCandidate(
                            storeScope,
                            jobId,
                            populationService,
                            batch,
                            "breeding-population-world-unavailable"
                    );
                    cancelLocal(storeScope, jobId);
                }
        );
    }

    private void finalizeOnWorld(
            Object storeScope,
            UUID jobId,
            BreedingPopulationAdmissionService populationService,
            PreparedBreedingPopulationBatch batch,
            Finalizer finalizer) {
        boolean accepted = false;
        try {
            accepted = finalizer.finalize(batch);
        } catch (RuntimeException | LinkageError failure) {
            warn("Breeding prepared population finalization failed", failure);
        }
        if (accepted) {
            return;
        }
        String reason = "breeding-population-world-finalization-rejected";
        boolean registryOwned = services.preparedPopulationRegistry().cancelOwnedJob(
                storeScope, jobId, populationService, batch, reason
        );
        if (!registryOwned) {
            cancelPrepared(populationService, batch, reason);
        }
        cancelLocal(storeScope, jobId);
    }

    private void cancelLocal(Object storeScope, UUID jobId) {
        try {
            services.jobRegistry().cancel(storeScope, jobId);
        } catch (RuntimeException | LinkageError ignored) {
            // A closed scope already released its nearby reservation.
        }
    }

    private boolean beginPreparation(Object storeScope,
                                     UUID jobId,
                                     BreedingParentIdentity firstParent,
                                     BreedingParentIdentity secondParent) {
        try {
            return services.preparedPopulationRegistry().beginPreparation(
                    storeScope, jobId, firstParent, secondParent
            );
        } catch (RuntimeException | LinkageError failure) {
            warn("Breeding preparation durability gate could not open", failure);
            return false;
        }
    }

    private boolean finishPreparation(Object storeScope, UUID jobId) {
        try {
            boolean finished = services.preparedPopulationRegistry().finishPreparation(
                    storeScope, jobId
            );
            if (finished) {
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            warn("Breeding preparation durability gate could not close", failure);
        }
        failPreparation(storeScope, jobId);
        return false;
    }

    private void failPreparation(Object storeScope, UUID jobId) {
        try {
            services.preparedPopulationRegistry().failPreparation(storeScope, jobId);
        } catch (RuntimeException | LinkageError failure) {
            warn("Breeding preparation durability gate failed closed", failure);
        }
    }

    private void cancelRegisteredOrCandidate(
            Object storeScope,
            UUID jobId,
            BreedingPopulationAdmissionService populationService,
            @Nullable PreparedBreedingPopulationBatch batch,
            String reason) {
        if (batch == null) {
            return;
        }
        boolean registryOwned = services.preparedPopulationRegistry().cancelOwnedJob(
                storeScope, jobId, populationService, batch, reason
        );
        if (!registryOwned) {
            cancelPrepared(populationService, batch, reason);
        }
    }

    private void cancelPrepared(BreedingPopulationAdmissionService populationService,
                                @Nullable PreparedBreedingPopulationBatch batch,
                                String reason) {
        if (batch == null) {
            return;
        }
        try {
            CompletableFuture<Integer> completion =
                    populationService.cancelRemainingAsync(batch, reason);
            if (completion != null) {
                completion.exceptionally(failure -> {
                    populationService.markReadinessDegraded(
                            "breeding_population_handoff_cancel_failed"
                    );
                    return 0;
                });
            } else {
                populationService.markReadinessDegraded(
                        "breeding_population_handoff_cancel_missing"
                );
            }
        } catch (RuntimeException | LinkageError failure) {
            populationService.markReadinessDegraded(
                    "breeding_population_handoff_cancel_start_failed"
            );
        }
    }

    private static String reason(BreedingPopulationPreparationResult result, Throwable failure) {
        if (failure != null) {
            return "prepare-error:" + failure.getClass().getSimpleName();
        }
        return result == null ? "prepare-result-missing" : result.reason();
    }

    private void warn(String message, Throwable failure) {
        try {
            warning.accept(message + ": " + failure.getClass().getSimpleName());
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must not strand the prepared batch.
        }
    }

    @FunctionalInterface
    interface Finalizer {
        boolean finalize(@Nonnull PreparedBreedingPopulationBatch batch);
    }
}
